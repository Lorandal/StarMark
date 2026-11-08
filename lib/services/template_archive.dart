import 'dart:io';
import 'dart:convert';
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:archive/archive.dart';
import 'package:crypto/crypto.dart';
import 'package:watermark_engine/watermark_engine.dart';
import '../editor/editor_controller.dart';
import '../editor/watermark_renderer.dart';
import 'template_preferences.dart';
import 'native_bridge.dart';
import 'watermark_package.dart';
import 'watermark_package_builder.dart';
import 'watermark_repository.dart';
import 'watermark_bundle_import.dart';

class DecodedStyle {
  final String title;
  final EditDocument document;
  const DecodedStyle(this.title, this.document);
}

/// Legacy API retained for existing callers and older template.json packages.
class StyleArchiveCodec {
  static Uint8List encode(
    String title,
    EditDocument document,
    Map<String, List<int>> resources,
  ) => WatermarkPackageCodec.encode(
    WatermarkPackageCodec.customConfig(title, document),
    resources,
  );
  static DecodedStyle decode(List<int> bytes) {
    final package = WatermarkPackageCodec.decode(bytes);
    return DecodedStyle(package.title, package.document);
  }
}

/// Matches both input budgets of WatermarkBundleImporter. Child ZIP payloads
/// are already validated by the package builder (or original-package reader).
class WatermarkExportBudget {
  int _compressed = 0, _expanded = 0;
  void add({required int compressedBytes, required int expandedBytes}) {
    if (compressedBytes < 0 ||
        expandedBytes < 0 ||
        compressedBytes > watermarkZipLimit ||
        _compressed + compressedBytes > watermarkBundleZipLimit - 1024 * 1024) {
      throw const FormatException('水印集合超过 1 GiB，请分批导出');
    }
    if (_expanded + expandedBytes > watermarkBundleTotalLimit) {
      throw const FormatException('水印集合解压后超过 2 GiB，请分批导出');
    }
    _compressed += compressedBytes;
    _expanded += expandedBytes;
  }
}

class TemplateArchive {
  final EditorController editor;
  TemplateArchive(this.editor);
  Future<({Uint8List bytes, int expandedBytes, bool staruw, bool preserved})>
  _sharedPackage(StylePreset style) async {
    if (editor.directory == null) throw StateError('存储不可用');
    final repo = WatermarkRepository(editor.directory!);
    // Export the saved design on the package definition, never the active photo.
    final document =
        await editor.templatePreferences?.remembered(
          style.document.packageId.isEmpty
              ? style.id
              : style.document.packageId,
          style.document,
        ) ??
        style.document;
    final savedStyle = StylePreset(
      style.id,
      style.title,
      style.image,
      document,
      factory: style.factory,
      category: style.category,
      categoryTitle: style.categoryTitle,
    );
    final package = await WatermarkPackageBuilder(repo, editor.modelCatalog)
        .build(
          savedStyle,
          forSharing: true,
          renderedPreview: document.staruwPreserved
              ? null
              : await _preview(document),
        );
    final bytes = document.staruwPreserved
        ? await repo.originalStaruwArchive(document.packageId)
        : WatermarkPackageCodec.encode(package.config, package.files);
    // Read the actual produced archive's sizes without inflating its resources.
    final directory = ZipDirectory()..read(InputMemoryStream(bytes));
    return (
      bytes: bytes,
      expandedBytes: directory.fileHeaders.fold<int>(
        0,
        (sum, file) => sum + file.uncompressedSize,
      ),
      staruw: package.document.isStaruw,
      preserved: document.staruwPreserved,
    );
  }

  Future<void> exportStyle(StylePreset style) => exportStyles([style]);

  Future<void> exportStyles(List<StylePreset> styles) async {
    if (editor.directory == null) throw StateError('存储不可用');
    if (styles.isEmpty ||
        styles.length > 100 ||
        styles.map((style) => style.id).toSet().length != styles.length) {
      throw const FormatException('一次可导出 1–100 款水印');
    }
    final multiple = styles.length > 1;
    final file = File(
      '${editor.directory!.path}/watermark-${multiple ? 'bundle-' : ''}${DateTime.now().microsecondsSinceEpoch}.zip',
    );
    try {
      var staruw = false, preserved = false;
      if (!multiple) {
        final shared = await _sharedPackage(styles.single);
        staruw = shared.staruw;
        preserved = shared.preserved;
        await file.writeAsBytes(shared.bytes, flush: true);
      } else {
        // ZIP children are already compressed. Write one child at a time without
        // holding every model/font/image package or recompressing them together.
        final output = OutputFileStream(file.path);
        final zip = ZipEncoder()..startEncode(output);
        final entries = <Map<String, dynamic>>[];
        final budget = WatermarkExportBudget();
        try {
          for (var index = 0; index < styles.length; index++) {
            final shared = await _sharedPackage(styles[index]);
            budget.add(
              compressedBytes: shared.bytes.length,
              expandedBytes: shared.expandedBytes,
            );
            final path =
                'watermark-${(index + 1).toString().padLeft(3, '0')}${shared.staruw ? '.staruw' : ''}.zip';
            entries.add({
              'path': path,
              'title': styles[index].title,
              'size': shared.bytes.length,
              'sha256': sha256.convert(shared.bytes).toString(),
            });
            zip.add(
              ArchiveFile(path, shared.bytes.length, shared.bytes)
                ..compression = CompressionType.none,
            );
            await Future<void>.delayed(Duration.zero);
          }
          final manifest = utf8.encode(
            jsonEncode({
              'format': 'com.realize.starmark.watermark-bundle',
              'bundleVersion': '1.0.0',
              'packages': entries,
            }),
          );
          zip.add(ArchiveFile('bundle.json', manifest.length, manifest));
          zip.endEncode();
        } finally {
          await output.close();
        }
      }
      final uri = await NativeBridge.call<String>('saveZip', {
        'path': file.path,
        'suggestedName': staruw
            ? file.uri.pathSegments.last.replaceFirst('.zip', '.staruw.zip')
            : file.uri.pathSegments.last,
      });
      if (uri != null) {
        editor.inform(
          multiple
              ? '已导出 ${styles.length} 款水印'
              : preserved
              ? '原始水印包已保存'
              : '完整水印 ZIP 已保存，包含配置与全部字体/图片资源',
        );
      }
    } finally {
      if (await file.exists()) await file.delete();
    }
  }

  Future<Uint8List> _preview(EditDocument document) async {
    final detached = EditorController(
      testMode: editor.testMode,
      writesDrafts: false,
      remembersDesign: false,
    );
    try {
      final clean = document.copyWith(
        staruwPhotoValues: const {},
        modelOverride: '',
        parametersOverride: '',
        xiaomiOptions: document.isStaruw
            ? const {}
            : Map<String, dynamic>.from(
                TemplatePreferences.capture(document)['xiaomiOptions'] as Map,
              ),
      );
      await detached.initializeTemplateFrom(editor, clean);
      await detached.prepareResources();
      final layout = detached.renderLayout,
          scale =
              480 /
              (layout.width > layout.height ? layout.width : layout.height);
      final width = (layout.width * scale).round().clamp(1, 480),
          height = (layout.height * scale).round().clamp(1, 480);
      final recorder = ui.PictureRecorder();
      WatermarkPainter(
        detached.renderDocument,
        detached.renderAsset,
        detached.renderAssets!,
      ).paint(
        ui.Canvas(recorder),
        ui.Size(width.toDouble(), height.toDouble()),
      );
      final picture = recorder.endRecording(),
          image = await picture.toImage(width, height);
      try {
        return (await image.toByteData(
          format: ui.ImageByteFormat.png,
        ))!.buffer.asUint8List();
      } finally {
        image.dispose();
        picture.dispose();
      }
    } finally {
      detached.dispose();
    }
  }

  Future<WatermarkImportResult?> importFromPicker({
    void Function(WatermarkImportProgress)? onProgress,
    bool Function()? isCancelled,
  }) async {
    if (editor.directory == null) throw StateError('存储不可用');
    final path = await NativeBridge.call<String>('pickZip');
    if (path == null) return null;
    final file = File(path);
    try {
      final result = await WatermarkBundleImporter(
        editor.watermarkRepository,
      ).importFile(file, onProgress: onProgress, isCancelled: isCancelled);
      await editor.persistStyles();
      editor.inform(
        '${result.cancelled ? '已停止，' : ''}已导入 ${result.installed} 款，跳过 ${result.skipped} 款，冲突 ${result.conflicts} 款，失败 ${result.failed} 款',
      );
      return result;
    } finally {
      // Native owns an explicit set of picker copies. Dart never guesses
      // ownership from a path/name and never deletes a caller's original.
      await NativeBridge.call<bool>('releasePickedZip', {'path': path});
    }
  }
}
