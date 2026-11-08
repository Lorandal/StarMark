package dev.starmark.starmark

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

internal fun shouldReadOriginalPhoto(scheme: String?, authority: String?, mediaLocationGranted: Boolean): Boolean =
    mediaLocationGranted && scheme == "content" && authority == "media"

internal fun copyPhotoSource(destination: File, original: (() -> InputStream?)?, regular: () -> InputStream?, maxBytes: Long = 250L * 1024 * 1024) {
    fun copy(open: () -> InputStream?) {
        // Each attempt starts with an empty file, including a shorter fallback
        // after an original stream fails part way through reading.
        destination.outputStream().use { output ->
            val input = open() ?: throw FileNotFoundException("读取照片失败")
            input.use {
                val buffer = ByteArray(65536)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "源图片超过 250 MiB" }
                    output.write(buffer, 0, count)
                }
            }
        }
    }
    if (original != null) {
        try {
            copy(original)
            return
        } catch (_: SecurityException) {
            // Media location access may have been revoked after selection.
        } catch (_: UnsupportedOperationException) {
            // Some providers cannot supply unredacted originals.
        } catch (_: IOException) {
            // Includes a null or interrupted original stream.
        }
    }
    copy(regular)
}
