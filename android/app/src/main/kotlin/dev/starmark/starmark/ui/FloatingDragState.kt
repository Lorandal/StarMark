package dev.starmark.starmark.ui

/** A pointer owns one preview and at most one release. No navigation is emitted by move. */
internal class FloatingDragState {
    private var track = FloatingNavigationTrack(0f, 1)
    private var height = 0f
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
        track = FloatingNavigationTrack(width, count)
        this.height = height
        if (!contains(x, y)) return false
        active = true
        nearestIndex = track.nearest(x)
        center = track.center(nearestIndex)
        grabOffset = x - center
        return true
    }

    fun move(x: Float, y: Float) {
        if (!active || !x.isFinite() || !y.isFinite()) return
        center = (x - grabOffset).coerceIn(track.center(0), track.center(track.count - 1))
        nearestIndex = track.nearest(x)
    }

    fun release(x: Float, y: Float): Int? {
        if (!active) return null
        move(x, y)
        val result = if (contains(x, y)) nearestIndex else null
        cancel()
        return result
    }

    fun cancel() { active = false }
    private fun contains(x: Float, y: Float) = x.isFinite() && y.isFinite() && x in 0f..track.width && y in 0f..height
}
