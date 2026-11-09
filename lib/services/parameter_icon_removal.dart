/// Remove icon choices only. The same bytes may still belong to a signature,
/// a package's original artwork, or an exported photo's recovery information.
dynamic withoutParameterIcons(dynamic value, Set<String> removed) {
  if (removed.isEmpty) return value;
  if (value is List) {
    return [for (final child in value) withoutParameterIcons(child, removed)];
  }
  if (value is! Map) return value;
  final next = <String, dynamic>{
    for (final entry in value.entries)
      entry.key as String: withoutParameterIcons(entry.value, removed),
  };
  final parameters = next['parameters'];
  if (parameters is Map && removed.contains(parameters['asset'])) {
    next.remove('parameters');
  }
  final assets =
      next['assets'] ?? (next['resourceBindings'] as Map?)?['staruwAssets'];
  if (assets is Map) {
    for (final key in ['values', 'staruwValues', 'staruwPhotoValues']) {
      final choices = next[key];
      if (choices is! Map) continue;
      for (final id in choices.keys.toList()) {
        final choice = choices[id];
        if (choice is Map &&
            choice.containsKey('shape') &&
            removed.contains(assets[choice['resourceId']])) {
          choices[id] = {'mode': 'native'};
        }
      }
    }
  }
  return next;
}
