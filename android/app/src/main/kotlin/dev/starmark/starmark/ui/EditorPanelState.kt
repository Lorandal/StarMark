package dev.starmark.starmark.ui

/** One slider gesture shares an id and the document revision at its start. */
internal class AdjustmentSession {
    var id: String? = null
        private set
    var revision: Long = 0
        private set
    var latest: Float = 0f
        private set
    var blockedUntilFinish: Boolean = false
        private set

    fun begin(interactionId: String, baseRevision: Long, initialValue: Float): Boolean {
        if (blockedUntilFinish || id != null) return false
        id = interactionId
        revision = baseRevision
        latest = initialValue
        return true
    }

    fun update(value: Float) {
        if (id != null && !blockedUntilFinish) latest = value
    }

    fun invalidateRevision(currentRevision: Long): Boolean {
        if (id == null || revision == currentRevision) return false
        id = null
        blockedUntilFinish = true
        return true
    }

    fun finish() {
        id = null
        blockedUntilFinish = false
    }
}

internal fun borderTextColor(currentColor: Long, originalBackground: String, selectedBackground: String): Long =
    if (selectedBackground == originalBackground) currentColor
    else if (selectedBackground == "white" || selectedBackground == "lightBlur" || selectedBackground == "lightGlass") 0xff181818L else 0xffffffffL

internal enum class WatermarkSizePreset(val title: String, val scale: Double) {
    Small("小", .85), Medium("中", 1.0), Large("大", 1.15),
}

internal fun watermarkSizePreset(scale: Float): WatermarkSizePreset? =
    WatermarkSizePreset.entries.firstOrNull { kotlin.math.abs(it.scale - scale.toDouble()) < .001 }
