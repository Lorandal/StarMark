import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:crypto/crypto.dart';
import 'watermark_package.dart';

/// Private immutable originals. No external path or provider grant is retained.
class RegionAssets {
  RegionAssets(this.base);
  final Directory base;
  static const maxBytes = 32 * 1024 * 1024;
  static const maxRasterEdge = 16384;
  static const maxRasterPixels = 16000000;
  static bool validId(String id) =>
      RegExp(r'^[a-f0-9]{64}\.(png|jpg|svg)$').hasMatch(id);
  File file(String id) {
    if (!validId(id)) throw const FormatException('Invalid region asset ID');
    return File('${base.path}/region-assets/$id');
  }

  static Future<String> validate(Uint8List bytes) async {
    if (bytes.isEmpty || bytes.length > maxBytes) {
      throw const FormatException('Image exceeds 32 MiB or is empty');
    }
    String extension;
    if (bytes.length >= 8 &&
        bytes.take(8).join(',') == '137,80,78,71,13,10,26,10') {
      extension = 'png';
    } else if (bytes.length >= 3 &&
        bytes[0] == 255 &&
        bytes[1] == 216 &&
        bytes[2] == 255) {
      extension = 'jpg';
    } else {
      final svg = utf8.decode(bytes);
      WatermarkPackageCodec.validateSvg(svg);
      return 'svg';
    }
    final buffer = await ui.ImmutableBuffer.fromUint8List(bytes);
    ui.ImageDescriptor? descriptor;
    try {
      // Header inspection precedes pixel allocation.
      descriptor = await ui.ImageDescriptor.encoded(buffer);
      if (descriptor.width <= 0 ||
          descriptor.height <= 0 ||
          descriptor.width > maxRasterEdge ||
          descriptor.height > maxRasterEdge ||
          descriptor.width * descriptor.height > maxRasterPixels) {
        throw const FormatException('Region image pixel budget exceeded');
      }
    } finally {
      descriptor?.dispose();
      buffer.dispose();
    }
    return extension;
  }

  Future<String> importBytes(Uint8List bytes) async {
    final extension = await validate(bytes);
    final id = '${sha256.convert(bytes)}.$extension';
    final target = file(id);
    await target.parent.create(recursive: true);
    if (!await target.exists()) {
      final pending = File(
        '${target.path}.${DateTime.now().microsecondsSinceEpoch}.tmp',
      );
      try {
        await pending.writeAsBytes(bytes, flush: true);
        await pending.rename(target.path);
      } finally {
        if (await pending.exists()) await pending.delete();
      }
    }
    return id;
  }

  Future<Uint8List> bytes(String id) async {
    final owned = file(id);
    if (await owned.length() > maxBytes) {
      throw const FormatException('Region image too large');
    }
    final bytes = await owned.readAsBytes();
    await verify(id, bytes);
    return bytes;
  }

  static Future<void> verify(String id, Uint8List bytes) async {
    if (!validId(id) ||
        '${sha256.convert(bytes)}.${await validate(bytes)}' != id) {
      throw const FormatException('Region image fingerprint mismatch');
    }
  }
}
