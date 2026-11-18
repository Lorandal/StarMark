// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package dev.starmark.starmark.ui.g2

// Adapted from Miuix 0.9.4: only track geometry changes; interaction and accessibility stay upstream.
import dev.starmark.starmark.ui.MiuixCornerShape
import dev.starmark.starmark.ui.drawG2Rect
import androidx.compose.ui.graphics.Path

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A [LinearProgressIndicator] with Miuix style.
 *
 * @param modifier The modifier to be applied to the indicator.
 * @param progress The current progress value between 0.0f and 1.0f, or null for indeterminate state.
 * @param colors The colors used for the indicator.
 * @param height The height of the indicator.
 */
@Composable
fun LinearProgressIndicator(
    modifier: Modifier = Modifier,
    progress: Float? = null,
    colors: ProgressIndicatorColors = ProgressIndicatorDefaults.progressIndicatorColors(),
    height: Dp = ProgressIndicatorDefaults.DefaultLinearProgressIndicatorHeight,
) {
    val trackPath = remember { Path() }
    val currentBackgroundColor = colors.backgroundColor()
    val currentForegroundColor = colors.foregroundColor(true)

    if (progress == null) {
        val transition = rememberInfiniteTransition()
        val animatedValue by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1250, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        )

        Canvas(
            modifier = modifier
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
                .fillMaxWidth()
                .height(height).clip(MiuixCornerShape.Capsule),
        ) {
            drawG2Rect(
                path = trackPath,
                color = currentBackgroundColor,
                size = size,
                radius = size.height / 2,
            )

            for (i in 0 until 3) {
                drawIndeterminateSegment(trackPath, animatedValue, i, currentForegroundColor)
            }
        }
    } else {
        val progressValue = progress.coerceIn(0f, 1f)

        Canvas(
            modifier = modifier
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progressValue, 0f..1f) }
                .fillMaxWidth()
                .height(height),
        ) {
            val cornerRadius = size.height / 2

            drawG2Rect(
                path = trackPath,
                color = currentBackgroundColor,
                size = size,
                radius = cornerRadius,
            )

            // Keep the usual round starting dot, leaving travel on a very short track.
            val minWidth = (size.width - size.height).coerceIn(0f, size.height)
            val progressWidth = minWidth + (size.width - minWidth) * progressValue

            drawG2Rect(
                path = trackPath,
                color = currentForegroundColor,
                size = Size(progressWidth, size.height),
                radius = cornerRadius,
            )
        }
    }
}

/**
 * A [CircularProgressIndicator] with Miuix style.
 *
 * @param modifier The modifier to be applied to the indicator.
 * @param progress The current progress value between 0.0f and 1.0f, or null for indeterminate state.
 * @param colors The colors used for the indicator.
 * @param strokeWidth The width of the circular stroke.
 * @param size The size (diameter) of the circular indicator.
 */
@Composable
fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    progress: Float? = null,
    colors: ProgressIndicatorColors = ProgressIndicatorDefaults.progressIndicatorColors(),
    strokeWidth: Dp = ProgressIndicatorDefaults.DefaultCircularProgressIndicatorStrokeWidth,
    size: Dp = ProgressIndicatorDefaults.DefaultCircularProgressIndicatorSize,
) {
    val currentBackgroundColor = colors.backgroundColor()
    val currentForegroundColor = colors.foregroundColor(true)

    if (progress == null) {
        val transition = rememberInfiniteTransition()

        val rotationAnim by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        )
        val sweepAnim by transition.animateFloat(
            initialValue = 30f,
            targetValue = 120f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1600
                    120f at 800 using LinearEasing
                    30f at 1600 using LinearEasing
                },
                repeatMode = RepeatMode.Restart,
            ),
        )

        Canvas(
            modifier = modifier
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
                .size(size),
        ) {
            drawProgressRing(currentBackgroundColor, currentForegroundColor, strokeWidth, rotationAnim, sweepAnim)
        }
    } else {
        val progressValue = progress.coerceIn(0f, 1f)

        Canvas(
            modifier = modifier
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progressValue, 0f..1f) }
                .size(size),
        ) {
            val minSweepAngle = 0.1f
            val sweepAngle = minSweepAngle + (360f - minSweepAngle) * progressValue
            drawProgressRing(currentBackgroundColor, currentForegroundColor, strokeWidth, -90f, sweepAngle)
        }
    }
}

/**
 * A [InfiniteProgressIndicator] with Miuix style.
 * The indicator is a circular indicator with an orbiting dot.
 *
 * @param modifier The modifier to be applied to the indicator.
 * @param color The color of the indicator.
 * @param size The size (diameter) of the circular indicator.
 * @param strokeWidth The width of the circular stroke.
 * @param orbitingDotSize The size of the orbiting dot.
 */
@Composable
fun InfiniteProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Gray,
    size: Dp = ProgressIndicatorDefaults.DefaultInfiniteProgressIndicatorSize,
    strokeWidth: Dp = ProgressIndicatorDefaults.DefaultInfiniteProgressIndicatorStrokeWidth,
    orbitingDotSize: Dp = ProgressIndicatorDefaults.DefaultInfiniteProgressIndicatorOrbitingDotSize,
) {
    val transition = rememberInfiniteTransition()
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )

    Canvas(
        modifier = modifier.size(size),
    ) {
        val diameter = this.size.minDimension
        if (diameter <= 0f) return@Canvas
        val strokeWidthPx = strokeWidth.toPx().coerceIn(0f, diameter)
        val radius = (diameter - strokeWidthPx) / 2f
        val orbitingDotSizePx = orbitingDotSize.toPx().coerceIn(0f, diameter / 2f)

        drawCircle(
            color = color,
            radius = radius,
            center = center,
            style = Stroke(strokeWidthPx, cap = StrokeCap.Round),
        )

        val orbitRadius = (radius - 2 * orbitingDotSizePx).coerceAtLeast(0f)
        val angle = rotation * (PI / 180.0).toFloat()
        val dotCenter = center + Offset(
            x = orbitRadius * cos(angle),
            y = orbitRadius * sin(angle),
        )

        drawCircle(
            color = color,
            radius = orbitingDotSizePx,
            center = dotCenter,
        )
    }
}

/**
 * Draws a single animated segment for the indeterminate linear progress indicator.
 */
private fun DrawScope.drawIndeterminateSegment(
    trackPath: Path,
    animatedValue: Float,
    segmentIndex: Int,
    color: Color,
) {
    val position = animatedValue - segmentIndex * (0.45f + 0.55f)
    val adjustedPos = (position % 1f + 1f) % 1f
    val cornerRadius = size.height / 2

    if (adjustedPos < 1f - 0.45f) {
        drawG2Rect(
            path = trackPath,
            color = color,
            topLeft = Offset(size.width * adjustedPos, 0f),
            size = Size(size.width * 0.45f, size.height),
            radius = cornerRadius,
        )
    } else {
        drawG2Rect(
            path = trackPath,
            color = color,
            topLeft = Offset(size.width * adjustedPos, 0f),
            size = Size(size.width * (1f - adjustedPos), size.height),
            radius = cornerRadius,
        )
        val remainingWidth = adjustedPos + 0.45f - 1f
        if (remainingWidth > 0) {
            drawG2Rect(
                path = trackPath,
                color = color,
                size = Size(size.width * remainingWidth, size.height),
                radius = cornerRadius,
            )
        }
    }
}

private fun DrawScope.drawProgressRing(
    background: Color, foreground: Color, stroke: Dp, startAngle: Float, sweepAngle: Float,
) {
    val diameter = size.minDimension
    if (diameter <= 0f) return
    val lineWidth = stroke.toPx().coerceIn(0f, diameter)
    val radius = (diameter - lineWidth) / 2f
    drawCircle(background, radius, center, style = Stroke(lineWidth))
    drawArc(foreground, startAngle, sweepAngle, useCenter = false,
        topLeft = center - Offset(radius, radius), size = Size(2f * radius, 2f * radius),
        style = Stroke(lineWidth, cap = StrokeCap.Round))
}

object ProgressIndicatorDefaults {
    /** The default height of [LinearProgressIndicator]. */
    val DefaultLinearProgressIndicatorHeight = 6.dp

    /** The default stroke width of [CircularProgressIndicator]. */
    val DefaultCircularProgressIndicatorStrokeWidth = 4.dp

    /** The default size of [CircularProgressIndicator]. */
    val DefaultCircularProgressIndicatorSize = 30.dp

    /** The default stroke width of [InfiniteProgressIndicator]. */
    val DefaultInfiniteProgressIndicatorStrokeWidth = 2.dp

    /** The default radius width of the orbiting dot in [InfiniteProgressIndicator]. */
    val DefaultInfiniteProgressIndicatorOrbitingDotSize = 2.dp

    /** The default size of [InfiniteProgressIndicator]. */
    val DefaultInfiniteProgressIndicatorSize = 20.dp

    /**
     * The default [ProgressIndicatorColors] used by [LinearProgressIndicator] and [CircularProgressIndicator].
     */
    @Composable
    fun progressIndicatorColors(
        foregroundColor: Color = MiuixTheme.colorScheme.primary,
        disabledForegroundColor: Color = MiuixTheme.colorScheme.disabledPrimarySlider,
        backgroundColor: Color = MiuixTheme.colorScheme.secondaryContainer,
    ): ProgressIndicatorColors = remember(foregroundColor, disabledForegroundColor, backgroundColor) {
        ProgressIndicatorColors(
            foregroundColor = foregroundColor,
            disabledForegroundColor = disabledForegroundColor,
            backgroundColor = backgroundColor,
        )
    }
}

@Immutable
data class ProgressIndicatorColors(
    private val foregroundColor: Color,
    private val disabledForegroundColor: Color,
    private val backgroundColor: Color,
) {
    @Stable
    internal fun foregroundColor(enabled: Boolean): Color = if (enabled) foregroundColor else disabledForegroundColor

    @Stable
    internal fun backgroundColor(): Color = backgroundColor
}
