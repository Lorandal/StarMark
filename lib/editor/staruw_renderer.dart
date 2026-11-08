import 'dart:convert';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:flutter_svg/flutter_svg.dart';
import 'package:vector_graphics/vector_graphics.dart' as vg;
import 'package:watermark_engine/watermark_engine.dart';
import '../services/photo_asset.dart';
import '../services/staruw/contract.dart';
import '../services/staruw/controls.dart';
import '../services/staruw/font_file.dart';
import 'model_lockup.dart';

ui.Color staruwColor(String rgba) =>
    ui.Color(int.parse(rgba.substring(7, 9) + rgba.substring(1, 7), radix: 16));

class StaruwRenderResources {
  final Map u;
  final Map<String, ui.Image> images;
  final Map<String, String> families;
  final Map<String, vg.PictureInfo> vectors = {};
  final Map<String, StaruwFontFile> fontFiles = {};
  final Map<String, Map> catalogs = {};
  final Map<String, Future<double>> _inkHeights = {};
  StaruwRenderResources._(this.u, this.images, this.families);
  Map get space => (u['scene'] ?? u['xiaomi']) as Map;
  Map get fonts => space['fonts'] as Map;
  Map get resources => u['resources'] as Map;
  ModelLockup? nativeModel(PhotoMetadata metadata) {
    String norm(String s) => s
        .trim()
        .replaceAll(RegExp(r'\s+'), ' ')
        .replaceAllMapped(RegExp('[A-Z]'), (m) => m[0]!.toLowerCase());
    for (final catalog in catalogs.values) {
      final entries = (catalog['entries'] as List).where(
        (e) =>
            norm(e['model'] as String) == norm(metadata.model) &&
            (e['make'] == null ||
                norm(e['make'] as String) == norm(metadata.make)),
      );
      final found = entries.firstOrNull;
      if (found == null) continue;
      final size = ui.Size(
        (found['size'][0] as num).toDouble(),
        (found['size'][1] as num).toDouble(),
      );
      return ModelLockup.catalog(size, (canvas, rect) {
        final scale = math.min(
          rect.width / size.width,
          rect.height / size.height,
        );
        canvas.save();
        canvas.translate(
          rect.center.dx - size.width * scale / 2,
          rect.center.dy - size.height * scale / 2,
        );
        canvas.scale(scale);
        for (final component in found['components'] as List) {
          final slot = StaruwSlot._(
            this,
            {'resourceId': component['resourceId'], 'fit': 'contain'},
            ui.Rect.fromLTWH(
              0,
              0,
              (component['width'] as num).toDouble(),
              (component['height'] as num).toDouble(),
            ),
            null,
          );
          canvas.save();
          canvas.translate(
            (component['x'] as num).toDouble(),
            (component['y'] as num).toDouble(),
          );
          slot.paint(canvas);
          canvas.restore();
        }
        canvas.restore();
      });
    }
    return null;
  }

  static Future<StaruwRenderResources> load(
    Map config,
    Map<String, Uint8List> files,
    Map<String, ui.Image> images,
    Map<String, String> families,
  ) async {
    final r = StaruwRenderResources._(
      config['staruw'] as Map,
      images,
      families,
    );
    try {
      for (final e in r.resources.entries) {
        final row = e.value as Map, path = row['path'] as String;
        if (row['kind'] == 'font') {
          r.fontFiles[e.key as String] = StaruwFontFile(files[path]!);
        }
        if (row['mediaType'] == 'image/svg+xml') {
          r.vectors[e.key as String] = await vg.vg.loadPicture(
            SvgStringLoader(utf8.decode(files[path]!)),
            null,
          );
        }
      }
      for (final e in (r.space['catalogs'] as Map).entries) {
        r.catalogs[e.key as String] =
            staruwJson(files[r.resources[e.value]['path']]!) as Map;
      }
      return r;
    } catch (_) {
      r.dispose();
      rethrow;
    }
  }

  String family(String fontId) =>
      families[resources[fonts[fontId]['resourceId']]['path']] ??
      (throw const StaruwException('E_RESOURCE', '包内字库未加载'));
  StaruwFontFile font(String id) => fontFiles[fonts[id]['resourceId']]!;
  List<ui.FontVariation> variations(String id, [Map? axes]) => [
    for (final e in (axes ?? fonts[id]['axes'] as Map).entries)
      ui.FontVariation(e.key as String, (e.value as num).toDouble()),
  ];
  String glyphFont(String primary, int cp) {
    // Shaping controls (ZWJ/variation selectors/bidi controls) do not themselves
    // require a visible glyph. Actual characters must use a declared font.
    if (cp == 0x200c ||
        cp == 0x200d ||
        cp >= 0xfe00 && cp <= 0xfe0f ||
        cp >= 0xe0100 && cp <= 0xe01ef ||
        cp >= 0x202a && cp <= 0x202e ||
        cp >= 0x2066 && cp <= 0x2069) {
      return primary;
    }
    String? find(String id) {
      if (font(id).glyph(cp) != 0) return id;
      for (final child in fonts[id]['fallbackFontIds'] as List) {
        final result = find(child as String);
        if (result != null) return result;
      }
      return null;
    }

    return find(primary) ??
        (throw const StaruwException('E_RESOURCE', '文字包含包内字体均不支持的字形'));
  }

  ui.Paragraph paragraph(
    String text,
    String fontId,
    double size,
    Map style, {
    Map? axes,
  }) {
    final direction = style['direction'] == 'rtl'
        ? ui.TextDirection.rtl
        : ui.TextDirection.ltr;
    final b = ui.ParagraphBuilder(
      ui.ParagraphStyle(
        fontFamily: family(fontId),
        fontSize: size,
        textDirection: direction,
        maxLines: 1,
        height: 1,
      ),
    );
    String? last;
    final run = StringBuffer();
    void flush() {
      if (run.isEmpty) return;
      b.pushStyle(
        ui.TextStyle(
          fontFamily: family(last!),
          fontFamilyFallback: const [],
          fontSize: size,
          fontVariations: variations(last, last == fontId ? axes : null),
          letterSpacing: (style['letterSpacing'] as num? ?? 0).toDouble(),
          color: staruwColor(style['color'] as String? ?? '#FFFFFFFF'),
          height: 1,
        ),
      );
      b.addText(run.toString());
      b.pop();
      run.clear();
    }

    for (final cp in text.runes) {
      final selected = glyphFont(fontId, cp);
      if (last != selected) {
        flush();
        last = selected;
      }
      run.writeCharCode(cp);
    }
    flush();
    // No automatic line breaking. Split only explicit newlines in _text().
    return b.build()..layout(const ui.ParagraphConstraints(width: 100000000));
  }

  Future<double> inkHeight(String fontId, String probe, {Map? axes}) {
    final key = '$fontId/${jsonEncode(axes ?? fonts[fontId]['axes'])}/$probe';
    return _inkHeights.putIfAbsent(key, () async {
      staruwNeed(
        probe.isNotEmpty && probe.runes.length <= 256,
        'E_BINDING',
        '字高探针无效',
      );
      final p = paragraph(probe, fontId, 128, {
        'color': '#FFFFFFFF',
      }, axes: axes);
      final width = p.longestLine.ceil() + 4, height = p.height.ceil() + 8;
      staruwNeed(
        width > 0 &&
            width <= 32768 &&
            height <= 1024 &&
            width * height <= 8 * 1024 * 1024,
        'E_LIMIT',
        '字高探针超过预算',
      );
      final recorder = ui.PictureRecorder();
      final canvas = ui.Canvas(recorder);
      canvas.drawParagraph(p, const ui.Offset(2, 4));
      final picture = recorder.endRecording();
      try {
        final image = await picture.toImage(width, height);
        try {
          final raw = await image.toByteData(
            format: ui.ImageByteFormat.rawRgba,
          );
          var first = height, last = -1;
          for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
              if (raw!.getUint8((y * width + x) * 4 + 3) > 8) {
                first = math.min(first, y);
                last = y;
                break;
              }
            }
          }
          staruwNeed(last >= first, 'E_RESOURCE', '字高探针没有可见字形');
          return (last - first + 1) / 128;
        } finally {
          image.dispose();
        }
      } finally {
        picture.dispose();
        p.dispose();
      }
    });
  }

  void dispose() {
    for (final p in vectors.values) {
      p.picture.dispose();
    }
    vectors.clear();
  }
}

class _StaruwText {
  final List<ui.Paragraph> lines;
  final List<ui.Offset> positions;
  final double scale;
  final ui.Rect clip;
  _StaruwText(this.lines, this.positions, this.scale, this.clip);
  void paint(ui.Canvas c) {
    c.save();
    c.clipRect(clip);
    c.scale(scale);
    for (var i = 0; i < lines.length; i++) {
      c.drawParagraph(lines[i], positions[i]);
    }
    c.restore();
  }

  void dispose() {
    for (final p in lines) {
      p.dispose();
    }
  }
}

class StaruwRenderPlan {
  final EditDocument document;
  final Map<String, dynamic> scene;
  final StaruwGeometry geometry;
  final StaruwRenderResources resources;
  final ui.Image image;
  final StaruwPhotoData data;
  final Map<String, _StaruwText> _texts = {};
  final Map<String, Map> _models = {};
  CanvasLayout get layout => geometry.layout;
  StaruwRenderPlan._(
    this.document,
    this.scene,
    this.geometry,
    this.resources,
    this.image,
    this.data,
  );
  static Future<StaruwRenderPlan> create(
    EditDocument document,
    PhotoAsset photo,
    ui.Image image,
    StaruwRenderResources resources,
  ) async {
    final controls = StaruwControls(document), scene = controls.scene();
    final g = StaruwGeometry(photo.width, photo.height, scene['canvas'] as Map);
    staruwNeed(g.layout.withinBudget, 'E_LIMIT', '通用场景画布超过像素预算');
    final plan = StaruwRenderPlan._(
      document,
      scene,
      g,
      resources,
      image,
      StaruwPhotoData.fromMetadata(
        photo.metadata,
        overrides: controls.photoFields,
      ),
    );
    try {
      await plan._prepare(scene['nodes'] as List, null);
      return plan;
    } catch (_) {
      plan.dispose();
      rethrow;
    }
  }

  Future<void> _prepare(List children, StaruwRect? parent) async {
    for (final n in children) {
      if (n['visible'] == false) continue;
      final ref = geometry.reference(
        n['bounds']['reference'] as String,
        parent: parent,
      );
      staruwNeed(ref.width > 0 && ref.height > 0, 'E_BINDING', '可见节点引用了零面积区域');
      final rect = StaruwGeometry.resolve(ref, n['bounds'] as Map);
      staruwNeed(rect.width > 0 && rect.height > 0, 'E_BINDING', '节点尺寸必须为正数');
      if (n['type'] == 'image' &&
          n['shape'] == 'circle' &&
          n['replacementCircle'] != true) {
        staruwNeed(rect.width == rect.height, 'E_BINDING', '圆形图片边界必须为正方形');
      }
      final id = n['id'] as String;
      if (n['type'] == 'text') {
        final text = data.content(n['content'] as Map);
        if (text.isNotEmpty) {
          _texts[id] = await _staruwText(
            resources,
            text,
            n['fontId'] as String,
            n['textStyle'] as Map,
            rect,
          );
        }
      } else if (n['type'] == 'model') {
        String norm(String s) => s
            .trim()
            .replaceAll(RegExp(r'\s+'), ' ')
            .replaceAllMapped(RegExp('[A-Z]'), (m) => m[0]!.toLowerCase());
        final model = norm(data.fields['photo.model'] as String? ?? ''),
            make = norm(data.fields['photo.make'] as String? ?? '');
        final matches = (resources.catalogs[n['catalogId']]!['entries'] as List)
            .where(
              (e) =>
                  norm(e['model'] as String) == model &&
                  (e['make'] == null || norm(e['make'] as String) == make),
            );
        final exact = matches.firstOrNull;
        if (exact != null) {
          _models[id] = exact as Map;
        } else if (model.isNotEmpty) {
          _texts[id] = await _staruwText(
            resources,
            data.fields['photo.model'] as String,
            n['fallbackFontId'] as String,
            n['textStyle'] as Map,
            rect,
          );
        }
      } else if (n['type'] == 'group') {
        final replacement = n['replacement'] as Map?;
        if (replacement?['mode'] == 'text') {
          _texts[id] = await _staruwText(
            resources,
            replacement!['text'] as String,
            replacement['fontId'] as String,
            n['replacementStyle']['textStyle'] as Map,
            rect,
          );
        } else if (replacement == null) {
          await _prepare(
            n['children'] as List,
            StaruwRect(0, 0, rect.width, rect.height),
          );
        }
      }
    }
  }

  void paint(
    ui.Canvas c,
    ui.Size size, {
    bool original = false,
    bool decorationOnly = false,
    bool backgroundOnly = false,
    bool photoContribution = false,
  }) {
    c.save();
    c.scale(size.width / layout.width, size.height / layout.height);
    // U transforms are shared by preview, tile recording and final export.
    c.scale(geometry.scale);
    final all = ui.Rect.fromLTWH(
          0,
          0,
          layout.width / geometry.scale,
          layout.height / geometry.scale,
        ),
        photo = ui.Rect.fromLTWH(
          layout.photoX / geometry.scale,
          layout.photoY / geometry.scale,
          layout.photoWidth / geometry.scale,
          layout.photoHeight / geometry.scale,
        );
    c.clipRect(all);
    if (original) {
      _bitmap(c, image, photo, 'contain');
      c.restore();
      return;
    }
    if (photoContribution) {
      c.drawRect(all, ui.Paint()..color = const ui.Color(0xff000000));
      c.drawRect(photo, ui.Paint()..color = const ui.Color(0xffffffff));
      c.saveLayer(
        all,
        ui.Paint()
          ..colorFilter = const ui.ColorFilter.mode(
            ui.Color(0xff000000),
            ui.BlendMode.srcIn,
          ),
      );
      _nodes(c, scene['nodes'] as List, null);
      c.restore();
      c.restore();
      return;
    }
    // Every leaf is converted before source-over; groups apply alpha only once.
    // The layer is converted back once for display/native color conversion.
    c.saveLayer(
      all,
      ui.Paint()..colorFilter = const ui.ColorFilter.linearToSrgbGamma(),
    );
    _background(c, all);
    if (!backgroundOnly) {
      if (decorationOnly) {
        c.drawRect(photo, ui.Paint()..blendMode = ui.BlendMode.clear);
      } else {
        c.saveLayer(
          photo,
          ui.Paint()..colorFilter = const ui.ColorFilter.srgbToLinearGamma(),
        );
        _bitmap(c, image, photo, 'contain');
        c.restore();
      }
      _nodes(c, scene['nodes'] as List, null);
    }
    c.restore();
    c.restore();
  }

  void _background(ui.Canvas c, ui.Rect all) {
    final bg = scene['canvas']['background'];
    if (bg['kind'] == 'solid') {
      _linear(
        c,
        all,
        () => c.drawRect(
          all,
          ui.Paint()..color = staruwColor(bg['color'] as String),
        ),
      );
    } else if (bg['kind'] == 'image') {
      _linear(
        c,
        all,
        () => _artwork(c, bg['resourceId'] as String, all, bg['fit'] as String),
      );
    } else {
      var material = scene['materials'][bg['materialId']] as Map;
      if (material['kind'] == 'photo-glass') {
        material = material['fallback'] as Map;
      }
      if (material['kind'] == 'solid') {
        _linear(
          c,
          all,
          () => c.drawRect(
            all,
            ui.Paint()..color = staruwColor(material['color'] as String),
          ),
        );
        return;
      }
      final sigma = (material['blurSigmaU'] as num).toDouble(),
          zoom = (material['scale'] as num).toDouble();
      // Clamp the original sample before convolution. The sampling transform is
      // based on the complete canvas, including when recorded into export tiles.
      final cover =
          math.max(all.width / image.width, all.height / image.height) * zoom;
      final matrix = Float64List.fromList([
        cover,
        0,
        0,
        0,
        0,
        cover,
        0,
        0,
        0,
        0,
        1,
        0,
        all.center.dx - image.width * cover / 2,
        all.center.dy - image.height * cover / 2,
        0,
        1,
      ]);
      c.save();
      c.clipRect(all);
      c.saveLayer(
        all.inflate(sigma * 3),
        ui.Paint()
          ..imageFilter = ui.ImageFilter.blur(
            sigmaX: sigma,
            sigmaY: sigma,
            tileMode: ui.TileMode.clamp,
          ),
      );
      c.drawRect(
        all.inflate(sigma * 3),
        ui.Paint()
          ..shader = ui.ImageShader(
            image,
            ui.TileMode.clamp,
            ui.TileMode.clamp,
            matrix,
          )
          ..colorFilter = const ui.ColorFilter.srgbToLinearGamma()
          ..filterQuality = ui.FilterQuality.high,
      );
      c.restore();
      c.restore();
      final tint = staruwColor(material['tint'] as String);
      _linear(
        c,
        all,
        () => c.drawRect(
          all,
          ui.Paint()
            ..color = tint.withValues(
              alpha: tint.a * (material['tintOpacity'] as num),
            ),
        ),
      );
      final width = (material['highlightWidthU'] as num).toDouble();
      if (width > 0) {
        _linear(
          c,
          all,
          () => c.drawRect(
            all.deflate(width / 2),
            ui.Paint()
              ..color = staruwColor(material['highlightColor'] as String)
              ..style = ui.PaintingStyle.stroke
              ..strokeWidth = width,
          ),
        );
      }
    }
  }

  void _linear(ui.Canvas c, ui.Rect bounds, void Function() draw) {
    c.saveLayer(
      bounds,
      ui.Paint()..colorFilter = const ui.ColorFilter.srgbToLinearGamma(),
    );
    draw();
    c.restore();
  }

  void _nodes(ui.Canvas c, List children, StaruwRect? parent) {
    for (final n in children) {
      if (n['visible'] == false || n['opacity'] == 0) continue;
      final rect = StaruwGeometry.resolve(
        geometry.reference(n['bounds']['reference'] as String, parent: parent),
        n['bounds'] as Map,
      );
      final local = ui.Rect.fromLTWH(0, 0, rect.width, rect.height),
          pivot = n['bounds']['pivot'] as List;
      c.save();
      c.translate(rect.x, rect.y);
      c.translate(pivot[0] * rect.width, pivot[1] * rect.height);
      c.rotate((n['rotation'] as num? ?? 0) * math.pi / 180);
      c.translate(-pivot[0] * rect.width, -pivot[1] * rect.height);
      final opacity = (n['opacity'] as num? ?? 1).toDouble();
      final group = n['type'] == 'group';
      if (group && n['clipChildren'] == true) c.clipRect(local);
      // Group bounds are a coordinate space, not an implicit clip.
      c.saveLayer(
        group ? null : local,
        ui.Paint()
          ..color = ui.Color.fromRGBO(255, 255, 255, opacity)
          ..colorFilter = group
              ? null
              : const ui.ColorFilter.srgbToLinearGamma(),
      );
      final id = n['id'] as String;
      if (group) {
        final replacement = n['replacement'] as Map?;
        if (replacement?['mode'] == 'image') {
          _linear(
            c,
            local,
            () => _artwork(
              c,
              replacement!['resourceId'] as String,
              local,
              replacement['fit'] as String,
            ),
          );
        } else if (replacement?['mode'] == 'text') {
          _linear(c, local, () => _texts[id]?.paint(c));
        } else {
          _nodes(
            c,
            n['children'] as List,
            StaruwRect(0, 0, rect.width, rect.height),
          );
        }
      } else if (n['type'] == 'image') {
        var bounds = local;
        if (n['shape'] == 'circle') {
          if (n['replacementCircle'] == true) {
            final side = math.min(rect.width, rect.height);
            bounds = ui.Rect.fromCenter(
              center: local.center,
              width: side,
              height: side,
            );
          }
          c.clipPath(ui.Path()..addOval(bounds));
        }
        _artwork(c, n['resourceId'] as String, bounds, n['fit'] as String);
      } else if (_models[id] case final Map model) {
        final natural = model['size'] as List;
        final fit = math.min(rect.width / natural[0], rect.height / natural[1]);
        c.translate(
          (rect.width - natural[0] * fit) / 2,
          (rect.height - natural[1] * fit) / 2,
        );
        c.scale(fit);
        for (final part in model['components'] as List) {
          _artwork(
            c,
            part['resourceId'] as String,
            ui.Rect.fromLTWH(
              (part['x'] as num).toDouble(),
              (part['y'] as num).toDouble(),
              (part['width'] as num).toDouble(),
              (part['height'] as num).toDouble(),
            ),
            'contain',
          );
        }
      } else {
        _texts[id]?.paint(c);
      }
      c.restore();
      c.restore();
    }
  }

  void _artwork(ui.Canvas c, String id, ui.Rect dest, String fit) {
    final vector = resources.vectors[id];
    if (vector != null) {
      final scale = fit == 'cover'
          ? math.max(
              dest.width / vector.size.width,
              dest.height / vector.size.height,
            )
          : math.min(
              dest.width / vector.size.width,
              dest.height / vector.size.height,
            );
      c.save();
      c.clipRect(dest);
      c.translate(
        dest.center.dx - vector.size.width * scale / 2,
        dest.center.dy - vector.size.height * scale / 2,
      );
      c.scale(scale);
      c.drawPicture(vector.picture);
      c.restore();
    } else {
      final img = resources.images[resources.resources[id]['path']];
      staruwNeed(img != null, 'E_RESOURCE', '场景图片尚未加载');
      _bitmap(c, img!, dest, fit);
    }
  }

  static void _bitmap(ui.Canvas c, ui.Image image, ui.Rect dest, String fit) {
    final scale = fit == 'cover'
        ? math.max(dest.width / image.width, dest.height / image.height)
        : math.min(dest.width / image.width, dest.height / image.height);
    final target = ui.Rect.fromCenter(
      center: dest.center,
      width: image.width * scale,
      height: image.height * scale,
    );
    c.save();
    c.clipRect(dest);
    c.drawImageRect(
      image,
      ui.Rect.fromLTWH(0, 0, image.width.toDouble(), image.height.toDouble()),
      target,
      ui.Paint()..filterQuality = ui.FilterQuality.high,
    );
    c.restore();
  }

  void dispose() {
    for (final t in _texts.values) {
      t.dispose();
    }
    _texts.clear();
  }
}

Future<_StaruwText> _staruwText(
  StaruwRenderResources resources,
  String text,
  String fontId,
  Map style,
  StaruwRect rect,
) async {
  final size = (style['size'] as num).toDouble(),
      lineHeight = (style['lineHeight'] as num).toDouble();
  final metrics = resources.font(fontId);
  final measured = metrics.metrics(resources.fonts[fontId]['axes'] as Map);
  var ascent = measured.ascent / metrics.unitsPerEm * size,
      descent = measured.descent.abs() / metrics.unitsPerEm * size;
  var sizeRatio = 1.0;
  if (style['fontFit'] case final Map fit) {
    final probe = fit['probe'] as String? ?? 'H0123456789',
        reference = fit['referenceFontId'] as String;
    final refSize = (fit['referenceSize'] as num).toDouble();
    sizeRatio =
        refSize *
        (await resources.inkHeight(
          reference,
          probe,
          axes: fit['referenceAxes'] as Map,
        )) /
        (size * await resources.inkHeight(fontId, probe));
    final ref = resources.font(reference);
    final measuredRef = ref.metrics(fit['referenceAxes'] as Map);
    ascent = measuredRef.ascent / ref.unitsPerEm * refSize;
    descent = measuredRef.descent.abs() / ref.unitsPerEm * refSize;
  }
  final paragraphs = <ui.Paragraph>[];
  try {
    for (final line in text.split('\n')) {
      paragraphs.add(
        resources.paragraph(line, fontId, size * sizeRatio, style),
      );
    }
    final width = paragraphs.fold<double>(
          0,
          (w, p) => math.max(w, p.longestLine),
        ),
        height = lineHeight * paragraphs.length;
    var shrink = 1.0;
    if (style['overflow'] == 'shrink') {
      shrink = math.min(
        1,
        math.min(width == 0 ? 1 : rect.width / width, rect.height / height),
      );
      staruwNeed(
        size * shrink + 1e-9 >= (style['minSize'] as num),
        'E_TEXT_OVERFLOW',
        '文字缩到最小字号后仍放不下',
      );
    }
    final availableWidth = rect.width / shrink,
        availableHeight = rect.height / shrink;
    final top = switch (style['verticalAlign']) {
      'center' => (availableHeight - height) / 2,
      'bottom' => availableHeight - height,
      _ => 0.0,
    };
    final positions = <ui.Offset>[];
    for (var i = 0; i < paragraphs.length; i++) {
      final p = paragraphs[i];
      final x = switch (style['align']) {
        'center' => (availableWidth - p.longestLine) / 2,
        'right' => availableWidth - p.longestLine,
        _ => 0.0,
      };
      final baseline =
          top + i * lineHeight + (lineHeight - ascent - descent) / 2 + ascent;
      positions.add(ui.Offset(x, baseline - p.alphabeticBaseline));
    }
    return _StaruwText(
      paragraphs,
      positions,
      shrink,
      ui.Rect.fromLTWH(0, 0, rect.width, rect.height),
    );
  } catch (_) {
    for (final p in paragraphs) {
      p.dispose();
    }
    rethrow;
  }
}

/// A replacement keeps the measured OEM slot; it never participates in layout.
class StaruwSlot {
  final StaruwRenderResources resources;
  final Map value;
  final ui.Rect bounds;
  final _StaruwText? _text;
  StaruwSlot._(this.resources, this.value, this.bounds, this._text);
  static Future<StaruwSlot> create(
    StaruwRenderResources resources,
    Map value,
    ui.Size size,
  ) async {
    final bounds = ui.Offset.zero & size;
    staruwNeed(!bounds.isEmpty, 'E_BINDING', '原厂替换槽没有可见面积');
    final text = value['mode'] == 'text'
        ? await _staruwText(
            resources,
            value['text'] as String,
            value['fontId'] as String,
            value['style']['textStyle'] as Map,
            StaruwRect(0, 0, size.width, size.height),
          )
        : null;
    return StaruwSlot._(resources, value, bounds, text);
  }

  void paint(ui.Canvas canvas) {
    if (_text != null) {
      _text.paint(canvas);
      return;
    }
    var rect = bounds;
    final circle = value['shape'] == 'circle';
    if (circle) {
      final side = math.min(rect.width, rect.height);
      rect = ui.Rect.fromCenter(center: rect.center, width: side, height: side);
    }
    canvas.save();
    canvas.clipRect(rect);
    if (circle) canvas.clipPath(ui.Path()..addOval(rect));
    final id = value['resourceId'] as String,
        fit = circle ? 'cover' : value['fit'] as String? ?? 'contain';
    final vector = resources.vectors[id];
    if (vector != null) {
      final scale = fit == 'cover'
          ? math.max(
              rect.width / vector.size.width,
              rect.height / vector.size.height,
            )
          : math.min(
              rect.width / vector.size.width,
              rect.height / vector.size.height,
            );
      canvas.translate(
        rect.center.dx - vector.size.width * scale / 2,
        rect.center.dy - vector.size.height * scale / 2,
      );
      canvas.scale(scale);
      canvas.drawPicture(vector.picture);
    } else {
      final image = resources.images[resources.resources[id]['path']];
      staruwNeed(image != null, 'E_RESOURCE', '原厂替换图片未加载');
      StaruwRenderPlan._bitmap(canvas, image!, rect, fit);
    }
    canvas.restore();
  }

  void dispose() => _text?.dispose();
}
