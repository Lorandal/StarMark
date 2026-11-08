package dev.starmark.starmark.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.squircle.addSquircleRect

/** One G2 outline for UI fills, clipping, borders, shadows and native blur. */
internal data class MiuixCornerShape(val radius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        miuixCornerOutline(size, with(density) { radius.toPx() })
}

internal fun miuixCornerOutline(size: Size, radiusPx: Float): Outline {
    val radius = radiusPx.coerceAtLeast(0f)
    // A true circle already has continuous curvature. Non-square capsules use G2 joins.
    if (kotlin.math.abs(size.width - size.height) < .01f && radius >= size.width / 2f) {
        val half = minOf(size.width, size.height) / 2f
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(half)))
    }
    if (radius >= minOf(size.width, size.height) / 2f) {
        // Preserve the former semicircular ends and diameter. Only their straight joins
        // receive the approved local G2 shoulder; never apply the card's squircle profile here.
        return Outline.Generic(Path().apply { addG2Rect(size.width, size.height, radius) })
    }
    return Outline.Generic(Path().apply { addSquircleRect(size.width, size.height, radius) })
}
