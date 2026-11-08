import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';
import '../watermark_package.dart';
import '../watermark_repository.dart';
import '../watermark_style.dart';
import '../region_assets.dart';
import '../imported_fonts.dart';
import 'package:watermark_engine/watermark_engine.dart';
import 'contract.dart';
import 'controls.dart';
import 'package_validation.dart';

/// Persistent migration/copy identity, isolated from payloads and photo drafts.
class StaruwIdentities {
  final Directory base;
  StaruwIdentities(this.base);
  static final Map<String, Future<void>> _tails = {};
  Future<String> forSource(String key) {
    final path = '${base.path}/staruw-identities-v1.json';
    final result = (_tails[path] ?? Future<void>.value()).then((_) async {
      final file = File(path);
      var ids = <String, dynamic>{};
      if (await file.exists()) {
        staruwNeed(await file.length() <= 4 * 1024 * 1024, 'E_LIMIT', '身份映射过大');
        ids = Map<String, dynamic>.from(
          staruwJson(await file.readAsBytes(), 'staruw-identities-v1.json')
              as Map,
        );
      }
      if (ids[key] is String) return ids[key] as String;
      final random = Random.secure(),
          bytes = List.generate(16, (_) => random.nextInt(256));
      bytes[6] = (bytes[6] & 15) | 0x40;
      bytes[8] = (bytes[8] & 63) | 0x80;
      final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
      final id =
          '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
      ids[key] = id;
      await base.create(recursive: true);
      final temporary = File('$path.tmp');
      await temporary.writeAsString(jsonEncode(ids), flush: true);
      await temporary.rename(path);
      return id;
    });
    _tails[path] = result.then<void>(
      (_) {},
      onError: (Object _, StackTrace _) {},
    );
    return result;
  }
}

Future<Map<String, Uint8List>> staruwAddedFiles(
  EditDocument document,
  Directory base,
) async {
  final result = <String, Uint8List>{},
      u = document.staruwConfig!['staruw'] as Map;
  for (final e
      in (document.resourceBindings['staruwAssets'] as Map? ?? {}).entries) {
    final r = u['resources'][e.key] as Map?;
    staruwNeed(r != null && r['kind'] == 'image', 'E_RESOURCE', '新增图片未登记');
    final bytes = await RegionAssets(base).bytes(e.value as String);
    staruwNeed(staruwHash(bytes) == r!['sha256'], 'E_HASH', '新增图片摘要不一致');
    result[r['path'] as String] = bytes;
  }
  for (final e
      in (document.resourceBindings['staruwFonts'] as Map? ?? {}).entries) {
    final r = u['resources'][e.key] as Map?;
    staruwNeed(r != null && r['kind'] == 'font', 'E_RESOURCE', '新增字体未登记');
    final ref = FontRef.fromJson(Map<String, dynamic>.from(e.value as Map));
    staruwNeed(ref.source == 'imported', 'E_RESOURCE', '新增字体必须复制到应用字库');
    final bytes = await ImportedFonts(base).bytes(ref);
    staruwNeed(staruwHash(bytes) == r!['sha256'], 'E_HASH', '新增字体摘要不一致');
    result[r['path'] as String] = bytes;
  }
  return result;
}

Future<WatermarkPackage> exportStaruw(
  StylePreset style,
  WatermarkRepository repository, {
  Uint8List? renderedPreview,
}) async {
  final doc = style.document;
  final payload = await repository.files(doc.packageId),
      original = WatermarkPackageCodec.fromFiles(payload);
  staruwNeed(
    original.document.staruwReport['payloadDigest'] ==
        doc.staruwReport['payloadDigest'],
    'E_HASH',
    '模板已变化，不能替换冻结的草稿资源',
  );
  if (doc.staruwPreserved) return original;
  final values = StaruwControls(doc).captureTemplate();
  final config =
      jsonDecode(jsonEncode(doc.staruwConfig)) as Map<String, dynamic>;
  final u = config['staruw'] as Map;
  u['defaults'] = values;
  u['metadata']['name'] = style.title;
  if (staruwEqual(config, original.config)) return original;
  final previewFiles = <String, Uint8List>{};
  if (renderedPreview != null) {
    final hash = staruwHash(renderedPreview),
        path = 'previews/$hash.png',
        rid = 'preview$hash';
    u['resources'][rid] = {
      'path': path,
      'kind': 'image',
      'mediaType': 'image/png',
      'sha256': hash,
    };
    u['metadata']['preview'] = path;
    u['metadata']['previewKind'] = 'rendered';
    previewFiles[path] = renderedPreview;
  }
  final identity = original.config['staruw']['identity'] as Map;
  final key = staruwHash(
    utf8.encode(
      jsonEncode({
        'source': doc.staruwReport['payloadDigest'],
        'config': config,
      }),
    ),
  );
  final id = await StaruwIdentities(repository.base).forSource('copy:$key');
  u['identity'] = {
    'id': id,
    'revision': 1,
    'derivedFrom': {
      'id': identity['id'],
      'revision': identity['revision'],
      'payloadDigest': doc.staruwReport['payloadDigest'],
    },
  };
  final files = staruwSeal({
    ...payload,
    ...await staruwAddedFiles(doc, repository.base),
    ...previewFiles,
    'config.json': utf8.encode(jsonEncode(config)),
  });
  return WatermarkPackageCodec.fromFiles(files);
}
