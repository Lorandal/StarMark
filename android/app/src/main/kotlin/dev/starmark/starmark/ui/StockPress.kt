package dev.starmark.starmark.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max

internal val StockPressScaleKey = SemanticsPropertyKey<Float>("StarMarkStockPressScale")
internal val StockPressTintKey = SemanticsPropertyKey<Float>("StarMarkStockPressTintAlpha")

/** Observes the caller's one interaction source; adds no recognizer or click action. */
@Composable
internal fun Modifier.stockPress(
    interactionSource: InteractionSource,
    kind: StockPressKind,
    enabled: Boolean = true,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = LocalStarMarkEffects.current.reduceMotion
    val dark = MiuixTheme.colorScheme.background.luminance() < .5f
    val density = LocalDensity.current.density
    var maxSizePx by remember { mutableFloatStateOf(0f) }
    val target = stockPressTarget(kind, maxSizePx / density, dark, pressed, enabled, reduceMotion)
    val profile = stockPressSpring(pressed)
    val animation = if (reduceMotion || !enabled) snap<Float>() else spring(
        dampingRatio = profile.damping,
        stiffness = profile.stiffness,
        visibilityThreshold = .0001f,
    )
    // animateFloatAsState retains the spring's velocity when a press reverses in flight.
    val scale by animateFloatAsState(target.scale, animationSpec = animation, label = "stockPressScale")
    val tintAlpha by animateFloatAsState(target.tint.alpha, animationSpec = animation, label = "stockPressTint")
    val tint = stockPressTint(kind, dark)
    val visibleScale = if (reduceMotion || !enabled) 1f else scale
    val visibleAlpha = if (reduceMotion || !enabled) 0f else tintAlpha.coerceIn(0f, tint.alpha)
    return this
        .onSizeChanged { maxSizePx = max(it.width, it.height).toFloat() }
        .graphicsLayer {
            scaleX = visibleScale
            scaleY = visibleScale
        }
        .drawWithContent {
            drawContent()
            if (visibleAlpha > 0f) drawRect(tint.copy(alpha = visibleAlpha))
        }
        .semantics {
            this[StockPressScaleKey] = visibleScale
            this[StockPressTintKey] = visibleAlpha
        }
}
