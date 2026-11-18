package dev.starmark.starmark.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.squircle.addG2Corners

/** One G2 outline for UI fills, clipping, borders, shadows and native blur. */
internal data class MiuixCornerShape(val radius: Dp, val kind: CornerKind = CornerKind.Fixed) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val frame = kind.resolve(size.width, size.height, with(density) { radius.toPx() })
        // Compose's simple rounded border assumes a full-size origin-zero rectangle.
        // An inset disk needs a path so its fill, rim and shadow keep the same bounds.
        if (frame.kind == CornerKind.Circle && size.width == size.height) {
            return Outline.Rounded(RoundRect(frame.left, frame.top, frame.left + frame.width,
                frame.top + frame.height, CornerRadius(frame.radius)))
        }
        return Outline.Generic(Path().apply { addCornerFrame(frame) })
    }

    companion object {
        val Circle = MiuixCornerShape(0.dp, CornerKind.Circle)
        val Capsule = MiuixCornerShape(0.dp, CornerKind.Capsule)
    }
}

/** The receiving path is empty. This also supplies the native material mask. */
internal fun Path.addCornerFrame(frame: CornerFrame) {
    when (frame.kind) {
        CornerKind.Circle -> addOval(androidx.compose.ui.geometry.Rect(0f, 0f, frame.width, frame.height))
        CornerKind.Capsule -> addG2Rect(frame.width, frame.height, frame.radius)
        CornerKind.Fixed -> with(frame) {
            addG2Corners(width, height, radius, radius, radius, radius, G2CornerGeometry.EXTENSION, G2CornerProfile.MiuixSurface)
        }
    }
    if (frame.left != 0f || frame.top != 0f) translate(Offset(frame.left, frame.top))
}
