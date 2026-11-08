package dev.starmark.starmark.ui

/** Paths are resolved by the native host, never supplied by a document-provider URI. */
internal sealed interface FontPreviewSource {
    data class Asset(val path: String) : FontPreviewSource
    data class PrivateFile(val relativePath: String, val fingerprint: String) : FontPreviewSource
}

internal fun fontPreviewSource(font: Map<*, *>): FontPreviewSource? = when (font["source"]?.toString() ?: "bundled") {
    "bundled" -> {
        val asset = font["asset"]?.toString().orEmpty()
        if (asset.startsWith("assets/fonts/") && !asset.contains('\\') &&
            asset.split('/').none { it == "." || it == ".." || it.isBlank() }
        ) FontPreviewSource.Asset("flutter_assets/$asset") else null
    }
    "imported" -> {
        val id = font["id"]?.toString().orEmpty()
        val fingerprint = font["fingerprint"]?.toString().orEmpty()
        if (fingerprint.matches(Regex("[a-f0-9]{64}")) && id == "imported-$fingerprint") {
            FontPreviewSource.PrivateFile("starmark/imported-fonts/$id.font", fingerprint)
        } else null
    }
    else -> null
}
