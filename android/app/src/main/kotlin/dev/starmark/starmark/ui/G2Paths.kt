package dev.starmark.starmark.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path

/** Reuses the caller's path; native and Compose previews have identical geometry. */
internal fun Path.addG2Rect(width: Float, height: Float, radius: Float, offset: Offset = Offset.Zero) {
    G2CornerGeometry.write(object : G2PathSink {
        override fun moveTo(x: Float, y: Float) = this@addG2Rect.moveTo(x + offset.x, y + offset.y)
        override fun lineTo(x: Float, y: Float) = this@addG2Rect.lineTo(x + offset.x, y + offset.y)
        override fun arcTo(centerX: Float, centerY: Float, radius: Float, startDegrees: Float, sweepDegrees: Float) =
            this@addG2Rect.arcTo(androidx.compose.ui.geometry.Rect(centerX-radius+offset.x,centerY-radius+offset.y,
                centerX+radius+offset.x,centerY+radius+offset.y),startDegrees,sweepDegrees,false)
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) =
            this@addG2Rect.cubicTo(
                x1 + offset.x, y1 + offset.y,
                x2 + offset.x, y2 + offset.y,
                x + offset.x, y + offset.y,
            )
        override fun close() = this@addG2Rect.close()
    }, width, height, radius, extension = 1f, profile = G2CornerProfile.Circular)
}

internal fun android.graphics.Path.addG2Rect(
    width: Float,
    height: Float,
    radius: Float,
    left: Float = 0f,
    top: Float = 0f,
) {
    G2CornerGeometry.write(object : G2PathSink {
        override fun moveTo(x: Float, y: Float) = this@addG2Rect.moveTo(x + left, y + top)
        override fun lineTo(x: Float, y: Float) = this@addG2Rect.lineTo(x + left, y + top)
        override fun arcTo(centerX: Float, centerY: Float, radius: Float, startDegrees: Float, sweepDegrees: Float) =
            this@addG2Rect.arcTo(centerX-radius+left,centerY-radius+top,centerX+radius+left,centerY+radius+top,
                startDegrees,sweepDegrees,false)
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) =
            this@addG2Rect.cubicTo(x1 + left, y1 + top, x2 + left, y2 + top, x + left, y + top)
        override fun close() = this@addG2Rect.close()
    }, width, height, radius, extension = 1f, profile = G2CornerProfile.Circular)
}
