import 'dart:convert';
import 'dart:typed_data';
import 'package:xml/xml.dart';
import 'package:archive/archive.dart';
import 'package:crypto/crypto.dart';
import 'package:watermark_engine/watermark_engine.dart';
import 'staruw/contract.dart';
import 'staruw/package_validation.dart';

const watermarkJsonLimit = 4 * 1024 * 1024;
const watermarkZipLimit = 64 * 1024 * 1024;
const watermarkFileLimit = 32 * 1024 * 1024;
const watermarkTotalLimit = 128 * 1024 * 1024;

bool safeWatermarkPath(String path) => staruwSafePath(path);

class WatermarkPackage {
  final Map<String, dynamic> config;
  final Map<String, Uint8List> files;
  final EditDocument document;
  final String title, preview;
  final bool xiaomi;
  final String? sourceDigest;
  final Uint8List? originalArchive;
  const WatermarkPackage(
    this.config,
    this.files,
    this.document,
    this.title,
    this.preview,
    this.xiaomi, {
    this.sourceDigest,
    this.originalArchive,
  });
}

class WatermarkPackageCodec {
  static Map<String, dynamic> parseJson(List<int> bytes, String file) {
    if (bytes.length > watermarkJsonLimit) throw FormatException('$file 配置过大');
    final parsed = staruwJson(bytes, file);
    if (parsed is! Map) throw FormatException('$file 不是 JSON 对象');
    return Map<String, dynamic>.from(parsed);
  }

  static Map<String, dynamic> customConfig(
    String title,
    EditDocument document, {
    String id = '',
  }) => {
    'format': 'dev.starmark.watermark',
    'version': 1,
    'metadata': {
      'name': title,
      'id': id,
      'position': document.mode == WatermarkMode.frame ? 'out' : 'in',
    },
    'document': document.copyWith(packageId: '').toJson(),
  };

  static Uint8List encode(
    Map<String, dynamic> config,
    Map<String, List<int>> resources,
  ) {
    if (config['format'] == staruwFormat) {
      final existing = resources['config.json'];
      final unchanged =
          existing != null && staruwEqual(staruwJson(existing), config);
      final files = unchanged
          ? {
              for (final e in resources.entries)
                e.key: Uint8List.fromList(e.value),
            }
          : staruwSeal({
              ...resources,
              'config.json': utf8.encode(jsonEncode(config)),
            });
      if (!unchanged && config['staruw']?['protocolVersion'] != '1.0.0') {
        throw const StaruwException('E_VERSION', '未支持的协议只能导出原包');
      }
      StaruwPackageValidator(files, validateSvg: validateSvg).validate();
      final archive = Archive();
      for (final e in files.entries) {
        archive.addFile(ArchiveFile.bytes(e.key, e.value));
      }
      final bytes = ZipEncoder().encode(archive);
      staruwNeed(
        bytes.length <= watermarkZipLimit,
        'E_LIMIT',
        '水印 ZIP 超过 64 MiB',
      );
      return Uint8List.fromList(bytes);
    }
    final files = <String, List<int>>{};
    for (final entry in resources.entries) {
      if (!['config.json', 'meta.json', 'manifest.json'].contains(entry.key)) {
        files[entry.key] = entry.value;
      }
    }
    files['config.json'] = utf8.encode(jsonEncode(config));
    files['meta.json'] = utf8.encode(
      jsonEncode({
        'version': config['version'] ?? 1,
        'metadata': config['metadata'] ?? {},
      }),
    );
    files.putIfAbsent('i18n.json', () => utf8.encode('{}'));
    var total = 0;
    for (final entry in files.entries) {
      if (!safeWatermarkPath(entry.key) ||
          entry.value.length > watermarkFileLimit) {
        throw const FormatException('无效或过大的水印资源');
      }
      total += entry.value.length;
    }
    if (total > watermarkTotalLimit || files.length > 511) {
      throw const FormatException('水印包超过资源预算');
    }
    files['manifest.json'] = utf8.encode(
      jsonEncode({
        'schemaVersion': 2,
        'format': 'dev.starmark.watermark-package',
        'resources': {
          for (final e in files.entries)
            e.key: sha256.convert(e.value).toString(),
        },
      }),
    );
    final archive = Archive();
    for (final e in files.entries) {
      archive.addFile(ArchiveFile.bytes(e.key, e.value));
    }
    final bytes = ZipEncoder().encode(archive);
    if (bytes.length > watermarkZipLimit) {
      throw const FormatException('水印 ZIP 超过 64 MiB');
    }
    return Uint8List.fromList(bytes);
  }

  static Map<String, ({int crc, int size})> _preflight(Uint8List bytes) {
    if (bytes.length < 22 || bytes.length > watermarkZipLimit) {
      throw const FormatException('无效或过大的水印 ZIP');
    }
    final data = ByteData.sublistView(bytes);
    var eocd = -1;
    for (var i = bytes.length - 22; i >= 0 && i >= bytes.length - 65557; i--) {
      if (data.getUint32(i, Endian.little) == 0x06054b50 &&
          i + 22 + data.getUint16(i + 20, Endian.little) == bytes.length) {
        eocd = i;
        break;
      }
    }
    if (eocd < 0) throw const FormatException('ZIP 目录缺失');
    final count = data.getUint16(eocd + 10, Endian.little);
    var offset = data.getUint32(eocd + 16, Endian.little), total = 0;
    final directoryStart = offset;
    if (count < 1 ||
        count > 512 ||
        data.getUint16(eocd + 8, Endian.little) != count ||
        offset + data.getUint32(eocd + 12, Endian.little) != eocd ||
        data.getUint16(eocd + 4, Endian.little) != 0 ||
        data.getUint16(eocd + 6, Endian.little) != 0) {
      throw const FormatException('不支持多卷或过多文件的 ZIP');
    }
    final names = <String>{};
    final checksums = <String, ({int crc, int size})>{};
    final ranges = <({int start, int end})>[];
    void checkExtra(int start, int length) {
      final end = start + length;
      for (var offset = start; offset < end;) {
        if (offset + 4 > end) throw const FormatException('ZIP 扩展字段损坏');
        final kind = data.getUint16(offset, Endian.little),
            size = data.getUint16(offset + 2, Endian.little);
        if (kind == 1 || offset + 4 + size > end) {
          throw const FormatException('不支持 ZIP64 或损坏的扩展字段');
        }
        offset += 4 + size;
      }
    }

    for (var i = 0; i < count; i++) {
      if (offset + 46 > eocd ||
          data.getUint32(offset, Endian.little) != 0x02014b50) {
        throw const FormatException('ZIP 目录损坏');
      }
      final flags = data.getUint16(offset + 8, Endian.little),
          method = data.getUint16(offset + 10, Endian.little),
          crc = data.getUint32(offset + 16, Endian.little),
          compressed = data.getUint32(offset + 20, Endian.little),
          size = data.getUint32(offset + 24, Endian.little),
          n = data.getUint16(offset + 28, Endian.little),
          extra = data.getUint16(offset + 30, Endian.little),
          comment = data.getUint16(offset + 32, Endian.little),
          mode = data.getUint32(offset + 38, Endian.little) >> 16,
          local = data.getUint32(offset + 42, Endian.little);
      if (offset + 46 + n + extra + comment > eocd ||
          size > watermarkFileLimit ||
          (flags & 0x41) != 0 ||
          data.getUint16(offset + 34, Endian.little) != 0 ||
          ![0, 8].contains(method) ||
          (mode & 0xf000) == 0xa000 ||
          (data.getUint32(offset + 38, Endian.little) & 0x400) != 0) {
        throw const FormatException('不支持的 ZIP 资源');
      }
      checkExtra(offset + 46 + n, extra);
      final raw = utf8.decode(bytes.sublist(offset + 46, offset + 46 + n));
      final name = raw.endsWith('/') ? raw.substring(0, raw.length - 1) : raw;
      if (!safeWatermarkPath(name) || !names.add(staruwCaseFold(name))) {
        throw const FormatException('非法或重复 ZIP 路径');
      }
      if (local + 30 > directoryStart ||
          data.getUint32(local, Endian.little) != 0x04034b50 ||
          data.getUint16(local + 6, Endian.little) != flags ||
          data.getUint16(local + 8, Endian.little) != method) {
        throw const FormatException('ZIP 本地文件头与目录不一致');
      }
      final localNameLength = data.getUint16(local + 26, Endian.little);
      final contentStart =
          local +
          30 +
          localNameLength +
          data.getUint16(local + 28, Endian.little);
      if (contentStart + compressed > directoryStart ||
          localNameLength != n ||
          utf8.decode(
                bytes.sublist(local + 30, local + 30 + localNameLength),
              ) !=
              raw ||
          ((flags & 8) == 0 &&
              (data.getUint32(local + 14, Endian.little) != crc ||
                  data.getUint32(local + 18, Endian.little) != compressed ||
                  data.getUint32(local + 22, Endian.little) != size))) {
        throw const FormatException('ZIP 文件名、长度或校验信息不一致');
      }
      checkExtra(
        local + 30 + localNameLength,
        data.getUint16(local + 28, Endian.little),
      );
      var contentEnd = contentStart + compressed;
      if ((flags & 8) != 0) {
        // Data descriptors are authoritative too, not unchecked padding.
        if (contentEnd + 12 > directoryStart) {
          throw const StaruwException('E_ZIP', 'ZIP 数据描述符缺失');
        }
        final signed = data.getUint32(contentEnd, Endian.little) == 0x08074b50;
        final descriptor = contentEnd + (signed ? 4 : 0);
        if (descriptor + 12 > directoryStart ||
            data.getUint32(descriptor, Endian.little) != crc ||
            data.getUint32(descriptor + 4, Endian.little) != compressed ||
            data.getUint32(descriptor + 8, Endian.little) != size) {
          throw const StaruwException('E_ZIP', 'ZIP 数据描述符不一致');
        }
        contentEnd = descriptor + 12;
      }
      ranges.add((start: local, end: contentEnd));
      checksums[raw] = (crc: crc, size: size);
      total += size;
      if (total > watermarkTotalLimit) {
        throw const FormatException('ZIP 解压预算超限');
      }
      offset += 46 + n + extra + comment;
    }
    if (offset != eocd) throw const FormatException('ZIP 目录条目数量不一致');
    ranges.sort((a, b) => a.start.compareTo(b.start));
    if (ranges.isEmpty ||
        ranges.first.start != 0 ||
        ranges.last.end != directoryStart) {
      throw const StaruwException('E_ZIP', '不接受自解压前缀或额外 ZIP 数据');
    }
    for (var i = 1; i < ranges.length; i++) {
      if (ranges[i].start != ranges[i - 1].end) {
        throw const FormatException('ZIP 文件数据重叠');
      }
    }
    return checksums;
  }

  static WatermarkPackage decode(
    List<int> input, {
    void Function(int)? onExpanded,
  }) {
    try {
      final bytes = Uint8List.fromList(input);
      final checksums = _preflight(bytes);
      // Reserve the bounded declared expansion before any inflation. Successful
      // entries must match it exactly; failed children still consume the
      // collection budget instead of allowing repeated expensive corrupt input.
      onExpanded?.call(
        checksums.values.fold(0, (sum, entry) => sum + entry.size),
      );
      final archive = ZipDecoder().decodeBytes(bytes, verify: true);
      final all = <String, Uint8List>{};
      final names = <String>{};
      var total = 0;
      for (final entry in archive) {
        final name = entry.name.endsWith('/')
            ? entry.name.substring(0, entry.name.length - 1)
            : entry.name;
        if (!safeWatermarkPath(name) ||
            !names.add(staruwCaseFold(name)) ||
            !checksums.containsKey(entry.name) ||
            entry.isSymbolicLink) {
          throw const FormatException('非法或重复 ZIP 资源路径');
        }
        if (!entry.isFile) continue;
        final out = BoundedWatermarkOutput();
        entry.writeContent(out);
        final value = out.getBytes();
        total += value.length;
        if (value.length != checksums[entry.name]!.size ||
            value.length != entry.size ||
            total > watermarkTotalLimit) {
          throw const FormatException('ZIP 实际解压超限');
        }
        if (getCrc32(value) != checksums[entry.name]!.crc) {
          throw const FormatException('ZIP 资源 CRC 校验失败');
        }
        if (entry.name.startsWith('__MACOSX/') ||
            entry.name.split('/').last == '.DS_Store') {
          continue;
        }
        all[entry.name] = value;
      }
      var configs = all.keys
          .where((p) => p == 'config.json' || p.endsWith('/config.json'))
          .toList();
      // Resolve the root before interpreting retained source/config.json files.
      // A writer emits no wrapper; a reader accepts exactly one common wrapper.
      String? staruwRoot;
      for (final candidate in configs) {
        if (candidate.split('/').length > 2) continue;
        final prefix = candidate.substring(
          0,
          candidate.length - 'config.json'.length,
        );
        if (all.keys.every((p) => p.startsWith(prefix)) &&
            parseJson(all[candidate]!, candidate)['format'] == staruwFormat) {
          staruwNeed(staruwRoot == null, 'E_FORMAT', '存在多个逻辑水印根');
          staruwRoot = prefix;
        }
      }
      if (staruwRoot != null) {
        final files = {
          for (final e in all.entries)
            e.key.substring(staruwRoot.length): e.value,
        };
        final package = fromFiles(files);
        return WatermarkPackage(
          package.config,
          package.files,
          package.document,
          package.title,
          package.preview,
          package.xiaomi,
          sourceDigest: package.sourceDigest,
          originalArchive: bytes,
        );
      }
      if (configs.isEmpty && all.containsKey('template.json')) {
        return _legacy(all);
      }
      if (configs.length != 1) {
        throw const FormatException('请导入只含一款水印的 ZIP，每款需有一个 config.json');
      }
      final root = configs.single.substring(
        0,
        configs.single.length - 'config.json'.length,
      );
      if (all.keys.any((k) => !k.startsWith(root))) {
        throw const FormatException('ZIP 包含水印目录之外的资源');
      }
      final files = {
        for (final e in all.entries) e.key.substring(root.length): e.value,
      };
      _verifyManifest(files);
      for (final e in files.entries) {
        _validateResource(e.key, e.value);
      }
      return _original(fromFiles(files), files, archive: bytes);
    } on FormatException {
      rethrow;
    } catch (e) {
      throw FormatException('水印包无法读取：$e');
    }
  }

  static void _verifyManifest(Map<String, Uint8List> files) {
    if (!files.containsKey('manifest.json')) return;
    final manifest = parseJson(files['manifest.json']!, 'manifest.json');
    if (![1, 2].contains(manifest['schemaVersion'])) {
      throw const FormatException('不支持此水印包版本');
    }
    final hashes = Map<String, dynamic>.from(
      manifest['resources'] as Map? ?? {},
    );
    for (final e in hashes.entries) {
      if (!safeWatermarkPath(e.key) ||
          files[e.key] == null ||
          sha256.convert(files[e.key]!).toString() != e.value) {
        throw const FormatException('水印资源校验失败');
      }
    }
    if (manifest['schemaVersion'] == 2 &&
        files.keys.any((k) => k != 'manifest.json' && !hashes.containsKey(k))) {
      throw const FormatException('水印含未声明资源');
    }
  }

  static void _validateResource(String name, Uint8List data) {
    if (!safeWatermarkPath(name)) throw const FormatException('无效水印路径');
    final ext = name.split('.').last.toLowerCase();
    if (![
      'json',
      'png',
      'webp',
      'jpg',
      'jpeg',
      'ttf',
      'otf',
      'svg',
      'txt',
      'md',
    ].contains(ext)) {
      throw FormatException('不支持的水印资源：$name');
    }
    if (['ttf', 'otf'].contains(ext)) {
      if (data.length < 12 ||
          ![
            'OTTO',
            'true',
            'ttcf',
            '\u0000\u0001\u0000\u0000',
          ].contains(String.fromCharCodes(data.take(4)))) {
        throw FormatException('字库损坏：$name');
      }
    }
    if (ext == 'svg') {
      validateSvg(utf8.decode(data));
    }
  }

  /// Shared, bounded SVG subset: paths remain vector; references cannot execute,
  /// load files/URLs, expand entities, or recurse through use elements.
  static void validateSvg(String text) {
    if (text.length > watermarkFileLimit ||
        RegExp(r'<!DOCTYPE|<!ENTITY', caseSensitive: false).hasMatch(text)) {
      throw const FormatException('Unsafe SVG declaration');
    }
    final XmlDocument xml;
    try {
      xml = XmlDocument.parse(text);
    } catch (_) {
      throw const FormatException('Malformed SVG');
    }
    final root = xml.rootElement;
    if (root.name.local != 'svg') throw const FormatException('Not SVG');
    var count = 0;
    final ids = <String, XmlElement>{};
    final graph = <XmlElement, List<XmlElement>>{};
    final references = <XmlElement, List<(String, String)>>{};
    final fragment = RegExp(r'''^#[^\s#()'"\\]+$''');
    final url = RegExp(
      r'''^url\(\s*(?:"(#[^"\\]+)"|'(#[^'\\]+)'|(#[^\s'"()\\]+))\s*\)$''',
      caseSensitive: false,
    );
    final hasUrl = RegExp(r'url\s*\(', caseSensitive: false);
    void reference(XmlElement node, String property, String value) {
      String? target;
      if (property == 'href') {
        if (!['linearGradient', 'radialGradient'].contains(node.name.local) ||
            !fragment.hasMatch(value)) {
          throw const FormatException('Unsafe SVG reference');
        }
        target = value;
      } else if (hasUrl.hasMatch(value)) {
        if (!['fill', 'stroke', 'clip-path'].contains(property)) {
          throw const FormatException('Unsupported SVG reference property');
        }
        final match = url.firstMatch(value);
        if (match == null) throw const FormatException('Unsafe SVG URL');
        target = match.group(1) ?? match.group(2) ?? match.group(3);
        if (target == null || !fragment.hasMatch(target)) {
          throw const FormatException('Unsafe SVG fragment');
        }
      }
      if (target != null) {
        (references[node] ??= []).add((
          target.substring(1),
          property == 'clip-path' ? 'clip' : 'gradient',
        ));
      }
    }

    void visit(XmlElement node, int depth) {
      if (++count > 20000 ||
          depth > 64 ||
          [
            'script',
            'foreignobject',
            'use',
            'image',
            'animate',
            'animatetransform',
            'set',
          ].contains(node.name.local.toLowerCase())) {
        throw const FormatException('Unsafe SVG element');
      }
      graph[node] = node.childElements.toList();
      final id = node.getAttribute('id');
      if (id != null && (id.isEmpty || ids.containsKey(id))) {
        throw const FormatException('Ambiguous SVG ID');
      }
      if (id != null) ids[id] = node;
      for (final attr in node.attributes) {
        final name = attr.name.local.toLowerCase(), value = attr.value.trim();
        if (name.startsWith('on') ||
            name == 'src' ||
            name == 'base' ||
            value.contains('\\') ||
            RegExp(
              r'@import|javascript:|file:',
              caseSensitive: false,
            ).hasMatch(value)) {
          throw const FormatException('Unsafe SVG reference');
        }
        if (name == 'style') {
          // A small presentation-only CSS subset, without selectors, escaped
          // identifiers or indirect/custom-property reference resolution.
          for (final declaration in value.split(';')) {
            if (declaration.trim().isEmpty) continue;
            final colon = declaration.indexOf(':');
            if (colon <= 0) throw const FormatException('Invalid SVG style');
            reference(
              node,
              declaration.substring(0, colon).trim().toLowerCase(),
              declaration.substring(colon + 1).trim(),
            );
          }
        } else {
          reference(node, name, value);
        }
      }
      for (final child in node.childElements) {
        visit(child, depth + 1);
      }
    }

    if (RegExp(
      r'@import|javascript:|file:|url\s*\(|\\',
      caseSensitive: false,
    ).hasMatch(
      // XmlCDATA and XmlText are separate classes. Both can carry stylesheet
      // content that must stay inside the same sanitization boundary.
      root.descendants
          .map(
            (node) => switch (node) {
              XmlText() => node.value,
              XmlCDATA() => node.value,
              _ => '',
            },
          )
          .join(),
    )) {
      throw const FormatException('Unsafe SVG style');
    }
    visit(root, 0);
    // Follow only resolved, typed local references. Containment edges matter:
    // a clip's child referencing that clip is recursive even without href.
    var edges = graph.values.fold<int>(0, (n, values) => n + values.length);
    for (final entry in references.entries) {
      for (final (id, kind) in entry.value) {
        final target = ids[id];
        if (target == null ||
            !(kind == 'clip'
                ? target.name.local == 'clipPath'
                : [
                    'linearGradient',
                    'radialGradient',
                  ].contains(target.name.local))) {
          throw const FormatException(
            'Missing or unsupported SVG reference target',
          );
        }
        graph[entry.key]!.add(target);
        if (++edges > 40000) {
          throw const FormatException('SVG reference budget exceeded');
        }
      }
    }
    final active = <XmlElement>{};
    final measured = <XmlElement, ({int depth, int work})>{};
    ({int depth, int work}) measure(XmlElement node, int traversalDepth) {
      if (traversalDepth > 64 || !active.add(node)) {
        throw const FormatException('Cyclic or deep SVG references');
      }
      final cached = measured[node];
      if (cached != null) {
        active.remove(node);
        return cached;
      }
      var depth = 1, work = 1;
      for (final target in graph[node]!) {
        final child = measure(target, traversalDepth + 1);
        if (child.depth + 1 > depth) depth = child.depth + 1;
        work += child.work;
        if (depth > 64 || work > 200000) {
          throw const FormatException(
            'SVG reference expansion budget exceeded',
          );
        }
      }
      active.remove(node);
      return measured[node] = (depth: depth, work: work);
    }

    measure(root, 0);
    final box = root
        .getAttribute('viewBox')
        ?.trim()
        .split(RegExp(r'[ ,]+'))
        .map(double.tryParse)
        .toList();
    final width = box != null && box.length == 4
        ? box[2]
        : double.tryParse(
            (root.getAttribute('width') ?? '').replaceFirst(RegExp(r'px$'), ''),
          );
    final height = box != null && box.length == 4
        ? box[3]
        : double.tryParse(
            (root.getAttribute('height') ?? '').replaceFirst(
              RegExp(r'px$'),
              '',
            ),
          );
    if (width == null ||
        height == null ||
        !width.isFinite ||
        !height.isFinite ||
        width <= 0 ||
        height <= 0 ||
        width > 1000000 ||
        height > 1000000) {
      throw const FormatException('Invalid SVG dimensions');
    }
  }

  static WatermarkPackage _legacy(Map<String, Uint8List> files) {
    _verifyManifest(files);
    final doc = EditDocument.fromJson(
      parseJson(files['template.json']!, 'template.json'),
    );
    final manifest = parseJson(files['manifest.json']!, 'manifest.json');
    final title = manifest['name']?.toString() ?? '导入的水印';
    final config = customConfig(title, doc);
    final migrated = {...files}
      ..remove('template.json')
      ..remove('manifest.json');
    migrated['config.json'] = Uint8List.fromList(
      utf8.encode(jsonEncode(config)),
    );
    return _original(fromFiles(migrated), files);
  }

  static WatermarkPackage _original(
    WatermarkPackage package,
    Map<String, Uint8List> files, {
    Uint8List? archive,
  }) => WatermarkPackage(
    package.config,
    package.files,
    package.document,
    package.title,
    package.preview,
    package.xiaomi,
    sourceDigest: canonicalWatermarkDigest(files),
    originalArchive: archive,
  );

  static WatermarkPackage fromFiles(Map<String, Uint8List> input) {
    final entry = input['config.json'];
    if (entry != null) {
      final config = parseJson(entry, 'config.json');
      if (config['format'] == staruwFormat) {
        final report = StaruwPackageValidator(
          input,
          validateSvg: validateSvg,
        ).validate();
        final u = config['staruw'] as Map;
        final future = u['protocolVersion'] != '1.0.0';
        final metadata = future ? <dynamic, dynamic>{} : u['metadata'] as Map;
        var doc = const EditDocument();
        final native = !future && u['render']['engine'] == 'xiaomi';
        if (native && report.canRender) {
          final original = Map<String, dynamic>.from(config)
            ..remove('staruw')
            ..remove('format')
            ..remove('starmark')
            ..remove('portable');
          doc = _documentFromConfig(original, input.keys.toSet());
        }
        doc = doc.copyWith(
          staruwConfig: config,
          staruwValues: future
              ? {}
              : Map<String, dynamic>.from(u['defaults'] as Map),
          staruwReport: report.json,
        );
        return WatermarkPackage(
          Map.unmodifiable(config),
          Map.unmodifiable(input),
          doc,
          metadata['name'] as String? ?? 'StarUW（仅保留原包）',
          metadata['preview'] as String? ?? '',
          native,
          sourceDigest: report.digest.isEmpty
              ? canonicalWatermarkDigest(input)
              : report.digest,
        );
      }
    }
    validateFiles(input);
    final files = Map<String, Uint8List>.from(input);
    final config = parseJson(
      files['config.json'] ?? (throw const FormatException('缺少 config.json')),
      'config.json',
    );
    if (config.containsKey('staruw')) {
      throw const FormatException('未知或缺少 StarUW 格式标识');
    }
    final native =
        config.containsKey('layout_group') && config.containsKey('wmRes');
    final metadata = Map<String, dynamic>.from(
      config['metadata'] as Map? ?? {},
    );
    final i18n = files['i18n.json'] == null
        ? <String, dynamic>{}
        : parseJson(files['i18n.json']!, 'i18n.json');
    var title = metadata['name']?.toString() ?? '导入的水印';
    if (title.startsWith('@')) {
      final translated = i18n[title.substring(1)];
      title = translated is Map
          ? (translated['zh-rCN'] ??
                    translated['zh-CN'] ??
                    translated['default'] ??
                    title)
                .toString()
          : title;
    }
    final doc = _documentFromConfig(config, files.keys.toSet());
    for (final entry
        in (doc.resourceBindings['regions'] as Map? ?? {}).entries) {
      final id = entry.key.toString(), bytes = files[entry.value]!;
      if (!RegExp(r'^[a-f0-9]{64}\.(png|jpg|svg)$').hasMatch(id) ||
          sha256.convert(bytes).toString() != id.split('.').first ||
          !entry.value.toString().endsWith('.${id.split('.').last}')) {
        throw const FormatException('Region image hash mismatch');
      }
    }
    final preview =
        (metadata['preview'] as String?) ??
        [
          'preview.png',
          'preview.webp',
          'preview.jpg',
        ].firstWhere(files.containsKey, orElse: () => '');
    if (preview.isNotEmpty &&
        (!safeWatermarkPath(preview) || !files.containsKey(preview))) {
      throw const FormatException('水印预览资源不存在');
    }
    return WatermarkPackage(
      Map.unmodifiable(config),
      Map.unmodifiable(files),
      doc,
      title,
      preview,
      native,
    );
  }

  /// Catalog-only reconstruction from a previously validated, local install.
  /// Selection and export revalidate its immutable payload before rendering.
  static EditDocument staruwCatalogDocument(
    Map<String, dynamic> config,
    Map<String, dynamic> report,
  ) {
    final u = config['staruw'] as Map;
    final future = u['protocolVersion'] != '1.0.0';
    final native = !future && u['render']['engine'] == 'xiaomi';
    final raw = native
        ? (Map<String, dynamic>.from(config)
            ..remove('format')
            ..remove('staruw')
            ..remove('starmark')
            ..remove('portable'))
        : null;
    return EditDocument(
      staruwConfig: config,
      staruwReport: report,
      staruwValues: future
          ? {}
          : Map<String, dynamic>.from(u['defaults'] as Map),
      xiaomiConfig: raw,
      mode: native
          ? (config['metadata']?['position'] == 'out'
                ? WatermarkMode.frame
                : WatermarkMode.overlay)
          : (!future && u['scene']?['canvas']?['mode'] == 'frame'
                ? WatermarkMode.frame
                : WatermarkMode.overlay),
    );
  }

  /// Parse bounded catalog JSON using indexed logical names, without loading
  /// pictures or fonts. Full import/use still goes through fromFiles/validateFiles.
  static EditDocument documentFromConfig(
    List<int> configBytes, {
    required Iterable<String> resourceNames,
  }) {
    final names = resourceNames.toSet();
    if (names.length > 512 ||
        names.any((name) => !safeWatermarkPath(name)) ||
        names.map((name) => name.toLowerCase()).toSet().length !=
            names.length) {
      throw const FormatException('无效的水印资源索引');
    }
    return _documentFromConfig(parseJson(configBytes, 'config.json'), names);
  }

  static EditDocument _documentFromConfig(
    Map<String, dynamic> config,
    Set<String> resourceNames,
  ) {
    if (config['format'] == 'com.realize.starmark.watermark' ||
        (config['render'] is Map &&
            (config['render'] as Map)['engine'] == 'scene')) {
      throw const FormatException('暂不支持新的通用场景水印格式');
    }
    if (config.containsKey('staruw')) {
      throw const FormatException('未知或缺少 StarUW 格式标识');
    }
    final native =
        config.containsKey('layout_group') && config.containsKey('wmRes');
    final metadata = Map<String, dynamic>.from(
      config['metadata'] as Map? ?? {},
    );
    EditDocument doc;
    if (native) {
      // These two versions occur in the audited factory source. A whitelist
      // also rejects invalid older/negative values and unknown future schemas.
      final version = config['version'];
      if (version is! num || !const [1.0, 1.58, 1.78, 1.81].contains(version)) {
        throw const FormatException('不支持此小米水印布局版本');
      }
      _validateNodes(config['layout_group'], 0, [0]);
      final raw = Map<String, dynamic>.from(config)..remove('starmark');
      doc = config['starmark'] is Map
          ? EditDocument.fromJson(
              Map<String, dynamic>.from(config['starmark'] as Map),
            )
          : const EditDocument();
      doc = doc.copyWith(
        xiaomiConfig: raw,
        mode: metadata['position'] == 'out'
            ? WatermarkMode.frame
            : WatermarkMode.overlay,
      );
      void checkReferences(dynamic value, {bool checkImages = true}) {
        if (value is Map) {
          for (final entry in value.entries) {
            // Several OEM packages omit selector-only background icons. They
            // are not rendered, whereas @img background values are required.
            checkReferences(
              entry.value,
              checkImages: checkImages && entry.key != 'icon',
            );
          }
        } else if (value is List) {
          for (final v in value) {
            checkReferences(v, checkImages: checkImages);
          }
        } else if (value is String) {
          for (final regex in [
            RegExp(r'@file=([^;]+)'),
            if (checkImages) RegExp(r'@img=([^;]+)'),
            RegExp(r'pathType=direct;path=([^;]+)'),
          ]) {
            for (final m in regex.allMatches(value)) {
              final path = m.group(1)!;
              if (!safeWatermarkPath(path) || !resourceNames.contains(path)) {
                throw FormatException('缺少水印资源：$path');
              }
            }
          }
        }
      }

      checkReferences(raw);
    } else {
      if (config['format'] != 'dev.starmark.watermark' ||
          config['version'] != 1) {
        throw const FormatException('未知水印 JSON 格式');
      }
      doc = EditDocument.fromJson(
        Map<String, dynamic>.from(config['document'] as Map),
      );
    }
    final bindings = doc.resourceBindings;
    if (config['portable'] == true) {
      final fonts = bindings['fonts'] as Map? ?? {};
      final requiredFonts = doc.font.automatic
          ? ['oxanium', 'lg1056', 'mi-sans']
          : [doc.font.id, 'mi-sans'];
      if (requiredFonts.any((id) => !fonts.containsKey(id))) {
        throw const FormatException('完整水印包缺少所需字体');
      }
    }
    for (final group in [
      'fonts',
      'models',
      'brands',
      'modelMarks',
      'regions',
    ]) {
      for (final path in (bindings[group] as Map? ?? {}).values) {
        if (path is! String ||
            !safeWatermarkPath(path) ||
            !resourceNames.contains(path)) {
          throw const FormatException('水印配置引用的资源不完整');
        }
      }
    }
    for (final row in doc.regionReplacements.values.whereType<Map>()) {
      final id = row['asset'];
      if (id != null && !(bindings['regions'] as Map? ?? {}).containsKey(id)) {
        throw const FormatException('Missing region image binding');
      }
    }
    final preview = metadata['preview'];
    if (preview != null &&
        (preview is! String ||
            (preview.isNotEmpty &&
                (!safeWatermarkPath(preview) ||
                    !resourceNames.contains(preview))))) {
      throw const FormatException('水印预览资源不存在');
    }
    return doc;
  }

  /// Recheck caller-supplied packages at the filesystem write boundary too.
  static void validateFiles(Map<String, Uint8List> files) {
    var total = 0;
    final names = <String>{};
    if (files.length > 512) throw const FormatException('水印资源过多');
    for (final entry in files.entries) {
      if (!names.add(entry.key.toLowerCase()) ||
          entry.value.length > watermarkFileLimit) {
        throw const FormatException('水印资源重复或过大');
      }
      _validateResource(entry.key, entry.value);
      total += entry.value.length;
      if (total > watermarkTotalLimit) throw const FormatException('水印资源预算超限');
    }
  }

  static void _validateNodes(dynamic value, int depth, List<int> count) {
    if (depth > 64) throw const FormatException('水印布局嵌套过深');
    if (value is Map) {
      if (value['type'] != null &&
          !['Layout', 'ImageView', 'TextView'].contains(value['type'])) {
        throw FormatException('暂不支持此水印组件：${value['type']}');
      }
      if (++count[0] > 4096) throw const FormatException('水印组件过多');
      for (final child in value.values) {
        _validateNodes(child, depth + 1, count);
      }
    } else if (value is List) {
      for (final child in value) {
        _validateNodes(child, depth + 1, count);
      }
    }
  }
}

class BoundedWatermarkOutput extends OutputMemoryStream {
  final int limit;
  BoundedWatermarkOutput({this.limit = watermarkFileLimit});
  void check(int count) {
    if (length + count > limit) {
      throw const FormatException('ZIP 实际资源超过预算');
    }
  }

  @override
  void writeByte(int value) {
    check(1);
    super.writeByte(value);
  }

  @override
  void writeBytes(List<int> bytes, {int? length}) {
    check(length ?? bytes.length);
    super.writeBytes(bytes, length: length);
  }

  @override
  void writeStream(InputStream stream) {
    check(stream.length);
    super.writeStream(stream);
  }

  @override
  void writeBackReference(int distance, int count) {
    check(count);
    super.writeBackReference(distance, count);
  }
}

/// Canonical file content before repository adds installation-local identities.
String canonicalWatermarkDigest(Map<String, Uint8List> files) {
  final names = files.keys.toList()..sort();
  return sha256
      .convert(
        utf8.encode(
          jsonEncode([
            for (final name in names)
              [
                name,
                files[name]!.length,
                sha256.convert(files[name]!).toString(),
              ],
          ]),
        ),
      )
      .toString();
}
