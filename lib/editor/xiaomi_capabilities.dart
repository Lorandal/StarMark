import 'package:watermark_engine/watermark_engine.dart';
import 'xiaomi_leica_choices.dart';
import 'xiaomi_background_choices.dart';

/// The first mix selector is the OEM default. Its other clauses configure that
/// value; they do not concatenate time, location and EXIF into one string.
String? xiaomiTextBinding(String text) {
  if (text.startsWith('@mix=')) {
    final kind = text.substring(5).split(';').first;
    return {'time', 'exif', 'location', 'custom_text'}.contains(kind)
        ? (kind == 'custom_text' ? 'custom' : kind)
        : null;
  }
  if (text.startsWith('@greeting=')) return 'custom';
  for (final kind in ['custom', 'model', 'exif', 'time', 'location']) {
    if (text.startsWith('@$kind=')) return kind;
  }
  return null;
}

/// Every selected mix node has its own OEM content choice and custom text.
class XiaomiMixField {
  final Map<String, dynamic> node;
  const XiaomiMixField(this.node);
  String get id => node['id'] as String;
  String get defaultType =>
      (node['text'] as String).substring(5).split(';').first;
  List<String> get types => [
    'off',
    'time',
    'location_latlng',
    'custom_text',
    'exif',
  ];
  Map state(Map<String, dynamic> options) =>
      options['mix'] is Map && (options['mix'] as Map)[id] is Map
      ? (options['mix'] as Map)[id] as Map
      : const {};
  String selectedType(
    Map<String, dynamic> options, {
    bool showParameters = true,
  }) {
    final value = state(options)['type'];
    if (types.contains(value)) return value as String;
    if (node['optional'] == true &&
        ((defaultType == 'time' && options['showTime'] == false) ||
            (defaultType == 'exif' && !showParameters))) {
      return 'off';
    }
    return defaultType;
  }
}

/// Editing permissions come from the selected OEM definitions, not from the
/// presence of text, colors, coordinates or other rendered values.
class XiaomiCapabilities {
  final bool supportsScale;
  final Set<String> scalableNodeIds;
  final bool canEditCaption, canEditModel, canEditParameters;
  final bool canToggleModel, canToggleParameters, canToggleBrand;
  final bool canToggleTime, canToggleLocation;
  final List<XiaomiLayoutChoice> layoutChoices;
  final List<Map<String, dynamic>> backgroundChoices;
  final List<Map<String, dynamic>> customizeSettings;
  final List<XiaomiMixField> mixFields;
  final bool limitsCaptionWidth;
  final int groupIndex;
  final Iterable<String> _resourceNames;

  const XiaomiCapabilities._({
    required this.supportsScale,
    required this.scalableNodeIds,
    required this.canEditCaption,
    required this.canEditModel,
    required this.canEditParameters,
    required this.canToggleModel,
    required this.canToggleParameters,
    required this.canToggleBrand,
    required this.canToggleTime,
    required this.canToggleLocation,
    required this.layoutChoices,
    required this.backgroundChoices,
    required this.customizeSettings,
    required this.mixFields,
    required this.limitsCaptionWidth,
    required this.groupIndex,
    required Iterable<String> resourceNames,
  }) : _resourceNames = resourceNames;

  factory XiaomiCapabilities.fromConfig(
    Map<String, dynamic> config, [
    Map<String, dynamic> options = const {},
    Iterable<String> resourceNames = const [],
  ]) {
    final groups = config['layout_group'] as List? ?? [];
    final choice = options['layoutGroup'];
    final index = choice is num
        ? choice.toInt()
        : choice is String
        ? groups.indexWhere(
            (group) =>
                group is Map &&
                (group.containsKey(choice) || group['id'] == choice),
          )
        : 0;
    if (index < 0 || index >= groups.length || groups[index] is! Map) {
      throw const FormatException('Unknown Xiaomi layout group');
    }
    final group = groups[index] as Map;
    final selected = options['layout'] == null
        ? group.values.whereType<List>().firstOrNull
        : group[options['layout']];
    if (selected is! List || selected.isEmpty) {
      throw const FormatException('Xiaomi layout is missing');
    }
    final toggles = <String>{}, edits = <String>{}, scalable = <String>{};
    final settingIds = <String>{};
    final mixFields = <XiaomiMixField>[];
    var limitsCaptionWidth = false;
    var scale = false, optionalBackground = false;
    const settings = {
      'type_time': 'time',
      'type_location': 'location',
      'type_model': 'model',
      'type_exif': 'exif',
    };
    String? binding(Map node) {
      return xiaomiTextBinding('${node['text'] ?? ''}');
    }

    void visit(dynamic value, Set<String> inherited) {
      if (value is List) {
        for (final node in value) {
          visit(node, inherited);
        }
      } else if (value is Map && value['enable'] != false) {
        final text = '${value['text'] ?? ''}';
        final isMix = text.startsWith('@mix=') && value['id'] is String;
        if (isMix &&
            value['optional'] == true &&
            ['mix_text_1', 'mix_text_2'].contains(value['id']) &&
            !mixFields.any((field) => field.id == value['id'])) {
          mixFields.add(XiaomiMixField(Map<String, dynamic>.from(value)));
        }
        if (text.startsWith('@greeting=')) limitsCaptionWidth = true;
        final setting = RegExp(
          r'(?:^|;)customize_settings=([^;]+)',
        ).firstMatch('${value['path'] ?? ''}')?.group(1);
        if (setting != null) settingIds.add(setting);
        final optional = {...inherited};
        final kind = binding(value);
        if (value['optional'] == true) {
          final category = settings[value['setting_optional_type']];
          if (category != null) optional.add(category);
          if (kind != null) optional.add(kind);
          if (value['type'] == 'ImageView' &&
              ['logo', 'leica', 'brand_leica'].contains(value['id'])) {
            toggles.add('brand');
          }
        }
        if (!isMix && kind != null && optional.contains(kind)) {
          toggles.add(kind);
        }
        // @custom explicitly describes user-supplied text. Metadata bindings
        // need an explicit editable declaration; optional only permits hiding.
        if (!isMix &&
            (kind == 'custom' || (kind != null && value['editable'] == true))) {
          edits.add(kind!);
        }
        if (value['support_scale'] == true) {
          scale = true;
          if (value['id'] is String) scalable.add(value['id'] as String);
        }
        if (value['background_optional'] == true) optionalBackground = true;
        visit(value['childViews'], optional);
      }
    }

    visit(selected, {});
    final wmRes = config['wmRes'] as Map? ?? {};
    final orientation = wmRes['orientation'] as Map? ?? {};
    List<String> tabs(String key) =>
        ((orientation[key] as Map? ?? {})['tab'] as List? ?? [])
            .whereType<String>()
            .toList();
    final vertical = tabs('orientation_vertical'),
        horizontal = tabs('orientation_horizontal');
    final names = {
      ...tabs('orientation_border'),
      ...vertical,
      ...horizontal,
      for (final v in vertical)
        for (final h in horizontal) '${v}_$h',
    };
    final layouts = <XiaomiLayoutChoice>[];
    for (var i = 0; i < groups.length; i++) {
      if (groups[i] is! Map) continue;
      for (final entry in (groups[i] as Map).entries) {
        if (entry.value is List && names.contains(entry.key)) {
          layouts.add(XiaomiLayoutChoice(i, entry.key.toString()));
        }
      }
    }
    return XiaomiCapabilities._(
      supportsScale: scale,
      scalableNodeIds: Set.unmodifiable(scalable),
      canEditCaption: edits.contains('custom'),
      canEditModel: edits.contains('model'),
      canEditParameters: edits.contains('exif'),
      canToggleModel: toggles.contains('model'),
      canToggleParameters: toggles.contains('exif'),
      canToggleBrand: toggles.contains('brand'),
      canToggleTime: toggles.contains('time'),
      canToggleLocation: toggles.contains('location'),
      layoutChoices: List.unmodifiable(
        layouts.length > 1 ? layouts : <XiaomiLayoutChoice>[],
      ),
      backgroundChoices: List.unmodifiable(
        optionalBackground
            ? xiaomiBackgroundChoices(config)
            : <Map<String, dynamic>>[],
      ),
      customizeSettings: List.unmodifiable([
        ...(wmRes['customize_settings'] as List? ?? [])
            .whereType<Map>()
            .where((row) => settingIds.contains(row['id']))
            .map((row) => Map<String, dynamic>.from(row)),
        ...xiaomiLeicaSettings(selected, resourceNames),
      ]),
      mixFields: List.unmodifiable(mixFields),
      limitsCaptionWidth: limitsCaptionWidth,
      groupIndex: index,
      resourceNames: resourceNames,
    );
  }

  bool allowsField(String field) => switch (field) {
    'enabled' || 'xiaomiOptions' => true,
    'scale' => supportsScale,
    'caption' => canEditCaption,
    // The user may correct the displayed device name for every watermark.
    'modelOverride' => true,
    'parametersOverride' => canEditParameters,
    'showModel' => canToggleModel,
    'showParameters' => canToggleParameters,
    'showBrand' => canToggleBrand,
    _ => false,
  };

  bool allowsOption(String field) => switch (field) {
    'layoutGroup' || 'layout' => layoutChoices.isNotEmpty,
    'background' => backgroundChoices.length > 1,
    'glassBackground' => backgroundChoices.any(
      (row) => '${row['value']}'.contains('blur'),
    ),
    // Values are subsequently restricted to missing EXIF by the bridge and
    // renderer, unless the original configuration explicitly permits editing.
    'exifOverrides' => true,
    'mix' => mixFields.isNotEmpty,
    'customizeSettings' => customizeSettings.any(
      (setting) =>
          (setting['options'] as List? ?? []).length > 1 ||
          setting['supportOff'] == true,
    ),
    'showTime' => canToggleTime,
    'showLocation' => canToggleLocation,
    _ => false,
  };

  /// A render-only copy makes old drafts obey today's OEM permissions without
  /// altering saved history, source EXIF or the user's explicitly selected face.
  EditDocument normalizeDocument(EditDocument document) {
    if (document.xiaomiConfig == null) return document;
    final options = Map<String, dynamic>.from(document.xiaomiOptions);
    if (!layoutChoices.any(
      (choice) =>
          choice.groupIndex == groupIndex &&
          (!options.containsKey('layout') || choice.name == options['layout']),
    )) {
      options.remove('layoutGroup');
      options.remove('layout');
    }
    var effective = XiaomiCapabilities.fromConfig(
      document.xiaomiConfig!,
      options,
      _resourceNames,
    );
    // Preserve legacy global toggles as individual choices when opening an old
    // draft. New mix menus never toggle an unrelated required metadata node.
    for (final field in effective.mixFields) {
      if (field.state(options)['type'] == null &&
          field.selectedType(
                options,
                showParameters: document.showParameters,
              ) ==
              'off') {
        options['mix'] = {
          ...(options['mix'] is Map ? options['mix'] as Map : {}),
          field.id: {...field.state(options), 'type': 'off'},
        };
      }
    }
    const guarded = [
      'background',
      'glassBackground',
      'exifOverrides',
      'textColor',
      'locationAddress',
      'filterName',
      'showTime',
      'showLocation',
      'customizeSettings',
      'mix',
    ];
    for (final key in guarded) {
      if (!effective.allowsOption(key)) options.remove(key);
    }
    if (options.containsKey('background') &&
        !effective.backgroundChoices.any(
          (row) => row['name'] == options['background'],
        )) {
      options.remove('background');
    }
    if (options['customizeSettings'] is Map) {
      final selected = Map<String, dynamic>.from(options['customizeSettings']);
      selected.removeWhere((id, value) {
        final setting = effective.customizeSettings
            .where((row) => row['id'] == id)
            .firstOrNull;
        return setting == null ||
            !(value == 'off' && setting['supportOff'] == true) &&
                !(setting['options'] as List? ?? []).whereType<Map>().any(
                  (row) => row['id'] == value,
                );
      });
      options['customizeSettings'] = selected;
      // Runtime aliases are render-only. The saved document keeps the user's
      // validated style selection; filter/lens metadata is never fabricated.
      for (final setting in effective.customizeSettings) {
        final id = setting['id'];
        if (!selected.containsKey(id)) continue;
        final choice = (setting['options'] as List? ?? [])
            .whereType<Map>()
            .where((row) => row['id'] == selected[id])
            .firstOrNull;
        if (id == 'leicaLooks') {
          options.remove('filterName');
          options.remove('@type_leica_looks');
          if (choice != null) {
            options['filterName'] = '${choice['id']}'.replaceAll('_', ' ');
            options['@type_leica_looks'] = choice['assetBase'];
          }
        } else if (id == 'leicaLens') {
          options.remove('cvLens');
          options.remove('@type_leica_lens');
          if (choice != null) {
            options['cvLens'] = choice['lensName'];
            options['@type_leica_lens'] = choice['assetBase'];
          }
        }
      }
    }
    if (options['mix'] is Map) {
      final states = <String, dynamic>{};
      for (final field in effective.mixFields) {
        final state = field.state(options);
        if (state.isEmpty) continue;
        states[field.id] = {
          if (field.types.contains(state['type'])) 'type': state['type'],
          if (state['text'] is String) 'text': state['text'],
        };
      }
      options['mix'] = states;
    } else {
      options.remove('mix');
    }
    effective = XiaomiCapabilities.fromConfig(
      document.xiaomiConfig!,
      options,
      _resourceNames,
    );
    return document.copyWith(
      x: .5,
      y: .94,
      opacity: 1,
      blur: .035,
      tint: .45,
      scale:
          effective.supportsScale && [.85, 1.0, 1.15].contains(document.scale)
          ? document.scale
          : 1,
      textColor: 0xffffffff,
      caption: effective.canEditCaption ? document.caption : '',
      modelId: document.modelId,
      modelOverride: document.modelOverride,
      parametersOverride: effective.canEditParameters
          ? document.parametersOverride
          : '',
      showModel: effective.canToggleModel ? document.showModel : true,
      showParameters: effective.canToggleParameters
          ? document.showParameters
          : true,
      showBrand: effective.canToggleBrand ? document.showBrand : true,
      xiaomiOptions: options,
    );
  }
}

class XiaomiLayoutChoice {
  final int groupIndex;
  final String name;
  const XiaomiLayoutChoice(this.groupIndex, this.name);
}
