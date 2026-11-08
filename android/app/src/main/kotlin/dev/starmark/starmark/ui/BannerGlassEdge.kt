package dev.starmark.starmark.ui

import android.graphics.PathMeasure
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect

/** Optical response only: the caller's original G2 clip remains the sole boundary.
 * Sample the actual G2 path once; do not substitute a round-rect SDF or change radius.
 * A small corner table gives a continuous normal without a per-frame CPU path walk. */
private object BannerGlassEdge {
    private const val Segments = 32
    val corner: FloatArray by lazy {
        val radius = 1000f
        val width = 8000f
        val outline = MiuixCornerShape(radius.dp).createOutline(Size(width, width), LayoutDirection.Ltr, Density(1f)) as Outline.Generic
        val measure = PathMeasure(outline.path.asAndroidPath(), true)
        val straight = width - 2f * radius * G2CornerGeometry.EXTENSION * 1.15f
        val cornerLength = (measure.length - 4f * straight) / 4f
        val position = FloatArray(2)
        val tangent = FloatArray(2)
        FloatArray((Segments + 1) * 4).also { samples ->
            for (i in 0..Segments) {
                measure.getPosTan(straight + cornerLength * i / Segments, position, tangent)
                samples[i * 4] = (width - position[0]) / radius
                samples[i * 4 + 1] = position[1] / radius
                samples[i * 4 + 2] = -tangent[0]
                samples[i * 4 + 3] = tangent[1]
            }
        }
    }

    const val Shader = """
        uniform shader content;
        uniform float2 bounds;
        uniform float radius;
        uniform float band;
        uniform float displacement;
        uniform float4 corner[33];
        half4 main(float2 p) {
            float2 q = min(p, bounds - p);
            float d = min(q.x, q.y);
            float2 n = q.x < q.y ? float2(1, 0) : float2(0, 1);
            float extent = corner[0].x * radius;
            if (q.x < extent && q.y < extent) {
                float best = 1e20;
                float2 v = q / radius;
                for (int i = 0; i < 32; ++i) {
                    float2 a = corner[i].xy;
                    float2 e = corner[i + 1].xy - a;
                    float t = clamp(dot(v - a, e) / max(dot(e, e), 1e-8), 0.0, 1.0);
                    float2 delta = v - a - t * e;
                    float dist2 = dot(delta, delta);
                    if (dist2 < best) {
                        best = dist2;
                        float2 tangent = normalize(mix(corner[i].zw, corner[i + 1].zw, t));
                        n = float2(tangent.y, -tangent.x);
                    }
                }
                d = sqrt(best) * radius;
            }
            if (d >= band) return content.eval(p);
            n *= float2(p.x < bounds.x * 0.5 ? 1 : -1, p.y < bounds.y * 0.5 ? 1 : -1);
            float t = clamp(d / band, 0.0, 1.0);
            float envelope = sin(t * 3.14159265);
            // Same gentle edge-only displacement as the shared portable glass.
            float2 sampleAt = clamp(p + n * displacement * envelope * envelope, float2(0.5), bounds - 0.5);
            half4 c = content.eval(sampleAt);
            float light = 0.42 + 0.58 * abs(dot(n, normalize(float2(0.6, 0.8))));
            float peak = exp(-pow((t - 0.16) / 0.15, 2.0));
            float highlight = 0.32 * light * peak * smoothstep(0.0, 0.08, t);
            c.rgb = mix(c.rgb, half3(c.a), half(highlight));
            return c;
        }
    """
}

/** The source contains only the photo and footer; text/buttons are never refracted.
 * No blur/downscale pass here: keep centre pixels exact and the thin edge full-resolution. */
internal fun Modifier.bannerGlassEdge(backdrop: Backdrop): Modifier = drawBackdrop(
    backdrop = backdrop, shape = { RectangleShape }, effects = {
        runtimeShaderEffect("starmark.banner.innerGlass", BannerGlassEdge.Shader, "content") {
            setFloatUniform("bounds", size.width, size.height)
            setFloatUniform("radius", 22.dp.toPx())
            setFloatUniform("band", 6.dp.toPx())
            setFloatUniform("displacement", 3.6.dp.toPx())
            setFloatUniform("corner", BannerGlassEdge.corner)
        }
    },
)

/** Draw outside the content clip, on the identical G2 path. Only the optical
 * coverage extends by half a dp; layout, source clip and touch bounds stay put. */
internal fun Modifier.bannerGlassBloom(): Modifier = drawWithCache {
    val outline = MiuixCornerShape(22.dp).createOutline(size, layoutDirection, this)
    val sheen = Brush.linearGradient(listOf(
        Color.White.copy(alpha = .52f), Color.White.copy(alpha = .16f),
        Color.White.copy(alpha = .12f), Color.White.copy(alpha = .40f),
    ))
    onDrawWithContent {
        drawContent()
        drawOutline(outline, Color.White.copy(alpha = .08f), style = Stroke(2.dp.toPx()))
        drawOutline(outline, sheen, style = Stroke(1.dp.toPx()))
    }
}
