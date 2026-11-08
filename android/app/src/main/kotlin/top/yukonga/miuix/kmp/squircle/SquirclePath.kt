// API-compatible adaptation of compose-miuix-ui 0.9.4; StarMark G2 geometry replaces its cubic/SDF.
// Copyright 2026 compose-miuix-ui contributors. SPDX-License-Identifier: Apache-2.0
package top.yukonga.miuix.kmp.squircle

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import dev.starmark.starmark.ui.G2CornerGeometry
import dev.starmark.starmark.ui.G2CornerProfile
import dev.starmark.starmark.ui.G2PathSink
import kotlin.math.abs

object SquircleDefaults {
    val Extension = G2CornerGeometry.EXTENSION
    val ExtensionMin = 1f
    val ExtensionMax = 2f
}

fun Path.addSquircleRect(
    width: Float,
    height: Float,
    cornerRadius: Float,
    extension: Float = SquircleDefaults.Extension,
    squircleEnabled: Boolean = true,
) {
    if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0) return
    val r = if (cornerRadius.isFinite()) cornerRadius.coerceAtLeast(0f) else 0f
    // Circles are already curvature-continuous and must keep their optical centre/diameter.
    if (!squircleEnabled || (abs(width - height) < .01f && r >= width / 2f)) {
        addRoundRect(RoundRect(0f, 0f, width, height, CornerRadius(r.coerceAtMost(minOf(width, height) / 2f))))
        return
    }
    addG2Corners(width, height, r, r, r, r, extension)
}

internal fun Path.addG2Corners(
    width: Float,
    height: Float,
    tl: Float,
    tr: Float,
    br: Float,
    bl: Float,
    extension: Float,
    profile: G2CornerProfile = G2CornerProfile.Miuix,
) {
    G2CornerGeometry.write(object : G2PathSink {
        override fun moveTo(x: Float, y: Float) = this@addG2Corners.moveTo(x, y)
        override fun lineTo(x: Float, y: Float) = this@addG2Corners.lineTo(x, y)
        override fun arcTo(centerX: Float, centerY: Float, radius: Float, startDegrees: Float, sweepDegrees: Float) =
            this@addG2Corners.arcTo(androidx.compose.ui.geometry.Rect(centerX-radius,centerY-radius,
                centerX+radius,centerY+radius),startDegrees,sweepDegrees,false)
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) =
            this@addG2Corners.cubicTo(x1, y1, x2, y2, x, y)
        override fun close() = this@addG2Corners.close()
    }, width, height, tl, tr, br, bl, extension, profile)
}
