import 'dart:math' as math;
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:vector_graphics/vector_graphics.dart' as vg;
import 'package:watermark_engine/watermark_engine.dart';
import 'xiaomi_renderer.dart';

bool isParameterEmblem(Map node) =>
    node['type'] == 'ImageView' &&
    ['leica', 'brand_leica'].contains(node['id']) &&
    RegExp(
      r'path=(?:leica(?:_p3|_black|_white)?\.webp|Mark[CS]\.svg)(?:;|$)',
    ).hasMatch('${node['path']}');
bool isModelRun(Map node) {
  final children = (node['childViews'] as List? ?? [])
      .whereType<Map>()
      .toList();
  final models = children
      .where(
        (n) =>
            n['type'] == 'TextView' &&
            '${n['text'] ?? ''}'.startsWith('@model='),
      )
      .toList();
  final brands = children
      .where(
        (n) =>
            n['type'] == 'ImageView' &&
            '${n['path'] ?? ''}'.contains(r'path=${logo}'),
      )
      .toList();
  return node['type'] == 'Layout' &&
      models.length == 2 &&
      brands.length == 1 &&
      [...models, ...brands].every((n) => n['enable'] != false);
}

Map<String, bool> regionSupport(EditDocument doc) {
  if (doc.xiaomiConfig == null) return {'model': true, 'parameters': false};
  var model = false, parameters = false;
  void visit(dynamic value) {
    if (value is List) {
      for (final child in value) {
        visit(child);
      }
    }
    if (value is! Map || value['enable'] == false) return;
    if (isModelRun(value) ||
        (value['type'] == 'ImageView' && value['id'] == 'img_model')) {
      model = true;
    }
    if (isParameterEmblem(value)) parameters = true;
    visit(value['childViews']);
  }

  visit(XiaomiRenderPlan.selectedLayouts(doc.xiaomiConfig!, doc.xiaomiOptions));
  return {'model': model, 'parameters': parameters};
}

class RegionArtwork {
  final ui.Image? image;
  final vg.PictureInfo? vector;
  RegionArtwork.image(this.image) : vector = null;
  RegionArtwork.vector(this.vector) : image = null;
  Size get size =>
      vector?.size ?? Size(image!.width.toDouble(), image!.height.toDouble());
  void dispose() {
    image?.dispose();
    vector?.picture.dispose();
  }

  void paint(
    Canvas canvas,
    Rect slot,
    bool circle, {
    Alignment alignment = Alignment.center,
    Color? svgColor,
  }) {
    if (slot.isEmpty) return;
    canvas.save();
    canvas.clipRect(slot);
    if (circle) {
      final side = math.min(slot.width, slot.height);
      slot = Rect.fromCenter(center: slot.center, width: side, height: side);
      canvas.clipPath(Path()..addOval(slot));
    }
    final factor = circle
        ? math.max(slot.width / size.width, slot.height / size.height)
        : math.min(slot.width / size.width, slot.height / size.height);
    final destination = (circle ? Alignment.center : alignment).inscribe(
      size * factor,
      slot,
    );
    canvas.translate(destination.left, destination.top);
    canvas.scale(factor);
    if (vector != null) {
      if (svgColor != null) {
        canvas.saveLayer(
          Offset.zero & size,
          Paint()..colorFilter = ColorFilter.mode(svgColor, BlendMode.srcIn),
        );
      }
      canvas.drawPicture(vector!.picture);
      if (svgColor != null) canvas.restore();
    } else {
      canvas.drawImage(
        image!,
        Offset.zero,
        Paint()..filterQuality = FilterQuality.high,
      );
    }
    canvas.restore();
  }
}

/// Return false for original/missing assets, preserving the original fallback.
bool paintRegion(
  Canvas canvas,
  Rect slot,
  Map design,
  Map<String, RegionArtwork> assets,
  String family,
  Color color, {
  Alignment imageAlignment = Alignment.center,
  bool tintSvg = false,
}) {
  if (slot.isEmpty) return false;
  if (design['source'] == 'image') {
    final art = assets[design['asset']];
    if (art == null) return false;
    art.paint(
      canvas,
      slot,
      design['shape'] == 'circle',
      alignment: imageAlignment,
      svgColor: tintSvg ? color : null,
    );
    return true;
  }
  if (design['source'] != 'text') return false;
  final text = TextPainter(
    text: TextSpan(
      text: '${design['text'] ?? ''}'.replaceAll(RegExp(r'[\r\n]'), ' '),
      style: TextStyle(
        fontFamily: family,
        fontSize: slot.height * .8,
        color: color,
      ),
    ),
    textDirection: TextDirection.ltr,
    maxLines: 1,
    ellipsis: '…',
  );
  text.layout();
  final shrink = math.max(
    .5,
    math.min(1.0, slot.width / math.max(1, text.width)),
  );
  text.layout(maxWidth: slot.width / shrink);
  canvas.save();
  canvas.clipRect(slot);
  canvas.translate(slot.left, slot.center.dy - text.height * shrink / 2);
  canvas.scale(shrink);
  text.paint(canvas, Offset.zero);
  canvas.restore();
  text.dispose();
  return true;
}
