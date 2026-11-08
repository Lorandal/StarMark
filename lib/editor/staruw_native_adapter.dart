import 'dart:convert';
import 'package:watermark_engine/watermark_engine.dart';
import '../services/staruw/contract.dart';
import '../services/staruw/controls.dart';
import '../services/photo_asset.dart';
import 'xiaomi_capabilities.dart';
import 'region_replacements.dart';

/// Source pointers authorize semantic operations on a render-only copy. Neither
/// source bytes nor the active OEM projection in a shared package are rewritten.
class StaruwNativeAdapter {
  static final _cache = Expando<EditDocument>();
  static EditDocument project(EditDocument document) {
    if (!document.isStaruw ||
        document.isStaruwScene ||
        document.staruwPreserved) {
      return document;
    }
    if (_cache[document] case final EditDocument cached) return cached;
    final root =
        jsonDecode(jsonEncode(document.staruwConfig)) as Map<String, dynamic>;
    final u = root['staruw'] as Map, x = u['xiaomi'] as Map;
    root.remove('staruw');
    root.remove('format');
    root.remove('starmark');
    root.remove('portable');
    final controls = StaruwControls(document),
        options = <String, dynamic>{
          'layoutGroup': x['initialLayout']['groupIndex'],
          'layout': x['initialLayout']['key'],
        };
    final fonts = <String, dynamic>{};
    for (final e in (x['fonts'] as Map).entries) {
      fonts[e.key as String] = u['resources'][e.value['resourceId']]['path'];
    }
    for (final e in (x['fontAliases'] as Map).entries) {
      fonts[e.key == 'default' ? 'mi-sans' : e.key as String] = fonts[e.value];
    }
    for (final e in (x['fontPolicy'] as Map).entries) {
      final key = {
        'default': 'oxanium',
        'leica': 'lg1056',
        'redmi': 'mi-sans',
      }[e.key]!;
      fonts.putIfAbsent(key, () => fonts[e.value]);
    }
    var caption = '';
    final operations = controls.operations.toList(), blocked = <String>[];
    for (final op in operations.where(
      (op) =>
          op.target['property'] == 'modelRegion' &&
          op.value['mode'] != 'native',
    )) {
      final binding = (x['bindings'] as List).firstWhere(
        (b) => b['id'] == op.target['id'],
      );
      final pointer = binding['sourcePointer'] as String,
          node = staruwPointer(root, pointer) as Map;
      if (isModelRun(node)) {
        final children = node['childViews'] as List;
        for (var i = 0; i < children.length; i++) {
          final child = children[i] as Map;
          if (child['type'] == 'TextView' &&
                  '${child['text'] ?? ''}'.startsWith('@model=') ||
              child['type'] == 'ImageView' &&
                  '${child['path'] ?? ''}'.contains(r'path=${logo}')) {
            blocked.add('$pointer/childViews/$i');
          }
        }
      }
    }
    for (final op in operations) {
      final target = op.target;
      if (target['kind'] != 'xiaomiBinding') continue;
      final binding =
          (x['bindings'] as List).firstWhere((b) => b['id'] == target['id'])
              as Map;
      final pointer = binding['sourcePointer'] as String;
      if (blocked.any((p) => pointer == p || pointer.startsWith('$p/'))) {
        continue;
      }
      final node = staruwPointer(root, binding['sourcePointer'] as String),
          value = op.value;
      switch (target['property']) {
        case 'selectVariant':
          final variant = (binding['variants'] as List).firstWhere(
            (v) => v['id'] == value,
          )['selection'];
          if (variant['kind'] == 'layout') {
            options['layoutGroup'] = variant['groupIndex'];
            options['layout'] = variant['key'];
          } else if (binding['property'] == 'background') {
            options['background'] = variant['key'];
          } else if (binding['property'] == 'glassBackground') {
            options['glassBackground'] = variant['key'] == 'glass';
          } else if ((binding['property'] as String).startsWith(
            'customizeSettings.',
          )) {
            (options['customizeSettings'] ??=
                    <String, dynamic>{})[(binding['property'] as String)
                    .substring(18)] =
                variant['key'];
          } else if ((binding['property'] as String).startsWith('mix.')) {
            (options['mix'] ??= <String, dynamic>{})[(binding['property']
                    as String)
                .substring(4)] = {
              'type': variant['key'],
            };
          } else if ((binding['property'] as String).startsWith('mixText.')) {
            final id = (binding['property'] as String).substring(8);
            ((options['mix'] ??= <String, dynamic>{})[id] ??=
                    <String, dynamic>{})['text'] =
                variant['key'];
          } else if (binding['property'] == 'caption') {
            caption = variant['key'] as String;
          } else {
            throw const StaruwException('E_BINDING', '原厂选项没有受控映射');
          }
        case 'visible':
          (node as Map)['enable'] = value;
        case 'scalePreset':
          (node as Map)['_staruwScale'] = {
            'small': .85,
            'medium': 1.0,
            'large': 1.15,
          }[value];
        case 'font':
          final def =
              (root['wmRes']['fonts'] as Map?)?[node['font_ref']] as Map? ?? {};
          final typeface = def['typeface'] as String? ?? 'default';
          final originalPath = typeface.startsWith('@file=')
              ? typeface.substring(6)
              : fonts[typeface == 'default' ? 'mi-sans' : typeface];
          // Choosing the exact original face keeps its raw OEM variation.
          if (fonts[value] != originalPath) {
            (node as Map)['_staruwFontId'] = value;
          }
        case 'modelRegion' || 'parameterIcon':
          if (value['mode'] != 'native') {
            (node as Map)['_staruwReplacement'] = {
              ...value as Map,
              'bindingId': binding['id'],
              if (binding['replacementStyle'] != null)
                'style': binding['replacementStyle'],
            };
          }
        case 'photoOverride':
          if (binding['property'] == 'user.signature') {
            (node as Map)['_staruwPhotoText'] = value;
          }
        default:
          throw const StaruwException('E_BINDING', '未支持的原厂操作');
      }
    }
    final capability = XiaomiCapabilities.fromConfig(
      root,
      options,
      (u['resources'] as Map).values.map((r) => r['path'] as String),
    );
    for (final setting in capability.customizeSettings) {
      final chosen =
          (options['customizeSettings'] as Map? ?? {})[setting['id']];
      final choice = (setting['options'] as List? ?? [])
          .where((r) => r['id'] == chosen)
          .firstOrNull;
      if (choice == null) continue;
      if (setting['id'] == 'leicaLooks') {
        options['filterName'] = '${choice['id']}'.replaceAll('_', ' ');
        options['@type_leica_looks'] = choice['assetBase'];
      }
      if (setting['id'] == 'leicaLens') {
        options['cvLens'] = choice['lensName'];
        options['@type_leica_lens'] = choice['assetBase'];
      }
    }
    caption = controls.photoFields['user.signature'] as String? ?? caption;
    final projected = document.copyWith(
      xiaomiConfig: root,
      xiaomiOptions: options,
      resourceBindings: {...document.resourceBindings, 'fonts': fonts},
      font: const FontRef.photoDefault(),
      scale: 1,
      showModel: true,
      showParameters: true,
      showBrand: true,
      modelId: '',
      modelOverride: '',
      parametersOverride: '',
      regionReplacements: const {},
      caption: caption,
    );
    _cache[document] = projected;
    _cache[projected] = projected;
    return projected;
  }

  static PhotoAsset photo(EditDocument doc, PhotoAsset photo) {
    if (!doc.isStaruw || doc.staruwPreserved) return photo;
    final controls = StaruwControls(doc), fields = controls.photoFields;
    final u = doc.staruwConfig!['staruw'] as Map;
    for (final op in controls.operations) {
      if (op.target['kind'] == 'xiaomiBinding' &&
          op.target['property'] == 'photoOverride') {
        final binding = (u['xiaomi']['bindings'] as List).firstWhere(
          (b) => b['id'] == op.target['id'],
        );
        fields[binding['property'] as String] = op.value;
      }
    }
    if (fields.isEmpty) return photo;
    final metadata = photo.metadata.toJson();
    const keys = {
      'photo.make': 'make',
      'photo.model': 'model',
      'exif.focalLengthMm': 'focal',
      'exif.focalLength35mm': 'focal35mm',
      'exif.aperture': 'aperture',
      'exif.exposureSeconds': 'exposure',
      'exif.iso': 'iso',
      'capture.localTime': 'dateTime',
      'gps.latitude': 'latitude',
      'gps.longitude': 'longitude',
    };
    for (final e in fields.entries) {
      if (keys.containsKey(e.key)) metadata[keys[e.key]!] = e.value;
    }
    return PhotoAsset.fromJson({...photo.toJson(), 'metadata': metadata});
  }
}
