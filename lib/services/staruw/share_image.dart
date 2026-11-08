import 'dart:typed_data';
import 'dart:ui' as ui;
import 'contract.dart';

/// Imported signature artwork is shareable design content; its hidden camera
/// metadata is not. Decode only when metadata needs removing, after header
/// validation, so ordinary OEM/vector resources retain their exact bytes.
Future<Uint8List> staruwShareImage(Uint8List bytes) async {
  if (!staruwHasImageMetadata(bytes)) return bytes;
  final buffer = await ui.ImmutableBuffer.fromUint8List(bytes);
  ui.ImageDescriptor? descriptor;
  ui.Codec? codec;
  ui.Image? image;
  try {
    descriptor = await ui.ImageDescriptor.encoded(buffer);
    staruwNeed(
      descriptor.width <= 8192 &&
          descriptor.height <= 8192 &&
          descriptor.width * descriptor.height <= 16000000,
      'E_LIMIT',
      '待清理图片超过预算',
    );
    codec = await descriptor.instantiateCodec();
    image = (await codec.getNextFrame()).image;
    final encoded = (await image.toByteData(
      format: ui.ImageByteFormat.png,
    ))!.buffer.asUint8List();
    staruwNeed(encoded.length <= 32 * 1024 * 1024, 'E_LIMIT', '清理后的图片超过预算');
    return encoded;
  } finally {
    image?.dispose();
    codec?.dispose();
    descriptor?.dispose();
    buffer.dispose();
  }
}

bool staruwHasImageMetadata(Uint8List bytes) {
  final data = ByteData.sublistView(bytes);
  if (bytes.length < 12) return false;
  if (bytes[0] == 255 && bytes[1] == 216) {
    var at = 2;
    while (at + 4 <= bytes.length) {
      if (bytes[at++] != 255) break;
      while (at < bytes.length && bytes[at] == 255) {
        at++;
      }
      if (at >= bytes.length) break;
      final marker = bytes[at++];
      if (marker == 0xda || marker == 0xd9) break;
      if (marker == 1 || marker >= 0xd0 && marker <= 0xd7) continue;
      if (at + 2 > bytes.length) break;
      final length = data.getUint16(at);
      if (length < 2 || at + length > bytes.length) break;
      if (marker == 0xe1 || marker == 0xed || marker == 0xfe) return true;
      at += length;
    }
  } else if (bytes[0] == 137 &&
      String.fromCharCodes(bytes.sublist(1, 4)) == 'PNG') {
    var at = 8;
    while (at + 12 <= bytes.length) {
      final length = data.getUint32(at),
          tag = String.fromCharCodes(bytes.sublist(at + 4, at + 8));
      if (length > bytes.length - at - 12) break;
      if (['eXIf', 'tEXt', 'iTXt', 'zTXt'].contains(tag)) return true;
      at += length + 12;
    }
  } else if (String.fromCharCodes(bytes.sublist(0, 4)) == 'RIFF' &&
      String.fromCharCodes(bytes.sublist(8, 12)) == 'WEBP') {
    var at = 12;
    while (at + 8 <= bytes.length) {
      final tag = String.fromCharCodes(bytes.sublist(at, at + 4)),
          length = data.getUint32(at + 4, Endian.little);
      if (length > bytes.length - at - 8) break;
      if (tag == 'EXIF' || tag == 'XMP ') return true;
      at += 8 + length + (length & 1);
    }
  }
  return false;
}
