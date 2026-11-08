package dev.starmark.starmark.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.flow.collectLatest

/** Tab order and drill depth provide the same direction for enter and return. */
internal fun pageDirection(from: String, to: String): Int {
    val tabs = listOf("home", "settings")
    if (from in tabs && to in tabs) return if (tabs.indexOf(to) >= tabs.indexOf(from)) 1 else -1
    fun depth(page: String) = when (page) { "home", "settings" -> 0; "watermarks" -> 2; "templateEditor" -> 3; else -> 1 }
    return if (depth(to) >= depth(from)) 1 else -1
}

/** Single retained photo view is transformed alongside native pages, never screenshot/resized. */
@Composable
internal fun ReferencePageTransition(
    page: String,
    onCanvasMotion: (Float, Float, Boolean) -> Unit = { _, _, _ -> },
    toolOrder: List<String>? = null,
    content: @Composable (String, () -> Float) -> Unit,
) {
    val reduced = LocalStarMarkEffects.current.reduceMotion
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = with(LocalDensity.current) { maxWidth.toPx() }
        val history = remember { arrayOf(page) }
        val direction = remember(page) {
            val previous = history[0]
            history[0] = page
            if (toolOrder != null) { if (toolOrder.indexOf(page) >= toolOrder.indexOf(previous)) 1 else -1 }
            else pageDirection(previous, page)
        }
        val pages = updateTransition(page, label = "pageTarget")
        val rootTabs = if (toolOrder == null) listOf("home", "settings") else emptyList()
        pages.AnimatedContent(
            Modifier.fillMaxSize(),
            transitionSpec = { (EnterTransition.None togetherWith ExitTransition.None).using(SizeTransform(clip = false)) },
            contentKey = { if (it in rootTabs) "primary-tabs" else it },
        ) { shown ->
            // The visibility transition keeps one composition per route even when reversed.
            val offset = transition.animateFloat(
                transitionSpec = { if (reduced) snap() else tween(300, easing = CubicBezierEasing(.22f, .61f, .36f, 1f)) }, label = "pageOffset",
            ) { phase -> when (phase) { EnterExitState.PreEnter -> direction * width; EnterExitState.Visible -> 0f; EnterExitState.PostExit -> -direction * width } }
            val alpha = transition.animateFloat(
                transitionSpec = { if (reduced) snap() else tween(220) }, label = "pageOpacity",
            ) { if (it == EnterExitState.Visible) 1f else 0f }
            val pageOffset = remember(reduced, offset) { { if (reduced) 0f else offset.value } }
            val canvasMotion by rememberUpdatedState(onCanvasMotion)
            if (shown in listOf("editor", "templateEditor")) {
                LaunchedEffect(shown, page, pageOffset, alpha) {
                    snapshotFlow { Triple(pageOffset(), alpha.value, shown == page && alpha.value >= .999f) }
                        .collectLatest { (x, opacity, active) -> canvasMotion(x, opacity, active) }
                }
                DisposableEffect(Unit) { onDispose { canvasMotion(0f, 0f, false) } }
            }
            // Animation state is read by the layer and canvas observer, not by every route's
            // composition. Headers, body and their live blur coordinates move as one subtree.
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = pageOffset(); this.alpha = alpha.value }) {
                if (shown in rootTabs) RetainedRootTabs(shown, rootTabs, width, reduced, pageOffset, content)
                else content(shown, pageOffset)
            }
        }
    }
}

/** Keep only the two small primary tabs alive. No hidden secondary screens or photo canvases. */
@Composable
private fun RetainedRootTabs(
    page: String,
    tabs: List<String>,
    width: Float,
    reduced: Boolean,
    parentOffset: () -> Float,
    content: @Composable (String, () -> Float) -> Unit,
) {
    val motion = updateTransition(page, label = "primaryTab")
    for ((index, tab) in tabs.withIndex()) key(tab) {
        val offset = motion.animateFloat(
            transitionSpec = { if (reduced) snap() else tween(300, easing = CubicBezierEasing(.22f, .61f, .36f, 1f)) },
            label = "primaryOffset",
        ) { (index - tabs.indexOf(it)) * width }
        val alpha = motion.animateFloat(
            transitionSpec = { if (reduced) snap() else tween(220) }, label = "primaryOpacity",
        ) { if (it == tab) 1f else 0f }
        val combinedOffset = remember(parentOffset, offset) { { parentOffset() + offset.value } }
        Box(Modifier.fillMaxSize()
            .graphicsLayer { translationX = offset.value; this.alpha = alpha.value }
            .then(if (page != tab) Modifier.clearAndSetSemantics { } else Modifier)) {
            content(tab, combinedOffset)
        }
    }
}
