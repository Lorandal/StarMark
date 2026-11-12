import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:watermark_engine/watermark_engine.dart';

import 'photo_asset.dart';

/// Owns only draft records and imported, app-private photo copies. Templates,
/// fonts, gallery originals and caches without proven ownership are never freed.
class DraftStore {
  DraftStore(Directory directory, {required this.retainedReferences})
    : _requestedDirectory = directory;

  final Directory _requestedDirectory;
  Directory? _canonicalDirectory;
  Directory get directory => _canonicalDirectory ?? _requestedDirectory;
  final Iterable<Object?> Function() retainedReferences;
  static const limit = 20;
  static final _queues = <String, Future<void>>{};
  final _pending = <Map<String, dynamic>>{};
  final removedIds = <String>{};
  bool sampleAvailable = true;

  String get _root => _key(directory.path);
  String get _queueKey => _key(_requestedDirectory.path);
  File get _initialized => File('${directory.path}/draft-lifecycle-v1.json');
  File get _dismissed => File('${directory.path}/sample-dismissed-v1');
  File get _cleanup => File('${directory.path}/draft-cleanup-v1.json');
  Directory get _folder => Directory('${directory.path}/drafts');
  Future<void> get pendingWrites => _queues[_queueKey] ?? Future.value();

  Future<T> _run<T>(Future<T> Function() operation) {
    final result = pendingWrites.then((_) => operation());
    final tail = result.then<void>(
      (_) {},
      onError: (Object _, StackTrace _) {},
    );
    _queues[_queueKey] = tail;
    unawaited(
      tail.then((_) {
        if (identical(_queues[_queueKey], tail)) _queues.remove(_queueKey);
      }),
    );
    return result;
  }

  Future<List<Map<String, dynamic>>> load() => _run(() async {
    await _loadLifecycle();
    return _maintain(await _scan());
  });

  Future<List<Map<String, dynamic>>> save(
    Map<String, dynamic> snapshot, {
    bool completesEditing = false,
  }) {
    snapshot = Map<String, dynamic>.from(
      jsonDecode(jsonEncode(snapshot)) as Map,
    );
    _validate(snapshot);
    _pending.add(snapshot);
    return _run(() async {
      await _loadLifecycle();
      if (sampleAvailable || (snapshot['photo'] as Map)['sample'] != true) {
        // A failed new write must never evict a previously complete draft.
        await _writeAtomic(
          File('${_folder.path}/${snapshot['id']}.json'),
          snapshot,
        );
        if (completesEditing) await _dismissSample();
      }
      return _maintain(await _scan());
    }).whenComplete(() => _pending.remove(snapshot));
  }

  Future<List<Map<String, dynamic>>> completeEditing() => _run(() async {
    await _loadLifecycle();
    await _dismissSample();
    return _maintain(await _scan());
  });

  Future<List<Map<String, dynamic>>> delete(Set<String> ids) => _run(() async {
    await _loadLifecycle();
    final records = await _scan();
    final selected = records.where((row) => ids.contains(row['id'])).toList();
    if (ids.isEmpty || selected.length != ids.length) {
      throw const FormatException('草稿不存在或未选择');
    }
    if (selected.any((row) => (row['photo'] as Map)['sample'] == true)) {
      // Persist the one-way decision before deleting its last visible record.
      await _dismissSample();
    }
    await _remove(selected);
    return _maintain(await _scan());
  });

  Future<void> _loadLifecycle() async {
    if (_canonicalDirectory == null) {
      // Android's trusted filesDir can be /data/user/0 while Dart canonicalizes
      // it to /data/data. Resolve that root once; use the resulting directory
      // for every filesystem operation and continue rejecting links inside it.
      await _requestedDirectory.create(recursive: true);
      _canonicalDirectory = Directory(
        await _requestedDirectory.resolveSymbolicLinks(),
      );
    }
    await _requireSafe(directory.path);
    await directory.create(recursive: true);
    await _requireSafe(_initialized.path);
    await _requireSafe(_dismissed.path);
    if (!await _initialized.exists()) {
      // Called before any first-launch settings/catalog writes. Existing data
      // without the new marker belongs to a returning installation.
      final legacy = await directory.list(followLinks: false).isEmpty == false;
      if (legacy) await _dismissSample();
      await _writeAtomic(_initialized, {'version': 1});
    }
    // Existence is deliberately enough: even an interrupted tombstone write
    // must not re-enable a deleted example on the next launch.
    sampleAvailable = !await _dismissed.exists();
  }

  Future<void> _dismissSample() async {
    await _requireSafe(_dismissed.path);
    if (!await _dismissed.exists()) {
      await _dismissed.writeAsString('1', flush: true);
    }
    sampleAvailable = false;
  }

  static void _validate(Map<String, dynamic> row) {
    final id = row['id'];
    if (id is! String ||
        !RegExp(r'^[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}$').hasMatch(id)) {
      throw const FormatException('无效草稿 ID');
    }
    final photo = PhotoAsset.fromJson(
      Map<String, dynamic>.from(row['photo'] as Map),
    );
    if (photo.id != id || DateTime.tryParse(row['updated'] as String) == null) {
      throw const FormatException('无效草稿记录');
    }
    EditHistory.fromJson(Map<String, dynamic>.from(row['history'] as Map));
  }

  Future<List<Map<String, dynamic>>> _scan() async {
    await _requireSafe(_folder.path);
    if (!await _folder.exists()) return [];
    final paths = <String>{};
    await for (final entry in _folder.list(followLinks: false)) {
      if (entry is! File) continue;
      if (entry.path.endsWith('.json')) paths.add(entry.path);
      if (entry.path.endsWith('.json.bak')) {
        paths.add(entry.path.substring(0, entry.path.length - 4));
      }
    }
    final found = <Map<String, dynamic>>[];
    for (final path in paths) {
      for (final suffix in ['', '.bak']) {
        try {
          final file = File('$path$suffix');
          await _requireSafe(file.path);
          if (!await file.exists()) continue;
          final row = Map<String, dynamic>.from(
            jsonDecode(await file.readAsString()) as Map,
          );
          _validate(row);
          if (_key(path) != _key('${_folder.path}/${row['id']}.json')) continue;
          found.add(row);
          break;
        } catch (_) {
          // Keep damaged and unfamiliar documents for recovery; never infer
          // their resource ownership from a partial record.
        }
      }
    }
    found.sort((a, b) {
      final order = DateTime.parse(
        b['updated'] as String,
      ).compareTo(DateTime.parse(a['updated'] as String));
      return order != 0
          ? order
          : (b['id'] as String).compareTo(a['id'] as String);
    });
    return found;
  }

  Future<List<Map<String, dynamic>>> _maintain(
    List<Map<String, dynamic>> records,
  ) async {
    final kept = <Map<String, dynamic>>[];
    final removed = <Map<String, dynamic>>[];
    for (final row in records) {
      if ((!sampleAvailable && (row['photo'] as Map)['sample'] == true) ||
          kept.length == limit) {
        removed.add(row);
      } else {
        kept.add(row);
      }
    }
    if (removed.isNotEmpty) await _remove(removed.reversed.toList());
    await _collectUnreferenced();
    return kept;
  }

  Future<Set<String>> _cleanupCandidates() async {
    for (final suffix in ['', '.bak']) {
      try {
        final file = File('${_cleanup.path}$suffix');
        await _requireSafe(file.path);
        if (!await file.exists()) continue;
        final value = jsonDecode(await file.readAsString()) as Map;
        return (value['paths'] as List).cast<String>().map(_ownedKey).toSet();
      } catch (_) {
        // Unknown ownership leaks a cache copy rather than deleting a photo.
      }
    }
    return {};
  }

  Future<void> _remove(List<Map<String, dynamic>> rows) async {
    if (sampleAvailable &&
        rows.any((row) => (row['photo'] as Map)['sample'] == true)) {
      await _dismissSample();
    }
    final candidates = await _cleanupCandidates();
    for (final row in rows) {
      final photo = PhotoAsset.fromJson(
        Map<String, dynamic>.from(row['photo'] as Map),
      );
      if (photo.sample) {
        candidates.add(
          '${directory.path}/sample-${photo.id}.${photo.path.split('.').last}',
        );
      } else if (_uuid.hasMatch(photo.id)) {
        final folder = '${directory.path}/assets/${photo.id}';
        if (_inside(photo.path, folder) && _inside(photo.preview, folder)) {
          candidates.add(folder);
        }
      }
    }
    // Retain cleanup candidates across a crash between record removal and GC.
    await _writeAtomic(_cleanup, {'version': 1, 'paths': candidates.toList()});
    for (final row in rows) {
      final path = '${_folder.path}/${row['id']}.json';
      for (final suffix in ['.bak', '.tmp', '']) {
        final file = File('$path$suffix');
        await _requireSafe(file.path);
        if (await file.exists()) await file.delete();
      }
      removedIds.add(row['id'] as String);
    }
  }

  static final _uuid = RegExp(
    r'^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$',
  );
  static const _photoFiles = {
    'source.img',
    'unmarked-original.img',
    'factory-restored.img',
    'preview.png',
  };

  Future<void> _collectUnreferenced() async {
    final candidates = await _cleanupCandidates();
    if (candidates.isEmpty) return;
    final references = <String>{};
    var uncertain = false;
    void collect(Object? value) {
      if (value is String) {
        references.add(value.toLowerCase());
        if (value.contains('%')) {
          try {
            references.add(Uri.decodeComponent(value).toLowerCase());
          } on FormatException {
            if (value.contains('/') ||
                value.contains('\\') ||
                value.startsWith('file:')) {
              uncertain = true;
            }
          }
        }
      } else if (value is Map) {
        for (final entry in value.entries) {
          collect(entry.key);
          collect(entry.value);
        }
      } else if (value is Iterable) {
        for (final child in value) {
          collect(child);
        }
      }
    }

    collect(retainedReferences());
    collect(_pending);
    // UUID directory names identify imported copies regardless of whether a
    // retained resource spells its location as an absolute/relative path,
    // file URI or percent-encoded URI. Ambiguous matches retain the copy.
    bool referenced(String path) {
      final name = _key(path).split('/').last.toLowerCase();
      return references.any((ref) => ref.contains(name));
    }

    if (uncertain || candidates.every(referenced)) return;
    try {
      // Include surviving histories, backups, template preferences, installed
      // and retired packages. An unreadable record makes GC defer, not guess.
      Future<void> scan(Directory folder) async {
        await for (final entry in folder.list(followLinks: false)) {
          if (_key(entry.path) == _key('${directory.path}/assets') ||
              _key(entry.path).startsWith(_key(_cleanup.path))) {
            continue;
          }
          if (entry is Link) throw const FormatException('资源引用包含链接');
          if (entry is Directory) {
            await _requireSafe(entry.path);
            await scan(entry);
          } else if (entry is File &&
              (entry.path.endsWith('.json') ||
                  entry.path.endsWith('.json.bak') ||
                  entry.path.endsWith('.json.tmp'))) {
            collect(jsonDecode(await entry.readAsString()));
          }
        }
      }

      await scan(directory);
    } catch (_) {
      return;
    }
    if (uncertain) return;
    bool heldNow(String path) {
      collect(retainedReferences());
      collect(_pending);
      return uncertain || referenced(path);
    }

    final retained = <String>{};
    for (final path in candidates) {
      try {
        await _requireSafe(path);
        if (referenced(path)) {
          retained.add(path);
          continue;
        }
        final key = _key(path);
        final name = key.split('/').last;
        if (key == _key('${directory.path}/assets/$name') &&
            _uuid.hasMatch(name)) {
          final folder = Directory(path);
          if (!await folder.exists()) continue;
          final contents = await folder.list(followLinks: false).toList();
          if (contents.any(
            (entry) =>
                entry is! File ||
                !_photoFiles.contains(_key(entry.path).split('/').last),
          )) {
            retained.add(path);
            continue;
          }
          // The directory scan yielded to other editor commands. Recheck live
          // leases after all async work, then validate and delete synchronously
          // so restore/export cannot acquire the photo between check and unlink.
          _requireSafeSync(folder.path);
          for (final entry in contents) {
            _requireSafeSync(entry.path);
          }
          if (heldNow(path)) {
            retained.add(path);
            continue;
          }
          // No recursive deletion: at most the four importer-owned files.
          for (final entry in contents) {
            entry.deleteSync();
          }
          folder.deleteSync();
        } else if (key == _key('${directory.path}/$name') &&
            RegExp(r'^sample-[a-zA-Z0-9_-]+\.(webp|jpg|png)$').hasMatch(name)) {
          final file = File(path);
          _requireSafeSync(file.path);
          if (heldNow(path)) {
            retained.add(path);
          } else if (file.existsSync()) {
            file.deleteSync();
          }
        }
      } catch (_) {
        retained.add(path);
      }
    }
    await _writeAtomic(_cleanup, {'version': 1, 'paths': retained.toList()});
  }

  static String _key(String path) {
    final value = File(
      path,
    ).absolute.uri.normalizePath().toFilePath().replaceAll('\\', '/');
    return Platform.isWindows ? value.toLowerCase() : value;
  }

  String _ownedKey(String path) {
    final key = _key(path);
    final requested = _key(_requestedDirectory.path);
    if (key == requested || key.startsWith('$requested/')) {
      return '$_root${key.substring(requested.length)}';
    }
    return key;
  }

  bool _inside(String path, String parent) {
    if (path.startsWith('assets/') || path.startsWith('content:')) return false;
    return _ownedKey(path).startsWith('${_ownedKey(parent)}/');
  }

  Future<void> _requireSafe(String path) async {
    final key = _ownedKey(path);
    if (key != _root && !key.startsWith('$_root/')) {
      throw const FileSystemException('草稿路径超出应用私有目录');
    }
    var current = key;
    while (true) {
      final kind = await FileSystemEntity.type(current, followLinks: false);
      if (kind == FileSystemEntityType.link) {
        throw const FileSystemException('草稿路径包含链接');
      }
      if (kind != FileSystemEntityType.notFound &&
          _key(await File(current).resolveSymbolicLinks()) != current) {
        throw const FileSystemException('草稿路径被重定向');
      }
      if (current == _root) break;
      current = current.substring(0, current.lastIndexOf('/'));
    }
  }

  void _requireSafeSync(String path) {
    final key = _ownedKey(path);
    if (key != _root && !key.startsWith('$_root/')) {
      throw const FileSystemException('草稿路径超出应用私有目录');
    }
    var current = key;
    while (true) {
      final kind = FileSystemEntity.typeSync(current, followLinks: false);
      if (kind == FileSystemEntityType.link ||
          (kind != FileSystemEntityType.notFound &&
              _key(File(current).resolveSymbolicLinksSync()) != current)) {
        throw const FileSystemException('草稿路径包含链接或重定向');
      }
      if (current == _root) break;
      current = current.substring(0, current.lastIndexOf('/'));
    }
  }

  Future<void> _writeAtomic(File file, Object value) async {
    await _requireSafe(file.path);
    await _requireSafe('${file.path}.tmp');
    await _requireSafe('${file.path}.bak');
    await file.parent.create(recursive: true);
    final temporary = File('${file.path}.tmp');
    await temporary.writeAsString(jsonEncode(value), flush: true);
    final backup = File('${file.path}.bak');
    if (await file.exists()) {
      if (await backup.exists()) await backup.delete();
      await file.rename(backup.path);
    }
    try {
      await temporary.rename(file.path);
    } catch (_) {
      if (await backup.exists()) await backup.rename(file.path);
      rethrow;
    }
  }
}
