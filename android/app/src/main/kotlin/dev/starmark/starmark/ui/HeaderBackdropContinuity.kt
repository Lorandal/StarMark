package dev.starmark.starmark.ui

/** Smooth endpoint slopes avoid a visible tint shoulder as native material is revealed. */
internal fun headerBackdropOpacity(fraction: Float): Float {
    val t = ((fraction - .75f) / .25f).coerceIn(0f, 1f)
    return 1f - t * t * (3f - 2f * t)
}
