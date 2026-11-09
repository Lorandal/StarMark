import 'dart:convert';
import 'dart:io';
import 'package:watermark_engine/watermark_engine.dart';
import 'staruw/contract.dart';
import 'staruw/controls.dart';
import 'parameter_icon_removal.dart';

/// Design only. Package config and resource bindings remain authoritative.
/// Captured EXIF, modelOverride, location text and source paths are never stored.
class TemplatePreferences {
  TemplatePreferences(this.directory);
  final Directory directory;
  Future<void> _tail = Future.value();
  Map<String, dynamic>? _records;
  static const fields = {
    'regionReplacements',
    'mode',
    'background',
    'border',
    'x',
    'y',
    'scale',
    'opacity',
    'blur',
    'tint',
    'font',
    'modelId',
    'caption',
    'enabled',
    'showParameters',
    'showModel',
    'showBrand',
    'textColor',
  };
  static const options = {
    'layoutGroup',
    'layout',
    'background',
    'glassBackground',
    'textColor',
    'showTime',
    'showLocation',
    'customizeSettings',
  };
  static Map<String, dynamic> capture(EditDocument document) {
    if (document.isStaruw) {
      if (document.staruwPreserved) return {};
      final u = document.staruwConfig!['staruw'] as Map;
      final addedAssets =
          document.resourceBindings['staruwAssets'] as Map? ?? {};
      final addedFonts = document.resourceBindings['staruwFonts'] as Map? ?? {};
      final addedIds = {...addedAssets.keys, ...addedFonts.keys};
      return {
        'staruw': {
          'identity': document.staruwConfig!['staruw']['identity'],
          'payloadDigest': document.staruwReport['payloadDigest'],
          'values': StaruwControls(document).captureTemplate(),
          'resources': {for (final id in addedIds) id: u['resources'][id]},
          'fonts': {
            for (final e
                in ((u['scene'] ?? u['xiaomi'])['fonts'] as Map).entries)
              if (addedIds.contains(e.value['resourceId'])) e.key: e.value,
          },
          'assets': addedAssets,
          'importedFonts': addedFonts,
        },
      };
    }
    final json = document.toJson();
    final native = document.xiaomiOptions;
    return jsonDecode(
          jsonEncode({
            for (final key in fields) key: json[key],
            // A manual selection can intentionally bypass a same-ID packaged
            // font snapshot. Persist that intent, never the removed file path.
            'fontBinding':
                !document.font.automatic &&
                    !(document.resourceBindings['fonts'] as Map? ?? {})
                        .containsKey(document.font.id)
                ? 'selected'
                : 'package',
            'xiaomiOptions': {
              for (final key in options)
                if (native.containsKey(key)) key: native[key],
              if (native['mix'] is Map)
                'mix': {
                  for (final entry in (native['mix'] as Map).entries)
                    if (entry.value is Map)
                      entry.key: {
                        'type': entry.value['type'],
                        // Only intentional custom text is design content.
                        if (entry.value['type'] == 'custom_text')
                          'text': entry.value['text'] ?? '',
                      },
                },
            },
          }),
        )
        as Map<String, dynamic>;
  }

  File get _file => File('${directory.path}/template-preferences-v1.json');
  Future<void> _load() async {
    if (_records != null) return;
    try {
      final json = jsonDecode(await _file.readAsString()) as Map;
      _records = json['version'] == 1
          ? Map<String, dynamic>.from(json['templates'] as Map)
          : {};
    } catch (_) {
      _records = {};
    }
  }

  Future<EditDocument?> remembered(String id, EditDocument factory) async {
    await _tail;
    await _load();
    try {
      final record = Map<String, dynamic>.from(_records![id] as Map);
      if (factory.isStaruw) {
        if (factory.staruwPreserved || record['staruw'] is! Map) return null;
        final saved = record['staruw'] as Map;
        if (!staruwEqual(
              saved['identity'],
              factory.staruwConfig!['staruw']['identity'],
            ) ||
            saved['payloadDigest'] != factory.staruwReport['payloadDigest']) {
          return null;
        }
        final config =
            jsonDecode(jsonEncode(factory.staruwConfig))
                as Map<String, dynamic>;
        final u = config['staruw'] as Map;
        for (final e in (saved['resources'] as Map? ?? {}).entries) {
          StaruwSchema('config').validate(e.key, definition: 'id');
          StaruwSchema('config').validate(e.value, definition: 'resource');
          staruwNeed(
            staruwSafePath(e.value['path'] as String),
            'E_PATH',
            '记忆资源路径无效',
          );
          if ((u['resources'] as Map).containsKey(e.key) &&
              !staruwEqual(u['resources'][e.key], e.value)) {
            return null;
          }
          u['resources'][e.key] = e.value;
        }
        final fonts = (u['scene'] ?? u['xiaomi'])['fonts'] as Map;
        for (final e in (saved['fonts'] as Map? ?? {}).entries) {
          StaruwSchema('config').validate(e.key, definition: 'id');
          StaruwSchema('config').validate(e.value, definition: 'font');
          if (fonts.containsKey(e.key) && !staruwEqual(fonts[e.key], e.value)) {
            return null;
          }
          fonts[e.key] = e.value;
        }
        final restored = factory.copyWith(
          staruwConfig: config,
          staruwValues: Map<String, dynamic>.from(saved['values'] as Map),
          staruwPhotoValues: {},
          resourceBindings: {
            ...factory.resourceBindings,
            'staruwAssets': saved['assets'] as Map? ?? {},
            'staruwFonts': saved['importedFonts'] as Map? ?? {},
          },
        );
        return restored.copyWith(
          staruwValues: StaruwControls(restored).captureTemplate(),
        );
      }
      for (final key in [
        'x',
        'y',
        'scale',
        'opacity',
        'blur',
        'tint',
        'textColor',
      ]) {
        if (record.containsKey(key) && record[key] is! num) return null;
      }
      for (final key in [
        'enabled',
        'showParameters',
        'showModel',
        'showBrand',
      ]) {
        if (record.containsKey(key) && record[key] is! bool) return null;
      }
      final fontBinding = record['fontBinding'];
      if (fontBinding != null &&
          fontBinding != 'selected' &&
          fontBinding != 'package') {
        return null;
      }
      final safe = capture(
        EditDocument.fromJson({'schemaVersion': 1, ...record}),
      );
      safe.remove('fontBinding');
      final rememberedOptions = safe['xiaomiOptions'] as Map;
      final mergedOptions = <String, dynamic>{
        ...factory.xiaomiOptions,
        ...rememberedOptions,
      };
      if (rememberedOptions['mix'] is Map) {
        final factoryMix = factory.xiaomiOptions['mix'] as Map? ?? {};
        mergedOptions['mix'] = {
          ...factoryMix,
          for (final entry in (rememberedOptions['mix'] as Map).entries)
            entry.key: _mergeMixEntry(
              factoryMix[entry.key],
              entry.value as Map,
            ),
        };
      }
      final bindings = Map<String, dynamic>.from(factory.resourceBindings);
      // Missing intent means a pre-fix v1 record: keep original package behavior.
      if (fontBinding == 'selected') {
        final selected = FontRef.fromJson(safe['font'] as Map<String, dynamic>);
        bindings['fonts'] = Map<String, dynamic>.from(
          bindings['fonts'] as Map? ?? {},
        )..remove(selected.id);
      }
      return EditDocument.fromJson({
        ...factory.toJson(),
        ...safe,
        'xiaomiOptions': mergedOptions,
        'resourceBindings': bindings,
      });
    } catch (_) {
      return null;
    }
  }

  static Map<String, dynamic> _mergeMixEntry(dynamic original, Map design) {
    final merged = <String, dynamic>{
      if (original is Map) ...Map<String, dynamic>.from(original),
      ...Map<String, dynamic>.from(design),
    };
    // A non-custom choice derives content from the photo; do not resurrect
    // stale text from a previously selected custom value or captured sample.
    if (merged['type'] != 'custom_text') merged.remove('text');
    return merged;
  }

  Future<void> save(String id, EditDocument document) {
    if (id.isEmpty) return Future.value();
    final captured = capture(
      document,
    ); // Capture before enqueue, never read a live controller later.
    final work = _tail.then((_) async {
      await _load();
      final next = {..._records!, id: captured};
      await directory.create(recursive: true);
      final temporary = File('${_file.path}.tmp');
      await temporary.writeAsString(
        jsonEncode({'version': 1, 'templates': next}),
        flush: true,
      );
      await temporary.rename(_file.path);
      _records = next;
    });
    _tail = work.catchError((Object _) {});
    return work;
  }

  Future<void> flush() => _tail;

  Future<void> removeParameterIcons(Set<String> removed) {
    final work = _tail.then((_) async {
      await _load();
      final next =
          withoutParameterIcons(_records!, removed) as Map<String, dynamic>;
      final temporary = File('${_file.path}.tmp');
      await directory.create(recursive: true);
      await temporary.writeAsString(
        jsonEncode({'version': 1, 'templates': next}),
        flush: true,
      );
      await temporary.rename(_file.path);
      _records = next;
    });
    _tail = work.catchError((Object _) {});
    return work;
  }
}
