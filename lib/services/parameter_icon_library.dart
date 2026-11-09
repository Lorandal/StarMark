import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:flutter_svg/flutter_svg.dart';
import 'package:vector_graphics/vector_graphics.dart' as vg;
import '../editor/model_lockup.dart';
import 'builtin_emblems.dart';
import 'region_assets.dart';
import 'watermark_repository.dart';

/// Original artwork stays content-addressed. The native gallery reads only
/// small cached PNGs, so it never decodes a full imported image while scrolling.
class ParameterIconLibrary {
  ParameterIconLibrary(this.base);
  final Directory base;
  File get _index => File('${base.path}/parameter-icons.json');
  Map<String, String>? _titles;
  Set<String> _removed = {};
  int removalRevision = 0;
  Set<String> get removed => Set.unmodifiable(_removed);
  Future<void> initialize() => _load();
  final _verified = <String>{};
  final _thumbnailJobs = <String, Future<String>>{};
  Future<void>? _loading;
  Future<List<Map<String, dynamic>>>? _listing;
  Future<void> _tail = Future.value();

  Future<void> _load() => _loading ??= _readIndex();
  Future<void> _readIndex() async {
    if (_titles != null) return;
    _titles = {};
    for (final path in [_index.path, '${_index.path}.bak']) {
      try {
        final json = jsonDecode(await File(path).readAsString()) as Map;
        if (json['version'] != 1) continue;
        _removed = {
          for (final id in (json['removed'] as List? ?? const []))
            if (id is String &&
                RegionAssets.validId(id) &&
                !builtinEmblems.any((e) => e['asset'] == id))
              id,
        };
        removalRevision++;
        _titles = {
          for (final row in (json['icons'] as List).cast<Map>())
            if (row['asset'] is String &&
                RegionAssets.validId(row['asset']) &&
                row['title'] is String)
              row['asset'] as String: row['title'] as String,
        };
        break;
      } catch (_) {
        /* Recover the last complete catalog. */
      }
    }
  }

  Future<void> ensureAsset(String asset) async {
    if (!RegionAssets.validId(asset)) throw const FormatException('无效图标素材');
    if (_verified.contains(asset)) return;
    final builtin = builtinEmblems
        .where((e) => e['asset'] == asset)
        .firstOrNull;
    if (builtin != null) {
      await RegionAssets(
        base,
      ).importBytes(await builtinEmblemBytes(builtin['id']!));
    }
    await RegionAssets(base).bytes(asset);
    _verified.add(asset);
  }

  Future<void> register(String asset, String title, {bool restore = false}) {
    final job = _tail.then((_) async {
      await _load();
      if (_removed.contains(asset) && !restore) return;
      await ensureAsset(asset);
      if (builtinEmblems.any((e) => e['asset'] == asset) ||
          _titles!.containsKey(asset)) {
        return;
      }
      final clean = title.replaceAll('\\', '/').split('/').last.trim();
      _titles![asset] = clean.isEmpty
          ? '自定义图标'
          : clean.substring(0, math.min(clean.length, 120));
      final wasRemoved = _removed.remove(asset);
      if (wasRemoved) removalRevision++;
      try {
        await _save();
      } catch (_) {
        _titles!.remove(asset);
        if (wasRemoved) {
          _removed.add(asset);
          removalRevision++;
        }
        rethrow;
      }
    });
    _tail = job.catchError((Object _) {});
    return job;
  }

  Future<String> importBytes(Uint8List bytes, String title) async {
    final asset = await RegionAssets(base).importBytes(bytes);
    await _thumbnail(asset);
    await register(asset, title, restore: true);
    return asset;
  }

  Future<void> delete(String asset) {
    final job = _tail.then((_) async {
      await _load();
      if (!RegionAssets.validId(asset) ||
          builtinEmblems.any((e) => e['asset'] == asset) ||
          !_titles!.containsKey(asset)) {
        throw const FormatException('仅可删除已添加的自定义图标');
      }
      final title = _titles!.remove(asset)!;
      _removed.add(asset);
      try {
        await _save();
        removalRevision++;
      } catch (_) {
        _titles![asset] = title;
        _removed.remove(asset);
        rethrow;
      }
      // Originals are shared with signatures and package resources. Removing
      // a gallery item must not destroy those unrelated uses.
    });
    _tail = job.catchError((Object _) {});
    return job;
  }

  Future<void> _save() async {
    await base.create(recursive: true);
    final temp = File('${_index.path}.tmp'),
        backup = File('${_index.path}.bak');
    final data = jsonEncode({
      'version': 1,
      'removed': _removed.toList(),
      'icons': [
        for (final e in _titles!.entries) {'asset': e.key, 'title': e.value},
      ],
    });
    await temp.writeAsString(data, flush: true);
    final recovery = File('${backup.path}.tmp');
    await recovery.writeAsString(data, flush: true);
    if (await _index.exists()) {
      if (await backup.exists()) await backup.delete();
      await _index.rename(backup.path);
    }
    try {
      await temp.rename(_index.path);
      // A confirmed deletion must survive recovery from a corrupt primary.
      // Keep the recovery catalog on the same committed deletion state.
      await recovery.rename(backup.path);
    } catch (_) {
      if (await backup.exists()) await backup.copy(_index.path);
      rethrow;
    }
  }

  Future<List<Map<String, dynamic>>> list({
    Iterable<dynamic> sources = const [],
  }) {
    return _listing ??= _list(sources: sources).whenComplete(() {
      _listing = null;
    });
  }

  Future<List<Map<String, dynamic>>> _list({
    Iterable<dynamic> sources = const [],
  }) async {
    await _load();
    final referenced = <String>{};
    final packaged = <String, List<(String, String)>>{};
    void collect(dynamic value) {
      if (value is List) {
        for (final child in value) {
          collect(child);
        }
        return;
      }
      if (value is! Map) return;
      final packageId = value['packageId'];
      final regions = (value['resourceBindings'] as Map?)?['regions'];
      if (packageId is String && packageId.isNotEmpty && regions is Map) {
        for (final binding in regions.entries) {
          if (binding.key is String &&
              RegionAssets.validId(binding.key) &&
              binding.value is String) {
            (packaged[binding.key] ??= []).add((packageId, binding.value));
          }
        }
      }
      final p = value['parameters'];
      if (p is Map &&
          p['asset'] is String &&
          RegionAssets.validId(p['asset'])) {
        referenced.add(p['asset']);
      }
      // Both draft documents and remembered StarUW design records retain the
      // image binding. Signature images use `fit`; parameter icons use `shape`.
      final assets =
          value['assets'] ??
          (value['resourceBindings'] as Map?)?['staruwAssets'];
      if (assets is Map) {
        final choices = [
          value['values'],
          value['staruwValues'],
          value['staruwPhotoValues'],
        ];
        for (final choice
            in choices
                .whereType<Map>()
                .expand((m) => m.values)
                .whereType<Map>()) {
          final id = assets[choice['resourceId']];
          if (choice.containsKey('shape') &&
              id is String &&
              RegionAssets.validId(id)) {
            referenced.add(id);
          }
        }
      }
      for (final child in value.values) {
        collect(child);
      }
    }

    for (final source in sources) {
      collect(source);
    }
    final preferences = File('${base.path}/template-preferences-v1.json');
    try {
      if (await preferences.exists()) {
        collect(jsonDecode(await preferences.readAsString()));
      }
    } catch (_) {}
    for (final asset in referenced) {
      if (_removed.contains(asset)) continue;
      try {
        try {
          await ensureAsset(asset);
        } catch (_) {
          // A freshly installed native package owns its own region resources.
          // Retain a verified copy before exposing the icon outside that package.
          for (final (packageId, path)
              in packaged[asset] ?? <(String, String)>[]) {
            try {
              final bytes = await WatermarkRepository(
                base,
              ).resource(packageId, path).readAsBytes();
              await RegionAssets.verify(asset, bytes);
              await RegionAssets(base).importBytes(bytes);
              break;
            } catch (_) {
              // Another history entry may point to an available copy.
            }
          }
        }
        await register(asset, '已上传图标');
      } catch (_) {
        /* Missing old artwork is not a selectable icon. */
      }
    }
    final rows = <Map<String, dynamic>>[];
    for (final item in [
      for (final e in builtinEmblems)
        {'asset': e['asset']!, 'title': e['title']!},
      for (final e in _titles!.entries) {'asset': e.key, 'title': e.value},
    ]) {
      try {
        if (_removed.contains(item['asset'])) continue;
        await ensureAsset(item['asset']!);
        rows.add({
          ...item,
          'id': item['asset'],
          'deletable': !builtinEmblems.any((e) => e['asset'] == item['asset']),
          'image': await _thumbnail(item['asset']!),
        });
      } catch (_) {
        /* Retain original records for recovery, skip unavailable tiles. */
      }
    }
    return rows;
  }

  Future<String> _thumbnail(String asset) =>
      _thumbnailJobs[asset] ??= _writeThumbnail(asset).whenComplete(() {
        _thumbnailJobs.remove(asset);
      });

  Future<String> _writeThumbnail(String asset) async {
    final file = File('${base.path}/parameter-icon-thumbnails/$asset.png');
    if (await file.exists()) return file.path;
    final bytes = await RegionAssets(base).bytes(asset);
    ui.Image? image;
    vg.PictureInfo? vector;
    ui.Picture? recording;
    ui.Image? thumbnail;
    try {
      ui.Size size;
      if (asset.endsWith('.svg')) {
        vector = await vg.vg.loadPicture(
          SvgStringLoader(ModelLockupAssets.inlineFills(utf8.decode(bytes))),
          null,
        );
        size = vector.size;
      } else {
        final buffer = await ui.ImmutableBuffer.fromUint8List(bytes);
        ui.ImageDescriptor? descriptor;
        ui.Codec? codec;
        try {
          descriptor = await ui.ImageDescriptor.encoded(buffer);
          final sample = math.min(
            1.0,
            144 / math.max(descriptor.width, descriptor.height),
          );
          codec = await descriptor.instantiateCodec(
            targetWidth: math.max(1, (descriptor.width * sample).round()),
            targetHeight: math.max(1, (descriptor.height * sample).round()),
          );
          image = (await codec.getNextFrame()).image;
        } finally {
          codec?.dispose();
          descriptor?.dispose();
          buffer.dispose();
        }
        size = ui.Size(image.width.toDouble(), image.height.toDouble());
      }
      const edge = 144.0;
      final scale = math.min(edge / size.width, edge / size.height);
      final recorder = ui.PictureRecorder();
      final canvas = ui.Canvas(recorder)
        ..translate(
          (edge - size.width * scale) / 2,
          (edge - size.height * scale) / 2,
        )
        ..scale(scale);
      if (vector != null) {
        canvas.drawPicture(vector.picture);
      } else {
        canvas.drawImage(
          image!,
          ui.Offset.zero,
          ui.Paint()..filterQuality = ui.FilterQuality.high,
        );
      }
      recording = recorder.endRecording();
      thumbnail = await recording.toImage(144, 144);
      final data = (await thumbnail.toByteData(
        format: ui.ImageByteFormat.png,
      ))!;
      await file.parent.create(recursive: true);
      final pending = File('${file.path}.tmp');
      await pending.writeAsBytes(
        data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
        flush: true,
      );
      await pending.rename(file.path);
      return file.path;
    } finally {
      image?.dispose();
      vector?.picture.dispose();
      recording?.dispose();
      thumbnail?.dispose();
    }
  }
}
