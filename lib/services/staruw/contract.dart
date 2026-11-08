import 'dart:convert';
import 'generated_contract.dart';

const staruwFormat = 'com.realize.starmark.watermark';

class StaruwException extends FormatException {
  final String code, path;
  const StaruwException(this.code, String message, [this.path = ''])
    : super(message);
  Map<String, dynamic> toDiagnostic() => {
    'code': code,
    'severity': 'error',
    'path': path,
    'message': message,
  };
  @override
  String toString() => '$code: $message${path.isEmpty ? '' : ' ($path)'}';
}

void staruwNeed(
  bool condition,
  String code,
  String message, [
  String path = '',
]) {
  if (!condition) throw StaruwException(code, message, path);
}

String staruwPointerToken(String s) =>
    s.replaceAll('~', '~0').replaceAll('/', '~1');

dynamic staruwPointer(dynamic value, String pointer) {
  if (pointer.isEmpty) return value;
  staruwNeed(
    RegExp(r'^/(?:[^~]|~[01])*$').hasMatch(pointer),
    'E_BINDING',
    '无效绑定指针',
    pointer,
  );
  for (final part in pointer.substring(1).split('/')) {
    final key = part.replaceAll('~1', '/').replaceAll('~0', '~');
    if (value is Map && value.containsKey(key)) {
      value = value[key];
    } else if (value is List &&
        RegExp(r'^(0|[1-9][0-9]*)$').hasMatch(key) &&
        int.parse(key) < value.length) {
      value = value[int.parse(key)];
    } else {
      throw StaruwException('E_BINDING', '绑定目标不存在', pointer);
    }
  }
  return value;
}

/// Parse once with duplicate-key, depth and finite-number checks. Errors never
/// include values from private source metadata.
dynamic staruwJson(List<int> bytes, [String path = 'config.json']) {
  staruwNeed(bytes.length <= 4 * 1024 * 1024, 'E_LIMIT', 'JSON 超过 4 MiB', path);
  staruwNeed(
    !(bytes.length >= 3 &&
        bytes[0] == 0xef &&
        bytes[1] == 0xbb &&
        bytes[2] == 0xbf),
    'E_SCHEMA',
    'JSON 不接受 BOM',
    path,
  );
  try {
    final text = utf8.decode(bytes);
    staruwNeed(!text.startsWith('\uFEFF'), 'E_SCHEMA', 'JSON 不接受 BOM', path);
    return _JsonReader(text, path).read();
  } on StaruwException {
    rethrow;
  } catch (_) {
    throw StaruwException('E_SCHEMA', 'JSON 无法读取', path);
  }
}

class _JsonReader {
  final String text, path;
  int at = 0, values = 0;
  _JsonReader(this.text, this.path);
  void space() {
    while (at < text.length &&
        const [32, 9, 10, 13].contains(text.codeUnitAt(at))) {
      at++;
    }
  }

  Never fail() => throw StaruwException('E_SCHEMA', 'JSON 结构无效或含重复键', path);
  String string() {
    if (at >= text.length || text[at] != '"') fail();
    final start = at++;
    while (at < text.length) {
      final c = text.codeUnitAt(at++);
      if (c == 92) {
        at++;
        continue;
      }
      if (c == 34) {
        final value = jsonDecode(text.substring(start, at)) as String;
        // JSON permits escaped UTF-16, but unpaired surrogates are not Unicode.
        for (var i = 0; i < value.length; i++) {
          final u = value.codeUnitAt(i);
          if (u >= 0xd800 && u <= 0xdbff) {
            if (++i >= value.length ||
                value.codeUnitAt(i) < 0xdc00 ||
                value.codeUnitAt(i) > 0xdfff) {
              fail();
            }
          } else if (u >= 0xdc00 && u <= 0xdfff) {
            fail();
          }
        }
        return value;
      }
    }
    return fail();
  }

  dynamic value(int depth) {
    if (depth > 256 || ++values > 262144) {
      throw StaruwException('E_LIMIT', 'JSON 结构过大或过深', path);
    }
    space();
    if (at >= text.length) fail();
    final c = text[at];
    if (c == '"') return string();
    if (c == '{' || c == '[') {
      at++;
      final map = <String, dynamic>{};
      final list = <dynamic>[];
      final end = c == '{' ? '}' : ']';
      space();
      if (at < text.length && text[at] == end) {
        at++;
        return c == '{' ? map : list;
      }
      while (true) {
        space();
        if (c == '{') {
          final key = string();
          space();
          if (map.containsKey(key) || at >= text.length || text[at++] != ':') {
            fail();
          }
          map[key] = value(depth + 1);
        } else {
          list.add(value(depth + 1));
        }
        space();
        if (at >= text.length) fail();
        final token = text[at++];
        if (token == end) return c == '{' ? map : list;
        if (token != ',') fail();
      }
    }
    final start = at;
    while (at < text.length &&
        !const [32, 9, 10, 13, 44, 93, 125].contains(text.codeUnitAt(at))) {
      at++;
    }
    if (at == start) fail();
    final scalar = jsonDecode(text.substring(start, at));
    if (scalar is num && !scalar.isFinite) fail();
    return scalar;
  }

  dynamic read() {
    final result = value(0);
    space();
    if (at != text.length) fail();
    return result;
  }
}

String staruwNfc(String text) {
  final decompositions = staruwUnicode['decomposition'] as Map;
  final combining = staruwUnicode['combining'] as Map;
  final compositions = staruwUnicode['composition'] as Map;
  int ccc(int cp) => combining['$cp'] as int? ?? 0;
  final runes = <int>[];
  void decompose(int cp) {
    final s = cp - 0xac00;
    if (s >= 0 && s < 11172) {
      runes.add(0x1100 + s ~/ 588);
      runes.add(0x1161 + (s % 588) ~/ 28);
      if (s % 28 != 0) runes.add(0x11a7 + s % 28);
    } else if (decompositions.containsKey('$cp')) {
      for (final part in decompositions['$cp'] as List) {
        decompose(part as int);
      }
    } else {
      runes.add(cp);
    }
  }

  for (final cp in text.runes) {
    decompose(cp);
  }
  for (var i = 1; i < runes.length; i++) {
    final cls = ccc(runes[i]);
    if (cls == 0) continue;
    var j = i;
    while (j > 0 && ccc(runes[j - 1]) > cls) {
      final cp = runes[j];
      runes[j] = runes[j - 1];
      runes[j - 1] = cp;
      j--;
    }
  }
  if (runes.isEmpty) return '';
  final out = <int>[runes.first];
  var starter = 0, lastClass = 0;
  for (final cp in runes.skip(1)) {
    final a = out[starter], cls = ccc(cp);
    int? composed;
    if (a >= 0x1100 && a < 0x1113 && cp >= 0x1161 && cp < 0x1176) {
      composed = 0xac00 + ((a - 0x1100) * 21 + cp - 0x1161) * 28;
    } else if (a >= 0xac00 &&
        a < 0xd7a4 &&
        (a - 0xac00) % 28 == 0 &&
        cp > 0x11a7 &&
        cp < 0x11c3) {
      composed = a + cp - 0x11a7;
    } else {
      composed = compositions['$a,$cp'] as int?;
    }
    if (composed != null && (lastClass < cls || lastClass == 0)) {
      out[starter] = composed;
    } else {
      if (cls == 0) starter = out.length;
      out.add(cp);
      lastClass = cls;
    }
  }
  return String.fromCharCodes(out);
}

String staruwCaseFold(String text) => String.fromCharCodes([
  for (final cp in text.runes)
    ...((staruwUnicode['folding']['$cp'] as List?)?.cast<int>() ?? [cp]),
]);

bool staruwSafePath(String path) =>
    path.isNotEmpty &&
    path.runes.length <= 512 &&
    staruwNfc(path) == path &&
    !RegExp(r'[\\\x00-\x1f\x7f:<>"|?*\uFEFF]').hasMatch(path) &&
    path
        .split('/')
        .every(
          (part) =>
              part.isNotEmpty &&
              part != '.' &&
              part != '..' &&
              !part.endsWith('.') &&
              !part.endsWith(' ') &&
              !RegExp(
                r'^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?$',
                caseSensitive: false,
              ).hasMatch(part),
        );

bool staruwEqual(dynamic a, dynamic b) {
  if (a is Map && b is Map) {
    return a.length == b.length &&
        a.keys.every((k) => b.containsKey(k) && staruwEqual(a[k], b[k]));
  }
  if (a is List && b is List) {
    return a.length == b.length &&
        List.generate(a.length, (i) => i).every((i) => staruwEqual(a[i], b[i]));
  }
  return a == b;
}

/// A deliberately local JSON Schema evaluator. Only generated, trusted schemas
/// are evaluated; package data cannot supply schemas or external references.
class StaruwSchema {
  final Map root;
  int _work = 0;
  StaruwSchema(String name) : root = staruwSchemas[name] as Map;
  void validate(dynamic value, {String? definition, String path = ''}) =>
      _check(
        definition == null ? root : root[r'$defs'][definition],
        value,
        path,
        0,
      );
  bool _matches(dynamic schema, dynamic value, String path, int depth) {
    try {
      _check(schema, value, path, depth);
      return true;
    } on StaruwException catch (e) {
      if (e.code == 'E_LIMIT') rethrow;
      return false;
    }
  }

  void _check(dynamic schema, dynamic value, String path, int depth) {
    if (++_work > 2000000 || depth > 512) {
      throw const StaruwException('E_LIMIT', '配置验证预算超限');
    }
    if (schema == true) return;
    void need(bool condition) =>
        staruwNeed(condition, 'E_SCHEMA', '配置不符合 StarUW 1.0 结构', path);
    need(schema != false);
    final s = schema as Map;
    if (s.containsKey(r'$ref')) {
      _check(
        staruwPointer(root, (s[r'$ref'] as String).substring(1)),
        value,
        path,
        depth + 1,
      );
    }
    final type = s['type'];
    if (type != null) {
      bool matches(dynamic t) => switch (t) {
        'object' => value is Map,
        'array' => value is List,
        'string' => value is String,
        'boolean' => value is bool,
        'number' => value is num && value.isFinite,
        'integer' =>
          value is num && value.isFinite && value == value.roundToDouble(),
        'null' => value == null,
        _ => false,
      };
      need(type is List ? type.any(matches) : matches(type));
    }
    if (s.containsKey('const')) need(staruwEqual(value, s['const']));
    if (s.containsKey('enum')) {
      need((s['enum'] as List).any((x) => staruwEqual(value, x)));
    }
    if (value is Map) {
      for (final k in s['required'] as List? ?? []) {
        need(value.containsKey(k));
      }
      if (s.containsKey('maxProperties')) {
        need(value.length <= s['maxProperties']);
      }
      final props = s['properties'] as Map? ?? {};
      for (final e in value.entries) {
        if (s.containsKey('propertyNames')) {
          _check(s['propertyNames'], e.key, path, depth + 1);
        }
        final child = props.containsKey(e.key)
            ? props[e.key]
            : s['additionalProperties'] ?? true;
        _check(
          child,
          e.value,
          '$path/${staruwPointerToken(e.key as String)}',
          depth + 1,
        );
      }
    }
    if (value is List) {
      if (s.containsKey('minItems')) need(value.length >= s['minItems']);
      if (s.containsKey('maxItems')) need(value.length <= s['maxItems']);
      if (s['uniqueItems'] == true) {
        for (var i = 0; i < value.length; i++) {
          for (var j = 0; j < i; j++) {
            need(!staruwEqual(value[i], value[j]));
          }
        }
      }
      for (var i = 0; i < value.length; i++) {
        if (s.containsKey('items')) {
          _check(s['items'], value[i], '$path/$i', depth + 1);
        }
      }
      if (s.containsKey('contains')) {
        need(value.any((x) => _matches(s['contains'], x, path, depth + 1)));
      }
    }
    if (value is String) {
      if (s.containsKey('minLength')) {
        need(value.runes.length >= s['minLength']);
      }
      if (s.containsKey('maxLength')) {
        need(value.runes.length <= s['maxLength']);
      }
      if (s.containsKey('pattern')) {
        need(RegExp(s['pattern'] as String).hasMatch(value));
      }
    }
    if (value is num) {
      need(value.isFinite);
      if (s.containsKey('minimum')) need(value >= s['minimum']);
      if (s.containsKey('maximum')) need(value <= s['maximum']);
      if (s.containsKey('exclusiveMinimum')) {
        need(value > s['exclusiveMinimum']);
      }
    }
    for (final sub in s['allOf'] as List? ?? []) {
      _check(sub, value, path, depth + 1);
    }
    if (s.containsKey('oneOf')) {
      need(
        (s['oneOf'] as List)
                .where((sub) => _matches(sub, value, path, depth + 1))
                .length ==
            1,
      );
    }
    if (s.containsKey('anyOf')) {
      need(
        (s['anyOf'] as List).any(
          (sub) => _matches(sub, value, path, depth + 1),
        ),
      );
    }
    if (s.containsKey('not')) need(!_matches(s['not'], value, path, depth + 1));
    if (s.containsKey('if')) {
      final branch = _matches(s['if'], value, path, depth + 1)
          ? 'then'
          : 'else';
      if (s.containsKey(branch)) _check(s[branch], value, path, depth + 1);
    }
  }
}
