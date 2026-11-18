package dev.starmark.starmark.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Redraw each end at its final size; the click target and content never move. */
internal fun Modifier.navigationPressFace(
    shape: MiuixCornerShape, color: Color, inset: Dp = 0.dp,
    progress: () -> Float, alpha: () -> Float = { 1f }, pressedColor: Color = color,
) = drawWithCache {
    val path = Path()
    val top = inset.toPx()
    val height = (size.height - top * 2f).coerceAtLeast(0f)
    val radius = shape.radius.toPx()
    val travel = 6.dp.toPx()
    var lastDelta = Float.NaN
    onDrawBehind {
        val opacity = alpha()
        if (opacity > 0f && size.width > 0f && height > 0f) {
            val pressure = progress()
            val delta = travel * pressure
            if (delta != lastDelta) {
                path.rewind()
                path.addCornerFrame(pressedCornerFrame(size.width, height, delta, shape.kind, radius))
                lastDelta = delta
            }
            val tint = if (pressedColor == color) color else androidx.compose.ui.graphics.lerp(color, pressedColor, pressure)
            translate(top = top) { drawPath(path, tint, alpha = opacity) }
        }
    }
}

/** The Path is owned by remember/the View, never allocated inside an animation frame. */
internal fun DrawScope.drawG2Rect(
    path: Path,
    color: Color,
    size: Size,
    radius: Float = size.height / 2f,
    topLeft: Offset = Offset.Zero,
) {
    path.reset()
    path.addG2Rect(size.width, size.height, radius, topLeft)
    drawPath(path, color)
}

/** Axis-aligned slider tracks keep the original round-cap bounds and touch geometry. */
internal fun DrawScope.drawG2Track(
    path: Path,
    color: Color,
    start: Offset,
    end: Offset,
    strokeWidth: Float,
) {
    val radius = strokeWidth / 2f
    if (start == end) {
        drawCircle(color, radius, start)
        return
    }
    drawG2Rect(
        path, color,
        Size(abs(end.x - start.x) + strokeWidth, abs(end.y - start.y) + strokeWidth),
        radius,
        Offset(minOf(start.x, end.x) - radius, minOf(start.y, end.y) - radius),
    )
}
