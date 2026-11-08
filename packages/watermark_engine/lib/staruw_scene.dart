part of 'watermark_engine.dart';

/// Geometry in reference units (U), independent of the screen density.
class StaruwRect {
  final double x, y, width, height;
  const StaruwRect(this.x, this.y, this.width, this.height);
}

class StaruwGeometry {
  final int photoWidth, photoHeight;
  final Map canvas;
  late final double scale = math.min(photoWidth, photoHeight) / 1080;
  late final double w = photoWidth / scale, h = photoHeight / scale;
  late final double left = (canvas['insets']['left'] as num).toDouble(),
      top = (canvas['insets']['top'] as num).toDouble(),
      right = (canvas['insets']['right'] as num).toDouble(),
      bottom = (canvas['insets']['bottom'] as num).toDouble();
  StaruwGeometry(this.photoWidth, this.photoHeight, this.canvas) {
    if (photoWidth <= 0 || photoHeight <= 0) {
      throw const FormatException('无效照片尺寸');
    }
  }
  StaruwRect get all => StaruwRect(0, 0, left + w + right, top + h + bottom);
  StaruwRect get photo => StaruwRect(left, top, w, h);
  StaruwRect reference(String id, {StaruwRect? parent}) => switch (id) {
    'canvas' => all,
    'photo' => photo,
    'frameTop' => StaruwRect(0, 0, all.width, top),
    'frameBottom' => StaruwRect(0, top + h, all.width, bottom),
    'frameLeft' => StaruwRect(0, top, left, h),
    'frameRight' => StaruwRect(left + w, top, right, h),
    'parent' => parent ?? (throw const FormatException('缺少父级坐标')),
    _ => throw const FormatException('未知场景参考区域'),
  };
  static StaruwRect resolve(StaruwRect ref, Map bounds) {
    final a = bounds['anchor'] as List,
        p = bounds['pivot'] as List,
        o = bounds['offset'] as List,
        s = bounds['size'] as List;
    return StaruwRect(
      ref.x + a[0] * ref.width + o[0] - p[0] * s[0],
      ref.y + a[1] * ref.height + o[1] - p[1] * s[1],
      (s[0] as num).toDouble(),
      (s[1] as num).toDouble(),
    );
  }

  static int edge(double pixels) => (pixels + .5).floor();
  CanvasLayout get layout {
    final x = edge(left * scale), y = edge(top * scale);
    // Absolute edges, never a sum of independently rounded widths.
    return CanvasLayout(
      edge(all.width * scale),
      edge(all.height * scale),
      x,
      y,
      edge((left + w) * scale) - x,
      edge((top + h) * scale) - y,
    );
  }
}

/// Data belongs to a photo/draft. It is not a template default or real EXIF edit.
class StaruwPhotoData {
  final Map<String, dynamic> fields;
  const StaruwPhotoData(this.fields);
  factory StaruwPhotoData.fromMetadata(
    PhotoMetadata m, {
    Map<String, dynamic> overrides = const {},
  }) => StaruwPhotoData({
    if (m.make.trim().isNotEmpty) 'photo.make': m.make,
    if (m.model.trim().isNotEmpty) 'photo.model': m.model,
    if (m.focal > 0) 'exif.focalLengthMm': m.focal,
    if (m.focal35mm > 0) 'exif.focalLength35mm': m.focal35mm,
    if (m.aperture > 0) 'exif.aperture': m.aperture,
    if (m.exposure > 0) 'exif.exposureSeconds': m.exposure,
    if (m.iso > 0) 'exif.iso': m.iso,
    if (m.dateTime.isNotEmpty) 'capture.localTime': m.dateTime,
    if (m.latitude != null) 'gps.latitude': m.latitude,
    if (m.longitude != null) 'gps.longitude': m.longitude,
    ...overrides,
  });
  static double? positive(dynamic v) =>
      v is num && v.isFinite && v > 0 ? v.toDouble() : null;
  static String decimal(num value, int places) {
    final factor = math.pow(10, places);
    final rounded = (value.abs() * factor + .5 + 1e-10).floor() / factor;
    return '${value < 0 ? '-' : ''}${rounded.toStringAsFixed(places)}'
        .replaceFirst(RegExp(r'\.?0+$'), '');
  }

  String? format(String field, String format) {
    final value = fields[field];
    if (format == 'focalMm') {
      final n =
          positive(fields['exif.focalLength35mm']) ??
          positive(fields['exif.focalLengthMm']);
      return n == null ? null : '${decimal(n, 2)}mm';
    }
    if (value == null || value is String && value.isEmpty) return null;
    final n = positive(value);
    return switch (format) {
      'plain' =>
        value is num
            ? value == value.roundToDouble()
                  ? value.toInt().toString()
                  : value.toString()
            : value.toString(),
      'apertureF' => n == null ? null : 'f/${decimal(n, 2)}',
      'shutterSeconds' =>
        n == null
            ? null
            : n < 1
            ? '1/${(1 / n + .5).floor()}s'
            : '${decimal(n, 3)}s',
      'iso' => n == null ? null : 'ISO ${(n + .5).floor()}',
      'gpsDms' => _gps(value, field == 'gps.latitude'),
      'dateTimeDots' || 'dateTimeSlash' => _date(
        value.toString(),
        format == 'dateTimeDots' ? '.' : '/',
      ),
      _ => throw const FormatException('未知动态格式器'),
    };
  }

  static String? _date(String raw, String separator) {
    // Camera local time has no invented zone or current-time fallback.
    final m = RegExp(
      r'^(\d{4})[:./-](\d{2})[:./-](\d{2})[ T](\d{2}):(\d{2}):(\d{2})',
    ).firstMatch(raw);
    if (m == null) return null;
    final parts = [for (var i = 1; i <= 6; i++) int.parse(m[i]!)];
    final date = DateTime.utc(
      parts[0],
      parts[1],
      parts[2],
      parts[3],
      parts[4],
      parts[5],
    );
    if (date.year != parts[0] ||
        date.month != parts[1] ||
        date.day != parts[2] ||
        date.hour != parts[3] ||
        date.minute != parts[4] ||
        date.second != parts[5]) {
      return null;
    }
    return '${m[1]}$separator${m[2]}$separator${m[3]} ${m[4]}:${m[5]}:${m[6]}';
  }

  static String? _gps(dynamic value, bool latitude) {
    if (value is! num || !value.isFinite || value.abs() > (latitude ? 90 : 180)) {
      return null;
    }
    final hundredths = (value.abs() * 360000 + .5).floor(),
        deg = hundredths ~/ 360000,
        min = (hundredths % 360000) ~/ 6000,
        seconds = (hundredths % 6000) / 100;
    return '$deg°$min′${seconds.toStringAsFixed(2)}″${latitude ? (value < 0 ? 'S' : 'N') : (value < 0 ? 'W' : 'E')}';
  }

  String content(Map content) {
    if (content['literal'] case final String literal) return literal;
    return (content['parts'] as List)
        .map(
          (p) =>
              p['literal'] ??
              format(p['field'] as String, p['format'] as String),
        )
        .whereType<String>()
        .join(content['separator'] as String);
  }
}
