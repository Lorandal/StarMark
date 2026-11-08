package dev.starmark.starmark

import android.graphics.*
import android.os.Build
import java.io.File

/** Compose in the decoded original's ICC space, as the OEM bitmap pipeline does.
 * The Flutter layer is premultiplied sRGB; Canvas converts only its decoration pixels.
 */
internal object PhotoCompositor {
    fun compose(source: File, layer: Bitmap, rect: Rect, orientation: Int, background: Bitmap? = null, decodeEdge: Int? = null, linear: Boolean = false): Bitmap {
        require(!rect.isEmpty && rect.left >= 0 && rect.top >= 0 &&
            rect.right <= layer.width && rect.bottom <= layer.height)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth.toLong() * bounds.outHeight <= if (decodeEdge == null) 32_000_000 else TiledPhotoExporter.SOURCE_PIXELS) { "原图尺寸超出导出范围" }
        val original = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
            inPreferredConfig = if (linear) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
            if (decodeEdge != null) {
                require(decodeEdge in 1..4096)
                while (maxOf(bounds.outWidth,bounds.outHeight)/inSampleSize.coerceAtLeast(1) > decodeEdge) inSampleSize = inSampleSize.coerceAtLeast(1)*2
            }
        }) ?: error("原图无法解码")
        try {
            // Compose the SDR base only. HDR is attached once after decoration compositing.
            if (Build.VERSION.SDK_INT >= 34 && original.hasGainmap()) original.setGainmap(null)
            val outputSpace = original.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
            val target = Bitmap.createBitmap(layer.width, layer.height,
                if (linear) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888,
                false, if (linear) ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB) else outputSpace)
            try {
                val src = FloatArray(6)
                val dst = floatArrayOf(rect.left.toFloat(), rect.top.toFloat(),
                    rect.right.toFloat(), rect.top.toFloat(), rect.left.toFloat(), rect.bottom.toFloat())
                val raw = DoubleArray(2)
                for (i in 0..2) {
                    PhotoHdr.toRaw(if (i == 1) 1.0 else 0.0, if (i == 2) 1.0 else 0.0,
                        if (orientation == 0) 1 else orientation, raw)
                    src[2*i] = (raw[0] * original.width).toFloat()
                    src[2*i+1] = (raw[1] * original.height).toFloat()
                }
                val matrix = Matrix().apply { check(setPolyToPoly(src, 0, dst, 0, 3)) }
                Canvas(target).apply {
                    drawColor(Color.WHITE)
                    background?.let {
                        // Outside the photo the decoration already contains the background.
                        // Compositing it twice would darken translucent OEM materials.
                        save()
                        clipRect(rect)
                        drawBitmap(it, 0f, 0f, null)
                        restore()
                    }
                    drawBitmap(original, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
                    drawBitmap(layer, 0f, 0f, Paint(Paint.DITHER_FLAG))
                }
                if (!linear) return target
                // Convert actual pixel values back to the original output ICC
                // space after linear blending. Never relabel sRGB pixels as P3.
                val converted = Bitmap.createBitmap(target.width, target.height,
                    Bitmap.Config.RGBA_F16, false, outputSpace)
                try { Canvas(converted).drawBitmap(target, 0f, 0f, Paint(Paint.DITHER_FLAG)) }
                catch (failure: Throwable) { converted.recycle(); throw failure }
                target.recycle()
                return converted
            } catch (failure: Throwable) { target.recycle(); throw failure }
        } finally { original.recycle() }
    }
}
