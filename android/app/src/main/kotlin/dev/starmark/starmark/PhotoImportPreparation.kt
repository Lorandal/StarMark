package dev.starmark.starmark

import java.io.File
import android.graphics.BitmapFactory

/** Work only on the app's imported copy; never mutate the picked MediaStore original. */
internal object PhotoImportPreparation {
    data class Prepared(val source: File, val documentJson: String?, val factoryRestored: Boolean)

    fun prepare(source: File, autoRemove: Boolean, context: android.content.Context? = null): Prepared {
        var current = source
        var document: String? = null
        val recovered = File(source.parentFile, "unmarked-original.img")
        val restored = PhotoContainers.restoreOriginal(current, recovered)
        if (restored != null) {
            current = recovered
            document = restored.documentJson
            source.delete() // Just our temporary imported copy, now superseded by its original.
        }
        var factoryRestored = false
        if (autoRemove) {
            val destination = File(source.parentFile, "factory-restored.img")
            val result = XiaomiWatermarkRecovery.recover(current, destination, context)
            if (result != null) {
                current.delete()
                current = result.file
                factoryRestored = true
            }
        }
        return Prepared(current, document, factoryRestored)
    }

    fun features(source: File): Map<String, Any?> {
        val hdr = PhotoHdr.inspect(source)
        val container = PhotoContainers.inspect(source)
        return mapOf("hdr" to hdr.hasGainmap, "hdrStatus" to hdr.status,
            "hdrUncertain" to (hdr.hasGainmap == null && hdr.mayContainGainmap),
            "hdrPossible" to hdr.mayContainGainmap, "live" to (container.motion != null),
            "reversible" to (container.reversible != null), "sourceHasAlpha" to sourceHasAlpha(source))
    }

    private fun sourceHasAlpha(source: File): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, options)
        if (options.outMimeType == "image/jpeg") return false
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 64) options.inSampleSize *= 2
        val probe = BitmapFactory.decodeFile(source.path, options) ?: return false
        return try { probe.hasAlpha() } finally { probe.recycle() }
    }
}
