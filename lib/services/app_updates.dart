import 'dart:async';
import 'dart:convert';
import 'dart:io';

class InstalledAppVersion {
  const InstalledAppVersion({
    required this.versionName,
    required this.channel,
    required this.installedAbis,
    required this.runtimeAbi,
  });

  factory InstalledAppVersion.fromNative(Map<dynamic, dynamic> value) =>
      InstalledAppVersion(
        versionName: value['versionName'] is String
            ? value['versionName'] as String
            : '',
        channel: value['channel'] is String ? value['channel'] as String : '',
        installedAbis: value['installedAbis'] is List
            ? List<String>.unmodifiable(
                (value['installedAbis'] as List).whereType<String>(),
              )
            : const [],
        runtimeAbi: value['runtimeAbi'] is String
            ? value['runtimeAbi'] as String
            : '',
      );

  final String versionName;
  final String channel;
  final List<String> installedAbis;
  final String runtimeAbi;
}

class AvailableAppUpdate {
  const AvailableAppUpdate({
    required this.versionName,
    required this.downloadUrl,
    required this.releaseUrl,
    required this.abi,
    required this.source,
  });

  final String versionName;
  final Uri downloadUrl;
  final Uri releaseUrl;
  final String abi;
  final String source;
}

class UpdateCheckException implements Exception {
  const UpdateCheckException(this.code, this.userMessage);

  final String code;
  final String userMessage;

  @override
  String toString() => userMessage;
}

typedef UpdateReleaseReader = Future<UpdateReleaseResponse> Function(Uri uri);

class UpdateReleaseResponse {
  const UpdateReleaseResponse({
    required this.statusCode,
    required this.body,
    this.headers = const {},
  });

  final int statusCode;
  final String body;
  final Map<String, String> headers;
}

class AppUpdateService {
  AppUpdateService({
    UpdateReleaseReader? reader,
    DateTime Function()? now,
    Duration requestTimeout = const Duration(seconds: 12),
  }) : _reader = reader,
       _now = now ?? DateTime.now,
       _requestTimeout = requestTimeout {
    if (requestTimeout <= Duration.zero) {
      throw ArgumentError.value(requestTimeout, 'requestTimeout');
    }
  }

  static const _maxResponseBytes = 2 * 1024 * 1024;
  static const _maxPages = 10;
  static const _sourceTimeout = Duration(seconds: 30);
  static const _supportedAbis = ['arm64-v8a', 'armeabi-v7a', 'x86_64'];
  static final _github = Uri.parse(
    'https://api.github.com/repos/Lorandal/StarMark/releases?per_page=100',
  );
  static final _gitlab = Uri.parse(
    'https://gitlab.com/api/v4/projects/Lorandal%2FStarMark/releases?per_page=100',
  );

  final UpdateReleaseReader? _reader;
  final DateTime Function() _now;
  final Duration _requestTimeout;

  /// Only an explicit user action should invoke this method.
  Future<AvailableAppUpdate?> check(InstalledAppVersion installed) async {
    final current = _ReleaseVersion.parse(installed.versionName);
    if (current == null || current.channel != installed.channel) {
      throw const UpdateCheckException(
        'installed_version',
        '无法识别当前应用版本，请稍后重试。',
      );
    }

    final client = _reader == null ? HttpClient() : null;
    client?.connectionTimeout = const Duration(seconds: 8);
    final reader = _reader ?? (uri) => _readHttp(client!, uri);
    try {
      final now = _now().toUtc();
      final sources = await Future.wait([
        _readSource(reader, _github, 'GitHub', now),
        _readSource(reader, _gitlab, 'GitLab', now),
      ]);
      if (sources.every((source) => source == null)) {
        throw const UpdateCheckException(
          'sources_unavailable',
          '暂时无法检查更新，请检查网络后重试。',
        );
      }
      final candidates = [
        for (final source in sources)
          if (source != null)
            for (final release in source)
              if (installed.channel != 'Release' ||
                  release.version.channel == 'Release')
                release,
      ];
      if (candidates.isEmpty) return null;

      final newest = candidates.reduce(
        (a, b) => a.version.compareTo(b.version) >= 0 ? a : b,
      );
      if (newest.version.compareTo(current) <= 0) return null;

      // Package availability must not lower the version we report as newest.
      for (final abi in _preferredAbis(installed)) {
        for (final release in candidates) {
          if (release.version.compareTo(newest.version) != 0) continue;
          final asset = release.assets[abi];
          if (asset == null) continue;
          return AvailableAppUpdate(
            versionName: release.version.tag,
            downloadUrl: asset,
            releaseUrl: Uri.parse(
              release.source == 'GitHub'
                  ? 'https://github.com/Lorandal/StarMark/releases/tag/${release.version.tag}'
                  : 'https://gitlab.com/Lorandal/StarMark/-/releases/${release.version.tag}',
            ),
            abi: abi,
            source: release.source,
          );
        }
      }
      throw const UpdateCheckException(
        'no_compatible_package',
        '发现新版本，但暂未提供适合当前安装版本的安装包，请稍后重试。',
      );
    } finally {
      client?.close(force: true);
    }
  }

  List<String> _preferredAbis(InstalledAppVersion installed) {
    final abis = installed.installedAbis.toSet();
    if (abis.length == 1) {
      return [
        if (_supportedAbis.contains(abis.single)) abis.single,
        'universal',
      ];
    }
    if (abis.length > 1) {
      return [
        'universal',
        if (abis.contains(installed.runtimeAbi) &&
            _supportedAbis.contains(installed.runtimeAbi))
          installed.runtimeAbi,
      ];
    }
    return [
      if (_supportedAbis.contains(installed.runtimeAbi)) installed.runtimeAbi,
      'universal',
    ];
  }

  Future<List<_PublishedRelease>?> _readSource(
    UpdateReleaseReader reader,
    Uri endpoint,
    String source,
    DateTime now,
  ) async {
    final elapsed = Stopwatch()..start();
    try {
      final releases = <_PublishedRelease>[];
      for (var page = 1; page <= _maxPages; page += 1) {
        final remaining = _sourceTimeout - elapsed.elapsed;
        if (remaining <= Duration.zero) throw const FormatException();
        final uri = page == 1
            ? endpoint
            : endpoint.replace(
                queryParameters: {'per_page': '100', 'page': '$page'},
              );
        final response = await reader(
          uri,
        ).timeout(remaining < _requestTimeout ? remaining : _requestTimeout);
        if (response.statusCode != HttpStatus.ok ||
            utf8.encode(response.body).length > _maxResponseBytes) {
          throw const FormatException();
        }
        final decoded = jsonDecode(response.body);
        if (decoded is! List) throw const FormatException();
        for (final item in decoded) {
          if (item is! Map) throw const FormatException();
          final release = _parseRelease(item, source, now);
          if (release != null) releases.add(release);
        }
        if (!_hasNextPage(response.headers, endpoint, page, decoded.length)) {
          return releases;
        }
      }
      // A bounded but incomplete catalog cannot establish the latest version.
      return null;
    } catch (_) {
      return null;
    } finally {
      elapsed.stop();
    }
  }

  bool _hasNextPage(
    Map<String, String> rawHeaders,
    Uri endpoint,
    int page,
    int count,
  ) {
    final headers = {
      for (final entry in rawHeaders.entries)
        entry.key.toLowerCase(): entry.value,
    };
    final link = headers['link'];
    var linkHasNext = false;
    if (link != null && link.isNotEmpty) {
      for (final part in link.split(',')) {
        if (!RegExp(r'\brel\s*=\s*"?next"?(?:\s|;|$)').hasMatch(part.trim())) {
          continue;
        }
        final match = RegExp(r'<([^>]+)>').firstMatch(part);
        final next = match == null ? null : Uri.tryParse(match.group(1)!);
        if (next == null ||
            next.scheme != 'https' ||
            next.host != endpoint.host ||
            next.port != 443 ||
            next.userInfo.isNotEmpty ||
            next.hasFragment ||
            !_equalSegments(next.pathSegments, endpoint.pathSegments) ||
            next.queryParameters.length != 2 ||
            next.queryParametersAll.values.any(
              (values) => values.length != 1,
            ) ||
            next.queryParameters['per_page'] != '100' ||
            next.queryParameters['page'] != '${page + 1}') {
          throw const FormatException();
        }
        linkHasNext = true;
      }
    }
    final nextPage = headers['x-next-page'];
    if (nextPage != null) {
      if (nextPage.isEmpty) {
        if (linkHasNext) throw const FormatException();
        return false;
      }
      if (int.tryParse(nextPage) != page + 1) throw const FormatException();
      return true;
    }
    if (link != null && link.isNotEmpty) return linkHasNext;
    return count >= 100;
  }

  _PublishedRelease? _parseRelease(Map item, String source, DateTime now) {
    final rawTag = item['tag_name'];
    final version = rawTag is String ? _ReleaseVersion.parse(rawTag) : null;
    if (version == null || version.channel == 'Dev') return null;
    final github = source == 'GitHub';
    if (github) {
      if (item['draft'] == true) return null;
      if (item['draft'] != false) throw const FormatException();
      if (version.channel == 'Release' && item['prerelease'] == true) {
        return null;
      }
    } else if (item['upcoming_release'] == true) {
      return null;
    }
    final rawDate = item[github ? 'published_at' : 'released_at'];
    final published = rawDate is String ? DateTime.tryParse(rawDate) : null;
    if (published == null) throw const FormatException();
    if (published.toUtc().isAfter(now)) return null;

    final rawAssets = item['assets'];
    final links = github
        ? rawAssets
        : (rawAssets is Map ? rawAssets['links'] : null);
    final assets = <String, Uri>{};
    if (links is List) {
      for (final raw in links) {
        if (raw is! Map || raw['name'] is! String) continue;
        if (github && raw['state'] != null && raw['state'] != 'uploaded') {
          continue;
        }
        final name = raw['name'] as String;
        String? abi;
        for (final candidate in [..._supportedAbis, 'universal']) {
          if (name == 'StarMark-${version.tag}-$candidate.apk') abi = candidate;
        }
        if (name == 'StarMark-${version.tag}.apk') abi = 'universal';
        if (abi == null) continue;
        final urls = github
            ? [raw['browser_download_url']]
            : [raw['direct_asset_url'], raw['url']];
        for (final rawUrl in urls) {
          if (rawUrl is! String) continue;
          final uri = _trustedDownload(rawUrl, version, name);
          if (uri != null) {
            assets.putIfAbsent(abi, () => uri);
            break;
          }
        }
      }
    }
    return _PublishedRelease(version, source, assets);
  }

  Uri? _trustedDownload(String raw, _ReleaseVersion version, String name) {
    final uri = Uri.tryParse(raw);
    if (uri == null ||
        uri.scheme != 'https' ||
        uri.userInfo.isNotEmpty ||
        uri.port != 443 ||
        uri.hasQuery ||
        uri.hasFragment) {
      return null;
    }
    final List<String> path;
    try {
      path = uri.pathSegments;
    } on FormatException {
      return null;
    }
    if (uri.host == 'github.com' &&
        _equalSegments(path, [
          'Lorandal',
          'StarMark',
          'releases',
          'download',
          version.tag,
          name,
        ])) {
      return uri;
    }
    if (uri.host != 'gitlab.com') return null;
    if (_equalSegments(path, [
      'Lorandal',
      'StarMark',
      '-',
      'releases',
      version.tag,
      'downloads',
      name,
    ])) {
      return uri;
    }
    if (_equalSegments(path, [
      'api',
      'v4',
      'projects',
      'Lorandal/StarMark',
      'packages',
      'generic',
      'starmark-${version.channel.toLowerCase()}',
      version.tag,
      name,
    ])) {
      return uri;
    }
    return null;
  }

  bool _equalSegments(List<String> a, List<String> b) {
    if (a.length != b.length) return false;
    for (var index = 0; index < a.length; index += 1) {
      if (a[index] != b[index]) return false;
    }
    return true;
  }

  Future<UpdateReleaseResponse> _readHttp(HttpClient client, Uri uri) async {
    HttpClientRequest? request;
    try {
      request = await client.getUrl(uri).timeout(_requestTimeout);
      request.followRedirects = false;
      request.headers.set(HttpHeaders.userAgentHeader, 'StarMark-UpdateCheck');
      request.headers.set(
        HttpHeaders.acceptHeader,
        uri.host == 'api.github.com'
            ? 'application/vnd.github+json'
            : 'application/json',
      );
      final response = await request.close().timeout(_requestTimeout);
      if (response.statusCode != HttpStatus.ok) {
        request.abort();
        return UpdateReleaseResponse(statusCode: response.statusCode, body: '');
      }
      if (response.contentLength > _maxResponseBytes) {
        throw const FormatException();
      }
      final bytes = <int>[];
      await for (final chunk in response.timeout(_requestTimeout)) {
        if (bytes.length + chunk.length > _maxResponseBytes) {
          throw const FormatException();
        }
        bytes.addAll(chunk);
      }
      return UpdateReleaseResponse(
        statusCode: response.statusCode,
        body: utf8.decode(bytes),
        headers: {
          for (final name in ['link', 'x-next-page'])
            if (response.headers.value(name) case final String value)
              name: value,
        },
      );
    } catch (_) {
      request?.abort();
      rethrow;
    }
  }
}

class _ReleaseVersion implements Comparable<_ReleaseVersion> {
  _ReleaseVersion(this.tag, this.channel, this.parts);

  static final _pattern = RegExp(
    r'^(Beta|Release|Dev)\.(\d+)\.(\d+)\.(\d+)\.(\d+)_([0-9a-fA-F]{7,8})$',
  );

  final String tag;
  final String channel;
  final List<int> parts;

  static _ReleaseVersion? parse(String value) {
    final match = _pattern.firstMatch(value);
    if (match == null) return null;
    final parsed = [
      for (var i = 2; i <= 5; i += 1) int.tryParse(match.group(i)!),
    ];
    if (parsed.any((value) => value == null)) return null;
    final parts = parsed.cast<int>();
    final channel = match.group(1)!;
    final suffix = match.group(6)!;
    final iteration = parts[3];
    if (parts[2] > 999) return null;
    // Match the channel ranges emitted by scripts/version_channels.py.
    if (channel == 'Dev') {
      if (!(iteration <= 49 || (iteration >= 100 && iteration <= 499)) ||
          !_validBuildDate(suffix)) {
        return null;
      }
    } else {
      if (suffix.length != 7) return null;
      if (channel == 'Beta' &&
          !((iteration >= 50 && iteration <= 99) ||
              (iteration >= 500 && iteration <= 999))) {
        return null;
      }
      if (channel == 'Release' && (parts[0] < 1 || iteration != 0)) return null;
    }
    return _ReleaseVersion(value, channel, parts);
  }

  static bool _validBuildDate(String value) {
    if (!RegExp(r'^\d{8}$').hasMatch(value)) return false;
    final year = int.parse(value.substring(0, 4));
    final month = int.parse(value.substring(4, 6));
    final day = int.parse(value.substring(6, 8));
    final date = DateTime.utc(year, month, day);
    return year >= 1 &&
        date.year == year &&
        date.month == month &&
        date.day == day;
  }

  @override
  int compareTo(_ReleaseVersion other) {
    for (var index = 0; index < parts.length; index += 1) {
      final comparison = parts[index].compareTo(other.parts[index]);
      if (comparison != 0) return comparison;
    }
    return 0;
  }
}

class _PublishedRelease {
  _PublishedRelease(this.version, this.source, this.assets);

  final _ReleaseVersion version;
  final String source;
  final Map<String, Uri> assets;
}
