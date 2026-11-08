import 'dart:typed_data';
import 'contract.dart';

/// Bounded SFNT directory, metrics, cmap and variation axes. Font identity is
/// supplied by the payload hash; family names never select installed fonts.
class StaruwFontFile {
  final Uint8List bytes;
  late final ByteData data = ByteData.sublistView(bytes);
  final Map<String, ({int offset, int length})> tables = {};
  final Map<String, ({double min, double initial, double max})> axes = {};
  late final int unitsPerEm, ascent, descent, glyphCount;
  late final String? displayName = _displayName();
  String? _displayName() {
    final table = tables['name'];
    if (table == null || table.length < 6) return null;
    final count = data.getUint16(table.offset + 2),
        strings = data.getUint16(table.offset + 4);
    if (count > 4096 ||
        6 + count * 12 > table.length ||
        strings > table.length) {
      return null;
    }
    String? name;
    var rank = -1;
    for (var i = 0; i < count; i++) {
      final at = table.offset + 6 + i * 12,
          platform = data.getUint16(at),
          language = data.getUint16(at + 4),
          id = data.getUint16(at + 6);
      if (![0, 3].contains(platform) || ![1, 4, 16].contains(id)) continue;
      final length = data.getUint16(at + 8),
          start = strings + data.getUint16(at + 10);
      if (length == 0 ||
          length > 512 ||
          length.isOdd ||
          start + length > table.length) {
        continue;
      }
      final candidate = String.fromCharCodes([
        for (var p = 0; p < length; p += 2)
          data.getUint16(table.offset + start + p),
      ]).trim();
      if (candidate.isEmpty || candidate.codeUnits.any((c) => c < 32)) continue;
      final score =
          (id == 4
              ? 6
              : id == 16
              ? 3
              : 0) +
          (language == 0x409 ? 2 : 0);
      if (score > rank) {
        rank = score;
        name = candidate;
      }
    }
    return name;
  }

  StaruwFontFile(this.bytes) {
    void check(bool ok) => staruwNeed(ok, 'E_RESOURCE', '字库结构无效');
    check(bytes.length >= 12);
    final signature = data.getUint32(0);
    check(
      signature == 0x00010000 ||
          signature == 0x4f54544f ||
          signature == 0x74727565,
    );
    final count = data.getUint16(4);
    check(count > 0 && count <= 256 && 12 + count * 16 <= bytes.length);
    final spans = <({int start, int end})>[];
    for (var i = 0; i < count; i++) {
      final at = 12 + i * 16;
      final name = String.fromCharCodes(bytes.sublist(at, at + 4));
      final offset = data.getUint32(at + 8), length = data.getUint32(at + 12);
      check(
        !tables.containsKey(name) &&
            offset >= 12 + count * 16 &&
            offset + length <= bytes.length,
      );
      tables[name] = (offset: offset, length: length);
      if (length > 0) spans.add((start: offset, end: offset + length));
    }
    spans.sort((a, b) => a.start.compareTo(b.start));
    for (var i = 1; i < spans.length; i++) {
      check(spans[i].start >= spans[i - 1].end);
    }
    for (final name in ['head', 'hhea', 'hmtx', 'maxp', 'cmap']) {
      check(tables.containsKey(name));
    }
    check(
      tables.containsKey('glyf') && tables.containsKey('loca') ||
          tables.containsKey('CFF ') ||
          tables.containsKey('CFF2'),
    );
    check(
      tables['head']!.length >= 54 &&
          tables['hhea']!.length >= 36 &&
          tables['maxp']!.length >= 6 &&
          tables['cmap']!.length >= 4,
    );
    final head = tables['head']!.offset, hhea = tables['hhea']!.offset;
    unitsPerEm = data.getUint16(head + 18);
    glyphCount = data.getUint16(tables['maxp']!.offset + 4);
    check(unitsPerEm >= 16 && unitsPerEm <= 16384 && glyphCount > 0);
    final os2 = tables['OS/2'];
    check(os2 == null || os2.length >= 72);
    ascent = data.getInt16(os2 == null ? hhea + 4 : os2.offset + 68);
    descent = data.getInt16(os2 == null ? hhea + 6 : os2.offset + 70);
    check(ascent > 0 && descent <= 0);
    final metrics = data.getUint16(hhea + 34);
    check(
      metrics > 0 &&
          metrics <= glyphCount &&
          tables['hmtx']!.length >= metrics * 4 + (glyphCount - metrics) * 2,
    );
    final fvar = tables['fvar'];
    if (fvar != null) {
      check(fvar.length >= 16);
      final axisOffset = data.getUint16(fvar.offset + 4),
          axisCount = data.getUint16(fvar.offset + 8),
          axisSize = data.getUint16(fvar.offset + 10);
      check(
        axisCount <= 32 &&
            axisSize >= 20 &&
            axisOffset + axisCount * axisSize <= fvar.length,
      );
      for (var i = 0; i < axisCount; i++) {
        final at = fvar.offset + axisOffset + i * axisSize;
        final tag = String.fromCharCodes(bytes.sublist(at, at + 4));
        final min = data.getInt32(at + 4) / 65536,
            initial = data.getInt32(at + 8) / 65536,
            max = data.getInt32(at + 12) / 65536;
        check(!axes.containsKey(tag) && min <= initial && initial <= max);
        axes[tag] = (min: min, initial: initial, max: max);
      }
    }
    _checkCmap();
    this.metrics(
      const {},
    ); // Validate variation offsets before passing bytes to Skia.
  }

  /// OpenType MVAR hasc/hdsc, evaluated after fvar normalization and avar.
  ({double ascent, double descent}) metrics(Map values) {
    validateAxes(values);
    final coordinates = <double>[];
    for (final entry in axes.entries) {
      final a = entry.value,
          v = (values[entry.key] as num?)?.toDouble() ?? a.initial;
      coordinates.add(
        v == a.initial
            ? 0
            : v < a.initial
            ? (v - a.initial) / (a.initial - a.min)
            : (v - a.initial) / (a.max - a.initial),
      );
    }
    void bounded(int at, int length, ({int offset, int length}) table) =>
        staruwNeed(
          at >= table.offset &&
              length >= 0 &&
              at + length <= table.offset + table.length,
          'E_RESOURCE',
          '字体变轴表越界',
        );
    final avar = tables['avar'];
    if (avar != null) {
      bounded(avar.offset, 8, avar);
      staruwNeed(
        data.getUint16(avar.offset) == 1 &&
            data.getUint16(avar.offset + 6) == axes.length,
        'E_RESOURCE',
        '不支持或无效的 avar 表',
      );
      var at = avar.offset + 8;
      for (var axis = 0; axis < axes.length; axis++) {
        bounded(at, 2, avar);
        final count = data.getUint16(at);
        at += 2;
        bounded(at, count * 4, avar);
        final pairs = <({double from, double to})>[];
        for (var i = 0; i < count; i++, at += 4) {
          final from = data.getInt16(at) / 16384,
              to = data.getInt16(at + 2) / 16384;
          staruwNeed(
            from >= -1 &&
                from <= 1 &&
                to >= -1 &&
                to <= 1 &&
                (pairs.isEmpty ||
                    from > pairs.last.from && to >= pairs.last.to),
            'E_RESOURCE',
            'avar 映射无效',
          );
          pairs.add((from: from, to: to));
        }
        if (![
          -1.0,
          0.0,
          1.0,
        ].every((v) => pairs.any((p) => p.from == v && p.to == v))) {
          continue;
        }
        final value = coordinates[axis];
        for (var i = 1; i < pairs.length; i++) {
          final a = pairs[i - 1], b = pairs[i];
          if (value <= b.from) {
            coordinates[axis] =
                a.to + (value - a.from) / (b.from - a.from) * (b.to - a.to);
            break;
          }
        }
      }
    }
    final mvar = tables['MVAR'];
    if (mvar == null) {
      return (ascent: ascent.toDouble(), descent: descent.toDouble());
    }
    bounded(mvar.offset, 12, mvar);
    final count = data.getUint16(mvar.offset + 8),
        stride = data.getUint16(mvar.offset + 6);
    staruwNeed(
      data.getUint16(mvar.offset) == 1 && stride >= 8,
      'E_RESOURCE',
      'MVAR 头无效',
    );
    bounded(mvar.offset + 12, count * stride, mvar);
    if (count == 0) {
      return (ascent: ascent.toDouble(), descent: descent.toDouble());
    }
    final store = mvar.offset + data.getUint16(mvar.offset + 10);
    bounded(store, 8, mvar);
    staruwNeed(data.getUint16(store) == 1, 'E_RESOURCE', '字体变轴存储格式无效');
    final subtables = data.getUint16(store + 6),
        regions = store + data.getUint32(store + 2);
    bounded(store + 8, subtables * 4, mvar);
    bounded(regions, 4, mvar);
    final axisCount = data.getUint16(regions),
        regionCount = data.getUint16(regions + 2);
    staruwNeed(
      axisCount == axes.length && regionCount < 32768,
      'E_RESOURCE',
      '字体变轴区域无效',
    );
    bounded(regions + 4, axisCount * regionCount * 6, mvar);
    final scalars = <double>[];
    for (var r = 0; r < regionCount; r++) {
      var scalar = 1.0;
      for (var a = 0; a < axisCount; a++) {
        final at = regions + 4 + (r * axisCount + a) * 6;
        final start = data.getInt16(at) / 16384,
            peak = data.getInt16(at + 2) / 16384,
            end = data.getInt16(at + 4) / 16384,
            value = coordinates[a];
        staruwNeed(
          start >= -1 && end <= 1 && start <= peak && peak <= end,
          'E_RESOURCE',
          '字体变轴区域坐标无效',
        );
        if (peak == 0 || start < 0 && end > 0) continue;
        if (value == peak) continue;
        if (value <= start || value >= end) {
          scalar = 0;
          continue;
        }
        scalar *= value < peak
            ? (value - start) / (peak - start)
            : (end - value) / (end - peak);
      }
      scalars.add(scalar);
    }
    double delta(int outer, int inner) {
      if (outer == 65535 && inner == 65535) return 0;
      staruwNeed(outer < subtables, 'E_RESOURCE', 'MVAR 外部索引越界');
      final offset = data.getUint32(store + 8 + outer * 4);
      if (offset == 0) return 0;
      final sub = store + offset;
      bounded(sub, 6, mvar);
      final items = data.getUint16(sub),
          words = data.getUint16(sub + 2),
          n = data.getUint16(sub + 4),
          longCount = words & 0x7fff;
      final longWords = (words & 0x8000) != 0;
      staruwNeed(
        inner < items && longCount <= n && n <= regionCount,
        'E_RESOURCE',
        'MVAR 增量索引越界',
      );
      final rowBytes = (n + longCount) * (longWords ? 2 : 1);
      bounded(sub + 6, n * 2 + items * rowBytes, mvar);
      var at = sub + 6 + n * 2 + inner * rowBytes, result = 0.0;
      for (var i = 0; i < n; i++) {
        final region = data.getUint16(sub + 6 + i * 2);
        staruwNeed(region < regionCount, 'E_RESOURCE', 'MVAR 区域索引越界');
        final size = i < longCount ? (longWords ? 4 : 2) : (longWords ? 2 : 1);
        final value = size == 4
            ? data.getInt32(at)
            : size == 2
            ? data.getInt16(at)
            : data.getInt8(at);
        at += size;
        result += value * scalars[region];
      }
      return result;
    }

    var up = ascent.toDouble(), down = descent.toDouble(), last = -1;
    for (var i = 0; i < count; i++) {
      final at = mvar.offset + 12 + i * stride, tag = data.getUint32(at);
      staruwNeed(tag > last, 'E_RESOURCE', 'MVAR 标签重复或无序');
      last = tag;
      final value = delta(data.getUint16(at + 4), data.getUint16(at + 6));
      if (tag == 0x68617363) up += value;
      if (tag == 0x68647363) down += value;
    }
    staruwNeed(up > 0 && down <= 0, 'E_RESOURCE', '字体变轴度量无效');
    return (ascent: up, descent: down);
  }

  void validateAxes(Map values) {
    for (final entry in values.entries) {
      final axis = axes[entry.key];
      staruwNeed(
        axis != null &&
            entry.value is num &&
            (entry.value as num).isFinite &&
            entry.value >= axis.min &&
            entry.value <= axis.max,
        'E_RESOURCE',
        '字库不含此轴或轴值超限',
      );
    }
  }

  final List<({int format, int at, int length})> _cmaps = [];
  void _checkCmap() {
    final table = tables['cmap']!;
    final count = data.getUint16(table.offset + 2);
    staruwNeed(
      count <= 256 && 4 + 8 * count <= table.length,
      'E_RESOURCE',
      '无效字体字符映射',
    );
    for (var i = 0; i < count; i++) {
      final row = table.offset + 4 + i * 8;
      final platform = data.getUint16(row), encoding = data.getUint16(row + 2);
      final offset = data.getUint32(row + 4);
      staruwNeed(offset + 2 <= table.length, 'E_RESOURCE', '字符映射偏移超限');
      final at = table.offset + offset, format = data.getUint16(at);
      if (!(platform == 0 || platform == 3 && [1, 10].contains(encoding)) ||
          ![4, 12].contains(format)) {
        continue;
      }
      staruwNeed(
        offset + (format == 12 ? 16 : 14) <= table.length,
        'E_RESOURCE',
        '字符映射不完整',
      );
      final length = format == 12
          ? data.getUint32(at + 4)
          : data.getUint16(at + 2);
      staruwNeed(
        length >= (format == 12 ? 16 : 16) && offset + length <= table.length,
        'E_RESOURCE',
        '字符映射长度超限',
      );
      if (format == 12) {
        final groups = data.getUint32(at + 12);
        staruwNeed(16 + groups * 12 <= length, 'E_RESOURCE', '字符映射区段超限');
        var previous = -1;
        for (var g = 0; g < groups; g++) {
          final p = at + 16 + g * 12,
              start = data.getUint32(p),
              end = data.getUint32(p + 4),
              glyph = data.getUint32(p + 8);
          staruwNeed(
            start > previous &&
                start <= end &&
                end <= 0x10ffff &&
                glyph + end - start < glyphCount,
            'E_RESOURCE',
            '字符映射无效',
          );
          previous = end;
        }
      } else {
        final n = data.getUint16(at + 6) ~/ 2;
        staruwNeed(
          n > 0 && data.getUint16(at + 6) == n * 2 && 16 + n * 8 <= length,
          'E_RESOURCE',
          '字符映射区段无效',
        );
        var previous = -1;
        for (var g = 0; g < n; g++) {
          final end = data.getUint16(at + 14 + g * 2),
              start = data.getUint16(at + 16 + n * 2 + g * 2);
          final ro = at + 16 + n * 6 + g * 2, delta = data.getUint16(ro);
          staruwNeed(
            start <= end &&
                end > previous &&
                (delta == 0 ||
                    ro + delta + 2 * (end - start) + 2 <= at + length),
            'E_RESOURCE',
            '字符映射区段越界',
          );
          previous = end;
        }
      }
      _cmaps.add((format: format, at: at, length: length));
    }
    staruwNeed(_cmaps.isNotEmpty, 'E_RESOURCE', '字库缺少 Unicode 字符映射');
    _cmaps.sort((a, b) => b.format.compareTo(a.format));
  }

  int glyph(int cp) {
    for (final map in _cmaps) {
      final at = map.at;
      if (map.format == 12) {
        var lo = 0, hi = data.getUint32(at + 12) - 1;
        while (lo <= hi) {
          final mid = (lo + hi) ~/ 2,
              p = at + 16 + mid * 12,
              start = data.getUint32(p),
              end = data.getUint32(p + 4);
          if (cp < start) {
            hi = mid - 1;
          } else if (cp > end) {
            lo = mid + 1;
          } else {
            return data.getUint32(p + 8) + cp - start;
          }
        }
      } else if (cp <= 0xffff) {
        final n = data.getUint16(at + 6) ~/ 2;
        var lo = 0, hi = n - 1;
        while (lo <= hi) {
          final mid = (lo + hi) ~/ 2, end = data.getUint16(at + 14 + mid * 2);
          if (cp > end) {
            lo = mid + 1;
          } else {
            hi = mid - 1;
          }
        }
        if (lo >= n) continue;
        final start = data.getUint16(at + 16 + n * 2 + lo * 2);
        if (cp < start) continue;
        final delta = data.getInt16(at + 16 + n * 4 + lo * 2),
            ro = at + 16 + n * 6 + lo * 2,
            offset = data.getUint16(ro);
        final g = offset == 0
            ? cp
            : data.getUint16(ro + offset + 2 * (cp - start));
        final index = (g == 0 && offset != 0) ? 0 : (g + delta) & 0xffff;
        staruwNeed(index < glyphCount, 'E_RESOURCE', '字符映射指向缺失字形');
        if (index != 0) return index;
      }
    }
    return 0;
  }
}
