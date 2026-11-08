// OEM CloudWatermarkMaker maps filter names to leica_looks_<name> and lens
// names to leica_lens_<name>. These are the names shipped in the original packs;
// STD/LUX and the other abbreviations remain in their original bitmap artwork.
const xiaomiLeicaLooks = [
  'standard',
  'vivid',
  'natural',
  'monochrome_natural',
  'monochrome_high_contrast',
  'classic',
  'contemporary',
  'blue',
  'teal',
  'brass',
  'eternal',
  'chrome',
  'i_model_a',
];

/// Convert the OEM's dynamic runtime bindings into the same option records used
/// by its explicit customize_settings. No source JSON or resource is rewritten.
List<Map<String, dynamic>> xiaomiLeicaSettings(
  List<dynamic> layouts,
  Iterable<String> resourceNames,
) {
  var textLooks = false, imageLooks = false, imageLens = false, cvLens = false;
  void visit(dynamic node) {
    if (node is List) {
      for (final child in node) {
        visit(child);
      }
    } else if (node is Map && node['enable'] != false) {
      final path = '${node['path'] ?? ''}';
      textLooks |= '${node['text'] ?? ''}'.startsWith('@filter=');
      imageLooks |= path.contains('path=@type_leica_looks');
      imageLens |= path.contains('path=@type_leica_lens');
      cvLens |= path.contains('@{cvLens}');
      visit(node['childViews']);
    }
  }

  visit(layouts);
  final resources = resourceNames.toSet();
  final settings = <Map<String, dynamic>>[];
  Map<String, dynamic> setting(
    String id,
    String label,
    List<Map<String, dynamic>> choices,
  ) => {
    'id': id,
    'label': label,
    'supportOff': true,
    'default': 'off',
    'options': choices,
  };
  Map<String, dynamic> choice(String id, String label, String base) => {
    'id': id,
    'description': label,
    'assetBase': base,
    if (resources.contains('${base}_black.webp'))
      'icon': '@img=${base}_black.webp',
    if (resources.contains('${base}_white.webp'))
      'icon_dark': '@img=${base}_white.webp',
  };
  bool paired(String base) =>
      resources.contains('${base}_black.webp') &&
      resources.contains('${base}_white.webp');
  if (textLooks || imageLooks) {
    final choices = [
      for (final name in xiaomiLeicaLooks)
        if (textLooks || paired('leica_looks_$name'))
          choice(
            name,
            'LEICA ${name.replaceAll('_', ' ').toUpperCase()}',
            'leica_looks_$name',
          ),
    ];
    if (choices.isNotEmpty) {
      settings.add(setting('leicaLooks', '徕卡标识', choices));
    }
  }
  if (imageLens || cvLens) {
    final prefix = imageLens ? 'leica_lens_' : 'cv_lens_';
    final choices = <Map<String, dynamic>>[];
    for (final path in resources.toList()..sort()) {
      if (!path.startsWith(prefix) || !path.endsWith('_black.webp')) continue;
      final base = path.substring(0, path.length - '_black.webp'.length);
      if (!paired(base)) continue;
      final name = base.substring(prefix.length);
      choices.add({
        ...choice(name.toLowerCase(), name.toUpperCase(), base),
        'lensName': name,
      });
    }
    if (choices.isNotEmpty) settings.add(setting('leicaLens', '徕卡镜头', choices));
  }
  return settings;
}
