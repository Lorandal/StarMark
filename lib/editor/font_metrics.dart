import 'dart:typed_data';

/// Visible capital height, used to preserve OEM type size when swapping faces.
/// TrueType's H outline takes precedence over sometimes stale OS/2 metadata.
/// CFF fonts and faces without H use their declared OpenType cap height.
double? fontCapHeight(Uint8List bytes) {
  if (bytes.length < 12) return null;
  final data = ByteData.sublistView(bytes);
  final count = data.getUint16(4);
  if (12 + count * 16 > bytes.length) return null;
  int? units, cap;
  final tables = <String, ByteData>{};
  for (var i = 0; i < count; i++) {
    final p = 12 + i * 16;
    final tag = String.fromCharCodes(bytes.sublist(p, p + 4));
    final offset = data.getUint32(p + 8), length = data.getUint32(p + 12);
    if (offset + length > bytes.length) return null;
    tables[tag] = ByteData.sublistView(bytes, offset, offset + length);
    if (tag == 'head' && length >= 20) units = data.getUint16(offset + 18);
    if (tag == 'OS/2' && length >= 90 && data.getUint16(offset) >= 2) {
      cap = data.getInt16(offset + 88);
    }
  }
  cap = _trueTypeCapitalTop(tables) ?? cap;
  return units != null &&
          units > 0 &&
          cap != null &&
          cap > 0 &&
          cap <= units * 2
      ? cap / units
      : null;
}

int? _trueTypeCapitalTop(Map<String, ByteData> tables) {
  final head = tables['head'], cmap = tables['cmap'];
  final loca = tables['loca'], glyf = tables['glyf'];
  if (head == null ||
      head.lengthInBytes < 54 ||
      cmap == null ||
      cmap.lengthInBytes < 4 ||
      loca == null ||
      glyf == null) {
    return null;
  }
  final count = cmap.getUint16(2);
  if (4 + count * 8 > cmap.lengthInBytes) return null;
  for (var i = 0; i < count; i++) {
    final record = 4 + i * 8;
    final platform = cmap.getUint16(record),
        encoding = cmap.getUint16(record + 2);
    if (platform != 0 &&
        !(platform == 3 && (encoding == 1 || encoding == 10))) {
      continue;
    }
    final offset = cmap.getUint32(record + 4);
    if (offset + 2 > cmap.lengthInBytes) continue;
    final glyph = _capitalGlyph(cmap, offset);
    if (glyph == null || glyph == 0) continue;
    final format = head.getInt16(50);
    final int start, end;
    if (format == 0 && (glyph + 2) * 2 <= loca.lengthInBytes) {
      start = loca.getUint16(glyph * 2) * 2;
      end = loca.getUint16((glyph + 1) * 2) * 2;
    } else if (format == 1 && (glyph + 2) * 4 <= loca.lengthInBytes) {
      start = loca.getUint32(glyph * 4);
      end = loca.getUint32((glyph + 1) * 4);
    } else {
      continue;
    }
    if (end > glyf.lengthInBytes || start + 10 > end) continue;
    final top = glyf.getInt16(start + 8);
    if (top > 0) return top;
  }
  return null;
}

int? _capitalGlyph(ByteData cmap, int offset) {
  // H has a flat capital top rather than round-glyph overshoot.
  const code = 0x48;
  final format = cmap.getUint16(offset);
  if (format == 4) {
    if (offset + 14 > cmap.lengthInBytes) return null;
    final length = cmap.getUint16(offset + 2);
    final count = cmap.getUint16(offset + 6) ~/ 2;
    if (length < 16 + count * 8 || offset + length > cmap.lengthInBytes) {
      return null;
    }
    for (var i = 0; i < count; i++) {
      final end = cmap.getUint16(offset + 14 + i * 2);
      final start = cmap.getUint16(offset + 16 + count * 2 + i * 2);
      if (code < start || code > end) continue;
      final delta = cmap.getInt16(offset + 16 + count * 4 + i * 2);
      final address = offset + 16 + count * 6 + i * 2;
      final range = cmap.getUint16(address);
      if (range == 0) return (code + delta) & 0xffff;
      final location = address + range + (code - start) * 2;
      if (location + 2 > offset + length) return null;
      final glyph = cmap.getUint16(location);
      return glyph == 0 ? null : (glyph + delta) & 0xffff;
    }
  } else if (format == 12) {
    if (offset + 16 > cmap.lengthInBytes) return null;
    final length = cmap.getUint32(offset + 4),
        count = cmap.getUint32(offset + 12);
    if (length < 16 + count * 12 || offset + length > cmap.lengthInBytes) {
      return null;
    }
    for (var i = 0; i < count; i++) {
      final group = offset + 16 + i * 12;
      final start = cmap.getUint32(group), end = cmap.getUint32(group + 4);
      if (code >= start && code <= end) {
        return cmap.getUint32(group + 8) + code - start;
      }
    }
  }
  return null;
}
