package dev.starmark.starmark.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.exp

/** A faint reflection fading into the original outline, not a second white frame.
 * Cached strokes stay inside the existing G2 clip and never change layout/hit bounds. */
internal fun Modifier.frostedEdge(shape: Shape, dark: Boolean): Modifier = clip(shape).drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val reflection = Brush.verticalGradient(listOf(
        Color.White.copy(alpha = if (dark) .22f else .32f),
        Color.White.copy(alpha = if (dark) .025f else .04f),
        Color.White.copy(alpha = if (dark) .10f else .14f),
    ))
    var coverage = 0f
    val feather = List(12) { index ->
        val distance = 2f * (12 - index) / 12f
        val next = .70f * exp(-.5f * (distance / .55f) * (distance / .55f))
        val alpha = (next - coverage) / (1f - coverage)
        coverage = next
        Stroke((2f * distance).dp.toPx()) to alpha
    }
    onDrawWithContent {
        drawContent()
        feather.forEach { (stroke, alpha) ->
            drawOutline(outline, reflection, alpha = alpha, style = stroke)
        }
    }
}
