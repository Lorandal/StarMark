import 'package:flutter/services.dart';
import 'package:watermark_engine/watermark_engine.dart';
import 'dart:convert';

Future<List<Map<String, dynamic>>> loadPresetModels() async =>
    (jsonDecode(
              await rootBundle.loadString('assets/presets/model_catalog.json'),
            )
            as List)
        .map((row) => Map<String, dynamic>.from(row as Map))
        .toList();

/// Carry preset text with the document so recipients need no matching catalog.
/// A package-supplied full model SVG remains the package's own binding.
EditDocument withPresetModelTitle(
  EditDocument doc,
  List<Map<String, dynamic>> catalog,
) {
  if (doc.modelId.isEmpty ||
      doc.modelOverride.isNotEmpty ||
      (doc.resourceBindings['models'] as Map? ?? {}).containsKey(doc.modelId)) {
    return doc;
  }
  final selected = catalog.where((row) => row['id'] == doc.modelId).firstOrNull;
  return selected == null
      ? doc
      : doc.copyWith(modelOverride: selected['title'] as String);
}
