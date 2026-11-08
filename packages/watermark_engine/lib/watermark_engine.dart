library;

import 'dart:convert';
import 'dart:math' as math;

part 'staruw_scene.dart';

enum WatermarkMode { overlay, frame }

enum BackgroundStyle {
  white,
  black,
  darkBlur,
  lightBlur,
  darkGlass,
  lightGlass,
}

String resolveBrand(String make) {
  final normalized = make.trim().toLowerCase();
  if (['redmi', 'redmi communications co., ltd.'].contains(normalized)) {
    return 'redmi';
  }
  if ([
    'xiaomi',
    'xiaomi communications co., ltd.',
    'xiaomi corporation',
  ].contains(normalized)) {
    return 'xiaomi';
  }
  return 'generic';
}

String resolveModelSvgId(
  PhotoMetadata metadata,
  List<Map<String, dynamic>> catalog,
) {
  final brand = resolveBrand(metadata.make);
  if (brand == 'generic') return '';
  String normalize(String s) =>
      s.trim().toLowerCase().replaceAll(RegExp(r'[\s_-]'), '');
  for (final row in catalog) {
    final id = row['id'] as String;
    if (brand == 'redmi' && !id.startsWith('redmi')) continue;
    if ((row['aliases'] as List).any(
      (e) => normalize(e.toString()) == normalize(metadata.model),
    )) {
      return id;
    }
  }
  return '';
}

class NativeInteractionGate {
  String? _id, _action;
  int? _revision;
  bool begin(String id, String action, int baseRevision, int currentRevision) {
    if (baseRevision != currentRevision || _id != null) return false;
    _id = id;
    _action = action;
    _revision = baseRevision;
    return true;
  }

  bool accepts(
    String id,
    String action,
    int baseRevision,
    int currentRevision,
  ) =>
      _id == id &&
      _action == action &&
      _revision == baseRevision &&
      baseRevision == currentRevision;
  void clear() {
    _id = null;
    _action = null;
    _revision = null;
  }

  void invalidateUnlessRevision(int current) {
    if (_revision != null && _revision != current) clear();
  }
}

double _number(dynamic value, double fallback) =>
    value is num && value.isFinite ? value.toDouble() : fallback;
String _decimal(double value) => value == value.roundToDouble()
    ? value.toInt().toString()
    : value.toStringAsFixed(1);

class PhotoMetadata {
  final String make, model;
  final String dateTime;
  final double? latitude, longitude;
  final double iso, aperture, focal, focal35mm, exposure;
  const PhotoMetadata({
    this.make = '',
    this.model = '',
    this.dateTime = '',
    this.latitude,
    this.longitude,
    this.iso = 0,
    this.aperture = 0,
    this.focal = 0,
    this.focal35mm = 0,
    this.exposure = 0,
  });
  factory PhotoMetadata.fromJson(Map<String, dynamic> json) => PhotoMetadata(
    make: json['make']?.toString() ?? '',
    model: json['model']?.toString() ?? '',
    dateTime: json['dateTime']?.toString() ?? '',
    latitude: (json['latitude'] as num?)?.toDouble(),
    longitude: (json['longitude'] as num?)?.toDouble(),
    iso: _number(json['iso'], 0),
    aperture: _number(json['aperture'], 0),
    focal: _number(json['focal'], 0),
    focal35mm: _number(json['focal35mm'], 0),
    exposure: _number(json['exposure'], 0),
  );
  String get shutter => exposure <= 0
      ? ''
      : exposure >= 1
      ? '${_decimal(exposure)}s'
      : '1/${(1 / exposure).round()}s';
  double get displayFocal => focal35mm > 0 ? focal35mm : focal;
  List<String> get fields => [
    if (displayFocal > 0) '${_decimal(displayFocal)}mm',
    if (aperture > 0) 'f/${_decimal(aperture)}',
    if (exposure > 0) shutter,
    if (iso > 0) 'ISO ${iso.round()}',
  ];
  String get parameters => fields.join('  ');

  /// Whole-second DMS for watermark text; missing or invalid GPS stays hidden.
  String formatCoordinates({String separator = ' '}) {
    final lat = latitude, lng = longitude;
    if (lat == null ||
        lng == null ||
        !lat.isFinite ||
        !lng.isFinite ||
        lat.abs() > 90 ||
        lng.abs() > 180) {
      return '';
    }
    String coordinate(double value, String hemisphere) {
      // Round once before splitting so 60 seconds/minutes carry correctly.
      final seconds = (value.abs() * 3600).round();
      return '${seconds ~/ 3600}°${seconds ~/ 60 % 60}′'
          '${seconds % 60}″$hemisphere';
    }

    // The OEM sm0.k example uses Unicode primes and unpadded components.
    return '${coordinate(lat, lat < 0 ? 'S' : 'N')}$separator'
        '${coordinate(lng, lng < 0 ? 'W' : 'E')}';
  }

  Map<String, dynamic> toJson() => {
    'make': make,
    'model': model,
    'dateTime': dateTime,
    'latitude': latitude,
    'longitude': longitude,
    'iso': iso,
    'aperture': aperture,
    'focal': focal,
    'focal35mm': focal35mm,
    'exposure': exposure,
  };
}

class BundledFontSpec {
  final String id, title, family, asset;
  final int weight;
  final Map<String, double> axes;
  final bool selectable;
  final String? license;
  const BundledFontSpec(
    this.id,
    this.title,
    this.family,
    this.asset, {
    this.weight = 500,
    this.axes = const {},
    this.selectable = true,
    this.license,
  });
}

const bundledFonts = [
  BundledFontSpec(
    'oxanium',
    'Oxanium',
    'Oxanium',
    'assets/fonts/Oxanium.ttf',
    axes: {'wght': 500},
  ),
  BundledFontSpec(
    'lg1056',
    'LG1056 · OTF',
    'LG1056',
    'assets/fonts/LG1056.otf',
    weight: 400,
  ),
  BundledFontSpec(
    'mi-sans',
    'MiSans VF',
    'MiSans',
    'assets/fonts/MiSansVF.ttf',
    axes: {'wght': 380},
  ),
  BundledFontSpec(
    'beihaibei',
    'Beihaibei',
    'Beihaibei',
    'assets/fonts/Beihaibei.ttf',
    weight: 400,
  ),
  // Dedicated model lettering; it must not change the parameter font picker.
  BundledFontSpec(
    'redmi-sans',
    'REDMI Sans Test 0801',
    'RedmiSans',
    'assets/fonts/RedmiSansTest0801.ttf',
    weight: 400,
    selectable: false,
  ),
  // Keep older drafts and styles readable; these are no longer picker choices.
  BundledFontSpec(
    'source-han',
    '思源黑体 · OTF',
    'SourceHanSans',
    'assets/fonts/SourceHanSansCN-Regular.otf',
    selectable: false,
    license: 'assets/fonts/SourceHanSans-LICENSE.txt',
  ),
  BundledFontSpec(
    'noto-sans',
    'Noto Sans · TTF',
    'NotoSans',
    'assets/fonts/NotoSans.ttf',
    selectable: false,
    license: 'assets/fonts/NotoSans-LICENSE.txt',
  ),
];

BundledFontSpec bundledFontSpec(String id) => bundledFonts.firstWhere(
  (font) => font.id == id,
  orElse: () => throw const FormatException('内置字体不存在，请重新选择'),
);

class FontRef {
  final String source, id, fingerprint;
  final int weight;
  final String style;
  final Map<String, double> axes;
  final bool automatic;
  const FontRef({
    this.source = 'bundled',
    this.id = 'oxanium',
    this.fingerprint = '',
    this.weight = 500,
    this.style = 'normal',
    this.axes = const {},
    this.automatic = false,
  });
  const FontRef.photoDefault()
    : this(automatic: true, axes: const {'wght': 500});
  factory FontRef.bundled(String id) {
    final spec = bundledFontSpec(id);
    return FontRef(id: id, weight: spec.weight, axes: spec.axes);
  }

  FontRef resolveFor(PhotoMetadata metadata) {
    if (!automatic) return this;
    final model = metadata.model.trim().toLowerCase().replaceAll(
      RegExp(r'\s+'),
      ' ',
    );
    final id = model == 'xiaomi 17 ultra by leica'
        ? 'lg1056'
        : model.startsWith('redmi') || resolveBrand(metadata.make) == 'redmi'
        ? 'mi-sans'
        : 'oxanium';
    final spec = bundledFontSpec(id);
    return FontRef(
      id: id,
      weight: spec.weight,
      axes: spec.axes,
      automatic: true,
    );
  }

  Map<String, dynamic> toJson() => {
    'source': source,
    'id': id,
    'fingerprint': fingerprint,
    'weight': weight,
    'style': style,
    'axes': axes,
    'automatic': automatic,
  };
  factory FontRef.fromJson(Map<String, dynamic> j) {
    final source = j['source']?.toString() ?? 'bundled';
    if (!['bundled', 'system', 'imported'].contains(source)) {
      throw const FormatException('未知字体来源');
    }
    final weight = (j['weight'] as num?)?.toInt() ?? 500;
    final style = j['style']?.toString() ?? 'normal';
    final axes = Map<String, dynamic>.from(
      j['axes'] as Map? ?? {},
    ).map((key, value) => MapEntry(key, (value as num).toDouble()));
    final automatic = j['automatic'] == true;
    if ((automatic && source != 'bundled') ||
        weight < 1 ||
        weight > 1000 ||
        !['normal', 'italic'].contains(style) ||
        axes.entries.any((e) => e.key.length != 4 || !e.value.isFinite)) {
      throw const FormatException('无效字体变体');
    }
    return FontRef(
      source: source,
      id: j['id']?.toString() ?? 'source-han',
      fingerprint: j['fingerprint']?.toString() ?? '',
      weight: weight,
      style: style,
      axes: Map.unmodifiable(axes),
      automatic: automatic,
    );
  }
}

class BorderSpec {
  final double top, bottom, left, right;
  const BorderSpec({
    this.top = 0,
    this.bottom = .12,
    this.left = 0,
    this.right = 0,
  });
  Map<String, dynamic> toJson() => {
    'top': top,
    'bottom': bottom,
    'left': left,
    'right': right,
  };
  factory BorderSpec.fromJson(Map<String, dynamic> j) {
    final b = BorderSpec(
      top: _number(j['top'], 0),
      bottom: _number(j['bottom'], .12),
      left: _number(j['left'], 0),
      right: _number(j['right'], 0),
    );
    if ([b.top, b.bottom, b.left, b.right].any((v) => v < 0 || v > .3)) {
      throw const FormatException('边框范围为 0–30%');
    }
    return b;
  }
  BorderSpec copyWith({
    double? top,
    double? bottom,
    double? left,
    double? right,
  }) => BorderSpec(
    top: top ?? this.top,
    bottom: bottom ?? this.bottom,
    left: left ?? this.left,
    right: right ?? this.right,
  );
}

class CanvasLayout {
  final int width, height, photoX, photoY, photoWidth, photoHeight;
  const CanvasLayout(
    this.width,
    this.height,
    this.photoX,
    this.photoY,
    this.photoWidth,
    this.photoHeight,
  );
  bool get withinBudget =>
      width > 0 &&
      height > 0 &&
      width <= 32768 &&
      height <= 32768 &&
      width * height <= 520000000 &&
      photoWidth > 0 &&
      photoHeight > 0 &&
      photoWidth <= 32768 &&
      photoHeight <= 32768 &&
      photoWidth * photoHeight <= 210000000;
}

class EditDocument {
  /// Frozen package and scoped values. Local state is never copied wholesale
  /// into a shareable StarUW package.
  final Map<String, dynamic>? staruwConfig;
  final Map<String, dynamic> staruwValues, staruwPhotoValues, staruwReport;
  bool get isStaruw => staruwConfig != null;
  bool get isStaruwScene =>
      staruwConfig?['staruw']?['render']?['engine'] == 'scene';
  bool get staruwPreserved =>
      isStaruw && staruwReport['editMode'] == 'preserve';
  final Map<String, dynamic>? xiaomiConfig;
  final Map<String, dynamic> xiaomiOptions,
      resourceBindings,
      regionReplacements;
  final String packageId;
  final WatermarkMode mode;
  final BackgroundStyle background;
  final BorderSpec border;
  final double x, y, scale, opacity, blur, tint;
  final FontRef font;
  final String modelId, caption, parametersOverride, modelOverride;
  final bool enabled, showParameters, showModel, showBrand;
  final int textColor;
  const EditDocument({
    this.staruwConfig,
    this.staruwValues = const {},
    this.staruwPhotoValues = const {},
    this.staruwReport = const {},
    this.xiaomiConfig,
    this.xiaomiOptions = const {},
    this.resourceBindings = const {},
    this.regionReplacements = const {},
    this.packageId = '',
    this.mode = WatermarkMode.overlay,
    this.background = BackgroundStyle.white,
    this.border = const BorderSpec(),
    this.x = .5,
    this.y = .94,
    this.scale = 1,
    this.opacity = 1,
    this.blur = .035,
    this.tint = .45,
    this.font = const FontRef.photoDefault(),
    this.modelId = '',
    this.caption = '',
    this.parametersOverride = '',
    this.modelOverride = '',
    this.enabled = true,
    this.showParameters = true,
    this.showModel = true,
    this.showBrand = true,
    this.textColor = 0xFFFFFFFF,
  });
  CanvasLayout layout(int w, int h) {
    if (!enabled) return CanvasLayout(w, h, 0, 0, w, h);
    final short = math.min(w, h);
    final l = mode == WatermarkMode.frame ? (border.left * short).round() : 0;
    final t = mode == WatermarkMode.frame ? (border.top * short).round() : 0;
    final r = mode == WatermarkMode.frame ? (border.right * short).round() : 0;
    final b = mode == WatermarkMode.frame ? (border.bottom * short).round() : 0;
    return CanvasLayout(w + l + r, h + t + b, l, t, w, h);
  }

  EditDocument copyWith({
    Map<String, dynamic>? staruwConfig,
    Map<String, dynamic>? staruwValues,
    Map<String, dynamic>? staruwPhotoValues,
    Map<String, dynamic>? staruwReport,
    Map<String, dynamic>? xiaomiConfig,
    Map<String, dynamic>? xiaomiOptions,
    Map<String, dynamic>? resourceBindings,
    Map<String, dynamic>? regionReplacements,
    String? packageId,
    WatermarkMode? mode,
    BackgroundStyle? background,
    BorderSpec? border,
    double? x,
    double? y,
    double? scale,
    double? opacity,
    double? blur,
    double? tint,
    FontRef? font,
    String? modelId,
    String? caption,
    String? parametersOverride,
    String? modelOverride,
    bool? enabled,
    bool? showParameters,
    bool? showModel,
    bool? showBrand,
    int? textColor,
  }) => EditDocument(
    staruwConfig: staruwConfig ?? this.staruwConfig,
    staruwValues: staruwValues ?? this.staruwValues,
    staruwPhotoValues: staruwPhotoValues ?? this.staruwPhotoValues,
    staruwReport: staruwReport ?? this.staruwReport,
    xiaomiConfig: xiaomiConfig ?? this.xiaomiConfig,
    xiaomiOptions: xiaomiOptions ?? this.xiaomiOptions,
    resourceBindings: resourceBindings ?? this.resourceBindings,
    regionReplacements: regionReplacements ?? this.regionReplacements,
    packageId: packageId ?? this.packageId,
    mode: mode ?? this.mode,
    background: background ?? this.background,
    border: border ?? this.border,
    x: x ?? this.x,
    y: y ?? this.y,
    scale: scale ?? this.scale,
    opacity: opacity ?? this.opacity,
    blur: blur ?? this.blur,
    tint: tint ?? this.tint,
    font: font ?? this.font,
    modelId: modelId ?? this.modelId,
    caption: caption ?? this.caption,
    parametersOverride: parametersOverride ?? this.parametersOverride,
    modelOverride: modelOverride ?? this.modelOverride,
    enabled: enabled ?? this.enabled,
    showParameters: showParameters ?? this.showParameters,
    showModel: showModel ?? this.showModel,
    showBrand: showBrand ?? this.showBrand,
    textColor: textColor ?? this.textColor,
  );
  Map<String, dynamic> toJson() => {
    'schemaVersion': 1,
    if (staruwConfig != null) 'staruwConfig': staruwConfig,
    if (staruwConfig != null) 'staruwValues': staruwValues,
    if (staruwConfig != null) 'staruwPhotoValues': staruwPhotoValues,
    if (staruwConfig != null) 'staruwReport': staruwReport,
    if (xiaomiConfig != null) 'xiaomiConfig': xiaomiConfig,
    'xiaomiOptions': xiaomiOptions,
    'resourceBindings': resourceBindings,
    'regionReplacements': regionReplacements,
    'packageId': packageId,
    'mode': mode.name,
    'background': background.name,
    'border': border.toJson(),
    'x': x,
    'y': y,
    'scale': scale,
    'opacity': opacity,
    'blur': blur,
    'tint': tint,
    'font': font.toJson(),
    'modelId': modelId,
    'caption': caption,
    'parametersOverride': parametersOverride,
    'modelOverride': modelOverride,
    'enabled': enabled,
    'showParameters': showParameters,
    'showModel': showModel,
    'showBrand': showBrand,
    'textColor': textColor,
  };
  factory EditDocument.fromJson(Map<String, dynamic> j) {
    if (j['schemaVersion'] != 1) throw const FormatException('不支持此样式版本');
    if (j.containsKey('enabled') && j['enabled'] is! bool) {
      throw const FormatException('无效照片水印开关');
    }
    final packageId = j['packageId']?.toString() ?? '';
    if (packageId.isNotEmpty &&
        !RegExp(r'^[a-zA-Z0-9_-]{1,100}$').hasMatch(packageId)) {
      throw const FormatException('无效水印资源包 ID');
    }
    final scale = _number(j['scale'], 1),
        x = _number(j['x'], .5),
        y = _number(j['y'], .94);
    if (scale < .3 || scale > 2.5 || x < 0 || x > 1 || y < 0 || y > 1) {
      throw const FormatException('无效水印尺寸或位置');
    }
    final opacity = _number(j['opacity'], 1),
        blur = _number(j['blur'], .035),
        tint = _number(j['tint'], .45);
    if (opacity < 0 ||
        opacity > 1 ||
        blur < 0 ||
        blur > .12 ||
        tint < 0 ||
        tint > 1) {
      throw const FormatException('无效效果参数');
    }
    final mode = j['mode']?.toString() ?? 'overlay',
        bg = j['background']?.toString() ?? 'white';
    if (!WatermarkMode.values.any((e) => e.name == mode) ||
        !BackgroundStyle.values.any((e) => e.name == bg)) {
      throw const FormatException('未知渲染模式');
    }
    return EditDocument(
      staruwConfig: j['staruwConfig'] == null
          ? null
          : Map<String, dynamic>.unmodifiable(j['staruwConfig'] as Map),
      staruwValues: Map<String, dynamic>.unmodifiable(
        j['staruwValues'] as Map? ?? {},
      ),
      staruwPhotoValues: Map<String, dynamic>.unmodifiable(
        j['staruwPhotoValues'] as Map? ?? {},
      ),
      staruwReport: Map<String, dynamic>.unmodifiable(
        j['staruwReport'] as Map? ?? {},
      ),
      xiaomiConfig: j['xiaomiConfig'] == null
          ? null
          : Map<String, dynamic>.unmodifiable(j['xiaomiConfig'] as Map),
      xiaomiOptions: Map<String, dynamic>.unmodifiable(
        j['xiaomiOptions'] as Map? ?? {},
      ),
      resourceBindings: Map<String, dynamic>.unmodifiable(
        j['resourceBindings'] as Map? ?? {},
      ),
      regionReplacements: validateRegionReplacements(j['regionReplacements']),
      packageId: packageId,
      mode: WatermarkMode.values.byName(mode),
      background: BackgroundStyle.values.byName(bg),
      border: BorderSpec.fromJson(
        Map<String, dynamic>.from(j['border'] as Map? ?? {}),
      ),
      x: x,
      y: y,
      scale: scale,
      opacity: opacity,
      blur: blur,
      tint: tint,
      font: FontRef.fromJson(
        Map<String, dynamic>.from(j['font'] as Map? ?? {}),
      ),
      modelId: j['modelId']?.toString() ?? '',
      caption: j['caption']?.toString() ?? '',
      parametersOverride: j['parametersOverride']?.toString() ?? '',
      modelOverride: j['modelOverride']?.toString() ?? '',
      enabled: j['enabled'] != false,
      showParameters: j['showParameters'] != false,
      showModel: j['showModel'] != false,
      showBrand: j['showBrand'] != false,
      textColor: (j['textColor'] as num?)?.toInt() ?? 0xFFFFFFFF,
    );
  }
}

class HistoryEntry {
  final EditDocument document;
  final String label;
  const HistoryEntry(this.document, this.label);
  Map<String, dynamic> toJson() => {
    'document': document.toJson(),
    'label': label,
  };
}

class EditHistory {
  List<HistoryEntry> _entries;
  int _cursor = 0;
  EditDocument? _preview, _origin;
  EditHistory(EditDocument initial)
    : _entries = [HistoryEntry(initial, '导入照片')];
  EditDocument get current => _preview ?? _entries[_cursor].document;
  List<HistoryEntry> get entries => List.unmodifiable(_entries);
  int get cursor => _cursor;
  bool get canUndo => _cursor > 0;
  bool get canRedo => _cursor < _entries.length - 1;
  bool get interacting => _origin != null;
  void apply(EditDocument next, String label) {
    _preview = null;
    _origin = null;
    if (jsonEncode(next.toJson()) ==
        jsonEncode(_entries[_cursor].document.toJson())) {
      return;
    }
    _entries = _entries.take(_cursor + 1).toList()
      ..add(HistoryEntry(next, label));
    if (_entries.length > 101) _entries.removeAt(0);
    _cursor = _entries.length - 1;
  }

  void beginInteraction() {
    _origin ??= current;
  }

  void preview(EditDocument next) {
    beginInteraction();
    _preview = next;
  }

  void commitInteraction(String label) {
    final next = current;
    _preview = null;
    _origin = null;
    apply(next, label);
  }

  void cancelInteraction() {
    _preview = null;
    _origin = null;
  }

  void undo() {
    cancelInteraction();
    if (canUndo) _cursor--;
  }

  void redo() {
    cancelInteraction();
    if (canRedo) _cursor++;
  }

  void jump(int index) {
    cancelInteraction();
    _cursor = index.clamp(0, _entries.length - 1);
  }

  Map<String, dynamic> toJson() => {
    'entries': _entries.map((e) => e.toJson()).toList(),
    'cursor': _cursor,
  };
  factory EditHistory.fromJson(Map<String, dynamic> j) {
    final rows = j['entries'];
    if (rows is! List || rows.isEmpty || rows.length > 101) {
      throw const FormatException('无效历史');
    }
    final entries = rows.map((e) {
      final row = Map<String, dynamic>.from(e as Map);
      return HistoryEntry(
        EditDocument.fromJson(
          Map<String, dynamic>.from(row['document'] as Map),
        ),
        row['label']?.toString() ?? '编辑',
      );
    }).toList();
    return EditHistory(entries.first.document)
      .._entries = entries
      .._cursor = (j['cursor'] as num? ?? 0).toInt().clamp(
        0,
        entries.length - 1,
      );
  }
}

/// Explicit user design, never source EXIF. Assets are content identifiers only.
Map<String, dynamic> validateRegionReplacements(dynamic value) {
  if (value == null) return const {};
  if (value is! Map ||
      value.keys.any((k) => k != 'model' && k != 'parameters')) {
    throw const FormatException('Invalid replacement region');
  }
  final result = <String, dynamic>{};
  for (final entry in value.entries) {
    final row = entry.value;
    if (row is! Map ||
        row.keys.any(
          (k) => !['source', 'text', 'asset', 'shape'].contains(k),
        )) {
      throw const FormatException('Invalid region design');
    }
    final source = row['source'] ?? 'original';
    if (!(entry.key == 'model'
                ? ['original', 'text', 'image']
                : ['original', 'image'])
            .contains(source) ||
        (row['text'] != null &&
            (row['text'] is! String ||
                (row['text'] as String).length > 2000)) ||
        (row['shape'] != null &&
            !['square', 'circle'].contains(row['shape'])) ||
        (row['asset'] != null &&
            (row['asset'] is! String ||
                !RegExp(
                  r'^[a-f0-9]{64}\.(png|jpg|svg)$',
                ).hasMatch(row['asset']))) ||
        (source == 'image' && row['asset'] == null)) {
      throw const FormatException('Invalid region content');
    }
    result[entry.key.toString()] = Map<String, dynamic>.unmodifiable(
      Map<String, dynamic>.from(row),
    );
  }
  return Map.unmodifiable(result);
}
