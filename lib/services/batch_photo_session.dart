import 'dart:convert';
import 'dart:io';

import 'package:watermark_engine/watermark_engine.dart';
import 'photo_asset.dart';

/// One resumable task, separate from the ordinary photo drafts.
class BatchPhotoSession {
  final photos = <PhotoAsset>[];
  final selected = <String>{};
  final histories = <String, EditHistory>{};
  final results = <String, String>{};
  final pendingContent = <String, Map<String, dynamic>>{};
  String? lastPhoto;
  bool expanded = true;
  EditDocument? defaultDesign;
  Future<void> _writes = Future.value();

  void removeSelected() {
    photos.removeWhere((p) => selected.contains(p.id));
    for (final id in selected) {
      histories.remove(id);
      pendingContent.remove(id);
      results.remove(id);
    }
    if (selected.contains(lastPhoto)) lastPhoto = photos.firstOrNull?.id;
    selected.clear();
    if (photos.isEmpty) {
      defaultDesign = null;
      expanded = true;
    }
  }

  Map<String, dynamic> toJson() => {
    'version': 1,
    'items': [
      for (final p in photos)
        {
          'photo': p.toJson(),
          'selected': selected.contains(p.id),
          'history': ?histories[p.id]?.toJson(),
          'content': ?pendingContent[p.id],
          'result': ?results[p.id],
        },
    ],
    'lastPhoto': lastPhoto,
    'expanded': expanded,
    'defaultDesign': ?defaultDesign?.toJson(),
  };

  Future<void> load(Directory directory) async {
    final file = File('${directory.path}/batch-session.json');
    Object? failure;
    for (final candidate in [file, File('${file.path}.bak')]) {
      if (!await candidate.exists()) continue;
      try {
        final data = jsonDecode(await candidate.readAsString()) as Map;
        if (data['version'] != 1) throw const FormatException('未知批量任务版本');
        final loaded = BatchPhotoSession();
        for (final item in data['items'] as List) {
          final photo = PhotoAsset.fromJson(
            item['photo'] as Map<String, dynamic>,
          );
          loaded.photos.add(photo);
          if (item['selected'] == true) loaded.selected.add(photo.id);
          if (item['history'] != null) {
            loaded.histories[photo.id] = EditHistory.fromJson(item['history']);
          }
          if (item['content'] != null) {
            loaded.pendingContent[photo.id] =
                item['content'] as Map<String, dynamic>;
          }
          if (item['result'] case final String status) {
            loaded.results[photo.id] = status == 'saving' ? '待核对' : status;
          }
        }
        final design = data['defaultDesign'] == null
            ? null
            : EditDocument.fromJson(data['defaultDesign']);
        photos.addAll(loaded.photos);
        selected.addAll(loaded.selected);
        histories.addAll(loaded.histories);
        pendingContent.addAll(loaded.pendingContent);
        results.addAll(loaded.results);
        expanded = data['expanded'] != false;
        lastPhoto = data['lastPhoto'] as String?;
        defaultDesign = design;
        return;
      } catch (error) {
        failure = error;
      }
    }
    if (failure != null) throw const FormatException('无法读取已保存的批量任务');
  }

  Future<void> save(Directory? directory) {
    if (directory == null) return Future.value();
    final job = _writes.then((_) async {
      final file = File('${directory.path}/batch-session.json');
      final temp = File('${file.path}.tmp');
      await temp.writeAsString(jsonEncode(toJson()), flush: true);
      if (await file.exists()) await file.rename('${file.path}.bak');
      await temp.rename(file.path);
    });
    _writes = job.catchError((Object _) {});
    return job;
  }
}

/// Design travels between photos. Manually entered camera data belongs to its original photo.
EditDocument batchDesignForPhoto(
  EditDocument design, [
  EditDocument target = const EditDocument(),
]) {
  const photoOptions = {'exifOverrides', 'locationAddress', 'filterName'};
  return design.copyWith(
    modelOverride: target.modelOverride,
    parametersOverride: target.parametersOverride,
    xiaomiOptions: {
      for (final e in design.xiaomiOptions.entries)
        if (!photoOptions.contains(e.key)) e.key: e.value,
      for (final key in photoOptions)
        if (target.xiaomiOptions.containsKey(key))
          key: target.xiaomiOptions[key],
    },
    staruwPhotoValues:
        design.isStaruw &&
            design.packageId == target.packageId &&
            design.staruwReport['payloadDigest'] ==
                target.staruwReport['payloadDigest']
        ? target.staruwPhotoValues
        : {},
  );
}
