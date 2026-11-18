package dev.starmark.starmark.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.app.MultiWindowModeChangedInfo
import androidx.core.util.Consumer

internal fun useTrailingTabs(widthDp: Float, heightDp: Float, multiWindow: Boolean): Boolean =
    !multiWindow && widthDp > heightDp && heightDp >= 600f

/** System split/freeform mode can change without a size change or an Activity restart. */
@Composable
internal fun useTrailingTabsInCurrentWindow(): Boolean {
    val activity = LocalActivity.current as? ComponentActivity ?: return false
    var multiWindow by remember(activity) { mutableStateOf(activity.isInMultiWindowMode) }
    DisposableEffect(activity) {
        val listener = Consumer<MultiWindowModeChangedInfo> { multiWindow = it.isInMultiWindowMode }
        activity.addOnMultiWindowModeChangedListener(listener)
        multiWindow = activity.isInMultiWindowMode
        onDispose { activity.removeOnMultiWindowModeChangedListener(listener) }
    }
    // Window bounds survive IME insets. The reduced editor/body height cannot rotate the bar.
    val size = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) { useTrailingTabs(size.width.toDp().value, size.height.toDp().value, multiWindow) }
}
