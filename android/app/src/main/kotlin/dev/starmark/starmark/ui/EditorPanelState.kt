package dev.starmark.starmark.ui

/** Pane and fold coordinates are in dp relative to the inset-adjusted editor body. */
internal data class EditorPaneBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

internal data class EditorFold(val vertical: Boolean, val start: Float, val end: Float)
internal data class EditorPanes(val preview: EditorPaneBounds, val controls: EditorPaneBounds, val sideBySide: Boolean)

/** The Flutter texture covers this area for the lifetime of a given pane size.
 * Only the photograph inside it changes bounds when a different watermark is painted. */
internal data class EditorPhotoViewport(val bounds: EditorPaneBounds, val horizontalBias: Float = 0f) {
    fun fit(aspectRatio: Float): EditorPaneBounds {
        val width = minOf(bounds.width, bounds.height * aspectRatio)
        val height = width / aspectRatio
        val left = bounds.left + (bounds.width - width) * ((horizontalBias + 1f) / 2f)
        val top = bounds.top + (bounds.height - height) / 2f
        return EditorPaneBounds(left, top, left + width, top + height)
    }
}

internal data class FoldPhotoAlignment(val centerYInPane: Float?, val outerInset: Float, val gapToCard: Float) {
    fun viewport(width: Float, height: Float, topPadding: Float): EditorPhotoViewport {
        val centerY = centerYInPane?.minus(topPadding)?.takeIf { it > 0f && it < height } ?: height / 2f
        val availableHeight = 2f * minOf(centerY, height - centerY)
        val outerMargin = maxOf(outerInset, gapToCard / 2f)
        val left = outerMargin - outerInset
        val right = width - (2f * outerMargin - gapToCard)
        val top = centerY - availableHeight / 2f
        // Divide spare horizontal space 1:2, including the outer inset and gap to the card.
        return EditorPhotoViewport(
            EditorPaneBounds(if (right > left) left else 0f, top, if (right > left) right else width, top + availableHeight),
            horizontalBias = -1f / 3f,
        )
    }
}

internal fun editorPaneLayout(
    width: Float, height: Float, landscape: Boolean, controlsMinimum: Float, fold: EditorFold? = null, rtl: Boolean = false,
    trailingSpace: Float = 0f,
): EditorPanes {
    var area = EditorPaneBounds(0f, 0f, width, height)
    if (fold != null) {
        val length = if (fold.vertical) width else height
        if (fold.end > 0f && fold.start < length) {
            val before = (fold.start - 8f).coerceIn(0f, length)
            val after = (fold.end + 8f).coerceIn(0f, length)
            val first = if (fold.vertical) area.copy(right = before) else area.copy(bottom = before)
            val second = if (fold.vertical) area.copy(left = after) else area.copy(top = after)
            if (fold.vertical) {
                val preview = if (rtl) second else first
                val controls = if (rtl) first else second
                if (preview.width >= 180f && controls.width >= 280f) return EditorPanes(preview, controls, sideBySide = true)
            } else if (first.height >= 96f && second.height >= 176f) {
                return EditorPanes(first, second, sideBySide = false)
            }
            // In a resized window the hinge can cut off only a thin strip. Keep both
            // panes on the usable side rather than drawing controls across that strip.
            area = if (before >= length - after) first else second
        }
    }
    val gap = 16f
    // The right pane paints to the edge. On narrow inner displays give it enough
    // width for 280dp of controls (180dp of photo in RTL) beside the floating tabs.
    val minimumWidth = if (trailingSpace > 0f) 280f + gap + 180f else 600f
    if (landscape && area.width >= minimumWidth) {
        val available = area.width - gap
        val tabWidth = trailingSpace.coerceIn(0f, available - 280f - 180f)
        val minimum = if (rtl) 280f else 280f + tabWidth
        val maximum = if (rtl) available - 180f - tabWidth else available - 180f
        val controlsWidth = (available / 2f).coerceAtMost(448f).coerceIn(minimum, maximum)
        val controls = if (rtl) area.copy(right = area.left + controlsWidth) else area.copy(left = area.right - controlsWidth)
        val preview = if (rtl) area.copy(left = controls.right + gap) else area.copy(right = controls.left - gap)
        return EditorPanes(preview, controls, sideBySide = true)
    }
    val edge = area.top + minOf(area.height * (if (area.height < 400f) .34f else .65f),
        (area.height - controlsMinimum).coerceAtLeast(0f))
    return EditorPanes(area.copy(bottom = edge), area.copy(top = edge), sideBySide = false)
}

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
