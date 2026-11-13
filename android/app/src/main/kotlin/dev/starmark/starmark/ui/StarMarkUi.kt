package dev.starmark.starmark.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal typealias UiCommand = (String, Map<String, Any?>) -> Unit
internal fun Map<*, *>.obj(key: String): Map<*, *> = this[key] as? Map<*, *> ?: emptyMap<Any, Any>()
internal fun Map<*, *>.rows(key: String): List<Map<*, *>> = (this[key] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
internal fun Map<*, *>.text(key: String, fallback: String = ""): String = this[key]?.toString() ?: fallback
internal fun Map<*, *>.flag(key: String, fallback: Boolean = false): Boolean = this[key] as? Boolean ?: fallback
internal fun Map<*, *>.number(key: String, fallback: Float = 0f): Float = (this[key] as? Number)?.toFloat() ?: fallback
internal fun Map<*, *>.integer(key: String, fallback: Int = 0): Int = (this[key] as? Number)?.toInt() ?: fallback
internal fun Map<*, *>.longNumber(key: String, fallback: Long = 0): Long = (this[key] as? Number)?.toLong() ?: fallback
internal fun UiCommand.send(action: String, vararg args: Pair<String, Any?>) = invoke(action, mapOf(*args))
internal fun UiCommand.patch(label: String, vararg fields: Pair<String, Any?>) = send("document.apply", "patch" to mapOf(*fields), "label" to label)
internal fun Map<*, *>.stringKeys(): Map<String, Any?> = entries.associate { it.key.toString() to it.value }

@Composable
internal fun MainButton(text: String, tag: String, enabled: Boolean = true, modifier: Modifier = Modifier,
    glassMaterial: Boolean = LocalGlassContainer.current, onClick: () -> Unit) {
    Button(
        onClick = hapticClick(onClick = onClick),
        enabled = enabled,
        modifier = modifier.fillMaxWidth().testTag(tag),
        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary, contentColor = MiuixTheme.colorScheme.onPrimary),
        glassMaterial = glassMaterial,
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
internal fun SupportingText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
internal fun EmptyState(title: String, description: String, action: String? = null, tag: String = "empty.action", onAction: () -> Unit = {}) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(24.dp)) {
        Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        SupportingText(description)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            MainButton(action, tag, onClick = onAction)
        }
    }
}

/** Bounded decode keeps full-size source images out of the native UI heap. */
@Composable
internal fun PhotoThumbnail(path: String, modifier: Modifier = Modifier, description: String? = null, contentScale: ContentScale = ContentScale.Crop, cornerRadius: androidx.compose.ui.unit.Dp = 20.dp, onAspectRatio: ((Float) -> Unit)? = null, decodeEdge: Int = 512) {
    val context = LocalContext.current
    val bitmap by produceState(ThumbnailCache.peek(path, decodeEdge), path, decodeEdge) {
        value = ThumbnailCache.peek(path, decodeEdge)
        value = ThumbnailCache.load(context.applicationContext, path, decodeEdge)
    }
    val ratioCallback by rememberUpdatedState(onAspectRatio)
    LaunchedEffect(bitmap) {
        bitmap?.let { if (it.height > 0) ratioCallback?.invoke(it.width.toFloat() / it.height) }
    }
    // A contained preview must not acquire a larger gray card around its image.
    // Keep the loading placeholder, and let the source pixels own their background.
    val background = if (bitmap != null && contentScale == ContentScale.Fit) Color.Transparent else MiuixTheme.colorScheme.surfaceContainerHigh
    Box(modifier.clip(MiuixCornerShape(cornerRadius)).background(background), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, description, Modifier.fillMaxSize(), contentScale = contentScale) }
            ?: SupportingText(if (path.isBlank()) "StarMark" else "预览", Modifier.padding(12.dp))
    }
}
