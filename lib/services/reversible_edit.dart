import 'package:watermark_engine/watermark_engine.dart';
import 'watermark_style.dart';

/// Recovered metadata may refer to a package from another installation. Resolve
/// resources from this installation's catalog, never from embedded filesystem paths.
EditDocument? restoreKnownEdit(
  Map<String, dynamic>? saved,
  List<StylePreset> catalog,
) {
  if (saved == null) return null;
  try {
    final document = EditDocument.fromJson(saved);
    if (document.packageId.isEmpty) {
      return EditDocument.fromJson({
        ...document.toJson(),
        'xiaomiConfig': null,
        'resourceBindings': const {},
      });
    }
    final template = catalog
        .where((s) => s.document.packageId == document.packageId)
        .firstOrNull;
    if (template == null) return null;
    return EditDocument.fromJson({
      ...document.toJson(),
      'xiaomiConfig': template.document.xiaomiConfig,
      'resourceBindings': template.document.resourceBindings,
    });
  } on FormatException {
    return null;
  } on TypeError {
    return null;
  }
}
