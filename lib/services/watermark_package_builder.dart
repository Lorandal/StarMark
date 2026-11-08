import 'dart:convert';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';
import 'package:watermark_engine/watermark_engine.dart';
import '../editor/model_lockup.dart';
import 'native_bridge.dart';
import 'imported_fonts.dart';
import 'region_assets.dart';
import 'watermark_package.dart';
import 'watermark_repository.dart';
import 'watermark_style.dart';
import 'preset_models.dart';
import 'photo_asset.dart';
import 'staruw/export.dart';
import 'staruw/native_migration.dart';

class WatermarkPackageBuilder {
  final WatermarkRepository repository;
  final List<Map<String, dynamic>> modelCatalog;
  WatermarkPackageBuilder(this.repository, this.modelCatalog);

  Future<WatermarkPackage> build(
    StylePreset style, {
    bool forSharing = false,
    Uint8List? renderedPreview,
  }) async {
    final document = withPresetModelTitle(style.document, modelCatalog);
    if (document.isStaruw) {
      return exportStaruw(style, repository, renderedPreview: renderedPreview);
    }
    final files = document.packageId.isEmpty
        ? <String, Uint8List>{}
        : await repository.files(document.packageId);
    final original = forSharing && document.xiaomiConfig != null
        ? await repository.originalFiles(document.packageId)
        : <String, Uint8List>{};
    final bindings = <String, dynamic>{};
    for (final group in [
      'fonts',
      'models',
      'brands',
      'modelMarks',
      'regions',
    ]) {
      bindings[group] = Map<String, dynamic>.from(
        document.resourceBindings[group] as Map? ?? {},
      );
    }
    Future<void> bundled(String key, String path, String group) async {
      final mapped = (bindings[group] as Map)[key] as String?;
      if (mapped != null && files.containsKey(mapped)) return;
      files[path] = (await rootBundle.load(path)).buffer.asUint8List();
      (bindings[group] as Map)[key] = path;
    }

    // Automatic binding and missing-glyph fallback must work on the recipient.
    for (final font in bundledFonts.where(
      (f) => f.selectable || f.id == document.font.id || f.id == 'redmi-sans',
    )) {
      await bundled(font.id, font.asset, 'fonts');
      if (font.license != null) {
        files[font.license!] = (await rootBundle.load(
          font.license!,
        )).buffer.asUint8List();
      }
    }
    if (document.font.source == 'imported' &&
        !(bindings['fonts'] as Map).containsKey(document.font.id)) {
      final bytes = await ImportedFonts(repository.base).bytes(document.font);
      final path = 'resources/fonts/${document.font.fingerprint}.ttf';
      files[path] = bytes;
      (bindings['fonts'] as Map)[document.font.id] = path;
    }
    if (document.font.source == 'system' &&
        !(bindings['fonts'] as Map).containsKey(document.font.id)) {
      // Only the existing public SystemFonts API can provide this font. Never
      // accept a path from an imported JSON or read a private system location.
      await NativeBridge.call<List>('fonts');
      final data = Map<String, dynamic>.from(
        (await NativeBridge.call<Map>('prepareFont', {
          'id': document.font.id,
        }))!,
      );
      final bytes = await File(data['path'] as String).readAsBytes();
      final hash = sha256.convert(bytes).toString();
      if (document.font.fingerprint.isNotEmpty &&
          hash != document.font.fingerprint) {
        throw const FormatException('系统字体已变化，请重新选择后导出');
      }
      final path = 'resources/fonts/$hash.ttf';
      files[path] = bytes;
      (bindings['fonts'] as Map)[document.font.id] = path;
    }
    for (final key in ModelLockupAssets.resourceKeys) {
      await bundled(key, 'assets/marks/$key', 'modelMarks');
    }
    for (final row in document.regionReplacements.values.whereType<Map>()) {
      final id = row['asset'];
      if (id is! String) continue;
      final mapped = (bindings['regions'] as Map)[id];
      final bytes = mapped is String && files.containsKey(mapped)
          ? files[mapped]!
          : await RegionAssets(repository.base).bytes(id);
      await RegionAssets.verify(id, bytes);
      final path = 'resources/regions/$id';
      files[path] = bytes;
      (bindings['regions'] as Map)[id] = path;
    }
    final previewStem = forSharing && document.xiaomiConfig != null
        ? 'staruw-preview'
        : 'preview';
    String preview = '$previewStem.webp';
    if (renderedPreview != null) {
      preview = '$previewStem.png';
      files[preview] = renderedPreview;
    } else if (style.image.startsWith('assets/')) {
      final extension = migrateBundledSample(
        style.image,
      ).split('.').last.toLowerCase();
      preview =
          '$previewStem.${['webp', 'png', 'jpg', 'jpeg'].contains(extension) ? extension : 'jpg'}';
      files[preview] = (await rootBundle.load(
        migrateBundledSample(style.image),
      )).buffer.asUint8List();
    } else if (forSharing && document.xiaomiConfig != null) {
      // A legacy library thumbnail may contain the user's photograph. The App
      // supplies a rendered synthetic preview; low-level callers get a sample.
      files[preview] = (await rootBundle.load(
        'assets/samples/watermark_standard.webp',
      )).buffer.asUint8List();
    } else {
      final original = File(style.image);
      if (await original.exists()) {
        final ext = style.image.split('.').last.toLowerCase();
        preview =
            '$previewStem.${['webp', 'png', 'jpg', 'jpeg'].contains(ext) ? ext : 'jpg'}';
        files[preview] = await original.readAsBytes();
      } else {
        files[preview] = (await rootBundle.load(
          'assets/samples/watermark_standard.webp',
        )).buffer.asUint8List();
      }
    }
    if (forSharing && document.xiaomiConfig != null) {
      return migrateStaruwNative(
        style,
        repository,
        original,
        files,
        bindings,
        preview,
        previewKind: renderedPreview != null
            ? 'rendered'
            : style.factory
            ? 'oem'
            : 'fixture',
      );
    }
    final detached = document.copyWith(
      packageId: '',
      resourceBindings: bindings,
    );
    final config = document.xiaomiConfig == null
        ? WatermarkPackageCodec.customConfig(
            style.title,
            detached,
            id: style.id,
          )
        : <String, dynamic>{
            ...document.xiaomiConfig!,
            'starmark': detached.toJson()..remove('xiaomiConfig'),
          };
    config['metadata'] = {
      ...Map<String, dynamic>.from(config['metadata'] as Map? ?? {}),
      'name': style.title,
      'preview': preview,
    };
    config['portable'] = true;
    files['config.json'] = Uint8List.fromList(utf8.encode(jsonEncode(config)));
    files.putIfAbsent('i18n.json', () => Uint8List.fromList(utf8.encode('{}')));
    files.remove('manifest.json');
    return WatermarkPackageCodec.fromFiles(files);
  }
}
