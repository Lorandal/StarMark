import 'dart:convert';
import 'package:watermark_engine/watermark_engine.dart';
import '../services/region_assets.dart';
import 'region_replacements.dart';

/// A reversible app preference. Stored documents, undo entries and packages
/// never acquire this overlay; only preview/photo-export copies do.
class GlobalParameterIcon {
  GlobalParameterIcon(this.asset, this.shape) {
    if (!RegionAssets.validId(asset) || !{'square', 'circle'}.contains(shape)) {
      throw const FormatException('无效默认参数图标');
    }
  }
  final String asset, shape;
  final _cache = Expando<EditDocument>();
  static bool controlsParameterIcon(Map control) =>
      (control['targets'] as List? ?? []).any(
        (t) => t is Map && t['property'] == 'parameterIcon',
      );

  EditDocument apply(EditDocument document) {
    if (_cache[document] case final EditDocument cached) return cached;
    final result = _apply(document);
    _cache[document] = result;
    _cache[result] = result;
    return result;
  }

  EditDocument _apply(EditDocument document) {
    if (document.staruwPreserved) return document;
    if (!document.isStaruw) {
      if (regionSupport(document)['parameters'] != true) return document;
      return document.copyWith(
        regionReplacements: {
          ...document.regionReplacements,
          'parameters': {'source': 'image', 'asset': asset, 'shape': shape},
        },
      );
    }
    final original = document.staruwConfig!['staruw'] as Map;
    final controls = (original['controls'] as List).cast<Map>();
    final covered = <String>{};
    final nodes = <String, Map>{};
    void visit(List values) {
      for (final n in values.cast<Map>()) {
        nodes[n['id'] as String] = n;
        if (n['children'] is List) visit(n['children'] as List);
      }
    }

    if (original['scene'] is Map) visit(original['scene']['nodes'] as List);
    for (final control in controls.where(controlsParameterIcon)) {
      for (final target in (control['targets'] as List).cast<Map>()) {
        covered.add('${target['kind']}/${target['id']}');
        // A parameters-group control already owns its child icon slot.
        for (final child
            in (nodes[target['id']]?['children'] as List? ?? [])
                .whereType<Map>()) {
          if (child['slot'] == 'parameterIcon') {
            covered.add('node/${child['id']}');
          }
        }
      }
    }
    final extraTargets = <Map<String, dynamic>>[
      for (final node in nodes.values)
        if (node['type'] == 'image' &&
            node['slot'] == 'parameterIcon' &&
            !covered.contains('node/${node['id']}'))
          {'kind': 'node', 'id': node['id'], 'property': 'parameterIcon'},
      for (final binding
          in ((original['xiaomi'] as Map?)?['bindings'] as List? ?? [])
              .whereType<Map>())
        if (binding['property'] == 'parameterIcon' &&
            !covered.contains('xiaomiBinding/${binding['id']}'))
          {
            'kind': 'xiaomiBinding',
            'id': binding['id'],
            'property': 'parameterIcon',
          },
    ];
    if (!controls.any(controlsParameterIcon) && extraTargets.isEmpty) {
      return document;
    }
    final config =
        jsonDecode(jsonEncode(document.staruwConfig)) as Map<String, dynamic>;
    final u = config['staruw'] as Map;
    for (var start = 0; start < extraTargets.length; start += 64) {
      var id = 'globalParameterDefault${start ~/ 64}';
      while ((u['controls'] as List).any((c) => c['id'] == id)) {
        id = '${id}App';
      }
      (u['controls'] as List).add({
        'id': id,
        'label': '参数图标',
        'type': 'image',
        'scope': 'template',
        'stage': 'design',
        'targets': extraTargets.skip(start).take(64).toList(),
        'allowTypes': ['image/png', 'image/jpeg', 'image/svg+xml'],
        'allowShapes': ['rect', 'circle'],
      });
      u['defaults'][id] = {'mode': 'native'};
    }
    final hash = asset.split('.').first, rid = 'globalParameter$hash';
    final mime = {
      'svg': 'image/svg+xml',
      'png': 'image/png',
      'jpg': 'image/jpeg',
    }[asset.split('.').last]!;
    u['resources'][rid] = {
      'path': 'resources/global-parameter/$asset',
      'kind': 'image',
      'mediaType': mime,
      'sha256': hash,
    };
    final values = {...document.staruwValues},
        photoValues = {...document.staruwPhotoValues};
    for (final control in (u['controls'] as List).cast<Map>().where(
      controlsParameterIcon,
    )) {
      control.remove('when');
      // These are app-owned render copies. The global preference is explicitly
      // authorized to replace any parameter icon, independently of the original
      // template's import-menu filters. Keep the shared/source package untouched.
      control['allowTypes'] = {
        ...(control['allowTypes'] as List),
        mime,
      }.toList();
      final targetShape = shape == 'circle' ? 'circle' : 'rect';
      control['allowShapes'] = {
        ...(control['allowShapes'] as List),
        targetShape,
      }.toList();
      final value = {'mode': 'image', 'resourceId': rid, 'shape': targetShape};
      final id = control['id'] as String;
      if (control['scope'] == 'photo') {
        photoValues[id] = value;
      } else {
        values[id] = value;
      }
    }
    return document.copyWith(
      staruwConfig: config,
      staruwValues: values,
      staruwPhotoValues: photoValues,
      resourceBindings: {
        ...document.resourceBindings,
        'staruwAssets': {
          ...document.resourceBindings['staruwAssets'] as Map? ?? {},
          rid: asset,
        },
      },
    );
  }
}
