import 'dart:convert';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:vector_graphics/vector_graphics.dart' as vg;
import 'package:watermark_engine/watermark_engine.dart';

/// Decoded once for the application or a package. Plans borrow these pictures.
class ModelLockupAssets {
  // The REDMI paths occupy y=1.42..78.59 inside their 80-unit viewBox.
  // REDMI Sans Test 0801 is a static Regular face. Its measured Latin/digit
  // body is -693.76..41.24 at 1000 em; do not reuse MiSans's cap-height ratio.
  static const _redmiTextSize = (78.59 - 1.42) / .736;
  static const _redmiTextBaseline = 78.59 - _redmiTextSize * .042;
  // Xiaomi's brand ink fills y=0..80. MiSans Medium's body, including round
  // overshoot, spans -758.33..16.67 at 1000 em. Keep descenders natural.
  static const _xiaomiTextSize = 80 / .776;
  static const _xiaomiTextBaseline = 80 - _xiaomiTextSize * .017;
  static const materialPrefix = 'materials/17_ultra_by_leica/';
  // Accepted only when reading older packages; never bundled or used to render.
  static const retiredKeys = [
    'leica/Leica.svg',
    '${materialPrefix}xiaomi_74380B.webp',
    '${materialPrefix}xiaomi_B66E18.webp',
    '${materialPrefix}xiaomi_CCFFFFFF_shadow.webp',
    '${materialPrefix}xiaomi_CE9238.webp',
    '${materialPrefix}xiaomi_DE1010.webp',
    '${materialPrefix}xiaomi_E2433B.webp',
    '${materialPrefix}xiaomi_E6A54F.webp',
    '${materialPrefix}xiaomi_F4CA32.webp',
    '${materialPrefix}xiaomi_F6C566.webp',
    '${materialPrefix}xiaomi_FFE099.webp',
    '${materialPrefix}xiaomi_FFF383.webp',
    '${materialPrefix}xiaomi_black.webp',
    '${materialPrefix}xiaomi_black_shadow.webp',
    '${materialPrefix}xiaomi_white.webp',
    '${materialPrefix}xiaomi_white_shadow.webp',
    '${materialPrefix}xiaomi_yellow.webp',
  ];
  static const resourceKeys = assetKeys;
  static const assetKeys = <String>[
    'models/Brand/Xiaomi.svg',
    'models/Brand/REDMI.svg',
    'models/0-9, S, T/0.svg',
    'models/0-9, S, T/1.svg',
    'models/0-9, S, T/2.svg',
    'models/0-9, S, T/3.svg',
    'models/0-9, S, T/4.svg',
    'models/0-9, S, T/5.svg',
    'models/0-9, S, T/6.svg',
    'models/0-9, S, T/7.svg',
    'models/0-9, S, T/8.svg',
    'models/0-9, S, T/9.svg',
    'models/0-9, S, T/S.svg',
    'models/0-9, S, T/T.svg',
    'models/0-9, S, T/X.svg',
    'models/Model/Civi.svg',
    'models/Model/Fold.svg',
    'models/Model/Max.svg',
    'models/Model/MIX Flip.svg',
    'models/Model/MIX Fold.svg',
    'models/Model/Pad.svg',
    'models/Model/Pro.svg',
    'models/Model/Ultra.svg',
    'models/Model/Lite.svg',
    'models/Model/Ti.svg',
    'models/Model/by Leica.svg',
    'leica/MarkC.svg',
    'leica/MarkS.svg',
  ];

  final Map<String, _Mark> _pictures;
  final Map<String, ModelLockup?> _plans = {};
  final List<TextPainter> _textPainters = [];
  final _VectorMaskCache _vectorMasks = _VectorMaskCache();
  bool _disposed = false;
  int _references = 1;

  ModelLockupAssets retain() {
    if (_disposed) throw StateError('Model lockup assets have been disposed');
    _references++;
    return this;
  }

  ModelLockupAssets._(this._pictures);

  @visibleForTesting
  int get debugRasterMaskBytes => _vectorMasks._pixels * 4;

  @visibleForTesting
  int get debugRasterMaskCount => _vectorMasks._entries.length;

  /// Visible logo geometry, excluding transparent padding and a soft shadow.
  /// Half of the peak alpha also works for uniformly translucent package logos.
  static Future<Rect> bitmapInkBounds(ui.Image image) async {
    final bytes = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
    if (bytes == null) {
      return Rect.fromLTWH(
        0,
        0,
        image.width.toDouble(),
        image.height.toDouble(),
      );
    }
    final data = bytes.buffer.asUint8List(
      bytes.offsetInBytes,
      bytes.lengthInBytes,
    );
    var peak = 0;
    for (var i = 3; i < data.length; i += 4) {
      peak = math.max(peak, data[i]);
    }
    if (peak == 0) return Rect.zero;
    final threshold = (peak / 2).ceil();
    var left = image.width, top = image.height, right = 0, bottom = 0;
    for (var y = 0; y < image.height; y++) {
      for (var x = 0; x < image.width; x++) {
        if (data[(y * image.width + x) * 4 + 3] < threshold) continue;
        left = math.min(left, x);
        top = math.min(top, y);
        right = math.max(right, x + 1);
        bottom = math.max(bottom, y + 1);
      }
    }
    return Rect.fromLTRB(
      left.toDouble(),
      top.toDouble(),
      right.toDouble(),
      bottom.toDouble(),
    );
  }

  static Future<ModelLockupAssets> load({
    Map<String, Uint8List> overrides = const {},
    bool loadMaterials = true,
  }) async {
    final pictures = <String, _Mark>{};
    try {
      for (final key in assetKeys) {
        final bytes =
            overrides[key] ??
            (await rootBundle.load('assets/marks/$key')).buffer.asUint8List();
        final source = utf8.decode(bytes);
        final decoded = await vg.vg.loadPicture(
          SvgStringLoader(inlineFills(source)),
          null,
        );
        pictures[key] = _Mark(decoded, decoded.size);
        final viewBox = RegExp(
          r'viewBox\s*=\s*"([^"]+)"',
        ).firstMatch(source)?[1]?.trim().split(RegExp(r'[\s,]+'));
        if (viewBox != null && viewBox.length == 4) {
          final size = Size(double.parse(viewBox[2]), double.parse(viewBox[3]));
          pictures[key] = _Mark(decoded, size);
        }
      }
      return ModelLockupAssets._(pictures);
    } catch (_) {
      for (final picture in pictures.values) {
        picture.picture.picture.dispose();
      }
      rethrow;
    }
  }

  ModelLockup? compose(
    PhotoMetadata metadata, {
    String modelOverride = '',
    bool includeBrand = true,
    String redmiFamily = 'RedmiSans',
    String xiaomiFamily = 'MiSans',
  }) {
    if (_disposed) throw StateError('Model lockup assets have been disposed');
    final manual = modelOverride.trim().isNotEmpty;
    final model = (manual ? modelOverride : metadata.model).trim().replaceAll(
      RegExp(r'\s+'),
      ' ',
    );
    final cacheKey = jsonEncode([
      metadata.make,
      model,
      manual,
      includeBrand,
      redmiFamily,
      xiaomiFamily,
    ]);
    if (_plans.containsKey(cacheKey)) return _plans[cacheKey];
    return _plans[cacheKey] = _compose(
      metadata.make,
      model,
      manual,
      includeBrand,
      redmiFamily,
      xiaomiFamily,
    );
  }

  ModelLockup? _compose(
    String make,
    String model,
    bool manual,
    bool includeBrand,
    String redmiFamily,
    String xiaomiFamily,
  ) {
    if (model.isEmpty) {
      return null;
    }
    final prefix = RegExp(
      r'^(xiaomi|redmi)(?:\s+|$)',
      caseSensitive: false,
    ).firstMatch(model);
    String brand;
    String tail;
    if (prefix != null) {
      brand = prefix[1]!.toLowerCase() == 'redmi' ? 'REDMI' : 'Xiaomi';
      tail = model.substring(prefix.end).trim();
    } else {
      // A freeform edit must not inherit a manufacturer from the photograph.
      if (manual) return null;
      final manufacturer = RegExp(
        r'^(xiaomi|redmi)(?:\s|$)',
        caseSensitive: false,
      ).firstMatch(make.trim());
      if (manufacturer == null) return null;
      brand = manufacturer[1]!.toLowerCase() == 'redmi' ? 'REDMI' : 'Xiaomi';
      final familyPrefix = brand == 'REDMI'
          ? r'^(?:[a-z]?\d|note\s*\d|turbo\s*\d|pad\b)'
          : r'^(?:\d|mix\b|civi\b|fold\b|pad\b|pro\b|max\b|ultra\b|note\b|cc\d|play\b|a\d)';
      if (!RegExp(familyPrefix, caseSensitive: false).hasMatch(model)) {
        return null;
      }
      tail = model;
    }
    if (tail.isEmpty) return null;
    final pieces = <_Piece>[];
    if (includeBrand) {
      pieces.add(
        _Piece.svg(brand, _Kind.brand, _pictures['models/Brand/$brand.svg']!),
      );
    }
    final warnings = <String>[];
    final tokens = brand == 'Xiaomi' ? _parse(tail, warnings) : <String>[];
    final textFallback = brand == 'REDMI' || warnings.isNotEmpty;
    if (textFallback) {
      final redmi = brand == 'REDMI';
      final text = TextPainter(
        text: TextSpan(
          text: tail,
          style: TextStyle(
            fontFamily: redmi ? redmiFamily : xiaomiFamily,
            fontSize: redmi ? _redmiTextSize : _xiaomiTextSize,
            height: 1,
            fontWeight: FontWeight.w400,
            fontVariations: redmi
                ? null
                : const [ui.FontVariation('wght', 380)],
            color: Colors.white,
          ),
        ),
        textDirection: TextDirection.ltr,
        maxLines: 1,
      )..layout();
      _textPainters.add(text);
      pieces.add(
        _Piece.text(
          tail,
          text,
          baseline: redmi ? _redmiTextBaseline : _xiaomiTextBaseline,
        ),
      );
    } else {
      if (tokens.isEmpty) return null;
      for (final token in tokens) {
        final digit = RegExp(r'^[0-9STX]$').hasMatch(token);
        final key = digit
            ? 'models/0-9, S, T/$token.svg'
            : 'models/Model/$token.svg';
        pieces.add(
          _Piece.svg(token, digit ? _Kind.digit : _Kind.word, _pictures[key]!),
        );
      }
    }
    var x = 0.0, height = 0.0, top = 0.0;
    var layoutTop = 0.0, layoutBottom = 0.0;
    _Piece? previous;
    for (final piece in pieces) {
      if (previous != null) x += _gap(previous, piece);
      final natural = piece.naturalSize.height > 80.01;
      final scale = natural ? 1.0 : 80 / piece.naturalSize.height;
      piece.size = Size(
        piece.naturalSize.width * scale,
        piece.naturalSize.height * scale,
      );
      final boxY = ['MIX Flip', 'by Leica'].contains(piece.key)
          ? 0.0
          : 80 - piece.size.height;
      // The supplied by Leica SVG's ordinary b/e/c/a body ends at y=84.93;
      // y continues to 104.76. Align the body, retaining both ascenders and
      // descender. MIX Flip's top and Ultra/Lite's bottom bleed stay unchanged.
      piece.offset = Offset(
        x,
        piece.key == 'by Leica' ? 80 - 84.93 * scale : boxY,
      );
      // Keep the previous layout envelope after raising the Leica ink. A
      // fixed-height image slot must not enlarge the entire model or reflow
      // its neighbors merely because the suffix now leaves bottom padding.
      layoutTop = math.min(layoutTop, boxY);
      layoutBottom = math.max(layoutBottom, boxY + piece.size.height);
      x += piece.size.width;
      top = math.min(top, piece.offset.dy);
      height = math.max(height, piece.offset.dy + piece.size.height);
      previous = piece;
    }
    if (top < 0) {
      for (final piece in pieces) {
        piece.offset -= Offset(0, top);
      }
    }
    return ModelLockup._(
      Size(x, math.max(height - top, layoutBottom - layoutTop)),
      pieces,
      List.unmodifiable(warnings),
      _vectorMasks,
    );
  }

  static const _words = [
    'MIX Flip',
    'MIX Fold',
    'Civi',
    'Fold',
    'Pad',
    'Pro',
    'Max',
    'Ultra',
    'by Leica',
    'Lite',
    'Ti',
  ];

  // The supplied Illustrator files use simple fill classes. flutter_svg does
  // not interpret style elements, so resolve those classes before decoding.
  static String inlineFills(String source) {
    final fills = <String, String>{};
    final styles = RegExp(r'<style\b[^>]*>([\s\S]*?)</style\s*>');
    for (final style in styles.allMatches(source)) {
      for (final rule in RegExp(
        r'\.([\w-]+)\s*\{([^}]+)\}',
      ).allMatches(style[1]!)) {
        final fill = RegExp(r'\bfill\s*:\s*([^;}]+)').firstMatch(rule[2]!);
        if (fill != null) fills[rule[1]!] = fill[1]!.trim();
      }
    }
    return source.replaceAll(styles, '').replaceAllMapped(
      RegExp(r'class\s*=\s*"([^"]+)"'),
      (match) {
        String? fill;
        for (final name in match[1]!.split(RegExp(r'\s+'))) {
          fill = fills[name] ?? fill;
        }
        return fill == null ? match[0]! : '${match[0]} fill="$fill"';
      },
    );
  }

  List<String> _parse(String text, List<String> warnings) {
    final result = <String>[];
    var offset = 0;
    while (offset < text.length) {
      if (text[offset] == ' ') {
        offset++;
        continue;
      }
      final remaining = text.substring(offset).toLowerCase();
      String? word;
      for (final candidate in _words) {
        if (remaining.startsWith(candidate.toLowerCase())) {
          word = candidate;
          break;
        }
      }
      if (word != null) {
        result.add(word);
        offset += word.length;
      } else {
        final character = text[offset].toUpperCase();
        if (RegExp(r'^[0-9STX]$').hasMatch(character)) {
          result.add(character);
        } else {
          warnings.add('Unknown model component: ${text[offset]}');
        }
        offset++;
      }
    }
    return result;
  }

  static const _opt = {
    '0': .93,
    '1': .07,
    '2': 2.60,
    '3': 2.19,
    '4': 10.68,
    '5': 2.88,
    '6': 2.77,
    '7': 7.15,
    '8': 3.27,
    '9': 2.33,
    'S': 3.63,
    'T': 24.01,
    'X': 0.0,
  };

  double _gap(_Piece left, _Piece right) {
    if (left.kind == _Kind.brand) return 45.6;
    // The supplied reference separates this suffix by half the 80-unit body.
    // Only its leading space changes; glyph scale and baseline stay intact.
    if (right.key == 'by Leica') return 40.0;
    if (left.kind == _Kind.digit && right.kind == _Kind.digit) {
      if (left.key == '1' || right.key == '1') return 10.4;
      return math.max(2.0, 10.4 - _opt[left.key]!);
    }
    return left.kind == _Kind.word && right.kind == _Kind.word ? 30.4 : 30.6;
  }

  void dispose() {
    if (_disposed) return;
    if (--_references > 0) return;
    _disposed = true;
    _vectorMasks.dispose();
    for (final plan in _plans.values) {
      plan?._disposeTextColors();
    }
    for (final text in _textPainters) {
      text.dispose();
    }
    for (final picture in _pictures.values) {
      picture.picture.picture.dispose();
    }
    _plans.clear();
  }
}

enum _Kind { brand, digit, word }

class _Mark {
  final vg.PictureInfo picture;
  final Size size;
  const _Mark(this.picture, this.size);
}

class _Piece {
  final String key;
  final _Kind kind;
  final vg.PictureInfo? svg;
  final TextPainter? text;
  final Offset textPaintOffset;
  final Map<int, TextPainter> _textColors = {};
  final Size naturalSize;
  Size size = Size.zero;
  Offset offset = Offset.zero;
  _Piece.svg(this.key, this.kind, _Mark mark)
    : svg = mark.picture,
      text = null,
      textPaintOffset = Offset.zero,
      naturalSize = mark.size;
  _Piece.text(this.key, TextPainter painter, {required double baseline})
    : kind = _Kind.word,
      svg = null,
      text = painter,
      textPaintOffset = Offset(
        0,
        baseline -
            painter.computeDistanceToActualBaseline(TextBaseline.alphabetic),
      ),
      naturalSize = Size(painter.width, 80);

  TextPainter textFor(Color color) =>
      _textColors.putIfAbsent(color.toARGB32(), () {
        final span = text!.text! as TextSpan;
        return TextPainter(
          text: TextSpan(
            text: span.text,
            style: span.style!.copyWith(color: color),
          ),
          textDirection: TextDirection.ltr,
          maxLines: 1,
        )..layout();
      });
}

/// A measured baseline-coordinate plan shared by preview and original export.
class ModelLockup {
  final Size size;
  final List<String> warnings;
  final List<_Piece> _pieces;
  final _VectorMaskCache _vectorMasks;
  final void Function(Canvas, Rect)? _catalogPaint;
  ModelLockup._(this.size, this._pieces, this.warnings, this._vectorMasks)
    : _catalogPaint = null;
  ModelLockup.catalog(this.size, void Function(Canvas, Rect) paint)
    : _catalogPaint = paint,
      _pieces = [],
      warnings = [],
      _vectorMasks = _VectorMaskCache();

  void paint(Canvas canvas, Rect rect, Color color, {double pixelRatio = 1}) {
    if (size.isEmpty || rect.isEmpty || color.a == 0) return;
    if (_catalogPaint != null) {
      _catalogPaint(canvas, rect);
      return;
    }
    final scale = math.min(rect.width / size.width, rect.height / size.height);
    canvas.save();
    try {
      canvas.translate(
        rect.left + (rect.width - size.width * scale) / 2,
        rect.top + (rect.height - size.height * scale) / 2,
      );
      canvas.scale(scale);
      if (_pieces.any((piece) => piece.svg != null)) {
        var paintedMask = false;
        try {
          final transform = canvas.getTransform();
          final mask = _vectorMasks.forPlan(this, transform, pixelRatio);
          if (mask != null) {
            // Undo the local transform for one physical-pixel-aligned blit.
            final a = transform[0],
                b = transform[1],
                c = transform[4],
                d = transform[5];
            final tx = transform[12], ty = transform[13];
            final det = a * d - b * c;
            canvas.save();
            try {
              canvas.transform(
                Float64List.fromList([
                  d / det,
                  -b / det,
                  0,
                  0,
                  -c / det,
                  a / det,
                  0,
                  0,
                  0,
                  0,
                  1,
                  0,
                  (c * ty - d * tx) / det,
                  (b * tx - a * ty) / det,
                  0,
                  1,
                ]),
              );
              canvas.scale(1 / pixelRatio);
              canvas.drawImage(
                mask.image,
                mask.origin,
                Paint()
                  ..filterQuality = FilterQuality.none
                  ..colorFilter = ColorFilter.mode(color, BlendMode.srcIn),
              );
              paintedMask = true;
            } finally {
              canvas.restore();
            }
          }
        } on ui.PictureRasterizationException {
          // toImageSync can fail later, at draw time. Retire all masks for this
          // asset set so a bad GPU allocation is not retried on every frame.
          _vectorMasks.dispose();
        }
        if (!paintedMask) {
          canvas.saveLayer(
            Offset.zero & size,
            Paint()..colorFilter = ColorFilter.mode(color, BlendMode.srcIn),
          );
          try {
            _paintVectors(canvas);
          } finally {
            canvas.restore();
          }
        }
      }
      for (final piece in _pieces) {
        if (piece.text == null) continue;
        piece
            .textFor(color)
            .paint(canvas, piece.offset + piece.textPaintOffset);
      }
    } finally {
      canvas.restore();
    }
  }

  void _paintVectors(Canvas canvas) {
    for (final piece in _pieces) {
      if (piece.svg == null) continue;
      canvas.save();
      canvas.translate(piece.offset.dx, piece.offset.dy);
      canvas.scale(
        piece.size.width / piece.naturalSize.width,
        piece.size.height / piece.naturalSize.height,
      );
      canvas.drawPicture(piece.svg!.picture);
      canvas.restore();
    }
  }

  void _disposeTextColors() {
    for (final piece in _pieces) {
      for (final painter in piece._textColors.values) {
        painter.dispose();
      }
    }
  }
}

class _VectorMask {
  final ui.Image image;
  final Offset phase;
  Offset origin;
  _VectorMask(this.image, this.phase, this.origin);
  int get pixels => image.width * image.height;
}

/// Cache the tight SVG layer after area reduction, never a full photograph.
/// Each size/content key retains only its current fractional pixel phase;
/// integer translations and tint changes reuse the same resolved image.
class _VectorMaskCache {
  static const _pixelBudget = 4 * 1024 * 1024; // 16 MiB retained RGBA.
  static const _temporaryPixelBudget =
      2 * 1024 * 1024; // 8 MiB high sample layer.
  static const _dimensionLimit = 8192;
  final _entries =
      <(ModelLockup, double, double, double, double), _VectorMask>{};
  int _pixels = 0;
  bool _disabled = false;

  _VectorMask? forPlan(
    ModelLockup plan,
    Float64List matrix,
    double pixelRatio,
  ) {
    if (_disabled ||
        !pixelRatio.isFinite ||
        pixelRatio <= 0 ||
        matrix[3] != 0 ||
        matrix[7] != 0) {
      return null;
    }
    final a = matrix[0] * pixelRatio, b = matrix[1] * pixelRatio;
    final c = matrix[4] * pixelRatio, d = matrix[5] * pixelRatio;
    final tx = matrix[12] * pixelRatio, ty = matrix[13] * pixelRatio;
    if (![a, b, c, d, tx, ty].every((v) => v.isFinite) ||
        (a * d - b * c).abs() < 1e-12) {
      return null;
    }
    Rect? local;
    for (final piece in plan._pieces.where((p) => p.svg != null)) {
      final bounds = piece.offset & piece.size;
      local = local?.expandToInclude(bounds) ?? bounds;
    }
    if (local == null || local.isEmpty) return null;
    final corners =
        [local.topLeft, local.topRight, local.bottomLeft, local.bottomRight]
            .map(
              (p) => Offset(a * p.dx + c * p.dy + tx, b * p.dx + d * p.dy + ty),
            )
            .toList();
    final left = corners.map((p) => p.dx).reduce(math.min).floorToDouble() - 2;
    final top = corners.map((p) => p.dy).reduce(math.min).floorToDouble() - 2;
    final width = (corners.map((p) => p.dx).reduce(math.max).ceil() - left + 2)
        .toInt();
    final height = (corners.map((p) => p.dy).reduce(math.max).ceil() - top + 2)
        .toInt();
    var samples = 8;
    while (samples >= 2 &&
        (width * height * samples * samples > _temporaryPixelBudget ||
            math.max(width, height) * samples > _dimensionLimit)) {
      samples ~/= 2;
    }
    if (samples < 2) return null;
    final key = (plan, a, b, c, d);
    final phase = Offset(tx - tx.floor(), ty - ty.floor());
    final origin = Offset(left, top);
    final existing = _entries.remove(key);
    if (existing != null) {
      if ((existing.phase - phase).distance < 1e-8 &&
          existing.image.width == width &&
          existing.image.height == height) {
        existing.origin = origin;
        _entries[key] = existing;
        return existing;
      }
      _pixels -= existing.pixels;
      existing.image.dispose();
    }
    while (_entries.isNotEmpty &&
        (_pixels + width * height > _pixelBudget || _entries.length >= 64)) {
      final oldest = _entries.remove(_entries.keys.first)!;
      _pixels -= oldest.pixels;
      oldest.image.dispose();
    }
    final recorder = ui.PictureRecorder();
    ui.PictureRecorder? resolveRecorder;
    ui.Picture? picture, resolvedPicture;
    ui.Image? high, resolved;
    try {
      final layer = Canvas(recorder)..scale(samples.toDouble());
      layer.transform(
        Float64List.fromList([
          a,
          b,
          0,
          0,
          c,
          d,
          0,
          0,
          0,
          0,
          1,
          0,
          tx - left,
          ty - top,
          0,
          1,
        ]),
      );
      plan._paintVectors(layer);
      picture = recorder.endRecording();
      high = picture.toImageSync(width * samples, height * samples);
      picture.dispose();
      picture = null;
      resolveRecorder = ui.PictureRecorder();
      final resolve = Canvas(resolveRecorder);
      final source = Rect.fromLTWH(
        0,
        0,
        high.width.toDouble(),
        high.height.toDouble(),
      );
      final destination = Rect.fromLTWH(
        0,
        0,
        width.toDouble(),
        height.toDouble(),
      );
      final samplePaint = Paint()
        ..filterQuality = FilterQuality.none
        ..blendMode = BlendMode.plus
        ..color = Colors.white.withValues(alpha: 1 / (samples * samples));
      for (var y = 0; y < samples; y++) {
        for (var x = 0; x < samples; x++) {
          resolve.drawImageRect(
            high,
            source,
            destination.shift(
              Offset((x + .5) / samples - .5, (y + .5) / samples - .5),
            ),
            samplePaint,
          );
        }
      }
      resolvedPicture = resolveRecorder.endRecording();
      resolved = resolvedPicture.toImageSync(width, height);
      final mask = _VectorMask(resolved, phase, origin);
      resolved = null; // Ownership transferred to the cache.
      _entries[key] = mask;
      _pixels += mask.pixels;
      assert(_pixels <= _pixelBudget);
      return mask;
    } finally {
      if (recorder.isRecording) recorder.endRecording().dispose();
      if (resolveRecorder?.isRecording ?? false) {
        resolveRecorder!.endRecording().dispose();
      }
      picture?.dispose();
      resolvedPicture?.dispose();
      high?.dispose();
      resolved?.dispose();
    }
  }

  void dispose() {
    _disabled = true;
    for (final mask in _entries.values) {
      mask.image.dispose();
    }
    _entries.clear();
    _pixels = 0;
  }
}
