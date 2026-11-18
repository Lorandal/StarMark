final _materialConfigs = Expando<Map<String, dynamic>>();

List<String> _lightGoldInk(String gold) => [
  '#FFFFFFFF',
  '#FF$gold',
  'E6A54F',
  '#BF$gold',
  '#FF604916',
];

/// Project the four fixed factory designs into the existing background selector.
/// Drafts and shared packages keep their original layout and resource bytes.
Map<String, dynamic> xiaomiMaterialConfig(Map<String, dynamic> config) {
  final meta = config['metadata'];
  if (meta is! Map) return config;
  final id = meta['id'];
  final gold = id == '111' && meta['name'] == '@wm_leica_111';
  final classic =
      const {'19', '71', '97'}.contains(id) &&
      meta['name'] == '@wm_classic_${id}_name';
  if (!gold && !classic) return config;
  final cached = _materialConfigs[config];
  if (cached != null) return cached;
  final resources = config['wmRes'];
  final backgrounds = resources is Map ? resources['background'] : null;
  final groups = config['layout_group'];
  if (resources is! Map ||
      resources['fonts'] is! Map ||
      backgrounds is! List ||
      backgrounds.length != 1 ||
      groups is! List ||
      groups.length != 1 ||
      groups.single is! Map) {
    return config;
  }
  final original = backgrounds.single;
  final layouts = groups.single['layout'];
  if ((groups.single as Map).length != 1 ||
      original is! Map ||
      original['name'] != (gold ? 'black' : 'transparent_white') ||
      original['value'] != (gold ? '@color=#FF141414' : '@color=#00FFFFFF') ||
      layouts is! List ||
      layouts.length != 1 ||
      layouts.single is! Map) {
    return config;
  }
  final root = layouts.single as Map;
  if (root['id'] != 'all_content' ||
      root['background'] != '@background=${original['name']}') {
    return config;
  }
  final yellow = id == '71';
  final defaultBase = gold || yellow ? 'black' : 'white';
  final accent = yellow ? '#FFFFC300' : '#FFCC1414';
  final baseValue = yellow ? '#FF141414' : '#FFFFFFFF';
  if (classic) {
    // The accent is composited on a full-width sibling painted immediately
    // underneath it. A differently structured imported layout owns its colors.
    final children = root['childViews'];
    if (children is! List) return config;
    final banner = children
        .whereType<Map>()
        .where((n) => n['id'] == 'watermark_container')
        .firstOrNull;
    final blocks = banner?['childViews'];
    if (blocks is! List ||
        blocks.length != 2 ||
        blocks[0] is! Map ||
        blocks[0]['id'] != 'right_bottom_group' ||
        blocks[0]['background'] != baseValue ||
        blocks[0]['layout_width'] != 'match_parent' ||
        blocks[1] is! Map ||
        blocks[1]['id'] != 'left_bottom_group' ||
        blocks[1]['background'] != accent) {
      return config;
    }
  }
  Map<String, dynamic> adaptNode(Map node) {
    final next = Map<String, dynamic>.from(node);
    switch (node['id']) {
      case 'all_content':
        if (gold) {
          next['background_optional'] = true;
        } else {
          // Only the inset banner receives the material. The photo stays clear.
          next['background'] = original['value'];
        }
      case 'right_bottom_group':
        if (classic && node['background'] == baseValue) {
          next['background'] = '@background=$defaultBase';
          next['background_optional'] = true;
        }
      case 'left_bottom_group':
        if (classic && node['background'] == accent) {
          // This block overlays the full-width material already painted below it.
          next['starmarkMaterialTint'] = yellow ? '#B3FFC300' : '#C2CC1414';
        }
    }
    if (node['childViews'] case final List children) {
      next['childViews'] = [
        for (final child in children) adaptNode(child as Map),
      ];
    }
    return next;
  }

  Map<String, dynamic> solid(String name) {
    final light = name == 'white';
    final base = light ? '#FFFFFFFF' : '#FF141414';
    return {
      'name': name,
      'value': '@color=$base',
      if (gold && !light) 'icon': original['icon'],
      if (gold && light) 'starmarkGoldWordmark': true,
      if (classic && name == defaultBase)
        'starmarkPreviousName': original['name'],
      'foreground': gold
          ? (light
                ? _lightGoldInk('A47B22')
                : [base, '#FFE6A54F', 'E6A54F', '#BFE6A54F', '#66E6A54F'])
          : [
              '#FF000000',
              '#00000000',
              yellow ? 'black' : 'white',
              light ? '#FF000000' : '#FFFFFFFF',
              light ? '#80000000' : '#80FFFFFF',
            ],
    };
  }

  final fonts = Map<String, dynamic>.from(resources['fonts'] as Map);
  for (final entry in (gold ? {'f2': 4} : {'f2': 3, 'f3': 4}).entries) {
    if (fonts[entry.key] case final Map font) {
      fonts[entry.key] = {...font, 'color': '@foreground=${entry.value}'};
    }
  }
  final bases = [
    solid(defaultBase),
    solid(defaultBase == 'white' ? 'black' : 'white'),
  ];
  final projected = {
    ...config,
    if (classic) 'starmarkCenterModelInk': true,
    'wmRes': {...resources, 'fonts': fonts, 'background': bases},
    'layout_group': [
      {
        ...groups.single as Map,
        'layout': [adaptNode(root)],
      },
    ],
  };
  _materialConfigs[config] = projected;
  return projected;
}

/// Add materials without mutating OEM resources.
List<Map<String, dynamic>> xiaomiBackgroundChoices(
  Map<String, dynamic> config,
) {
  final resource = xiaomiMaterialConfig(config)['wmRes'] as Map? ?? const {};
  final rows = (resource['background'] as List? ?? const [])
      .whereType<Map>()
      .map((row) => Map<String, dynamic>.from(row))
      .toList();
  Map<String, dynamic>? solid(bool light) => rows.where((row) {
    final match = RegExp(
      r'^@color=#([\da-fA-F]{6}|[\da-fA-F]{8})$',
    ).firstMatch('${row['value']}');
    if (match == null) return false;
    final hex = match[1]!;
    final argb = int.parse(hex.length == 6 ? 'ff$hex' : hex, radix: 16);
    if ((argb >> 24) != 255) return false;
    final channels = [(argb >> 16) & 255, (argb >> 8) & 255, argb & 255]
      ..sort();
    return channels.last - channels.first <= 12 &&
        (light ? channels.first >= 230 : channels.last <= 32);
  }).firstOrNull;
  final white = solid(true), black = solid(false);
  if (white == null || black == null) return rows;
  for (final light in [true, false]) {
    final tone = light ? 'light' : 'dark';
    if (rows.any(
      (r) => RegExp('@algo=${tone}_blur(?:;|\$)').hasMatch('${r['value']}'),
    )) {
      continue;
    }
    final base = light ? white : black;
    rows.add({
      ...base,
      'name': 'starmark_${tone}_blur',
      'description': light ? '白透模糊' : '黑透模糊',
      'icon': '', 'icon_dark': '',
      'value': '@algo=${tone}_blur',
      if (base['starmarkGoldWordmark'] == true)
        'foreground': _lightGoldInk('775B13'),
      // Some OEM frames fill padding via a foreground reference as well.
      // Replace those background fills, never the text using that same color.
      'starmarkBaseColor': '${base['value']}'.substring(7),
    });
  }
  return rows;
}
