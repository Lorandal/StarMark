package dev.starmark.starmark

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Shared picker-copy guard. Destination is opened only after the first quota probe. */
internal fun copyWatermarkZip(
    input: InputStream,
    openOutput: () -> OutputStream,
    declaredSize: Long?,
    availableBytes: () -> Long,
    maxBytes: Long = 1024L * 1024 * 1024,
) {
    val reserve = 1024L * 1024
    val declared = declaredSize?.takeIf { it >= 0L }
    if (declared != null && declared > maxBytes) throw IOException("水印集合超过 1 GiB")
    fun requireSpace(required: Long) {
        if (availableBytes() < required + reserve) throw IOException("存储空间不足，无法复制水印集合")
    }
    requireSpace(declared ?: 0L)
    openOutput().use { output ->
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) throw IOException("水印集合超过 1 GiB")
            requireSpace(read.toLong())
            output.write(buffer, 0, read)
        }
    }
}
