// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// Adapted from compose-miuix-ui 0.9.4 TopAppBar.kt for the StarMark home header.
package dev.starmark.starmark.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBarState

@Composable
internal fun rememberHomeHeaderScrollBehavior(
    state: TopAppBarState,
    canScroll: () -> Boolean,
    snapAnimationSpec: AnimationSpec<Float>? = spring(stiffness = 2500f),
    flingAnimationSpec: DecayAnimationSpec<Float>? = rememberSplineBasedDecay(),
): ScrollBehavior = remember(state, canScroll, snapAnimationSpec, flingAnimationSpec) {
    HomeHeaderScrollBehavior(state, canScroll, snapAnimationSpec, flingAnimationSpec)
}

/** Miuix motion with exact nested-scroll consumption and a signed decay boundary check. */
internal class HomeHeaderScrollBehavior(
    override val state: TopAppBarState,
    private val canScroll: () -> Boolean,
    override val snapAnimationSpec: AnimationSpec<Float>?,
    override val flingAnimationSpec: DecayAnimationSpec<Float>?,
) : ScrollBehavior {
    override val isPinned = false
    override val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!canScroll() || available.y > 0f) return Offset.Zero
            val previous = state.heightOffset
            state.heightOffset += available.y
            // At the collapse limit, leave the remainder for LazyColumn in this same event.
            return Offset(0f, state.heightOffset - previous)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (!canScroll()) return Offset.Zero
            state.contentOffset += consumed.y
            val previous = state.heightOffset
            if (available.y < 0f || consumed.y < 0f) state.heightOffset += consumed.y
            else if (available.y > 0f) state.heightOffset += available.y
            return Offset(0f, state.heightOffset - previous)
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (!canScroll()) return Velocity.Zero
            if (available.y > 0f) state.contentOffset = 0f
            return settle(available.y)
        }
    }

    private suspend fun settle(velocity: Float): Velocity {
        if (state.collapsedFraction < .01f || state.collapsedFraction == 1f) return Velocity.Zero
        var remainingVelocity = velocity
        if (flingAnimationSpec != null && abs(velocity) > 1f) {
            var lastValue = 0f
            AnimationState(initialValue = 0f, initialVelocity = velocity).animateDecay(flingAnimationSpec) {
                val delta = value - lastValue
                val previous = state.heightOffset
                state.heightOffset += delta
                val consumed = state.heightOffset - previous
                lastValue = value
                remainingVelocity = this.velocity
                // Both values retain direction. abs(consumed) would cancel every upward fling.
                if (abs(delta - consumed) > .5f) cancelAnimation()
            }
        }
        if (snapAnimationSpec != null && state.heightOffset < 0f && state.heightOffset > state.heightOffsetLimit) {
            AnimationState(initialValue = state.heightOffset).animateTo(
                if (state.collapsedFraction < .5f) 0f else state.heightOffsetLimit,
                animationSpec = snapAnimationSpec,
            ) { state.heightOffset = value }
        }
        return Velocity(0f, velocity - remainingVelocity)
    }
}
