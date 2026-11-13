// API-compatible adaptation of compose-miuix-ui 0.9.4; shared G2 fill and mask.
// Copyright 2026 compose-miuix-ui contributors. SPDX-License-Identifier: Apache-2.0
package top.yukonga.miuix.kmp.squircle

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import dev.starmark.starmark.ui.G2CornerProfile

private data class G2Shape(
    val ts: Dp,
    val te: Dp,
    val be: Dp,
    val bs: Dp,
    val extension: Float,
    val absolute: Boolean,
    val enabled: Boolean,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = with(density) {
        val flip = !absolute && layoutDirection == LayoutDirection.Rtl
        val tl = if (flip) te else ts
        val tr = if (flip) ts else te
        val br = if (flip) bs else be
        val bl = if (flip) be else bs
        if (!enabled) {
            return@with AbsoluteRoundedCornerShape(tl, tr, br, bl).createOutline(size, layoutDirection, density)
        }
        Outline.Generic(Path().apply {
            if (size.width == size.height && tl == tr && tl == br && tl == bl && tl.toPx() >= size.width / 2f) {
                addSquircleRect(size.width, size.height, tl.toPx(), extension)
            } else {
                addG2Corners(size.width, size.height, tl.toPx(), tr.toPx(), br.toPx(), bl.toPx(), extension, G2CornerProfile.MiuixSurface)
            }
        })
    }
}

@Composable
private fun g2(ts: Dp, te: Dp, be: Dp, bs: Dp, extension: Float, absolute: Boolean = false): Shape {
    val enabled = isSquircleEnabled()
    return remember(ts, te, be, bs, extension, absolute, enabled) {
        G2Shape(ts, te, be, bs, extension, absolute, enabled)
    }
}

/** Share the original Surface mask with custom paint without changing its geometry. */
@Composable
internal fun rememberSquircleSurfaceShape(cornerRadius: Dp): Shape =
    g2(cornerRadius, cornerRadius, cornerRadius, cornerRadius, SquircleDefaults.Extension)

@Composable
fun Modifier.squircleBackground(color: Color, cornerRadius: Dp, extension: Float = SquircleDefaults.Extension): Modifier =
    squircleBackground(color, cornerRadius, cornerRadius, cornerRadius, cornerRadius, extension)

@Composable
fun Modifier.squircleBackground(
    color: Color, topStart: Dp, topEnd: Dp, bottomEnd: Dp, bottomStart: Dp,
    extension: Float = SquircleDefaults.Extension,
): Modifier = background(color, g2(topStart, topEnd, bottomEnd, bottomStart, extension))

@Composable
fun Modifier.squircleClip(cornerRadius: Dp, extension: Float = SquircleDefaults.Extension): Modifier =
    squircleClip(cornerRadius, cornerRadius, cornerRadius, cornerRadius, extension)

@Composable
fun Modifier.squircleClip(
    topStart: Dp, topEnd: Dp, bottomEnd: Dp, bottomStart: Dp,
    extension: Float = SquircleDefaults.Extension,
): Modifier = clip(g2(topStart, topEnd, bottomEnd, bottomStart, extension))

@Composable
fun Modifier.squircleSurface(color: Color, cornerRadius: Dp, extension: Float = SquircleDefaults.Extension): Modifier =
    squircleSurface(color, cornerRadius, cornerRadius, cornerRadius, cornerRadius, extension)

@Composable
fun Modifier.squircleSurface(
    color: Color, topStart: Dp, topEnd: Dp, bottomEnd: Dp, bottomStart: Dp,
    extension: Float = SquircleDefaults.Extension,
): Modifier = clip(g2(topStart, topEnd, bottomEnd, bottomStart, extension)).background(color)

@Composable
fun Modifier.absoluteSquircleBackground(
    color: Color, topLeft: Dp, topRight: Dp, bottomRight: Dp, bottomLeft: Dp,
    extension: Float = SquircleDefaults.Extension,
): Modifier = background(color, g2(topLeft, topRight, bottomRight, bottomLeft, extension, true))

@Composable
fun Modifier.absoluteSquircleClip(
    topLeft: Dp, topRight: Dp, bottomRight: Dp, bottomLeft: Dp,
    extension: Float = SquircleDefaults.Extension,
): Modifier = clip(g2(topLeft, topRight, bottomRight, bottomLeft, extension, true))

@Composable
fun Modifier.absoluteSquircleSurface(
    color: Color, topLeft: Dp, topRight: Dp, bottomRight: Dp, bottomLeft: Dp,
    extension: Float = SquircleDefaults.Extension,
): Modifier = clip(g2(topLeft, topRight, bottomRight, bottomLeft, extension, true)).background(color)
