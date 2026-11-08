import 'package:watermark_engine/watermark_engine.dart';

/// Stable per-field watermark values; the photograph's EXIF is never mutated.
Map<String, String> photoParameterValues(PhotoMetadata metadata) {
  // Camera EXIF includes values such as F1.67 and physical21.77mm. Keep their
  // hundredths instead of rewriting the JSON-bound parameter to one decimal.
  String number(double value) => value
      .toStringAsFixed(2)
      .replaceFirst(RegExp(r'0+$'), '')
      .replaceFirst(RegExp(r'\.$'), '');
  return {
    'focal': metadata.displayFocal > 0 ? number(metadata.displayFocal) : '',
    'aperture': metadata.aperture > 0 ? number(metadata.aperture) : '',
    'speed': metadata.shutter.replaceFirst(RegExp(r's$'), ''),
    'iso': metadata.iso > 0 ? metadata.iso.round().toString() : '',
  };
}

Map<String, String> effectiveParameterValues(
  PhotoMetadata metadata,
  Object? rawOverrides, {
  bool allowExisting = false,
}) {
  final values = photoParameterValues(metadata);
  final overrides = rawOverrides is Map ? rawOverrides : const {};
  for (final key in values.keys) {
    final raw = overrides[key];
    final value = raw is String ? raw.trim() : '';
    if (value.isNotEmpty && (allowExisting || values[key]!.isEmpty)) {
      values[key] = value;
    }
  }
  return values;
}

String displayParameterValues(Map<String, String> values) => [
  if (values['focal']!.isNotEmpty) '${values['focal']}mm',
  if (values['aperture']!.isNotEmpty) 'f/${values['aperture']}',
  if (values['speed']!.isNotEmpty) '${values['speed']}s',
  if (values['iso']!.isNotEmpty) 'ISO ${values['iso']}',
].join('  ');
