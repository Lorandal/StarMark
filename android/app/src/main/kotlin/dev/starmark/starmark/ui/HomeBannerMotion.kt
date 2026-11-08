package dev.starmark.starmark.ui

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** Calendar 18.0.2.0 ViewPager.i / P / ViewPager$b; distances and velocity in pixels. */
internal object CalendarPagerMotion {
    fun ease(t: Float): Float { val x = t - 1f; return x * x * x * x * x + 1f }

    fun target(startPage: Int, position: Float, draggedPx: Float, velocityPx: Float, density: Float): Int {
        val base = floor(position).toInt()
        return (if (abs(draggedPx) > 25f * density && abs(velocityPx) > 400f * density) {
            base + if (velocityPx < 0f) 1 else 0
        } else floor(position + if (base >= startPage) .4f else .6f).toInt()).coerceIn(0, 2)
    }

    fun duration(distancePx: Float, pageWidthPx: Float, velocityPx: Float): Int {
        if (pageWidthPx <= 0f || abs(distancePx) < .5f) return 0
        val distance = abs(distancePx)
        val influenced = pageWidthPx * .5f * (1f + sin((minOf(1f, distance / pageWidthPx) - .5f) * .47123894f))
        return (if (abs(velocityPx) > 0f) 4 * (1000f * abs(influenced / velocityPx)).roundToInt()
            else ((distance / pageWidthPx + 1f) * 100f).toInt()).coerceIn(0, 600)
    }
}

internal fun homeResumePreview(styles: List<Map<*, *>>): String =
    styles.firstOrNull { it["selected"] == true }?.get("image") as? String ?: ""

internal data class BannerCardTransform(val scale: Float, val translationX: Float)

internal fun homeBannerCardTransform(width: Float, index: Int, position: Float): BannerCardTransform =
    BannerCardTransform(
        1f - abs(index - position).coerceIn(0f, 1f) * (1f - 800f / 890f),
        (index - position) * width * 900f / 1200f,
    )

internal data class BannerActionGeometry(val left: Float, val top: Float, val size: Float)

/** Same square-card center/scale as the photo layer, expressed in viewport pixels. */
internal fun homeBannerActionGeometry(width: Float, height: Float, index: Int, position: Float, density: Float): BannerActionGeometry {
    val card = width * 890f / 1200f
    val transform = homeBannerCardTransform(width, index, position)
    val scale = transform.scale
    val size = 56f * density * scale
    val inset = 16f * density * scale
    val right = width / 2f + transform.translationX + card / 2f * scale
    val bottom = height / 2f + card / 2f * scale
    // Preview is 57.6dp, so the 56dp action sits .8dp higher than its bottom.
    return BannerActionGeometry(right - inset - size, bottom - inset - size - .8f * density * scale, size)
}
