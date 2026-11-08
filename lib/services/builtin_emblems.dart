import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';

const builtinEmblems = [
  {
    'id': 'MarkC',
    'title': 'MarkC · 圆形',
    'asset':
        '03cd4adda5d4c58554eea15a5ff041350978748be8b2dfb9cc4a848f11f38bcb.svg',
  },
  {
    'id': 'MarkS',
    'title': 'MarkS · 方形',
    'asset':
        '7e05191e2788607c932a0bfc59e740fa3e6196ba14cdbf0aa2b210decc11c2e9.svg',
  },
];

Future<Uint8List> builtinEmblemBytes(String id) async {
  final item = builtinEmblems.where((row) => row['id'] == id).firstOrNull;
  if (item == null) throw const FormatException('未知内置图标');
  final data = await rootBundle.load('assets/marks/leica/$id.svg');
  final bytes = data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes);
  if ('${sha256.convert(bytes)}.svg' != item['asset']) {
    throw const FormatException('内置图标校验失败');
  }
  return bytes;
}
