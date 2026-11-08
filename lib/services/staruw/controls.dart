import 'dart:convert';
import 'package:watermark_engine/watermark_engine.dart';
import 'contract.dart';
import 'package_validation.dart';

/// Controls always operate on a fresh render copy. The package scene/source is
/// the immutable meaning of "native", not a canvas on which defaults are baked.
class StaruwControls {
  final EditDocument document;
  late final Map<String, dynamic> config = document.staruwConfig!;
  late final Map u = config['staruw'] as Map;
  late final List<Map> controls = (u['controls'] as List).cast<Map>();
  late final StaruwPackageValidator validator =
      StaruwPackageValidator.forControls(config);
  StaruwControls(this.document) {
    staruwNeed(
      document.isStaruw && !document.staruwPreserved,
      'E_CAPABILITY',
      '此包只允许保留原件',
    );
  }
  Map<String, dynamic> get templateValues => {
    ...Map<String, dynamic>.from(u['defaults'] as Map),
    ...document.staruwValues,
  };
  Map<String, dynamic> get allValues => {
    ...templateValues,
    ...document.staruwPhotoValues,
  };
  List<Map> get active {
    final values = allValues;
    final result = controls
        .where(
          (c) => (c['when'] as List? ?? []).every(
            (w) => staruwEqual(values[w['controlId']], w['equals']),
          ),
        )
        .toList();
    int rank(Map c) => switch (c['stage'] ?? 'design') {
      'layout' => 0,
      'photo' => 2,
      _ => 1,
    };
    // Stable declaration order within stages, independent of sort stability.
    return [
      for (final stage in [0, 1, 2]) ...result.where((c) => rank(c) == stage),
    ];
  }

  EditDocument set(String id, dynamic value) {
    final c = controls.where((c) => c['id'] == id).firstOrNull;
    staruwNeed(c != null, 'E_BINDING', '控件不存在');
    validator.controlValue(id, value);
    // Copy values at the transaction boundary; the UI must not retain a mutable
    // map that can change an undo entry after it has been captured.
    final owned = jsonDecode(jsonEncode(value));
    return c!['scope'] == 'photo'
        ? document.copyWith(
            staruwPhotoValues: {...document.staruwPhotoValues, id: owned},
          )
        : document.copyWith(staruwValues: {...templateValues, id: owned});
  }

  EditDocument clearPhoto(String id) {
    final c = controls.where((c) => c['id'] == id).firstOrNull;
    staruwNeed(c != null && c['scope'] == 'photo', 'E_SCOPE', '只能清除照片覆盖');
    return document.copyWith(
      staruwPhotoValues: {...document.staruwPhotoValues}..remove(id),
    );
  }

  Map<String, dynamic> captureTemplate() {
    final values = templateValues;
    final result = <String, dynamic>{};
    for (final c in controls.where((c) => c['scope'] == 'template')) {
      final id = c['id'] as String;
      validator.controlValue(id, values[id]);
      result[id] = values[id];
    }
    return Map<String, dynamic>.from(jsonDecode(jsonEncode(result)) as Map);
  }

  Iterable<({Map control, Map target, dynamic value})> get operations sync* {
    final values = allValues;
    for (final c in active) {
      final id = c['id'] as String;
      if (!values.containsKey(id)) continue; // No defaults for photo controls.
      final v = validator.controlValue(id, values[id]);
      for (final t in c['targets'] as List) {
        yield (control: c, target: t as Map, value: v);
      }
    }
  }

  Map<String, dynamic> get photoFields => {
    for (final op in operations)
      if (op.target['kind'] == 'photoField')
        op.target['id'] as String: op.value,
  };
  Map<String, dynamic> scene() {
    staruwNeed(u['scene'] is Map, 'E_BINDING', '模板没有通用场景');
    final s = jsonDecode(jsonEncode(u['scene'])) as Map<String, dynamic>;
    final nodes = <String, Map>{};
    void visit(List children) {
      for (final n in children) {
        nodes[n['id'] as String] = n as Map;
        if (n['children'] is List) visit(n['children'] as List);
      }
    }

    visit(s['nodes'] as List);
    final ops = operations.toList();
    final replaced = <String>{};
    void block(Map n) {
      for (final c in n['children'] as List? ?? []) {
        replaced.add(c['id'] as String);
        block(c as Map);
      }
    }

    for (final op in ops) {
      if (op.target['kind'] == 'node' &&
          op.target['property'] == 'modelRegion' &&
          op.value['mode'] != 'native') {
        block(nodes[op.target['id']]!);
      }
    }
    for (final op in ops) {
      final t = op.target, p = t['property'], v = op.value;
      if (t['kind'] == 'canvas') {
        s['canvas'][p] = v;
        continue;
      }
      if (t['kind'] != 'node' || replaced.contains(t['id'])) continue;
      final n = nodes[t['id']]!;
      switch (p) {
        case 'visible' || 'opacity' || 'rotation' || 'shape':
          n[p] = v;
        case 'offset' || 'size':
          n['bounds'][p] = v;
        case 'offsetX' || 'offsetY':
          n['bounds']['offset'][p == 'offsetX' ? 0 : 1] = v;
        case 'width' || 'height':
          n['bounds']['size'][p == 'width' ? 0 : 1] = v;
        case 'font':
          n[n['type'] == 'model' ? 'fallbackFontId' : 'fontId'] = v;
        case 'content':
          n['content'] = {'literal': v};
        case 'image':
          n['resourceId'] = v;
        case 'modelRegion':
          if (v['mode'] != 'native') n['replacement'] = v;
        case 'parameterIcon':
          if (v['mode'] == 'native') continue;
          final icon = n['type'] == 'image'
              ? n
              : (n['children'] as List).firstWhere(
                      (c) => c['slot'] == 'parameterIcon',
                    )
                    as Map;
          icon['resourceId'] = v['resourceId'];
          icon['shape'] = v['shape'];
          // A circular replacement crops inside the existing slot. Adjacent
          // text and the parent group remain exactly where the author put them.
          icon['replacementCircle'] = v['shape'] == 'circle';
          icon['fit'] = v['shape'] == 'circle' ? 'cover' : 'contain';
      }
    }
    return s;
  }
}
