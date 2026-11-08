import 'dart:ui' as ui;
import 'package:flutter/painting.dart';
import 'package:watermark_engine/watermark_engine.dart';

/// OEM `default` loads MiSansVF and passes JSON variation coordinates directly
/// to Typeface.Builder (um0.c$a.d -> xn0.b.a in the supplied MediaEditor APK).
/// Only an explicitly selected different face needs semantic weight conversion.
TextStyle xiaomiFontStyle({
  required Map<String, dynamic> definition,
  required FontRef selection,
  required PhotoMetadata metadata,
  required String defaultFamily,
  required Map<String, String> fonts,
}) {
  final spec = selection.resolveFor(metadata);
  final typeface = '${definition['typeface'] ?? 'default'}';
  var family = defaultFamily;
  final axes = <String, double>{};
  for (final m in RegExp(
    r"'([^']{4})'\s+(-?[\d.]+)",
  ).allMatches('${definition['variation'] ?? ''}')) {
    axes[m[1]!] = double.parse(m[2]!);
  }
  final declaredWeight = axes['wght'];
  final templateWeight = typeface == 'default'
      ? _miSansWeight(declaredWeight ?? 330)
      : typeface == 'oxanium'
      ? _oxaniumWeight(declaredWeight)
      : _androidWeight(declaredWeight ?? 400);
  if (!selection.automatic) {
    // A selected font replaces the face, not all the distinct weights in a JSON
    // template. Non-weight axes default to the selected font unless declared.
    for (final entry in spec.axes.entries) {
      if (entry.key != 'wght') axes.putIfAbsent(entry.key, () => entry.value);
    }
    if (spec.id == 'mi-sans') {
      axes['wght'] = typeface == 'default'
          ? declaredWeight ?? 330
          : _miSansAxis(templateWeight);
    } else if (spec.axes.containsKey('wght') || spec.id == 'oxanium') {
      axes['wght'] = templateWeight;
    } else {
      // Static faces (e.g. LG1056) have no variable axis to force globally.
      axes.remove('wght');
    }
  } else if (typeface.startsWith('@file=')) {
    final path = typeface.substring(6);
    family =
        fonts[path] ??
        (throw FormatException('Missing Xiaomi font resource: $path'));
    if (path.toLowerCase().contains('lg1056')) axes.remove('wght');
  } else if (typeface == 'oxanium') {
    family = fonts['oxanium'] ?? 'Oxanium';
    axes['wght'] = _oxaniumWeight(declaredWeight);
  } else if (typeface == 'default') {
    // OEM Android "default" is MiSans. The automatic photo-brand preference
    // belongs to custom templates; it must not rewrite original JSON typography.
    family = fonts['mi-sans'] ?? 'MiSans';
    axes['wght'] = declaredWeight ?? 330;
  } else if (fonts[typeface] != null) {
    family = fonts[typeface]!;
  } else {
    throw FormatException('Unsupported Xiaomi font typeface: $typeface');
  }
  return TextStyle(
    fontFamily: family,
    fontFamilyFallback: [fonts['mi-sans'] ?? 'MiSans'],
    // A variable axis controls the actual stroke; requesting synthetic bold as well doubles it.
    fontWeight: axes.containsKey('wght') || selection.automatic
        ? FontWeight.normal
        : FontWeight.values[((templateWeight / 100).round() - 1).clamp(0, 8)],
    fontStyle: !selection.automatic && spec.style == 'italic'
        ? FontStyle.italic
        : FontStyle.normal,
    fontVariations: axes.entries
        .map((e) => ui.FontVariation(e.key, e.value))
        .toList(),
  );
}

double _androidWeight(double value) => value % 50 == 1 ? value - 1 : value;
double _oxaniumWeight(double? value) =>
    value == 400 || value == 700 ? value! : 500;

double _miSansWeight(double axis) {
  const stops = [
    (150.0, 100.0),
    (200.0, 200.0),
    (250.0, 300.0),
    (305.0, 350.0),
    (330.0, 400.0),
    (380.0, 500.0),
    (450.0, 600.0),
    (520.0, 650.0),
    (630.0, 700.0),
    (700.0, 900.0),
  ];
  if (axis <= stops.first.$1) return stops.first.$2;
  for (var i = 1; i < stops.length; i++) {
    final a = stops[i - 1], b = stops[i];
    if (axis <= b.$1) {
      return a.$2 + (b.$2 - a.$2) * (axis - a.$1) / (b.$1 - a.$1);
    }
  }
  return stops.last.$2;
}

double _miSansAxis(double weight) {
  const stops = [
    (100.0, 150.0),
    (200.0, 200.0),
    (300.0, 250.0),
    (350.0, 305.0),
    (400.0, 330.0),
    (500.0, 380.0),
    (600.0, 450.0),
    (650.0, 520.0),
    (700.0, 630.0),
    (900.0, 700.0),
  ];
  if (weight <= stops.first.$1) return stops.first.$2;
  for (var i = 1; i < stops.length; i++) {
    final a = stops[i - 1], b = stops[i];
    if (weight <= b.$1) {
      return a.$2 + (b.$2 - a.$2) * (weight - a.$1) / (b.$1 - a.$1);
    }
  }
  return stops.last.$2;
}
