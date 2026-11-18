package dev.starmark.starmark.ui

/** A pointer owns one preview and at most one release. No navigation is emitted by move. */
internal class FloatingDragState(private val vertical: Boolean = false, private val rtl: Boolean = false) {
    private var track = FloatingNavigationTrack(0f, 1)
    private var thickness = 0f
    private var grabOffset = 0f
    var active = false
        private set
    var center = 0f
        private set
    var nearestIndex = 0
        private set

    fun begin(x: Float, y: Float, width: Float, height: Float, count: Int, enabled: Boolean): Boolean {
        cancel()
        if (!enabled || count < 1 || !width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return false
        track = FloatingNavigationTrack(if (vertical) height else width, count)
        thickness = if (vertical) width else height
        if (!contains(x, y)) return false
        active = true
        val along = positionAlongTrack(x, y)
        nearestIndex = track.nearest(along)
        center = track.center(nearestIndex)
        grabOffset = along - center
        return true
    }

    fun move(x: Float, y: Float) {
        if (!active || !x.isFinite() || !y.isFinite()) return
        val along = positionAlongTrack(x, y)
        center = (along - grabOffset).coerceIn(track.center(0), track.center(track.count - 1))
        nearestIndex = track.nearest(along)
    }

    fun release(x: Float, y: Float): Int? {
        if (!active) return null
        move(x, y)
        val result = if (contains(x, y)) nearestIndex else null
        cancel()
        return result
    }

    fun cancel() { active = false }

    private fun positionAlongTrack(x: Float, y: Float) = when {
        vertical -> y
        rtl -> track.width - x
        else -> x
    }

    private fun contains(x: Float, y: Float): Boolean {
        val across = if (vertical) x else y
        return positionAlongTrack(x, y) in 0f..track.width && across in 0f..thickness
    }
}
