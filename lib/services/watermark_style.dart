import 'photo_asset.dart';
import 'package:watermark_engine/watermark_engine.dart';

class StylePreset {
  final String id, title, image;
  final bool factory;
  final String category, categoryTitle;
  final EditDocument document;
  const StylePreset(
    this.id,
    this.title,
    this.image,
    this.document, {
    this.factory = false,
    this.category = '',
    this.categoryTitle = '',
  });
  Map<String, dynamic> toJson() => {
    'id': id,
    'title': title,
    'image': image,
    'factory': factory,
    'category': category,
    'categoryTitle': categoryTitle,
    'document': document.toJson(),
  };
  factory StylePreset.fromJson(Map<String, dynamic> j) => StylePreset(
    j['id'] as String,
    j['title'] as String,
    migrateBundledSample(j['image'] as String? ?? PhotoAsset.demo.path),
    EditDocument.fromJson(Map<String, dynamic>.from(j['document'] as Map)),
    factory: j['factory'] == true,
    category: j['category'] as String? ?? '',
    categoryTitle: j['categoryTitle'] as String? ?? '',
  );
}
