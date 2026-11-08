import 'dart:convert';
import 'dart:typed_data';
import 'package:watermark_engine/watermark_engine.dart';
import '../../editor/xiaomi_capabilities.dart';
import '../../editor/region_replacements.dart';
import '../watermark_package.dart';
import '../watermark_repository.dart';
import '../watermark_style.dart';
import 'contract.dart';
import 'export.dart';
import 'font_file.dart';
import 'package_validation.dart';
import 'share_image.dart';

/// Converts a native tree without flattening it or serializing an EditDocument.
/// Only the explicit design allowlist below can enter shared defaults.
Future<WatermarkPackage> migrateStaruwNative(
  StylePreset style,
  WatermarkRepository repository,
  Map<String, Uint8List> original,
  Map<String, Uint8List> collected,
  Map<String, dynamic> resourceBindings,
  String preview, {
  String previewKind = 'oem',
}) async {
  final doc = style.document;
  final source = Map<String, Uint8List>.of(original);
  final root = Map<String, dynamic>.from(
    staruwJson(source['config.json']!) as Map,
  );
  staruwNeed(
    root['layout_group'] is List && root['wmRes'] is Map,
    'E_FORMAT',
    '缺少原厂布局',
  );
  final removed = <String>[];
  final legacy = root['starmark'] as Map?;
  if (legacy != null) {
    final known = const EditDocument().toJson().keys.toSet();
    staruwNeed(
      legacy.keys.every(known.contains),
      'E_BINDING',
      '旧模板包含未识别设计字段，原包已保留',
    );
    removed.addAll(legacy.keys.map((k) => '/starmark/$k'));
    root.remove('starmark');
    root.remove('portable');
    // Recovery input remains private; shared provenance contains design only.
    final metadata = Map<String, dynamic>.from(root['metadata'] as Map? ?? {});
    metadata.remove('localId');
    metadata.remove('displayName');
    final oldPreview = metadata.remove('preview');
    if (oldPreview is String && oldPreview != preview) {
      final rawTree = jsonEncode({
        'layout_group': root['layout_group'],
        'wmRes': root['wmRes'],
      });
      if (!rawTree.contains(oldPreview)) {
        source.remove(oldPreview);
        collected.remove(oldPreview);
        removed.add('/$oldPreview');
      }
    }
    root['metadata'] = metadata;
    source['config.json'] = Uint8List.fromList(utf8.encode(jsonEncode(root)));
    source.remove('manifest.json');
    source.remove('meta.json');
    removed.addAll(['/manifest.json', '/meta.json']);
    for (final path in source.keys.toList()) {
      final clean = await staruwShareImage(source[path]!);
      if (!identical(clean, source[path])) {
        source[path] = clean;
        removed.add('/$path#camera-metadata');
      }
    }
    for (final path in collected.keys.toList()) {
      collected[path] = await staruwShareImage(collected[path]!);
    }
  }
  staruwNeed(
    !source.keys.any((p) => p.startsWith('source/xiaomi/')),
    'E_PATH',
    '原包已占用保留目录，不能无歧义转换',
  );
  final files = Map<String, Uint8List>.of(collected)
    ..remove('manifest.json')
    ..remove('meta.json');
  // Do not let collected thumbnails or aliases overwrite preserved source bytes.
  final sourceRows = <Map<String, dynamic>>[];
  for (final e in source.entries) {
    final path = ['config.json', 'manifest.json'].contains(e.key)
        ? 'source/xiaomi/${e.key}'
        : e.key;
    files[path] = e.value;
    sourceRows.add({'originalPath': e.key, 'path': path});
  }
  var largeOem = false;
  final resources = <String, dynamic>{}, byPath = <String, String>{};
  String resource(String path) {
    if (byPath[path] case final String id) return id;
    final bytes = files[path];
    staruwNeed(bytes != null, 'E_RESOURCE', '缺少可携带依赖：$path');
    final ext = path.split('.').last.toLowerCase();
    var mime = {
      'png': 'image/png',
      'jpg': 'image/jpeg',
      'jpeg': 'image/jpeg',
      'webp': 'image/webp',
      'svg': 'image/svg+xml',
      'ttf': 'font/ttf',
      'otf': 'font/otf',
      'json': 'application/json',
      'txt': 'text/plain',
      'md': 'text/plain',
    }[ext];
    if (mime?.startsWith('image/') == true &&
        mime != 'image/svg+xml' &&
        bytes!.length >= 12) {
      if (bytes[0] == 137 &&
          bytes[1] == 80 &&
          bytes[2] == 78 &&
          bytes[3] == 71) {
        mime = 'image/png';
      } else if (bytes[0] == 255 && bytes[1] == 216) {
        mime = 'image/jpeg';
      } else if (String.fromCharCodes(bytes.sublist(0, 4)) == 'RIFF' &&
          String.fromCharCodes(bytes.sublist(8, 12)) == 'WEBP') {
        mime = 'image/webp';
      }
    }
    if (mime?.startsWith('font/') == true) {
      mime = String.fromCharCodes(bytes!.take(4)) == 'OTTO'
          ? 'font/otf'
          : 'font/ttf';
    }
    staruwNeed(mime != null, 'E_RESOURCE', '依赖类型未登记：$path');
    if (mime!.startsWith('image/') && mime != 'image/svg+xml') {
      final size = staruwImageSize(bytes!, mime, path);
      if (size.width * size.height > 16000000) {
        staruwNeed(source.containsKey(path), 'E_LIMIT', '自定义图片超过协议预算');
        largeOem = true;
      }
    }
    final kind = mime.startsWith('image/')
        ? 'image'
        : mime.startsWith('font/')
        ? 'font'
        : mime == 'text/plain'
        ? 'license'
        : 'data';
    final id = 'r${resources.length}';
    byPath[path] = id;
    resources[id] = {
      'path': path,
      'kind': kind,
      'mediaType': mime,
      'sha256': staruwHash(bytes!),
    };
    return id;
  }

  for (final path in files.keys) {
    if (path == 'config.json' ||
        path.startsWith('source/') ||
        path == 'i18n.json') {
      continue;
    }
    if (RegExp(
      r'\.(png|jpg|jpeg|webp|svg|ttf|otf|json|txt|md)$',
      caseSensitive: false,
    ).hasMatch(path)) {
      resource(path);
    }
  }
  final fonts = <String, dynamic>{},
      fontPaths = resourceBindings['fonts'] as Map;
  String addFont(String id, String path, Map axes) {
    final sfnt = StaruwFontFile(files[path]!);
    sfnt.validateAxes(axes);
    fonts[id] = {
      'resourceId': resource(path),
      'axes': axes,
      'fallbackFontIds': <String>[],
    };
    return id;
  }

  for (final e in fontPaths.entries) {
    final path = e.value as String, sfnt = StaruwFontFile(files[path]!);
    final axes = <String, num>{};
    final spec = bundledFonts.where((f) => f.id == e.key).firstOrNull;
    if (spec != null) axes.addAll(spec.axes);
    for (final axis in axes.keys.toList()) {
      if (!sfnt.axes.containsKey(axis)) axes.remove(axis);
    }
    addFont(e.key as String, path, axes);
  }
  staruwNeed(
    ['mi-sans', 'oxanium', 'lg1056'].every(fonts.containsKey),
    'E_RESOURCE',
    '缺少自动字体策略依赖',
  );
  for (final e in fonts.entries) {
    if (e.key != 'mi-sans') e.value['fallbackFontIds'] = ['mi-sans'];
  }
  final originalFonts = <String, String>{};
  final nativeFonts = root['wmRes']['fonts'] as Map? ?? {};
  for (final e in nativeFonts.entries) {
    final def = e.value as Map,
        typeface = def['typeface'] as String? ?? 'default';
    final path = typeface.startsWith('@file=')
        ? typeface.substring(6)
        : fontPaths[typeface == 'default' ? 'mi-sans' : typeface] as String?;
    staruwNeed(path != null, 'E_RESOURCE', '原厂字体别名未解析');
    final axes = <String, num>{};
    for (final m in RegExp(
      r"'([^']{4})'\s+(-?[\d.]+)",
    ).allMatches('${def['variation'] ?? ''}')) {
      axes[m[1]!] = double.parse(m[2]!);
    }
    if (typeface == 'default') axes.putIfAbsent('wght', () => 330);
    if (typeface == 'oxanium') {
      axes['wght'] = [400, 700].contains(axes['wght']) ? axes['wght']! : 500;
    }
    if (StaruwFontFile(files[path]!).axes.isEmpty) axes.clear();
    final id = 'original${originalFonts.length}';
    originalFonts[e.key as String] = id;
    addFont(id, path!, axes);
    if (path != fontPaths['mi-sans']) {
      fonts[id]['fallbackFontIds'] = ['mi-sans'];
    }
  }
  final bindings = <Map<String, dynamic>>[],
      controls = <Map<String, dynamic>>[],
      defaults = <String, dynamic>{};
  void bind(
    String pointer,
    Map node,
    String semantic,
    String operation,
    Map<String, dynamic> control,
    dynamic value, {
    List? when,
    List? variants,
    Map? replacementStyle,
  }) {
    final id = 'b${bindings.length}';
    bindings.add({
      'id': id,
      'sourcePointer': pointer,
      'nodeType': node['type'],
      'property': semantic,
      'operations': [operation],
      'variants': ?variants,
      'replacementStyle': ?replacementStyle,
    });
    final cid = 'c${controls.length}';
    controls.add({
      'id': cid,
      'scope': 'template',
      ...control,
      'targets': [
        {'kind': 'xiaomiBinding', 'id': id, 'property': operation},
      ],
      'when': ?when,
    });
    if (control['scope'] != 'photo') defaults[cid] = value;
  }

  final groups = root['layout_group'] as List;
  final layouts = <Map<String, dynamic>>[];
  for (var g = 0; g < groups.length; g++) {
    for (final e in (groups[g] as Map).entries) {
      if (e.value is List) {
        layouts.add({
          'id': 'layout${layouts.length}',
          'selection': {'kind': 'layout', 'groupIndex': g, 'key': e.key},
        });
      }
    }
  }
  final selected = layouts
      .where(
        (v) =>
            v['selection']['groupIndex'] ==
                (doc.xiaomiOptions['layoutGroup'] ?? 0) &&
            (doc.xiaomiOptions['layout'] == null ||
                v['selection']['key'] == doc.xiaomiOptions['layout']),
      )
      .firstOrNull;
  staruwNeed(selected != null, 'E_BINDING', '旧模板选择的原厂布局已不存在');
  bind(
    '/layout_group',
    {'type': 'LayoutGroup'},
    'layout',
    'selectVariant',
    {
      'label': '布局',
      'type': 'choice',
      'stage': 'layout',
      'options': [
        for (final v in layouts)
          {'id': v['id'], 'label': v['selection']['key'], 'value': v['id']},
      ],
    },
    selected!['id'],
    variants: layouts,
  );
  Map regionValue(String name) {
    final value = doc.regionReplacements[name] as Map?;
    if (value == null || value['source'] == 'original') {
      return {'mode': 'native'};
    }
    if (value['source'] == 'text') {
      return {
        'mode': 'text',
        'text': value['text'] ?? '',
        'fontId': doc.font.automatic ? 'oxanium' : doc.font.id,
      };
    }
    final path = (resourceBindings['regions'] as Map)[value['asset']];
    staruwNeed(path is String, 'E_RESOURCE', '替换图片尚未复制');
    return {
      'mode': 'image',
      'resourceId': resource(path),
      if (name == 'model')
        'fit': 'contain'
      else
        'shape': value['shape'] == 'circle' ? 'circle' : 'rect',
    };
  }

  for (final layout in layouts) {
    final selection = layout['selection'] as Map,
        g = selection['groupIndex'],
        key = selection['key'];
    final when = [
      {'controlId': 'c0', 'equals': layout['id']},
    ];
    final caps = XiaomiCapabilities.fromConfig(root, {
      'layoutGroup': g,
      'layout': key,
    }, files.keys);
    var backgroundAdded = false;
    void visit(List nodes, String path) {
      for (var i = 0; i < nodes.length; i++) {
        final node = nodes[i] as Map,
            pointer = '$path/$i',
            id = '${node['id']}';
        if (node['enable'] == false) continue;
        if (node['optional'] == true) {
          final text = '${node['text'] ?? ''}', kind = xiaomiTextBinding(text);
          final semantic = node['setting_optional_type'];
          final visible = (kind == 'model' || semantic == 'type_model')
              ? doc.showModel
              : kind == 'exif'
              ? doc.showParameters
              : kind == 'time'
              ? doc.xiaomiOptions['showTime'] != false
              : kind == 'location'
              ? doc.xiaomiOptions['showLocation'] != false
              : ['logo', 'leica', 'brand_leica'].contains(id)
              ? doc.showBrand
              : true;
          bind(
            pointer,
            node,
            'visible',
            'visible',
            {
              'label':
                  {
                    'model': '机型',
                    'exif': '拍摄参数',
                    'time': '拍摄时间',
                    'location': '拍摄位置',
                  }[kind] ??
                  id,
              'type': 'toggle',
            },
            visible,
            when: when,
          );
        }
        if (node['support_scale'] == true) {
          bind(
            pointer,
            node,
            'scale',
            'scalePreset',
            {
              'label': '$id · 尺寸',
              'type': 'choice',
              'options': [
                for (final size in ['small', 'medium', 'large'])
                  {
                    'id': size,
                    'label': {'small': '小', 'medium': '标准', 'large': '大'}[size],
                    'value': size,
                  },
              ],
            },
            doc.scale == .85
                ? 'small'
                : doc.scale == 1.15
                ? 'large'
                : 'medium',
            when: when,
          );
        }
        if (node['type'] == 'TextView') {
          final originalId = originalFonts[node['font_ref']];
          if (originalId != null) {
            bind(
              pointer,
              node,
              'font',
              'font',
              {
                'label': '$id · 字体',
                'type': 'choice',
                'options': [
                  {'id': originalId, 'label': '原厂字体', 'value': originalId},
                  for (final f in fontPaths.keys)
                    {
                      'id': f,
                      'label':
                          bundledFonts
                              .where((v) => v.id == f)
                              .firstOrNull
                              ?.title ??
                          f,
                      'value': f,
                    },
                ],
              },
              doc.font.automatic ? originalId : doc.font.id,
              when: when,
            );
          }
        }
        if (isModelRun(node) ||
            node['type'] == 'ImageView' && id == 'img_model') {
          bind(
            pointer,
            node,
            'model',
            'modelRegion',
            {
              'label': '机型区域',
              'type': 'region',
              'allowModes': ['native', 'text', 'image'],
              'allowTypes': ['image/png', 'image/jpeg', 'image/svg+xml'],
              'allowFits': ['contain', 'cover'],
            },
            regionValue('model'),
            when: when,
            replacementStyle: {
              'fontId': 'oxanium',
              'textStyle': {
                'size': 28,
                'lineHeight': 36,
                'minSize': 1,
                'color': '#FFFFFFFF',
                'align': 'center',
                'verticalAlign': 'center',
                'overflow': 'shrink',
                'fontFit': {
                  'referenceFontId': 'oxanium',
                  'referenceAxes': {'wght': 500},
                  'referenceSize': 28,
                  'probe': 'H0123456789',
                },
              },
            },
          );
        }
        if (isParameterEmblem(node)) {
          bind(
            pointer,
            node,
            'parameterIcon',
            'parameterIcon',
            {
              'label': '参数图标',
              'type': 'image',
              'allowTypes': ['image/png', 'image/jpeg', 'image/svg+xml'],
              'allowShapes': ['rect', 'circle'],
            },
            regionValue('parameters'),
            when: when,
          );
        }
        if (node['background_optional'] == true &&
            !backgroundAdded &&
            caps.backgroundChoices.length > 1) {
          backgroundAdded = true;
          final variants = [
            for (final v in caps.backgroundChoices)
              {
                'id': v['name'],
                'selection': {'kind': 'option', 'key': v['name']},
              },
          ];
          bind(
            pointer,
            node,
            'background',
            'selectVariant',
            {
              'label': '背景',
              'type': 'choice',
              'options': [
                for (final v in caps.backgroundChoices)
                  {
                    'id': v['name'],
                    'label': v['description'] ?? v['name'],
                    'value': v['name'],
                  },
              ],
            },
            doc.xiaomiOptions['background'] ??
                caps.backgroundChoices.first['name'],
            when: when,
            variants: variants,
          );
        }
        if (node['childViews'] is List) {
          visit(node['childViews'] as List, '$pointer/childViews');
        }
      }
    }

    visit(
      groups[g][key] as List,
      '/layout_group/$g/${staruwPointerToken(key as String)}',
    );
    final first = (groups[g][key] as List).first as Map;
    final pointer = '/layout_group/$g/${staruwPointerToken(key)}/0';
    if (caps.backgroundChoices.any((r) => '${r['value']}'.contains('blur'))) {
      bind(
        pointer,
        first,
        'glassBackground',
        'selectVariant',
        {
          'label': '背景材质',
          'type': 'choice',
          'options': [
            {'id': 'blur', 'label': '模糊', 'value': 'blur'},
            {'id': 'glass', 'label': '柔光玻璃', 'value': 'glass'},
          ],
        },
        doc.xiaomiOptions['glassBackground'] == true ? 'glass' : 'blur',
        when: when,
        variants: [
          for (final id in ['blur', 'glass'])
            {
              'id': id,
              'selection': {'kind': 'option', 'key': id},
            },
        ],
      );
    }
    for (final setting in caps.customizeSettings) {
      final variants = [
        if (setting['supportOff'] == true)
          {
            'id': 'off',
            'selection': {'kind': 'option', 'key': 'off'},
          },
        for (final v in setting['options'] as List? ?? [])
          {
            'id': v['id'],
            'selection': {'kind': 'option', 'key': v['id']},
          },
      ];
      if (variants.isEmpty) continue;
      final value =
          (doc.xiaomiOptions['customizeSettings'] as Map? ??
              {})[setting['id']] ??
          setting['default'] ??
          variants.first['id'];
      bind(
        pointer,
        first,
        'customizeSettings.${setting['id']}',
        'selectVariant',
        {
          'label': setting['label'] ?? setting['id'],
          'type': 'choice',
          'options': [
            for (final v in variants)
              {
                'id': v['id'],
                'label': v['id'] == 'off' ? '关闭' : v['id'],
                'value': v['id'],
              },
          ],
        },
        value,
        when: when,
        variants: variants,
      );
    }
    for (final field in caps.mixFields) {
      final variants = [
        for (final value in field.types)
          {
            'id': value,
            'selection': {'kind': 'option', 'key': value},
          },
      ];
      bind(
        pointer,
        first,
        'mix.${field.id}',
        'selectVariant',
        {
          'label': '${field.id} · 内容',
          'type': 'choice',
          'options': [
            for (final value in field.types)
              {
                'id': value,
                'label': {
                  'off': '关闭',
                  'time': '时间',
                  'location_latlng': '经纬度',
                  'custom_text': '文案',
                  'exif': '拍摄参数',
                }[value],
                'value': value,
              },
          ],
        },
        field.selectedType(
          doc.xiaomiOptions,
          showParameters: doc.showParameters,
        ),
        when: when,
        variants: variants,
      );
      final text = field.state(doc.xiaomiOptions)['text'];
      if (text is String && text.isNotEmpty) {
        bind(
          pointer,
          first,
          'mixText.${field.id}',
          'selectVariant',
          {
            'label': '${field.id} · 文案',
            'type': 'choice',
            'options': [
              {'id': 'custom', 'label': '已保存文案', 'value': 'custom'},
              {'id': 'empty', 'label': '清空', 'value': 'empty'},
            ],
          },
          'custom',
          when: when,
          variants: [
            {
              'id': 'custom',
              'selection': {'kind': 'option', 'key': text},
            },
            {
              'id': 'empty',
              'selection': {'kind': 'option', 'key': ''},
            },
          ],
        );
      }
    }
    if (caps.canEditCaption && doc.caption.isNotEmpty) {
      bind(
        pointer,
        first,
        'caption',
        'selectVariant',
        {
          'label': '自定义文案',
          'type': 'choice',
          'options': [
            {'id': 'custom', 'label': '已保存文案', 'value': 'custom'},
            {'id': 'empty', 'label': '关闭', 'value': 'empty'},
          ],
        },
        'custom',
        when: when,
        variants: [
          {
            'id': 'custom',
            'selection': {'kind': 'option', 'key': doc.caption},
          },
          {
            'id': 'empty',
            'selection': {'kind': 'option', 'key': ''},
          },
        ],
      );
    }
  }
  // Real capture values are never defaults. They remain explicit photo controls.
  for (final field in [
    'photo.make',
    'photo.model',
    'exif.focalLengthMm',
    'exif.focalLength35mm',
    'exif.aperture',
    'exif.exposureSeconds',
    'exif.iso',
    'capture.localTime',
    'gps.latitude',
    'gps.longitude',
  ]) {
    final number = field.startsWith('exif.') || field.startsWith('gps.');
    controls.add({
      'id': 'photo${controls.length}',
      'label': {
        'photo.make': '照片品牌',
        'photo.model': '照片机型',
        'capture.localTime': '拍摄时间',
        'gps.latitude': '纬度',
        'gps.longitude': '经度',
        'exif.iso': 'ISO',
        'exif.focalLengthMm': '焦距',
        'exif.focalLength35mm': '等效焦距',
        'exif.aperture': '光圈',
        'exif.exposureSeconds': '曝光秒数',
      }[field],
      'type': number ? 'number' : 'text',
      'scope': 'photo',
      'stage': 'photo',
      if (number) ...{
        'min': field.startsWith('gps.')
            ? (field == 'gps.latitude' ? -90 : -180)
            : field == 'exif.iso'
            ? 1
            : .000001,
        'max': field.startsWith('gps.')
            ? (field == 'gps.latitude' ? 90 : 180)
            : 1000000,
        'step': field == 'exif.iso' ? 1 : .000001,
      },
      'targets': [
        {'kind': 'photoField', 'id': field, 'property': 'value'},
      ],
    });
  }
  final digest = staruwTreeDigest(source),
      identityKey =
          'native:$digest:${staruwHash(utf8.encode(jsonEncode(defaults)))}';
  final uuid = await StaruwIdentities(repository.base).forSource(identityKey);
  root['format'] = staruwFormat;
  root['staruw'] = {
    'standard': 'StarUW',
    'protocolVersion': '1.0.0',
    'identity': {'id': uuid, 'revision': 1},
    'metadata': {
      'name': style.title,
      'brand': 'xiaomi',
      'category': style.category.isEmpty
          ? 'custom'
          : RegExp(r'^[A-Za-z]').hasMatch(style.category)
          ? style.category
          : 'category-${style.category}',
      'preview': preview,
      'previewKind': previewKind,
    },
    'render': {'engine': 'xiaomi', 'profile': 'xiaomi.layout.v1'},
    'requires': [
      'xiaomi.layout.v1',
      'starmark.xiaomi.v1',
      if (largeOem) 'starmark.xiaomi.large-images.v1',
    ],
    'source': {
      'vendor': 'xiaomi',
      'kind': legacy == null ? 'xiaomi-oem' : 'starmark-legacy-sanitized',
      'schemaVersion': (root['version'] as num).toDouble().toString(),
      'entry': 'config.json',
      'config': 'source/xiaomi/config.json',
      'sourceDigest': digest,
      'files': sourceRows,
      if (legacy != null) ...{'sanitized': true, 'removedFields': removed},
    },
    'resources': resources,
    'xiaomi': {
      'policy': 'starmark.xiaomi.v1',
      'fonts': fonts,
      'fontAliases': {'default': 'mi-sans', 'oxanium': 'oxanium'},
      'fontPolicy': {
        'default': 'oxanium',
        'leica': 'lg1056',
        'redmi': 'mi-sans',
      },
      'initialLayout': {
        'groupIndex': selected['selection']['groupIndex'],
        'key': selected['selection']['key'],
      },
      'catalogs': {},
      'bindings': bindings,
    },
    'controls': controls,
    'defaults': defaults,
  };
  files['config.json'] = Uint8List.fromList(utf8.encode(jsonEncode(root)));
  return WatermarkPackageCodec.fromFiles(staruwSeal(files));
}
