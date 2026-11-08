import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';
import 'factory_watermarks.dart';
import 'region_assets.dart';
import 'watermark_package.dart';
import 'watermark_style.dart';
import 'staruw/contract.dart';

class WatermarkRepository {
  final Directory base;
  WatermarkRepository(this.base);
  final Set<String> _staruwIds = {};
  // Catalog removal and resource lifetime are separate: existing draft history
  // and saved designs may still refer to a removed package's exact ID.
  Future<Set<String>> _removedIds() async {
    final file = File('${root.path}/.removed.json');
    if (!await file.exists()) return {};
    if (await file.length() > watermarkJsonLimit) {
      throw const FormatException('水印目录记录过大');
    }
    final data = WatermarkPackageCodec.parseJson(
      await file.readAsBytes(),
      'removed watermarks',
    );
    final ids = data['ids'];
    if (ids is! List ||
        ids.any(
          (id) =>
              id is! String || !RegExp(r'^[a-zA-Z0-9_-]{1,100}$').hasMatch(id),
        )) {
      throw const FormatException('水印目录记录无效');
    }
    return ids.cast<String>().toSet();
  }

  Future<void> removeFromLibrary(Iterable<String> ids) async {
    final selected = ids.toSet();
    final custom = (await list())
        .where((style) => !style.factory)
        .map((style) => style.id)
        .toSet();
    if (selected.isEmpty ||
        selected.any(
          (id) => !custom.contains(id) || id.startsWith('factory-'),
        )) {
      throw const FormatException('仅可删除自定义水印');
    }
    final removed = await _removedIds()
      ..addAll(selected);
    final target = File('${root.path}/.removed.json');
    final pending = File('${target.path}.tmp');
    try {
      await pending.writeAsString(
        jsonEncode({'version': 1, 'ids': removed.toList()..sort()}),
        flush: true,
      );
      await pending.rename(target.path);
    } finally {
      if (await pending.exists()) await pending.delete();
    }
  }

  Directory get root => Directory('${base.path}/watermarks');

  /// A deleted catalog entry can still be needed by a previously exported
  /// editable photo. Resolve only local, validated payloads, never photo paths.
  Future<StylePreset?> retainedStyle(String id) async {
    if (!RegExp(r'^[a-zA-Z0-9_-]{1,100}$').hasMatch(id) ||
        !(await _removedIds()).contains(id)) {
      return null;
    }
    try {
      final package = WatermarkPackageCodec.fromFiles(await files(id));
      return StylePreset(
        id,
        package.title,
        package.preview.isEmpty
            ? 'assets/samples/watermark_standard.webp'
            : resource(id, package.preview).path,
        package.document.copyWith(packageId: id),
      );
    } on FormatException {
      return null;
    } on FileSystemException {
      return null;
    }
  }

  Directory directory(String id) {
    if (!RegExp(r'^[a-zA-Z0-9_-]{1,100}$').hasMatch(id)) {
      throw const FormatException('无效水印 ID');
    }
    return Directory('${root.path}/$id');
  }

  File resource(String id, String path) {
    if (!safeWatermarkPath(path)) throw const FormatException('无效资源路径');
    final dir = directory(id);
    if (_staruwIds.contains(id) ||
        File('${dir.path}/staruw-local.json').existsSync()) {
      _staruwIds.add(id);
      return File('${dir.path}/payload/$path');
    }
    return File('${dir.path}/$path');
  }

  Future<StylePreset> install(
    WatermarkPackage package, {
    String? id,
    String? sourceDigest,
    String? sourceIdentity,
    bool Function()? isCancelled,
  }) async {
    if (package.document.isStaruw) {
      return _installStaruw(package, id: id, isCancelled: isCancelled);
    }
    WatermarkPackageCodec.validateFiles(package.files);
    staruwNeed(
      !package.files.containsKey('staruw-local.json'),
      'E_PATH',
      '文件名与本地存储标记冲突',
    );
    for (final entry
        in (package.document.resourceBindings['regions'] as Map? ?? {})
            .entries) {
      final bytes = package.files[entry.value];
      if (bytes == null) throw const FormatException('Missing region asset');
      await RegionAssets.verify(entry.key.toString(), bytes);
    }
    sourceDigest ??=
        package.sourceDigest ?? canonicalWatermarkDigest(package.files);
    sourceIdentity ??=
        (package.config['metadata'] as Map?)?['id']?.toString() ?? '';
    await root.create(recursive: true);
    final token =
        '${DateTime.now().microsecondsSinceEpoch}-${Random.secure().nextInt(1 << 32)}';
    final targetId = id ?? 'user-$token';
    if (FactoryWatermarkCatalog.isFactoryId(targetId)) {
      throw const FormatException('内置水印 ID 不能用于用户导入');
    }
    final target = directory(targetId);
    if (await target.exists()) throw const FormatException('水印 ID 已存在，不会覆盖');
    final temp = Directory('${root.path}/.pending-$targetId');
    await temp.create();
    try {
      final config = Map<String, dynamic>.from(package.config);
      final document = package.document.copyWith(packageId: targetId);
      if (package.xiaomi) {
        config['starmark'] = document.toJson()..remove('xiaomiConfig');
      } else {
        config['document'] = document.toJson();
      }
      for (final entry in package.files.entries) {
        if (['config.json', 'meta.json', 'manifest.json'].contains(entry.key)) {
          continue;
        }
        final file = File('${temp.path}/${entry.key}');
        await file.parent.create(recursive: true);
        await file.writeAsBytes(entry.value, flush: true);
      }
      final metadata = {
        ...Map<String, dynamic>.from(config['metadata'] as Map? ?? {}),
        'localId': targetId,
        'displayName': package.title,
      };
      await File('${temp.path}/meta.json').writeAsString(
        jsonEncode({
          'version': 1,
          'metadata': metadata,
          'sourceDigest': sourceDigest,
          'sourceIdentity': sourceIdentity,
        }),
        flush: true,
      );
      await File(
        '${temp.path}/config.json',
      ).writeAsString(jsonEncode(config), flush: true);
      if (isCancelled?.call() == true) throw const WatermarkInstallCancelled();
      // Preserve the imported input outside the render payload and never export
      // this private recovery backup as a nested archive.
      if (package.originalArchive != null) {
        final backup = File('${root.path}/.sources/$targetId.zip');
        await backup.parent.create(recursive: true);
        await backup.writeAsBytes(package.originalArchive!, flush: true);
      }
      await temp.rename(target.path);
      return StylePreset(
        targetId,
        package.title,
        package.preview.isEmpty
            ? 'assets/samples/watermark_standard.webp'
            : '${target.path}/${package.preview}',
        document,
      );
    } catch (_) {
      if (await temp.exists()) await temp.delete(recursive: true);
      rethrow;
    }
  }

  Future<Map<String, Uint8List>> originalFiles(String id) async {
    directory(
      id,
    ); // Validate local identifier before constructing a backup path.
    final backup = File('${root.path}/.sources/$id.zip');
    if (await backup.exists()) {
      staruwNeed(
        await backup.length() <= watermarkZipLimit,
        'E_LIMIT',
        '本地原包备份过大',
      );
      return Map.of(
        WatermarkPackageCodec.decode(await backup.readAsBytes()).files,
      );
    }
    return files(id);
  }

  Future<Map<String, Uint8List>> files(String id) async {
    if (FactoryWatermarkCatalog.isFactoryId(id)) {
      return (await FactoryWatermarkCatalog.load()).files(id);
    }
    final result = <String, Uint8List>{};
    var dir = directory(id);
    if (await File('${dir.path}/staruw-local.json').exists()) {
      _staruwIds.add(id);
      dir = Directory('${dir.path}/payload');
    }
    if (!await dir.exists()) throw const FormatException('水印资源目录不存在');
    var total = 0;
    await for (final entry in dir.list(recursive: true, followLinks: false)) {
      if (entry is Link) throw const FormatException('水印目录不接受符号链接');
      if (entry is! File) continue;
      final path = entry.path
          .substring(dir.path.length + 1)
          .replaceAll('\\', '/');
      final length = await entry.length();
      total += length;
      if (length > watermarkFileLimit ||
          total > watermarkTotalLimit ||
          result.length >= 512) {
        throw const FormatException('水印资源过大');
      }
      result[path] = await entry.readAsBytes();
    }
    return result;
  }

  /// Bounded provenance metadata, without loading font/image payloads.
  Future<List<({String digest, String identity})>> importRecords() async {
    final records = <({String digest, String identity})>[];
    if (!await root.exists()) return records;
    final removed = await _removedIds();
    await for (final dir in root.list(followLinks: false)) {
      if (dir is! Directory ||
          removed.contains(dir.path.split(Platform.pathSeparator).last) ||
          dir.path.split(Platform.pathSeparator).last.startsWith('.')) {
        continue;
      }
      try {
        final meta = File('${dir.path}/meta.json');
        if (await meta.length() > watermarkJsonLimit) continue;
        final json = WatermarkPackageCodec.parseJson(
          await meta.readAsBytes(),
          'meta.json',
        );
        final identity =
            json['sourceIdentity']?.toString() ??
            (json['metadata'] as Map?)?['id']?.toString() ??
            '';
        if (json['sourceDigest'] is String || identity.isNotEmpty) {
          records.add((
            digest: json['sourceDigest']?.toString() ?? '',
            identity: identity,
          ));
        }
      } catch (_) {
        /* An unrelated incomplete package never blocks imports. */
      }
    }
    return records;
  }

  Future<List<StylePreset>> list() async {
    if (!await root.exists()) return [];
    final removed = await _removedIds();
    final styles = <StylePreset>[];
    await for (final entry in root.list(followLinks: false)) {
      if (entry is! Directory ||
          entry.path.split(Platform.pathSeparator).last.startsWith('.')) {
        continue;
      }
      final id = entry.path.split(Platform.pathSeparator).last;
      if (removed.contains(id)) continue;
      try {
        final localFile = File('${entry.path}/staruw-local.json');
        if (await localFile.exists()) {
          if (await localFile.length() > watermarkJsonLimit) continue;
          final local = WatermarkPackageCodec.parseJson(
            await localFile.readAsBytes(),
            'staruw-local.json',
          );
          _staruwIds.add(id);
          final configFile = resource(id, 'config.json');
          if (await configFile.length() > watermarkJsonLimit) continue;
          final config = WatermarkPackageCodec.parseJson(
            await configFile.readAsBytes(),
            'config.json',
          );
          final preview = local['preview'] as String? ?? '';
          if (preview.isNotEmpty && !safeWatermarkPath(preview)) continue;
          styles.add(
            StylePreset(
              id,
              local['title'] as String,
              preview.isEmpty
                  ? 'assets/samples/watermark_standard.webp'
                  : resource(id, preview).path,
              WatermarkPackageCodec.staruwCatalogDocument(
                config,
                Map<String, dynamic>.from(local['report'] as Map),
              ).copyWith(packageId: id),
            ),
          );
          continue;
        }
        // Import, selection and export validate every resource. The catalog only
        // needs bounded JSON; rereading hundreds of MB of fonts delays startup.
        Future<Map<String, dynamic>> json(String name) async {
          final file = resource(id, name);
          if (await file.length() > watermarkJsonLimit) {
            throw const FormatException('配置过大');
          }
          return WatermarkPackageCodec.parseJson(
            await file.readAsBytes(),
            name,
          );
        }

        final config = await json('config.json');
        final metadata = Map<String, dynamic>.from(
          config['metadata'] as Map? ?? {},
        );
        final names = <String>[];
        await for (final item in entry.list(
          recursive: true,
          followLinks: false,
        )) {
          if (item is Link) throw const FormatException('不接受符号链接');
          if (item is File) {
            names.add(
              item.path.substring(entry.path.length + 1).replaceAll('\\', '/'),
            );
          }
        }
        final document = WatermarkPackageCodec.documentFromConfig(
          utf8.encode(jsonEncode(config)),
          resourceNames: names,
        );
        var title = metadata['name']?.toString() ?? '导入的水印';
        if (await resource(id, 'meta.json').exists()) {
          title =
              ((await json('meta.json'))['metadata'] as Map?)?['displayName']
                  ?.toString() ??
              title;
        }
        var preview = metadata['preview']?.toString() ?? '';
        if (preview.isNotEmpty && !safeWatermarkPath(preview)) {
          throw const FormatException('无效预览路径');
        }
        if (preview.isEmpty) {
          for (final path in ['preview.png', 'preview.webp', 'preview.jpg']) {
            if (await resource(id, path).exists()) {
              preview = path;
              break;
            }
          }
        }
        styles.add(
          StylePreset(
            id,
            title,
            preview.isEmpty
                ? 'assets/samples/watermark_standard.webp'
                : '${entry.path}/$preview',
            document.copyWith(packageId: id),
          ),
        );
      } catch (_) {
        /* Preserve incomplete packages for recovery; never delete a user package on scan. */
      }
    }
    return styles..sort((a, b) => b.id.compareTo(a.id));
  }

  Future<StylePreset> _installStaruw(
    WatermarkPackage input, {
    String? id,
    bool Function()? isCancelled,
  }) async {
    final package = WatermarkPackageCodec.fromFiles(input.files);
    final report = package.document.staruwReport;
    final identity = report['identity'] as Map?;
    final identityKey = identity == null
        ? ''
        : '${identity['id']}@${identity['revision']}';
    final digest = package.sourceDigest!;
    final records = await importRecords();
    staruwNeed(
      !records.any(
        (r) =>
            r.identity == identityKey &&
            identityKey.isNotEmpty &&
            r.digest != digest,
      ),
      'E_ID_CONFLICT',
      '相同模板和修订已有不同内容，未覆盖',
    );
    await root.create(recursive: true);
    final token =
        '${DateTime.now().microsecondsSinceEpoch}-${Random.secure().nextInt(1 << 32)}';
    final targetId = id ?? 'user-$token';
    staruwNeed(
      !FactoryWatermarkCatalog.isFactoryId(targetId),
      'E_ID_CONFLICT',
      '不能覆盖内置模板',
    );
    final target = directory(targetId);
    staruwNeed(!await target.exists(), 'E_ID_CONFLICT', '模板目录已存在');
    final pending = Directory('${root.path}/.pending-$targetId');
    await pending.create();
    try {
      for (final entry in package.files.entries) {
        if (isCancelled?.call() == true) {
          throw const WatermarkInstallCancelled();
        }
        final file = File('${pending.path}/payload/${entry.key}');
        await file.parent.create(recursive: true);
        await file.writeAsBytes(entry.value, flush: true);
      }
      if (input.originalArchive != null) {
        await File(
          '${pending.path}/original.staruw.zip',
        ).writeAsBytes(input.originalArchive!, flush: true);
      }
      final local = {
        'title': package.title,
        'preview': package.preview,
        'report': report,
      };
      await File(
        '${pending.path}/staruw-local.json',
      ).writeAsString(jsonEncode(local), flush: true);
      await File('${pending.path}/meta.json').writeAsString(
        jsonEncode({
          'version': 1,
          'metadata': {'localId': targetId, 'displayName': package.title},
          'sourceDigest': digest,
          'sourceIdentity': identityKey,
        }),
        flush: true,
      );
      if (isCancelled?.call() == true) throw const WatermarkInstallCancelled();
      await pending.rename(target.path);
      _staruwIds.add(targetId);
      return StylePreset(
        targetId,
        package.title,
        package.preview.isEmpty
            ? 'assets/samples/watermark_standard.webp'
            : resource(targetId, package.preview).path,
        package.document.copyWith(packageId: targetId),
      );
    } catch (_) {
      if (await pending.exists()) await pending.delete(recursive: true);
      rethrow;
    }
  }

  /// Preserve means original download, never an edited synthetic replacement.
  Future<Uint8List> originalStaruwArchive(String id) async {
    final original = File('${directory(id).path}/original.staruw.zip');
    if (await original.exists()) {
      staruwNeed(
        await original.length() <= watermarkZipLimit,
        'E_LIMIT',
        '原包过大',
      );
      final bytes = await original.readAsBytes();
      WatermarkPackageCodec.decode(bytes);
      return bytes;
    }
    final payload = await files(id);
    final package = WatermarkPackageCodec.fromFiles(payload);
    return WatermarkPackageCodec.encode(package.config, payload);
  }
}

class WatermarkInstallCancelled implements Exception {
  const WatermarkInstallCancelled();
}

