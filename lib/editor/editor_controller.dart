import 'dart:async';
import 'dart:math' as math;
import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;
import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:vector_graphics/vector_graphics.dart' as vg;
import 'package:watermark_engine/watermark_engine.dart';
import '../services/native_bridge.dart';
import '../services/template_preferences.dart';
import '../services/reversible_edit.dart';
import '../services/imported_fonts.dart';
import '../services/region_assets.dart';
import '../services/builtin_emblems.dart';
import '../services/parameter_icon_library.dart';
import 'global_parameter_icon.dart';
import 'region_replacements.dart';
import '../services/photo_asset.dart';
import '../services/draft_store.dart';
import '../services/preset_models.dart';
import '../services/watermark_style.dart';
import '../services/watermark_package.dart';
import '../services/watermark_package_builder.dart';
import '../services/watermark_repository.dart';
import '../services/factory_watermarks.dart';
import 'model_lockup.dart';
import 'xiaomi_renderer.dart';
import 'font_metrics.dart';
import 'high_resolution_export.dart';
import 'xiaomi_capabilities.dart';
import 'watermark_renderer.dart';
import 'staruw_renderer.dart';
import 'staruw_native_adapter.dart';
import '../services/staruw/contract.dart';
import '../services/staruw/controls.dart';
import '../services/staruw/share_image.dart';
import '../services/staruw/export.dart';
import '../services/staruw/font_file.dart';
import '../services/staruw/package_validation.dart';
import '../services/parameter_icon_removal.dart';
export '../services/watermark_style.dart';

class EditorController extends ChangeNotifier {
  final bool testMode;
  final bool writesDrafts;
  final bool remembersDesign;
  TemplatePreferences? _preferences;
  TemplatePreferences? get templatePreferences => directory == null
      ? null
      : (_preferences ??= TemplatePreferences(directory!));
  EditorController({
    this.testMode = false,
    this.writesDrafts = true,
    this.remembersDesign = true,
  });
  void rememberDesign() {
    if (!remembersDesign) return;
    unawaited(
      templatePreferences?.save(document.packageId, document).catchError((
        Object e,
      ) {
        inform('偏好保存失败：$e');
      }),
    );
  }

  bool staticPhotoExport = false;
  bool autoRemoveWatermark = false;
  ParameterIconLibrary? _parameterIconLibrary;
  ParameterIconLibrary get parameterIconLibrary =>
      _parameterIconLibrary ??= ParameterIconLibrary(directory!);
  GlobalParameterIcon? _globalParameterIcon;
  int _iconRemovalRevision = -1;
  Expando<EditDocument> _iconFallbacks = Expando<EditDocument>();
  bool get usesGlobalParameterIcon => _globalParameterIcon != null;
  EditDocument _withoutDeletedIcons(EditDocument value) {
    final removed = _parameterIconLibrary?.removed ?? const <String>{};
    if (removed.isEmpty) return value;
    if (_iconRemovalRevision != _parameterIconLibrary!.removalRevision) {
      _iconRemovalRevision = _parameterIconLibrary!.removalRevision;
      _iconFallbacks = Expando<EditDocument>();
    }
    return _iconFallbacks[value] ??= EditDocument.fromJson(
      withoutParameterIcons(value.toJson(), removed),
    );
  }

  EditDocument effectiveDocument([EditDocument? value]) {
    final local = _withoutDeletedIcons(value ?? document);
    return _globalParameterIcon?.apply(local) ?? local;
  }

  Future<void> setGlobalParameterIcon({
    required bool enabled,
    required String asset,
    required String shape,
  }) async {
    if (enabled) {
      await parameterIconLibrary.initialize();
      if (parameterIconLibrary.removed.contains(asset)) {
        asset = builtinEmblems.first['asset']!;
      }
      await parameterIconLibrary.ensureAsset(asset);
    }
    if (_globalParameterIcon?.asset == asset &&
            _globalParameterIcon?.shape == shape &&
            enabled ||
        !enabled && _globalParameterIcon == null) {
      return;
    }
    _globalParameterIcon = enabled ? GlobalParameterIcon(asset, shape) : null;
    if (preview != null) await prepareResources();
    if (!_disposed) notifyListeners();
  }

  PhotoAsset asset = PhotoAsset.demo;
  EditHistory history = EditHistory(const EditDocument());
  EditDocument get document => history.current;
  FontRef get effectiveFont => document.font.resolveFor(asset.metadata);
  ui.Rect? get watermarkBounds {
    final resources = renderAssets;
    if (resources == null || !document.enabled) return null;
    return WatermarkPainter(
      renderDocument,
      renderAsset,
      resources,
    ).measureDecorationBounds();
  }

  Map<String, String> get alignmentState {
    final bounds = watermarkBounds;
    if (bounds == null) return {};
    final area = layout;
    final margin = math.min(area.width, area.height) * .025;
    String value(double start, double end, double extent) {
      if ((start - margin).abs() < 2) return 'start';
      if ((end - (extent - margin)).abs() < 2) return 'end';
      if (((start + end) / 2 - extent / 2).abs() < 2) return 'center';
      return '';
    }

    return {
      'alignmentHorizontal': value(
        bounds.left,
        bounds.right,
        area.width.toDouble(),
      ),
      'alignmentVertical': value(
        bounds.top,
        bounds.bottom,
        area.height.toDouble(),
      ),
    };
  }

  void alignWatermark(String axis, String value) {
    if (!['vertical', 'horizontal'].contains(axis) ||
        !['start', 'center', 'end'].contains(value)) {
      throw const FormatException('无效对齐方式');
    }
    cancel();
    final bounds = watermarkBounds;
    if (bounds == null) throw const FormatException('请先开启照片水印');
    final area = layout;
    final horizontal = axis == 'horizontal';
    final extent = (horizontal ? area.width : area.height).toDouble();
    final size = horizontal ? bounds.width : bounds.height;
    final margin = math.min(area.width, area.height) * .025;
    final target = switch (value) {
      'start' => margin,
      'center' => (extent - size) / 2,
      _ => extent - margin - size,
    };
    final delta = (target - (horizontal ? bounds.left : bounds.top)) / extent;
    apply(
      document.copyWith(
        x: horizontal ? (document.x + delta).clamp(0.0, 1.0) : document.x,
        y: horizontal ? document.y : (document.y + delta).clamp(0.0, 1.0),
      ),
      '水印对齐',
    );
  }

  ui.Image? preview, background;
  vg.PictureInfo? model, brand;
  String family = 'Oxanium';
  Directory? directory;
  String? message;
  String saveStatus = '';
  bool busy = false, loading = true, dark = false;
  String? exportStage;
  bool exportHighResolution = false;

  void _exportProgress(String stage) {
    exportStage = stage;
    notifyListeners();
  }

  int revision = 0;
  Timer? _saveTimer;
  final Set<String> _deletedDraftIds = {};
  DraftStore? _draftStore;
  final _activeDraftResources = <Map<String, dynamic>>{};
  Iterable<Object?> Function()? liveDraftReferences;
  DraftStore get _store => _draftStore ??= DraftStore(
    directory!,
    retainedReferences: () => [
      asset.toJson(),
      history.toJson(),
      ..._activeDraftResources,
      for (final style in userStyles) style.toJson(),
      ...?liveDraftReferences?.call(),
    ],
  );
  bool get sampleAvailable => _draftStore?.sampleAvailable ?? true;
  bool get hasPhoto => !asset.sample || sampleAvailable;
  int _resourcesToken = 0;
  int _assetToken = 0;
  int _fontIntent = 0;
  bool _disposed = false;
  final Map<String, Future<vg.PictureInfo?>> _pictureJobs = {};
  String _backgroundKey = '';
  bool _backgroundToned = false;
  bool useXiaomiImageBlur = true;
  bool useXiaomiGlassWatermark = false;
  int nativeMaterialRevision = 0;
  final Map<String, vg.PictureInfo> _models = {};
  final Map<String, String> _fontFamilies = {};
  final Map<String, double> _fontCapHeights = {};
  final Map<String, Future<_PackageResources>> _packageJobs = {};
  final Map<String, _PackageResources> _packages = {};
  final Map<String, _PackageResources> _staruwVariants = {};
  final Map<String, Future<Map<String, String>>> _lunarDateJobs = {};
  final Map<String, Map<String, String>> _lunarDates = {};
  XiaomiRenderPlan? _plan;
  StaruwRenderPlan? _scenePlan;
  StaruwRenderResources? _nativeStaruwResources;
  FactoryWatermarkCatalog? _factoryCatalog;
  ModelLockupAssets? _markAssets;
  EditDocument? _planDocument;
  ui.Image? _planImage, _planBackground;
  String _planFamily = '';
  EditDocument? _renderedDocument;
  PhotoAsset? _renderedAsset;
  String? _preparedResourceKey;
  EditDocument? _keyDocument;
  PhotoAsset? _keyPhoto;
  String _key = '';
  String _resourceKey(EditDocument doc, PhotoAsset photo) {
    doc = effectiveDocument(doc);
    if (identical(doc, _keyDocument) && identical(photo, _keyPhoto)) {
      return _key;
    }
    _keyDocument = doc;
    _keyPhoto = photo;
    return _key = jsonEncode({
      'photo': photo.toJson(),
      'packageId': doc.packageId,
      if (!doc.isStaruw) ...{
        'font': doc.font.toJson(),
        'bindings': doc.resourceBindings,
        'regions': doc.regionReplacements,
        'modelId': doc.modelId,
      } else ...{
        'addedImages': doc.resourceBindings['staruwAssets'],
        'addedFonts': doc.resourceBindings['staruwFonts'],
      },
      'enabled': doc.enabled,
      'mode': doc.mode.name,
      'background': doc.background.name,
      'blur': doc.blur,
      if (!doc.isStaruw) 'config': doc.xiaomiConfig,
      if (!doc.isStaruw) 'options': doc.xiaomiOptions,
      if (doc.isStaruw) 'staruw': doc.staruwConfig,
      if (doc.isStaruw) 'staruwValues': doc.staruwValues,
      if (doc.isStaruw) 'staruwPhotoValues': doc.staruwPhotoValues,
    });
  }

  bool get _usingPreparedResources =>
      _renderedDocument != null &&
      _preparedResourceKey != _resourceKey(document, asset);
  EditDocument get renderDocument {
    if (_usingPreparedResources) return _renderedDocument!;
    // Position, scale and text edits can use the prepared resources immediately.
    // Retain that latest visible document if the next template needs more work.
    final effective = StaruwNativeAdapter.project(effectiveDocument());
    if (_preparedResourceKey != null) _renderedDocument = effective;
    return effective;
  }

  PhotoAsset get renderAsset =>
      _usingPreparedResources ? _renderedAsset! : asset;
  CanvasLayout get renderLayout =>
      (renderDocument.enabled && renderDocument.isStaruwScene
          ? _scenePlan?.layout
          : null) ??
      nativePlan?.layout ??
      renderDocument.layout(renderAsset.width, renderAsset.height);
  WatermarkRepository get watermarkRepository =>
      WatermarkRepository(directory!);
  CanvasLayout get layout => renderLayout;
  XiaomiRenderPlan? get nativePlan {
    final doc = renderDocument, photo = renderAsset;
    if (!doc.enabled || doc.xiaomiConfig == null || preview == null) {
      return null;
    }
    final resources = _packages[doc.packageId];
    if (resources == null) return null;
    if (!identical(doc, _planDocument) ||
        !identical(preview, _planImage) ||
        !identical(background, _planBackground) ||
        family != _planFamily) {
      _plan = _makeNativePlan(
        doc,
        photo,
        preview!,
        resources,
        family,
        background,
        backgroundToned: _backgroundToned,
      );
      _planDocument = doc;
      _planImage = preview;
      _planBackground = background;
      _planFamily = family;
    }
    return _plan;
  }

  XiaomiRenderPlan _makeNativePlan(
    EditDocument doc,
    PhotoAsset photo,
    ui.Image image,
    _PackageResources resources,
    String family,
    ui.Image? blur, {
    bool backgroundToned = false,
  }) {
    doc = _currentFactoryMarks(doc);
    doc = StaruwNativeAdapter.project(doc);
    photo = StaruwNativeAdapter.photo(doc, photo);
    if (!doc.isStaruw) {
      doc = XiaomiCapabilities.fromConfig(
        doc.xiaomiConfig!,
        doc.xiaomiOptions,
        [...resources.images.keys, ...resources.vectors.keys],
      ).normalizeDocument(doc);
    }
    if (doc.modelOverride.isEmpty && doc.modelId.isNotEmpty) {
      final selected = modelCatalog
          .where((item) => item['id'] == doc.modelId)
          .firstOrNull;
      if (selected != null) {
        doc = doc.copyWith(modelOverride: selected['title']?.toString() ?? '');
      }
    }
    return XiaomiRenderPlan.build(
      config: doc.xiaomiConfig!,
      document: doc,
      photo: photo,
      image: image,
      resources: XiaomiRenderResources(
        regionAssets: _regionArtwork,
        modelLockup: _modelLockup(doc, photo, resources),
        imageContentBounds: resources.imageContentBounds,
        images: resources.images,
        vectors: resources.vectors,
        fonts: {
          ...resources.fonts,
          for (final entry
              in (doc.resourceBindings['fonts'] as Map? ?? {}).entries)
            if (resources.fonts[entry.value] != null)
              entry.key.toString(): resources.fonts[entry.value]!,
        },
        defaultFamily: family,
        fontCapHeights: _fontCapHeights,
        background: blur,
        backgroundToned: backgroundToned,
        localizations: {
          ...resources.localizations,
          ..._lunarLocalizations(doc, photo),
        },
      ),
    );
  }

  String watermarkLabel(String key, String fallback) {
    final translations = _packages[document.packageId]?.localizations;
    return translations?[key.replaceFirst(RegExp(r'^@'), '')] ?? fallback;
  }

  Iterable<String> get watermarkImageNames => [
    ...?_packages[document.packageId]?.images.keys,
    ...?_packages[document.packageId]?.vectors.keys,
  ];

  String staruwFontLabel(String id) {
    final r =
        _scenePlan?.resources ??
        _nativeStaruwResources ??
        _packages[document.packageId]?.scene;
    final rid = r?.fonts[id]?['resourceId'];
    return r?.fontFiles[rid]?.displayName ?? '模板字体';
  }

  /// Expose only images already decoded from the current validated package.
  /// Snapshot generation stays synchronous and performs no filesystem reads.
  String? watermarkResourcePath(String reference) {
    final name = reference.startsWith('@img=')
        ? reference.substring(5)
        : reference;
    final id = document.packageId;
    if (!safeWatermarkPath(name) ||
        !(_packages[id]?.images.containsKey(name) ?? false)) {
      return null;
    }
    if (FactoryWatermarkCatalog.isFactoryId(id)) {
      return _factoryCatalog?.resourceAsset(id, name);
    }
    return directory == null
        ? null
        : watermarkRepository.resource(id, name).path;
  }

  Future<void> validateTextEdits(EditDocument candidate) async {
    if (document.xiaomiConfig == null) return;
    final capabilities = XiaomiCapabilities.fromConfig(
      document.xiaomiConfig!,
      candidate.xiaomiOptions,
    );
    if ((!capabilities.limitsCaptionWidth ||
            candidate.caption == document.caption) &&
        jsonEncode(candidate.xiaomiOptions['mix']) ==
            jsonEncode(document.xiaomiOptions['mix'])) {
      return;
    }
    final before = revision;
    await prepareResources();
    if (_disposed || revision != before) {
      throw const FormatException('水印已变更，请重新应用文案');
    }
    final resources = _packages[document.packageId], image = preview;
    if (resources == null || image == null) {
      throw const FormatException('请等待水印字体加载完成');
    }
    _makeNativePlan(
      document,
      asset,
      image,
      resources,
      family,
      background,
    ).validateEditableText(candidate);
  }

  Set<String> _lunarPatterns(EditDocument doc) {
    final patterns = <String>{};
    void visit(dynamic value) {
      if (value is Map) {
        final text = value['text'];
        if (text is String && text.startsWith('@time=@lunar(')) {
          patterns.add(text.substring(6));
        }
        visit(value['childViews']);
      } else if (value is List) {
        for (final child in value) {
          visit(child);
        }
      }
    }

    if (doc.xiaomiConfig != null) {
      visit(
        XiaomiRenderPlan.selectedLayouts(doc.xiaomiConfig!, doc.xiaomiOptions),
      );
    }
    return patterns;
  }

  String? _civilDate(PhotoAsset photo) => RegExp(
    r'^\d{4}[:\-]\d{2}[:\-]\d{2}',
  ).firstMatch(photo.metadata.dateTime)?.group(0)?.replaceAll(':', '-');

  Future<void> _prepareLunarBindings(EditDocument doc, PhotoAsset photo) async {
    if (_lunarPatterns(doc).isEmpty) return;
    final date = _civilDate(photo);
    if (date == null) return;
    final job = _lunarDateJobs.putIfAbsent(date, () async {
      final parts = date.split('-').map(int.parse).toList();
      final result = await NativeBridge.call<Map>('date.lunar', {
        'year': parts[0],
        'month': parts[1],
        'day': parts[2],
      });
      if (result == null) throw const FormatException('无法计算照片的农历日期');
      return result.map((key, value) => MapEntry('$key', '$value'));
    });
    try {
      _lunarDates[date] = await job;
    } catch (_) {
      if (identical(_lunarDateJobs[date], job)) _lunarDateJobs.remove(date);
      rethrow;
    }
  }

  Map<String, String> _lunarLocalizations(EditDocument doc, PhotoAsset photo) {
    final values = _lunarDates[_civilDate(photo)];
    if (values == null) return const {};
    return {
      for (final pattern in _lunarPatterns(doc))
        pattern: pattern
            .substring(7, pattern.length - 1)
            .replaceAllMapped(
              RegExp(r'\$\{([^}]+)\}'),
              (match) =>
                  values[match[1]] ??
                  (throw FormatException('不支持的农历字段：${match[1]}')),
            ),
    };
  }

  List<Map<String, dynamic>> modelCatalog = [], systemFonts = [], drafts = [];
  List<Map<String, dynamic>> importedFonts = [];
  List<StylePreset> userStyles = [];
  List<StylePreset> presets = [];
  List<StylePreset> get allStyles => [...presets, ...userStyles];
  RenderAssets? get renderAssets =>
      preview == null ||
          (renderDocument.enabled &&
              renderDocument.isStaruwScene &&
              _scenePlan == null) ||
          (renderDocument.enabled &&
              renderDocument.xiaomiConfig != null &&
              nativePlan == null)
      ? null
      : RenderAssets(
          preview!,
          background: background,
          backgroundToned: _backgroundToned,
          regionAssets: _regionArtwork,
          model: model,
          brand: brand,
          family: family,
          fallbackFamily: _fallbackFamily(renderDocument),
          xiaomi: nativePlan,
          scene: renderDocument.isStaruwScene ? _scenePlan : null,
          modelLockup: _modelLockup(
            renderDocument,
            renderAsset,
            _packages[renderDocument.packageId],
          ),
        );

  ModelLockup? _modelLockup(
    EditDocument doc,
    PhotoAsset photo,
    _PackageResources? package,
  ) {
    if (doc.isStaruw && package?.scene?.catalogs.isNotEmpty == true) {
      return package!.scene!.nativeModel(
        StaruwNativeAdapter.photo(doc, photo).metadata,
      );
    }
    final modelId = doc.modelId.isEmpty
        ? resolveModelSvgId(photo.metadata, modelCatalog)
        : doc.modelId;
    if (doc.modelOverride.isEmpty &&
        (doc.resourceBindings['models'] as Map? ?? {}).containsKey(modelId)) {
      return null;
    }
    final selected = modelCatalog
        .where((row) => row['id'] == doc.modelId)
        .firstOrNull;
    return (package?.marks ?? _markAssets)?.compose(
      photo.metadata,
      modelOverride: doc.modelOverride.isNotEmpty
          ? doc.modelOverride
          : selected?['title']?.toString() ?? '',
      includeBrand: doc.showBrand,
      redmiFamily:
          package?.fonts[(doc.resourceBindings['fonts']
              as Map?)?['redmi-sans']] ??
          'RedmiSans',
      xiaomiFamily: _fallbackFamily(doc),
    );
  }

  void inform(String value) {
    if (_disposed) return;
    message = value;
    notifyListeners();
  }

  String _fallbackFamily(EditDocument doc) {
    final path = (doc.resourceBindings['fonts'] as Map? ?? {})['mi-sans'];
    return _packages[doc.packageId]?.fonts[path] ?? 'MiSans';
  }

  /// A template editor needs its own history/decoded photo, but not another
  /// catalog scan, draft scan or preparation of the unrelated default style.
  Future<void> initializeTemplateFrom(
    EditorController source,
    EditDocument design,
  ) async {
    if (writesDrafts || remembersDesign || source._disposed || source.loading) {
      throw StateError('模板编辑器必须使用已就绪的独立会话');
    }
    _factoryCatalog = source._factoryCatalog;
    presets = List.of(source.presets);
    modelCatalog = List.of(source.modelCatalog);
    userStyles = List.of(source.userStyles);
    importedFonts = List.of(source.importedFonts);
    systemFonts = List.of(source.systemFonts);
    directory = source.directory;
    _parameterIconLibrary = source._parameterIconLibrary;
    _globalParameterIcon = source._globalParameterIcon;
    _markAssets = source._markAssets?.retain();
    _fontFamilies.addAll(source._fontFamilies);
    _fontCapHeights.addAll(source._fontCapHeights);
    await setAsset(PhotoAsset.demo, restored: EditHistory(design), save: false);
  }

  Future<void> initialize() async {
    final startup = Stopwatch()..start();
    void stage(String name) {
      if (kDebugMode) {
        debugPrint('StarMarkInit $name: ${startup.elapsedMilliseconds}ms');
      }
    }

    stage('begin');
    try {
      _factoryCatalog = await FactoryWatermarkCatalog.load();
      presets = await _factoryCatalog!.loadStyles();
      stage('factory');
      _markAssets = await ModelLockupAssets.load();
      stage('marks');
      history = EditHistory(
        presets.firstWhere((s) => s.id == 'factory-standard-8').document,
      );
      modelCatalog = await loadPresetModels();
      if (!testMode) {
        directory = Directory((await NativeBridge.call<String>('directory'))!);
        // Establish first-install state before catalogs/preferences create files.
        await loadDrafts();
        await parameterIconLibrary.initialize();
        await loadImportedFonts();
        stage('private-directory');
        userStyles = await watermarkRepository.list();
        stage('user-styles');
        final styles = File('${directory!.path}/styles.json');
        final storedStyles = await _readJsonRecovery(
          styles,
          validate: (data) {
            if (data is! List) throw const FormatException('无效样式库');
            for (final item in data) {
              StylePreset.fromJson(Map<String, dynamic>.from(item as Map));
            }
          },
        );
        if (storedStyles != null) {
          final legacy = (storedStyles as List)
              .map(
                (e) =>
                    StylePreset.fromJson(Map<String, dynamic>.from(e as Map)),
              )
              .toList();
          for (final style in legacy) {
            if (userStyles.any((s) => s.id == style.id)) continue;
            try {
              final pkg = await WatermarkPackageBuilder(
                watermarkRepository,
                modelCatalog,
              ).build(style);
              userStyles.add(
                await watermarkRepository.install(pkg, id: style.id),
              );
            } catch (_) {
              userStyles.add(style);
            }
          }
        }
        stage('drafts');
      }
      if (directory != null) {
        await parameterIconLibrary.initialize();
        // Finish an interrupted deletion before a later import can clear its
        // tombstone and expose unsanitized draft or preference references.
        if (parameterIconLibrary.removed.isNotEmpty) {
          await resetDeletedParameterIcons();
        }
      }
      final remembered = await templatePreferences?.remembered(
        document.packageId,
        document,
      );
      if (remembered != null) history = EditHistory(remembered);
      await setAsset(asset, save: false);
      stage('ready');
    } catch (e) {
      inform('初始化失败：$e');
      loading = false;
      notifyListeners();
    }
  }

  Future<ui.Image> decode(String path, {bool bundled = false}) async {
    final buffer = bundled
        ? await ui.ImmutableBuffer.fromAsset(path)
        : await ui.ImmutableBuffer.fromFilePath(path);
    ui.ImageDescriptor? descriptor;
    ui.Codec? codec;
    try {
      descriptor = await ui.ImageDescriptor.encoded(buffer);
      final factor = math.min(
        1.0,
        previewMaxEdge / math.max(descriptor.width, descriptor.height),
      );
      codec = await descriptor.instantiateCodec(
        targetWidth: math.max(1, (descriptor.width * factor).round()),
        targetHeight: math.max(1, (descriptor.height * factor).round()),
      );
      return (await codec.getNextFrame()).image;
    } finally {
      codec?.dispose();
      descriptor?.dispose();
      buffer.dispose();
    }
  }

  Future<void> setAsset(
    PhotoAsset photo, {
    EditHistory? restored,
    bool save = true,
  }) async {
    final token = ++_assetToken;
    loading = true;
    notifyListeners();
    try {
      var preparedPhoto = photo;
      if (!testMode && !photo.sample && photo.metadata.focal35mm <= 0) {
        try {
          final metadata = await NativeBridge.call<Map>('photo.metadata', {
            'path': photo.path,
          });
          final equivalent = metadata?['focal35mm'];
          if (equivalent is num && equivalent.isFinite && equivalent > 0) {
            preparedPhoto = PhotoAsset.fromJson({
              ...photo.toJson(),
              'metadata': {
                ...photo.metadata.toJson(),
                'focal35mm': equivalent.toDouble(),
              },
            });
          }
        } catch (_) {
          // Legacy drafts remain usable if their private original is missing or
          // unreadable. Only a real equivalent EXIF tag upgrades stored metadata.
        }
      }
      if (_disposed || token != _assetToken) return;
      final recovered = preparedPhoto.restoredDocument;
      var recoveredDocument =
          recovered != null &&
              FactoryWatermarkCatalog.isRetiredId(
                recovered['packageId']?.toString() ?? '',
              )
          ? EditDocument.fromJson(recovered)
          : restoreKnownEdit(recovered, allStyles);
      if (restored == null &&
          recovered != null &&
          recoveredDocument == null &&
          directory != null) {
        final retained = await watermarkRepository.retainedStyle(
          recovered['packageId']?.toString() ?? '',
        );
        if (retained != null) {
          recoveredDocument = restoreKnownEdit(recovered, [retained]);
        }
      }
      if (_disposed || token != _assetToken) return;
      final image = await decode(
        preparedPhoto.preview,
        bundled: preparedPhoto.sample,
      );
      if (_disposed || token != _assetToken) {
        image.dispose();
        return;
      }
      _fontIntent++;
      _resourcesToken++;
      _renderedDocument = null;
      _renderedAsset = null;
      _preparedResourceKey = null;
      if (background != null && !identical(background, preview)) {
        background!.dispose();
      }
      preview?.dispose();
      preview = image;
      background = null;
      _backgroundKey = '';
      _backgroundToned = false;
      asset = preparedPhoto;
      history = _supportedHistory(
        restored ?? EditHistory(recoveredDocument ?? document),
      );
      revision++;
      await prepareResources();
      if (save) scheduleSave();
    } finally {
      if (token == _assetToken) {
        loading = false;
        notifyListeners();
      }
    }
  }

  Future<List<PhotoAsset>> pick({bool multiple = false}) async {
    final rows = await NativeBridge.call<List>('pickPhotos', {
      'multiple': multiple,
      'autoRemoveWatermark': autoRemoveWatermark,
    });
    return rows
            ?.map(
              (e) => PhotoAsset.fromJson(Map<String, dynamic>.from(e as Map)),
            )
            .toList() ??
        [];
  }

  void apply(EditDocument doc, String label, {bool remember = true}) {
    if (busy) return;
    _fontIntent++;
    history.apply(doc, label);
    if (remember) rememberDesign();
    revision++;
    notifyListeners();
    scheduleSave();
    unawaited(_safePrepare());
  }

  Future<void> applyStyle(StylePreset style) async {
    if (style.document.staruwPreserved) {
      throw const StaruwException('E_CAPABILITY', '此水印暂不能应用，可在水印库查看说明并导出原包');
    }
    final base = document, baseRevision = revision, intent = ++_fontIntent;
    final remembered = await templatePreferences?.remembered(
      style.document.packageId.isEmpty ? style.id : style.document.packageId,
      style.document,
    );
    if (_disposed || intent != _fontIntent || revision != baseRevision) return;
    if (style.document.isStaruw) {
      final checked = WatermarkPackageCodec.fromFiles(
        await watermarkRepository.files(style.document.packageId),
      );
      staruwNeed(
        !checked.document.staruwPreserved,
        'E_CAPABILITY',
        '此水印只能保留原件',
      );
      staruwNeed(
        checked.document.staruwReport['payloadDigest'] ==
            style.document.staruwReport['payloadDigest'],
        'E_HASH',
        '模板资源发生变化，请重新导入',
      );
      if (_disposed || intent != _fontIntent || revision != baseRevision) {
        return;
      }
      apply(
        (remembered ?? style.document).copyWith(staruwPhotoValues: {}),
        '应用${style.title}',
        remember: false,
      );
      return;
    }
    if (remembered != null) {
      apply(
        remembered.copyWith(
          modelOverride: base.modelOverride,
          parametersOverride: base.parametersOverride,
          xiaomiOptions: {
            ...remembered.xiaomiOptions,
            if (base.xiaomiOptions['exifOverrides'] is Map)
              'exifOverrides': base.xiaomiOptions['exifOverrides'],
          },
        ),
        '应用${style.title}',
        remember: false,
      );
      return;
    }
    var selected = base.font;
    final previousPath =
        (base.resourceBindings['fonts'] as Map? ?? {})[selected.id];
    if (!selected.automatic &&
        base.packageId.isNotEmpty &&
        previousPath is String) {
      if (directory == null) throw const FormatException('导入字库存储不可用');
      final resources = await _loadPackage(base.packageId);
      if (_disposed || intent != _fontIntent || revision != baseRevision) {
        return;
      }
      final Uint8List bytes;
      if (FactoryWatermarkCatalog.isFactoryId(base.packageId)) {
        final files =
            await (_factoryCatalog ??= await FactoryWatermarkCatalog.load())
                .files(base.packageId);
        bytes =
            files[previousPath] ?? (throw const FormatException('包内字库无法读取'));
      } else {
        bytes = await watermarkRepository
            .resource(base.packageId, previousPath)
            .readAsBytes();
      }
      if (_disposed || intent != _fontIntent || revision != baseRevision) {
        return;
      }
      if (sha256.convert(bytes).toString() !=
          resources.fontHashes[previousPath]) {
        throw const FormatException('包内字体指纹不匹配');
      }
      final row = await ImportedFonts(
        directory!,
      ).importBytes(bytes, previousPath.split('/').last);
      if (_disposed || intent != _fontIntent || revision != baseRevision) {
        return;
      }
      selected = FontRef(
        source: 'imported',
        id: row['id'] as String,
        fingerprint: row['fingerprint'] as String,
        weight: selected.weight,
        style: selected.style,
        axes: selected.axes,
      );
      final alias = resources.fonts[previousPath];
      if (alias != null) {
        _fontFamilies['${selected.id}:${selected.fingerprint}'] = alias;
      }
      await loadImportedFonts();
      if (_disposed || intent != _fontIntent || revision != baseRevision) {
        return;
      }
    }
    final bindings = Map<String, dynamic>.from(style.document.resourceBindings);
    if (!selected.automatic) {
      bindings['fonts'] =
          Map<String, dynamic>.from(bindings['fonts'] as Map? ?? {})
            ..remove(base.font.id)
            ..remove(selected.id);
    }
    apply(
      style.document.copyWith(
        enabled: true,
        font: selected,
        modelId: base.modelId,
        modelOverride: base.modelOverride,
        xiaomiOptions: {
          ...style.document.xiaomiOptions,
          if (base.xiaomiOptions['exifOverrides'] is Map)
            'exifOverrides': base.xiaomiOptions['exifOverrides'],
        },
        resourceBindings: bindings,
      ),
      '应用${style.title}',
      remember: false,
    );
  }

  void undo() {
    if (busy) return;
    _fontIntent++;
    history.undo();
    rememberDesign();
    revision++;
    notifyListeners();
    scheduleSave();
    unawaited(_safePrepare());
  }

  void redo() {
    if (busy) return;
    _fontIntent++;
    history.redo();
    rememberDesign();
    revision++;
    notifyListeners();
    scheduleSave();
    unawaited(_safePrepare());
  }

  void begin() {
    if (!busy) history.beginInteraction();
  }

  void previewEdit(EditDocument doc) {
    if (busy) return;
    history.preview(doc);
    notifyListeners();
  }

  void commit(String label) {
    if (busy) return;
    history.commitInteraction(label);
    rememberDesign();
    revision++;
    notifyListeners();
    scheduleSave();
    unawaited(_safePrepare());
  }

  void cancel() {
    history.cancelInteraction();
    notifyListeners();
  }

  Future<void> _safePrepare() async {
    final token = _resourcesToken + 1;
    final resourceKey = _resourceKey(document, asset);
    try {
      await prepareResources();
    } catch (e) {
      if (_disposed ||
          token != _resourcesToken ||
          _resourceKey(document, asset) != resourceKey) {
        return;
      }
      inform('资源加载失败：$e');
    }
  }

  Future<String> resolveFont(
    FontRef ref, {
    String packageId = '',
    Map<String, dynamic> bindings = const {},
  }) async {
    if (packageId.isNotEmpty && directory != null) {
      final resources = await _loadPackage(packageId);
      final path = (bindings['fonts'] as Map? ?? {})[ref.id] as String?;
      if (path != null) {
        final alias = resources.fonts[path];
        if (alias == null) throw const FormatException('包内字库无法加载');
        if (ref.fingerprint.isNotEmpty &&
            resources.fontHashes[path] != ref.fingerprint) {
          throw const FormatException('包内字体指纹不匹配');
        }
        return alias;
      }
    }
    if (ref.source == 'bundled') {
      final spec = bundledFontSpec(ref.id);
      for (final item in [spec, bundledFontSpec('mi-sans')]) {
        if (!_fontCapHeights.containsKey(item.family)) {
          final data = await rootBundle.load(item.asset);
          final cap = fontCapHeight(
            data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
          );
          if (cap != null) _fontCapHeights[item.family] = cap;
        }
      }
      return spec.family;
    }
    final key = '${ref.id}:${ref.fingerprint}';
    if (_fontFamilies.containsKey(key)) return _fontFamilies[key]!;
    if (ref.source == 'imported') {
      if (directory == null) throw const FormatException('导入字库存储不可用');
      final bytes = await ImportedFonts(directory!).bytes(ref);
      final alias = 'ImportedFont_${ref.fingerprint}';
      final cap = fontCapHeight(bytes);
      if (cap != null) _fontCapHeights[alias] = cap;
      await (FontLoader(
        alias,
      )..addFont(Future.value(ByteData.sublistView(bytes)))).load();
      _fontFamilies[key] = alias;
      return alias;
    }
    await NativeBridge.call<List>('fonts');
    final result = Map<String, dynamic>.from(
      (await NativeBridge.call<Map>('prepareFont', {'id': ref.id}))!,
    );
    if (ref.fingerprint.isNotEmpty &&
        result['fingerprint'] != ref.fingerprint) {
      throw const FormatException('系统字体已变化，请重新选择字体');
    }
    final alias = result['family'] as String;
    final bytes = await File(result['path'] as String).readAsBytes();
    final cap = fontCapHeight(bytes);
    if (cap != null) _fontCapHeights[alias] = cap;
    await (FontLoader(
      alias,
    )..addFont(Future.value(ByteData.sublistView(bytes)))).load();
    _fontFamilies[key] = alias;
    return alias;
  }

  void cancelPendingFont() {
    _fontIntent++;
  }

  Future<bool> chooseFont(Map<String, dynamic> font) async {
    final intent = ++_fontIntent, baseRevision = revision;
    try {
      if (font['source'] == 'imported') {
        if (directory == null) throw const FormatException('导入字库存储不可用');
        final id = font['id'];
        final stored = (await ImportedFonts(
          directory!,
        ).list()).where((row) => row['id'] == id).firstOrNull;
        if (stored == null) throw const FormatException('导入字体不存在');
        final ref = FontRef.fromJson(stored);
        await resolveFont(ref);
        if (_disposed || intent != _fontIntent || baseRevision != revision) {
          return false;
        }
        apply(_selectedFont(ref), '切换导入字体');
      } else if (font['source'] == 'system') {
        final result = Map<String, dynamic>.from(
          (await NativeBridge.call<Map>('prepareFont', {'id': font['id']}))!,
        );
        final ref = FontRef(
          source: 'system',
          id: font['id'] as String,
          fingerprint: result['fingerprint'] as String,
          weight: (font['weight'] as num?)?.toInt() ?? 500,
          style: font['style']?.toString() ?? 'normal',
          axes: Map.unmodifiable(
            Map<String, dynamic>.from(
              font['axes'] as Map? ?? {},
            ).map((k, v) => MapEntry(k, (v as num).toDouble())),
          ),
        );
        await resolveFont(ref);
        if (_disposed || intent != _fontIntent || baseRevision != revision) {
          return false;
        }
        apply(_selectedFont(ref), '切换系统字体');
      } else {
        apply(_selectedFont(FontRef.bundled(font['id'] as String)), '切换内置字体');
      }
    } catch (e) {
      inform('字体不可用：$e');
      return false;
    }
    return true;
  }

  EditDocument _selectedFont(FontRef ref) {
    final fonts = Map<String, dynamic>.from(
      document.resourceBindings['fonts'] as Map? ?? {},
    )..remove(ref.id);
    return document.copyWith(
      font: ref,
      resourceBindings: {...document.resourceBindings, 'fonts': fonts},
    );
  }

  void useAutomaticFont() {
    final fonts = Map<String, dynamic>.from(
      document.resourceBindings['fonts'] as Map? ?? {},
    );
    for (final id in ['oxanium', 'lg1056', 'mi-sans']) {
      fonts.remove(id);
    }
    cancelPendingFont();
    apply(
      document.copyWith(
        font: const FontRef.photoDefault(),
        resourceBindings: {...document.resourceBindings, 'fonts': fonts},
      ),
      '按照片自动选择字体',
    );
  }

  Future<void> loadSystemFonts() async {
    try {
      systemFonts = ((await NativeBridge.call<List>('fonts')) ?? [])
          .map((e) => Map<String, dynamic>.from(e as Map))
          .toList();
      notifyListeners();
    } catch (e) {
      inform('读取系统字体失败：$e');
    }
  }

  Future<void> loadImportedFonts() async {
    if (directory == null) return;
    importedFonts = await ImportedFonts(directory!).list();
    if (!_disposed) notifyListeners();
  }

  final Map<String, RegionArtwork> _regionArtwork = {};
  Future<void> _prepareRegions(EditDocument doc) async {
    for (final row in doc.regionReplacements.values.whereType<Map>()) {
      final id = row['asset'];
      if (id is! String || _regionArtwork.containsKey(id)) continue;
      final path = (doc.resourceBindings['regions'] as Map? ?? {})[id];
      final bytes = path is String
          ? await watermarkRepository
                .resource(doc.packageId, path)
                .readAsBytes()
          : await RegionAssets(directory!).bytes(id);
      await RegionAssets.verify(id, bytes);
      final RegionArtwork art;
      if (id.endsWith('.svg')) {
        art = RegionArtwork.vector(
          await vg.vg.loadPicture(
            SvgStringLoader(ModelLockupAssets.inlineFills(utf8.decode(bytes))),
            null,
          ),
        );
      } else {
        final codec = await ui.instantiateImageCodec(bytes);
        try {
          art = RegionArtwork.image((await codec.getNextFrame()).image);
        } finally {
          codec.dispose();
        }
      }
      if (_disposed || _regionArtwork.containsKey(id)) {
        art.dispose();
      } else {
        _regionArtwork[id] = art;
      }
    }
  }

  Future<void> importRegionImage(String region) async {
    if (directory == null || regionSupport(document)[region] != true) {
      throw const FormatException('Unsupported region');
    }
    final baseRevision = revision;
    final picked = await NativeBridge.call<Map>('pickRegionImage');
    if (picked == null || _disposed || baseRevision != revision) return;
    final bytes = picked['bytes'];
    if (bytes is! Uint8List) throw const FormatException('Invalid image');
    final id = await RegionAssets(directory!).importBytes(bytes);
    if (_disposed || baseRevision != revision) return;
    final candidate = document.copyWith(
      regionReplacements: validateRegionReplacements({
        ...document.regionReplacements,
        region: {
          ...(document.regionReplacements[region] as Map? ?? {}),
          'source': 'image',
          'asset': id,
        },
      }),
    );
    await _prepareRegions(candidate);
    if (_disposed || baseRevision != revision) return;
    if (region == 'parameters') {
      await parameterIconLibrary.register(
        id,
        picked['title'] as String? ?? '自定义图标',
        restore: true,
      );
    }
    apply(candidate, '导入区域图片');
  }

  Future<void> useBuiltinEmblem(String id) async {
    if (directory == null || regionSupport(document)['parameters'] != true) {
      throw const FormatException('当前水印没有可替换的参数图标');
    }
    final token = revision;
    final asset = await RegionAssets(
      directory!,
    ).importBytes(await builtinEmblemBytes(id));
    if (_disposed || token != revision) return;
    final candidate = document.copyWith(
      regionReplacements: validateRegionReplacements({
        ...document.regionReplacements,
        'parameters': {'source': 'image', 'asset': asset, 'shape': 'square'},
      }),
    );
    await _prepareRegions(candidate);
    if (_disposed || token != revision) return;
    apply(candidate, '使用 $id 图标');
  }

  Future<void> useParameterIcon(String asset) async {
    if (directory == null || regionSupport(document)['parameters'] != true) {
      throw const FormatException('当前水印没有可替换的参数图标');
    }
    if (usesGlobalParameterIcon) {
      throw const FormatException('参数图标已由实验性功能统一设置');
    }
    final token = revision;
    await parameterIconLibrary.ensureAsset(asset);
    if (_disposed || token != revision) return;
    final previous = document.regionReplacements['parameters'] as Map? ?? {};
    final candidate = document.copyWith(
      regionReplacements: validateRegionReplacements({
        ...document.regionReplacements,
        'parameters': {...previous, 'source': 'image', 'asset': asset},
      }),
    );
    await _prepareRegions(candidate);
    if (_disposed || token != revision) return;
    apply(candidate, '更换参数图标');
  }

  Future<void> resetDeletedParameterIcons({bool persist = true}) async {
    final removed = parameterIconLibrary.removed;
    if (removed.isEmpty) return;
    if (_globalParameterIcon != null &&
        removed.contains(_globalParameterIcon!.asset)) {
      await setGlobalParameterIcon(
        enabled: true,
        asset: builtinEmblems.first['asset']!,
        shape: _globalParameterIcon!.shape,
      );
    }
    _saveTimer?.cancel();
    await _store.pendingWrites;
    history = EditHistory.fromJson(
      withoutParameterIcons(history.toJson(), removed),
    );
    userStyles = [
      for (final style in userStyles)
        StylePreset.fromJson(withoutParameterIcons(style.toJson(), removed)),
    ];
    revision++;
    if (persist) {
      await templatePreferences?.removeParameterIcons(removed);
      final folder = Directory('${directory!.path}/drafts');
      final files = <File>[
        if (await folder.exists())
          ...await folder
              .list(followLinks: false)
              .where(
                (e) =>
                    e is File &&
                    (e.path.endsWith('.json') || e.path.endsWith('.json.bak')),
              )
              .cast<File>()
              .toList(),
        for (final suffix in ['', '.bak'])
          File('${directory!.path}/styles.json$suffix'),
      ];
      for (final file in files) {
        if (!await file.exists()) continue;
        final original = await file.readAsString();
        dynamic data;
        try {
          data = jsonDecode(original);
        } on FormatException {
          continue;
        }
        final cleaned = jsonEncode(withoutParameterIcons(data, removed));
        if (cleaned == original) continue;
        final pending = File('${file.path}.icon-clean.tmp');
        try {
          await pending.writeAsString(cleaned, flush: true);
          await pending.rename(file.path);
        } finally {
          if (await pending.exists()) await pending.delete();
        }
      }
      await loadDrafts();
    }
    await prepareResources();
    notifyListeners();
  }

  Future<StaruwRenderResources> _staruwResourcesFor(
    EditDocument doc,
    _PackageResources base,
  ) async {
    final assets = doc.resourceBindings['staruwAssets'] as Map? ?? {};
    final fonts = doc.resourceBindings['staruwFonts'] as Map? ?? {};
    if (assets.isEmpty && fonts.isEmpty) return base.scene!;
    final key = staruwHash(
      utf8.encode(
        jsonEncode([
          doc.packageId,
          doc.staruwConfig!['staruw']['resources'],
          doc.staruwConfig!['staruw']['scene']?['fonts'] ??
              doc.staruwConfig!['staruw']['xiaomi']['fonts'],
          assets,
          fonts,
        ]),
      ),
    );
    if (_staruwVariants[key]?.scene case final StaruwRenderResources cached) {
      return cached;
    }
    final files = {
      ...await watermarkRepository.files(doc.packageId),
      ...await staruwAddedFiles(doc, directory!),
    };
    final checked = WatermarkPackageCodec.fromFiles(
      staruwSeal({
        ...files,
        'config.json': utf8.encode(jsonEncode(doc.staruwConfig)),
      }),
    );
    staruwNeed(
      !checked.document.staruwPreserved,
      'E_CAPABILITY',
      '新增资源需要未支持的能力',
    );
    final variant = _PackageResources();
    try {
      var pixels = base.images.values.fold<int>(
        0,
        (n, i) => n + i.width * i.height,
      );
      for (final row
          in (doc.staruwConfig!['staruw']['resources'] as Map).values) {
        final path = row['path'] as String;
        if (row['kind'] == 'font' && !base.fonts.containsKey(path)) {
          final hash = row['sha256'] as String,
              alias = 'WatermarkFont_${row['sha256']}';
          if (!_fontFamilies.containsKey('bytes:$hash')) {
            await (FontLoader(alias)
                  ..addFont(Future.value(ByteData.sublistView(files[path]!))))
                .load();
            _fontFamilies['bytes:$hash'] = alias;
          }
          variant.fonts[path] = alias;
        } else if (row['kind'] == 'image' &&
            row['mediaType'] != 'image/svg+xml' &&
            !base.images.containsKey(path)) {
          final buffer = await ui.ImmutableBuffer.fromUint8List(files[path]!);
          ui.ImageDescriptor? descriptor;
          ui.Codec? codec;
          try {
            descriptor = await ui.ImageDescriptor.encoded(buffer);
            pixels += descriptor.width * descriptor.height;
            staruwNeed(pixels <= 64000000, 'E_LIMIT', '水印图片像素预算超限');
            codec = await descriptor.instantiateCodec();
            variant.images[path] = (await codec.getNextFrame()).image;
          } finally {
            codec?.dispose();
            descriptor?.dispose();
            buffer.dispose();
          }
        }
      }
      variant.scene = await StaruwRenderResources.load(
        doc.staruwConfig!,
        files,
        {...base.images, ...variant.images},
        {...base.fonts, ...variant.fonts},
      );
      if (_disposed) throw StateError('编辑器已关闭');
      // Bound additional decoded resources while keeping the displayed plan alive.
      while (_staruwVariants.length >= 2) {
        final old = _staruwVariants.entries
            .where(
              (e) =>
                  !identical(e.value.scene, _scenePlan?.resources) &&
                  !identical(e.value.scene, _nativeStaruwResources),
            )
            .firstOrNull;
        if (old == null) break;
        _staruwVariants.remove(old.key)?.dispose();
      }
      _staruwVariants[key] = variant;
      return variant.scene!;
    } catch (_) {
      variant.dispose();
      rethrow;
    }
  }

  Future<void> _validateStaruwEdit(EditDocument candidate) async {
    if (candidate.isStaruw && preview != null) {
      final base = await _loadPackage(candidate.packageId);
      final r = await _staruwResourcesFor(candidate, base);
      if (candidate.isStaruwScene) {
        final plan = await StaruwRenderPlan.create(
          candidate,
          asset,
          preview!,
          r,
        );
        plan.dispose();
      } else {
        final plan = _makeNativePlan(
          candidate,
          asset,
          preview!,
          base,
          family,
          null,
        );
        await plan.prepareStaruw(r);
        plan.disposeStaruw();
      }
    }
  }

  Future<void> setStaruwControl(
    String id,
    dynamic value, {
    bool clear = false,
  }) async {
    final before = revision;
    final candidate = clear
        ? StaruwControls(document).clearPhoto(id)
        : StaruwControls(document).set(id, value);
    await _validateStaruwEdit(candidate);
    if (!_disposed && before == revision) {
      apply(candidate, clear ? '恢复照片参数' : '调整水印');
    }
  }

  Future<void> importStaruwImage(String controlId) async {
    final controls = StaruwControls(document);
    final control = controls.controls
        .where((c) => c['id'] == controlId)
        .firstOrNull;
    staruwNeed(
      directory != null &&
          control != null &&
          ['image', 'region'].contains(control['type']),
      'E_BINDING',
      '此控件不允许导入图片',
    );
    final before = revision,
        picked = await NativeBridge.call<Map>('pickRegionImage');
    if (picked == null || _disposed || before != revision) return;
    final input = picked['bytes'];
    staruwNeed(input is Uint8List, 'E_RESOURCE', '图片无法读取');
    await RegionAssets.validate(input as Uint8List);
    final bytes = await staruwShareImage(input);
    final assetId = await RegionAssets(directory!).importBytes(bytes);
    final extension = assetId.split('.').last;
    final mime = {
      'png': 'image/png',
      'jpg': 'image/jpeg',
      'svg': 'image/svg+xml',
    }[extension]!;
    staruwNeed(
      (control!['allowTypes'] as List).contains(mime),
      'E_BINDING',
      '模板不允许这种图片格式',
    );
    final rid = 'user${staruwHash(bytes)}',
        config =
            jsonDecode(jsonEncode(document.staruwConfig))
                as Map<String, dynamic>;
    config['staruw']['resources'][rid] = {
      'path': 'resources/user/$assetId',
      'kind': 'image',
      'mediaType': mime,
      'sha256': staruwHash(bytes),
    };
    var candidate = document.copyWith(
      staruwConfig: config,
      resourceBindings: {
        ...document.resourceBindings,
        'staruwAssets': {
          ...document.resourceBindings['staruwAssets'] as Map? ?? {},
          rid: assetId,
        },
      },
    );
    candidate = StaruwControls(candidate).set(controlId, {
      'mode': 'image',
      'resourceId': rid,
      if (control['type'] == 'region')
        'fit': (control['allowFits'] as List).first
      else
        'shape': (control['allowShapes'] as List).first,
    });
    await _validateStaruwEdit(candidate);
    if (!_disposed && before == revision) {
      if (GlobalParameterIcon.controlsParameterIcon(control)) {
        await parameterIconLibrary.register(
          assetId,
          picked['title'] as String? ?? '自定义图标',
          restore: true,
        );
      }
      apply(candidate, '导入水印图片');
    }
  }

  Future<void> importStaruwFont(String controlId) async {
    final controls = StaruwControls(document),
        control = controls.controls
            .where((c) => c['id'] == controlId)
            .firstOrNull;
    staruwNeed(
      directory != null &&
          control?['type'] == 'region' &&
          (control!['allowModes'] as List).contains('text'),
      'E_BINDING',
      '此控件不允许导入签名字体',
    );
    final before = revision, picked = await NativeBridge.call<Map>('pickFont');
    if (picked == null || _disposed || before != revision) return;
    final bytes = picked['bytes'];
    staruwNeed(bytes is Uint8List, 'E_RESOURCE', '字库无法读取');
    StaruwFontFile(bytes as Uint8List);
    final imported = await ImportedFonts(
      directory!,
    ).importBytes(bytes, picked['title'] as String? ?? '签名字体');
    final hash = staruwHash(bytes), rid = 'font$hash', fid = 'font$hash';
    final config =
            jsonDecode(jsonEncode(document.staruwConfig))
                as Map<String, dynamic>,
        u = config['staruw'] as Map;
    final otf = String.fromCharCodes(bytes.take(4)) == 'OTTO';
    u['resources'][rid] = {
      'path': 'resources/user/$hash.${otf ? 'otf' : 'ttf'}',
      'kind': 'font',
      'mediaType': otf ? 'font/otf' : 'font/ttf',
      'sha256': hash,
    };
    (u['scene'] ?? u['xiaomi'])['fonts'][fid] = {
      'resourceId': rid,
      'axes': <String, dynamic>{},
      'fallbackFontIds': <String>[],
    };
    var candidate = document.copyWith(
      staruwConfig: config,
      resourceBindings: {
        ...document.resourceBindings,
        'staruwFonts': {
          ...document.resourceBindings['staruwFonts'] as Map? ?? {},
          rid: imported,
        },
      },
    );
    final current = controls.allValues[controlId] as Map? ?? {};
    candidate = StaruwControls(candidate).set(controlId, {
      'mode': 'text',
      'text': current['mode'] == 'text' ? current['text'] : '',
      'fontId': fid,
    });
    await _validateStaruwEdit(candidate);
    if (!_disposed && before == revision) apply(candidate, '导入签名字体');
  }

  Future<void> importFont() async {
    if (directory == null) throw const FormatException('导入字库存储不可用');
    final baseRevision = revision;
    final picked = await NativeBridge.call<Map>('pickFont');
    if (picked == null || _disposed || baseRevision != revision) {
      return;
    }
    // Opening Android's picker makes this Activity inactive and cancels pending
    // render/font work. Start the loading token after that expected lifecycle.
    final intent = ++_fontIntent;
    final bytes = picked['bytes'];
    if (bytes is! Uint8List) throw const FormatException('无法读取字体文件');
    // Validate and load before publishing either the catalog or a history edit.
    ImportedFonts.validate(bytes);
    final hash = sha256.convert(bytes).toString();
    final alias = 'ImportedFont_$hash';
    await (FontLoader(
      alias,
    )..addFont(Future.value(ByteData.sublistView(bytes)))).load();
    if (_disposed || intent != _fontIntent || baseRevision != revision) return;
    final font = await ImportedFonts(
      directory!,
    ).importBytes(bytes, picked['title']?.toString() ?? '导入字体');
    await loadImportedFonts();
    if (_disposed || intent != _fontIntent || baseRevision != revision) return;
    final cap = fontCapHeight(bytes);
    if (cap != null) _fontCapHeights[alias] = cap;
    _fontFamilies['${font['id']}:$hash'] = alias;
    apply(_selectedFont(FontRef.fromJson(font)), '导入字体');
  }

  Future<_PackageResources> _loadPackage(
    String id,
  ) => _packageJobs.putIfAbsent(id, () async {
    final files = FactoryWatermarkCatalog.isFactoryId(id)
        ? await (_factoryCatalog ??= await FactoryWatermarkCatalog.load())
              .files(id)
        : await watermarkRepository.files(id);
    final packageConfig = jsonDecode(utf8.decode(files['config.json']!)) as Map;
    if (packageConfig['format'] == staruwFormat) {
      final checked = WatermarkPackageCodec.fromFiles(files);
      staruwNeed(
        !checked.document.staruwPreserved,
        'E_CAPABILITY',
        '未支持的水印不加载渲染资源',
      );
    }
    final extensionDocument =
        (packageConfig['starmark'] ?? packageConfig['document']) as Map? ?? {};
    final regionBindings =
        (extensionDocument['resourceBindings'] as Map? ?? {})['regions']
            as Map? ??
        {};
    final regionPaths = regionBindings.values.toSet();
    final originalConfig = jsonEncode(
      Map.of(packageConfig)
        ..remove('starmark')
        ..remove('document'),
    );
    for (final entry in regionBindings.entries) {
      await RegionAssets.verify(entry.key.toString(), files[entry.value]!);
    }
    final resources = _PackageResources();
    var pixels = 0;
    try {
      for (final entry in files.entries) {
        if (packageConfig['format'] == staruwFormat &&
            entry.key.startsWith('source/')) {
          continue;
        }
        final extension = entry.key.split('.').last.toLowerCase();
        if (['ttf', 'otf'].contains(extension)) {
          final hash = sha256.convert(entry.value).toString();
          final alias = 'WatermarkFont_$hash';
          if (!_fontFamilies.containsKey('bytes:$hash')) {
            await (FontLoader(
              alias,
            )..addFont(Future.value(ByteData.sublistView(entry.value)))).load();
            _fontFamilies['bytes:$hash'] = alias;
          }
          resources.fonts[entry.key] = alias;
          final cap = fontCapHeight(entry.value);
          if (cap != null) _fontCapHeights[alias] = cap;
          resources.fontHashes[entry.key] = hash;
        } else if (['png', 'webp', 'jpg', 'jpeg'].contains(extension)) {
          // Library previews are native UI thumbnails, not editor artwork.
          // Preserve packages that explicitly use the preview as a render input.
          if (entry.key == 'preview.webp' &&
              !originalConfig.contains(entry.key) &&
              !regionPaths.contains(entry.key)) {
            continue;
          }
          if (regionPaths.contains(entry.key) &&
              !originalConfig.contains(entry.key)) {
            continue;
          }
          final buffer = await ui.ImmutableBuffer.fromUint8List(entry.value);
          ui.ImageDescriptor? descriptor;
          ui.Codec? codec;
          try {
            descriptor = await ui.ImageDescriptor.encoded(buffer);
            pixels += descriptor.width * descriptor.height;
            if (descriptor.width > 8192 ||
                descriptor.height > 8192 ||
                pixels > 64000000) {
              throw const FormatException('水印图片像素预算超限');
            }
            codec = await descriptor.instantiateCodec();
            final decoded = (await codec.getNextFrame()).image;
            resources.images[entry.key] = decoded;
            if (RegExp(
              r'^(xiaomi|redmi|poco)(?:_|\.)',
              caseSensitive: false,
            ).hasMatch(entry.key.split('/').last)) {
              resources.imageContentBounds[entry.key] =
                  await ModelLockupAssets.bitmapInkBounds(decoded);
            }
          } finally {
            codec?.dispose();
            descriptor?.dispose();
            buffer.dispose();
          }
        } else if (extension == 'svg' && originalConfig.contains(entry.key)) {
          resources.vectors[entry.key] = RegionArtwork.vector(
            await vg.vg.loadPicture(
              SvgStringLoader(
                ModelLockupAssets.inlineFills(utf8.decode(entry.value)),
              ),
              null,
            ),
          );
        } else if (entry.key == 'i18n.json') {
          final data = jsonDecode(utf8.decode(entry.value)) as Map;
          for (final row in data.entries) {
            final v = row.value;
            if (v is Map) {
              resources.localizations[row.key.toString()] =
                  (v['zh-rCN'] ?? v['zh-CN'] ?? v['default'] ?? '').toString();
            }
          }
        }
      }
      final config = jsonDecode(utf8.decode(files['config.json']!)) as Map;
      final state = (config['starmark'] ?? config['document']) as Map? ?? {};
      final bindings = state['resourceBindings'] as Map? ?? {};
      final marks = config['format'] == staruwFormat
          ? {
              for (final row in (config['staruw']['resources'] as Map).values)
                if ((row['path'] as String).startsWith('assets/marks/') &&
                    ModelLockupAssets.resourceKeys.contains(
                      (row['path'] as String).substring(13),
                    ))
                  (row['path'] as String).substring(13): row['path'],
            }
          : bindings['modelMarks'] as Map? ?? {};
      if (marks.isNotEmpty) {
        final overrides = <String, Uint8List>{};
        for (final entry in marks.entries) {
          final key = entry.key.toString();
          final path = entry.value.toString();
          if (ModelLockupAssets.retiredKeys.contains(key)) continue;
          if (!ModelLockupAssets.resourceKeys.contains(key) ||
              !safeWatermarkPath(path) ||
              files[path] == null) {
            throw const FormatException('无效机型组合资源');
          }
          overrides[key] = files[path]!;
        }
        resources.marks = await ModelLockupAssets.load(
          overrides: overrides,
          loadMaterials: false,
        );
      }
      if (_disposed) throw StateError('编辑器已关闭');
      if (packageConfig['format'] == staruwFormat) {
        resources.scene = await StaruwRenderResources.load(
          packageConfig,
          files,
          resources.images,
          resources.fonts,
        );
      }
      _packages[id] = resources;
      return resources;
    } catch (_) {
      resources.dispose();
      rethrow;
    }
  });

  Future<StylePreset> installWatermark(WatermarkPackage package) async {
    if (directory == null) throw StateError('存储不可用');
    if (busy) throw StateError('请等待导出完成');
    final style = await watermarkRepository.install(package);
    try {
      final resources = await _loadPackage(style.id);
      await _prepareRegions(style.document);
      final font = await resolveFont(
        style.document.font.resolveFor(asset.metadata),
        packageId: style.id,
        bindings: style.document.resourceBindings,
      );
      if (style.document.xiaomiConfig != null && preview != null) {
        await _prepareLunarBindings(style.document, asset);
        _makeNativePlan(style.document, asset, preview!, resources, font, null);
      }
      userStyles.insert(0, style);
      notifyListeners();
      return style;
    } catch (_) {
      _packages.remove(style.id)?.dispose();
      _packageJobs.remove(style.id);
      await watermarkRepository.directory(style.id).delete(recursive: true);
      rethrow;
    }
  }

  Future<void> prepareResources() async {
    final token = ++_resourcesToken,
        d = StaruwNativeAdapter.project(effectiveDocument()),
        photo = asset;
    final image = preview;
    final resourceKey = _resourceKey(d, photo);
    bool stale() =>
        _disposed ||
        token != _resourcesToken ||
        !identical(preview, image) ||
        _resourceKey(document, asset) != resourceKey;
    final package = d.packageId.isNotEmpty
        ? await _loadPackage(d.packageId)
        : null;
    if (stale()) return;
    if (d.isStaruwScene && image != null) {
      staruwNeed(
        !d.staruwPreserved && package?.scene != null,
        'E_CAPABILITY',
        '通用场景资源尚不可用',
      );
      final scene = await StaruwRenderPlan.create(
        d,
        photo,
        image,
        await _staruwResourcesFor(d, package!),
      );
      if (stale()) {
        scene.dispose();
        return;
      }
      final previous = _scenePlan;
      _scenePlan = scene;
      _renderedDocument = d;
      _renderedAsset = photo;
      _preparedResourceKey = resourceKey;
      previous?.dispose();
      notifyListeners();
      return;
    }
    await _prepareRegions(d);
    if (stale()) return;
    await _prepareLunarBindings(d, photo);
    if (stale()) return;
    // Original JSON can mix MiSans and Oxanium independently of the selected
    // face. Prepare both references so cap-height normalization does not depend
    // on which fonts the user happened to select earlier in this session.
    if (d.xiaomiConfig != null) {
      await resolveFont(FontRef.bundled('mi-sans'));
      if (stale()) return;
      await resolveFont(FontRef.bundled('oxanium'));
      if (stale()) return;
    }
    final resolvedFamily = await resolveFont(
      d.font.resolveFor(photo.metadata),
      packageId: d.packageId,
      bindings: d.resourceBindings,
    );
    if (stale()) return;
    final id = d.modelId.isEmpty
        ? resolveModelSvgId(photo.metadata, modelCatalog)
        : d.modelId;
    final mappedModel =
        (d.resourceBindings['models'] as Map? ?? {})[id] as String?;
    final picture = mappedModel != null
        ? await _picture(
            'pkg:${d.packageId}:$id',
            watermarkRepository.resource(d.packageId, mappedModel).path,
            bundled: false,
          )
        : null;
    if (stale()) return;
    final brandId = resolveBrand(photo.metadata.make);
    final brandPicture = brandId == 'generic'
        ? null
        : (d.resourceBindings['brands'] as Map? ?? {})[brandId] != null
        ? await _picture(
            'pkg:${d.packageId}:brand-$brandId',
            watermarkRepository
                .resource(
                  d.packageId,
                  (d.resourceBindings['brands'] as Map)[brandId] as String,
                )
                .path,
            bundled: false,
          )
        : null;
    if (stale()) return;
    final nativeBlur =
        d.enabled &&
        d.xiaomiConfig != null &&
        XiaomiRenderPlan.backgroundNeedsBlur(d.xiaomiConfig!, d.xiaomiOptions);
    final blurEnabled =
        nativeBlur ||
        (d.enabled &&
            d.mode == WatermarkMode.frame &&
            _isImageBackground(d.background));
    var nextBackground = background;
    var nextBackgroundKey = _backgroundKey;
    var nextBackgroundToned = _backgroundToned;
    var ownsBackground = false;
    if (blurEnabled && image != null && !testMode) {
      final layout = d.xiaomiConfig != null
          ? _makeNativePlan(
              d,
              photo,
              image,
              package!,
              resolvedFamily,
              null,
            ).layout
          : d.layout(photo.width, photo.height);
      final tone = _blurTone(d);
      final backgroundChoice = d.xiaomiConfig == null
          ? null
          : XiaomiRenderPlan.selectedBackground(
              d.xiaomiConfig!,
              d.xiaomiOptions,
            );
      // Text, timestamps and GPS do not change the sampled background. Reuse
      // the GPU result while those fields change instead of regenerating it.
      final key =
          '${photo.id}:${layout.width}:${layout.height}:${d.blur}:${jsonEncode(backgroundChoice)}:$tone:$useXiaomiImageBlur:$useXiaomiGlassWatermark:${d.background.name}:${_glassRequested(d)}:$nativeMaterialRevision';
      if (key != _backgroundKey) {
        final source = await _nativeSource(photo);
        if (stale()) return;
        final result = await _requestBlur(
          {
            'path': source,
            'orientation': photo.orientation,
            'width': layout.width,
            'height': layout.height,
            'radius': d.blur,
          },
          tone,
          glass: _glassRequested(d),
          custom: d.xiaomiConfig == null,
        );
        final path = result.$1;
        if (path != null) {
          final blurred = await decode(path);
          await File(path).delete();
          if (stale()) {
            blurred.dispose();
            return;
          }
          nextBackground = blurred;
          nextBackgroundKey = key;
          nextBackgroundToned = result.$2;
          ownsBackground = true;
        }
      }
    } else if (blurEnabled && photo.sample) {
      nextBackground = image?.clone();
      nextBackgroundKey = '';
      nextBackgroundToned = false;
      ownsBackground = nextBackground != null;
    } else {
      nextBackground = null;
      nextBackgroundKey = '';
      nextBackgroundToned = false;
    }
    if (stale()) {
      if (ownsBackground) nextBackground?.dispose();
      return;
    }
    XiaomiRenderPlan? staruwNative;
    if (d.isStaruw && !d.isStaruwScene && image != null) {
      staruwNative = _makeNativePlan(
        d,
        photo,
        image,
        package!,
        resolvedFamily,
        nextBackground,
        backgroundToned: nextBackgroundToned,
      );
      final nativeResources = await _staruwResourcesFor(d, package);
      await staruwNative.prepareStaruw(nativeResources);
      if (!stale()) _nativeStaruwResources = nativeResources;
      if (stale()) {
        staruwNative.disposeStaruw();
        if (ownsBackground) nextBackground?.dispose();
        return;
      }
    }
    // Publish one complete render state. The previous document, face, pictures
    // and background remain usable throughout first-use package preparation.
    final previousBackground = background;
    if (staruwNative != null) {
      _plan?.disposeStaruw();
      _plan = staruwNative;
      _planDocument = d;
      _planImage = image;
      _planBackground = nextBackground;
      _planFamily = resolvedFamily;
    }
    family = resolvedFamily;
    model = picture;
    brand = brandPicture;
    background = nextBackground;
    _backgroundKey = nextBackgroundKey;
    _backgroundToned = nextBackgroundToned;
    _renderedDocument = d;
    _renderedAsset = photo;
    _preparedResourceKey = resourceKey;
    if (previousBackground != null &&
        !identical(previousBackground, background) &&
        !identical(previousBackground, preview)) {
      previousBackground.dispose();
    }
    notifyListeners();
  }

  String? _blurTone(EditDocument d) {
    if (d.xiaomiConfig != null) {
      return XiaomiRenderPlan.backgroundBlurTone(
        d.xiaomiConfig!,
        d.xiaomiOptions,
      );
    }
    return switch (d.background) {
      BackgroundStyle.darkBlur || BackgroundStyle.darkGlass => 'dark',
      BackgroundStyle.lightBlur || BackgroundStyle.lightGlass => 'light',
      _ => null,
    };
  }

  Future<(String?, bool)> _requestBlur(
    Map<String, dynamic> args,
    String? tone, {
    bool glass = false,
    bool custom = false,
  }) async {
    final width = (args['width'] as num).toInt();
    final height = (args['height'] as num).toInt();
    final factor = math.min(1.0, previewMaxEdge / math.max(width, height));
    args = {
      ...args,
      'width': math.max(1, (width * factor).round()),
      'height': math.max(1, (height * factor).round()),
    };
    if (useXiaomiGlassWatermark && glass && tone != null) {
      try {
        final path = await NativeBridge.call<String>('xiaomi.glass', {
          ...args,
          'tone': tone,
        });
        if (path != null) return (path, true);
      } on PlatformException catch (_) {
        // Vendor rendering failure falls back to the original blur background.
      } on MissingPluginException catch (_) {
        // Portable packages also work on devices without the vendor bridge.
      }
    }
    if (useXiaomiImageBlur && tone != null) {
      try {
        final path = await NativeBridge.call<String>('xiaomi.blur', {
          ...args,
          'tone': tone,
          'custom': custom,
        });
        if (path != null) return (path, !custom);
      } on PlatformException catch (_) {
        // An unavailable vendor GPU implementation must not block export.
      } on MissingPluginException catch (_) {
        // The bridge may be unavailable in previews/tests or older builds.
      }
    }
    return (await NativeBridge.call<String>('blur', args), false);
  }

  Future<void> setXiaomiImageBlur(bool value) async {
    if (useXiaomiImageBlur == value) return;
    useXiaomiImageBlur = value;
    _backgroundKey = '';
    if (preview != null) await prepareResources();
  }

  static bool _isImageBackground(BackgroundStyle value) =>
      ![BackgroundStyle.white, BackgroundStyle.black].contains(value);

  bool _glassRequested(EditDocument d) => d.xiaomiConfig != null
      ? d.xiaomiOptions['glassBackground'] == true
      : [
          BackgroundStyle.darkGlass,
          BackgroundStyle.lightGlass,
        ].contains(d.background);

  Future<void> setXiaomiGlassWatermark(bool value) async {
    if (useXiaomiGlassWatermark == value) return;
    useXiaomiGlassWatermark = value;
    _backgroundKey = '';
    if (preview != null) await prepareResources();
  }

  Future<void> invalidateNativeMaterial() async {
    nativeMaterialRevision++;
    _backgroundKey = '';
    if (preview != null) await prepareResources();
  }

  void scheduleSave() {
    if (!writesDrafts || testMode || directory == null) return;
    if (asset.sample && !sampleAvailable) return;
    _deletedDraftIds.remove(asset.id);
    saveStatus = '保存中';
    _saveTimer?.cancel();
    _saveTimer = Timer(
      const Duration(milliseconds: 500),
      () => unawaited(saveDraft()),
    );
  }

  Future<String> _nativeSource(PhotoAsset photo) async {
    if (!photo.sample) return photo.path;
    if (directory == null) throw StateError('应用存储不可用');
    final extension = photo.path.split('.').last.toLowerCase();
    final file = File('${directory!.path}/sample-${photo.id}.$extension');
    if (!await file.exists()) {
      await file.writeAsBytes(
        (await rootBundle.load(photo.path)).buffer.asUint8List(),
        flush: true,
      );
    }
    return file.path;
  }

  Future<dynamic> _readJsonRecovery(
    File file, {
    void Function(dynamic)? validate,
  }) async {
    for (final candidate in [file, File('${file.path}.bak')]) {
      try {
        if (await candidate.exists()) {
          final data = jsonDecode(await candidate.readAsString());
          validate?.call(data);
          return data;
        }
      } catch (_) {
        /* Try last complete backup. */
      }
    }
    return null;
  }

  Future<vg.PictureInfo?> _picture(
    String key,
    String path, {
    bool bundled = true,
  }) {
    return _pictureJobs.putIfAbsent(key, () async {
      final picture = await vg.vg.loadPicture(
        SvgStringLoader(
          bundled
              ? await rootBundle.loadString(path)
              : await File(path).readAsString(),
        ),
        null,
      );
      if (_disposed) {
        picture.picture.dispose();
        return null;
      }
      _models[key] = picture;
      return picture;
    });
  }

  Future<void> saveDraft({bool explicit = false}) async {
    if (!writesDrafts || directory == null || testMode) return;
    if (asset.sample && !sampleAvailable) return;
    if (explicit) _deletedDraftIds.remove(asset.id);
    if (_deletedDraftIds.contains(asset.id)) return;
    final snapshot = {
      'id': asset.id,
      'updated': DateTime.now().toIso8601String(),
      'photo': asset.toJson(),
      'history': history.toJson(),
    };
    try {
      _acceptDrafts(await _store.save(snapshot, completesEditing: explicit));
      saveStatus = '已保存';
    } catch (e) {
      saveStatus = '保存失败';
      message = '草稿保存失败：$e';
      if (explicit) rethrow;
    }
    if (!_disposed) notifyListeners();
  }

  Future<void> loadDrafts() async {
    if (directory == null) return;
    _acceptDrafts(await _store.load());
    if (!_disposed) notifyListeners();
  }

  void _acceptDrafts(List<Map<String, dynamic>> value) {
    drafts = value;
    _deletedDraftIds.addAll(_store.removedIds);
    _store.removedIds.clear();
  }

  /// Leaving a real-photo session completes its already-applied watermark;
  /// import cancellation and background autosaves never call this transition.
  Future<void> finishPhotoEditing() async {
    if (!writesDrafts || directory == null || testMode || !hasPhoto) return;
    if (asset.sample && !history.canUndo && !history.canRedo) return;
    if (!_deletedDraftIds.contains(asset.id)) {
      await saveDraft();
      if (saveStatus != '已保存') throw StateError('草稿保存失败，请重试');
    }
    _acceptDrafts(await _store.completeEditing());
    if (!_disposed) notifyListeners();
  }

  Future<void> deleteDrafts(Iterable<String> ids) async {
    if (directory == null) throw StateError('草稿存储不可用');
    final selected = ids.toSet();
    if (selected.isEmpty ||
        selected.any((id) => !drafts.any((row) => row['id'] == id))) {
      throw const FormatException('草稿不存在或未选择');
    }
    if (selected.contains(asset.id)) _saveTimer?.cancel();
    _deletedDraftIds.addAll(selected);
    try {
      _acceptDrafts(await _store.delete(selected));
      if (selected.contains(asset.id)) saveStatus = '草稿已删除';
    } finally {
      await loadDrafts();
    }
  }

  Future<void> restore(Map<String, dynamic> draft) async {
    _saveTimer?.cancel();
    _activeDraftResources.add(draft);
    try {
      await saveDraft();
      await setAsset(
        PhotoAsset.fromJson(Map<String, dynamic>.from(draft['photo'] as Map)),
        restored: EditHistory.fromJson(
          Map<String, dynamic>.from(draft['history'] as Map),
        ),
        save: false,
      );
    } finally {
      _activeDraftResources.remove(draft);
    }
  }

  final _retiredFactoryDefinitions = Expando<bool>();
  EditDocument _currentFactoryMarks(EditDocument old) {
    if (!FactoryWatermarkCatalog.isFactoryId(old.packageId) ||
        old.xiaomiConfig == null) {
      return old;
    }
    final definition = old.xiaomiConfig!;
    final retired = _retiredFactoryDefinitions[definition] ??= (() {
      final source = jsonEncode(definition);
      return RegExp(r'leica(?:_p3|_black|_white)?\.webp').hasMatch(source) ||
          (source.contains('img_model') &&
              !source.contains('starmark_model_ink'));
    })();
    if (!retired) return old;
    final current = presets
        .where((style) => style.id == old.packageId)
        .firstOrNull;
    return current == null
        ? old
        : old.copyWith(xiaomiConfig: current.document.xiaomiConfig);
  }

  EditHistory _supportedHistory(EditHistory candidate) {
    // Drafts embed their native definition. Refresh retired bundled resources
    // without changing the saved photo, user controls, signatures or history.
    final refreshed = candidate.entries
        .map(
          (entry) => _withoutDeletedIcons(_currentFactoryMarks(entry.document)),
        )
        .toList();
    if (List.generate(
      refreshed.length,
      (index) =>
          !identical(refreshed[index], candidate.entries[index].document),
    ).any((changed) => changed)) {
      final json = candidate.toJson();
      json['entries'] = [
        for (var index = 0; index < refreshed.length; index++)
          {
            'label': candidate.entries[index].label,
            'document': refreshed[index].toJson(),
          },
      ];
      candidate = EditHistory.fromJson(json);
    }
    if (!candidate.entries.any(
      (e) => FactoryWatermarkCatalog.isRetiredId(e.document.packageId),
    )) {
      return candidate;
    }
    final fallback = presets
        .firstWhere((s) => s.id == 'factory-standard-8')
        .document;
    final migrated = candidate.toJson();
    migrated['entries'] = candidate.entries.map((entry) {
      final old = entry.document;
      if (!FactoryWatermarkCatalog.isRetiredId(old.packageId)) {
        return entry.toJson();
      }
      return {
        'label': entry.label,
        'document': fallback
            .copyWith(
              enabled: old.enabled,
              caption: old.caption,
              modelOverride: old.modelOverride,
              parametersOverride: old.parametersOverride,
              showModel: old.showModel,
              showParameters: old.showParameters,
              showBrand: old.showBrand,
            )
            .toJson(),
      };
    }).toList();
    inform('迪士尼模板已下线，已切换为标准水印；照片与编辑历史已保留');
    return EditHistory.fromJson(migrated);
  }

  Future<void> saveStyle(String name) async {
    if (name.trim().isEmpty) return;
    final preset = StylePreset(
      'user-${DateTime.now().microsecondsSinceEpoch}',
      name.trim(),
      'assets/samples/watermark_standard.webp',
      document,
    );
    if (directory == null) throw StateError('存储不可用');
    final pkg = await WatermarkPackageBuilder(
      watermarkRepository,
      modelCatalog,
    ).build(preset);
    userStyles.add(await watermarkRepository.install(pkg));
    inform('样式已保存到“自定义”');
  }

  Future<void> persistStyles() async {
    if (directory != null) userStyles = await watermarkRepository.list();
    notifyListeners();
  }

  Future<Map<String, dynamic>> export({
    PhotoAsset? photo,
    EditDocument? style,
    bool? staticOutput,
  }) async {
    if (busy) throw StateError('导出任务进行中');
    final a = photo ?? asset,
        d = StaruwNativeAdapter.project(
          withPresetModelTitle(
            effectiveDocument(style ?? document),
            modelCatalog,
          ),
        );
    staruwNeed(!d.staruwPreserved, 'E_CAPABILITY', '保留模式水印不能导出照片');
    final preserveMotion = !(staticOutput ?? staticPhotoExport);
    if (directory == null) throw StateError('应用存储不可用');
    final heldResources = {'photo': a.toJson(), 'document': d.toJson()};
    _activeDraftResources.add(heldResources);
    busy = true;
    exportHighResolution = needsTiledExport(
      a.width,
      a.height,
      a.width,
      a.height,
    );
    _exportProgress('preparing');
    ui.Image? full, blurImage, output;
    var blurToned = false;
    ui.Picture? recording;
    StaruwRenderPlan? scene;
    File? temp, hdrMask, photoBackground;
    TiledDecorationExport? tiledExport;
    XiaomiRenderPlan? native;
    try {
      await _prepareRegions(d);
      final sourcePath = await _nativeSource(a);
      final features = await NativeBridge.call<Map>('photo.features', {
        'path': sourcePath,
      });
      final requiresHdr =
          a.hdr ||
          features?['hdr'] == true ||
          features?['hdrUncertain'] == true ||
          (features?['hdr'] == null &&
              (a.hdrUncertain || features?['hdrPossible'] == true));
      final package = d.packageId.isNotEmpty
          ? await _loadPackage(d.packageId)
          : null;
      final resolvedFamily = d.isStaruwScene
          ? ''
          : await resolveFont(
              d.font.resolveFor(a.metadata),
              packageId: d.packageId,
              bindings: d.resourceBindings,
            );
      await _prepareLunarBindings(d, a);
      full = await decode(a.path, bundled: a.sample);
      if (d.enabled && d.isStaruwScene) {
        staruwNeed(package?.scene != null, 'E_RESOURCE', '缺少通用场景资源');
        scene = await StaruwRenderPlan.create(
          d,
          a,
          full,
          await _staruwResourcesFor(d, package!),
        );
      }
      native = !d.enabled || d.xiaomiConfig == null
          ? null
          : _makeNativePlan(d, a, full, package!, resolvedFamily, null);
      final layout =
          scene?.layout ?? native?.layout ?? d.layout(a.width, a.height);
      if (!layout.withinBudget) {
        throw const FormatException(
          '照片超过 2.1 亿像素、画布超过 5.2 亿像素或边长超过 32768；原图不会被修改',
        );
      }
      final tiled = needsTiledExport(
        layout.width,
        layout.height,
        a.width,
        a.height,
      );
      if (exportHighResolution != tiled) {
        exportHighResolution = tiled;
        notifyListeners();
      }
      if (d.enabled &&
          !d.isStaruwScene &&
          ((d.xiaomiConfig != null &&
                  XiaomiRenderPlan.backgroundNeedsBlur(
                    d.xiaomiConfig!,
                    d.xiaomiOptions,
                  )) ||
              (d.mode == WatermarkMode.frame &&
                  _isImageBackground(d.background)))) {
        final blurResult = await _requestBlur(
          {
            'path': await _nativeSource(a),
            'orientation': a.orientation,
            'width': layout.width,
            'height': layout.height,
            'radius': d.blur,
          },
          _blurTone(d),
          glass: _glassRequested(d),
          custom: d.xiaomiConfig == null,
        );
        final p = blurResult.$1!;
        blurToned = blurResult.$2;
        blurImage = await decode(p);
        await File(p).delete();
        if (native != null) {
          native = _makeNativePlan(
            d,
            a,
            full,
            package!,
            resolvedFamily,
            blurImage,
            backgroundToned: blurToned,
          );
        }
      }
      // Rebind the model SVG for each photo during batch export.
      final modelId = d.modelId.isEmpty
          ? resolveModelSvgId(a.metadata, modelCatalog)
          : d.modelId;
      final mappedModel =
          (d.resourceBindings['models'] as Map? ?? {})[modelId] as String?;
      final modelPicture = d.isStaruwScene
          ? null
          : mappedModel != null
          ? await _picture(
              'pkg:${d.packageId}:$modelId',
              watermarkRepository.resource(d.packageId, mappedModel).path,
              bundled: false,
            )
          : null;
      final brandId = d.isStaruwScene
          ? 'generic'
          : resolveBrand(a.metadata.make);
      final brandPicture = brandId == 'generic'
          ? null
          : (d.resourceBindings['brands'] as Map? ?? {})[brandId] != null
          ? await _picture(
              'pkg:${d.packageId}:brand-$brandId',
              watermarkRepository
                  .resource(
                    d.packageId,
                    (d.resourceBindings['brands'] as Map)[brandId] as String,
                  )
                  .path,
              bundled: false,
            )
          : null;
      if (d.isStaruw && native != null) {
        await native.prepareStaruw(await _staruwResourcesFor(d, package!));
      }
      _exportProgress('rendering');
      final recorder = ui.PictureRecorder(), canvas = ui.Canvas(recorder);
      final painter = WatermarkPainter(
        d,
        a,
        RenderAssets(
          full,
          background: blurImage,
          backgroundToned: blurToned,
          regionAssets: _regionArtwork,
          model: modelPicture,
          brand: brandPicture,
          family: resolvedFamily,
          fallbackFamily: _fallbackFamily(d),
          xiaomi: native,
          scene: scene,
          modelLockup: _modelLockup(d, a, package),
        ),
      );
      painter.paintDecorationLayer(
        canvas,
        ui.Size(layout.width.toDouble(), layout.height.toDouble()),
      );
      recording = recorder.endRecording();
      if (tiled) {
        ui.Picture? backgroundPicture;
        try {
          if (features?['sourceHasAlpha'] == true) {
            final backgroundRecorder = ui.PictureRecorder();
            painter.paintBackgroundLayer(
              ui.Canvas(backgroundRecorder),
              ui.Size(layout.width.toDouble(), layout.height.toDouble()),
            );
            backgroundPicture = backgroundRecorder.endRecording();
          }
          tiledExport = await TiledDecorationExport.create(
            parent: directory!,
            width: layout.width,
            height: layout.height,
            decoration: recording,
            background: backgroundPicture,
            onProgress: (done, total) {
              if (_disposed) throw StateError('导出已取消');
              _exportProgress('rendering');
            },
          );
          temp = tiledExport.manifest;
        } finally {
          backgroundPicture?.dispose();
        }
      } else {
        output = await recording.toImage(layout.width, layout.height);
        temp = File(
          '${directory!.path}/export-${DateTime.now().microsecondsSinceEpoch}.rgba',
        );
        await _writeExportPixels(output, temp);
        output.dispose();
        output = null;
      }
      recording.dispose();
      recording = null;
      if (!tiled && features?['sourceHasAlpha'] == true) {
        final backgroundRecorder = ui.PictureRecorder();
        painter.paintBackgroundLayer(
          ui.Canvas(backgroundRecorder),
          ui.Size(layout.width.toDouble(), layout.height.toDouble()),
        );
        final backgroundPicture = backgroundRecorder.endRecording();
        try {
          final image = await backgroundPicture.toImage(
            layout.width,
            layout.height,
          );
          try {
            final bytes = (await image.toByteData(
              format: ui.ImageByteFormat.png,
            ))!;
            photoBackground = File('${temp.path}.background.png');
            await photoBackground.writeAsBytes(
              bytes.buffer.asUint8List(
                bytes.offsetInBytes,
                bytes.lengthInBytes,
              ),
            );
          } finally {
            image.dispose();
          }
        } finally {
          backgroundPicture.dispose();
        }
      }
      if (requiresHdr) {
        final factor = math.min(
          1.0,
          1024 / math.max(layout.width, layout.height),
        );
        final maskWidth = math.max(1, (layout.width * factor).round());
        final maskHeight = math.max(1, (layout.height * factor).round());
        final maskRecorder = ui.PictureRecorder();
        painter.paintPhotoContribution(
          ui.Canvas(maskRecorder),
          ui.Size(maskWidth.toDouble(), maskHeight.toDouble()),
        );
        final maskPicture = maskRecorder.endRecording();
        try {
          final mask = await maskPicture.toImage(maskWidth, maskHeight);
          try {
            final bytes = (await mask.toByteData(
              format: ui.ImageByteFormat.png,
            ))!;
            hdrMask = File('${temp.path}.mask.png');
            await hdrMask.writeAsBytes(
              bytes.buffer.asUint8List(
                bytes.offsetInBytes,
                bytes.lengthInBytes,
              ),
            );
          } finally {
            mask.dispose();
          }
        } finally {
          maskPicture.dispose();
        }
      }
      full.dispose();
      full = null;
      blurImage?.dispose();
      blurImage = null;
      _exportProgress('saving');
      final result = Map<String, dynamic>.from(
        (await NativeBridge.call<Map>('saveJpeg', {
          'path': temp.path,
          'pixelFormat': tiled ? 'tiled-png' : 'rgba8888',
          'photoLayerSeparated': true,
          'linearComposition': scene != null,
          'photoBackgroundPath': ?photoBackground?.path,
          'width': layout.width,
          'height': layout.height,
          'sourcePath': sourcePath,
          'sourceName': ?a.originalName,
          'photoX': layout.photoX,
          'photoY': layout.photoY,
          'photoWidth': layout.photoWidth,
          'photoHeight': layout.photoHeight,
          'sourceOrientation': a.orientation,
          'hdrMaskPath': ?hdrMask?.path,
          'hdrExpected': requiresHdr,
          'preserveMotion': preserveMotion,
          'documentJson': jsonEncode(d.toJson()),
          'quality': 100,
        }))!,
      );
      message = '已保存 ${result['width']} × ${result['height']} 到相册';
      if (writesDrafts && !testMode) {
        _acceptDrafts(await _store.completeEditing());
      }
      return result;
    } finally {
      scene?.dispose();
      native?.disposeStaruw();
      output?.dispose();
      recording?.dispose();
      full?.dispose();
      blurImage?.dispose();
      try {
        await tiledExport?.dispose();
        if (temp != null && await temp.exists()) await temp.delete();
        if (hdrMask != null && await hdrMask.exists()) await hdrMask.delete();
        if (photoBackground != null && await photoBackground.exists()) {
          await photoBackground.delete();
        }
      } finally {
        _activeDraftResources.remove(heldResources);
        busy = false;
        exportStage = null;
        exportHighResolution = false;
        notifyListeners();
      }
    }
  }

  Future<void> _writeExportPixels(ui.Image image, File target) async {
    // Keep the full-resolution buffer out of the native JPEG/EXIF await scope.
    final bytes = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
    if (bytes == null) throw StateError('导出像素读取失败');
    await target.writeAsBytes(
      bytes.buffer.asUint8List(bytes.offsetInBytes, bytes.lengthInBytes),
    );
  }

  @override
  void dispose() {
    _disposed = true;
    _fontIntent++;
    _resourcesToken++;
    _scenePlan?.dispose();
    _plan?.disposeStaruw();
    for (final variant in _staruwVariants.values) {
      variant.dispose();
    }
    _staruwVariants.clear();
    _saveTimer?.cancel();
    if (background != null && !identical(background, preview)) {
      background!.dispose();
    }
    preview?.dispose();
    for (final p in _models.values) {
      p.picture.dispose();
    }
    for (final package in _packages.values) {
      package.dispose();
    }
    _markAssets?.dispose();
    for (final art in _regionArtwork.values) {
      art.dispose();
    }
    super.dispose();
  }

  @override
  void notifyListeners() {
    if (!_disposed) super.notifyListeners();
  }
}

class _PackageResources {
  StaruwRenderResources? scene;
  ModelLockupAssets? marks;
  final Map<String, ui.Image> images = {};
  final Map<String, RegionArtwork> vectors = {};
  final Map<String, Rect> imageContentBounds = {};
  final Map<String, String> fonts = {}, fontHashes = {}, localizations = {};
  void dispose() {
    scene?.dispose();
    marks?.dispose();
    for (final image in images.values) {
      image.dispose();
    }
    images.clear();
    for (final vector in vectors.values) {
      vector.dispose();
    }
    vectors.clear();
  }
}
