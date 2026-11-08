package dev.starmark.starmark.xiaomi

import android.graphics.Bitmap
import android.os.Build
import com.xiaomi.milab.filtersdk.CandySDK
import dev.starmark.starmark.ui.PhotoMaterialBitmap
import kotlin.math.max
import kotlin.math.min

/** Xiaomi's image filter, separate from the View-only system glass material. */
object XiaomiFilterBlur {
    private val xiaomi by lazy {
        listOf(Build.MANUFACTURER, Build.BRAND).any { name ->
            name.equals("xiaomi", true) || name.equals("redmi", true) || name.equals("poco", true)
        }
    }

    /** Presence does not guarantee the GPU filter can initialize; callers must handle failure. */
    fun isAvailable(): Boolean = xiaomi && Build.SUPPORTED_ABIS.contains("arm64-v8a") &&
        runCatching { Class.forName("com.xiaomi.milab.filtersdk.CandySDK") }.isSuccess

    /** Returns an independently owned bitmap. Factory defaults apply Xiaomi's
     * luminance transform; custom blur leaves tint compositing to the editor.
     * A null return means the caller must use its existing Android blur fallback. */
    fun process(source: Bitmap, light: Boolean, radius: Float = 4f, applyTone: Boolean = true): Bitmap? {
        if (!isAvailable() || source.isRecycled || source.width <= 0 || source.height <= 0) return null
        if (!radius.isFinite() || radius <= 0f || radius > 64f) return null
        var result: Bitmap? = null
        return try {
            // The vendor level=3 kernel is calibrated at 100px; its radius does
            // not scale linearly with input size. Keep that approved blur and
            // reconstruct/tone at the larger output resolution below.
            val scale = min(1f, 100f / max(source.width, source.height))
            val scaled = Bitmap.createScaledBitmap(source, max(1, (source.width * scale).toInt()), max(1, (source.height * scale).toInt()), false)
            try { result = scaled.copy(Bitmap.Config.ARGB_8888, true) }
            finally { if (scaled !== source) scaled.recycle() }
            val bitmap = checkNotNull(result)
            CandySDK().use { filter ->
                filter.configure("TiltBlurEffect;level=3", "TiltBlurEffect;;BlurRadius=$radius")
                filter.process(bitmap)
            }
            // ChangeLuminance on the tiny 8-bit buffer quantized dark values
            // before enlargement. Apply the same half-range tone in the final
            // GPU pass, then dither once before the PNG/Flutter readback.
            val finished = PhotoMaterialBitmap.finish(bitmap, max(source.width, source.height), light.takeIf { applyTone })
            bitmap.recycle()
            result = null
            finished
        } catch (failure: Throwable) {
            android.util.Log.w("StarMarkXiaomiBlur", "Xiaomi image filter unavailable; using Android fallback", failure)
            result?.recycle()
            null
        }
    }
}
