import 'dart:convert';
import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';
import 'watermark_package.dart';
import 'watermark_style.dart';

/// The asset index maps original package-relative names onto shared asset bytes.
/// Factory packages never need to be installed into the user's repository.
class FactoryWatermarkCatalog {
  final AssetBundle _bundle;
  final List<Map<String, dynamic>> _styles;
  final Map<String, String> _categoryTitles;
  final Map<String, Map<String, String>> _resourceAssets;
  final Map<String, Future<Uint8List>> _assetJobs = {};

  FactoryWatermarkCatalog._(this._bundle, this._styles, this._categoryTitles)
    : _resourceAssets = {
        for (final style in _styles) style['id'] as String: _resources(style),
      };

  /// Pure index lookup for native UI images; never constructs a path from input.
  /// The controller separately requires the image's package to be validated.
  String? resourceAsset(String id, String name) {
    if (!safeWatermarkPath(name)) return null;
    return _resourceAssets[id]?[name];
  }

  static bool isFactoryId(String id) => id.startsWith('factory-');
  static bool isRetiredId(String id) => id.startsWith('factory-disney-');

  static Future<FactoryWatermarkCatalog> load({AssetBundle? bundle}) async {
    final source = bundle ?? rootBundle;
    final indexText = await source.loadString('assets/watermarks/index.json');
    if (indexText.length > 4 * 1024 * 1024) {
      throw const FormatException('内置水印索引过大');
    }
    final index = jsonDecode(indexText) as Map;
    if (index['schemaVersion'] != 1) {
      throw const FormatException('不支持的内置水印索引版本');
    }
    final categories = <String, String>{};
    for (final row in index['categories'] as List) {
      final category = Map<String, dynamic>.from(row as Map);
      categories[category['id'] as String] = category['title'] as String;
    }
    final styles = <Map<String, dynamic>>[];
    if ((index['styles'] as List).length > 512) {
      throw const FormatException('内置水印数量超限');
    }
    final ids = <String>{};
    for (final row in index['styles'] as List) {
      final style = Map<String, dynamic>.from(row as Map);
      final id = style['id'] as String;
      if (!RegExp(r'^factory-[a-z0-9_]+-[0-9]+$').hasMatch(id) ||
          !ids.add(id) ||
          !categories.containsKey(style['category'])) {
        throw const FormatException('无效的内置水印索引');
      }
      _resources(style);
      styles.add(style);
    }
    return FactoryWatermarkCatalog._(source, styles, categories);
  }

  static Map<String, String> _resources(Map<String, dynamic> style) {
    final resources = style['resources'], hashes = style['sha256'];
    if (resources is! Map ||
        hashes is! Map ||
        resources.isEmpty ||
        resources.length > 512 ||
        !resources.containsKey('config.json') ||
        !resources.containsKey('preview.webp') ||
        style['preview'] != resources['preview.webp'] ||
        resources.length != hashes.length) {
      throw const FormatException('无效的内置水印资源索引');
    }
    final result = <String, String>{};
    final names = <String>{};
    for (final entry in resources.entries) {
      final name = entry.key, path = entry.value, hash = hashes[entry.key];
      if (name is! String ||
          !safeWatermarkPath(name) ||
          !names.add(name.toLowerCase()) ||
          path is! String ||
          !safeWatermarkPath(path) ||
          !path.startsWith('assets/') ||
          hash is! String ||
          !RegExp(r'^[a-f0-9]{64}$').hasMatch(hash)) {
        throw const FormatException('无效的内置水印资源路径或指纹');
      }
      result[name] = path;
    }
    return result;
  }

  Future<Uint8List> _asset(String path) => _assetJobs.putIfAbsent(
    path,
    () async {
      if (!safeWatermarkPath(path) || !path.startsWith('assets/')) {
        throw const FormatException('无效的内置水印资源路径');
      }
      try {
        final data = await _bundle.load(path);
        return data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes);
      } catch (_) {
        _assetJobs.remove(path);
        rethrow;
      }
    },
  );

  Future<Map<String, Uint8List>> files(String id) async {
    final rows = _styles.where((row) => row['id'] == id);
    if (rows.isEmpty) throw const FormatException('内置水印不存在');
    final row = rows.single;
    final resources = _resources(row);
    final hashes = Map<String, dynamic>.from(row['sha256'] as Map);
    final result = <String, Uint8List>{};
    for (final entry in resources.entries) {
      final bytes = await _asset(entry.value);
      if (sha256.convert(bytes).toString() != hashes[entry.key]) {
        throw const FormatException('内置水印资源校验失败');
      }
      // Callers can edit a package without mutating a cached shared asset.
      result[entry.key] = Uint8List.fromList(bytes);
    }
    WatermarkPackageCodec.validateFiles(result);
    return result;
  }

  Future<List<StylePreset>> loadStyles() async {
    final styles = <StylePreset>[];
    for (final row in _styles) {
      final id = row['id'] as String;
      final resources = _resources(row);
      final config = await _asset(resources['config.json']!);
      if (config.length > 4 * 1024 * 1024 ||
          sha256.convert(config).toString() !=
              (row['sha256'] as Map)['config.json']) {
        throw const FormatException('内置水印配置过大或校验失败');
      }
      final document = WatermarkPackageCodec.documentFromConfig(
        config,
        resourceNames: resources.keys,
      );
      final category = row['category'] as String;
      styles.add(
        StylePreset(
          id,
          row['title'] as String,
          resources['preview.webp']!,
          document.copyWith(packageId: id),
          factory: true,
          category: category,
          categoryTitle: _categoryTitles[category]!,
        ),
      );
    }
    return styles;
  }
}
