package dev.starmark.starmark

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Lossless opaque RGB strips, retaining native colour-space channel values and ICC. */
internal class StreamingPng(
    private val file: File, private val width: Int, private val height: Int, icc: ByteArray?,
) : Closeable {
    private val output = DataOutputStream(file.outputStream().buffered(65536))
    private val deflater = Deflater(Deflater.BEST_SPEED)
    private val compressed = ByteArray(65536)
    private val row = ByteArray(1 + width * 3)
    private var pixels = ByteArray(0)
    private var rows = 0
    private var finished = false
    private var closed = false

    init {
        try {
            require(width in 1..32768 && height in 1..32768)
            output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            chunk("IHDR", ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
                it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, 2, 0, 0, 0))
            } }.toByteArray())
            if (icc == null) chunk("sRGB", byteArrayOf(0)) else {
                val payload = ByteArrayOutputStream()
                payload.write("StarMark\u0000\u0000".toByteArray(Charsets.US_ASCII))
                DeflaterOutputStream(payload).use { it.write(icc) }
                chunk("iCCP", payload.toByteArray())
            }
        } catch (error: Throwable) { close(); throw error }
    }

    fun write(bitmap: Bitmap) {
        check(!closed && !finished)
        require(bitmap.config == Bitmap.Config.ARGB_8888 && !bitmap.hasAlpha() && bitmap.width == width &&
            bitmap.height <= height - rows) { "Invalid opaque PNG strip" }
        if (pixels.size < bitmap.byteCount) pixels = ByteArray(bitmap.byteCount)
        // copyPixelsToBuffer retains P3 values; getPixels() would silently convert them to sRGB.
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(pixels))
        for (y in 0 until bitmap.height) {
            row[0] = 1 // PNG Sub filter; no previous full row/strip retained.
            var r = 0; var g = 0; var b = 0
            for (x in 0 until width) {
                val at = y * bitmap.rowBytes + x * 4
                val nextR = pixels[at].toInt() and 255
                val nextG = pixels[at + 1].toInt() and 255
                val nextB = pixels[at + 2].toInt() and 255
                val out = 1 + x * 3
                row[out] = (nextR - r).toByte(); row[out + 1] = (nextG - g).toByte(); row[out + 2] = (nextB - b).toByte()
                r = nextR; g = nextG; b = nextB
            }
            deflater.setInput(row)
            while (!deflater.needsInput()) drain()
        }
        rows += bitmap.height
    }

    fun finish() {
        check(!closed && !finished && rows == height) { "Incomplete PNG" }
        deflater.finish()
        while (!deflater.finished()) drain()
        chunk("IEND", byteArrayOf())
        output.flush()
        finished = true
    }

    private fun drain() {
        val count = deflater.deflate(compressed)
        if (count > 0) chunk("IDAT", compressed, count)
        else check(deflater.needsInput() || deflater.finished()) { "PNG encoder stalled" }
    }

    private fun chunk(type: String, bytes: ByteArray, count: Int = bytes.size) {
        val name = type.toByteArray(Charsets.US_ASCII)
        output.writeInt(count); output.write(name); output.write(bytes, 0, count)
        val crc = CRC32().apply { update(name); update(bytes, 0, count) }
        output.writeInt(crc.value.toInt())
    }

    override fun close() {
        if (closed) return
        closed = true
        try { deflater.end(); output.close() } finally { if (!finished) file.delete() }
    }
}
