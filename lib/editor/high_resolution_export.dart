import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

const previewMaxEdge = 2048;

bool needsTiledExport(
  int width,
  int height,
  int sourceWidth,
  int sourceHeight,
) =>
    width * height > 32000000 ||
    sourceWidth * sourceHeight > 32000000 ||
    width > 8192 ||
    height > 8192 ||
    sourceWidth > 8192 ||
    sourceHeight > 8192;

/// Owns a temporary manifest and bounded raster tiles, never the input pictures.
/// PNG preserves the sRGB decoration's alpha for Android's premultiplied decode.
class TiledDecorationExport {
  final Directory _directory;
  final File manifest;
  TiledDecorationExport._(this._directory, this.manifest);

  static Future<TiledDecorationExport> create({
    required Directory parent,
    required int width,
    required int height,
    required ui.Picture decoration,
    ui.Picture? background,
    void Function(int done, int total)? onProgress,
  }) async {
    if (width <= 0 ||
        height <= 0 ||
        width > 32768 ||
        height > 32768 ||
        width * height > 520000000) {
      throw const FormatException('无效导出画布尺寸');
    }
    final directory = await parent.createTemp('export-tiles-');
    final result = TiledDecorationExport._(
      directory,
      File('${directory.path}/manifest.json'),
    );
    try {
      const tileWidth = 2048, tileHeight = 128;
      final count =
          ((width + tileWidth - 1) ~/ tileWidth) *
          ((height + tileHeight - 1) ~/ tileHeight);
      final total = count * (background == null ? 1 : 2);
      var done = 0;
      final transparentTiles = <(int, int), File>{};
      Future<List<Map<String, Object>>> tiles(
        ui.Picture picture,
        String name,
      ) async {
        final rows = <Map<String, Object>>[];
        for (var y = 0; y < height; y += tileHeight) {
          for (var x = 0; x < width; x += tileWidth) {
            final w = math.min(tileWidth, width - x);
            final h = math.min(tileHeight, height - y);
            final file = File('${directory.path}/$name-$x-$y.png');
            final recorder = ui.PictureRecorder();
            final canvas = ui.Canvas(recorder)
              ..clipRect(ui.Rect.fromLTWH(0, 0, w.toDouble(), h.toDouble()))
              ..translate(-x.toDouble(), -y.toDouble());
            canvas.drawPicture(picture);
            final encoded = await _writePicture(
              recorder.endRecording(),
              file,
              w,
              h,
              transparentTiles: transparentTiles,
            );
            rows.add({
              'x': x,
              'y': y,
              'width': w,
              'height': h,
              'path': encoded.path,
            });
            onProgress?.call(++done, total);
            // Allow input, progress and cancellation to run between raster jobs.
            await Future<void>.delayed(Duration.zero);
          }
        }
        return rows;
      }

      Future<String> preview(ui.Picture picture, String name) async {
        final factor = math.min(1.0, previewMaxEdge / math.max(width, height));
        final w = math.max(1, (width * factor).round());
        final h = math.max(1, (height * factor).round());
        final recorder = ui.PictureRecorder();
        ui.Canvas(recorder)
          ..scale(w / width, h / height)
          ..drawPicture(picture);
        final file = File('${directory.path}/$name-preview.png');
        await _writePicture(recorder.endRecording(), file, w, h);
        return file.path;
      }

      final decorationTiles = await tiles(decoration, 'decoration');
      final backgroundTiles = background == null
          ? null
          : await tiles(background, 'background');
      await result.manifest.writeAsString(
        jsonEncode({
          'width': width,
          'height': height,
          'tileWidth': tileWidth,
          'tileHeight': tileHeight,
          'tiles': decorationTiles,
          'backgroundTiles': ?backgroundTiles,
          'previewPath': await preview(decoration, 'decoration'),
          if (background != null)
            'backgroundPreviewPath': await preview(background, 'background'),
        }),
      );
      return result;
    } catch (_) {
      await result.dispose();
      rethrow;
    }
  }

  static Future<File> _writePicture(
    ui.Picture picture,
    File file,
    int width,
    int height, {
    Map<(int, int), File>? transparentTiles,
  }) async {
    ui.Image? image;
    try {
      image = await picture.toImage(width, height);
      // Most full-resolution tiles lie inside the cleared photo rectangle.
      // Avoid hundreds of identical PNG encodes and files while retaining the
      // complete grid and the exact dimensions required by native validation.
      final transparent =
          transparentTiles != null && await _isTransparent(image);
      if (transparent) {
        final existing = transparentTiles[(width, height)];
        if (existing != null) return existing;
      }
      final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
      if (bytes == null) throw StateError('导出像素读取失败');
      await file.writeAsBytes(
        bytes.buffer.asUint8List(bytes.offsetInBytes, bytes.lengthInBytes),
      );
      if (transparent) transparentTiles[(width, height)] = file;
      return file;
    } finally {
      image?.dispose();
      picture.dispose();
    }
  }

  static Future<bool> _isTransparent(ui.Image image) async {
    final bytes = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
    if (bytes == null) throw StateError('导出像素读取失败');
    final pixels = bytes.buffer.asUint32List(
      bytes.offsetInBytes,
      bytes.lengthInBytes ~/ 4,
    );
    return pixels.every((pixel) => pixel == 0);
  }

  Future<void> dispose() async {
    if (await _directory.exists()) await _directory.delete(recursive: true);
  }
}
