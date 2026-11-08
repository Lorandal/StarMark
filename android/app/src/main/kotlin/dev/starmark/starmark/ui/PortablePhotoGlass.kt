package dev.starmark.starmark.ui

import android.graphics.*
import kotlin.math.max
import kotlin.math.roundToInt

/** Independent offline background renderer. Never consumes UI sources or screenshots.
 * The full background is rendered once, bounded to 1400px, and reused by every export tile.
 * Shader coordinates are normalized against full-photo dimensions, not tile dimensions. */
internal object PortablePhotoGlass {
    fun supported() = android.os.Build.VERSION.SDK_INT >= 33
    internal const val SHADER = """
        uniform shader scene;
        uniform float2 size;
        uniform float light;
        uniform float glass;
        uniform float tone;
        uniform float4 grading;
        uniform float tintAlpha;
        uniform float2 optics;
    """ + PhotoMaterialBitmap.DITHER + """
        // Haze-compatible grading: linear-sRGB chroma, then white point,
        // contrast and neutral tint. No opaque frame or screen-space source.
        float3 grade(float3 color) {
            float3 linear = toLinearSrgb(color);
            float luminance = dot(linear, float3(0.2126, 0.7152, 0.0722));
            float3 delta = linear - luminance;
            float3 room = mix(float3(luminance), float3(1.0 - luminance), step(0.0, delta));
            float3 limits = room / max(abs(delta), float3(0.00001));
            float chroma = max(1.0, min(grading.z, min(limits.x, min(limits.y, limits.z))));
            color = fromLinearSrgb(luminance + delta * chroma);
            color = mix(color, float3(grading.y > 0.0 ? 1.0 : 0.0), abs(grading.y));
            color = clamp((color - 0.5) * (1.0 + grading.x) + 0.5, 0.0, 1.0);
            return mix(color, float3(grading.w), tintAlpha);
        }
        half4 main(float2 p) {
            float2 edgeDistance = min(p, size - p);
            float edge = min(edgeDistance.x, edgeDistance.y);
            float band = optics.x * min(size.x, size.y) / 360.0;
            float t = clamp(edge / band, 0.0, 1.0);
            float envelope = sin(t * 3.14159265);
            float2 normal = edgeDistance.x < edgeDistance.y ? float2(1, 0) : float2(0, 1);
            normal *= float2(p.x < size.x * 0.5 ? 1 : -1, p.y < size.y * 0.5 ? 1 : -1);
            float2 q = p + normal * (optics.y * min(size.x, size.y) / 360.0) * envelope * envelope * glass;
            float4 c = float4(scene.eval(clamp(q, float2(0.5), size - 0.5)));
            if (tone > 0.5) c.rgb = glass > 0.5 ? grade(c.rgb) : mix(c.rgb, float3(light), light > 0.5 ? 0.22 : 0.30);
            // Refraction supplies the local edge response. Do not add a white
            // sine band: it reads as an inset rectangular outline on flat photos.
            return half4(clamp(float3(c.rgb) + dither(p), 0.0, 1.0), 1.0);
        }
    """
    fun render(source: Bitmap, targetWidth: Int, targetHeight: Int, light: Boolean, glass: Boolean = true, radius: Float? = null, applyTone: Boolean = true): Bitmap? {
        require(dev.starmark.starmark.TiledPhotoExporter.validSize(targetWidth, targetHeight))
        if (!supported()) return null
        val scale = minOf(1f, 1400f / max(targetWidth, targetHeight))
        val width = max(1, (targetWidth * scale).roundToInt())
        val height = max(1, (targetHeight * scale).roundToInt())
        val node = RenderNode("starmark-portable-photo-background").apply { setPosition(0, 0, width, height) }
        try {
            val canvas = node.beginRecording()
            val cropScale = max(width.toFloat()/source.width, height.toFloat()/source.height)
            canvas.drawBitmap(source, null, RectF((width-source.width*cropScale)/2, (height-source.height*cropScale)/2,
                (width+source.width*cropScale)/2, (height+source.height*cropScale)/2), Paint(Paint.FILTER_BITMAP_FLAG))
            node.endRecording()
            val shader = RuntimeShader(SHADER).apply {
                setFloatUniform("size", width.toFloat(), height.toFloat())
                setFloatUniform("light", if (light) 1f else 0f)
                setFloatUniform("glass", if (glass) 1f else 0f)
                setFloatUniform("tone", if (applyTone) 1f else 0f)
                setFloatUniform("grading", PortableGlassRecipe.contrast(!light), PortableGlassRecipe.whitePoint(!light),
                    PortableGlassRecipe.chroma, Color.red(PortableGlassRecipe.tint(!light).toInt()) / 255f)
                setFloatUniform("tintAlpha", PortableGlassRecipe.tintAlpha)
                setFloatUniform("optics", PortableGlassRecipe.edgeBandDp, PortableGlassRecipe.edgeDisplacementDp)
            }
            val fraction = radius ?: if (glass) PortableGlassRecipe.blurDp(!light) / 360f else .012f
            val blur = (fraction * minOf(width, height)).coerceIn(.1f, 64f)
            node.setRenderEffect(RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "scene"),
                RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)))
            return PhotoMaterialBitmap.rasterize(node, width, height)
        } catch (error: Exception) {
            android.util.Log.w("StarMarkPhotoGlass", "Offline glass unavailable", error)
            return null
        } finally { node.discardDisplayList() }
    }
}
