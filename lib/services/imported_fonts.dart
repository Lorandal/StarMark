import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import 'package:watermark_engine/watermark_engine.dart';

/// Content-addressed private copies. No source paths or document-provider URIs
/// are persisted; the same identifier can be bound to a portable ZIP resource.
class ImportedFonts {
  ImportedFonts(this.base);
  final Directory base;
  static const maxBytes = 32 * 1024 * 1024;
  Directory get _root => Directory('${base.path}/imported-fonts');

  File _file(String id, String extension) {
    if (!RegExp(r'^imported-[a-f0-9]{64}$').hasMatch(id)) {
      throw const FormatException('无效导入字体 ID');
    }
    return File('${_root.path}/$id.$extension');
  }

  Future<List<Map<String, dynamic>>> list() async {
    if (!await _root.exists()) return [];
    final rows = <Map<String, dynamic>>[];
    await for (final item in _root.list()) {
      if (item is! File || !item.path.endsWith('.json')) continue;
      try {
        final row = Map<String, dynamic>.from(
          jsonDecode(await item.readAsString()) as Map,
        );
        final ref = FontRef.fromJson(row);
        await bytes(ref);
        rows.add(row);
      } catch (_) {
        /* An interrupted or damaged entry is never selectable. */
      }
    }
    rows.sort((a, b) => (a['id'] as String).compareTo(b['id'] as String));
    return rows;
  }

  Future<Uint8List> bytes(FontRef ref) async {
    final file = _file(ref.id, 'font');
    if (await file.length() > maxBytes) {
      throw const FormatException('字体超过 32 MiB');
    }
    final value = await file.readAsBytes();
    if (ref.source != 'imported' ||
        sha256.convert(value).toString() != ref.fingerprint ||
        ref.id != 'imported-${ref.fingerprint}') {
      throw const FormatException('导入字体指纹不匹配');
    }
    validate(value);
    return value;
  }

  Future<Map<String, dynamic>> importBytes(
    Uint8List value,
    String title,
  ) async {
    final variant = validate(value);
    final hash = sha256.convert(value).toString();
    final id = 'imported-$hash';
    final metadata = _file(id, 'json');
    if (await metadata.exists()) {
      final row = Map<String, dynamic>.from(
        jsonDecode(await metadata.readAsString()) as Map,
      );
      await bytes(FontRef.fromJson(row));
      return row;
    }
    final cleaned = title
        .split(RegExp(r'[/\\]'))
        .last
        .replaceAll(RegExp(r'\.(ttf|otf)$', caseSensitive: false), '')
        .trim();
    final row = <String, dynamic>{
      ...FontRef(
        source: 'imported',
        id: id,
        fingerprint: hash,
        weight: variant.weight,
        style: variant.style,
        axes: variant.axes,
      ).toJson(),
      'title': cleaned.isEmpty
          ? '导入字体'
          : cleaned.substring(0, cleaned.length.clamp(0, 80)),
    };
    await _root.create(recursive: true);
    final font = _file(id, 'font');
    final pendingFont = File('${font.path}.tmp');
    final pendingMetadata = File('${metadata.path}.tmp');
    try {
      await pendingFont.writeAsBytes(value, flush: true);
      await pendingFont.rename(font.path);
      await pendingMetadata.writeAsString(jsonEncode(row), flush: true);
      await pendingMetadata.rename(metadata.path);
    } catch (_) {
      for (final file in [pendingFont, pendingMetadata]) {
        if (await file.exists()) await file.delete();
      }
      rethrow;
    }
    return row;
  }

  /// Checks SFNT structure and real outlines, rather than trusting a filename or
  /// MIME type. Bounds are checked before every table-specific read.
  static FontRef validate(Uint8List bytes) {
    Never invalid() => throw const FormatException('请选择有效的 TTF 或 OTF 字体');
    if (bytes.length > maxBytes) throw const FormatException('字体超过 32 MiB');
    if (bytes.length < 12) invalid();
    final data = ByteData.sublistView(bytes);
    final signature = data.getUint32(0);
    if (signature != 0x00010000 && signature != 0x4f54544f) invalid();
    final count = data.getUint16(4);
    if (count == 0 || count > 256 || 12 + count * 16 > bytes.length) invalid();
    final tables = <String, ({int offset, int length})>{};
    for (var i = 0; i < count; i++) {
      final p = 12 + i * 16;
      final tag = String.fromCharCodes(bytes.sublist(p, p + 4));
      final offset = data.getUint32(p + 8), length = data.getUint32(p + 12);
      if (tables.containsKey(tag) ||
          offset < 12 + count * 16 ||
          offset > bytes.length ||
          length > bytes.length - offset) {
        invalid();
      }
      tables[tag] = (offset: offset, length: length);
    }
    int table(String tag, int minimum) {
      final value = tables[tag];
      if (value == null || value.length < minimum) invalid();
      return value.offset;
    }

    final head = table('head', 54);
    if (data.getUint32(head + 12) != 0x5f0f3cf5) invalid();
    final glyphs = data.getUint16(table('maxp', 6) + 4);
    if (glyphs == 0) invalid();
    table('name', 6);
    final cmap = table('cmap', 4);
    final cmapCount = data.getUint16(cmap + 2);
    if (cmapCount == 0 || 4 + cmapCount * 8 > tables['cmap']!.length) invalid();
    for (var i = 0; i < cmapCount; i++) {
      if (data.getUint32(cmap + 8 + i * 8) >= tables['cmap']!.length) invalid();
    }
    if (signature == 0x00010000) {
      table('glyf', 1);
      final format = data.getInt16(head + 50);
      if (format != 0 && format != 1) invalid();
      table('loca', (glyphs + 1) * (format == 0 ? 2 : 4));
    } else if (tables.containsKey('CFF ')) {
      table('CFF ', 4);
    } else {
      table('CFF2', 5);
    }
    var weight = 400;
    if ((tables['OS/2']?.length ?? 0) >= 6) {
      weight = data.getUint16(tables['OS/2']!.offset + 4).clamp(1, 1000);
    }
    final axes = <String, double>{};
    if (tables.containsKey('fvar')) {
      final fvar = table('fvar', 16), length = tables['fvar']!.length;
      final offset = data.getUint16(fvar + 4),
          count = data.getUint16(fvar + 8),
          size = data.getUint16(fvar + 10);
      if (data.getUint32(fvar) != 0x00010000 ||
          offset < 16 ||
          size < 20 ||
          count > 64 ||
          offset + count * size > length) {
        invalid();
      }
      for (var i = 0; i < count; i++) {
        final p = fvar + offset + i * size;
        final tag = String.fromCharCodes(bytes.sublist(p, p + 4));
        final min = data.getInt32(p + 4) / 65536,
            value = data.getInt32(p + 8) / 65536,
            max = data.getInt32(p + 12) / 65536;
        if (axes.containsKey(tag) ||
            min > value ||
            value > max ||
            !RegExp(r'^[ -~]{4}$').hasMatch(tag)) {
          invalid();
        }
        axes[tag] = value;
      }
      if (axes.containsKey('wght')) {
        weight = axes['wght']!.round().clamp(1, 1000);
      }
    }
    return FontRef(
      weight: weight,
      style: data.getUint16(head + 44) & 2 != 0 ? 'italic' : 'normal',
      axes: Map.unmodifiable(axes),
    );
  }
}
