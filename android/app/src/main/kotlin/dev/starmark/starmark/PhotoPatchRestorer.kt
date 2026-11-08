package dev.starmark.starmark

import android.graphics.*
import android.os.Build
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.ceil
import kotlin.math.floor

/** Region decode and sequential encoding: no full camera photo or rotated duplicate in memory. */
internal object PhotoPatchRestorer {
    fun write(source: File, output: File, bytes: ByteArray, width: Int, height: Int,
        rotation: Int, patchValues: FloatArray, hdr: Boolean, motion: Boolean): ColorSpace {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth == width && bounds.outHeight == height)
        val space = bounds.outColorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
        val uprightWidth = if (rotation % 180 == 0) width else height
        val uprightHeight = if (rotation % 180 == 0) height else width
        val upright = Matrix().apply {
            if (rotation != 0) {
                postRotate(rotation.toFloat())
                postTranslate(if (rotation == 270) 0f else uprightWidth.toFloat(),
                    if (rotation == 90) 0f else uprightHeight.toFloat())
            }
        }
        val basePlacement = if (hdr) Matrix() else upright
        val patchPlacement = Matrix().apply { setValues(patchValues) }
        if (hdr) {
            val inverse = Matrix()
            require(upright.invert(inverse))
            patchPlacement.postConcat(inverse)
        }
        val outWidth = if (hdr) width else uprightWidth
        val outHeight = if (hdr) height else uprightHeight
        @Suppress("DEPRECATION")
        val base = requireNotNull(BitmapRegionDecoder.newInstance(source.path, false))
        var patch: BitmapRegionDecoder? = null
        try {
            @Suppress("DEPRECATION")
            val patchDecoder = requireNotNull(BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false))
            patch = patchDecoder
            val icc = TiledPhotoExporter.iccProfile(space)
            fun strips(write: (Bitmap) -> Unit) {
                var y = 0
                while (y < outHeight) {
                    val rows = minOf(128, outHeight - y)
                    val strip = Bitmap.createBitmap(outWidth, rows, Bitmap.Config.ARGB_8888, false, space)
                    try {
                        val canvas = Canvas(strip)
                        drawRegion(canvas, base, basePlacement, outWidth, y, rows, space, false)
                        drawRegion(canvas, patchDecoder, patchPlacement, outWidth, y, rows, space, true)
                        write(strip)
                    } finally { strip.recycle() }
                    y += rows
                }
            }
            if (hdr || motion) StreamingJpeg(output, outWidth, outHeight, 100, icc).use { encoder ->
                strips(encoder::write); encoder.finish()
            } else StreamingPng(output, outWidth, outHeight, icc).use { encoder ->
                strips(encoder::write); encoder.finish()
            }
        } finally { patch?.recycle(); base.recycle() }
        if (hdr) {
            // The raw-coordinate canvas did not move: retain the camera's exact compressed
            // gainmap and parameters, without decoding or resampling the HDR plane.
            val grafted = File.createTempFile("patch-hdr-", ".jpg", output.parentFile)
            try {
                PhotoContainers.attachRecoveryGainmap(output, source, grafted)
                Files.move(grafted.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { grafted.delete() }
        }
        return space
    }

    private fun drawRegion(canvas: Canvas, decoder: BitmapRegionDecoder, placement: Matrix,
        width: Int, top: Int, rows: Int, space: ColorSpace, opaquePatch: Boolean) {
        val inverse = Matrix()
        require(placement.invert(inverse)) { "恢复坐标矩阵无效" }
        val needed = RectF(0f, top.toFloat(), width.toFloat(), (top + rows).toFloat())
        inverse.mapRect(needed)
        // One source pixel halo preserves bilinear filtering at strip boundaries.
        val rect = Rect((floor(needed.left).toInt() - 1).coerceAtLeast(0),
            (floor(needed.top).toInt() - 1).coerceAtLeast(0),
            (ceil(needed.right).toInt() + 1).coerceAtMost(decoder.width),
            (ceil(needed.bottom).toInt() + 1).coerceAtMost(decoder.height))
        if (rect.isEmpty) return
        require(rect.width().toLong() * rect.height() <= 8_000_000L) { "局部恢复区域超过分块内存预算" }
        val bitmap = requireNotNull(decoder.decodeRegion(rect, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888; inPreferredColorSpace = space
        })) { "恢复图像分块解码失败" }
        try {
            require(!opaquePatch || !bitmap.hasAlpha()) { "局部图像存在不确定的透明像素" }
            if (Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap()) bitmap.gainmap = null
            val matrix = Matrix(placement).apply { preTranslate(rect.left.toFloat(), rect.top.toFloat()); postTranslate(0f, -top.toFloat()) }
            canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        } finally { bitmap.recycle() }
    }
}
