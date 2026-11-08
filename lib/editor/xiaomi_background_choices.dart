/// Add materials without mutating OEM resources or changing foreground bindings.
List<Map<String, dynamic>> xiaomiBackgroundChoices(
  Map<String, dynamic> config,
) {
  final resource = config['wmRes'] as Map? ?? const {};
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
      // Some OEM frames fill padding via a foreground reference as well.
      // Replace those background fills, never the text using that same color.
      'starmarkBaseColor': '${base['value']}'.substring(7),
    });
  }
  return rows;
}
