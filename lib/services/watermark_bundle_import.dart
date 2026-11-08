import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';
import 'dart:typed_data';
import 'package:archive/archive.dart' show InputFileStream, getCrc32;
import 'package:crypto/crypto.dart';
import 'watermark_package.dart';
import 'watermark_repository.dart';
import 'native_bridge.dart';

const watermarkBundleZipLimit = 1024 * 1024 * 1024;
const watermarkBundleOuterLimit = 1024 * 1024 * 1024;
const watermarkBundleTotalLimit = 2 * 1024 * 1024 * 1024;

class WatermarkImportItem {
  final String path, status;
  final String? id, message;
  const WatermarkImportItem(this.path, this.status, {this.id, this.message});
  Map<String, dynamic> toJson() => {
    'path': path,
    'status': status,
    'id': id,
    'message': message,
  };
}

class WatermarkImportProgress {
  final int completed, total;
  final String path;
  const WatermarkImportProgress(this.completed, this.total, this.path);
  Map<String, dynamic> toJson() => {
    'completed': completed,
    'total': total,
    'path': path,
  };
}

class WatermarkImportResult {
  final List<WatermarkImportItem> items;
  final bool cancelled;
  final String? error;
  final List<String> ignored;
  const WatermarkImportResult(
    this.items, {
    this.cancelled = false,
    this.error,
    this.ignored = const [],
  });
  bool get aborted => error != null;
  int get total => items.length;
  List<String> get remaining => [
    for (final item in items)
      if (['cancelled', 'unprocessed'].contains(item.status)) item.path,
  ];
  int get completed => total - remaining.length;
  int _count(String status) => items.where((i) => i.status == status).length;
  int get installed => _count('installed');
  int get skipped => _count('skipped');
  int get failed => _count('failed');
  int get conflicts => _count('conflict');
  Map<String, dynamic> toJson() => {
    'installed': installed,
    'skipped': skipped,
    'failed': failed,
    'conflicts': conflicts,
    'cancelled': cancelled,
    'aborted': aborted,
    'error': error,
    'ignored': ignored,
    'total': total,
    'completed': completed,
    'remaining': remaining,
    'items': [for (final i in items) i.toJson()],
  };
}

/// Reads only ZIP headers and one bounded child at a time from the picker copy.
/// Cancellation checkpoints never undo siblings already committed by repository.
class WatermarkBundleImporter {
  final WatermarkRepository repository;
  final Future<int> Function()? availableBytes;
  WatermarkBundleImporter(this.repository, {this.availableBytes});
  Future<int> _space() async {
    try {
      final bytes = availableBytes != null
          ? await availableBytes!()
          : await NativeBridge.call<int>('storage.availableBytes', {
              'path': repository.base.path,
            });
      if (bytes == null) throw const WatermarkImportAbort('无法检查可用存储空间');
      return bytes;
    } catch (error) {
      throw WatermarkImportAbort('无法检查可用存储空间：$error');
    }
  }

  Future<WatermarkImportResult> importFile(
    File file, {
    bool Function()? isCancelled,
    void Function(WatermarkImportProgress)? onProgress,
  }) async {
    if (await file.length() > watermarkBundleZipLimit) {
      throw const FormatException('集合 ZIP 超过 1 GiB');
    }
    final input = InputFileStream(file.path);
    try {
      final headers = _scan(input);
      var files = headers.where((h) => !h.name.endsWith('/')).toList();
      // Strip exactly one common wrapper, never an arbitrary recursive tree.
      var prefix = '';
      if (files.isNotEmpty && files.every((h) => h.name.contains('/'))) {
        final first = files.first.name.split('/').first;
        if (files.every((h) => h.name.startsWith('$first/'))) {
          prefix = '$first/';
        }
      }
      String logical(_Header h) => h.name.substring(prefix.length);
      final children = files
          .where((h) => logical(h).toLowerCase().endsWith('.zip'))
          .toList();
      final manifestEntries = files
          .where((h) => logical(h) == 'bundle.json')
          .toList();
      final single = files.any(
        (h) => ['config.json', 'template.json'].contains(logical(h)),
      );
      if (single && (children.isNotEmpty || manifestEntries.isNotEmpty)) {
        throw const FormatException('单包与集合内容混杂');
      }
      if (children.isEmpty && manifestEntries.isEmpty) {
        if (!single || input.totalLength > watermarkZipLimit) {
          throw const FormatException('不是单款水印或水印集合');
        }
        input.position = 0;
        return await _process(
          [
            (
              file.path.split(Platform.pathSeparator).last,
              () => input.readBytes(input.totalLength).toUint8List(),
            ),
          ],
          isCancelled,
          onProgress,
        );
      }
      if (children.isEmpty || children.length > 100) {
        throw const FormatException('集合需包含 1–100 款水印 ZIP');
      }
      for (final h in files) {
        final name = logical(h);
        if (children.contains(h)) {
          if (name.split('/').length > 9 || h.size > watermarkZipLimit) {
            throw const FormatException('集合子包路径或大小无效');
          }
        } else if (name != 'bundle.json' &&
            !RegExp(
              r'^(readme|license)(\.(txt|md))?$',
              caseSensitive: false,
            ).hasMatch(name.split('/').last)) {
          throw const FormatException('集合含非水印文件或多层目录');
        }
      }
      Future<Uint8List> content(_Header h) async {
        try {
          final limit = h.name.endsWith('bundle.json')
              ? 1024 * 1024
              : (children.contains(h) ? watermarkZipLimit : watermarkFileLimit);
          if (h.size > limit) throw const FormatException('集合资源超过预算');
          Stream<List<int>> stream = file.openRead(
            h.contentStart,
            h.contentStart + h.compressed,
          );
          if (h.method == 8) stream = stream.transform(ZLibDecoder(raw: true));
          final out = BytesBuilder(copy: false);
          var size = 0, crc = 0;
          await for (final chunk in stream) {
            size += chunk.length;
            if (size > limit || size > h.size) {
              throw const FormatException('集合实际解压超过预算');
            }
            crc = getCrc32(chunk, crc);
            out.add(chunk);
          }
          if (size != h.size || crc != h.crc) {
            throw const FormatException('集合资源长度或 CRC 校验失败');
          }
          return out.takeBytes();
        } catch (error) {
          throw WatermarkOuterIntegrityException(
            '集合外层资源 ${logical(h)} 损坏：$error',
          );
        }
      }

      final declared = <String, Map<String, dynamic>>{};
      if (manifestEntries.isNotEmpty) {
        final manifest = WatermarkPackageCodec.parseJson(
          await content(manifestEntries.single),
          'bundle.json',
        );
        if (manifest['format'] != 'com.realize.starmark.watermark-bundle' ||
            manifest['bundleVersion'] != '1.0.0' ||
            manifest['packages'] is! List) {
          throw const FormatException('不支持此水印集合版本');
        }
        for (final item in manifest['packages'] as List) {
          if (item is! Map ||
              item['path'] is! String ||
              item['size'] is! int ||
              item['sha256'] is! String) {
            throw const FormatException('集合清单条目不完整');
          }
          final row = Map<String, dynamic>.from(item);
          final path = row['path'] as String;
          if (!safeWatermarkPath(path) ||
              declared.containsKey(path) ||
              !RegExp(r'^[a-f0-9]{64}$').hasMatch(row['sha256'])) {
            throw const FormatException('集合清单路径或摘要无效');
          }
          declared[path] = row;
        }
        if (declared.length != children.length ||
            children.any((h) => !declared.containsKey(logical(h)))) {
          throw const FormatException('集合清单必须精确覆盖全部子包');
        }
      }
      if (declared.isNotEmpty) {
        final order = declared.keys.toList();
        children.sort(
          (a, b) =>
              order.indexOf(logical(a)).compareTo(order.indexOf(logical(b))),
        );
      } else {
        children.sort((a, b) => logical(a).compareTo(logical(b)));
      }
      final ignored = <String>[];
      // Harmless ancillary files must still pass their CRC and actual budgets.
      for (final h in files.where(
        (h) => !children.contains(h) && !manifestEntries.contains(h),
      )) {
        await content(h);
        ignored.add(logical(h));
      }
      return await _process(
        [
          for (final h in children)
            (
              logical(h),
              () async {
                final bytes = await content(h);
                final row = declared[logical(h)];
                if (row != null &&
                    (row['size'] != bytes.length ||
                        (row['sha256'] as String) !=
                            sha256.convert(bytes).toString())) {
                  throw const WatermarkOuterIntegrityException(
                    '集合子包大小或 SHA-256 校验失败',
                  );
                }
                return bytes;
              },
            ),
        ],
        isCancelled,
        onProgress,
        ignored: ignored,
      );
    } on FormatException {
      rethrow;
    } catch (e) {
      throw FormatException('水印集合无法读取：$e');
    } finally {
      await input.close();
    }
  }

  Future<WatermarkImportResult> _process(
    List<(String, FutureOr<Uint8List> Function())> children,
    bool Function()? cancelled,
    void Function(WatermarkImportProgress)? progress, {
    List<String> ignored = const [],
  }) async {
    final items = <WatermarkImportItem>[];
    final records = await repository.importRecords();
    var expanded = 0;
    WatermarkImportResult finish({bool stopped = false, String? error}) {
      final result = [...items];
      for (final (path, _) in children.skip(items.length)) {
        result.add(
          WatermarkImportItem(
            path,
            stopped ? 'cancelled' : 'unprocessed',
            message: stopped ? '取消，未导入' : error,
          ),
        );
      }
      return WatermarkImportResult(
        List.unmodifiable(result),
        cancelled: stopped,
        error: error,
        ignored: List.unmodifiable(ignored),
      );
    }

    for (final (path, read) in children) {
      await Future<void>.delayed(Duration.zero);
      if (cancelled?.call() == true) return finish(stopped: true);
      progress?.call(
        WatermarkImportProgress(items.length, children.length, path),
      );
      if (cancelled?.call() == true) return finish(stopped: true);
      try {
        if (expanded > watermarkBundleTotalLimit) {
          throw const WatermarkImportAbort('集合累计解压超过 2 GiB');
        }
        final free = await _space();
        if (free < watermarkJsonLimit) {
          throw const WatermarkImportAbort('存储空间不足，未开始安装');
        }
        final bytes = await read();
        final remaining = watermarkBundleTotalLimit - expanded;
        final decoded = await _decodeChildAsync(bytes, remaining, free);
        expanded += decoded.$2;
        if (decoded.$5) throw WatermarkImportAbort(decoded.$4 ?? '集合预算超限');
        final package = decoded.$1;
        if (package == null) throw FormatException(decoded.$4 ?? '水印包无效');
        final digest = decoded.$3;
        final staruwIdentity =
            package.document.staruwReport['identity'] as Map?;
        final identity = staruwIdentity != null
            ? '${staruwIdentity['id']}@${staruwIdentity['revision']}'
            : (package.config['metadata'] as Map?)?['id']?.toString() ?? '';
        if (records.any((r) => r.digest == digest)) {
          items.add(WatermarkImportItem(path, 'skipped', message: '相同内容已存在'));
        } else if (identity.startsWith('factory-') ||
            (identity.isNotEmpty &&
                records.any((r) => r.identity == identity))) {
          items.add(
            WatermarkImportItem(path, 'conflict', message: '不可信的水印身份冲突'),
          );
        } else {
          final required =
              package.files.values.fold(0, (n, b) => n + b.length) +
              (package.originalArchive?.length ?? 0) +
              watermarkJsonLimit;
          if (await _space() < required) {
            throw const WatermarkImportAbort('存储空间不足，未写入暂存目录');
          }
          if (cancelled?.call() == true) return finish(stopped: true);
          final style = await repository.install(
            package,
            sourceDigest: digest,
            sourceIdentity: identity,
            isCancelled: cancelled,
          );
          records.add((digest: digest, identity: identity));
          items.add(WatermarkImportItem(path, 'installed', id: style.id));
        }
      } on WatermarkInstallCancelled {
        return finish(stopped: true);
      } on WatermarkOuterIntegrityException catch (error) {
        items.add(WatermarkImportItem(path, 'failed', message: error.message));
        return finish(error: error.message);
      } on WatermarkImportAbort catch (error) {
        items.add(WatermarkImportItem(path, 'failed', message: error.message));
        return finish(error: error.message);
      } catch (error) {
        items.add(
          WatermarkImportItem(
            path,
            'failed',
            message: error is FormatException
                ? error.message
                : error.toString(),
          ),
        );
      }
      progress?.call(
        WatermarkImportProgress(items.length, children.length, path),
      );
    }
    return finish();
  }
}

class WatermarkOuterIntegrityException extends FormatException {
  const WatermarkOuterIntegrityException(super.message);
}

class WatermarkImportAbort extends FormatException {
  const WatermarkImportAbort(super.message);
}

class _Header {
  final String name;
  final int size, crc, start, end, contentStart, compressed, method;
  const _Header(
    this.name,
    this.size,
    this.crc,
    this.start,
    this.end,
    this.contentStart,
    this.compressed,
    this.method,
  );
}

List<_Header> _scan(InputFileStream input) {
  final length = input.totalLength;
  Uint8List at(int offset, int size) {
    if (offset < 0 || size < 0 || offset + size > length) {
      throw const FormatException('ZIP 头越界');
    }
    input.position = offset;
    return input.readBytes(size).toUint8List();
  }

  if (length < 22) throw const FormatException('ZIP 过短');
  final tailStart = length > 65557 ? length - 65557 : 0;
  final tail = at(tailStart, length - tailStart);
  final td = ByteData.sublistView(tail);
  var eocd = -1;
  for (var i = tail.length - 22; i >= 0; i--) {
    if (td.getUint32(i, Endian.little) == 0x06054b50 &&
        i + 22 + td.getUint16(i + 20, Endian.little) == tail.length) {
      eocd = tailStart + i;
      break;
    }
  }
  if (eocd < 0) throw const FormatException('ZIP 目录缺失');
  final end = ByteData.sublistView(at(eocd, 22));
  final count = end.getUint16(10, Endian.little),
      directory = end.getUint32(16, Endian.little);
  if (count == 0 ||
      count > 512 ||
      end.getUint16(8, Endian.little) != count ||
      end.getUint16(4, Endian.little) != 0 ||
      end.getUint16(6, Endian.little) != 0 ||
      directory + end.getUint32(12, Endian.little) != eocd) {
    throw const FormatException('ZIP64、多卷或条目预算不支持');
  }
  final result = <_Header>[];
  final names = <String>{};
  var offset = directory, total = 0;
  void extraGuard(Uint8List extra) {
    final d = ByteData.sublistView(extra);
    var o = 0;
    while (o < extra.length) {
      if (o + 4 > extra.length) throw const FormatException('ZIP 扩展字段损坏');
      final id = d.getUint16(o, Endian.little),
          size = d.getUint16(o + 2, Endian.little);
      if (id == 1 || o + 4 + size > extra.length) {
        throw const FormatException('ZIP64 或损坏的扩展字段');
      }
      o += 4 + size;
    }
  }

  for (var i = 0; i < count; i++) {
    if (offset + 46 > eocd) throw const FormatException('ZIP 目录损坏');
    final d = ByteData.sublistView(at(offset, 46));
    if (d.getUint32(0, Endian.little) != 0x02014b50) {
      throw const FormatException('ZIP 目录签名损坏');
    }
    final flags = d.getUint16(8, Endian.little),
        method = d.getUint16(10, Endian.little),
        crc = d.getUint32(16, Endian.little),
        compressed = d.getUint32(20, Endian.little),
        size = d.getUint32(24, Endian.little),
        n = d.getUint16(28, Endian.little),
        extra = d.getUint16(30, Endian.little),
        comment = d.getUint16(32, Endian.little),
        local = d.getUint32(42, Endian.little),
        mode = d.getUint32(38, Endian.little) >> 16;
    if (n > 512 ||
        offset + 46 + n + extra + comment > eocd ||
        (flags & 0x41) != 0 ||
        ![0, 8].contains(method) ||
        size == 0xffffffff ||
        compressed == 0xffffffff ||
        local == 0xffffffff ||
        d.getUint16(34, Endian.little) != 0 ||
        (mode & 0xf000) == 0xa000) {
      throw const FormatException('不支持的 ZIP 条目');
    }
    final raw = utf8.decode(at(offset + 46, n));
    final name = raw.endsWith('/') ? raw.substring(0, raw.length - 1) : raw;
    if (!safeWatermarkPath(name) || !names.add(name.toLowerCase())) {
      throw const FormatException('非法或重复 ZIP 路径');
    }
    if (raw.endsWith('/') && (size != 0 || compressed != 0)) {
      throw const FormatException('ZIP 目录包含数据');
    }
    extraGuard(at(offset + 46 + n, extra));
    if (local + 30 > directory) throw const FormatException('ZIP 本地头越界');
    final ld = ByteData.sublistView(at(local, 30));
    final ln = ld.getUint16(26, Endian.little),
        le = ld.getUint16(28, Endian.little);
    final start = local + 30 + ln + le;
    if (ld.getUint32(0, Endian.little) != 0x04034b50 ||
        ld.getUint16(6, Endian.little) != flags ||
        ld.getUint16(8, Endian.little) != method ||
        ln != n ||
        start + compressed > directory ||
        utf8.decode(at(local + 30, ln)) != raw) {
      throw const FormatException('ZIP 本地头与目录不一致');
    }
    extraGuard(at(local + 30 + ln, le));
    var dataEnd = start + compressed;
    if ((flags & 8) == 0) {
      if (ld.getUint32(14, Endian.little) != crc ||
          ld.getUint32(18, Endian.little) != compressed ||
          ld.getUint32(22, Endian.little) != size) {
        throw const FormatException('ZIP 声明长度不一致');
      }
    } else {
      final descriptor = ByteData.sublistView(at(dataEnd, 16));
      final skip = descriptor.getUint32(0, Endian.little) == 0x08074b50 ? 4 : 0;
      if (descriptor.getUint32(skip, Endian.little) != crc ||
          descriptor.getUint32(skip + 4, Endian.little) != compressed ||
          descriptor.getUint32(skip + 8, Endian.little) != size) {
        throw const FormatException('ZIP 数据描述符不一致');
      }
      dataEnd += skip + 12;
    }
    if (dataEnd > directory) throw const FormatException('ZIP 数据越过目录');
    total += size;
    if (total > watermarkBundleOuterLimit) {
      throw const FormatException('集合外层解压超过 1 GiB');
    }
    result.add(
      _Header(raw, size, crc, local, dataEnd, start, compressed, method),
    );
    offset += 46 + n + extra + comment;
  }
  if (offset != eocd) throw const FormatException('ZIP 目录数量不一致');
  final ranges = [...result]..sort((a, b) => a.start.compareTo(b.start));
  for (var i = 1; i < ranges.length; i++) {
    if (ranges[i].start < ranges[i - 1].end) {
      throw const FormatException('ZIP 数据重叠');
    }
  }
  return result;
}

(WatermarkPackage?, int, String, String?, bool) _decodeChild(
  Uint8List bytes,
  int remaining,
  int available,
) {
  var reserved = 0;
  try {
    final package = WatermarkPackageCodec.decode(
      bytes,
      onExpanded: (n) {
        reserved = n;
        if (n > remaining) throw const WatermarkImportAbort('集合累计解压超过 2 GiB');
        if (n + watermarkJsonLimit > available) {
          throw const WatermarkImportAbort('存储空间不足，未开始解压安装');
        }
      },
    );
    return (
      package,
      reserved,
      package.sourceDigest ?? canonicalWatermarkDigest(package.files),
      null,
      false,
    );
  } catch (error) {
    return (
      null,
      reserved,
      '',
      error is FormatException ? error.message : error.toString(),
      error is WatermarkImportAbort,
    );
  }
}

Future<(WatermarkPackage?, int, String, String?, bool)> _decodeChildAsync(
  Uint8List bytes,
  int remaining,
  int available,
) => Isolate.run(() => _decodeChild(bytes, remaining, available));
