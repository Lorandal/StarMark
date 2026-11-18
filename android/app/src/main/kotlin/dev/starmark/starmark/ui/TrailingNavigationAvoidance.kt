package dev.starmark.starmark.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val LocalRootNavigationRail = staticCompositionLocalOf<TrailingNavigationMetrics?> { null }

internal enum class RailListPlacement { FullWidth, ScrollClear, InsetPage }

internal fun railListPlacement(overflows: Boolean, contentEnd: Int, viewportEnd: Int, railSpace: Int) = when {
    overflows -> RailListPlacement.ScrollClear
    contentEnd > viewportEnd - railSpace -> RailListPlacement.InsetPage
    else -> RailListPlacement.FullWidth
}

/** Measure the natural list before adding scroll room. Current scroll position never moves a single row. */
@Composable
internal fun rootRailListInsets(state: LazyListState, normalBottom: Dp, layoutKey: Any): PaddingValues {
    val rail = LocalRootNavigationRail.current ?: return PaddingValues()
    val bottom = rail.capsule.track.width.dp + rail.bottomPadding +
        WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + 8.dp
    val density = LocalDensity.current
    val railSpace = with(density) { bottom.roundToPx() }
    val naturalPadding = with(density) { normalBottom.roundToPx() }
    val window = LocalWindowInfo.current.containerSize
    var insetPage by remember(window, layoutKey, railSpace, naturalPadding, density.fontScale) { mutableStateOf(false) }
    val measured by remember(state, railSpace, naturalPadding) { derivedStateOf {
        val layout = state.layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf RailListPlacement.FullWidth
        val contentEnd = last.offset + last.size + state.firstVisibleItemScrollOffset
        val overflow = state.firstVisibleItemIndex > 0 || last.index < layout.totalItemsCount - 1 ||
            contentEnd + naturalPadding > layout.viewportEndOffset
        railListPlacement(overflow, contentEnd, layout.viewportEndOffset, railSpace)
    } }
    // Narrowing a home banner can make it shorter. Keep the full-width decision until its
    // content or window changes, instead of alternating widths at the layout threshold.
    val placement = if (insetPage) RailListPlacement.InsetPage else measured
    if (placement == RailListPlacement.InsetPage && !insetPage) SideEffect { insetPage = true }
    return PaddingValues.Absolute(
        right = if (placement == RailListPlacement.InsetPage) rail.clearance else 0.dp,
        bottom = if (placement == RailListPlacement.ScrollClear) maxOf(0.dp, bottom - normalBottom) else 0.dp,
    )
}
