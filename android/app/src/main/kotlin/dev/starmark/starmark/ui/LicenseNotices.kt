package dev.starmark.starmark.ui

import java.security.MessageDigest
import java.util.Locale

internal data class LicenseNotice(val title: String, val text: String)
internal data class LicenseNoticeGroup(val titles: List<String>, val text: String) {
    // Separate sources can carry identical terms and still need separate expansion keys.
    val id: String = MessageDigest.getInstance("SHA-256").digest(
        (titles.joinToString("\u0000") { licenseSourceKey(it) } + "\u0000" + text).toByteArray(Charsets.UTF_8),
    )
        .joinToString("") { "%02x".format(it) }
}

private fun normalizeNoticeLineEndings(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')

private fun licenseSourceKey(title: String): String = when (val key = title.trim().lowercase(Locale.ROOT)) {
    "dart sdk" -> "dart"
    else -> key
}

private class SourceNotices(val title: String) {
    val byText = linkedMapOf<String, LinkedHashSet<String>>()
}

/** Aggregate by source, retaining each complete notice and its original package attribution. */
internal fun groupLicenseNotices(notices: List<LicenseNotice>): List<LicenseNoticeGroup> {
    val bySource = linkedMapOf<String, SourceNotices>()
    notices.forEach { notice ->
        val text = normalizeNoticeLineEndings(notice.text).lineSequence()
            .joinToString("\n") { it.trimEnd() }.trimEnd('\n')
        val titles = normalizeNoticeLineEndings(notice.title).lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() }.distinct().toList().ifEmpty { listOf("未命名来源") }
        // Flutter lists every package sharing a notice in its header. A shared notice
        // belongs to each package; it does not make those packages the same project.
        titles.distinctBy(::licenseSourceKey).forEach { title ->
            val key = licenseSourceKey(title)
            val source = bySource.getOrPut(key) { SourceNotices(if (key == "dart") "dart" else title) }
            source.byText.getOrPut(text) { linkedSetOf() }.addAll(titles)
        }
    }
    return bySource.values.map { source ->
        val text = source.byText.entries.joinToString("\n\n" + "-".repeat(40) + "\n\n") { (body, titles) ->
            "来源：\n${titles.joinToString("\n")}\n\n$body"
        }
        LicenseNoticeGroup(listOf(source.title), text)
    }
}

internal fun parseFlutterLicenseNotices(raw: String): List<LicenseNotice> =
    normalizeNoticeLineEndings(raw).split("\n" + "-".repeat(80) + "\n")
        .filter { it.isNotBlank() }.mapIndexed { index, rawChunk ->
            val chunk = rawChunk.trim('\n')
            val split = chunk.indexOf("\n\n")
            LicenseNotice(
                if (split > 0) chunk.substring(0, split).trim() else "Flutter 依赖 ${index + 1}",
                if (split > 0) chunk.substring(split + 2).trim('\n') else chunk,
            )
        }
