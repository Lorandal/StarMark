package dev.starmark.starmark.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.abs

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
