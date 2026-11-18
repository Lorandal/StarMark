package dev.starmark.starmark.ui

/** All lengths are physical pixels in the final, measured drawing area. */
internal enum class CornerKind {
    Fixed, Circle, Capsule;

    fun resolve(width: Float, height: Float, nominalRadius: Float): CornerFrame {
        val diameter = minOf(width, height).coerceAtLeast(0f)
        val half = diameter / 2f
        val radius = if (this == Fixed) nominalRadius.coerceIn(0f, half) else half
        val kind = when {
            this == Circle || (width == height && radius == half) -> Circle
            radius == half -> Capsule
            else -> this
        }
        return if (kind == Circle) {
            CornerFrame((width - diameter) / 2f, (height - diameter) / 2f, diameter, diameter, half, kind)
        } else CornerFrame(0f, 0f, width, height, radius, kind)
    }
}

internal data class CornerFrame(
    val left: Float, val top: Float, val width: Float, val height: Float,
    val radius: Float, val kind: CornerKind,
) {
    // Both optical engines accept only circles and rounded rectangles. Keep their
    // material outside the visible G2 path so the final clip can draw every corner.
    val opticalRadius: Float get() = if (kind == CornerKind.Fixed)
        G2CornerGeometry.enclosingSurfaceRadius(width, height, radius) else radius
}

internal fun pressedCornerFrame(
    width: Float, height: Float, shrink: Float, kind: CornerKind, radius: Float,
): CornerFrame {
    val w = (width - shrink).coerceAtLeast(width / 2f)
    val h = (height - shrink).coerceAtLeast(height / 2f)
    val frame = kind.resolve(w, h, radius)
    return frame.copy(left = (width - w) / 2f + frame.left, top = (height - h) / 2f + frame.top)
}
