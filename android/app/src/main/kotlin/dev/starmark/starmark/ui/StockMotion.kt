package dev.starmark.starmark.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.max

/** The Clock row tint and Folme's banner/template scale are separate effects. */
internal enum class StockPressKind { Row, Banner, Watermark }

internal data class StockPressTarget(val scale: Float, val tint: Color)

internal fun stockPressScale(kind: StockPressKind, maxSizeDp: Float): Float {
    if (kind == StockPressKind.Row || !maxSizeDp.isFinite() || maxSizeDp <= 0f) return 1f
    return max(.9f, 1f - 10f / maxSizeDp)
}

internal fun stockPressTint(kind: StockPressKind, dark: Boolean): Color = when {
    kind != StockPressKind.Row -> Color.Transparent
    dark -> Color.White.copy(alpha = .04f)
    else -> Color.Black.copy(alpha = .08f)
}

internal fun stockPressTarget(
    kind: StockPressKind,
    maxSizeDp: Float,
    dark: Boolean,
    pressed: Boolean,
    enabled: Boolean = true,
    reduceMotion: Boolean = false,
): StockPressTarget = if (enabled && pressed && !reduceMotion) {
    StockPressTarget(stockPressScale(kind, maxSizeDp), stockPressTint(kind, dark))
} else {
    StockPressTarget(1f, Color.Transparent)
}

internal fun stockPressSpring(pressed: Boolean) = FolmeSpring(.99f, if (pressed) .15f else .30f)

/** MiMediaEditor subpanel timing is independent of the physics-based press effect. */
internal const val StockPanelEnterMillis = 350
internal const val StockPanelExitMillis = 160
internal const val StockPanelOffsetDp = 134f

internal fun stockEaseOutCubic(fraction: Float): Float {
    if (fraction.isNaN()) return 0f
    val remaining = 1f - fraction.coerceIn(0f, 1f)
    return 1f - remaining * remaining * remaining
}
