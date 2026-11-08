// API-compatible adaptation of compose-miuix-ui 0.9.4.
// Copyright 2026 compose-miuix-ui contributors. SPDX-License-Identifier: Apache-2.0
package top.yukonga.miuix.kmp.squircle

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import dev.starmark.starmark.ui.G2CornerProfile

@Composable
fun Modifier.squircleBorder(
    width: Dp,
    color: Color,
    cornerRadius: Dp,
    extension: Float = SquircleDefaults.Extension,
): Modifier = squircleBorder({ width }, { color }, cornerRadius, extension)

@Composable
fun Modifier.squircleBorder(
    width: () -> Dp,
    color: () -> Color,
    cornerRadius: Dp,
    extension: Float = SquircleDefaults.Extension,
): Modifier {
    val enabled = isSquircleEnabled()
    return drawWithCache {
        val path = Path().apply {
            if (!enabled || size.width == size.height && cornerRadius.toPx() >= size.width / 2f) {
                addSquircleRect(size.width, size.height, cornerRadius.toPx(), extension, enabled)
            } else {
                val r = cornerRadius.toPx()
                addG2Corners(size.width, size.height, r, r, r, r, extension, G2CornerProfile.MiuixSurface)
            }
        }
        onDrawWithContent {
            drawContent()
            val px = width().toPx()
            if (px > 0f) clipPath(path) { drawPath(path, color(), style = Stroke(px * 2f)) }
        }
    }
}
