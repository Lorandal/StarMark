package dev.starmark.starmark.ui

/** Convert both origins from screen pixels before applying density; windows may have different insets. */
internal fun popupLocalAnchor(screen: MotionRect, popupScreenX: Float, popupScreenY: Float, density: Float): MotionRect? {
    if (!listOf(screen.left, screen.top, screen.width, screen.height, popupScreenX, popupScreenY, density).all { it.isFinite() } ||
        density <= 0f || screen.width <= 0f || screen.height <= 0f) return null
    return MotionRect((screen.left - popupScreenX) / density, (screen.top - popupScreenY) / density, screen.width / density, screen.height / density)
}

internal const val ChoicePopupWidth = 200f
internal const val ChoicePopupMaxHeight = 416f
internal const val ChoicePopupSafeMargin = 12f

internal data class PopupPlacement(val bounds: MotionRect, val alignRight: Boolean, val above: Boolean) {
    // Settings Spinner implements IPopupAnimView, selecting ArcAnimation (not DirectionAnimation).
    val collapsed: MotionRect get() {
        val width = bounds.width * .69f
        val height = width / 5f
        return MotionRect(if (alignRight) bounds.left + bounds.width - width else bounds.left,
            if (above) bounds.top + bounds.height - height else bounds.top, width, height)
    }
}

/** Settings aligns to the value's bounds, then flips above it when the lower side is full. */
internal fun choicePopupPlacement(anchor: MotionRect, safe: MotionRect, width: Float, height: Float): PopupPlacement {
    val w = width.coerceIn(1f, safe.width.coerceAtLeast(1f))
    val h = height.coerceIn(1f, minOf(ChoicePopupMaxHeight, safe.height).coerceAtLeast(1f))
    val right = anchor.centerX > safe.centerX
    val x = (if (right) anchor.left + anchor.width - w else anchor.left)
        .coerceIn(safe.left, (safe.left + safe.width - w).coerceAtLeast(safe.left))
    val top = anchor.top.coerceIn(safe.top, safe.top + safe.height)
    val below = safe.top + safe.height - top
    val above = top - safe.top
    val flip = below < h && above > below
    val y = if (flip) top - h else top
    return PopupPlacement(MotionRect(x, y.coerceIn(safe.top, (safe.top + safe.height - h).coerceAtLeast(safe.top)), w, h), right, flip)
}
