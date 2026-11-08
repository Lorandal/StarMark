import 'dart:ui' as ui;
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:vector_graphics/vector_graphics.dart' as vg;
import 'package:watermark_engine/watermark_engine.dart';
import '../services/photo_asset.dart';
import 'xiaomi_renderer.dart';
import 'region_replacements.dart';
import 'model_lockup.dart';
import 'metadata_overrides.dart';
import 'staruw_renderer.dart';

class RenderAssets {
  final StaruwRenderPlan? scene;
  final XiaomiRenderPlan? xiaomi;
  final Map<String, RegionArtwork> regionAssets;
  final ui.Image photo;
  final ui.Image? background;
  final bool backgroundToned;
  final vg.PictureInfo? model, brand;
  final ModelLockup? modelLockup;
  final String family;
  final String fallbackFamily;
  const RenderAssets(
    this.photo, {
    this.background,
    this.backgroundToned = false,
    this.model,
    this.brand,
    this.family = 'Oxanium',
    this.fallbackFamily = 'MiSans',
    this.xiaomi,
    this.scene,
    this.regionAssets = const {},
    this.modelLockup,
  });
}

class WatermarkPainter extends CustomPainter {
  final EditDocument document;
  final PhotoAsset asset;
  final RenderAssets images;
  final bool original;
  final double pixelRatio;
  final ValueChanged<Rect>? onDecorationBounds;
  const WatermarkPainter(
    this.document,
    this.asset,
    this.images, {
    this.original = false,
    this.pixelRatio = 1,
    this.onDecorationBounds,
  });

  Rect? measureDecorationBounds() {
    if (images.scene != null) return null;
    if (images.xiaomi != null) return images.xiaomi!.decorationBounds;
    Rect? result;
    final recorder = ui.PictureRecorder();
    final layout = document.layout(asset.width, asset.height);
    WatermarkPainter(
      document,
      asset,
      images,
      onDecorationBounds: (rect) => result = rect,
    )._watermark(
      Canvas(recorder),
      Rect.fromLTWH(0, 0, layout.width.toDouble(), layout.height.toDouble()),
    );
    recorder.endRecording().dispose();
    return result;
  }

  @override
  void paint(Canvas canvas, Size size) => _paint(canvas, size);

  /// Keep original photo pixels out of Flutter's 8-bit sRGB readback.
  /// Android composites this premultiplied layer over the original ICC bitmap.
  void paintDecorationLayer(Canvas canvas, Size size) =>
      _paint(canvas, size, decorationOnly: true);

  void paintBackgroundLayer(Canvas canvas, Size size) =>
      _paint(canvas, size, backgroundOnly: true);

  void _paint(
    Canvas canvas,
    Size size, {
    bool decorationOnly = false,
    bool backgroundOnly = false,
  }) {
    if (document.enabled && images.scene != null) {
      images.scene!.paint(canvas, size, original: original,
          decorationOnly: decorationOnly, backgroundOnly: backgroundOnly);
      return;
    }
    if (document.enabled && images.xiaomi != null) {
      images.xiaomi!.paint(
        canvas,
        size,
        original: original,
        pixelRatio: pixelRatio,
        decorationOnly: decorationOnly,
        backgroundOnly: backgroundOnly,
      );
      return;
    }
    final d = document;
    final layout = d.layout(asset.width, asset.height);
    canvas.save();
    canvas.scale(size.width / layout.width, size.height / layout.height);
    final all = Rect.fromLTWH(
      0,
      0,
      layout.width.toDouble(),
      layout.height.toDouble(),
    );
    if (d.enabled && d.mode == WatermarkMode.frame && !original) {
      final bg = images.background;
      final dark =
          d.background == BackgroundStyle.black ||
          d.background == BackgroundStyle.darkBlur ||
          d.background == BackgroundStyle.darkGlass;
      canvas.drawRect(all, Paint()..color = dark ? Colors.black : Colors.white);
      if (bg != null &&
          ![
            BackgroundStyle.white,
            BackgroundStyle.black,
          ].contains(d.background)) {
        paintImage(
          canvas: canvas,
          rect: all,
          image: bg,
          fit: BoxFit.cover,
          filterQuality: FilterQuality.high,
        );
        if (!images.backgroundToned) {
          canvas.drawRect(
            all,
            Paint()
              ..color = (dark ? Colors.black : Colors.white).withValues(
                alpha: d.tint,
              ),
          );
        }
      }
    }
    if (backgroundOnly) {
      canvas.restore();
      return;
    }
    final photoRect = Rect.fromLTWH(
      layout.photoX.toDouble(),
      layout.photoY.toDouble(),
      asset.width.toDouble(),
      asset.height.toDouble(),
    );
    if (decorationOnly) {
      canvas.drawRect(photoRect, Paint()..blendMode = BlendMode.clear);
    } else {
      paintImage(
        canvas: canvas,
        rect: photoRect,
        image: images.photo,
        fit: BoxFit.fill,
        filterQuality: FilterQuality.high,
      );
    }
    if (d.enabled && !original) _watermark(canvas, all);
    canvas.restore();
  }

  /// Linear coverage for HDR reconstruction: only surviving original-photo pixels
  /// contribute HDR light. Frame materials and watermark ink remain SDR.
  void paintPhotoContribution(Canvas canvas, Size size) {
    if (document.enabled && images.scene != null) {
      images.scene!.paint(canvas, size, photoContribution: true);
      return;
    }
    if (document.enabled && images.xiaomi != null) {
      images.xiaomi!.paint(canvas, size, photoContribution: true);
      return;
    }
    final layout = document.layout(asset.width, asset.height);
    canvas.save();
    canvas.scale(size.width / layout.width, size.height / layout.height);
    final all = Rect.fromLTWH(
      0,
      0,
      layout.width.toDouble(),
      layout.height.toDouble(),
    );
    canvas.drawRect(all, Paint()..color = Colors.black);
    canvas.drawRect(
      Rect.fromLTWH(
        layout.photoX.toDouble(),
        layout.photoY.toDouble(),
        layout.photoWidth.toDouble(),
        layout.photoHeight.toDouble(),
      ),
      Paint()..color = Colors.white,
    );
    if (document.enabled) {
      canvas.saveLayer(
        all,
        Paint()
          ..colorFilter = const ColorFilter.mode(Colors.black, BlendMode.srcIn),
      );
      _watermark(canvas, all);
      canvas.restore();
    }
    canvas.restore();
  }

  void _watermark(Canvas canvas, Rect all) {
    final d = document;
    final font = d.font.resolveFor(asset.metadata);
    final short = math.min(asset.width, asset.height).toDouble();
    final fs = short * .022 * d.scale;
    final selectedColor = Color(d.textColor);
    final color = selectedColor.withValues(alpha: selectedColor.a * d.opacity);
    TextPainter text(String value, {double? fontSize}) => TextPainter(
      text: TextSpan(
        text: value,
        style: TextStyle(
          fontFamily: images.family,
          fontFamilyFallback: [images.fallbackFamily],
          fontSize: fontSize ?? fs,
          color: color,
          fontWeight:
              FontWeight.values[((font.weight / 100).round() - 1).clamp(0, 8)],
          fontStyle: font.style == 'italic'
              ? FontStyle.italic
              : FontStyle.normal,
          fontVariations: font.axes.entries
              .map((e) => ui.FontVariation(e.key, e.value))
              .toList(),
        ),
      ),
      textDirection: TextDirection.ltr,
      maxLines: 2,
    )..layout(maxWidth: all.width * .86);
    final hasFields =
        (d.xiaomiOptions['exifOverrides'] as Map?)?.values.any(
          (value) => value is String && value.trim().isNotEmpty,
        ) ??
        false;
    final params = hasFields
        ? displayParameterValues(
            effectiveParameterValues(
              asset.metadata,
              d.xiaomiOptions['exifOverrides'],
              allowExisting: true,
            ),
          )
        : d.parametersOverride.isNotEmpty
        ? d.parametersOverride
        : asset.metadata.parameters;
    final parameterText = text(d.showParameters ? params : '');
    final modelText = text(
      d.showModel
          ? (d.modelOverride.isNotEmpty
                ? d.modelOverride
                : asset.metadata.model)
          : '',
    );
    final caption = text(d.caption, fontSize: fs * .85);
    final model = images.model;
    final lockup = d.showModel ? images.modelLockup : null;
    final useModel =
        lockup == null &&
        d.showModel &&
        model != null &&
        d.modelOverride.isEmpty;
    final modelH = fs * 1.85;
    final modelW = lockup != null
        ? lockup.size.width / lockup.size.height * modelH
        : useModel
        ? model.size.width / model.size.height * modelH
        : modelText.width;
    final gap = fs * .8;
    final logo = d.showBrand && lockup == null ? fs * 1.4 : 0.0;
    final hasParameters = d.showParameters && params.isNotEmpty;
    final contentWidth =
        logo +
        (logo > 0 ? gap : 0) +
        (hasParameters ? parameterText.width : 0) +
        (hasParameters && d.showModel ? gap : 0) +
        (d.showModel ? modelW : 0);
    final naturalWidth = math.max(contentWidth, caption.width);
    if (naturalWidth <= 0) return;
    final fit = math.min(1.0, all.width * .92 / naturalWidth);
    final rowH = math.max(
      fs * 1.85,
      math.max(modelText.height, parameterText.height),
    );
    final height =
        (rowH + (d.caption.isNotEmpty ? caption.height + fs * .25 : 0)) * fit;
    final width = naturalWidth * fit;
    final cx = (d.x * all.width).clamp(
      width / 2 + all.width * .015,
      all.width - width / 2 - all.width * .015,
    );
    final cy = (d.y * all.height).clamp(
      height / 2 + all.height * .01,
      all.height - height / 2 - all.height * .01,
    );
    onDecorationBounds?.call(
      Rect.fromLTWH(cx - width / 2, cy - height / 2, width, height),
    );
    canvas.save();
    canvas.translate(cx - width / 2, cy - height / 2);
    canvas.scale(fit);
    var x = (naturalWidth - contentWidth) / 2;
    if (logo > 0) {
      final r = RRect.fromRectAndRadius(
        Rect.fromLTWH(x, (rowH - logo) / 2, logo, logo),
        Radius.circular(logo * .3),
      );
      canvas.drawRRect(
        r,
        Paint()
          ..color =
              (resolveBrand(asset.metadata.make) == 'generic'
                      ? const Color(0xff8c8c8c)
                      : const Color(0xFFFF6900))
                  .withValues(alpha: d.opacity),
      );
      final mark = images.brand;
      if (mark != null) {
        final scale = math.min(
          logo * .8 / mark.size.width,
          logo * .8 / mark.size.height,
        );
        canvas.save();
        canvas.translate(
          x + (logo - mark.size.width * scale) / 2,
          (rowH - mark.size.height * scale) / 2,
        );
        canvas.scale(scale);
        canvas.saveLayer(
          Rect.fromLTWH(0, 0, mark.size.width, mark.size.height),
          Paint()
            ..colorFilter = ColorFilter.mode(
              Colors.white.withValues(alpha: d.opacity),
              BlendMode.srcIn,
            ),
        );
        canvas.drawPicture(mark.picture);
        canvas.restore();
        canvas.restore();
      } else {
        canvas.drawCircle(
          Offset(x + logo / 2, rowH / 2),
          logo * .23,
          Paint()
            ..color = Colors.white.withValues(alpha: d.opacity)
            ..style = PaintingStyle.stroke
            ..strokeWidth = logo * .09,
        );
      }
      x += logo + gap;
    }
    if (hasParameters) {
      parameterText.paint(canvas, Offset(x, (rowH - parameterText.height) / 2));
      x += parameterText.width + (d.showModel ? gap : 0);
    }
    if (d.showModel) {
      final replaced = paintRegion(
        canvas,
        Rect.fromLTWH(x, (rowH - modelH) / 2, modelW, modelH),
        d.regionReplacements['model'] as Map? ?? {},
        images.regionAssets,
        images.family,
        color,
        imageAlignment: Alignment.centerLeft,
        tintSvg: true,
      );
      if (replaced) {
        // Original measurement remains authoritative.
      } else if (lockup != null) {
        lockup.paint(
          canvas,
          Rect.fromLTWH(x, (rowH - modelH) / 2, modelW, modelH),
          color,
          pixelRatio: pixelRatio,
        );
      } else if (useModel) {
        canvas.save();
        canvas.translate(x, (rowH - modelH) / 2);
        canvas.scale(modelW / model.size.width, modelH / model.size.height);
        canvas.saveLayer(
          Rect.fromLTWH(0, 0, model.size.width, model.size.height),
          Paint()..colorFilter = ColorFilter.mode(color, BlendMode.srcIn),
        );
        canvas.drawPicture(model.picture);
        canvas.restore();
        canvas.restore();
      } else {
        modelText.paint(canvas, Offset(x, (rowH - modelText.height) / 2));
      }
    }
    if (d.caption.isNotEmpty) {
      caption.paint(
        canvas,
        Offset((naturalWidth - caption.width) / 2, rowH + fs * .25),
      );
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant WatermarkPainter oldDelegate) {
    if (pixelRatio != oldDelegate.pixelRatio ||
        original != oldDelegate.original ||
        document.enabled != oldDelegate.document.enabled) {
      return true;
    }
    if (document.enabled && images.xiaomi != null) {
      return !identical(images.xiaomi, oldDelegate.images.xiaomi);
    }
    return !identical(document, oldDelegate.document) ||
        !identical(asset, oldDelegate.asset) ||
        !identical(images.photo, oldDelegate.images.photo) ||
        !identical(images.background, oldDelegate.images.background) ||
        !identical(images.model, oldDelegate.images.model) ||
        !identical(images.brand, oldDelegate.images.brand) ||
        !identical(images.modelLockup, oldDelegate.images.modelLockup) ||
        images.family != oldDelegate.images.family ||
        images.fallbackFamily != oldDelegate.images.fallbackFamily;
  }
}
