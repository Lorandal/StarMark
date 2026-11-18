package dev.starmark.starmark.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.flow.collectLatest

private val rootTabPages = listOf("home", "settings")
private val editorTabPages = listOf("styles", "content", "fonts", "position")

/** Tab order and drill depth provide the same direction for enter and return. */
internal fun pageDirection(from: String, to: String): Int {
    if (from in rootTabPages && to in rootTabPages) return if (rootTabPages.indexOf(to) >= rootTabPages.indexOf(from)) 1 else -1
    fun depth(page: String) = when (page) { "home", "settings" -> 0; "watermarks" -> 2; "templateEditor" -> 3; else -> 1 }
    return if (depth(to) >= depth(from)) 1 else -1
}

internal fun verticalTabSwitch(trailingTabs: Boolean, from: String, to: String): Boolean =
    trailingTabs && ((from in editorTabPages && to in editorTabPages) || (from in rootTabPages && to in rootTabPages))

/** Single retained photo view is transformed alongside native pages, never screenshot/resized. */
@Composable
internal fun ReferencePageTransition(
    page: String,
    onCanvasMotion: (Float, Float, Boolean) -> Unit = { _, _, _ -> },
    toolOrder: List<String>? = null,
    trailingTabs: Boolean = false,
    content: @Composable (String, () -> Float) -> Unit,
) {
    val reduced = LocalStarMarkEffects.current.reduceMotion
    BoxWithConstraints(Modifier.fillMaxSize().then(if (trailingTabs && toolOrder != null) Modifier.clipToBounds() else Modifier)) {
        val width = with(LocalDensity.current) { maxWidth.toPx() }
        val height = with(LocalDensity.current) { maxHeight.toPx() }
        val history = remember { arrayOf(page) }
        val previous = remember(page) { history[0].also { history[0] = page } }
        val direction = if (toolOrder == null) pageDirection(previous, page)
            else if (toolOrder.indexOf(page) >= toolOrder.indexOf(previous)) 1 else -1
        val verticalTools = toolOrder != null && verticalTabSwitch(trailingTabs, previous, page)
        val pages = updateTransition(page, label = "pageTarget")
        val rootTabs = if (toolOrder == null) rootTabPages else emptyList()
        pages.AnimatedContent(
            Modifier.fillMaxSize(),
            transitionSpec = { (EnterTransition.None togetherWith ExitTransition.None).using(SizeTransform(clip = false)) },
            contentKey = { if (it in rootTabs) "primary-tabs" else it },
        ) { shown ->
            // The visibility transition keeps one composition per route even when reversed.
            val displacement = transition.animateFloat(
                transitionSpec = { if (reduced) snap() else tween(300, easing = CubicBezierEasing(.22f, .61f, .36f, 1f)) }, label = "pageOffset",
            ) { phase -> when (phase) { EnterExitState.PreEnter -> direction.toFloat(); EnterExitState.Visible -> 0f; EnterExitState.PostExit -> -direction.toFloat() } }
            val alpha = transition.animateFloat(
                transitionSpec = { if (reduced) snap() else tween(220) }, label = "pageOpacity",
            ) { if (it == EnterExitState.Visible) 1f else 0f }
            // Whole-route pushes move the native chrome and the Flutter texture together on X.
            // Tool-tab changes move only the inspector and leave the photograph stationary.
            val pageOffset = remember(reduced, verticalTools, width, displacement) {
                { if (reduced || verticalTools) 0f else displacement.value * width }
            }
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
            // A departing tool keeps its painted state until it slides out, but cannot receive input.
            val outgoingTool = if (toolOrder != null && shown != page) Modifier.clearAndSetSemantics {}
                .focusProperties { canFocus = false }.pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                } else Modifier
            Box(Modifier.fillMaxSize().graphicsLayer {
                translationX = pageOffset()
                translationY = if (verticalTools && !reduced) displacement.value * height else 0f
                this.alpha = alpha.value
            }.then(outgoingTool)) {
                if (shown in rootTabs) RetainedRootTabs(shown, rootTabs, width, height, trailingTabs, reduced, pageOffset, content)
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
    height: Float,
    trailingTabs: Boolean,
    reduced: Boolean,
    parentOffset: () -> Float,
    content: @Composable (String, () -> Float) -> Unit,
) {
    val motion = updateTransition(page, label = "primaryTab")
    for ((index, tab) in tabs.withIndex()) key(tab) {
        val displacement = motion.animateFloat(
            transitionSpec = { if (reduced) snap() else tween(300, easing = CubicBezierEasing(.22f, .61f, .36f, 1f)) },
            label = "primaryOffset",
        ) { (index - tabs.indexOf(it)).toFloat() }
        val alpha = motion.animateFloat(
            transitionSpec = { if (reduced) snap() else tween(220) }, label = "primaryOpacity",
        ) { if (it == tab) 1f else 0f }
        val combinedOffset = remember(parentOffset, displacement, trailingTabs, width) {
            { parentOffset() + if (trailingTabs) 0f else displacement.value * width }
        }
        Box(Modifier.fillMaxSize()
            .graphicsLayer {
                translationX = if (trailingTabs) 0f else displacement.value * width
                translationY = if (trailingTabs) displacement.value * height else 0f
                this.alpha = alpha.value
            }
            .then(if (page != tab) Modifier.clearAndSetSemantics { } else Modifier)) {
            content(tab, combinedOffset)
        }
    }
}
