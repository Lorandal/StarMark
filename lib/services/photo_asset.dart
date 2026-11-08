import 'dart:convert';
import 'package:watermark_engine/watermark_engine.dart';

class PhotoAsset {
  final String id, path, preview;
  final String? originalName;
  final int width, height, orientation;
  final bool sample;
  final bool hdr, live, hdrUncertain;
  final Map<String, dynamic>? restoredDocument;
  final PhotoMetadata metadata;
  const PhotoAsset({
    required this.id,
    required this.path,
    required this.preview,
    required this.width,
    required this.height,
    this.originalName,
    this.orientation = 1,
    this.sample = false,
    this.hdr = false,
    this.live = false,
    this.hdrUncertain = false,
    this.restoredDocument,
    this.metadata = const PhotoMetadata(),
  });
  static const demo = PhotoAsset(
    id: 'demo-mountain',
    path: 'assets/samples/watermark_standard.webp',
    originalName: 'watermark_standard.webp',
    preview: 'assets/samples/watermark_standard.webp',
    width: 720,
    height: 720,
    sample: true,
    metadata: PhotoMetadata(
      make: 'Xiaomi',
      model: 'Xiaomi 15',
      iso: 400,
      aperture: 1.7,
      focal: 23,
      exposure: 1 / 60,
    ),
  );
  factory PhotoAsset.fromJson(Map<String, dynamic> j) => PhotoAsset(
    id: j['id'] as String,
    path: j['sample'] == true
        ? migrateBundledSample(j['path'] as String)
        : j['path'] as String,
    originalName:
        j['sample'] == true && retiredBundledSamples.contains(j['path'])
        ? PhotoAsset.demo.originalName
        : j['originalName'] as String?,
    preview: j['sample'] == true
        ? migrateBundledSample(j['preview'] as String)
        : j['preview'] as String,
    width: j['sample'] == true && retiredBundledSamples.contains(j['path'])
        ? 720
        : (j['width'] as num).toInt(),
    height: j['sample'] == true && retiredBundledSamples.contains(j['path'])
        ? 720
        : (j['height'] as num).toInt(),
    orientation: (j['orientation'] as num?)?.toInt() ?? 1,
    sample: j['sample'] == true,
    hdr: j['hdr'] == true,
    live: j['live'] == true,
    hdrUncertain: j['hdrUncertain'] == true,
    restoredDocument: _document(j['restoredDocument']),
    metadata: PhotoMetadata.fromJson(
      Map<String, dynamic>.from(j['metadata'] as Map? ?? {}),
    ),
  );
  Map<String, dynamic> toJson() => {
    'id': id,
    'path': path,
    'originalName': ?originalName,
    'preview': preview,
    'width': width,
    'height': height,
    'orientation': orientation,
    'sample': sample,
    'hdr': hdr,
    'live': live,
    'hdrUncertain': hdrUncertain,
    'restoredDocument': ?restoredDocument,
    'metadata': metadata.toJson(),
  };

  static Map<String, dynamic>? _document(dynamic value) {
    if (value is String) value = jsonDecode(value);
    return value is Map ? Map<String, dynamic>.from(value) : null;
  }
}

// Only exact retired bundle keys migrate; user files are never rewritten.
const retiredBundledSamples = {
  'assets/samples/mountain.jpg',
  'assets/samples/film.jpg',
  'assets/samples/sky.jpg',
};
String migrateBundledSample(String path) =>
    retiredBundledSamples.contains(path) ? PhotoAsset.demo.path : path;
