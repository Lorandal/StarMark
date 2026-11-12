package dev.starmark.starmark.updates

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Opens a validated APK URL through Android's browser resolution, including its default browser. */
fun openUpdateInBrowser(context: Context, url: String) {
    require(isAllowedUpdateDownloadUrl(url)) { "更新下载地址无效，请重新检查更新" }
    var unavailable: ActivityNotFoundException? = null
    for (scheme in listOf("https", "http")) {
        // A hostless selector matches general browsers, excluding domain-specific App Links.
        // Keep ACTION_VIEW and the actual HTTPS URL on the delivered intent. Using ACTION_MAIN
        // with URL data instead can fail modern Android intent-filter validation.
        val browserSelector = Intent(Intent.ACTION_VIEW, Uri.fromParts(scheme, "", null))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { selector = browserSelector }
        try {
            context.startActivity(intent)
            return
        } catch (error: ActivityNotFoundException) {
            unavailable = error
        } catch (error: SecurityException) {
            throw IllegalStateException(browserUnavailableMessage, error)
        }
    }
    throw IllegalStateException(browserUnavailableMessage, unavailable)
}

private const val browserUnavailableMessage = "无法打开系统浏览器，请在系统设置中检查默认浏览器后重试"

internal fun isAllowedUpdateDownloadUrl(url: String): Boolean {
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        return false
    }
    if (uri.scheme != "https" || uri.isOpaque || uri.rawUserInfo != null ||
        uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null ||
        uri.rawAuthority != uri.host
    ) return false

    val parts = uri.rawPath?.split('/') ?: return false
    val releaseParts = when {
        uri.host == "github.com" && parts.size == 7 &&
            parts.take(5) == listOf("", "Lorandal", "StarMark", "releases", "download") ->
            parts[5] to parts[6]
        uri.host == "gitlab.com" && parts.size == 8 &&
            parts.take(5) == listOf("", "Lorandal", "StarMark", "-", "releases") &&
            parts[6] == "downloads" -> parts[5] to parts[7]
        uri.host == "gitlab.com" && parts.size == 10 &&
            parts.take(4) == listOf("", "api", "v4", "projects") &&
            parts[4].replace("%2f", "%2F") == "Lorandal%2FStarMark" &&
            parts[5] == "packages" && parts[6] == "generic" &&
            parts[7] in listOf("starmark-beta", "starmark-release") -> parts[8] to parts[9]
        else -> return false
    }
    val tag = safeDownloadSegment(releaseParts.first) ?: return false
    val filename = safeDownloadSegment(releaseParts.second) ?: return false
    return tag.isNotEmpty() && filename.length > 4 && filename.endsWith(".apk")
}

private fun safeDownloadSegment(raw: String): String? {
    val decoded = try {
        // '+' is literal in a URL path; URLDecoder otherwise treats it as a form space.
        URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (decoded.isEmpty() || decoded == "." || decoded == ".." ||
        decoded.any { !it.isLetterOrDigit() && it !in "._-+ " }
    ) return null
    return decoded
}
