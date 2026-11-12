import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:watermark_engine/watermark_engine.dart';
import '../services/staruw/contract.dart';
import '../services/staruw/controls.dart';
import '../editor/editor_controller.dart';
import '../editor/region_replacements.dart';
import '../editor/metadata_overrides.dart';
import '../editor/xiaomi_capabilities.dart';
import '../editor/xiaomi_renderer.dart';
import '../services/photo_asset.dart';
import '../services/template_archive.dart';
import '../services/builtin_emblems.dart';
import '../services/region_assets.dart';
import '../editor/global_parameter_icon.dart';
import '../services/native_bridge.dart';
import '../services/app_updates.dart';

/// The native shell owns presentation; this bridge owns editor commands/state.
class NativeUiBridge extends ChangeNotifier {
  NativeUiBridge(this.photoEditor, {AppUpdateService? updateService})
    : _updateService = updateService ?? AppUpdateService() {
    photoEditor.liveDraftReferences = () => [
      for (final photo in _batch) photo.toJson(),
      if (_templateEditor != null) _templateEditor!.history.toJson(),
    ];
    _lastEditorMessage = editor.message;
    _message = editor.message ?? '';
    editor.addListener(_editorChanged);
  }

  static const channel = MethodChannel('dev.starmark/ui');
  final EditorController photoEditor;
  final AppUpdateService _updateService;
  bool _checkingUpdate = false, _openingUpdate = false;
  AvailableAppUpdate? _availableUpdate;
  EditorController? _templateEditor;
  EditorController get editor => _templateEditor ?? photoEditor;
  StylePreset? _editingTemplate;
  String? _initialTemplate;
  bool _openingTemplate = false;
  String _templateReturnPage = 'watermarks';
  Future<void> _openTemplate(StylePreset style) async {
    staruwNeed(
      !style.document.staruwPreserved,
      'E_CAPABILITY',
      '此水印仅保留原包，可长按导出',
    );
    _templateReturnPage = {'watermarks', 'styles'}.contains(_page)
        ? _page
        : 'watermarks';
    _openingTemplate = true;
    await photoEditor.templatePreferences?.flush();
    final next = EditorController(
      testMode: photoEditor.testMode,
      writesDrafts: false,
      remembersDesign: false,
    );
    try {
      next.useXiaomiImageBlur = photoEditor.useXiaomiImageBlur;
      next.useXiaomiGlassWatermark = photoEditor.useXiaomiGlassWatermark;
      final document =
          await photoEditor.templatePreferences?.remembered(
            style.document.packageId.isEmpty
                ? style.id
                : style.document.packageId,
            style.document,
          ) ??
          style.document;
      await next.initializeTemplateFrom(photoEditor, document);
    } catch (_) {
      _openingTemplate = false;
      next.dispose();
      rethrow;
    }
    if (_disposed || !_openingTemplate) {
      next.dispose();
      return;
    }
    _openingTemplate = false;
    photoEditor.removeListener(_editorChanged);
    _templateEditor = next;
    _editingTemplate = style;
    _initialTemplate = jsonEncode(next.document.toJson());
    next.addListener(_editorChanged);
    _page = 'templateEditor';
  }

  void _closeTemplate() {
    final old = _templateEditor;
    if (old == null) return;
    old.removeListener(_editorChanged);
    _templateEditor = null;
    _editingTemplate = null;
    old.dispose();
    photoEditor.addListener(_editorChanged);
    _page = _templateReturnPage;
  }

  final _gate = NativeInteractionGate();
  final _batch = <PhotoAsset>[];
  final _selected = <String>{};
  final _selectedDrafts = <String>{};
  final _selectedWatermarks = <String>{};
  bool _selectingWatermarks = false;
  bool _selectingDrafts = false;
  String _page = 'home', _message = '', _pickerReturnPage = 'editor';
  String? _lastEditorMessage, _settingsDirectory;
  int _messageId = 0, _completed = 0, _total = 0, _failed = 0;
  bool _attached = false, _disposed = false, _operationBusy = false;
  bool _statePending = false, _stateScheduled = false, _stateSending = false;
  bool _batchRunning = false, _cancelled = false;
  bool _importPending = false;
  String? _operationTitle;
  int _operationId = 0;
  String? _importStage;
  bool _styleImportCancelled = false;
  Map<String, dynamic>? _styleImportProgress, _styleImportResult;
  Future<void> _settingsTail = Future.value();
  Map<String, dynamic> _settings = {
    'theme': 'system',
    'glass': true,
    'glassMode': 'auto',
    'glassWatermark': false,
    'xiaomiImageBlur': true,
    'reduceMotion': false,
    'staticPhotoExport': false,
    'autoRemoveWatermark': false,
    'replaceDefaultParameterIcon': false,
    'defaultParameterIcon': builtinEmblems.first['asset'],
    'defaultParameterIconShape': 'square',
  };
  List<Map<String, dynamic>> _parameterIcons = [];

  bool get busy => _operationBusy || _batchRunning || editor.busy;

  Map<String, dynamic> get snapshot {
    final doc = editor.effectiveDocument();
    final document = _copy(doc.toJson())
      ..remove('xiaomiConfig')
      ..remove('resourceBindings')
      ..remove('staruwConfig')
      ..remove('staruwReport');
    if (doc.xiaomiConfig != null) {
      document['xiaomiOptions'] = effectiveMetadataOptions(
        editor.asset.metadata,
        doc.xiaomiOptions,
      );
    }
    final layout = editor.layout;
    return {
      'template': {
        'active': _templateEditor != null,
        'id': _editingTemplate?.id,
        'title': _editingTemplate?.title,
        'dirty':
            _templateEditor != null &&
            _initialTemplate != jsonEncode(editor.document.toJson()),
      },
      'version': 1,
      'page': _page,
      'returnPage': _templateEditor != null
          ? _templateReturnPage
          : _page == 'watermarks'
          ? _pickerReturnPage
          : 'home',
      'ready': !editor.loading && editor.renderAssets != null,
      'busy': busy,
      'appUpdate': {
        'checking': _checkingUpdate,
        'opening': _openingUpdate,
        'available': _availableUpdate != null,
        'versionName': _availableUpdate?.versionName ?? '',
      },
      'operation': {
        'active': _operationBusy && _operationTitle != null,
        'title': _operationTitle,
        'id': _operationId,
      },
      'import': {
        'active': _importStage != null,
        'stage': _importStage,
        ...?_styleImportProgress,
        'result': _styleImportResult,
      },
      'export': {
        'active': editor.exportStage != null,
        'stage': editor.exportStage,
        'single': !_batchRunning,
        'highResolution': editor.exportHighResolution,
      },
      'revision': editor.revision,
      'materialRevision': editor.nativeMaterialRevision,
      'message': _message,
      'messageId': _messageId,
      'saveStatus': editor.saveStatus,
      'modelWarnings':
          editor.renderAssets?.modelLockup?.warnings ?? const <String>[],
      'photo': {
        ...editor.asset.toJson(),
        'parameters': editor.asset.metadata.parameters,
        'displayFocal': editor.asset.metadata.displayFocal,
      },
      'layout': {
        'width': layout.width,
        'height': layout.height,
        'photoX': layout.photoX,
        'photoY': layout.photoY,
        'photoWidth': layout.photoWidth,
        'photoHeight': layout.photoHeight,
      },
      'document': document,
      'staruw': _staruwState(doc),
      'styles': [
        for (final style in editor.allStyles)
          {
            'id': style.id,
            'title': style.title,
            'image': style.image,
            'stripImage': style.image,
            'mode': style.document.mode.name,
            'custom': editor.userStyles.any((s) => s.id == style.id),
            'native': style.document.xiaomiConfig != null,
            'staruw': style.document.isStaruw,
            'preserve': style.document.staruwPreserved,
            if (style.document.isStaruw)
              'compatibility': style.document.staruwReport,
            'factory': style.factory,
            'checked': _selectedWatermarks.contains(style.id),
            'category': style.category.isNotEmpty ? style.category : 'custom',
            'categoryTitle': style.categoryTitle.isNotEmpty
                ? style.categoryTitle
                : '自定义水印',
            'selected': doc.packageId.isNotEmpty
                ? doc.packageId == style.document.packageId
                : jsonEncode(doc.toJson()) ==
                      jsonEncode(style.document.toJson()),
          },
      ],
      'drafts': [for (final draft in editor.drafts) _draftRow(draft)],
      'homeResume': _homeResume(),
      'watermarkSelection': {
        'active': _selectingWatermarks,
        'selectedCount': _selectedWatermarks.length,
        'allSelected':
            editor.allStyles.isNotEmpty &&
            _selectedWatermarks.length == editor.allStyles.length,
        'canDelete':
            _selectedWatermarks.isNotEmpty &&
            _selectedWatermarks.every(
              (id) => editor.userStyles.any(
                (style) => style.id == id && !style.factory,
              ),
            ),
      },
      'draftSelection': {
        'active': _selectingDrafts,
        'selectedCount': _selectedDrafts.length,
        'allSelected':
            editor.drafts.isNotEmpty &&
            _selectedDrafts.length == editor.drafts.length,
      },
      'fonts': {
        'bundled': [
          for (final f in bundledFonts.where((f) => f.selectable))
            {
              'id': f.id,
              'title': f.title,
              'source': 'bundled',
              'asset': f.asset,
              'weight': f.weight,
              'axes': f.axes,
            },
        ],
        'imported': [for (final f in editor.importedFonts) _copy(f)],
        'effective': editor.effectiveFont.toJson(),
        'automatic': doc.font.automatic,
      },
      'models': [for (final model in editor.modelCatalog) _copy(model)],
      'native': {..._nativeState(doc), ...editor.alignmentState},
      'regions': {
        ...regionSupport(doc),
        'marks': builtinEmblems,
        'globalDefault': editor.usesGlobalParameterIcon,
      },
      'parameterIcons': _parameterIcons,
      'history': {
        'cursor': editor.history.cursor,
        'canUndo': editor.history.canUndo,
        'canRedo': editor.history.canRedo,
        'entries': [
          for (var i = 0; i < editor.history.entries.length; i++)
            {'index': i, 'label': editor.history.entries[i].label},
        ],
      },
      'batch': {
        'items': [
          for (final photo in _batch)
            {
              'id': photo.id,
              'title': _photoTitle(photo),
              'image': photo.preview,
              'width': photo.width,
              'height': photo.height,
              'selected': _selected.contains(photo.id),
            },
        ],
        'selectedCount': _selected.length,
        'completed': _completed,
        'total': _total,
        'failed': _failed,
        'running': _batchRunning,
        'cancelled': _cancelled,
      },
      'settings': Map<String, dynamic>.from(_settings),
    };
  }

  static Map<String, dynamic> _copy(Map value) =>
      Map<String, dynamic>.from(jsonDecode(jsonEncode(value)) as Map);

  static String _photoTitle(PhotoAsset photo) => photo.sample
      ? '示例照片'
      : photo.metadata.model.isEmpty
      ? '未命名照片'
      : photo.metadata.model;

  Map<String, dynamic> _homeResume() {
    final editor = photoEditor;
    // A cold start has the bundled example in the editor. Preview the newest
    // draft without decoding/restoring its full image until Continue is tapped.
    // Older builds autosave the pristine bundled demo on lifecycle changes.
    // It must not displace a real photo or an intentionally edited demo.
    final recent =
        editor.drafts.where((draft) {
          final photo = draft['photo'] as Map;
          final history = draft['history'] as Map;
          return photo['sample'] != true ||
              (history['entries'] as List).length > 1;
        }).firstOrNull ??
        editor.drafts.firstOrNull;
    final draft =
        editor.asset.sample &&
            !editor.history.canUndo &&
            !editor.history.canRedo
        ? recent
        : null;
    final useDraft = draft != null;
    final photo = useDraft ? draft['photo'] as Map : editor.asset.toJson();
    var packageId = editor.document.packageId;
    if (useDraft) {
      final history = draft['history'] as Map;
      final entries = history['entries'] as List;
      final cursor = (history['cursor'] as num? ?? 0).toInt().clamp(
        0,
        entries.length - 1,
      );
      packageId =
          (entries[cursor] as Map)['document']['packageId'] as String? ?? '';
    }
    final style = editor.allStyles
        .where((s) => s.document.packageId == packageId && packageId.isNotEmpty)
        .firstOrNull;
    return {
      'draftId': useDraft ? draft['id'] : '',
      'image': useDraft || editor.hasPhoto ? photo['preview'] : '',
      'watermarkPreview': style?.image ?? '',
    };
  }

  Map<String, dynamic> _draftRow(Map<String, dynamic> draft) {
    final raw = draft['photo'];
    final photo = raw is Map
        ? PhotoAsset.fromJson(Map<String, dynamic>.from(raw))
        : editor.asset;
    return {
      'id': draft['id'] ?? photo.id,
      'title': _photoTitle(photo),
      'image': photo.preview,
      'updated': draft['updated'] ?? '',
      'selected': _selectedDrafts.contains(draft['id'] ?? photo.id),
    };
  }

  static const _names = {
    'bottom_middle': '底部居中',
    'bottom_left': '左下角',
    'bottom_right': '右下角',
    'top_left': '左上角',
    'top_middle': '顶部居中',
    'top_right': '右上角',
    'center_left': '左侧居中',
    'center_middle': '居中',
    'center_right': '右侧居中',
    'center_top': '上方居中',
    'outer_border_left': '左侧外框',
    'outer_border_right': '右侧外框',
    'bottom': '底部',
    'top': '顶部',
    'center': '居中',
    'inner_border_right': '右侧内框',
    'inner_border_left': '左侧内框',
    'layout': '默认布局',
    'layout_loc_off': '隐藏位置布局',
    'white': '纯白',
    'black': '纯黑',
    'dark_blur': '暗色模糊',
    'light_blur': '亮色模糊',
    'noise_blur': '颗粒模糊',
    'layout_right_tennis': '右侧网球',
    'layout_left_tennis': '左侧网球',
  };

  Map<String, dynamic> _nativeState(EditDocument doc) {
    final config = doc.xiaomiConfig;
    if (config == null) {
      return {
        'enabled': false,
        'supportsCaption': true,
        'supportsModel': true,
        'supportsParameters': true,
        'parameterFields': {
          for (final key in photoParameterValues(editor.asset.metadata).keys)
            key: true,
        },
        'supportsBrand': true,
        'supportsTime': false,
        'supportsLocation': false,
        'supportsScale': true,
        'supportsPosition': true,
        'supportsOpacity': true,
        'supportsColor': true,
        'supportsBlur': true,
        'supportsModelToggle': true,
        'supportsParametersToggle': true,
        'supportsBrandToggle': true,
        'needsBlur':
            doc.mode == WatermarkMode.frame &&
            [
              BackgroundStyle.darkBlur,
              BackgroundStyle.lightBlur,
              BackgroundStyle.darkGlass,
              BackgroundStyle.lightGlass,
            ].contains(doc.background),
        'layouts': <Map<String, dynamic>>[],
        'backgrounds': <Map<String, dynamic>>[],
        'canChooseStyle': false,
        'groupIndex': 0,
        'backgroundName': '',
      };
    }
    final options = doc.xiaomiOptions;
    final capabilities = XiaomiCapabilities.fromConfig(
      config,
      options,
      editor.watermarkImageNames,
    );
    final glassBackgrounds = editor.useXiaomiGlassWatermark
        ? capabilities.backgroundChoices
              .where(
                (row) =>
                    '${row['value']}'.contains('dark_blur') ||
                    '${row['value']}'.contains('light_blur'),
              )
              .toList()
        : <Map<String, dynamic>>[];
    final location = _locationControl(config, options);
    final parameterFields = {
      for (final entry in photoParameterValues(editor.asset.metadata).entries)
        entry.key: capabilities.canEditParameters || entry.value.isEmpty,
    };
    return {
      'enabled': true,
      'supportsCaption': capabilities.canEditCaption,
      'supportsModel': true,
      'supportsParameters': parameterFields.values.any((enabled) => enabled),
      'parameterFields': parameterFields,
      'supportsBrand': capabilities.canToggleBrand,
      'supportsModelToggle': capabilities.canToggleModel,
      'supportsParametersToggle': capabilities.canToggleParameters,
      'supportsBrandToggle': capabilities.canToggleBrand,
      'supportsTime': capabilities.canToggleTime,
      'supportsLocation': capabilities.canToggleLocation,
      ...location,
      'supportsScale': capabilities.supportsScale,
      'scale': capabilities.normalizeDocument(doc).scale,
      'supportsPosition': false,
      'supportsOpacity': false,
      'supportsColor': false,
      'supportsBlur': false,
      'needsBlur': XiaomiRenderPlan.backgroundNeedsBlur(config, options),
      'supportsGlassBackground': capabilities.allowsOption('glassBackground'),
      'canChooseStyle':
          capabilities.backgroundChoices.length + glassBackgrounds.length > 1 ||
          capabilities.customizeSettings.any(
            (setting) =>
                (setting['options'] as List? ?? []).length +
                    (setting['supportOff'] == true ? 1 : 0) >
                1,
          ),
      'layouts': [
        for (final choice in capabilities.layoutChoices)
          {
            'value': choice.groupIndex,
            'layout': choice.name,
            'label': _names[choice.name] ?? '布局 ${choice.groupIndex + 1}',
          },
      ],
      'patterns': [
        for (final setting in capabilities.customizeSettings)
          {
            'id': setting['id'],
            'label':
                setting['label'] ??
                editor.watermarkLabel('${setting['name'] ?? ''}', '图形'),
            'selected':
                _map(options['customizeSettings'] ?? {})[setting['id']] ??
                setting['default'] ??
                ((setting['options'] as List? ?? []).firstOrNull
                    as Map?)?['id'],
            'options': [
              if (setting['supportOff'] == true)
                {'value': 'off', 'label': '关闭'},
              for (final (index, row)
                  in (setting['options'] as List? ?? []).indexed)
                {
                  'value': (row as Map)['id'],
                  'label': _optionLabel(
                    row,
                    _optionNames[(row['sub_options'] as List? ?? [])
                            .firstOrNull] ??
                        '图案 ${index + 1}',
                  ),
                  ..._optionVisuals(row),
                },
            ],
          },
      ],
      'mixFields': [
        for (final (index, field) in capabilities.mixFields.indexed)
          {
            'id': field.id,
            'label': '内容 ${index + 1}',
            'type': field.selectedType(
              options,
              showParameters: doc.showParameters,
            ),
            'text': field.state(options)['text'] ?? '',
            'types': [
              for (final type in field.types)
                {
                  'value': type,
                  'label': const {
                    'off': '关闭',
                    'time': '拍摄时间',
                    'location_latlng': '经纬度',
                    'exif': '拍摄参数',
                    'custom_text': '自定义文字',
                  }[type],
                },
            ],
          },
      ],
      'backgrounds': [
        for (final (index, row) in capabilities.backgroundChoices.indexed)
          {
            'value': row['name'].toString(),
            'label': _optionLabel(
              row,
              _optionNames[row['name']] ??
                  _names[row['name']] ??
                  '款式 ${index + 1}',
            ),
            ..._optionVisuals(row),
            'glass': false,
          },
        for (final row in glassBackgrounds)
          {
            'value': row['name'].toString(),
            'label': '${row['value']}'.contains('dark_blur') ? '黑透玻璃' : '白透玻璃',
            ..._optionVisuals(row),
            'glass': true,
          },
      ],
      'glassBackground':
          editor.useXiaomiGlassWatermark && options['glassBackground'] == true,
      'groupIndex': capabilities.groupIndex,
      'backgroundName':
          XiaomiRenderPlan.selectedBackground(
            config,
            options,
          )?['name']?.toString() ??
          '',
    };
  }

  static const _optionNames = <String, String>{
    'texture_black': '纹理黑',
    'texture_white': '纹理白',
    'texture_rose_gray': '玫瑰灰',
    'texture_blue_gray_light': '浅蓝灰',
    'texture_blue_gray_dark': '深蓝灰',
    'texture_blue_gray': '蓝灰',
    'texture_green_dark': '深绿',
    'texture_green_gray_light': '浅绿灰',
    'texture_brown_gray': '棕灰',
    'gradient_color_red': '渐变红',
    'gradient_color_yellow': '渐变黄',
    'gradient_color_blue_dark': '孔雀蓝',
    'gradient_color_blue_light': '晴朗蓝',
    'wm_background_blue': '运动蓝',
    'running': '跑步',
    'icon_running': '跑步',
    'pingPong': '乒乓球',
    'icon_tableTennis': '乒乓球',
    'swim': '游泳',
    'icon_swimming': '游泳',
    'diving': '跳水',
    'icon_diving': '跳水',
    'shooting': '射击',
    'icon_shooting': '射击',
    'badminton': '羽毛球',
    'icon_badminton': '羽毛球',
    'basketball': '篮球',
    'icon_basketBall': '篮球',
    'volleyball': '排球',
    'icon_volleyball': '排球',
    'tennis': '网球',
    'icon_tennis': '网球',
    'football': '足球',
    'gymnastics': '体操',
    'icon_gymnastics': '体操',
    'red_national': '国庆',
    'red_76': '周年',
    'yellow': '中秋',
    'wm_background_rabbit_1': '玉兔一',
    'wm_background_rabbit_2': '玉兔二',
    'wm_background_moon': '明月',
    'red': '红色',
    'black1': '黑色',
    'green': '绿色',
    'white_tree': '白底圣诞树',
    'white_merry': '白底祝福',
    'red_tree': '红底圣诞树',
    'green_merryChristmas': '圣诞绿',
    'red_merryChristmas': '圣诞红',
    'white_merryChristmas': '圣诞白',
    'FF8F0403': '醒狮',
    'FFFFFFFF': '游鱼',
    'fireworks': '烟花',
    'tangyuan': '汤圆',
    'golden': '金色',
  };

  String _optionLabel(Map row, String fallback) {
    final description = row['description'];
    if (description is String && description.isNotEmpty) {
      final translated = description.startsWith('@')
          ? editor.watermarkLabel(description, '')
          : description;
      if (translated.isNotEmpty) return translated;
    }
    return editor.watermarkLabel('${row['name'] ?? ''}', fallback);
  }

  Map<String, dynamic> _optionVisuals(Map row) {
    final icon = '${row['icon'] ?? ''}';
    final image = editor.watermarkResourcePath(icon);
    final darkImage = editor.watermarkResourcePath('${row['icon_dark'] ?? ''}');
    final value = '${row['value'] ?? ''}';
    final colorMatch = RegExp(
      r'(?:^|;)@color=#([0-9a-fA-F]{8}|[0-9a-fA-F]{6})(?:;|$)',
    ).firstMatch(value);
    final colorText = colorMatch?.group(1);
    final color = colorText == null
        ? null
        : int.parse(
            colorText.length == 6 ? 'FF$colorText' : colorText,
            radix: 16,
          );
    // Generic OEM color icons do not necessarily match the template's literal
    // color. Texture and artwork icons remain the original bitmap.
    final swatch =
        color != null &&
        RegExp(r'^@color=#[0-9a-fA-F]{6,8}$').hasMatch(value) &&
        (image == null ||
            RegExp(
              r'^@img=icon_background_(?:white|black|blue|red|green)\.webp$',
            ).hasMatch(icon));
    return {
      'imagePath': ?image,
      'blurTone': ?RegExp(
        r'@algo=(light|dark)_blur(?:;|$)',
      ).firstMatch(value)?.group(1),
      'darkImagePath': ?darkImage,
      'color': ?color,
      'useColorSwatch': swatch,
    };
  }

  /// The optional location control follows the selected layout's bindings.
  /// An unresolved address falls back to the photo's real GPS coordinates.
  Map<String, dynamic> _locationControl(
    Map<String, dynamic> config,
    Map<String, dynamic> options,
  ) {
    var coordinates = false, address = false;
    void visit(dynamic value, bool optionalLocation) {
      if (value is List) {
        for (final child in value) {
          visit(child, optionalLocation);
        }
      } else if (value is Map && value['enable'] != false) {
        final binding = '${value['text'] ?? ''}';
        final optional =
            optionalLocation ||
            (value['optional'] == true &&
                (value['setting_optional_type'] == 'type_location' ||
                    binding.startsWith('@location=')));
        if (optional && binding.startsWith('@location=')) {
          if (binding.contains('latlng')) {
            coordinates = true;
          } else {
            address = true;
          }
        }
        visit(value['childViews'], optional);
      }
    }

    visit(XiaomiRenderPlan.selectedLayouts(config, options), false);
    coordinates |= address;
    final available = coordinates && _hasPhotoCoordinates;
    return {
      'locationKind': coordinates ? 'coordinates' : 'address',
      'locationAvailable': available,
      'locationUnavailableReason': available
          ? null
          : coordinates
          ? '照片没有经纬度'
          : '照片没有地址',
    };
  }

  // OEM a.I/g0 count an enabled setting group as one item; ordinary layouts
  // recurse into their leaves. The source photograph never counts as an item.
  bool _hasEnabledWatermarkItem(Map<String, dynamic> config, EditDocument doc) {
    bool visit(dynamic value) {
      if (value is List) return value.any(visit);
      if (value is! Map || value['enable'] == false) return false;
      final text = '${value['text'] ?? ''}',
          setting = '${value['setting_optional_type'] ?? ''}';
      if (value['optional'] == true &&
          ((setting == 'type_time' || text.startsWith('@time=')) &&
                  doc.xiaomiOptions['showTime'] == false ||
              (setting == 'type_location' || text.startsWith('@location=')) &&
                  doc.xiaomiOptions['showLocation'] == false ||
              (setting == 'type_exif' || text.startsWith('@exif=')) &&
                  !doc.showParameters ||
              (setting == 'type_model' || text.startsWith('@model=')) &&
                  !doc.showModel)) {
        return false;
      }
      if (value['type'] == 'Layout') {
        return setting.isNotEmpty || visit(value['childViews']);
      }
      if (value['id'] == 'src_image_view' || value['path'] == 'source_image') {
        return false;
      }
      if (text.startsWith('@mix=')) {
        return XiaomiMixField(Map<String, dynamic>.from(value)).selectedType(
              doc.xiaomiOptions,
              showParameters: doc.showParameters,
            ) !=
            'off';
      }
      return value['type'] == 'TextView' || value['type'] == 'ImageView';
    }

    return visit(XiaomiRenderPlan.selectedLayouts(config, doc.xiaomiOptions));
  }

  bool get _hasPhotoCoordinates {
    final metadata = editor.asset.metadata;
    final lat = metadata.latitude, lng = metadata.longitude;
    return lat != null &&
        lng != null &&
        lat.isFinite &&
        lng.isFinite &&
        lat >= -90 &&
        lat <= 90 &&
        lng >= -180 &&
        lng <= 180;
  }

  /// Attach before editor initialization so a native sync is never dropped.
  void attach() {
    if (_attached || _disposed) return;
    _attached = true;
    channel.setMethodCallHandler((call) async {
      if (call.method == 'import.progress') {
        if (_importPending &&
            ['reading', 'preparing'].contains(call.arguments)) {
          _importStage = call.arguments as String;
          publish();
        }
        return null;
      }
      if (call.method != 'command') return _failure('未知界面方法');
      try {
        if (call.arguments is! Map) throw const FormatException('无效命令');
        return await handleCommand(
          Map<String, dynamic>.from(call.arguments as Map),
        );
      } catch (error) {
        return _failure(_errorText(error));
      }
    });
    publish();
  }

  Future<void> initialize() async {
    final directory = editor.directory;
    if (directory == null || directory.path == _settingsDirectory) return;
    _settingsDirectory = directory.path;
    for (final name in [
      'ui-settings.json',
      'ui-settings.json.bak',
      'settings.json',
    ]) {
      final file = File('${directory.path}/$name');
      try {
        if (!await file.exists()) continue;
        final raw = _copy(jsonDecode(await file.readAsString()) as Map);
        if (!raw.containsKey('theme') && raw['dark'] is bool) {
          raw['theme'] = raw['dark'] == true ? 'dark' : 'light';
        }
        final next = _validatedSettings({
          for (final key in [
            'theme',
            'glass',
            'glassMode',
            'glassWatermark',
            'xiaomiImageBlur',
            'reduceMotion',
            'staticPhotoExport',
            'autoRemoveWatermark',
            'replaceDefaultParameterIcon',
            'defaultParameterIcon',
            'defaultParameterIconShape',
          ])
            if (raw.containsKey(key)) key: raw[key],
        });
        _settings = next;
        await editor.setXiaomiImageBlur(next['xiaomiImageBlur'] == true);
        await editor.setXiaomiGlassWatermark(next['glassWatermark'] == true);
        editor.staticPhotoExport = next['staticPhotoExport'] == true;
        editor.autoRemoveWatermark = next['autoRemoveWatermark'] == true;
        await _applyParameterIconSetting(next);
        publish();
        return;
      } catch (_) {
        // A complete backup remains usable after interrupted writes.
      }
    }
    if (editor.dark) _settings['theme'] = 'dark';
    publish();
  }

  void _editorChanged() {
    if (_disposed) return;
    final watermarkIds = photoEditor.allStyles.map((style) => style.id).toSet();
    _selectedWatermarks.removeWhere((id) => !watermarkIds.contains(id));
    if (watermarkIds.isEmpty) _selectingWatermarks = false;
    final draftIds = photoEditor.drafts.map((draft) => draft['id']).toSet();
    _selectedDrafts.removeWhere((id) => !draftIds.contains(id));
    if (draftIds.isEmpty) _selectingDrafts = false;
    _gate.invalidateUnlessRevision(editor.revision);
    if (editor.message != null && editor.message != _lastEditorMessage) {
      _message = editor.message!;
      _messageId++;
    }
    _lastEditorMessage = editor.message;
    publish();
  }

  void publish() {
    if (_disposed) return;
    notifyListeners();
    if (!_attached) return;
    _statePending = true;
    if (_stateScheduled || _stateSending) return;
    _stateScheduled = true;
    scheduleMicrotask(() {
      _stateScheduled = false;
      unawaited(_drainState());
    });
  }

  Future<void> _drainState() async {
    if (_stateSending || _disposed) return;
    _stateSending = true;
    try {
      while (_statePending && !_disposed) {
        _statePending = false;
        await _sendState();
      }
    } finally {
      _stateSending = false;
    }
  }

  Future<void> _sendState() async {
    try {
      await channel.invokeMethod<void>('state', snapshot);
    } on MissingPluginException {
      // The pure Dart test runner has no native shell.
    } on PlatformException {
      // The Activity may be recreating; its next sync receives a full snapshot.
    }
  }

  void cancelInteraction() {
    _gate.clear();
    editor.cancelPendingFont();
    editor.cancel();
  }

  Map<String, dynamic> _failure(String message) {
    _message = message;
    _messageId++;
    publish();
    return {'ok': false, 'message': message};
  }

  static String _errorText(Object error) => error is PlatformException
      ? error.message ?? '系统操作失败'
      : error is FormatException
      ? error.message
      : error.toString();

  Future<void> _checkForUpdates() async {
    if (_checkingUpdate || _openingUpdate) return;
    if (_page != 'settings') throw const FormatException('请在设置页检查更新');
    _checkingUpdate = true;
    _availableUpdate = null;
    publish();
    try {
      final raw = await NativeBridge.call<Map>('app.updateInfo');
      if (_disposed) return;
      if (raw == null) throw const FormatException('无法读取当前安装版本');
      final update = await _updateService.check(
        InstalledAppVersion.fromNative(raw),
      );
      if (_disposed || _page != 'settings') return;
      _availableUpdate = update;
      if (update == null) {
        _message = '已是最新版本';
        _messageId++;
      }
    } on UpdateCheckException catch (error) {
      if (!_disposed && _page == 'settings') _failure(error.userMessage);
    } catch (_) {
      if (!_disposed && _page == 'settings') _failure('检查更新失败，请稍后重试');
    } finally {
      if (!_disposed) {
        _checkingUpdate = false;
        publish();
      }
    }
  }

  Future<void> _openUpdate() async {
    if (_openingUpdate) return;
    final update = _availableUpdate;
    if (_page != 'settings' || update == null) {
      throw const FormatException('请先检查更新');
    }
    _openingUpdate = true;
    publish();
    try {
      // The command carries no URL: only the checked, currently offered asset
      // can reach the browser after the user confirms this dialog.
      await NativeBridge.call<void>('app.openUpdate', {
        'url': update.downloadUrl.toString(),
      });
      if (!_disposed && identical(_availableUpdate, update)) {
        _availableUpdate = null;
      }
    } finally {
      if (!_disposed) {
        _openingUpdate = false;
        publish();
      }
    }
  }

  Future<void> _photoImport(Future<void> Function() action) =>
      _exclusive(() async {
        _importPending = true;
        _styleImportResult = null;
        _styleImportProgress = null;
        try {
          await action();
        } finally {
          _importPending = false;
          _importStage = null;
          publish();
        }
      }, title: null);

  Future<T> _exclusive<T>(
    Future<T> Function() action, {
    String? title = '正在处理…',
  }) async {
    _operationBusy = true;
    _operationTitle = title;
    _operationId++;
    publish();
    try {
      return await action();
    } finally {
      _operationBusy = false;
      _operationTitle = null;
      publish();
    }
  }

  static const _pages = {
    'home',
    'editor',
    'styles',
    'watermarks',
    'batch',
    'settings',
    'components',
    'licenses',
    'experiments',
  };
  static const _allowedWhileBusy = {
    'parameterIcon.load',
    'material.changed',
    'sync',
    'navigate',
    'navigate.back',
    'interaction.cancel',
    'batch.cancel',
    'style.import.cancel',
    'settings.update',
    'message.clear',
  };

  Future<Map<String, dynamic>> handleCommand(
    Map<String, dynamic> command,
  ) async {
    if (_disposed) return {'ok': false, 'message': '编辑器已关闭'};
    try {
      if (command['version'] != 1) throw const FormatException('不支持此界面协议版本');
      final action = command['action'];
      if (action is! String) throw const FormatException('命令缺少操作名称');
      if (busy && !_allowedWhileBusy.contains(action)) {
        return _failure('请等待当前任务完成');
      }
      if (editor.document.isStaruw &&
          const {
            'position.align',
            'font.auto',
            'font.import',
            'font.choose',
            'region.import',
            'region.builtin',
            'region.icon',
            'model.choose',
            'adjust',
          }.contains(action)) {
        throw const StaruwException('E_BINDING', '请使用模板声明的 StarUW 控件');
      }
      if (_templateEditor != null &&
          !{
            'sync',
            'navigate.back',
            'template.save',
            'template.reset',
            'position.align',
            'document.apply',
            'font.load',
            'font.import',
            'region.import',
            'region.builtin',
            'region.icon',
            'parameterIcon.load',
            'parameterIcon.delete',
            'font.auto',
            'font.choose',
            'model.choose',
            'history.undo',
            'history.redo',
            'history.jump',
            'adjust',
            'interaction.cancel',
            'material.changed',
            'message.clear',
            'staruw.set',
            'staruw.clear',
            'staruw.image',
            'staruw.font',
          }.contains(action)) {
        throw const FormatException('模板编辑中不可执行此操作');
      }
      if (_templateEditor != null && action == 'document.apply') {
        final patch = _map(command['patch']);
        final options = _map(patch['xiaomiOptions'] ?? {});
        if (patch.containsKey('modelOverride') ||
            patch.containsKey('parametersOverride') ||
            options.keys.any(
              (key) => {
                'exifOverrides',
                'locationAddress',
                'filterName',
              }.contains(key),
            )) {
          throw const FormatException('模板不能修改照片信息');
        }
      }
      switch (action) {
        case 'parameterIcon.delete':
          await _exclusive(() async {
            final asset = command['asset'] as String? ?? '';
            await photoEditor.parameterIconLibrary.delete(asset);
            if (_settings['defaultParameterIcon'] == asset) {
              await _updateSettings({
                'defaultParameterIcon': builtinEmblems.first['asset'],
              });
            }
            await _finishParameterIconDeletion();
            await _loadParameterIcons();
          }, title: '正在删除图标…');
        case 'parameterIcon.load':
          await _loadParameterIcons();
        case 'parameterIcon.import':
          await _exclusive(() async {
            final picked = await NativeBridge.call<Map>('pickRegionImage');
            if (picked == null || _disposed) return;
            if (picked['bytes'] is! Uint8List) {
              throw const FormatException('图片无法读取');
            }
            await _finishParameterIconDeletion();
            final asset = await photoEditor.parameterIconLibrary.importBytes(
              picked['bytes'] as Uint8List,
              picked['title'] as String? ?? '自定义图标',
            );
            await _loadParameterIcons();
            await _updateSettings({'defaultParameterIcon': asset});
          }, title: '正在导入图标…');
        case 'staruw.set':
        case 'staruw.clear':
        case 'staruw.image':
        case 'staruw.font':
          final id = command['id'] as String? ?? '';
          final control = StaruwControls(
            editor.document,
          ).active.where((c) => c['id'] == id).firstOrNull;
          staruwNeed(control != null, 'E_BINDING', '此控件当前不可用');
          staruwNeed(
            _templateEditor == null || control!['scope'] == 'template',
            'E_SCOPE',
            '模板不能保存照片参数',
          );
          cancelInteraction();
          await _exclusive(
            () async {
              if (action == 'staruw.image') {
                await _finishParameterIconDeletion();
                await editor.importStaruwImage(id);
              } else if (action == 'staruw.font') {
                await editor.importStaruwFont(id);
              } else {
                await editor.setStaruwControl(
                  id,
                  command['value'],
                  clear: action == 'staruw.clear',
                );
              }
            },
            title: action == 'staruw.image' || action == 'staruw.font'
                ? '正在准备素材…'
                : null,
          );
        case 'style.describe':
          final style = _style(command['id']);
          final report = style.document.staruwReport;
          final messages = (report['diagnostics'] as List? ?? [])
              .whereType<Map>()
              .map((d) => d['message'])
              .join('；');
          editor.inform('${messages.isEmpty ? '此水印仅保留原包' : messages}。长按可导出原包');
        case 'template.edit':
          cancelInteraction();
          await _exclusive(
            () => _openTemplate(_style(command['id'])),
            title: '正在打开模板…',
          );
        case 'template.save':
          final style = _editingTemplate;
          if (style == null) throw const FormatException('未打开模板');
          cancelInteraction();
          await _exclusive(() async {
            await photoEditor.templatePreferences?.save(
              style.document.packageId.isEmpty
                  ? style.id
                  : style.document.packageId,
              editor.document,
            );
            if (!_disposed) _closeTemplate();
          });
        case 'template.reset':
          if (_editingTemplate == null) throw const FormatException('未打开模板');
          editor.apply(_editingTemplate!.document, '恢复出厂设置');
        case 'sync':
          break;
        case 'position.align':
          if (editor.document.xiaomiConfig != null) {
            throw const FormatException('原厂水印仅支持配置中声明的构图');
          }
          editor.alignWatermark('${command['axis']}', '${command['value']}');
        case 'navigate':
          _openingTemplate = false;
          final page = command['page'];
          if (!_pages.contains(page)) throw const FormatException('未知页面');
          if (page == 'watermarks') {
            final previous =
                command['returnPage'] ??
                (['editor', 'batch'].contains(_page) ? _page : 'home');
            if (!['editor', 'home', 'styles', 'batch'].contains(previous)) {
              throw const FormatException('无效水印页返回位置');
            }
            _pickerReturnPage = previous as String;
          }
          cancelInteraction();
          if (_page == 'editor' &&
              !['editor', 'watermarks', 'styles'].contains(page)) {
            await editor.finishPhotoEditing();
          }
          if (page == 'editor' && !editor.hasPhoto) {
            _message = '请选择照片';
            _messageId++;
            _page = 'home';
            break;
          }
          _page = page as String;
          if (_page == 'experiments') {
            await _exclusive(_loadParameterIcons, title: '正在读取图标…');
          }
          if (!['watermarks', 'styles'].contains(_page)) {
            _cancelWatermarkSelection();
          }
        case 'navigate.back':
          if (_selectingWatermarks &&
              ['watermarks', 'styles'].contains(_page)) {
            _cancelWatermarkSelection();
            break;
          }
          if (_openingTemplate) {
            _openingTemplate = false;
            break;
          }
          if (_templateEditor != null) {
            _closeTemplate();
            break;
          }
          cancelInteraction();
          if (_page == 'editor') await editor.finishPhotoEditing();
          _page = _page == 'watermarks'
              ? _pickerReturnPage
              : ['components', 'licenses', 'experiments'].contains(_page)
              ? 'settings'
              : 'home';
        case 'photo.import':
          cancelInteraction();
          await _photoImport(() async {
            final photos = await editor.pick();
            if (photos.isEmpty) return;
            if (_page == 'editor') {
              await editor.finishPhotoEditing();
            } else {
              await editor.saveDraft();
            }
            await editor.setAsset(photos.first);
            _page = 'editor';
          });
        case 'photo.export':
          cancelInteraction();
          await _exclusive(() async {
            await editor.export();
            await editor.saveDraft();
          }, title: null);
        case 'draft.restore':
          final draft = editor.drafts
              .where((d) => d['id'] == command['id'])
              .firstOrNull;
          if (draft == null) throw const FormatException('草稿不存在');
          cancelInteraction();
          await _exclusive(() => editor.restore(draft), title: '正在打开草稿…');
          _page = 'editor';
        case 'draft.save':
          cancelInteraction();
          await _exclusive(
            () => editor.saveDraft(explicit: true),
            title: '正在保存草稿…',
          );
        case 'draft.selection.begin':
        case 'draft.selection.toggle':
          final id = command['id'];
          if (id is! String || !editor.drafts.any((row) => row['id'] == id)) {
            throw const FormatException('草稿不存在');
          }
          if (action == 'draft.selection.begin') {
            _selectingDrafts = true;
            _selectedDrafts.add(id);
          } else {
            if (!_selectingDrafts) throw const FormatException('请先选择草稿');
            if (!_selectedDrafts.remove(id)) _selectedDrafts.add(id);
          }
        case 'draft.selection.all':
          if (command['selected'] is! bool) {
            throw const FormatException('无效选择状态');
          }
          _selectingDrafts = true;
          _selectedDrafts.clear();
          if (command['selected'] == true) {
            _selectedDrafts.addAll(
              editor.drafts.map((row) => row['id'] as String),
            );
          }
        case 'draft.selection.cancel':
          _selectingDrafts = false;
          _selectedDrafts.clear();
        case 'draft.delete':
          final ids = command['ids'];
          if (command['confirmed'] != true ||
              !_selectingDrafts ||
              ids is! List ||
              ids.isEmpty ||
              ids.any((id) => id is! String || !_selectedDrafts.contains(id))) {
            throw const FormatException('请确认删除选中的草稿');
          }
          await _exclusive(
            () => editor.deleteDrafts(ids.cast<String>()),
            title: '正在删除草稿…',
          );
          _selectedDrafts.clear();
          _selectingDrafts = false;
        case 'style.apply':
          final style = _style(command['id']);
          final destination =
              _page == 'watermarks' && _pickerReturnPage == 'batch'
              ? 'batch'
              : 'editor';
          cancelInteraction();
          await _exclusive(() => editor.applyStyle(style), title: '正在准备水印…');
          if (command['stayOnPage'] != true) {
            _page = destination == 'editor' && !editor.hasPhoto
                ? 'home'
                : destination;
            if (_page == 'home' && !editor.hasPhoto) {
              _message = '请选择照片';
              _messageId++;
            }
          }
        case 'watermark.selection.begin':
        case 'watermark.selection.toggle':
          _requireWatermarkLibrary();
          final style = _style(command['id']);
          if (action == 'watermark.selection.begin') {
            _selectingWatermarks = true;
            _selectedWatermarks.add(style.id);
          } else {
            if (!_selectingWatermarks) throw const FormatException('请先选择水印');
            if (!_selectedWatermarks.remove(style.id)) {
              _selectedWatermarks.add(style.id);
            }
          }
        case 'watermark.selection.all':
          _requireWatermarkLibrary();
          if (command['selected'] is! bool) {
            throw const FormatException('无效选择状态');
          }
          _selectingWatermarks = true;
          _selectedWatermarks.clear();
          if (command['selected'] == true) {
            _selectedWatermarks.addAll(
              editor.allStyles.map((style) => style.id),
            );
          }
        case 'watermark.selection.cancel':
          _cancelWatermarkSelection();
        case 'watermark.export':
          final styles = _selectedWatermarkStyles(command['ids']);
          await _exclusive(
            () => TemplateArchive(editor).exportStyles(styles),
            title: '正在导出水印…',
          );
        case 'watermark.delete':
          final styles = _selectedWatermarkStyles(command['ids']);
          if (command['confirmed'] != true ||
              styles.any(
                (style) =>
                    style.factory ||
                    !editor.userStyles.any(
                      (custom) => custom.id == style.id && !custom.factory,
                    ),
              )) {
            throw const FormatException('仅可确认删除选中的自定义水印');
          }
          await _exclusive(() async {
            try {
              await editor.watermarkRepository.removeFromLibrary(
                styles.map((style) => style.id),
              );
            } finally {
              await editor.persistStyles();
            }
          }, title: '正在删除水印…');
          _cancelWatermarkSelection();
          editor.inform('已删除 ${styles.length} 款自定义水印');
        case 'style.export':
          final style = _style(command['id']);
          await _exclusive(
            () => TemplateArchive(editor).exportStyle(style),
            title: '正在导出水印…',
          );
        case 'style.save':
          final title = command['title'];
          if (title is! String ||
              title.trim().isEmpty ||
              title.trim().length > 40) {
            throw const FormatException('样式名称需要 1–40 个字符');
          }
          cancelInteraction();
          await _exclusive(() => editor.saveStyle(title), title: '正在保存水印…');
        case 'style.import':
          _styleImportCancelled = false;
          _styleImportResult = null;
          _styleImportProgress = null;
          await _exclusive(() async {
            _importStage = 'watermarks';
            publish();
            try {
              final result = await TemplateArchive(editor).importFromPicker(
                isCancelled: () => _styleImportCancelled || _disposed,
                onProgress: (progress) {
                  _styleImportProgress = progress.toJson();
                  publish();
                },
              );
              _styleImportResult = result?.toJson();
            } catch (error) {
              _styleImportResult = {
                'installed': 0,
                'skipped': 0,
                'failed': 1,
                'conflicts': 0,
                'cancelled': false,
                'aborted': true,
                'error': _errorText(error),
                'total': 0,
                'completed': 0,
                'remaining': <String>[],
                'ignored': <String>[],
                'items': <Map>[],
              };
              rethrow;
            } finally {
              _importStage = null;
              publish();
            }
          }, title: null);
        case 'style.import.cancel':
          _styleImportCancelled = true;
        case 'style.import.dismiss':
          _styleImportResult = null;
          _styleImportProgress = null;
        case 'styles.refresh':
          await _exclusive(editor.persistStyles, title: '正在刷新水印…');
        case 'document.apply':
          final document = _patchedDocument(_map(command['patch']));
          await _exclusive(() async {
            await editor.validateTextEdits(document);
            cancelInteraction();
            editor.apply(document, command['label']?.toString() ?? '修改水印');
          });
        case 'font.load':
          await editor.loadImportedFonts();
        case 'region.import':
          await _exclusive(() async {
            final region = command['region']?.toString() ?? '';
            if (region == 'parameters' && editor.usesGlobalParameterIcon) {
              throw const FormatException('参数图标已由实验性功能统一设置');
            }
            if (region == 'parameters') await _finishParameterIconDeletion();
            await editor.importRegionImage(region);
            if (region == 'parameters') await _loadParameterIcons();
          });
        case 'region.icon':
          await _exclusive(
            () => editor.useParameterIcon(command['asset']?.toString() ?? ''),
          );
        case 'region.builtin':
          await _exclusive(
            () => editor.useBuiltinEmblem(command['id']?.toString() ?? ''),
          );
          break;
        case 'font.import':
          cancelInteraction();
          await _exclusive(editor.importFont, title: '正在导入字体…');
        case 'font.auto':
          cancelInteraction();
          editor.useAutomaticFont();
        case 'font.choose':
          final font = _map(command['font']);
          FontRef.fromJson(font);
          if (font['id'] is! String ||
              !['bundled', 'system', 'imported'].contains(font['source'])) {
            throw const FormatException('无效字体选择');
          }
          _gate.clear();
          editor.cancel();
          if (!await editor.chooseFont(font)) {
            return _failure(editor.message ?? '字体选择已取消');
          }
        case 'model.choose':
          final id = command['id'];
          if (id is! String ||
              (id.isNotEmpty &&
                  !editor.modelCatalog.any((m) => m['id'] == id))) {
            throw const FormatException('预设型号不存在');
          }
          cancelInteraction();
          editor.apply(
            editor.document.copyWith(
              modelId: id,
              modelOverride: id.isEmpty
                  ? ''
                  : editor.modelCatalog.firstWhere(
                          (row) => row['id'] == id,
                        )['title']
                        as String,
            ),
            '选择预设型号',
          );
        case 'history.undo':
          cancelInteraction();
          editor.undo();
        case 'history.redo':
          cancelInteraction();
          editor.redo();
        case 'history.jump':
          final index = command['index'];
          if (index is! num ||
              index != index.toInt() ||
              index < 0 ||
              index >= editor.history.entries.length) {
            throw const FormatException('历史记录不存在');
          }
          cancelInteraction();
          editor.history.jump(index.toInt());
          // Applying the same document publishes the jump and refreshes resources
          // without appending an entry or discarding the redo branch.
          editor.apply(editor.document, '恢复历史');
        case 'adjust':
          if (!_adjust(command)) return _failure('调整事件已过期，请重新操作');
        case 'interaction.cancel':
          _gate.clear();
          editor.cancel();
        case 'batch.add':
          await _photoImport(() async {
            final photos = await editor.pick(multiple: true);
            for (final photo in photos) {
              if (!_batch.any((p) => p.id == photo.id)) _batch.add(photo);
              _selected.add(photo.id);
            }
          });
        case 'batch.toggle':
          final id = command['id'];
          if (!_batch.any((p) => p.id == id)) {
            throw const FormatException('批量照片不存在');
          }
          if (!_selected.remove(id)) _selected.add(id as String);
        case 'batch.selectAll':
          if (command['selected'] is! bool) {
            throw const FormatException('无效选择状态');
          }
          _selected.clear();
          if (command['selected'] == true) {
            _selected.addAll(_batch.map((p) => p.id));
          }
        case 'batch.removeSelected':
          // Queue references only: never release photo files, drafts or resources.
          // The command-wide busy guard also protects import/export snapshots.
          if (_selected.isNotEmpty) {
            _batch.removeWhere((photo) => _selected.contains(photo.id));
            _selected.clear();
            _completed = 0;
            _total = 0;
            _failed = 0;
            _cancelled = false;
            _message = '';
          }
        case 'batch.export':
          cancelInteraction();
          await _exportBatch();
        case 'batch.cancel':
          if (_batchRunning) _cancelled = true;
        case 'settings.update':
          await _updateSettings(_map(command['patch']));
        case 'settings.checkUpdate':
          await _checkForUpdates();
        case 'settings.dismissUpdate':
          _availableUpdate = null;
        case 'settings.openUpdate':
          await _openUpdate();
        case 'material.changed':
          await editor.invalidateNativeMaterial();
        case 'message.clear':
          _message = '';
          editor.message = null;
          _lastEditorMessage = null;
        default:
          throw const FormatException('未知操作');
      }
      if (_page != 'settings') _availableUpdate = null;
      publish();
      return {'ok': true};
    } catch (error) {
      return _failure(_errorText(error));
    }
  }

  Map<String, dynamic> _staruwState(EditDocument doc) {
    if (!doc.isStaruw) return {'enabled': false};
    final report = doc.staruwReport;
    if (doc.staruwPreserved) {
      return {
        'enabled': true,
        'preserve': true,
        'report': report,
        'controls': <Map>[],
      };
    }
    final controls = StaruwControls(doc);
    final u = doc.staruwConfig!['staruw'] as Map;
    final fonts = (u['scene'] ?? u['xiaomi'])['fonts'] as Map;
    final replacementFonts = <String, String>{};
    void visit(List nodes) {
      for (final node in nodes) {
        if (node['replacementStyle'] is Map) {
          replacementFonts[node['id'] as String] =
              node['replacementStyle']['fontId'] as String;
        }
        if (node['children'] is List) visit(node['children'] as List);
      }
    }

    if (doc.isStaruwScene) {
      visit(u['scene']['nodes'] as List);
    } else {
      for (final binding in u['xiaomi']['bindings'] as List) {
        if (binding['replacementStyle'] is Map) {
          replacementFonts[binding['id'] as String] =
              binding['replacementStyle']['fontId'] as String;
        }
      }
    }
    return {
      'enabled': true,
      'preserve': false,
      'report': report,
      'fonts': [
        for (final id in fonts.keys)
          {'id': id, 'label': editor.staruwFontLabel(id as String)},
      ],
      'controls': [
        for (final c in controls.active)
          if (_templateEditor == null || c['scope'] == 'template')
            {
              ...c,
              'value': controls.allValues[c['id']],
              'hasValue': controls.allValues.containsKey(c['id']),
              'globalParameterIcon':
                  editor.usesGlobalParameterIcon &&
                  GlobalParameterIcon.controlsParameterIcon(c),
              if (c['type'] == 'region')
                'defaultFontId':
                    replacementFonts[(c['targets'] as List).first['id']],
            },
      ],
    };
  }

  static Map<String, dynamic> _map(dynamic value) {
    if (value is! Map) throw const FormatException('命令缺少有效参数');
    return Map<String, dynamic>.from(value);
  }

  void _cancelWatermarkSelection() {
    _selectingWatermarks = false;
    _selectedWatermarks.clear();
  }

  void _requireWatermarkLibrary() {
    if (!['watermarks', 'styles'].contains(_page) || _templateEditor != null) {
      throw const FormatException('请先打开水印页');
    }
  }

  List<StylePreset> _selectedWatermarkStyles(dynamic ids) {
    _requireWatermarkLibrary();
    if (!_selectingWatermarks ||
        ids is! List ||
        ids.isEmpty ||
        ids.length != _selectedWatermarks.length ||
        ids.toSet().length != ids.length ||
        ids.any((id) => id is! String || !_selectedWatermarks.contains(id))) {
      throw const FormatException('请先选择水印');
    }
    return ids.map(_style).toList();
  }

  StylePreset _style(dynamic id) {
    final style = editor.allStyles.where((s) => s.id == id).firstOrNull;
    if (style == null) throw const FormatException('水印样式不存在');
    return style;
  }

  static const _editable = {
    'regionReplacements',
    'enabled',
    'mode',
    'background',
    'border',
    'x',
    'y',
    'scale',
    'opacity',
    'blur',
    'tint',
    'caption',
    'parametersOverride',
    'modelOverride',
    'showParameters',
    'showModel',
    'showBrand',
    'textColor',
    'xiaomiOptions',
  };

  EditDocument _patchedDocument(Map<String, dynamic> patch) {
    if (editor.document.isStaruw && patch.keys.any((k) => k != 'enabled')) {
      throw const StaruwException('E_BINDING', '请使用模板声明的 StarUW 控件');
    }
    if (patch.keys.any((k) => !_editable.contains(k))) {
      throw const FormatException('此字段不能由界面直接修改');
    }
    if (patch.containsKey('regionReplacements')) {
      final input = _map(patch['regionReplacements']);
      if (editor.usesGlobalParameterIcon) {
        // The native model editor sends the whole visible region map, including
        // the temporary global icon. Persist only the user's underlying choice.
        input.remove('parameters');
        if (editor.document.regionReplacements.containsKey('parameters')) {
          input['parameters'] =
              editor.document.regionReplacements['parameters'];
        }
      }
      final regions = validateRegionReplacements(input);
      final supported = regionSupport(editor.document);
      if (regions.keys.any((k) => supported[k] != true)) {
        throw const FormatException('Unsupported replacement region');
      }
      patch = {...patch, 'regionReplacements': regions};
    }
    final config = editor.document.xiaomiConfig;
    final capabilities = config == null
        ? null
        : XiaomiCapabilities.fromConfig(
            config,
            editor.document.xiaomiOptions,
            editor.watermarkImageNames,
          );
    if (capabilities != null) {
      if (patch.keys.any(
        (key) => key != 'regionReplacements' && !capabilities.allowsField(key),
      )) {
        throw const FormatException('此原厂水印未声明该调整');
      }
      if (patch.containsKey('scale') &&
          ![.85, 1.0, 1.15].contains(patch['scale'])) {
        throw const FormatException('原厂内容大小仅支持小、中、大');
      }
    }
    for (final key in ['x', 'y', 'scale', 'opacity', 'blur', 'tint']) {
      if (patch.containsKey(key) &&
          (patch[key] is! num || !(patch[key] as num).isFinite)) {
        throw const FormatException('无效数值');
      }
    }
    for (final key in ['showParameters', 'showModel', 'showBrand']) {
      if (patch.containsKey(key) && patch[key] is! bool) {
        throw const FormatException('无效开关值');
      }
    }
    for (final key in ['caption', 'parametersOverride', 'modelOverride']) {
      if (patch.containsKey(key) &&
          (patch[key] is! String || (patch[key] as String).length > 2000)) {
        throw const FormatException('无效水印文字');
      }
    }
    final json = editor.document.toJson();
    final merged = {...json, ...patch};
    if (patch.containsKey('border')) {
      final border = _map(patch['border']);
      if (border.keys.any(
            (k) => !['top', 'bottom', 'left', 'right'].contains(k),
          ) ||
          border.values.any((v) => v is! num || !v.isFinite)) {
        throw const FormatException('无效边框');
      }
      merged['border'] = {...editor.document.border.toJson(), ...border};
    }
    if (patch.containsKey('textColor') &&
        (patch['textColor'] is! num ||
            (patch['textColor'] as num) < 0 ||
            (patch['textColor'] as num) > 0xffffffff)) {
      throw const FormatException('无效颜色');
    }
    if (patch.containsKey('xiaomiOptions')) {
      final options = _map(patch['xiaomiOptions']);
      if (capabilities == null &&
          options.keys.any((key) => key != 'exifOverrides')) {
        throw const FormatException('当前样式没有原厂选项');
      }
      if (capabilities != null &&
          options.keys.any((key) => !capabilities.allowsOption(key))) {
        throw const FormatException('此原厂选项未声明为可编辑');
      }
      if (options.keys.any(
        (k) =>
            !{
              'layoutGroup',
              'layout',
              'background',
              'glassBackground',
              'exifOverrides',
              'textColor',
              'locationAddress',
              'filterName',
              'showTime',
              'showLocation',
              'customizeSettings',
              'mix',
            }.contains(k) &&
            (!editor.document.xiaomiOptions.containsKey(k) ||
                jsonEncode(options[k]) !=
                    jsonEncode(editor.document.xiaomiOptions[k])),
      )) {
        throw const FormatException('此原厂选项不可编辑');
      }
      final group = options['layoutGroup'];
      if (options.containsKey('layoutGroup') &&
          !(group is String ||
              (group is num && group.isFinite && group == group.toInt()))) {
        throw const FormatException('无效原厂布局');
      }
      final color = options['textColor'];
      for (final key in ['showTime', 'showLocation', 'glassBackground']) {
        if (options.containsKey(key) && options[key] is! bool) {
          throw const FormatException('无效时间/经纬度开关');
        }
      }
      if (options.containsKey('textColor') &&
          (color is! num ||
              !color.isFinite ||
              color != color.toInt() ||
              color < 0 ||
              color > 0xffffffff)) {
        throw const FormatException('无效颜色');
      }
      for (final key in [
        'layout',
        'background',
        'locationAddress',
        'filterName',
      ]) {
        final value = options[key];
        if (options.containsKey(key) &&
            (value is! String || value.length > 2000)) {
          throw const FormatException('无效原厂选项');
        }
      }
      final next = {...editor.document.xiaomiOptions, ...options};
      if (options.containsKey('mix')) {
        final changes = _map(options['mix']);
        final states = {..._map(editor.document.xiaomiOptions['mix'] ?? {})};
        for (final entry in changes.entries) {
          final field = capabilities!.mixFields
              .where((field) => field.id == entry.key)
              .firstOrNull;
          final state = _map(entry.value);
          if (field == null ||
              state.keys.any((key) => !{'type', 'text'}.contains(key)) ||
              (state.containsKey('type') &&
                  !field.types.contains(state['type'])) ||
              (state.containsKey('text') &&
                  (state['text'] is! String ||
                      (state['type'] ??
                              field.selectedType(
                                editor.document.xiaomiOptions,
                              )) !=
                          'custom_text'))) {
            throw const FormatException('未声明的原厂混合内容选项');
          }
          if (state['type'] == 'location_latlng' && !_hasPhotoCoordinates) {
            throw const FormatException('照片无地理位置信息');
          }
          states[entry.key] = {
            ...field.state(editor.document.xiaomiOptions),
            ...state,
            if (state['text'] is String)
              'text': (state['text'] as String)
                  .replaceAll(RegExp(r'[\r\n]'), '')
                  .trim(),
          };
        }
        next['mix'] = states;
      }
      if (options.containsKey('customizeSettings')) {
        if (options['customizeSettings'] is! Map) {
          throw const FormatException('无效原厂图形选项');
        }
        final selections = _map(options['customizeSettings']);
        for (final entry in selections.entries) {
          final setting = capabilities!.customizeSettings
              .where((row) => row['id'] == entry.key)
              .firstOrNull;
          if (setting == null ||
              !(entry.value == 'off' && setting['supportOff'] == true) &&
                  !(setting['options'] as List? ?? []).whereType<Map>().any(
                    (row) => row['id'] == entry.value,
                  )) {
            throw const FormatException('未声明的原厂图形选项');
          }
        }
        next['customizeSettings'] = {
          ..._map(editor.document.xiaomiOptions['customizeSettings'] ?? {}),
          ...selections,
        };
      }
      if (options.containsKey('layoutGroup') &&
          !options.containsKey('layout')) {
        next.remove('layout');
      }
      if (options.containsKey('exifOverrides')) {
        final exif = _map(options['exifOverrides']);
        if (exif.keys.any(
              (k) => !{'focal', 'aperture', 'speed', 'iso'}.contains(k),
            ) ||
            exif.values.any((v) => v is! String || v.length > 32)) {
          throw const FormatException('无效分栏参数');
        }
        if (capabilities != null && !capabilities.canEditParameters) {
          final originals = photoParameterValues(editor.asset.metadata);
          final previous = _map(
            editor.document.xiaomiOptions['exifOverrides'] ?? {},
          );
          if (exif.entries.any(
            (entry) =>
                originals[entry.key]!.isNotEmpty &&
                (entry.value as String).trim().isNotEmpty &&
                entry.value != previous[entry.key],
          )) {
            throw const FormatException('此原厂水印仅可补充缺失的拍摄参数');
          }
        }
        next['exifOverrides'] = {
          ..._map(editor.document.xiaomiOptions['exifOverrides'] ?? {}),
          ...exif,
        };
      }
      final config = editor.document.xiaomiConfig;
      if (config != null) {
        final nextCapabilities = XiaomiCapabilities.fromConfig(
          config,
          next,
          editor.watermarkImageNames,
        );
        if (options.keys.any((key) => !nextCapabilities.allowsOption(key))) {
          throw const FormatException('所选原厂布局未声明该调整');
        }
        if (options['mix'] is Map &&
            (options['mix'] as Map).keys.any(
              (id) =>
                  !nextCapabilities.mixFields.any((field) => field.id == id),
            )) {
          throw const FormatException('所选原厂布局没有此混合内容');
        }
        if (options['mix'] is Map &&
            (options['mix'] as Map).values.any(
              (value) => value is Map && value['type'] == 'off',
            ) &&
            !_hasEnabledWatermarkItem(
              config,
              EditDocument.fromJson({...merged, 'xiaomiOptions': next}),
            )) {
          throw const FormatException('请至少保留一个选项');
        }
        if (options.containsKey('layoutGroup') ||
            options.containsKey('layout')) {
          if (!nextCapabilities.layoutChoices.any(
            (choice) =>
                choice.groupIndex == nextCapabilities.groupIndex &&
                (!options.containsKey('layout') ||
                    choice.name == options['layout']),
          )) {
            throw const FormatException('此原厂水印没有声明该构图');
          }
        }
        XiaomiRenderPlan.selectedLayouts(config, next);
        XiaomiRenderPlan.selectedBackground(config, next);
        if (options.containsKey('background') &&
            options['background'] !=
                editor.document.xiaomiOptions['background'] &&
            !XiaomiRenderPlan.selectableBackgrounds(
              config,
              next,
            ).any((b) => b['name'] == options['background'])) {
          throw const FormatException('此布局没有可选背景');
        }
      }
      merged['xiaomiOptions'] = next;
    }
    return EditDocument.fromJson(merged);
  }

  bool _adjust(Map<String, dynamic> command) {
    // OEM support_scale applies only to flagged nodes through the three presets.
    // Canvas transforms and continuous document-wide sliders are custom-only.
    if (editor.document.xiaomiConfig != null) return false;
    final control = command['control'], phase = command['phase'];
    final id = command['interactionId'], revision = command['revision'];
    if (!{'scale', 'x', 'y', 'opacity', 'blur', 'tint'}.contains(control) ||
        id is! String ||
        id.isEmpty ||
        revision is! num ||
        revision != revision.toInt()) {
      return false;
    }
    final base = revision.toInt();
    if (phase == 'begin') {
      if (editor.history.interacting ||
          !_gate.begin(id, control as String, base, editor.revision)) {
        return false;
      }
      editor.cancelPendingFont();
      editor.begin();
      return true;
    }
    if (!_gate.accepts(id, control as String, base, editor.revision)) {
      return false;
    }
    if (phase == 'cancel') {
      _gate.clear();
      editor.cancel();
      return true;
    }
    if (phase != 'update' && phase != 'end') return false;
    if (command.containsKey('value')) {
      final document = _patchedDocument({control: command['value']});
      editor.previewEdit(document);
    } else if (phase == 'update') {
      return false;
    }
    if (phase == 'end') {
      _gate.clear();
      editor.commit(
        '调整${const {'scale': '大小', 'x': '水平位置', 'y': '垂直位置', 'opacity': '不透明度', 'blur': '模糊强度', 'tint': '背景明暗'}[control]}',
      );
    }
    return true;
  }

  Future<void> _exportBatch() async {
    final photos = _batch
        .where((p) => _selected.contains(p.id))
        .toList(growable: false);
    if (photos.isEmpty) throw const FormatException('请先选择要导出的照片');
    // Freeze the complete style, including package/font bindings, once per job.
    final style = EditDocument.fromJson(_copy(editor.document.toJson()));
    final staticOutput = editor.staticPhotoExport;
    _completed = 0;
    _failed = 0;
    _total = photos.length;
    _cancelled = false;
    _batchRunning = true;
    publish();
    try {
      for (final photo in photos) {
        if (_cancelled || _disposed) break;
        try {
          await editor.export(
            photo: photo,
            style: style,
            staticOutput: staticOutput,
          );
        } catch (_) {
          _failed++;
        }
        _completed++;
        publish();
      }
    } finally {
      _batchRunning = false;
      final succeeded = _completed - _failed;
      _message =
          '${_cancelled ? '已停止，' : ''}已保存 $succeeded 张照片${_failed > 0 ? '，$_failed 张失败' : ''}';
      _messageId++;
      publish();
    }
  }

  Future<void> _finishParameterIconDeletion() async {
    await photoEditor.resetDeletedParameterIcons();
    await _templateEditor?.resetDeletedParameterIcons(persist: false);
  }

  Future<void> _loadParameterIcons() async {
    if (photoEditor.directory == null) return;
    _parameterIcons = await photoEditor.parameterIconLibrary.list(
      sources: [
        photoEditor.history.toJson(),
        if (_templateEditor != null) _templateEditor!.history.toJson(),
        ...photoEditor.drafts,
        for (final style in photoEditor.userStyles) style.document.toJson(),
      ],
    );
    publish();
  }

  Future<void> _applyParameterIconSetting(Map<String, dynamic> settings) async {
    await photoEditor.setGlobalParameterIcon(
      enabled: settings['replaceDefaultParameterIcon'] == true,
      asset: settings['defaultParameterIcon'] as String,
      shape: settings['defaultParameterIconShape'] as String,
    );
  }

  Map<String, dynamic> _validatedSettings(Map<String, dynamic> patch) {
    if (patch.keys.any(
          (key) => !{
            'theme',
            'glass',
            'glassMode',
            'glassWatermark',
            'xiaomiImageBlur',
            'reduceMotion',
            'staticPhotoExport',
            'autoRemoveWatermark',
            'replaceDefaultParameterIcon',
            'defaultParameterIcon',
            'defaultParameterIconShape',
          }.contains(key),
        ) ||
        (patch.containsKey('theme') &&
            !{'system', 'light', 'dark'}.contains(patch['theme'])) ||
        (patch.containsKey('glassMode') &&
            !{'auto', 'frost', 'glass', 'off'}.contains(patch['glassMode'])) ||
        (patch.containsKey('defaultParameterIcon') &&
            (patch['defaultParameterIcon'] is! String ||
                !RegionAssets.validId(patch['defaultParameterIcon']))) ||
        (patch.containsKey('defaultParameterIconShape') &&
            !{
              'square',
              'circle',
            }.contains(patch['defaultParameterIconShape'])) ||
        [
          'glass',
          'glassWatermark',
          'xiaomiImageBlur',
          'reduceMotion',
          'staticPhotoExport',
          'autoRemoveWatermark',
          'replaceDefaultParameterIcon',
        ].any((key) => patch.containsKey(key) && patch[key] is! bool)) {
      throw const FormatException('无效界面设置');
    }
    final result = {..._settings, ...patch};
    if (photoEditor.directory != null &&
        photoEditor.parameterIconLibrary.removed.contains(
          result['defaultParameterIcon'],
        )) {
      result['defaultParameterIcon'] = builtinEmblems.first['asset'];
    }
    if (patch.containsKey('glass') && !patch.containsKey('glassMode')) {
      result['glassMode'] = patch['glass'] == false ? 'off' : 'auto';
    }
    if (patch.containsKey('glassMode')) {
      result['glass'] = patch['glassMode'] != 'off';
    }
    return result;
  }

  Future<void> _updateSettings(Map<String, dynamic> patch) async {
    _validatedSettings(patch);
    final job = _settingsTail.then((_) async {
      final next = _validatedSettings(patch);
      final directory = editor.directory;
      if (directory == null && !editor.testMode) throw StateError('应用存储不可用');
      if (next['replaceDefaultParameterIcon'] == true ||
          patch.containsKey('defaultParameterIcon')) {
        if (directory == null) throw StateError('应用存储不可用');
        await photoEditor.parameterIconLibrary.ensureAsset(
          next['defaultParameterIcon'] as String,
        );
      }
      if (directory != null) {
        await directory.create(recursive: true);
        final file = File('${directory.path}/ui-settings.json');
        final temp = File('${file.path}.tmp');
        final backup = File('${file.path}.bak');
        await temp.writeAsString(jsonEncode(next), flush: true);
        if (await file.exists()) {
          if (await backup.exists()) await backup.delete();
          await file.rename(backup.path);
        }
        try {
          await temp.rename(file.path);
        } catch (_) {
          if (await backup.exists()) await backup.rename(file.path);
          rethrow;
        }
      }
      _settings = next;
      await editor.setXiaomiImageBlur(next['xiaomiImageBlur'] == true);
      await editor.setXiaomiGlassWatermark(next['glassWatermark'] == true);
      editor.staticPhotoExport = next['staticPhotoExport'] == true;
      editor.autoRemoveWatermark = next['autoRemoveWatermark'] == true;
      await _applyParameterIconSetting(next);
      publish();
    });
    _settingsTail = job.catchError((Object _) {});
    await job;
  }

  @override
  void dispose() {
    _disposed = true;
    _cancelled = _batchRunning;
    editor.removeListener(_editorChanged);
    _templateEditor?.dispose();
    photoEditor.liveDraftReferences = null;
    if (_attached) channel.setMethodCallHandler(null);
    super.dispose();
  }
}
