package dev.starmark.starmark.ui

/** Shared values for the current soft-glass UI and offline photo material. */
internal object PortableGlassRecipe {
    const val chroma = 1.1f
    const val tintAlpha = .25f
    const val edgeBandDp = 6f
    const val edgeDisplacementDp = 3.6f
    fun contrast(dark: Boolean) = if (dark) .68f else 0f
    fun whitePoint(dark: Boolean) = if (dark) -.35f else .50f
    fun tint(dark: Boolean) = if (dark) 0xff808080L else 0xffe0e0e0L
    fun blurDp(dark: Boolean) = if (dark) 9f else 11f
}
