import 'dart:math' as math;
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:watermark_engine/watermark_engine.dart';
import '../services/photo_asset.dart';
import 'model_lockup.dart';
import 'staruw_renderer.dart';
import 'region_replacements.dart';
import 'xiaomi_font_style.dart';
import 'xiaomi_capabilities.dart';
import 'metadata_overrides.dart';
import 'xiaomi_background_choices.dart';

/// Resources have already been decoded and registered from the validated package.
class XiaomiRenderResources {
  final Map<String, ui.Image> images;
  final Map<String, RegionArtwork> vectors;
  final Map<String, RegionArtwork> regionAssets;
  final Map<String, Rect> imageContentBounds;
  final Map<String, String> fonts;
  final Map<String, double> fontCapHeights;
  final String defaultFamily;
  final ui.Image? background;
  final bool backgroundToned;
  final Map<String, String> localizations;
  final ModelLockup? modelLockup;
  final ui.Locale? locale;
  const XiaomiRenderResources({
    required this.images,
    this.vectors = const {},
    this.regionAssets = const {},
    required this.fonts,
    required this.defaultFamily,
    this.imageContentBounds = const {},
    this.fontCapHeights = const {},
    this.background,
    this.backgroundToned = false,
    this.localizations = const {},
    this.modelLockup,
    this.locale,
  });
}

/// A measured native layout. Coordinates always use the original photo pixels,
/// independently of the decoded preview's resolution.
class XiaomiRenderPlan {
  final CanvasLayout layout;
  final Map<String, String> resolvedTexts;
  final Map<String, Rect> nodeBounds;
  final _Builder _builder;
  final List<_Node> _roots;
  final Map<String, Rect> _contentBounds = {};

  final Map<_Node, StaruwSlot> _staruwSlots = {};
  Future<void> prepareStaruw(StaruwRenderResources resources) async {
    Future<void> visit(_Node node) async {
      if (!node.visible || node.source) return;
      final value = node.json['_staruwReplacement'];
      if (value is Map) {
        final members = node.modelMembers
            .where((c) => c.visible)
            .map((c) => c.local & c.size)
            .where((r) => !r.isEmpty)
            .toList();
        final bounds = members.isEmpty
            ? Offset.zero & node.unrotated
            : members.reduce((a, b) => a.expandToInclude(b));
        final slot = await StaruwSlot.create(
          resources,
          value,
          bounds.size / _builder.dp,
        );
        _staruwSlots[node]?.dispose();
        _staruwSlots[node] = slot;
        _builder.staruwSlots[node] = slot;
        _builder.staruwSlotOrigins[node] = bounds.topLeft;
        if (node.modelMembers.isEmpty) return;
        for (final child in node.children.where(
          (c) => !node.modelMembers.contains(c),
        )) {
          await visit(child);
        }
        return;
      }
      for (final child in node.children) {
        await visit(child);
      }
    }

    try {
      for (final root in _roots) {
        await visit(root);
      }
    } catch (_) {
      disposeStaruw();
      rethrow;
    }
  }

  void disposeStaruw() {
    for (final slot in _staruwSlots.values) {
      slot.dispose();
    }
    _staruwSlots.clear();
    _builder.staruwSlots.clear();
    _builder.staruwSlotOrigins.clear();
  }

  /// sm0.f validates the chosen typeface's advance against greetingRatio*1080.
  /// Work in the same photo-scaled units as the renderer, including font axes.
  void validateEditableText(EditDocument candidate) {
    final b = _Builder(
      _builder.config,
      candidate,
      _builder.photo,
      _builder.image,
      _builder.resources,
    );
    void visit(dynamic value) {
      if (value is List) {
        for (final child in value) {
          visit(child);
        }
      } else if (value is Map && value['enable'] != false) {
        final node = _map(value), binding = '${value['text'] ?? ''}';
        final isMix = binding.startsWith('@mix=');
        final mixed = isMix ? XiaomiMixField(node) : null;
        final customMix =
            mixed?.selectedType(candidate.xiaomiOptions) == 'custom_text';
        final input = customMix
            ? '${mixed!.state(candidate.xiaomiOptions)['text'] ?? ''}'
            : candidate.caption;
        if ((binding.startsWith('@greeting=') || customMix) &&
            input.isNotEmpty) {
          final ratio = customMix
              ? _number(
                  RegExp(
                    r'(?:^|;)@custom=([^;]+)',
                  ).firstMatch(binding)?.group(1),
                  .4,
                )
              : _number(binding.substring(10).split(';').first);
          if (ratio > 0) {
            final text = node['text_upper'] == true
                ? input.toUpperCase()
                : input;
            final painter = b.textPainter(
              customMix ? text : text.replaceAll(RegExp(r'[\r\n]'), ''),
              node,
            );
            var tooWide = painter.width > ratio * 1080 * b.dp + .001;
            if (customMix) {
              final maxLines = _number(
                RegExp(
                  r'(?:^|;)@customMaxLines=(\d+)',
                ).firstMatch(binding)?.group(1),
                1,
              ).toInt();
              painter.maxLines = null;
              painter.ellipsis = null;
              painter.layout(maxWidth: ratio * 1080 * b.dp);
              tooWide = painter.computeLineMetrics().length > maxLines;
            }
            painter.dispose();
            if (tooWide) throw const FormatException('文案超过原厂允许的宽度');
          }
        }
        visit(value['childViews']);
      }
    }

    visit(selectedLayouts(_builder.config, candidate.xiaomiOptions));
  }

  /// Unscaled, rotated foreground leaf geometry, useful to inspect OEM layouts.
  Map<String, Rect> get contentBounds {
    if (_rawDecorationBounds == null) return const {};
    return Map.unmodifiable(_contentBounds);
  }

  late final Rect? _rawDecorationBounds = _measureDecorationBounds();

  Rect? _measureDecorationBounds() {
    Rect? result;
    void include(Rect rect, Offset Function(Offset) transform, String id) {
      if (rect.isEmpty) return;
      final points = [
        rect.topLeft,
        rect.topRight,
        rect.bottomLeft,
        rect.bottomRight,
      ].map(transform).toList();
      final bounds = Rect.fromLTRB(
        points.map((p) => p.dx).reduce(math.min),
        points.map((p) => p.dy).reduce(math.min),
        points.map((p) => p.dx).reduce(math.max),
        points.map((p) => p.dy).reduce(math.max),
      );
      result = result?.expandToInclude(bounds) ?? bounds;
      _contentBounds[id] =
          _contentBounds[id]?.expandToInclude(bounds) ?? bounds;
    }

    void local(_Node node, Offset Function(Offset) parent) {
      if (!node.visible || node.source) return;
      Offset transform(Offset point) => parent(switch (node.degree) {
        90 => Offset(node.size.width - point.dy, point.dx),
        270 => Offset(point.dy, node.size.height - point.dx),
        _ => point,
      });
      final text = node.text;
      if (text != null) {
        final placement = _builder.textPlacement(node);
        final origin = placement.origin;
        for (final line in text.computeLineMetrics()) {
          include(
            Rect.fromLTWH(
              origin.dx + line.left * placement.horizontalScale,
              origin.dy + line.baseline - line.ascent,
              line.width * placement.horizontalScale,
              line.ascent + line.descent,
            ),
            transform,
            node.id,
          );
        }
      }
      if (node.bitmap != null ||
          node.vector != null ||
          node.modelLockup != null) {
        include(
          node.modelLockup == null
              ? Offset.zero & node.unrotated
              : node.lockupPaintRect,
          transform,
          node.id,
        );
      }
      for (final child in node.children) {
        local(child, (point) => transform(child.local + point));
      }
    }

    void visit(_Node node) {
      if (!node.visible || node.source) return;
      if (node.containsSource) {
        for (final child in node.children) {
          visit(child);
        }
      } else {
        local(node, (point) => node.origin + point);
      }
    }

    for (final root in _roots) {
      visit(root);
    }
    return result;
  }

  /// Actual foreground geometry, including nested rotations and document transform.
  Rect? get decorationBounds {
    return _rawDecorationBounds;
  }

  XiaomiRenderPlan._(
    this.layout,
    this._builder,
    this._roots,
    this.resolvedTexts,
    this.nodeBounds,
  );

  static Map<String, dynamic>? selectedBackground(
    Map<String, dynamic> config, [
    Map<String, dynamic> options = const {},
  ]) {
    final backgrounds = xiaomiBackgroundChoices(config);
    if (backgrounds.isEmpty) return null;
    final requested = options['background'];
    if (requested != null) {
      for (final b in backgrounds) {
        if (_map(b)['name'] == requested) return _map(b);
      }
      throw FormatException('Unknown Xiaomi background: $requested');
    }
    String? declared;
    void visit(dynamic value) {
      if (value is Map) {
        final background = value['background'];
        if (declared == null &&
            background is String &&
            background.startsWith('@background=')) {
          declared = background.substring(12);
        }
        for (final child in value.values) {
          visit(child);
        }
      } else if (value is List) {
        for (final child in value) {
          visit(child);
        }
      }
    }

    visit(selectedLayouts(config, options));
    for (final b in backgrounds) {
      if (_map(b)['name'] == declared) return _map(b);
    }
    return _map(backgrounds.first);
  }

  static bool backgroundNeedsBlur(
    Map<String, dynamic> config, [
    Map<String, dynamic> options = const {},
  ]) {
    final selected = selectedBackground(config, options);
    final backgrounds = _map(config['wmRes'])['background'] as List? ?? [];
    bool visit(dynamic value) {
      if (value is Map) {
        if (value['enable'] == false) return false;
        var background = '${value['background'] ?? ''}';
        if (background.startsWith('@background=') &&
            value['background_optional'] == true &&
            selected != null) {
          background = '${selected['value']}';
        } else if (background.startsWith('@background=')) {
          final name = background.substring(12);
          final resource = backgrounds
              .where((b) => _map(b)['name'] == name)
              .firstOrNull;
          background = '${_map(resource)['value'] ?? ''}';
        }
        return _photoBlurAlgorithm(background) != null ||
            visit(value['childViews']);
      }
      return value is List && value.any(visit);
    }

    return visit(selectedLayouts(config, options));
  }

  /// A single pre-toned Xiaomi filter image is safe only when every blurred
  /// photo-blurred area requests the same lightness. Noise is a separate material.
  static String? backgroundBlurTone(
    Map<String, dynamic> config, [
    Map<String, dynamic> options = const {},
  ]) {
    final selected = selectedBackground(config, options);
    final backgrounds = _map(config['wmRes'])['background'] as List? ?? [];
    final tones = <String>{};
    void visit(dynamic value) {
      if (value is Map) {
        if (value['enable'] == false) return;
        var background = '${value['background'] ?? ''}';
        if (background.startsWith('@background=') &&
            value['background_optional'] == true &&
            selected != null) {
          background = '${selected['value']}';
        } else if (background.startsWith('@background=')) {
          final name = background.substring(12);
          final resource = backgrounds
              .where((b) => _map(b)['name'] == name)
              .firstOrNull;
          background = '${_map(resource)['value'] ?? ''}';
        }
        final algorithm = _photoBlurAlgorithm(background);
        if (algorithm != null) {
          tones.add(algorithm == 'dark_blur' ? 'dark' : 'light');
        }
        visit(value['childViews']);
      } else if (value is List) {
        for (final child in value) {
          visit(child);
        }
      }
    }

    visit(selectedLayouts(config, options));
    return tones.length == 1 ? tones.single : null;
  }

  static List<dynamic> selectedLayouts(
    Map<String, dynamic> config, [
    Map<String, dynamic> options = const {},
  ]) {
    final groups = config['layout_group'];
    if (groups is! List || groups.isEmpty) {
      throw const FormatException('Xiaomi layout_group is missing');
    }
    var index = 0;
    final choice = options['layoutGroup'];
    if (choice is num) index = choice.toInt();
    if (choice is String) {
      index = groups.indexWhere(
        (g) => _map(g).containsKey(choice) || _map(g)['id'] == choice,
      );
    }
    if (index < 0 || index >= groups.length) {
      throw const FormatException('Unknown Xiaomi layout group');
    }
    final group = _map(groups[index]);
    final layouts = options['layout'] == null
        ? group.values.whereType<List>().firstOrNull
        : group[options['layout']];
    if (layouts is! List || layouts.isEmpty) {
      throw const FormatException('Xiaomi layout is missing');
    }
    return layouts;
  }

  static bool hasBinding(
    Map<String, dynamic> config,
    String prefix, [
    Map<String, dynamic> options = const {},
  ]) {
    bool visit(dynamic value) {
      if (value is Map) {
        return value['enable'] != false &&
            ('${value['text'] ?? ''}'.startsWith(prefix) ||
                visit(value['childViews']));
      }
      return value is List && value.any(visit);
    }

    return visit(selectedLayouts(config, options));
  }

  static List<Map<String, dynamic>> selectableBackgrounds(
    Map<String, dynamic> config, [
    Map<String, dynamic> options = const {},
  ]) {
    bool optional(dynamic value) {
      if (value is Map) {
        return value['enable'] != false &&
            (value['background_optional'] == true ||
                optional(value['childViews']));
      }
      return value is List && value.any(optional);
    }

    if (!optional(selectedLayouts(config, options))) return [];
    return xiaomiBackgroundChoices(config);
  }

  static XiaomiRenderPlan build({
    required Map<String, dynamic> config,
    required EditDocument document,
    required PhotoAsset photo,
    required ui.Image image,
    required XiaomiRenderResources resources,
  }) {
    if (photo.width <= 0 || photo.height <= 0) {
      throw const FormatException('Invalid source photo size');
    }
    final normalized = document.isStaruw
        ? document
        : XiaomiCapabilities.fromConfig(
            config,
            document.xiaomiOptions,
            resources.images.keys,
          ).normalizeDocument(document.copyWith(xiaomiConfig: config));
    final b = _Builder(config, normalized, photo, image, resources);
    final layouts = selectedLayouts(config, b.options);
    final roots = layouts.map((j) => b.parse(_map(j))).toList();
    final available = Size(photo.width.toDouble(), photo.height.toDouble());
    for (final root in roots) {
      b.measure(root, available);
      b.position(root, Offset.zero);
    }
    final sources = <_Node>[];
    void collect(_Node n) {
      if (n.source) sources.add(n);
      for (final child in n.children) {
        collect(child);
      }
    }

    for (final root in roots) {
      collect(root);
    }
    if (sources.length != 1) {
      throw const FormatException(
        'Xiaomi layout must contain exactly one source image',
      );
    }
    final source = sources.single;
    // Integer translation preserves a one-to-one source rectangle in exported files.
    final shift = Offset(
      math.max(0.0, -source.origin.dx),
      math.max(0.0, -source.origin.dy),
    );
    if (shift != Offset.zero) {
      for (final root in roots) {
        b.move(root, shift);
      }
    }
    final width = roots.fold<double>(
      photo.width.toDouble(),
      (v, n) => math.max(v, n.origin.dx + n.size.width),
    );
    final height = roots.fold<double>(
      photo.height.toDouble(),
      (v, n) => math.max(v, n.origin.dy + n.size.height),
    );
    final px = source.origin.dx.round(), py = source.origin.dy.round();
    final layout = CanvasLayout(
      math.max(width.ceil(), px + photo.width),
      math.max(height.ceil(), py + photo.height),
      px,
      py,
      photo.width,
      photo.height,
    );
    final bounds = <String, Rect>{};
    b.materialBounds = Rect.fromLTWH(
      0,
      0,
      layout.width.toDouble(),
      layout.height.toDouble(),
    );
    b.photoBounds = Rect.fromLTWH(
      px.toDouble(),
      py.toDouble(),
      photo.width.toDouble(),
      photo.height.toDouble(),
    );
    final visibleTexts = <String, String>{};
    void inspect(_Node n) {
      if (!n.visible) return;
      bounds[n.id] = n.origin & n.size;
      if (n.text != null) visibleTexts[n.id] = n.text!.text!.toPlainText();
      for (final c in n.children) {
        inspect(c);
      }
    }

    for (final root in roots) {
      inspect(root);
    }
    return XiaomiRenderPlan._(
      layout,
      b,
      roots,
      Map.unmodifiable(visibleTexts),
      Map.unmodifiable(bounds),
    );
  }

  void paint(
    Canvas canvas,
    Size size, {
    bool original = false,
    double pixelRatio = 1,
    bool photoContribution = false,
    bool decorationOnly = false,
    bool backgroundOnly = false,
  }) {
    final b = _builder;
    canvas.save();
    canvas.scale(size.width / layout.width, size.height / layout.height);
    b.materialTransform = Matrix4.fromFloat64List(canvas.getTransform());
    final area = Rect.fromLTWH(
      0,
      0,
      layout.width.toDouble(),
      layout.height.toDouble(),
    );
    if (photoContribution) canvas.drawRect(area, Paint()..color = Colors.black);
    if (!original && !photoContribution) {
      // ceil() keeps every original photo pixel. Cover its fractional outer
      // remainder too, otherwise JPEG compositing turns the transparent edge dark.
      for (final root in _roots) {
        if (root.containsSource && root.json['background'] != null) {
          b.paintBackground(canvas, root, bounds: area);
          break;
        }
      }
      for (final root in _roots) {
        b.paintStructure(canvas, root);
      }
    }
    if (backgroundOnly) {
      canvas.restore();
      return;
    }
    if (photoContribution) {
      canvas.drawRect(
        Rect.fromLTWH(
          layout.photoX.toDouble(),
          layout.photoY.toDouble(),
          layout.photoWidth.toDouble(),
          layout.photoHeight.toDouble(),
        ),
        Paint()..color = Colors.white,
      );
    } else if (decorationOnly) {
      canvas.drawRect(
        Rect.fromLTWH(
          layout.photoX.toDouble(),
          layout.photoY.toDouble(),
          layout.photoWidth.toDouble(),
          layout.photoHeight.toDouble(),
        ),
        Paint()..blendMode = BlendMode.clear,
      );
    } else {
      paintImage(
        canvas: canvas,
        rect: Rect.fromLTWH(
          layout.photoX.toDouble(),
          layout.photoY.toDouble(),
          layout.photoWidth.toDouble(),
          layout.photoHeight.toDouble(),
        ),
        image: b.image,
        fit: BoxFit.fill,
        filterQuality: FilterQuality.high,
      );
    }
    if (!original) {
      if (b.document.opacity < 1 || photoContribution) {
        canvas.saveLayer(
          area,
          Paint()
            ..colorFilter = photoContribution
                ? const ColorFilter.mode(Colors.black, BlendMode.srcIn)
                : null
            ..color = Colors.white.withValues(
              alpha: b.document.opacity.clamp(0, 1),
            ),
        );
      } else {
        canvas.save();
      }
      for (final root in _roots) {
        b.paintDecoration(canvas, root, pixelRatio: pixelRatio);
      }
      canvas.restore();
    }
    canvas.restore();
  }
}

Map<String, dynamic> _map(dynamic value) =>
    value is Map ? Map<String, dynamic>.from(value) : <String, dynamic>{};
double _number(dynamic value, [double fallback = 0]) => value is num
    ? value.toDouble()
    : double.tryParse('$value'.replaceAll('f', '')) ?? fallback;

// OEM noise_blur is a tiled bitmap material, not a photograph blur.
String? _photoBlurAlgorithm(String value) =>
    RegExp(r'@algo=(light_blur|dark_blur)(?:;|$)').firstMatch(value)?.group(1);

class _Node {
  final Map<String, dynamic> json;
  final String type, id;
  final List<_Node> children;
  bool visible = true, containsSource = false;
  Size size = Size.zero, unrotated = Size.zero;
  Offset origin = Offset.zero, local = Offset.zero;
  TextPainter? text;
  TextPainter? metricText;
  TextSpan? baseText;
  TextSpan? selectedBaseText;
  ui.Image? bitmap;
  RegionArtwork? vector;
  Size? get artworkSize =>
      vector?.size ??
      (bitmap == null
          ? null
          : Size(bitmap!.width.toDouble(), bitmap!.height.toDouble()));
  String? imagePath;
  Color? imageTint;
  ModelLockup? modelLockup;
  Color? lockupColor;
  double lockupBaseline = 0;
  double lockupSlotHeight = 0, lockupInkBottom = 0;
  Rect lockupPaintRect = Rect.zero;
  bool completeModelImage = false;
  String? replacementRegion;
  List<_Node> modelMembers = [];
  Alignment modelAlignment = Alignment.centerLeft;
  _Node(this.json, this.children)
    : type = '${json['type']}',
      id = '${json['id'] ?? ''}';
  bool get source =>
      type == 'ImageView' &&
      (id == 'src_image_view' || json['path'] == 'source_image');
  int get degree => _number(json['degree']).toInt();
  String get orientation => '${json['orientation'] ?? 'none'}';
}

class _Builder {
  final Map<_Node, StaruwSlot> staruwSlots = {};
  final Map<_Node, Offset> staruwSlotOrigins = {};
  final Map<String, dynamic> config;
  final EditDocument document;
  final PhotoAsset photo;
  final ui.Image image;
  final XiaomiRenderResources resources;
  late final Map<String, dynamic> options = effectiveMetadataOptions(
    photo.metadata,
    document.xiaomiOptions,
  );
  late final Map<String, dynamic> wmRes = _map(config['wmRes']);
  late final Map<String, dynamic>? background =
      XiaomiRenderPlan.selectedBackground(config, options);
  late final double dp = math.min(photo.width, photo.height) / 1080;
  final Map<String, int> _nodeIds = {};
  Rect materialBounds = Rect.zero, photoBounds = Rect.zero;
  Matrix4 materialTransform = Matrix4.identity();
  _Builder(this.config, this.document, this.photo, this.image, this.resources);

  _Node parse(Map<String, dynamic> j, {String parentGravity = 'left|top'}) {
    if (!['Layout', 'TextView', 'ImageView'].contains(j['type'])) {
      throw FormatException('Unsupported Xiaomi node: ${j['type']}');
    }
    final orientation = j['orientation'];
    if (orientation != null &&
        !['horizontal', 'vertical', 'overlap', 'none'].contains(orientation)) {
      throw FormatException('Unsupported Xiaomi orientation: $orientation');
    }
    if (j['degree'] != null &&
        ![0, 90, 270].contains(_number(j['degree']).toInt())) {
      throw FormatException('Unsupported Xiaomi rotation: ${j['degree']}');
    }
    validateBackground(
      '${j['background'] ?? ''}',
      optional: j['background_optional'] == true,
    );
    final children = (j['childViews'] as List? ?? [])
        .map(
          (c) => parse(_map(c), parentGravity: '${j['gravity'] ?? 'left|top'}'),
        )
        .toList();
    // OEM files reuse IDs (Leica89 has three distinct `aperture` nodes).
    // Keep every leaf inspectable instead of silently overwriting earlier ones.
    final rawId = '${j['id'] ?? ''}';
    final occurrence = _nodeIds.update(rawId, (n) => n + 1, ifAbsent: () => 1);
    final n = _Node({
      ...j,
      if (occurrence > 1) 'id': '$rawId#$occurrence',
    }, children);
    n.containsSource = n.source || children.any((c) => c.containsSource);
    n.visible = j['enable'] != false;
    final setting = '${j['setting_optional_type'] ?? ''}';
    if (j['optional'] == true && !'${j['text'] ?? ''}'.startsWith('@mix=')) {
      final binding = xiaomiTextBinding('${j['text'] ?? ''}');
      if (((setting == 'type_time' || binding == 'time') &&
              options['showTime'] == false) ||
          ((setting == 'type_location' || binding == 'location') &&
              options['showLocation'] == false) ||
          ((setting == 'type_exif' || binding == 'exif') &&
              !document.showParameters) ||
          ((setting == 'type_model' || binding == 'model') &&
              !document.showModel)) {
        n.visible = false;
      }
    }
    if (setting == 'type_custom_text' &&
        document.caption.isEmpty &&
        !children.any(hasContent)) {
      n.visible = false;
    }
    if (setting.contains('leica_looks') &&
        options['filterName'] == null &&
        options['@type_leica_looks'] == null) {
      n.visible = false;
    }
    if (setting.contains('leica_lens') &&
        options['cvLens'] == null &&
        options['@type_leica_lens'] == null) {
      n.visible = false;
    }
    if (n.type == 'TextView') {
      var value =
          j['_staruwPhotoText'] as String? ??
          bind('${j['text'] ?? ''}', nodeId: '${j['id'] ?? ''}');
      if (j['text_upper'] == true) value = value.toUpperCase();
      n.visible = n.visible && value.isNotEmpty;
      if (n.visible) {
        n.text = textPainter(value, j);
        n.metricText =
            document.font.automatic && n.json['_staruwFontId'] == null
            ? n.text
            : textPainter(value, j, original: true);
        n.baseText = n.metricText!.text as TextSpan;
        n.selectedBaseText = n.text!.text as TextSpan;
      }
    } else if (n.type == 'ImageView' && !n.source) {
      final path = imagePath('${j['path'] ?? ''}');
      n.imagePath = path;
      n.bitmap = path == null ? null : resources.images[path];
      n.vector = path == null ? null : resources.vectors[path];
      final descriptor = '${j['path'] ?? ''}';
      final foregroundIndex = RegExp(
        r'foreground=(\d+)',
      ).firstMatch(descriptor)?.group(1);
      if (foregroundIndex != null) {
        final value = foreground(int.parse(foregroundIndex));
        if (value.startsWith('#')) n.imageTint = color(value);
      }
      if ((n.id == 'logo' || n.id == 'leica' || n.id == 'brand_leica') &&
          !document.showBrand) {
        n.visible = false;
      }
      if (path == null) n.visible = false;
      if (path != null && n.bitmap == null && n.vector == null) {
        throw FormatException('Missing Xiaomi image resource: $path');
      }
      replaceModelImage(n);
    }
    final modelRun = isModelRun(j);
    // Fit a signature inside the existing model slot using that slot's original
    // horizontal anchor. Keep the line's vertical center and measurement intact.
    // Read the source declaration: fallback text can set n.json['gravity'] to
    // center for its own line metrics, which must not recenter a model image.
    final modelGravity = modelRun
        ? j['gravity'] ?? j['layout_gravity'] ?? parentGravity
        : j['layout_gravity'] ?? j['gravity'] ?? parentGravity;
    n.modelAlignment = Alignment(align('$modelGravity', true, 2) - 1, 0);
    mergeModelLockup(n);
    if (rawId == 'img_model' && n.type == 'ImageView') {
      n.replacementRegion = 'model';
    }
    if (isParameterEmblem(j)) n.replacementRegion = 'parameters';
    if (modelRun) {
      n.modelMembers = n.children
          .where(
            (c) =>
                c.modelLockup != null ||
                c.completeModelImage ||
                (c.type == 'TextView' &&
                    '${c.json['text'] ?? ''}'.startsWith('@model=')) ||
                isModelBrand(c),
          )
          .toList();
    }
    if (!n.containsSource &&
        children.isNotEmpty &&
        children.every((c) => !c.visible) &&
        j['background'] == null &&
        !['layout_width', 'layout_height'].any((key) {
          final value = '${j[key] ?? ''}';
          return value.isNotEmpty &&
              value != 'wrap_content' &&
              !value.startsWith('match_parent') &&
              (dimension(value, Size.zero, key == 'layout_width') ?? 0) > 0;
        })) {
      // Hiding optional text does not remove a fixed frame slot. Film14's
      // 109dp header remains part of the frame even with time and GPS disabled.
      n.visible = false;
    }
    return n;
  }

  bool hasContent(_Node n) =>
      n.visible &&
      (n.text != null ||
          n.bitmap != null ||
          n.vector != null ||
          n.modelLockup != null ||
          n.children.any(hasContent));

  /// Some OEM layouts provide the complete model as one image rather than a
  /// brand plus @model text. Keep that image's slot, padding and anchors while
  /// applying the same metadata/override SVG composition as the split run.
  void replaceModelImage(_Node n) {
    final lockup = resources.modelLockup, size = n.artworkSize;
    if (n.id.split('#').first != 'img_model' || !n.visible || size == null) {
      return;
    }
    final model =
        (document.modelOverride.isEmpty
                ? photo.metadata.model
                : document.modelOverride)
            .trim()
            .replaceAll(RegExp(r'\s+'), ' ');
    if (model.isEmpty) {
      n.visible = false;
      return;
    }
    final height = dimension(n.json['display_height'], Size.zero, false);
    final width = dimension(n.json['display_width'], Size.zero, true);
    final scale = n.json['support_scale'] == true
        ? _number(n.json['_staruwScale'], document.scale)
        : 1.0;
    final slot =
        (height != null && height > 0
            ? height
            : width != null && width > 0
            ? width * size.height / size.width
            : 32 * dp) *
        scale;
    final declaredInk = n.json['starmark_model_ink'];
    final ink =
        declaredInk is List &&
            declaredInk.length == 4 &&
            declaredInk.every((v) => v is num)
        ? Rect.fromLTRB(
            (declaredInk[0] as num).toDouble(),
            (declaredInk[1] as num).toDouble(),
            (declaredInk[2] as num).toDouble(),
            (declaredInk[3] as num).toDouble(),
          ).intersect(Offset.zero & size)
        : resources.imageContentBounds[n.imagePath];
    final visible = ink == null || ink.isEmpty ? Offset.zero & size : ink;
    final variant = RegExp(
      r'_([a-fA-F0-9]{6,8}|white|black|red|yellow)(?:_shadow)?\.[^.]+$',
    ).firstMatch(n.imagePath ?? '')?.group(1);
    final foreground = options['textColor'] is num
        ? Color((options['textColor'] as num).toInt())
        : n.imageTint ??
              (n.json['starmark_model_color'] is String
                  ? color(n.json['starmark_model_color'] as String)
                  : variant == 'yellow'
                  ? Colors.yellow
                  : color(variant ?? 'black'));
    n.bitmap = null;
    n.vector = null;
    if (lockup == null) {
      // Unsupported SVG names still belong to the photograph/user. Render the
      // full name with the normal font policy, fitted to the original slot.
      final definition = <String, dynamic>{
        'typeface': 'default',
        'variation': "'wght' 380",
        'size': slot / (dp * scale),
        'color': '#${foreground.toARGB32().toRadixString(16).padLeft(8, '0')}',
      };
      n.text = textPainter(model, n.json, definition: definition);
      n.metricText = document.font.automatic && n.json['_staruwFontId'] == null
          ? n.text
          : textPainter(model, n.json, original: true, definition: definition);
      final fit = slot / n.metricText!.height;
      for (final painter in {n.text!, n.metricText!}) {
        final span = painter.text! as TextSpan;
        painter.text = TextSpan(
          text: span.text,
          style: span.style!.copyWith(fontSize: span.style!.fontSize! * fit),
        );
        painter.layout();
      }
      n.baseText = n.metricText!.text as TextSpan;
      n.selectedBaseText = n.text!.text as TextSpan;
      n.json['layout_height'] = '${slot / dp}dp';
      n.json['gravity'] = 'center';
      return;
    }
    n
      ..modelLockup = lockup
      ..lockupBaseline = slot * visible.height / size.height
      ..lockupSlotHeight = slot
      ..lockupInkBottom = slot * visible.bottom / size.height
      ..lockupColor = foreground;
  }

  /// Replace just the factory brand/model run. Other children (including Leica,
  /// separators and artwork) keep their original layout and rotation.
  void mergeModelLockup(_Node group) {
    final lockup = resources.modelLockup;
    if (lockup == null || group.type != 'Layout') {
      return;
    }
    final models = group.children
        .where(
          (child) =>
              child.type == 'TextView' &&
              '${child.json['text'] ?? ''}'.startsWith('@model='),
        )
        .toList();
    if (models.isEmpty) return;
    final brands = group.children.where(isModelBrand).toList();
    // Keep explicit visibility choices in imported layouts. An empty optional
    // suffix is still composable; a deliberately disabled run is not.
    if (!models.any((child) => child.visible) ||
        [...models, ...brands].any((child) => child.json['enable'] == false)) {
      return;
    }
    final firstModel = models.first;
    final anchor = document.showBrand && brands.isNotEmpty
        ? brands.first
        : firstModel;
    final matched = {...models, ...brands};
    final insertion = group.children.indexWhere(matched.contains);
    final f = _map(_map(wmRes['fonts'])[firstModel.json['font_ref']]);
    var baseline = _number(f['size'], 28) * dp;
    var slotHeight = baseline, inkBottom = baseline;
    if (brands.isNotEmpty) {
      final declared = dimension(
        brands.first.json['display_height'],
        Size.zero,
        false,
      );
      if (declared != null && declared > 0) {
        baseline = slotHeight = inkBottom = declared;
        final bitmap = brands.first.bitmap;
        final ink = resources.imageContentBounds[brands.first.imagePath];
        if (bitmap != null && ink != null && !ink.isEmpty) {
          baseline = declared * ink.height / bitmap.height;
          inkBottom = declared * ink.bottom / bitmap.height;
        }
      }
    }
    if (anchor.json['support_scale'] == true) {
      final scale = _number(anchor.json['_staruwScale'], document.scale);
      baseline *= scale;
      slotHeight *= scale;
      inkBottom *= scale;
    }
    // Transparent logo padding and the original text line boxes still belong
    // to the row; only the vector's visible capital height uses the ink bounds.
    final textHeight = models.fold<double>(
      0,
      (height, child) => math.max(height, child.metricText?.height ?? 0),
    );
    final extraHeight = math.max(0.0, textHeight - slotHeight);
    slotHeight += extraHeight;
    inkBottom += extraHeight / 2;
    final replacement =
        _Node({
            'type': 'ModelLockup',
            'id': '${group.id}_lockup',
            if (anchor.json['layout_gravity'] != null)
              'layout_gravity': anchor.json['layout_gravity'],
            if (anchor.json['margin_left'] != null)
              'margin_left': anchor.json['margin_left'],
            if (anchor.json['margin_top'] != null)
              'margin_top': anchor.json['margin_top'],
            if (anchor.json['degree'] != null) 'degree': anchor.json['degree'],
          }, [])
          ..modelLockup = lockup
          ..lockupBaseline = baseline
          ..lockupSlotHeight = slotHeight
          ..lockupInkBottom = inkBottom
          ..lockupColor = options['textColor'] is num
              ? Color((options['textColor'] as num).toInt())
              : color('${f['color'] ?? '#FF000000'}');
    group.children.removeWhere(matched.contains);
    group.children.insert(insertion, replacement);
  }

  bool isModelBrand(_Node node) {
    if (node.type != 'ImageView' || node.source) return false;
    final descriptor = '${node.json['path'] ?? ''}';
    if (descriptor.contains(r'path=${logo}')) return true;
    final filename = node.imagePath?.split('/').last.toLowerCase() ?? '';
    return RegExp(
      r'^(xiaomi|redmi|poco)(?:_|\.webp$|\.png$|\.svg$)',
    ).hasMatch(filename);
  }

  String bind(String input, {String? nodeId}) {
    final m = photo.metadata;
    if (input.startsWith('@mix=')) {
      final clauses = input.split(';');
      final state = _map(_map(options['mix'])[nodeId]);
      final kind = state['type'] ?? clauses.first.substring(5);
      if (kind == 'off') return '';
      final prefix = '@$kind=';
      final declared = clauses
          .where((part) => part.startsWith(prefix))
          .firstOrNull;
      if (kind == 'time') {
        return bind(declared ?? '@time=@wm_time_yyyy_MM_dd_HH_mm');
      }
      if (kind == 'exif') {
        // sm0.k's constructor supplies this format when @exif is omitted.
        return bind(
          declared ?? '@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}',
        );
      }
      if (kind == 'location' || kind == 'location_latlng') {
        return bind('@location=location_latlng');
      }
      if (kind == 'custom_text') return '${state['text'] ?? ''}';
      throw FormatException('Unsupported Xiaomi mix selection: $kind');
    }
    if (input.startsWith('@model=')) {
      var model = document.modelOverride.isNotEmpty
          ? document.modelOverride
          : m.model;
      model = model.trim().replaceAll(RegExp(r'\s+'), ' ');
      final prefix = RegExp(
        r'^(xiaomi|redmi|poco)\s+',
        caseSensitive: false,
      ).firstMatch(model);
      if (document.modelOverride.isEmpty &&
          document.showBrand &&
          prefix != null &&
          hasRenderedBrand(prefix[1]!.toLowerCase())) {
        model = model.substring(prefix.end);
      }
      final match = RegExp(r'^(.*?\d[\d\s.]*)(?:\s+(.+))?$').firstMatch(model);
      final base = match?.group(1)?.trim() ?? model;
      final suffix = match?.group(2) ?? '';
      return input
          .substring(7)
          .replaceAll('@{series}', base)
          .replaceAll('@{versionNumber}', '')
          .replaceAll('@{versionName}', suffix);
    }
    if (input.startsWith('@exif=')) {
      final template = input.substring(6);
      final overrides = _map(options['exifOverrides']);
      if (document.parametersOverride.isNotEmpty) {
        if (RegExp(r'@\{([^}]+)\}').allMatches(template).length > 1) {
          return document.parametersOverride;
        }
        final legacy = document.parametersOverride;
        for (final entry in {
          'focal': RegExp(r'(\d+(?:\.\d+)?)\s*mm', caseSensitive: false),
          'aperture': RegExp(r'f/?\s*(\d+(?:\.\d+)?)', caseSensitive: false),
          'speed': RegExp(r'(1/\d+|\d+(?:\.\d+)?)\s*s\b', caseSensitive: false),
          'iso': RegExp(r'ISO\s*(\d+)', caseSensitive: false),
        }.entries) {
          final value = entry.value.firstMatch(legacy)?.group(1);
          if (value != null) overrides.putIfAbsent(entry.key, () => value);
        }
        if (overrides.isEmpty) throw const FormatException('原厂分栏参数需要分别编辑');
      }
      final values = effectiveParameterValues(
        m,
        overrides,
        allowExisting: XiaomiCapabilities.fromConfig(
          config,
          options,
        ).canEditParameters,
      );
      final parts = template.split(RegExp(r'\s{2,}'));
      return parts
          .where(
            (p) => !RegExp(
              r'@\{([^}]+)\}',
            ).allMatches(p).any((m) => values[m.group(1)]?.isEmpty ?? true),
          )
          .map(
            (p) => p.replaceAllMapped(
              RegExp(r'@\{([^}]+)\}'),
              (m) => values[m.group(1)] ?? '',
            ),
          )
          .join('  ');
    }
    if (input.startsWith('@custom=') || input.startsWith('@greeting=')) {
      if (document.caption.isNotEmpty) return document.caption;
      final key = RegExp(r'@defaultText=(@[^;]+)').firstMatch(input)?.group(1);
      return key == null
          ? ''
          : resources.localizations[key] ??
                resources.localizations[key.substring(1)] ??
                '';
    }
    if (input.startsWith('@time=')) {
      final raw = '${m.toJson()['dateTime'] ?? ''}';
      final fields = RegExp(
        r'^(\d{4})[:\-](\d{2})[:\-](\d{2})[ T](\d{2}):(\d{2})',
      ).firstMatch(raw);
      if (fields == null) return '';
      final key = input.substring(6);
      const defaults = {
        '@wm_time_yyyy_MM_dd_HH_mm': 'yyyy.MM.dd HH:mm',
        '@wm_time_yyyy_MM_dd': 'yyyy.MM.dd',
        '@wm_time_HH_mm': 'HH:mm',
        '@wm_time_MM': 'MM',
        '@wm_time_dd': 'dd',
        '@wm_time_week': 'EEEE',
      };
      final pattern =
          resources.localizations[key] ??
          resources.localizations[key.substring(1)] ??
          defaults[key];
      if (pattern == null) {
        throw FormatException('Unsupported Xiaomi date binding: $key');
      }
      final date = DateTime.tryParse('${fields[1]}-${fields[2]}-${fields[3]}');
      // Xiaomi's EEEE pattern follows the Android device locale. Returning an
      // English weekday on a Chinese device also changes this run's width and
      // can disturb neighbouring factory date elements.
      final chinese =
          (resources.locale ?? ui.PlatformDispatcher.instance.locale)
              .languageCode ==
          'zh';
      final values = {
        'yyyy': fields[1]!,
        'MM': fields[2]!,
        'dd': fields[3]!,
        'HH': fields[4]!,
        'mm': fields[5]!,
        'EEEE': date == null
            ? ''
            : (chinese
                  ? ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日']
                  : [
                      'Monday',
                      'Tuesday',
                      'Wednesday',
                      'Thursday',
                      'Friday',
                      'Saturday',
                      'Sunday',
                    ])[date.weekday - 1],
      };
      return pattern.replaceAllMapped(
        RegExp(r'yyyy|EEEE|MM|dd|HH|mm'),
        (m) => values[m[0]]!,
      );
    }
    if (input.startsWith('@location=')) {
      if (input.contains('latlng')) {
        // sm0.i.p uses @LOC to put the two coordinates on separate lines
        // (Leica 91); it is not part of the location selector's name.
        final separator =
            input.split(';').any((part) => part.startsWith('@LOC'))
            ? '\n'
            : ' ';
        return m.formatCoordinates(separator: separator);
      }
      final address = options['locationAddress']?.toString() ?? '';
      // OEM location controls can display the photo's coordinates when no
      // resolved address is supplied. Preserve the original layout and DMS
      // formatting; never manufacture an address from incomplete GPS.
      return address.trim().isNotEmpty ? address : m.formatCoordinates();
    }
    if (input.startsWith('@filter=')) {
      final name = options['filterName']?.toString();
      return name == null
          ? ''
          : input
                .substring(8)
                .replaceAll(r'${uppercase_name}', name.toUpperCase());
    }
    if (input.startsWith('@')) {
      return resources.localizations[input] ??
          resources.localizations[input.substring(1)] ??
          '';
    }
    return input;
  }

  bool hasRenderedBrand(String brand) {
    bool visit(dynamic value) {
      if (value is Map) {
        if (value['enable'] == false) return false;
        final descriptor = '${value['path'] ?? ''}';
        if (value['type'] == 'ImageView' && descriptor.isNotEmpty) {
          final path = imagePath(descriptor);
          final filename = path?.split('/').last.toLowerCase() ?? '';
          if ((filename.startsWith('${brand}_') || filename == '$brand.webp') &&
              resources.images.containsKey(path)) {
            return true;
          }
        }
        return visit(value['childViews']);
      }
      return value is List && value.any(visit);
    }

    return visit(XiaomiRenderPlan.selectedLayouts(config, options));
  }

  String? imagePath(String value) {
    final parts = <String, String>{};
    for (final item in value.split(';')) {
      final p = item.indexOf('=');
      if (p >= 0) parts[item.substring(0, p)] = item.substring(p + 1);
    }
    var path = parts['path'] ?? value;
    final kind = parts['pathType'] ?? 'direct';
    if (!['direct', 'fill', 'select'].contains(kind)) {
      throw FormatException('Unsupported Xiaomi image binding: $kind');
    }
    if (kind == 'fill') {
      if (path != r'${logo}') {
        throw FormatException('Unsupported Xiaomi image fill: $path');
      }
      final name = '${photo.metadata.make} ${photo.metadata.model}'
          .toLowerCase();
      path = name.contains('poco')
          ? 'poco'
          : name.contains('redmi')
          ? 'redmi'
          : name.contains('xiaomi')
          ? 'xiaomi'
          : '';
      if (path.isEmpty || !document.showBrand) return null;
    } else if (kind == 'select') {
      final settingId = parts['customize_settings'];
      if (settingId != null) {
        final settings = wmRes['customize_settings'] as List? ?? [];
        final setting = settings
            .map(_map)
            .where((row) => row['id'] == settingId)
            .firstOrNull;
        if (setting == null) {
          throw FormatException('Unknown Xiaomi image setting: $settingId');
        }
        final choices = (setting['options'] as List? ?? []).map(_map).toList();
        final selection = _map(options['customizeSettings'])[settingId];
        if (selection == 'off' && setting['supportOff'] == true) return null;
        final selected = selection == null
            ? choices.firstOrNull
            : choices.where((row) => row['id'] == selection).firstOrNull;
        final index = int.tryParse(parts['sub_options'] ?? '');
        final suffixes = selected?['sub_options'] as List? ?? [];
        if (index == null || index < 0 || index >= suffixes.length) {
          throw FormatException('Invalid Xiaomi image sub-option: $settingId');
        }
        path = '${path}_${suffixes[index]}.webp';
        _safePath(path);
        return path;
      }
      if (path.startsWith('@type_')) {
        path = options[path]?.toString() ?? '';
      } else if (path.contains('@{cvLens}')) {
        final lens = options['cvLens'];
        if (lens == null) return null;
        path = path.replaceAll('@{cvLens}', '$lens');
      } else if (path == 'custom_icon' || path == 'icon_signature') {
        path = options[path]?.toString() ?? '';
      }
      if (path.isEmpty) return null;
    }
    if (path.isEmpty) return null;
    _safePath(path);
    if (kind == 'direct') return path;
    var fg = foreground(_number(parts['foreground']).toInt());
    if (fg.startsWith('#')) {
      fg = color(fg).computeLuminance() > .5 ? 'white' : 'black';
    }
    if (resources.images.containsKey('${path}_$fg.webp')) {
      return '${path}_$fg.webp';
    }
    if (resources.images.containsKey('$path.webp')) return '$path.webp';
    if (resources.images.containsKey(path)) return path;
    // Device-restricted OEM packs may only ship Xiaomi's gold brand. A REDMI
    // SVG/model run can replace it; absent dynamic brand art must not abort parse.
    if (kind == 'fill') return null;
    throw FormatException('Missing Xiaomi selected image: $path ($fg)');
  }

  void _safePath(String value) {
    if (value.contains('\\') ||
        value.startsWith('/') ||
        value.contains(':') ||
        value.split('/').any((p) => p == '..' || p == '.') ||
        value.contains('@{') ||
        value.contains(r'${')) {
      throw FormatException('Unsafe Xiaomi resource path: $value');
    }
  }

  String foreground(int index) {
    // Noise backgrounds intentionally omit a palette and inherit the native
    // declared background's foreground resources (including gold logo names).
    final values =
        background?['foreground'] ??
        XiaomiRenderPlan.selectedBackground(config)?['foreground'];
    return values is List && index < values.length
        ? '${values[index]}'
        : '#FF000000';
  }

  Color color(String value) {
    if (value.startsWith('@foreground=')) {
      value = foreground(int.tryParse(value.substring(12)) ?? 0);
    }
    if (value == 'black') return Colors.black;
    if (value == 'white') return Colors.white;
    if (value == 'red') return Colors.red;
    if (value == 'transparent') return Colors.transparent;
    final hex = value.replaceFirst('#', '');
    return Color(
      int.tryParse(hex.length == 6 ? 'FF$hex' : hex, radix: 16) ?? 0xff000000,
    );
  }

  TextPainter textPainter(
    String value,
    Map<String, dynamic> node, {
    bool original = false,
    Map<String, dynamic>? definition,
  }) {
    final f = definition ?? _map(_map(wmRes['fonts'])[node['font_ref']]);
    var selection = original ? const FontRef.photoDefault() : document.font;
    var selectedFamily = resources.defaultFamily;
    if (!original && node['_staruwFontId'] is String) {
      final id = node['_staruwFontId'] as String;
      final x = document.staruwConfig!['staruw']['xiaomi'] as Map;
      final entry = x['fonts'][id] as Map;
      final path = document.resourceBindings['fonts'][id] as String;
      final sameAs = (x['fontAliases'] as Map).entries
          .where(
            (e) => x['fonts'][e.value]['resourceId'] == entry['resourceId'],
          )
          .firstOrNull
          ?.key;
      selection = FontRef(
        id: sameAs == 'default'
            ? 'mi-sans'
            : sameAs == 'oxanium'
            ? 'oxanium'
            : id,
        axes: (entry['axes'] as Map).map(
          (k, v) => MapEntry(k as String, (v as num).toDouble()),
        ),
      );
      selectedFamily =
          resources.fonts[path] ?? (throw const FormatException('包内所选字体未加载'));
    }
    var fontSize = _number(f['size'], 28) * dp;
    if (node['support_scale'] == true) {
      fontSize *= _number(node['_staruwScale'], document.scale);
    }
    final typeface = '${f['typeface'] ?? 'default'}';
    if (typeface.startsWith('@file=')) _safePath(typeface.substring(6));
    final font = xiaomiFontStyle(
      definition: f,
      selection: selection,
      metadata: photo.metadata,
      defaultFamily: selectedFamily,
      fonts: resources.fonts,
    );
    if (!original && !selection.automatic) {
      final reference = xiaomiFontStyle(
        definition: f,
        selection: const FontRef.photoDefault(),
        metadata: photo.metadata,
        defaultFamily: resources.defaultFamily,
        fonts: resources.fonts,
      );
      final referenceCap = resources.fontCapHeights[reference.fontFamily];
      final selectedCap = resources.fontCapHeights[font.fontFamily];
      if (referenceCap != null && selectedCap != null) {
        fontSize *= referenceCap / selectedCap;
      }
    }
    final shadow = f['shadow'];
    final p = TextPainter(
      text: TextSpan(
        text: value,
        style: font.copyWith(
          fontSize: fontSize,
          letterSpacing: _number(f['letterSpacing']) * fontSize,
          // The APK's on0.j text bitmap uses Paint ascent/descent. A forced
          // em-height clips those metrics and moves every neighbouring row.
          color: options['textColor'] is num
              ? Color((options['textColor'] as num).toInt())
              : color('${f['color'] ?? '#FF000000'}'),
          shadows: shadow is List && shadow.length == 4
              ? [
                  Shadow(
                    blurRadius: _number(shadow[0]) * dp,
                    offset: Offset(
                      _number(shadow[1]) * dp,
                      _number(shadow[2]) * dp,
                    ),
                    color: color('${shadow[3]}'),
                  ),
                ]
              : null,
        ),
      ),
      textDirection: TextDirection.ltr,
      maxLines: value.contains('\n') ? 5 : 1,
      ellipsis: '…',
    );
    p.layout();
    return p;
  }

  double? dimension(dynamic value, Size available, bool horizontal) {
    if (value == null || value == 'wrap_content') return null;
    final s = '$value';
    if (s.startsWith('match_parent')) {
      return math.max(
        0,
        (horizontal ? available.width : available.height) -
            _number(s.substring(12).replaceAll('dp', '')) * dp,
      );
    }
    if (s.endsWith('%w')) {
      return _number(s.substring(0, s.length - 2)) * photo.width / 100;
    }
    if (s.endsWith('%h')) {
      return _number(s.substring(0, s.length - 2)) * photo.height / 100;
    }
    if (s.endsWith('dp')) return _number(s.substring(0, s.length - 2)) * dp;
    final number = double.tryParse(s);
    if (number != null && number.isFinite) return number * dp;
    throw FormatException('Unsupported Xiaomi dimension: $s');
  }

  double margin(_Node n, bool horizontal) =>
      dimension(
        n.json[horizontal ? 'margin_left' : 'margin_top'],
        Size.zero,
        horizontal,
      ) ??
      0;

  bool rowCenterAnchor(_Node parent, _Node child) {
    final width = parent.json['layout_width'];
    final gravity = '${child.json['layout_gravity'] ?? child.json['gravity']}'
        .split('|');
    return parent.orientation == 'horizontal' &&
        width != null &&
        width != 'wrap_content' &&
        (gravity.contains('center_horizontal') || gravity.contains('center'));
  }

  bool rowOverlay(_Node parent, _Node child) =>
      rowCenterAnchor(parent, child) ||
      (parent.orientation == 'horizontal' &&
          '${child.json['layout_width']}'.startsWith('match_parent') &&
          !'${parent.json['gravity']}'.split('|').contains('right') &&
          '${child.json['layout_gravity'] ?? child.json['gravity']}'
              .split('|')
              .contains('right'));

  void measure(_Node n, Size available) {
    if (!n.visible) {
      n.size = Size.zero;
      n.unrotated = Size.zero;
      return;
    }
    if (n.source) {
      n.size = Size(photo.width.toDouble(), photo.height.toDouble());
      n.unrotated = n.size;
      return;
    }
    // Constraints arrive in the parent's coordinate system. A rotated strip's
    // text runs along the parent's height, not its narrow physical width.
    if (n.degree == 90 || n.degree == 270) {
      available = Size(available.height, available.width);
    }
    var width = dimension(n.json['layout_width'], available, true);
    var height = dimension(n.json['layout_height'], available, false);
    double naturalW = 0, naturalH = 0;
    if (n.modelLockup != null) {
      naturalW = n.modelLockup!.size.width * n.lockupBaseline / 80;
      final inkHeight = n.modelLockup!.size.height * n.lockupBaseline / 80;
      naturalH = math.max(n.lockupSlotHeight, inkHeight);
      var fit = 1.0;
      if (naturalW > available.width && available.width > 0) {
        fit = available.width / naturalW;
        naturalH *= fit;
        naturalW = available.width;
      }
      n.lockupPaintRect = Rect.fromLTWH(
        0,
        // Tall suffix bleed belongs inside the measured model slot. Otherwise
        // replacing the model leaves pixels above the region's recorded bounds.
        (n.lockupInkBottom - inkHeight).clamp(0.0, naturalH / fit - inkHeight) *
            fit,
        naturalW,
        inkHeight * fit,
      );
    } else if (n.text != null) {
      final painter = n.metricText!;
      painter.text = n.baseText;
      painter.layout();
      final maxWidth = math.max(
        0.0,
        math.min(width ?? available.width, available.width),
      );
      if (painter.width > maxWidth && maxWidth > 0) {
        final base = n.baseText!;
        final style = base.style!;
        final scale = (maxWidth / painter.width).clamp(.05, 1.0);
        painter.text = TextSpan(
          text: base.text,
          style: style.copyWith(
            fontSize: style.fontSize! * scale,
            letterSpacing: (style.letterSpacing ?? 0) * scale,
          ),
        );
      }
      painter.layout(maxWidth: maxWidth);
      if (!identical(n.text, painter)) {
        final selected = n.selectedBaseText!;
        final fit =
            (painter.text! as TextSpan).style!.fontSize! /
            n.baseText!.style!.fontSize!;
        n.text!.text = TextSpan(
          text: selected.text,
          style: selected.style!.copyWith(
            fontSize: selected.style!.fontSize! * fit,
            letterSpacing: (selected.style!.letterSpacing ?? 0) * fit,
          ),
        );
        // The reference font owns the line spacing as well as the outer box.
        // Without a strut only the first baseline can be restored at paint time;
        // every subsequent GPS/address line follows the replacement's metrics.
        n.text!.strutStyle = StrutStyle.fromTextStyle(
          (painter.text! as TextSpan).style!,
          forceStrutHeight: true,
        );
        n.text!.layout();
      }
      final rect = _map(n.json['rect_params']);
      naturalW = painter.width + _number(rect['rect_text_width_gap']) * 2 * dp;
      naturalH =
          painter.height + _number(rect['rect_text_height_gap']) * 2 * dp;
      width ??= dimension(rect['rect_width'], available, true);
      height ??= dimension(rect['rect_height'], available, false);
    } else if (n.artworkSize != null) {
      width = dimension(n.json['display_width'], available, true);
      height = dimension(n.json['display_height'], available, false);
      if (width == 0) width = null;
      if (height == 0) height = null;
      if (n.json['support_scale'] == true && !n.completeModelImage) {
        final scale = _number(n.json['_staruwScale'], document.scale);
        width = width == null ? null : width * scale;
        height = height == null ? null : height * scale;
      }
      final aspect = n.artworkSize!.aspectRatio;
      naturalW = width ?? (height ?? 32 * dp) * aspect;
      naturalH = height ?? (width ?? naturalW) / aspect;
      if (n.completeModelImage &&
          naturalW > available.width &&
          available.width > 0) {
        naturalH *= available.width / naturalW;
        naturalW = available.width;
        width = naturalW;
        height = naturalH;
      }
    } else {
      final limits = Size(width ?? available.width, height ?? available.height);
      // A cross-axis match_parent spacer depends on the wrap-content parent's
      // measured extent, so it cannot also define that extent. Measure the
      // independent siblings first; the existing final pass then fills spacers
      // from the resolved parent. Otherwise a short photo-corner row inherits
      // the whole photo height, or an 8dp separator inherits its whole width.
      double childWidth(_Node c) =>
          n.orientation == 'vertical' &&
              width == null &&
              '${c.json['layout_width']}'.startsWith('match_parent')
          ? 0
          : c.size.width;
      double childHeight(_Node c) =>
          n.orientation == 'horizontal' &&
              height == null &&
              '${c.json['layout_height']}'.startsWith('match_parent')
          ? 0
          : c.size.height;
      var rowWidth = 0.0, overlayWidth = 0.0;
      for (final c in n.children) {
        measure(c, limits);
        if (!c.visible) continue;
        final w = childWidth(c) + math.max(0, margin(c, true));
        final h = childHeight(c) + math.max(0, margin(c, false));
        if (n.orientation == 'horizontal') {
          if (rowOverlay(n, c)) {
            overlayWidth = math.max(overlayWidth, w);
          } else {
            rowWidth += w;
          }
          naturalW = math.max(rowWidth, overlayWidth);
          naturalH = math.max(naturalH, h);
        } else if (n.orientation == 'vertical') {
          naturalH += h;
          naturalW = math.max(naturalW, w);
        } else {
          naturalW = math.max(naturalW, w);
          naturalH = math.max(naturalH, h);
        }
      }
      if (n.orientation == 'horizontal' &&
          !n.containsSource &&
          limits.width > 0) {
        // Left/model and right/metadata runs share one physical row. Measure flex
        // children within their share before positioning the right-anchored run.
        final allVisible = n.children.where((c) => c.visible).toList();
        final visible = allVisible.where((c) => !rowOverlay(n, c)).toList();
        final flexible = visible
            .where(
              (c) =>
                  c.modelLockup != null ||
                  c.json['layout_width'] == null ||
                  c.json['layout_width'] == 'wrap_content',
            )
            .toList();
        final rightAnchored =
            !'${n.json['gravity'] ?? ''}'.split('|').contains('right') &&
            visible.any(
              (c) => '${c.json['layout_gravity'] ?? c.json['gravity'] ?? ''}'
                  .split('|')
                  .contains('right'),
            );
        final margins = visible.fold<double>(
          0,
          (v, c) => v + margin(c, true).abs(),
        );
        final fixed = visible
            .where((c) => !flexible.contains(c))
            .fold<double>(0, (v, c) => v + c.size.width);
        final total = flexible.fold<double>(0, (v, c) => v + c.size.width);
        final room = math.max(
          0.0,
          limits.width - fixed - margins - (rightAnchored ? 16 * dp : 0),
        );
        if (total > 0 && room < total) {
          final budgets = [
            for (final c in flexible) room * c.size.width / total,
          ];
          for (var i = 0; i < flexible.length; i++) {
            measure(flexible[i], Size(budgets[i], limits.height));
          }
          naturalW = visible.fold<double>(
            0,
            (v, c) => v + c.size.width + math.max(0, margin(c, true)),
          );
          naturalW = math.max(naturalW, overlayWidth);
          naturalH = allVisible.fold<double>(
            0,
            (v, c) =>
                math.max(v, childHeight(c) + math.max(0, margin(c, false))),
          );
        }
      }
    }
    n.unrotated = Size(
      math.max(0, width ?? naturalW),
      math.max(0, height ?? naturalH),
    );
    n.size = n.degree == 90 || n.degree == 270
        ? Size(n.unrotated.height, n.unrotated.width)
        : n.unrotated;
    // Resolve cross-axis match_parent after a wrap-content container is measured.
    for (final c in n.children) {
      if (!c.visible) continue;
      final cross = n.orientation == 'vertical'
          ? 'layout_width'
          : n.orientation == 'horizontal'
          ? 'layout_height'
          : null;
      if (cross != null && '${c.json[cross]}'.startsWith('match_parent')) {
        measure(c, n.unrotated);
      }
    }
  }

  double align(String gravity, bool horizontal, double room) {
    final parts = gravity.split('|');
    if (parts.contains(horizontal ? 'right' : 'bottom')) return room;
    if (parts.contains('center') ||
        parts.contains(horizontal ? 'center_horizontal' : 'center_vertical')) {
      return room / 2;
    }
    return 0;
  }

  void position(_Node n, Offset origin) {
    n.origin = origin;
    final horizontal = n.orientation == 'horizontal',
        vertical = n.orientation == 'vertical';
    final children = n.children.where((c) => c.visible).toList();
    final gravity = '${n.json['gravity'] ?? 'left|top'}';
    final rightJustified = gravity.split('|').contains('right');
    final used = children
        .where((c) => !rowOverlay(n, c))
        .fold<double>(
          0,
          (v, c) =>
              v +
              (horizontal
                  ? c.size.width +
                        (rightJustified
                            ? margin(c, true).abs()
                            : math.max(0, margin(c, true)))
                  : c.size.height + math.max(0, margin(c, false))),
        );
    var cursor = align(
      gravity,
      horizontal,
      horizontal ? n.unrotated.width - used : n.unrotated.height - used,
    );
    for (final c in children) {
      final childGravity =
          '${c.json['layout_gravity'] ?? c.json['gravity'] ?? gravity}';
      var x =
          align(childGravity, true, n.unrotated.width - c.size.width) +
          margin(c, true);
      var y =
          align(childGravity, false, n.unrotated.height - c.size.height) +
          margin(c, false);
      if (horizontal) {
        // Opposite anchored groups share the full row; a negative margin is an
        // inset from the right edge in the original layouts.
        if (rowCenterAnchor(n, c)) {
          // A centered child in a bounded row has its own anchor. Leica 91
          // declares GPS in the photo center and EXIF against the right inset;
          // packing both into one right-aligned run erases that separation.
          x = (n.unrotated.width - c.size.width) / 2 + margin(c, true);
        } else if (childGravity.split('|').contains('right') &&
            !rightJustified) {
          x = n.unrotated.width - c.size.width + margin(c, true);
        } else {
          // Negative left margins in a right-justified run are end insets.
          // Reserve them before the run instead of backing one child over its
          // preceding sibling (for example GPS beside the Leica91 EXIF block).
          x =
              cursor +
              (rightJustified ? math.max(0, margin(c, true)) : margin(c, true));
        }
        if (!rowOverlay(n, c)) {
          cursor += c.size.width + math.max(0, margin(c, true));
        }
      } else if (vertical) {
        y =
            childGravity.split('|').contains('bottom') &&
                !gravity.split('|').contains('bottom')
            ? n.unrotated.height - c.size.height + margin(c, false)
            : cursor + margin(c, false);
        cursor += c.size.height + math.max(0, margin(c, false));
      }
      c.local = Offset(x, y);
      position(c, origin + c.local);
    }
  }

  void move(_Node n, Offset delta) {
    n.origin += delta;
    for (final c in n.children) {
      move(c, delta);
    }
  }

  String backgroundValue(String value, {bool optional = false}) {
    if (value.startsWith('@foreground=') &&
        background?['starmarkBaseColor'] is String &&
        color(value) == color(background!['starmarkBaseColor'] as String)) {
      return '${background!['value']}';
    }
    if (!value.startsWith('@background=')) return value;
    final name = value.substring(12);
    if (optional && background != null) return '${background!['value']}';
    final list = wmRes['background'];
    if (list is List) {
      for (final b in list) {
        if (_map(b)['name'] == name) return '${_map(b)['value']}';
      }
    }
    if (name == 'transparent') return '@color=#00000000';
    throw FormatException('Unknown Xiaomi background reference: $name');
  }

  void validateBackground(String value, {bool optional = false}) {
    value = backgroundValue(value, optional: optional);
    final algorithm = RegExp(r'@algo=([^;]+)').firstMatch(value)?.group(1);
    if (algorithm != null &&
        !['light_blur', 'dark_blur', 'noise_blur'].contains(algorithm) &&
        !algorithm.startsWith('gradient_color(')) {
      throw FormatException(
        'Unsupported Xiaomi background algorithm: $algorithm',
      );
    }
    final path = RegExp(r'@img=([^;]+)').firstMatch(value)?.group(1);
    if (algorithm != null && algorithm.startsWith('gradient_color(')) {
      final match = RegExp(
        r'^gradient_color\(([^)]+)\)$',
      ).firstMatch(algorithm);
      final args =
          match?[1]?.split(',').map((v) => v.trim()).toList() ?? <String>[];
      final count = args.length >= 5 ? int.tryParse(args[4]) ?? 0 : 0;
      bool numeric(String s) => double.tryParse(s)?.isFinite ?? false;
      if (count < 2 ||
          count > 32 ||
          args.length != 5 + count * 2 ||
          !args.take(4).every(numeric) ||
          !args
              .skip(5)
              .take(count)
              .every(
                (s) =>
                    RegExp(r'^#[a-fA-F0-9]{6}([a-fA-F0-9]{2})?$').hasMatch(s),
              ) ||
          !args.skip(5 + count).every(numeric)) {
        throw const FormatException('Invalid Xiaomi gradient');
      }
      final stops = args.skip(5 + count).map(double.parse).toList();
      for (var i = 0; i < stops.length; i++) {
        if (stops[i] < 0 ||
            stops[i] > 1 ||
            (i > 0 && stops[i] < stops[i - 1])) {
          throw const FormatException('Invalid Xiaomi gradient stops');
        }
      }
    }
    if (path != null) {
      _safePath(path);
      if (!resources.images.containsKey(path)) {
        throw FormatException('Missing Xiaomi background resource: $path');
      }
    }
  }

  void paintBackground(Canvas canvas, _Node n, {Rect? bounds}) {
    final value = backgroundValue(
      '${n.json['background'] ?? ''}',
      optional: n.json['background_optional'] == true,
    );
    if (value.isEmpty) return;
    final rect = bounds ?? Offset.zero & n.unrotated;
    final c = RegExp(r'@color=(#[0-9A-Fa-f]+)').firstMatch(value)?.group(1);
    if (c != null ||
        value.startsWith('#') ||
        value.startsWith('@foreground=')) {
      canvas.drawRect(rect, Paint()..color = color(c ?? value));
    }
    final photoBlur = _photoBlurAlgorithm(value);
    if (photoBlur != null) {
      final bg = resources.background;
      if (bg != null) {
        final globalMaterial =
            options['glassBackground'] == true ||
            background?['starmarkBaseColor'] != null;
        canvas.save();
        canvas.clipRect(rect);
        if (globalMaterial) {
          final toRoot = Matrix4.fromFloat64List(canvas.getTransform())
            ..invert()
            ..multiply(materialTransform);
          canvas.transform(toRoot.storage);
        }
        paintImage(
          canvas: canvas,
          rect: globalMaterial ? materialBounds : rect,
          image: bg,
          fit: BoxFit.cover,
          filterQuality: FilterQuality.high,
        );
        canvas.restore();
      }
      if (!resources.backgroundToned || bg == null) {
        canvas.drawRect(
          rect,
          Paint()
            ..color = (photoBlur == 'dark_blur' ? Colors.black : Colors.white)
                .withValues(alpha: document.tint),
        );
      }
    }
    final gradient = RegExp(r'gradient_color\(([^)]+)\)').firstMatch(value);
    if (gradient != null) {
      final args = gradient[1]!.split(',').map((v) => v.trim()).toList();
      if (args.length >= 5) {
        final count = int.tryParse(args[4]) ?? 0;
        if (count > 1 && args.length == 5 + count * 2) {
          final colors = args.sublist(5, 5 + count).map(color).toList();
          final stops = args
              .sublist(5 + count)
              .map((v) => double.parse(v))
              .toList();
          canvas.drawRect(
            rect,
            Paint()
              ..shader = ui.Gradient.linear(
                Offset(
                  _number(args[0]) * rect.width,
                  _number(args[1]) * rect.height,
                ),
                Offset(
                  _number(args[2]) * rect.width,
                  _number(args[3]) * rect.height,
                ),
                colors,
                stops,
              ),
          );
        } else {
          throw const FormatException('Invalid Xiaomi gradient');
        }
      }
    }
    final path = RegExp(r'@img=([^;]+)').firstMatch(value)?.group(1);
    if (path != null) {
      _safePath(path);
      final bitmap = resources.images[path];
      if (bitmap == null) {
        throw FormatException('Missing Xiaomi background resource: $path');
      }
      if (RegExp(r'@algo=noise_blur(?:;|$)').hasMatch(value)) {
        paintNoiseTexture(canvas, n, rect, bitmap);
        return;
      }
      paintImage(
        canvas: canvas,
        rect: rect,
        image: bitmap,
        fit: BoxFit.fill,
        // OEM on0.a.c paints the background color and bitmap independently.
        // Multiplication by an alpha-zero base erased the opaque 142 texture.
      );
    }
  }

  void paintNoiseTexture(Canvas canvas, _Node n, Rect rect, ui.Image texture) {
    // MiMediaEditor on0.k uses photo-width/10 and photo-height/10 crops,
    // five source origins, 0..329 degree rotations, and width/10 grid steps.
    // Keep the OEM alpha over the parent frame; no photo or white tint belongs
    // here. A stable node seed prevents the grain from changing on each repaint.
    final tileWidth = math.max(1, photo.width ~/ 10).toDouble();
    final tileHeight = math.max(1, photo.height ~/ 10).toDouble();
    var seed = photo.width * 31 + photo.height;
    for (final unit in n.id.codeUnits) {
      seed = (seed * 31 + unit) & 0x7fffffff;
    }
    final random = math.Random(seed);
    final halfWidth = texture.width ~/ 2;
    final halfHeight = texture.height ~/ 2;
    final origins = [
      Offset.zero,
      Offset(halfWidth.toDouble(), halfHeight.toDouble()),
      Offset(0, halfHeight.toDouble()),
      Offset(halfWidth.toDouble(), 0),
      Offset((halfWidth ~/ 2).toDouble(), (halfHeight ~/ 2).toDouble()),
    ];
    final paint = Paint()..isAntiAlias = false;
    canvas.save();
    canvas.clipRect(rect);
    for (var y = rect.top; y < rect.bottom; y += tileWidth) {
      for (var x = rect.left; x < rect.right; x += tileWidth) {
        final sourceOrigin = origins[random.nextInt(origins.length)];
        final source = sourceOrigin & Size(tileWidth, tileHeight);
        final destination = Rect.fromLTWH(x, y, tileWidth, tileHeight);
        canvas.save();
        canvas.translate(destination.center.dx, destination.center.dy);
        canvas.rotate(random.nextInt(330) * math.pi / 180);
        canvas.translate(-destination.center.dx, -destination.center.dy);
        canvas.drawImageRect(texture, source, destination, paint);
        canvas.restore();
      }
    }
    canvas.restore();
  }

  void paintStructure(Canvas canvas, _Node n) {
    if (!n.visible || !n.containsSource) return;
    canvas.save();
    canvas.translate(n.origin.dx, n.origin.dy);
    paintBackground(canvas, n);
    canvas.restore();
    for (final c in n.children) {
      paintStructure(canvas, c);
    }
  }

  void paintDecoration(Canvas canvas, _Node n, {double pixelRatio = 1}) {
    if (!n.visible || n.source) return;
    if (n.containsSource) {
      for (final c in n.children) {
        paintDecoration(canvas, c, pixelRatio: pixelRatio);
      }
      return;
    }
    canvas.save();
    canvas.translate(n.origin.dx, n.origin.dy);
    paintLocal(canvas, n, pixelRatio: pixelRatio);
    canvas.restore();
  }

  ({Offset origin, double horizontalScale}) textPlacement(_Node n) {
    final text = n.text!, metric = n.metricText!;
    // OEM on0.m gives its inner text gravity 48 (horizontal + vertical center).
    // The JSON layout_gravity only positions that enclosing rectangle.
    final gravity = _map(n.json['rect_params']).isNotEmpty
        ? 'center'
        : '${n.json['gravity'] ?? n.json['layout_gravity'] ?? 'center'}';
    final horizontalScale = text.width > 0
        ? math.min(1.0, metric.width / text.width)
        : 1.0;
    final referenceBaseline =
        metric.computeLineMetrics().firstOrNull?.baseline ?? 0;
    final selectedBaseline =
        text.computeLineMetrics().firstOrNull?.baseline ?? 0;
    return (
      origin: Offset(
        align(gravity, true, n.unrotated.width - text.width * horizontalScale),
        align(gravity, false, n.unrotated.height - metric.height) +
            referenceBaseline -
            selectedBaseline,
      ),
      horizontalScale: horizontalScale,
    );
  }

  void paintLocal(Canvas canvas, _Node n, {double pixelRatio = 1}) {
    if (!n.visible || n.source) return;
    if (n.degree == 90) {
      canvas.translate(n.size.width, 0);
      canvas.rotate(math.pi / 2);
    }
    if (n.degree == 270) {
      canvas.translate(0, n.size.height);
      canvas.rotate(-math.pi / 2);
    }
    paintBackground(canvas, n);
    final staruwReplacement = staruwSlots[n];
    if (staruwReplacement != null) {
      final origin = staruwSlotOrigins[n] ?? Offset.zero;
      canvas.save();
      canvas.translate(origin.dx, origin.dy);
      canvas.scale(dp);
      staruwReplacement.paint(canvas);
      canvas.restore();
      if (n.modelMembers.isEmpty) return;
    }
    final replacement = document.regionReplacements[n.replacementRegion];
    if (replacement is Map &&
        paintRegion(
          canvas,
          Offset.zero & n.unrotated,
          replacement,
          resources.regionAssets,
          resources.defaultFamily,
          n.lockupColor ??
              n.text?.text?.style?.color ??
              Color(document.textColor),
          imageAlignment: n.replacementRegion == 'model'
              ? n.modelAlignment
              : Alignment.center,
          tintSvg: n.replacementRegion == 'model',
        )) {
      return;
    }
    final modelDesign = document.regionReplacements['model'];
    var paintedModel = staruwReplacement != null;
    if (modelDesign is Map && n.modelMembers.isNotEmpty) {
      final boxes = n.modelMembers
          .where((c) => c.visible)
          .map((c) => c.local & c.size)
          .where((r) => !r.isEmpty)
          .toList();
      if (boxes.isNotEmpty) {
        final slot = boxes.reduce((a, b) => a.expandToInclude(b));
        paintedModel = paintRegion(
          canvas,
          slot,
          modelDesign,
          resources.regionAssets,
          resources.defaultFamily,
          n.modelMembers.first.lockupColor ??
              n.modelMembers
                  .map((c) => c.text?.text?.style?.color)
                  .whereType<Color>()
                  .firstOrNull ??
              Color(document.textColor),
          imageAlignment: n.modelAlignment,
          tintSvg: true,
        );
      }
    }
    n.modelLockup?.paint(
      canvas,
      n.lockupPaintRect,
      n.lockupColor ?? Colors.black,
      pixelRatio: pixelRatio,
    );
    final text = n.text;
    if (text != null) {
      final rect = _map(n.json['rect_params']);
      if (rect.isNotEmpty) {
        final radii = rect['rect_radius'] as List? ?? [];
        Radius corner(int index) => radii.length >= 8
            ? Radius.elliptical(
                math.max(0, _number(radii[index]) * dp),
                math.max(0, _number(radii[index + 1]) * dp),
              )
            : Radius.circular(
                radii.isEmpty ? 0 : math.max(0, _number(radii.first) * dp),
              );
        final shape = RRect.fromRectAndCorners(
          Offset.zero & n.unrotated,
          topLeft: corner(0),
          topRight: corner(2),
          bottomRight: corner(4),
          bottomLeft: corner(6),
        );
        if (rect['rect_fill_color'] != null) {
          canvas.drawRRect(
            shape,
            Paint()..color = color('${rect['rect_fill_color']}'),
          );
        }
        if (rect['rect_line_color'] != null &&
            _number(rect['rect_line_width'], 1) > 0) {
          canvas.drawRRect(
            shape,
            Paint()
              ..style = PaintingStyle.stroke
              ..strokeWidth = _number(rect['rect_line_width'], 1) * dp
              ..color = color('${rect['rect_line_color']}'),
          );
        }
      }
      final placement = textPlacement(n);
      canvas.save();
      canvas.translate(placement.origin.dx, placement.origin.dy);
      canvas.scale(placement.horizontalScale, 1);
      text.paint(canvas, Offset.zero);
      canvas.restore();
    }
    if (n.bitmap != null) {
      paintImage(
        canvas: canvas,
        rect: Offset.zero & n.unrotated,
        image: n.bitmap!,
        fit: BoxFit.fill,
        filterQuality: FilterQuality.high,
        colorFilter: n.imageTint == null
            ? null
            : ColorFilter.mode(n.imageTint!, BlendMode.srcIn),
      );
    }
    if (n.vector != null) {
      final rect = Offset.zero & n.unrotated;
      if (n.imageTint != null) {
        canvas.saveLayer(
          rect,
          Paint()
            ..colorFilter = ColorFilter.mode(n.imageTint!, BlendMode.srcIn),
        );
      }
      n.vector!.paint(canvas, rect, false);
      if (n.imageTint != null) canvas.restore();
    }
    for (final c in n.children) {
      if (paintedModel && n.modelMembers.contains(c)) continue;
      canvas.save();
      canvas.translate(c.local.dx, c.local.dy);
      paintLocal(canvas, c, pixelRatio: pixelRatio);
      canvas.restore();
    }
  }
}
