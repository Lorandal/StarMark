package dev.starmark.starmark

import android.graphics.Bitmap
import androidx.annotation.Keep
import java.io.Closeable
import java.io.File

/**
 * Sequential, single-pass JPEG encoder. Each strip contains opaque RGBA_8888 pixels in the
 * colour space described by [icc]. Native bitmap access preserves those channel values;
 * no intermediate sRGB bitmap or full-image coefficient buffer is created.
 *
 * [finish] requires exactly [height] rows. Closing without finishing deletes the partial file.
 * The caller owns (and may reuse/recycle) each bitmap after [write] returns.
 */
@Keep
internal class StreamingJpeg(
    private val file: File,
    private val width: Int,
    private val height: Int,
    quality: Int = 100,
    icc: ByteArray? = null,
) : Closeable {
    private var handle: Long
    private var rows = 0
    private var finished = false

    init {
        require(width in 1..32768 && height in 1..32768) { "Invalid JPEG dimensions" }
        require(quality in 0..100) { "Invalid JPEG quality" }
        require(icc == null || icc.size in 1..(65519 * 255)) { "Invalid JPEG ICC size" }
        handle = nativeOpen(file.absolutePath, width, height, quality, icc)
        check(handle != 0L) { "JPEG encoder did not open" }
    }

    @Synchronized
    fun write(bitmap: Bitmap) {
        check(handle != 0L && !finished) { "JPEG encoder is closed" }
        require(!bitmap.isRecycled && bitmap.config == Bitmap.Config.ARGB_8888) { "JPEG strip must be software RGBA_8888" }
        require(bitmap.width == width && bitmap.height <= height - rows) { "JPEG strip exceeds remaining rows or has wrong width" }
        try {
            nativeWrite(handle, bitmap)
            rows += bitmap.height
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    @Synchronized
    fun finish() {
        check(handle != 0L && !finished) { "JPEG encoder is closed" }
        check(rows == height) { "JPEG is incomplete: $rows of $height rows" }
        val active = handle
        handle = 0L
        try {
            nativeFinish(active)
            finished = true
        } finally {
            if (!finished) file.delete()
        }
    }

    @Synchronized
    override fun close() {
        val active = handle
        if (active == 0L) return
        handle = 0L
        try {
            nativeAbort(active)
        } finally {
            if (!finished) file.delete()
        }
    }

    private external fun nativeOpen(path: String, width: Int, height: Int, quality: Int, icc: ByteArray?): Long
    private external fun nativeWrite(handle: Long, bitmap: Bitmap)
    private external fun nativeFinish(handle: Long)
    private external fun nativeAbort(handle: Long)

    companion object {
        init { System.loadLibrary("starmark_jpeg") }
    }
}
