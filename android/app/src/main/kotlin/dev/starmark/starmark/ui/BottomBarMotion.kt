package dev.starmark.starmark.ui

internal data class BottomBarFrame(
    val navigationAlpha: Float,
    val navigationScale: Float,
    val actionsAlpha: Float,
    val actionsOffsetDp: Float,
)

/** FileExplorer switches two whole surfaces, not custom content inside one resizing shell. */
internal class BottomBarMotion(actions: Boolean, visible: Boolean) {
    private val navigation = SpringValue(if (visible && !actions) 1f else 0f)
    private val menuOffset = SpringValue(if (visible && actions) 0f else 15f)
    private var menuAlpha = if (visible && actions) 1f else 0f
    private var menuStart = menuAlpha
    private var menuTarget = menuAlpha
    private var navigationDelay = 0f
    private var menuDelay = 0f
    private var menuElapsed = .15f
    private var targetActions = actions
    private var targetVisible = visible
    val frame get() = BottomBarFrame(navigation.value.coerceIn(0f, 1f),
        .6f + .4f * navigation.value, menuAlpha, menuOffset.value)
    val running get() = navigation.running || menuOffset.running || menuAlpha != menuTarget

    fun retarget(actions: Boolean, visible: Boolean, immediate: Boolean = false) {
        val nav = visible && !actions
        val menu = visible && actions
        if (immediate) {
            navigation.snapTo(if (nav) 1f else 0f)
            menuOffset.snapTo(if (menu) 0f else 15f)
            menuAlpha = if (menu) 1f else 0f
            menuStart = menuAlpha; menuTarget = menuAlpha
            navigationDelay = 0f; menuDelay = 0f; menuElapsed = .15f
        } else if (targetActions != actions || targetVisible != visible) {
            navigation.retarget(if (nav) 1f else 0f, FolmeSpring(1f, .30f))
            navigationDelay = if (nav) .1f else 0f
            menuOffset.retarget(if (menu) 0f else 15f, FolmeSpring(.82f, .30f))
            menuStart = menuAlpha
            menuTarget = if (menu) 1f else 0f
            // The recording includes ~50ms of action-mode setup before the menu's
            // own 100ms delay. Keep that gap even when our Compose content is ready.
            menuDelay = if (menu) .15f else 0f
            menuElapsed = 0f
        }
        targetActions = actions; targetVisible = visible
    }

    fun step(seconds: Float) {
        if (seconds <= 0f || !seconds.isFinite()) return
        val navTime = (seconds - navigationDelay).coerceAtLeast(0f)
        navigationDelay = (navigationDelay - seconds).coerceAtLeast(0f)
        navigation.step(navTime)
        val menuTime = (seconds - menuDelay).coerceAtLeast(0f)
        menuDelay = (menuDelay - seconds).coerceAtLeast(0f)
        menuOffset.step(menuTime)
        menuElapsed += menuTime
        val fraction = (menuElapsed / .15f).coerceIn(0f, 1f)
        menuAlpha = if (fraction == 1f) menuTarget else menuStart + (menuTarget - menuStart) * fraction
    }
}
