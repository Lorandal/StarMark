package dev.starmark.starmark.ui

import android.content.pm.ActivityInfo
import android.util.DisplayMetrics
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.app.MultiWindowModeChangedInfo
import androidx.core.util.Consumer

private const val LargeScreenMinDp = 600f

internal data class EditorWindow(val largeDisplay: Boolean, val wide: Boolean, val landscapeWorkspace: Boolean, val sideNavigation: Boolean)

internal fun editorWindow(displayShortDp: Float, widthDp: Float, heightDp: Float, multiWindow: Boolean): EditorWindow {
    val largeDisplay = displayShortDp >= LargeScreenMinDp
    val wide = largeDisplay && widthDp >= LargeScreenMinDp
    val landscape = wide && widthDp > heightDp && heightDp >= LargeScreenMinDp
    return EditorWindow(largeDisplay, wide, landscape, landscape && !multiWindow)
}

internal fun useTrailingTabs(widthDp: Float, heightDp: Float, multiWindow: Boolean): Boolean =
    editorWindow(minOf(widthDp, heightDp), widthDp, heightDp, multiWindow).sideNavigation

/** System split/freeform mode can change without a size change or an Activity restart. */
@Composable
internal fun currentEditorWindow(): EditorWindow {
    val activity = LocalActivity.current as? ComponentActivity ?: return editorWindow(0f, 0f, 0f, false)
    var multiWindow by remember(activity) { mutableStateOf(activity.isInMultiWindowMode) }
    DisposableEffect(activity) {
        val listener = Consumer<MultiWindowModeChangedInfo> { multiWindow = it.isInMultiWindowMode }
        activity.addOnMultiWindowModeChangedListener(listener)
        multiWindow = activity.isInMultiWindowMode
        onDispose { activity.removeOnMultiWindowModeChangedListener(listener) }
    }
    // Window bounds survive IME insets. The reduced editor/body height cannot rotate the bar.
    val size = LocalWindowInfo.current.containerSize
    // Read display bounds again when the active panel, density or window configuration changes.
    val configuration = LocalConfiguration.current
    val display = remember(activity, configuration, size, multiWindow) { activity.windowManager.maximumWindowMetrics.bounds }
    val density = LocalDensity.current.density
    // A phone's display-size preference can make it report a tablet-sized window in dp.
    // Use the built-in display's factory density for panel identity, and the user's density
    // for the actual space available inside a split/freeform window.
    val panelDensity = if (activity.display?.displayId == Display.DEFAULT_DISPLAY)
        DisplayMetrics.DENSITY_DEVICE_STABLE / 160f else density
    val windowDensity = if (multiWindow) density else panelDensity
    val layout = editorWindow(minOf(display.width(), display.height()) / panelDensity,
        size.width / windowDensity, size.height / windowDensity, multiWindow)
    SideEffect {
        val orientation = if (multiWindow || layout.largeDisplay) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        if (activity.requestedOrientation != orientation) activity.requestedOrientation = orientation
    }
    return layout
}
