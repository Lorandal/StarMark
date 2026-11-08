package dev.starmark.starmark.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs

/** Attach outside the header's nested-scroll connection, so a collapsing header isn't an edge. */
@Composable
internal fun Modifier.scrollBoundaryHaptics(headerOffset: (() -> Float)? = null): Modifier {
    val haptic = rememberUpdatedState(LocalHapticFeedback.current)
    val header = rememberUpdatedState(headerOffset)
    val density = LocalDensity.current.density
    val connection = remember(density) {
        val feedback = ScrollBoundaryFeedback(2f * density, 8f * density)
        object : NestedScrollConnection {
            private var headerBefore = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                headerBefore = header.value?.invoke() ?: 0f
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // A short page may move only its header. Pre-scroll consumption
                // is absent from `consumed`; count the actual header movement too.
                // Taking the larger delta avoids double counting post-scroll expansion.
                val headerDelta = (header.value?.invoke() ?: 0f) - headerBefore
                val travelled = if (abs(headerDelta) > abs(consumed.y)) headerDelta else consumed.y
                if (feedback.scroll(travelled, available.y, source == NestedScrollSource.UserInput)) {
                    haptic.value.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                }
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                feedback.finish()
                return Velocity.Zero
            }
        }
    }
    return nestedScroll(connection)
}
