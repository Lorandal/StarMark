package dev.starmark.starmark.ui

import android.graphics.*
import android.hardware.HardwareBuffer
import android.media.ImageReader
import kotlin.math.max
import kotlin.math.roundToInt

/** Bounded material-only readback. Quantize after resampling and tone, not before.
 * The deterministic sub-level dither breaks coherent contour lines; it is never
 * applied to the original photo, lettering, or a separately rendered export tile. */
internal object PhotoMaterialBitmap {
    const val DITHER = """
        float noise(float2 p) {
            return fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715))));
        }
        float dither(float2 p) {
            return (noise(floor(p)) + noise(floor(p) + float2(37, 17)) - 1.0) / 255.0;
        }
    """
    private const val FINISH = """
        uniform shader scene;
        uniform float tone;
        uniform float light;
    """ + DITHER + """
        half4 main(float2 p) {
            float4 c = float4(scene.eval(p));
            c.rgb = mix(c.rgb, c.rgb * 0.5 + light * 0.5 * c.a, tone);
            return half4(clamp(c.rgb + dither(p) * c.a, 0.0, c.a), c.a);
        }
    """

    fun finish(source: Bitmap, outputEdge: Int = max(source.width, source.height), light: Boolean? = null): Bitmap {
        val scale = minOf(1400, outputEdge).toFloat() / max(source.width, source.height)
        val width = max(1, (source.width * scale).roundToInt())
        val height = max(1, (source.height * scale).roundToInt())
        val node = RenderNode("starmark-photo-material-finish").apply { setPosition(0, 0, width, height) }
        try {
            node.beginRecording().drawBitmap(source, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
            node.endRecording()
            val shader = RuntimeShader(FINISH).apply {
                setFloatUniform("tone", if (light == null) 0f else 1f)
                setFloatUniform("light", if (light == true) 1f else 0f)
            }
            node.setRenderEffect(RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(shader, "scene"),
                RenderEffect.createBlurEffect(.75f, .75f, Shader.TileMode.CLAMP)))
            return rasterize(node, width, height)
        } finally { node.discardDisplayList() }
    }

    fun rasterize(node: RenderNode, width: Int, height: Int): Bitmap {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            val status = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            check(status == HardwareRenderer.SYNC_OK || status == HardwareRenderer.SYNC_FRAME_DROPPED)
            reader.acquireNextImage().use { image ->
                val buffer = requireNotNull(image?.hardwareBuffer)
                try {
                    val hardware = requireNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)))
                    return try { requireNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false)) } finally { hardware.recycle() }
                } finally { buffer.close() }
            }
        } finally { renderer.destroy(); reader.close() }
    }
}
