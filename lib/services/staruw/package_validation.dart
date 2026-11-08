import 'dart:convert';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import 'contract.dart';
import 'font_file.dart';
import '../../editor/xiaomi_capabilities.dart';
import '../../editor/region_replacements.dart';

const staruwCapabilities = <String>{
  'scene.core.v1',
  'xiaomi.layout.v1',
  'starmark.xiaomi.v1',
  'scene.model-catalog.v1',
  'material.photo-blur.v1',
  'authoring.ten-elements.v1',
  'starmark.xiaomi.large-images.v1',
};

String staruwHash(List<int> bytes) => sha256.convert(bytes).toString();
String staruwTreeDigest(Map<String, List<int>> files) {
  final names = files.keys.toList()
    ..sort((a, b) {
      final aa = utf8.encode(a), bb = utf8.encode(b);
      for (var i = 0; i < aa.length && i < bb.length; i++) {
        if (aa[i] != bb[i]) return aa[i] - bb[i];
      }
      return aa.length - bb.length;
    });
  return staruwHash(
    utf8.encode(
      names
          .map(
            (p) =>
                '$p\u0000${files[p]!.length}\u0000${staruwHash(files[p]!)}\n',
          )
          .join(),
    ),
  );
}

Map<String, Uint8List> staruwSeal(Map<String, List<int>> input) {
  final files = {
    for (final e in input.entries)
      if (e.key != 'manifest.json') e.key: Uint8List.fromList(e.value),
  };
  files['manifest.json'] = Uint8List.fromList(
    utf8.encode(
      jsonEncode({
        'format': 'com.realize.starmark.watermark-manifest',
        'manifestVersion': 1,
        'files': [
          for (final e in files.entries)
            {
              'path': e.key,
              'size': e.value.length,
              'sha256': staruwHash(e.value),
            },
        ],
        'payloadDigest': staruwTreeDigest(files),
      }),
    ),
  );
  return files;
}

class StaruwPackageReport {
  final Map<String, dynamic> json;
  const StaruwPackageReport(this.json);
  bool get canRender =>
      json['editMode'] != 'preserve' && json['editMode'] != 'reject';
  String get digest => json['payloadDigest'] as String? ?? '';
}

class StaruwPackageValidator {
  final Map<String, Uint8List> files;
  final void Function(String) validateSvg;
  final Set<String> capabilities;
  final Map<String, StaruwFontFile> fontFiles = {};
  final List<Map<String, dynamic>> diagnostics = [];
  late Map config, u, resources, space, fonts;
  late Set<String> requires;
  bool unsupportedNative = false;
  final Map<String, Map> nodes = {}, bindings = {}, controls = {};
  StaruwPackageValidator(
    this.files, {
    required this.validateSvg,
    this.capabilities = staruwCapabilities,
  });

  /// Reuse the same value/type checks for edits against an already validated
  /// payload. It cannot validate or authorize a newly imported package.
  factory StaruwPackageValidator.forControls(Map<String, dynamic> config) {
    final v = StaruwPackageValidator({}, validateSvg: (_) {})..config = config;
    v.u = config['staruw'] as Map;
    v.resources = v.u['resources'] as Map;
    v.space = (v.u['scene'] ?? v.u['xiaomi']) as Map;
    v.fonts = v.space['fonts'] as Map;
    for (final c in v.u['controls'] as List) {
      v.controls[c['id'] as String] = c as Map;
    }
    for (final b in v.u['xiaomi']?['bindings'] as List? ?? []) {
      v.bindings[b['id'] as String] = b as Map;
    }
    return v;
  }
  dynamic controlValue(String id, dynamic value) {
    final c = controls[id];
    staruwNeed(c != null, 'E_BINDING', '控件不存在');
    validateValue(c!, value);
    final effective = c['type'] == 'choice'
        ? (c['options'] as List).firstWhere((o) => o['id'] == value)['value']
        : value;
    for (final t in c['targets'] as List) {
      _targetValue(c, t as Map, effective);
    }
    return effective;
  }

  StaruwPackageReport validate() {
    _paths();
    final parsed = staruwJson(
      files['config.json'] ??
          (throw const StaruwException('E_RESOURCE', '缺少 config.json')),
    );
    staruwNeed(
      parsed is Map &&
          parsed['format'] == staruwFormat &&
          parsed['staruw'] is Map,
      'E_FORMAT',
      '不是 StarUW 水印包',
    );
    config = parsed as Map;
    u = config['staruw'] as Map;
    staruwNeed(
      u['standard'] == 'StarUW' &&
          u['protocolVersion'] is String &&
          RegExp(
            r'^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$',
          ).hasMatch(u['protocolVersion']),
      'E_VERSION',
      'StarUW 协议标识或版本无效',
    );
    // Future versions are inspected only within the archive/JSON boundary.
    // Do not guess their manifest, resources, renderer or source schema.
    if (u['protocolVersion'] != '1.0.0') {
      return StaruwPackageReport({
        'format': staruwFormat,
        'protocolVersion': u['protocolVersion'],
        'engine': 'unknown',
        'editMode': 'preserve',
        'renderStatus': 'unsupported',
        'missingCapabilities': <String>[],
        'payloadDigest': '',
        'diagnostics': [
          const StaruwException('E_VERSION', '此版本仅保留原包').toDiagnostic(),
        ],
      });
    }
    for (final e in files.entries) {
      if (e.key.toLowerCase().endsWith('.json')) staruwJson(e.value, e.key);
    }
    final manifest = staruwJson(
      files['manifest.json'] ??
          (throw const StaruwException('E_RESOURCE', '缺少 manifest.json')),
      'manifest.json',
    );
    StaruwSchema('manifest').validate(manifest);
    final listed = <String>{};
    for (final item in manifest['files'] as List) {
      final name = item['path'] as String;
      final bytes = files[name];
      staruwNeed(
        name != 'manifest.json' &&
            listed.add(name) &&
            bytes != null &&
            bytes.length == item['size'] &&
            staruwHash(bytes) == item['sha256'],
        'E_HASH',
        '清单资源完整性校验失败',
        name,
      );
    }
    staruwNeed(
      listed.length == files.length - 1 &&
          files.keys.every((p) => p == 'manifest.json' || listed.contains(p)),
      'E_HASH',
      '清单未覆盖全部资源',
    );
    final digest = staruwTreeDigest({
      for (final e in files.entries)
        if (e.key != 'manifest.json') e.key: e.value,
    });
    staruwNeed(digest == manifest['payloadDigest'], 'E_HASH', '包摘要不一致');
    StaruwSchema('config').validate(config);
    resources = u['resources'] as Map;
    requires = (u['requires'] as List).cast<String>().toSet();
    space = (u['scene'] ?? u['xiaomi']) as Map;
    fonts = space['fonts'] as Map;
    staruwNeed(
      requires.contains(u['render']['profile']),
      'E_CAPABILITY',
      '缺少渲染入口能力声明',
    );
    _resources();
    _source();
    _fontAndCatalogs();
    if (u['render']['engine'] == 'scene') {
      _scene();
    } else {
      _xiaomi();
    }
    if (!unsupportedNative) _controls();
    final missing = requires.difference(capabilities).toList()..sort();
    if (unsupportedNative) missing.add('xiaomi.source.unsupported');
    for (final capability in missing) {
      diagnostics.add({
        ...const StaruwException('E_CAPABILITY', '缺少必需能力，已保留原包').toDiagnostic(),
        'capability': capability,
      });
    }
    return StaruwPackageReport({
      'format': staruwFormat,
      'protocolVersion': '1.0.0',
      'engine': u['render']['engine'],
      // The App exposes declared controls, including for complex scene trees.
      'editMode': missing.isEmpty ? 'controlled' : 'preserve',
      'renderStatus': missing.isNotEmpty
          ? 'unsupported'
          : diagnostics.any((d) => d['code'] == 'W_MATERIAL_FALLBACK')
          ? 'fallback'
          : 'supported',
      'missingCapabilities': missing, 'diagnostics': diagnostics,
      'identity': u['identity'], 'payloadDigest': digest,
      'materialBackends': {
        for (final e in (space['materials'] as Map? ?? {}).entries)
          e.key: e.value['kind'] == 'photo-glass'
              ? 'fallback.${e.value['fallback']['kind']}'
              : 'photo-blur.v1',
      },
    });
  }

  void _paths() {
    staruwNeed(files.isNotEmpty && files.length <= 512, 'E_LIMIT', '文件数量超限');
    var total = 0;
    final folded = <String>{};
    for (final e in files.entries) {
      staruwNeed(
        staruwSafePath(e.key) && folded.add(staruwCaseFold(e.key)),
        'E_PATH',
        '文件路径无效或冲突',
      );
      total += e.value.length;
      staruwNeed(
        e.value.length <= 32 * 1024 * 1024 && total <= 128 * 1024 * 1024,
        'E_LIMIT',
        '资源大小超限',
      );
      staruwNeed(
        !e.key.toLowerCase().endsWith('.zip'),
        'E_ZIP',
        '单款包不允许嵌套 ZIP',
      );
    }
    for (final path in folded) {
      final parts = path.split('/');
      for (var i = 1; i < parts.length; i++) {
        staruwNeed(
          !folded.contains(parts.take(i).join('/')),
          'E_PATH',
          '文件与目录路径冲突',
        );
      }
    }
  }

  void _resources() {
    final mediaKinds = {
      'image/png': 'image',
      'image/jpeg': 'image',
      'image/webp': 'image',
      'image/svg+xml': 'image',
      'font/ttf': 'font',
      'font/otf': 'font',
      'application/json': null,
      'text/plain': null,
    };
    final seenFonts = <String, StaruwFontFile>{};
    for (final e in resources.entries) {
      final r = e.value as Map, path = r['path'] as String, bytes = files[path];
      staruwNeed(bytes != null, 'E_RESOURCE', '资源不存在', path);
      staruwNeed(staruwHash(bytes!) == r['sha256'], 'E_HASH', '资源摘要不一致', path);
      staruwNeed(
        mediaKinds.containsKey(r['mediaType']) &&
            (mediaKinds[r['mediaType']] == null ||
                mediaKinds[r['mediaType']] == r['kind']),
        'E_RESOURCE',
        '资源类型与 MIME 不匹配',
        path,
      );
      if (r['kind'] == 'font') {
        staruwNeed(
          ['font/ttf', 'font/otf'].contains(r['mediaType']),
          'E_RESOURCE',
          '字库 MIME 无效',
          path,
        );
        fontFiles[e.key as String] = seenFonts.putIfAbsent(
          r['sha256'] as String,
          () => StaruwFontFile(bytes),
        );
      } else if (r['kind'] == 'image') {
        if (r['mediaType'] == 'image/svg+xml') {
          validateSvg(utf8.decode(bytes));
        } else {
          _image(
            bytes,
            r['mediaType'] as String,
            path,
            largeOem: _largeOem(path),
          );
        }
      } else if (r['mediaType'] == 'application/json') {
        staruwJson(bytes, path);
      } else {
        utf8.decode(bytes);
      }
    }
    staruwNeed(
      resources.values.any(
        (r) => r['path'] == u['metadata']['preview'] && r['kind'] == 'image',
      ),
      'E_RESOURCE',
      '预览未登记为图片资源',
    );
    if (u['render']['engine'] == 'xiaomi') {
      final registered = resources.values.map((r) => r['path']).toSet();
      for (final e in files.entries) {
        if (registered.contains(e.key) || e.key.startsWith('source/')) continue;
        final ext = e.key.split('.').last.toLowerCase();
        if (['ttf', 'otf'].contains(ext)) StaruwFontFile(e.value);
        if (['png', 'jpg', 'jpeg', 'webp'].contains(ext)) {
          final b = e.value;
          final mime = b.length >= 4 && b[0] == 137
              ? 'image/png'
              : b.length >= 2 && b[0] == 255
              ? 'image/jpeg'
              : 'image/webp';
          _image(b, mime, e.key, largeOem: _largeOem(e.key));
        }
      }
    }
  }

  bool _largeOem(String path) =>
      u['render']['engine'] == 'xiaomi' &&
      requires.contains('starmark.xiaomi.large-images.v1') &&
      (u['source']?['files'] as List? ?? []).any(
        (r) => r['path'] == path && r['originalPath'] == path,
      );
  void resource(dynamic id, String kind) => staruwNeed(
    resources[id] is Map && resources[id]['kind'] == kind,
    'E_RESOURCE',
    '缺少已声明的 $kind 资源',
  );
  void font(dynamic id) =>
      staruwNeed(fonts.containsKey(id), 'E_RESOURCE', '缺少已声明的字体');
  void _fontAndCatalogs() {
    for (final e in fonts.entries) {
      resource(e.value['resourceId'], 'font');
      fontFiles[e.value['resourceId']]!.validateAxes(e.value['axes'] as Map);
    }
    final visited = <String>{};
    void visit(String id, Set<String> active) {
      font(id);
      staruwNeed(active.add(id), 'E_RESOURCE', '字体回退存在循环');
      if (!visited.contains(id)) {
        for (final child in fonts[id]['fallbackFontIds'] as List) {
          visit(child as String, active);
        }
        visited.add(id);
      }
      active.remove(id);
    }

    for (final id in fonts.keys) {
      visit(id as String, {});
    }
    for (final id in (space['catalogs'] as Map).values) {
      resource(id, 'catalog');
      final catalog = staruwJson(files[resources[id]['path']]!);
      StaruwSchema('model-catalog').validate(catalog);
      final matches = <String>{};
      for (final row in catalog['entries'] as List) {
        String norm(String s) => s
            .trim()
            .replaceAll(RegExp(r'\s+'), ' ')
            .replaceAllMapped(RegExp('[A-Z]'), (m) => m[0]!.toLowerCase());
        staruwNeed(
          matches.add(
            '${norm(row['make'] as String? ?? '')}\u0000${norm(row['model'] as String? ?? '')}',
          ),
          'E_BINDING',
          '机型目录包含重复匹配项',
        );
        for (final c in row['components'] as List) {
          resource(c['resourceId'], 'image');
        }
      }
    }
    if ((space['catalogs'] as Map).isNotEmpty) {
      staruwNeed(
        requires.contains('scene.model-catalog.v1'),
        'E_CAPABILITY',
        '缺少机型目录能力声明',
      );
    }
  }

  void _source() {
    final source = u['source'];
    if (source == null) return;
    final original = <String, List<int>>{}, destinations = <String>{};
    for (final row in source['files'] as List) {
      final name = row['originalPath'] as String, path = row['path'] as String;
      staruwNeed(
        staruwSafePath(name) && staruwSafePath(path),
        'E_PATH',
        '原件路径不合法',
      );
      staruwNeed(
        !original.containsKey(name) &&
            destinations.add(path) &&
            files.containsKey(path),
        'E_RESOURCE',
        '原件映射重复或资源缺失',
      );
      original[name] = files[path]!;
    }
    staruwNeed(
      staruwTreeDigest(original) == source['sourceDigest'],
      'E_HASH',
      '原件摘要不一致',
    );
    staruwNeed(
      (source['files'] as List).any(
        (row) =>
            row['originalPath'] == source['entry'] &&
            row['path'] == source['config'],
      ),
      'E_RESOURCE',
      '原件配置映射缺失',
    );
    if ((source['kind'] as String).endsWith('-sanitized')) {
      staruwNeed(
        source['sanitized'] == true &&
            (source['removedFields'] as List? ?? []).isNotEmpty,
        'E_SCHEMA',
        '净化来源标记不完整',
      );
    }
    if (source['kind'] == 'xiaomi-oem' || u['render']['engine'] == 'xiaomi') {
      staruwNeed(source['vendor'] == 'xiaomi', 'E_BINDING', '小米源品牌不匹配');
    }
    if (u['render']['engine'] == 'xiaomi') {
      staruwNeed(source['entry'] == 'config.json', 'E_BINDING', '原厂入口不正确');
      final saved = staruwJson(
        files[source['config']]!,
        source['config'] as String,
      );
      Map projection(Map c) => {
        for (final e in c.entries)
          if (!['format', 'staruw', 'starmark', 'portable'].contains(e.key))
            e.key: e.value,
      };
      staruwNeed(
        saved is Map &&
            jsonEncode(projection(saved)) == jsonEncode(projection(config)),
        'E_HASH',
        '活动原厂树与保留原件不一致',
      );
      staruwNeed(
        source['schemaVersion'] == _decimal(config['version'] as num),
        'E_BINDING',
        '原厂版本来源不一致',
      );
    }
  }

  String _decimal(num n) =>
      n == n.roundToDouble() ? '${n.toInt()}.0' : n.toString();
  void _style(Map style) {
    staruwNeed(style['minSize'] <= style['size'], 'E_BINDING', '最小字号超过字号');
    if (style['fontFit'] case final Map fit) {
      font(fit['referenceFontId']);
      fontFiles[fonts[fit['referenceFontId']]['resourceId']]!.validateAxes(
        fit['referenceAxes'] as Map,
      );
    }
  }

  void _content(Map content) {
    for (final part in content['parts'] as List? ?? []) {
      if (part['field'] == null) continue;
      final field = part['field'], format = part['format'];
      final allowed = switch (format) {
        'plain' => true,
        'focalMm' => [
          'exif.focalLengthMm',
          'exif.focalLength35mm',
        ].contains(field),
        'apertureF' => field == 'exif.aperture',
        'shutterSeconds' => field == 'exif.exposureSeconds',
        'iso' => field == 'exif.iso',
        'gpsDms' => ['gps.latitude', 'gps.longitude'].contains(field),
        'dateTimeDots' || 'dateTimeSlash' => field == 'capture.localTime',
        _ => false,
      };
      staruwNeed(allowed, 'E_BINDING', '动态字段与格式器不匹配');
    }
  }

  void _scene() {
    void walk(List children, int depth) {
      staruwNeed(depth <= 64, 'E_LIMIT', '场景节点过深');
      for (final child in children) {
        final n = child as Map, id = n['id'] as String;
        staruwNeed(!nodes.containsKey(id), 'E_BINDING', '节点 ID 重复');
        nodes[id] = n;
        staruwNeed(nodes.length <= 4096, 'E_LIMIT', '节点数量超限');
        staruwNeed(
          (n['bounds']['reference'] == 'parent') == (depth > 1),
          'E_BINDING',
          '父级坐标参考不正确',
        );
        switch (n['type']) {
          case 'image':
            resource(n['resourceId'], 'image');
            staruwNeed(
              n['shape'] != 'circle' ||
                  n['bounds']['size'][0] == n['bounds']['size'][1],
              'E_BINDING',
              '圆形图片必须有方形边界',
            );
          case 'text':
            font(n['fontId']);
            _style(n['textStyle'] as Map);
            _content(n['content'] as Map);
          case 'model':
            font(n['fallbackFontId']);
            _style(n['textStyle'] as Map);
            staruwNeed(
              (space['catalogs'] as Map).containsKey(n['catalogId']),
              'E_RESOURCE',
              '机型目录不存在',
            );
          case 'group':
            if (n['role'] == 'model') {
              staruwNeed(
                n['replacementStyle'] is Map,
                'E_BINDING',
                '机型组缺少签名排版',
              );
              font(n['replacementStyle']['fontId']);
              _style(n['replacementStyle']['textStyle'] as Map);
            }
            walk(n['children'] as List, depth + 1);
        }
      }
    }

    walk(space['nodes'] as List, 1);
    final canvas = space['canvas'] as Map;
    staruwNeed(
      canvas['mode'] != 'overlay' ||
          (canvas['insets'] as Map).values.every((v) => v == 0),
      'E_BINDING',
      '叠加水印不能增加外边框',
    );
    _background(canvas['background']);
    for (final material in (space['materials'] as Map).values) {
      if (material['kind'] == 'photo-blur' ||
          material['fallback']?['kind'] == 'photo-blur') {
        staruwNeed(
          requires.contains('material.photo-blur.v1'),
          'E_CAPABILITY',
          '缺少照片模糊能力声明',
        );
      }
      if (material['kind'] == 'photo-glass') {
        diagnostics.add({
          'code': 'W_MATERIAL_FALLBACK',
          'severity': 'warning',
          'path': '/staruw/scene/materials',
          'message': '当前照片渲染器使用作者声明的玻璃回退效果',
        });
      }
    }
    if (u['authoring']?['profile'] == 'authoring.ten-elements.v1') {
      staruwNeed(
        (space['nodes'] as List).length <= 10,
        'E_LIMIT',
        '十元素档案超过十个元素',
      );
      final roles = <String>{};
      for (final n in space['nodes'] as List) {
        if (n['type'] != 'group') continue;
        staruwNeed(
          ['model', 'parameters'].contains(n['role']) &&
              roles.add(n['role'] as String),
          'E_BINDING',
          '十元素档案包含无效组合',
        );
        final children = n['children'] as List;
        if (n['role'] == 'model') {
          staruwNeed(
            children.length <= 2 &&
                children.where((c) => c['type'] == 'model').length == 1 &&
                children.every(
                  (c) =>
                      c['type'] == 'model' ||
                      c['type'] == 'image' && c['slot'] == 'brand',
                ),
            'E_BINDING',
            '机型组子元素不正确',
          );
        } else {
          staruwNeed(
            children.where((c) => c['type'] == 'image').length <= 1 &&
                children.every(
                  (c) => c['type'] == 'image'
                      ? c['slot'] == 'parameterIcon'
                      : c['type'] == 'text' &&
                            c['slot'] == 'parameters' &&
                            (c['content']['parts'] as List? ?? []).any(
                              (p) => ['exif.', 'capture.', 'gps.'].any(
                                (prefix) => (p['field'] as String? ?? '')
                                    .startsWith(prefix),
                              ),
                            ),
                ),
            'E_BINDING',
            '参数组子元素不正确',
          );
        }
      }
    }
  }

  void _background(dynamic v) {
    StaruwSchema('config').validate(v, definition: 'background');
    if (v['kind'] == 'image') resource(v['resourceId'], 'image');
    if (v['kind'] == 'material') {
      staruwNeed(
        (space['materials'] as Map).containsKey(v['materialId']),
        'E_RESOURCE',
        '材质不存在',
      );
    }
  }

  void _xiaomi() {
    staruwNeed(
      requires.contains('starmark.xiaomi.v1'),
      'E_CAPABILITY',
      '缺少小米适配策略',
    );
    if (![1.0, 1.58, 1.78, 1.81].contains(config['version'])) {
      unsupportedNative = true;
      diagnostics.add(
        const StaruwException('E_VERSION', '未登记的原厂版本，仅保留原包').toDiagnostic(),
      );
      return;
    }
    void inspect(dynamic node, int depth) {
      staruwNeed(depth <= 64, 'E_LIMIT', '原厂节点嵌套过深');
      if (node is List) {
        for (final child in node) {
          inspect(child, depth);
        }
        return;
      }
      if (node is! Map) return;
      if (node['type'] != null &&
          !['Layout', 'ImageView', 'TextView'].contains(node['type'])) {
        unsupportedNative = true;
      }
      if (node['childViews'] != null) inspect(node['childViews'], depth + 1);
    }

    for (final group in config['layout_group'] as List) {
      for (final value in (group as Map).values) {
        if (value is List) inspect(value, 1);
      }
    }
    if (unsupportedNative) {
      diagnostics.add(
        const StaruwException(
          'E_CAPABILITY',
          '原厂布局含未支持的节点，仅保留原包',
        ).toDiagnostic(),
      );
      return;
    }
    for (final id in [
      ...(space['fontAliases'] as Map).values,
      ...(space['fontPolicy'] as Map).values,
    ]) {
      font(id);
    }
    final layout = space['initialLayout'];
    staruwNeed(
      staruwPointer(
            config,
            '/layout_group/${layout['groupIndex']}/${staruwPointerToken(layout['key'] as String)}',
          )
          is List,
      'E_BINDING',
      '初始原厂布局不存在',
    );
    for (final b in space['bindings'] as List) {
      staruwNeed(!bindings.containsKey(b['id']), 'E_BINDING', '原厂绑定 ID 重复');
      bindings[b['id'] as String] = b as Map;
      final p = b['sourcePointer'] as String;
      staruwNeed(
        !p.startsWith('/staruw') && !p.startsWith('/source'),
        'E_BINDING',
        '不可绑定保留数据',
      );
      final node = staruwPointer(config, p);
      staruwNeed(
        b['nodeType'] == 'LayoutGroup'
            ? p == '/layout_group' && node is List
            : node is Map &&
                  (node['type'] == b['nodeType'] ||
                      node['viewType'] == b['nodeType']),
        'E_BINDING',
        '原厂绑定节点类型不匹配',
        p,
      );
      final operationSet = (b['operations'] as List).toSet();
      final property = b['property'] as String;
      for (final operation in operationSet) {
        final supported = switch (operation) {
          'visible' =>
            node is Map && node['optional'] == true && property == 'visible',
          'scalePreset' =>
            node is Map && node['support_scale'] == true && property == 'scale',
          'font' =>
            node is Map &&
                node['type'] == 'TextView' &&
                node['font_ref'] is String &&
                property == 'font',
          'modelRegion' =>
            node is Map &&
                (isModelRun(node) ||
                    node['type'] == 'ImageView' && node['id'] == 'img_model') &&
                property == 'model',
          'parameterIcon' =>
            node is Map &&
                isParameterEmblem(node) &&
                property == 'parameterIcon',
          'photoOverride' =>
            property == 'user.signature'
                ? node is Map && node['type'] == 'TextView'
                : [
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
                  ].contains(property),
          'selectVariant' =>
            property == 'layout' && b['nodeType'] == 'LayoutGroup' ||
                node is Map &&
                    (property == 'background' &&
                            node['background_optional'] == true ||
                        property.startsWith('customizeSettings.') ||
                        property.startsWith('mix.') ||
                        property.startsWith('mixText.') ||
                        property == 'glassBackground' ||
                        property == 'caption'),
          _ => false,
        };
        staruwNeed(supported, 'E_BINDING', '操作超出已登记的原厂能力', p);
      }
      // Inspect every layout, including inactive alternatives; no operation may
      // exploit the declaration to write arbitrary JSON or forbidden geometry.
      final location = RegExp(r'^/layout_group/([0-9]+)/([^/]+)').firstMatch(p);
      XiaomiCapabilities? capability;
      if (location != null) {
        capability = XiaomiCapabilities.fromConfig(
          Map<String, dynamic>.from(config),
          {
            'layoutGroup': int.parse(location[1]!),
            'layout': location[2]!.replaceAll('~1', '/').replaceAll('~0', '~'),
          },
          files.keys,
        );
      }
      final variants = <String>{};
      for (final v in b['variants'] as List? ?? []) {
        staruwNeed(variants.add(v['id'] as String), 'E_BINDING', '款式 ID 重复');
        final s = v['selection'];
        if (s['kind'] == 'layout') {
          staruwNeed(
            property == 'layout' &&
                staruwPointer(
                      config,
                      '/layout_group/${s['groupIndex']}/${staruwPointerToken(s['key'] as String)}',
                    )
                    is List,
            'E_BINDING',
            '候选布局不存在',
          );
        } else {
          final key = s['key'];
          var allowed = false;
          if (property == 'background') {
            allowed =
                capability?.backgroundChoices.any((c) => c['name'] == key) ==
                true;
          }
          if (property.startsWith('customizeSettings.')) {
            final setting = capability?.customizeSettings
                .where((c) => c['id'] == property.substring(18))
                .firstOrNull;
            allowed =
                setting != null &&
                (key == 'off' && setting['supportOff'] == true ||
                    (setting['options'] as List? ?? []).any(
                      (v) => v['id'] == key,
                    ));
          }
          if (property.startsWith('mix.')) {
            allowed =
                capability?.mixFields.any(
                  (f) => f.id == property.substring(4) && f.types.contains(key),
                ) ==
                true;
          }
          if (property == 'glassBackground') {
            allowed =
                ['blur', 'glass'].contains(key) &&
                capability?.backgroundChoices.any(
                      (r) => '${r['value']}'.contains('blur'),
                    ) ==
                    true;
          }
          if (property.startsWith('mixText.')) {
            allowed =
                key is String &&
                capability?.mixFields.any(
                      (f) => f.id == property.substring(8),
                    ) ==
                    true;
          }
          if (property == 'caption') {
            allowed = capability?.canEditCaption == true && key is String;
          }
          staruwNeed(allowed, 'E_BINDING', '原厂候选选项未授权', p);
        }
      }
      if (b['replacementStyle'] case final Map style) {
        font(style['fontId']);
        _style(style['textStyle'] as Map);
      }
    }
  }

  void _controls() {
    for (final c in u['controls'] as List) {
      staruwNeed(!controls.containsKey(c['id']), 'E_BINDING', '控件 ID 重复');
      controls[c['id'] as String] = c as Map;
    }
    final templateIds = controls.values
        .where((c) => c['scope'] == 'template')
        .map((c) => c['id'])
        .toSet();
    staruwNeed(
      staruwEqual(
        templateIds.toList()..sort(),
        (u['defaults'] as Map).keys.toList()..sort(),
      ),
      'E_SCOPE',
      '默认值必须恰好覆盖模板控件',
    );
    final writers = <String, Set<String>>{};
    for (final c in controls.values) {
      final stage = c['stage'] ?? 'design';
      staruwNeed(
        (stage == 'photo') == (c['scope'] == 'photo'),
        'E_SCOPE',
        '控件阶段与作用域不匹配',
      );
      if (stage == 'layout') {
        staruwNeed(
          c['type'] == 'choice' && (c['when'] as List? ?? []).isEmpty,
          'E_BINDING',
          '布局控件必须为无条件选项',
        );
      }
      for (final condition in c['when'] as List? ?? []) {
        final parent = controls[condition['controlId']];
        staruwNeed(
          parent != null &&
              parent['stage'] == 'layout' &&
              (parent['options'] as List).any(
                (o) => o['id'] == condition['equals'],
              ),
          'E_BINDING',
          '控件条件必须引用布局选项',
        );
      }
      final values = <dynamic>[];
      if (c['type'] == 'number') {
        staruwNeed(c['min'] <= c['max'], 'E_BINDING', '数值控件上下界不正确');
      }
      if (c['type'] == 'region') {
        staruwNeed(
          (c['allowModes'] as List).contains('native'),
          'E_BINDING',
          '替换必须允许恢复原样',
        );
      }
      if (c['type'] == 'choice') {
        final ids = <String>{};
        for (final option in c['options'] as List) {
          staruwNeed(ids.add(option['id'] as String), 'E_BINDING', '选项 ID 重复');
          values.add(option['value']);
        }
      }
      if (c['scope'] == 'template') {
        final v = u['defaults'][c['id']];
        validateValue(c, v);
        if (c['type'] != 'choice') values.add(v);
      }
      for (final t in c['targets'] as List) {
        final kind = t['kind'], id = t['id'], p = t['property'] as String;
        final props = writers.putIfAbsent('$kind/$id', () => {});
        final overlaps = switch (p) {
          'offset' => ['offsetX', 'offsetY'],
          'offsetX' || 'offsetY' => ['offset'],
          'size' => ['width', 'height'],
          'width' || 'height' => ['size'],
          _ => <String>[],
        };
        staruwNeed(
          !props.contains(p) && !overlaps.any(props.contains),
          'E_BINDING',
          '多个控件写入同一属性',
        );
        props.add(p);
        if (c['type'] == 'region') {
          staruwNeed(p == 'modelRegion', 'E_BINDING', '区域控件目标错误');
        }
        if (c['type'] == 'image') {
          staruwNeed(p == 'parameterIcon', 'E_BINDING', '图标控件目标错误');
        }
        if (kind == 'node') {
          final n = nodes[id];
          staruwNeed(n != null, 'E_BINDING', '节点目标不存在');
          staruwNeed(
            [
              'visible',
              'opacity',
              'rotation',
              'offset',
              'offsetX',
              'offsetY',
              'size',
              'width',
              'height',
              'font',
              'content',
              'image',
              'shape',
              'modelRegion',
              'parameterIcon',
            ].contains(p),
            'E_BINDING',
            '未知节点属性',
          );
          if (p == 'font') {
            staruwNeed(
              ['text', 'model'].contains(n!['type']),
              'E_BINDING',
              '目标不是文字节点',
            );
          }
          if (p == 'content') {
            staruwNeed(
              n!['type'] == 'text' && n['content']['literal'] is String,
              'E_BINDING',
              '不能覆盖动态文字',
            );
          }
          if (['image', 'shape'].contains(p)) {
            staruwNeed(n!['type'] == 'image', 'E_BINDING', '目标不是图片');
          }
          if (p == 'modelRegion') {
            staruwNeed(
              n!['type'] == 'group' && n['role'] == 'model',
              'E_BINDING',
              '目标不是机型组',
            );
          }
          if (p == 'parameterIcon') {
            staruwNeed(
              n!['type'] == 'group' &&
                      n['role'] == 'parameters' &&
                      (n['children'] as List)
                              .where(
                                (x) =>
                                    x['type'] == 'image' &&
                                    x['slot'] == 'parameterIcon',
                              )
                              .length ==
                          1 ||
                  n['type'] == 'image' && n['slot'] == 'parameterIcon',
              'E_BINDING',
              '缺少专用参数图标槽',
            );
          }
        } else if (kind == 'canvas') {
          staruwNeed(
            u['scene'] != null &&
                id == 'canvas' &&
                ['background', 'insets'].contains(p),
            'E_BINDING',
            '画布目标错误',
          );
        } else if (kind == 'xiaomiBinding') {
          staruwNeed(
            bindings[id] != null &&
                (bindings[id]!['operations'] as List).contains(p),
            'E_BINDING',
            '原厂操作未授权',
          );
        } else {
          staruwNeed(
            c['scope'] == 'photo' && p == 'value',
            'E_SCOPE',
            '照片数据不能写入模板',
          );
          StaruwSchema('config').validate(id, definition: 'field');
        }
        // Validate every reachable choice, not only the selected default.
        for (final v in values) {
          _targetValue(c, t as Map, v);
        }
        if (c['type'] == 'toggle') {
          _targetValue(c, t as Map, true);
        }
        if (c['type'] == 'number') {
          for (final v in [c['min'], c['max']]) {
            _targetValue(c, t as Map, v);
          }
        }
        if (c['type'] == 'text') {
          _targetValue(c, t as Map, '');
        }
      }
    }
  }

  void validateValue(Map c, dynamic v) {
    bool ok;
    switch (c['type']) {
      case 'choice':
        ok = (c['options'] as List).any((o) => staruwEqual(o['id'], v));
      case 'toggle':
        ok = v is bool;
      case 'number':
        ok =
            v is num &&
            v.isFinite &&
            v >= c['min'] &&
            v <= c['max'] &&
            (((v - c['min']) / c['step']) -
                        ((v - c['min']) / c['step']).round())
                    .abs() <=
                1e-9;
      case 'text':
        ok = v is String && v.length <= 65536;
      case 'image' || 'region':
        _replacement(c, v, c['type'] == 'region');
        ok = true;
      default:
        ok = false;
    }
    staruwNeed(ok, 'E_BINDING', '控件值无效');
  }

  void _replacement(Map? c, dynamic v, bool model) {
    staruwNeed(v is Map, 'E_BINDING', '替换值必须为对象');
    final m = v as Map;
    final mode = m['mode'];
    staruwNeed(
      (c == null
              ? (model ? ['native', 'text', 'image'] : ['native', 'image'])
              : model
              ? c['allowModes']
              : ['native', 'image'])
          .contains(mode),
      'E_BINDING',
      '替换模式不允许',
    );
    final keys = mode == 'native'
        ? ['mode']
        : mode == 'text'
        ? ['mode', 'text', 'fontId']
        : model
        ? ['mode', 'resourceId', 'fit']
        : ['mode', 'resourceId', 'shape'];
    staruwNeed(
      m.length == keys.length && keys.every(m.containsKey),
      'E_BINDING',
      '替换字段不完整',
    );
    if (mode == 'text') {
      staruwNeed(m['text'] is String, 'E_BINDING', '签名不是文字');
      font(m['fontId']);
    }
    if (mode == 'image') {
      resource(m['resourceId'], 'image');
      final prop = model ? 'fit' : 'shape',
          allowed = c == null
              ? (model ? ['contain', 'cover'] : ['rect', 'circle'])
              : c[model ? 'allowFits' : 'allowShapes'];
      staruwNeed(
        (allowed as List).contains(m[prop]) &&
            (c == null ||
                (c['allowTypes'] as List).contains(
                  resources[m['resourceId']]['mediaType'],
                )),
        'E_BINDING',
        '替换图片类型或裁切方式不允许',
      );
    }
  }

  void _photoValue(String field, dynamic value) {
    if (field.startsWith('exif.') || field.startsWith('gps.')) {
      staruwNeed(value is num && value.isFinite, 'E_BINDING', '照片数值无效');
      if (field.startsWith('exif.')) {
        staruwNeed(
          value > 0 && (field != 'exif.iso' || value == value.round()),
          'E_BINDING',
          '拍摄参数必须为正数，ISO 必须为整数',
        );
      }
      if (field == 'gps.latitude') {
        staruwNeed(value >= -90 && value <= 90, 'E_BINDING', '纬度超限');
      }
      if (field == 'gps.longitude') {
        staruwNeed(value >= -180 && value <= 180, 'E_BINDING', '经度超限');
      }
    } else {
      staruwNeed(
        value is String && value.length <= 65536,
        'E_BINDING',
        '照片字段类型不匹配',
      );
      if (field == 'capture.localTime' && value.isNotEmpty) {
        final m = RegExp(
          r'^(\d{4})[-:](\d{2})[-:](\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})?$',
        ).firstMatch(value as String);
        staruwNeed(m != null, 'E_BINDING', '拍摄时间格式无效');
        final n = [for (var i = 1; i <= 6; i++) int.parse(m![i]!)];
        final d = DateTime.utc(n[0], n[1], n[2], n[3], n[4], n[5]);
        staruwNeed(
          d.year == n[0] &&
              d.month == n[1] &&
              d.day == n[2] &&
              d.hour == n[3] &&
              d.minute == n[4] &&
              d.second == n[5],
          'E_BINDING',
          '拍摄时间超出有效范围',
        );
      }
    }
  }

  void _targetValue(Map control, Map t, dynamic v) {
    final p = t['property'], kind = t['kind'];
    if (kind == 'photoField') {
      _photoValue(t['id'] as String, v);
      return;
    }
    if (p == 'modelRegion' || p == 'parameterIcon') {
      _replacement(
        ['region', 'image'].contains(control['type']) ? control : null,
        v,
        p == 'modelRegion',
      );
      return;
    }
    if (kind == 'xiaomiBinding') {
      final b = bindings[t['id']]!;
      if (p == 'selectVariant') {
        staruwNeed(
          control['type'] == 'choice' &&
              (b['variants'] as List).any((x) => x['id'] == v),
          'E_BINDING',
          '款式不在绑定列表',
        );
        return;
      }
      if (p == 'scalePreset') {
        staruwNeed(
          ['small', 'medium', 'large'].contains(v),
          'E_BINDING',
          '尺寸仅允许三个预设',
        );
        return;
      }
      if (p == 'photoOverride') {
        staruwNeed(control['scope'] == 'photo', 'E_SCOPE', '照片覆盖不能写入模板');
        _photoValue(b['property'] as String, v);
        return;
      }
    }
    bool numeric(num min, num max) =>
        v is num && v.isFinite && v >= min && v <= max;
    switch (p) {
      case 'visible':
        staruwNeed(v is bool, 'E_BINDING', '显示开关类型不正确');
      case 'opacity':
        staruwNeed(numeric(0, 1), 'E_BINDING', '透明度超限');
      case 'rotation':
        staruwNeed(numeric(-360, 360), 'E_BINDING', '旋转角度超限');
      case 'offsetX' || 'offsetY':
        staruwNeed(numeric(-1000000, 1000000), 'E_BINDING', '位置超限');
      case 'width' || 'height':
        staruwNeed(numeric(0, 1000000) && v > 0, 'E_BINDING', '尺寸超限');
      case 'offset' || 'size':
        StaruwSchema(
          'config',
        ).validate(v, definition: p == 'size' ? 'positivePoint' : 'point');
      case 'font':
        font(v);
      case 'content':
        staruwNeed(v is String, 'E_BINDING', '文案必须是字符串');
      case 'image':
        resource(v, 'image');
      case 'shape':
        staruwNeed(['rect', 'circle'].contains(v), 'E_BINDING', '图片形状无效');
      case 'background':
        _background(v);
      case 'insets':
        StaruwSchema('config').validate(v, definition: 'insets');
      default:
        throw const StaruwException('E_BINDING', '未知操作');
    }
  }
}

({int width, int height}) staruwImageSize(
  Uint8List bytes,
  String mime,
  String path,
) {
  final d = ByteData.sublistView(bytes);
  int w = 0, h = 0;
  bool prefix(List<int> p) =>
      bytes.length >= p.length &&
      List.generate(p.length, (i) => i).every((i) => bytes[i] == p[i]);
  if (mime == 'image/png' &&
      bytes.length >= 33 &&
      prefix([137, 80, 78, 71, 13, 10, 26, 10]) &&
      String.fromCharCodes(bytes.sublist(12, 16)) == 'IHDR') {
    w = d.getUint32(16);
    h = d.getUint32(20);
  } else if (mime == 'image/jpeg' && prefix([255, 216])) {
    var at = 2;
    while (at + 4 <= bytes.length) {
      if (bytes[at++] != 255) break;
      while (at < bytes.length && bytes[at] == 255) {
        at++;
      }
      if (at >= bytes.length) break;
      final marker = bytes[at++];
      if (marker == 0xda || marker == 0xd9) break;
      if (marker == 1 || marker >= 0xd0 && marker <= 0xd7) continue;
      if (at + 2 > bytes.length) break;
      final size = d.getUint16(at);
      if (size < 2 || at + size > bytes.length) break;
      if ([
            0xc0,
            0xc1,
            0xc2,
            0xc3,
            0xc5,
            0xc6,
            0xc7,
            0xc9,
            0xca,
            0xcb,
            0xcd,
            0xce,
            0xcf,
          ].contains(marker) &&
          size >= 8) {
        h = d.getUint16(at + 3);
        w = d.getUint16(at + 5);
        break;
      }
      at += size;
    }
  } else if (mime == 'image/webp' &&
      bytes.length >= 30 &&
      String.fromCharCodes(bytes.sublist(0, 4)) == 'RIFF' &&
      String.fromCharCodes(bytes.sublist(8, 12)) == 'WEBP') {
    final kind = String.fromCharCodes(bytes.sublist(12, 16));
    if (kind == 'VP8X') {
      w = 1 + bytes[24] + (bytes[25] << 8) + (bytes[26] << 16);
      h = 1 + bytes[27] + (bytes[28] << 8) + (bytes[29] << 16);
    }
    if (kind == 'VP8 ' &&
        bytes[23] == 0x9d &&
        bytes[24] == 1 &&
        bytes[25] == 0x2a) {
      w = d.getUint16(26, Endian.little) & 0x3fff;
      h = d.getUint16(28, Endian.little) & 0x3fff;
    }
    if (kind == 'VP8L' && bytes[20] == 0x2f) {
      final bits = d.getUint32(21, Endian.little);
      w = (bits & 0x3fff) + 1;
      h = ((bits >> 14) & 0x3fff) + 1;
    }
  }
  staruwNeed(w > 0 && h > 0, 'E_RESOURCE', '图片头与声明格式不匹配', path);
  return (width: w, height: h);
}

void _image(
  Uint8List bytes,
  String mime,
  String path, {
  bool largeOem = false,
}) {
  final size = staruwImageSize(bytes, mime, path);
  staruwNeed(
    size.width <= 8192 &&
        size.height <= 8192 &&
        size.width * size.height <= (largeOem ? 32000000 : 16000000),
    'E_LIMIT',
    '图片尺寸超限',
    path,
  );
}
