package dev.starmark.starmark

import android.graphics.*
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt

/** Crop verified camera rectangles without allocating a 50/200MP RGBA image. */
internal object PhotoFrameCropper {
    fun write(source: File, destination: File, width: Int, height: Int, frame: XiaomiFrameCrop, hdr: Boolean) {
        frame.validate(width, height)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth == width && bounds.outHeight == height)
        val space = bounds.outColorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
        @Suppress("DEPRECATION")
        val decoder = requireNotNull(BitmapRegionDecoder.newInstance(source.path, false))
        try {
            StreamingJpeg(destination, frame.width, frame.height, 100, TiledPhotoExporter.iccProfile(space)).use { encoder ->
                var y = 0
                while (y < frame.height) {
                    val rows = minOf(128, frame.height - y)
                    val bitmap = requireNotNull(decoder.decodeRegion(Rect(frame.x, frame.y + y,
                        frame.x + frame.width, frame.y + y + rows), BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888; inPreferredColorSpace = space
                    }))
                    try {
                        if (Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap()) bitmap.gainmap = null
                        encoder.write(bitmap)
                    } finally { bitmap.recycle() }
                    y += rows
                }
                encoder.finish()
            }
        } finally { decoder.recycle() }
        if (hdr) attachCroppedGainmap(source, destination, width, height, frame)
    }

    private fun attachCroppedGainmap(source: File, destination: File, width: Int, height: Int, frame: XiaomiFrameCrop) {
        require(Build.VERSION.SDK_INT >= 34)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888; inSampleSize = 1
            while (maxOf(width, height) / inSampleSize > 512) inSampleSize *= 2
        }
        val sample = requireNotNull(BitmapFactory.decodeFile(source.path, options))
        var carrierBitmap: Bitmap? = null; var fullMap: Bitmap? = null; var croppedMap: Bitmap? = null
        val carrier = File.createTempFile("recovery-hdr-", ".jpg", destination.parentFile)
        val grafted = File.createTempFile("recovery-grafted-", ".jpg", destination.parentFile)
        try {
            val parameters = requireNotNull(sample.gainmap) { "原片 HDR 参数不可用" }
            val range = requireNotNull(PhotoContainers.gainmapImageRange(source)) { "原片 HDR 图像不可用" }
            require(range.length in 1..(64L * 1024 * 1024))
            val encoded = RandomAccessFile(source, "r").use { input ->
                input.seek(range.offset); ByteArray(range.length.toInt()).also(input::readFully)
            }
            val mapBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(encoded, 0, encoded.size, mapBounds)
            require(mapBounds.outWidth > 0 && mapBounds.outHeight > 0 &&
                mapBounds.outWidth.toLong() * mapBounds.outHeight <= 16_777_216) { "HDR 恢复图超过像素预算" }
            fullMap = requireNotNull(BitmapFactory.decodeByteArray(encoded, 0, encoded.size))
            // Parameter parsing uses a bounded base decode, but the gain pixels use the full
            // original secondary JPEG. Never substitute the sampled thumbnail gainmap.
            parameters.gainmapContents = fullMap
            val cropped = PhotoHdr.cropGainmap(parameters, width, height,
                Rect(frame.x, frame.y, frame.x + frame.width, frame.y + frame.height))
            croppedMap = cropped.gainmapContents
            sample.gainmap = null
            val left = (frame.x.toDouble() * sample.width / width).roundToInt()
            val top = (frame.y.toDouble() * sample.height / height).roundToInt()
            val right = ((frame.x + frame.width).toDouble() * sample.width / width).roundToInt()
            val bottom = ((frame.y + frame.height).toDouble() * sample.height / height).roundToInt()
            carrierBitmap = Bitmap.createBitmap(sample, left, top, right - left, bottom - top)
            carrierBitmap.gainmap = cropped
            carrier.outputStream().use { check(carrierBitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            PhotoContainers.attachGainmap(destination, carrier, grafted)
            Files.move(grafted.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            check(PhotoHdr.inspect(destination).hasGainmap == true) { "恢复后的 HDR 验证失败" }
        } finally {
            if (carrierBitmap !== sample) carrierBitmap?.recycle()
            sample.recycle(); fullMap?.recycle(); croppedMap?.recycle()
            carrier.delete(); grafted.delete()
        }
    }
}
