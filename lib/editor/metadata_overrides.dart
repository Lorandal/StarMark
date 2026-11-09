import 'package:watermark_engine/watermark_engine.dart';

/// Missing photo fields behave like their switches being off. Keep this derived
/// state out of saved template preferences so the next photo can show its EXIF.
Map<String, dynamic> effectiveMetadataOptions(
  PhotoMetadata metadata,
  Map<String, dynamic> options,
) => {
  ...options,
  if (!hasCaptureTime(metadata)) 'showTime': false,
  if (metadata.formatCoordinates().isEmpty &&
      '${options['locationAddress'] ?? ''}'.trim().isEmpty)
    'showLocation': false,
};

bool hasCaptureTime(PhotoMetadata metadata) {
  final match = RegExp(
    r'^(\d{4})[:\-](\d{2})[:\-](\d{2})[ T](\d{2}):(\d{2})',
  ).firstMatch(metadata.dateTime);
  if (match == null) return false;
  final year = int.parse(match[1]!), month = int.parse(match[2]!);
  final day = int.parse(match[3]!);
  final date = DateTime(year, month, day);
  return year > 0 &&
      date.year == year &&
      date.month == month &&
      date.day == day &&
      int.parse(match[4]!) < 24 &&
      int.parse(match[5]!) < 60;
}

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
