package dev.starmark.starmark.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val rootTabPages = listOf("home", "settings")
private val editorTabPages = listOf("styles", "content", "fonts", "position")
private fun String.usesPhotoCanvas() = this == "editor" || this == "templateEditor"
internal val LocalOwnsPhotoCanvas = staticCompositionLocalOf<() -> Boolean> { { true } }

/** Tab order and drill depth provide the same direction for enter and return. */
internal fun pageDirection(from: String, to: String): Int {
    if (from in rootTabPages && to in rootTabPages) return if (rootTabPages.indexOf(to) >= rootTabPages.indexOf(from)) 1 else -1
    fun depth(page: String) = when (page) {
        "home", "settings" -> 0
        "editor" -> 2
        "watermarks" -> 3
        "templateEditor" -> 4
        else -> 1
    }
    return if (depth(to) >= depth(from)) 1 else -1
}

internal fun verticalTabSwitch(trailingTabs: Boolean, from: String, to: String): Boolean =
    trailingTabs && ((from in editorTabPages && to in editorTabPages) || (from in rootTabPages && to in rootTabPages))

// Positive travel belongs to the front page. Negative travel belongs to the page behind it.
internal fun routeTranslation(travel: Float): Float = if (travel < 0f) .25f * travel else travel
internal fun exposedRouteWidth(travel: Float): Float = (1f + travel - routeTranslation(travel)).coerceIn(0f, 1f)

/** Single retained photo view is transformed alongside native pages, never screenshot/resized. */
@Composable
internal fun ReferencePageTransition(
    page: String,
    onCanvasMotion: (Float, Float, Boolean) -> Unit = { _, _, _ -> },
    toolOrder: List<String>? = null,
    trailingTabs: Boolean = false,
    primaryTabLayout: (@Composable (String, Modifier, @Composable () -> Unit) -> Unit)? = null,
    content: @Composable (String, () -> Float) -> Unit,
) {
    val reduced = LocalStarMarkEffects.current.reduceMotion
    BoxWithConstraints(Modifier.fillMaxSize().then(if (toolOrder != null) Modifier.clipToBounds() else Modifier)) {
        val width = with(LocalDensity.current) { maxWidth.toPx() }
        val height = with(LocalDensity.current) { maxHeight.toPx() }
        val history = remember { arrayOf(page) }
        val previous = remember(page) { history[0].also { history[0] = page } }
        val direction = if (toolOrder == null) pageDirection(previous, page)
            else if (toolOrder.indexOf(page) >= toolOrder.indexOf(previous)) 1 else -1
        val verticalTools = toolOrder != null && verticalTabSwitch(trailingTabs, previous, page)
        val peerTools = toolOrder != null && previous in editorTabPages && page in editorTabPages
        val toolTravel = with(LocalDensity.current) { 8.dp.toPx() }
        val pages = updateTransition(page, label = "pageTarget")
        val rootTabs = if (toolOrder == null) rootTabPages else emptyList()
        // The incoming photo route owns the single texture; a departing one may
        // still finish its slide when the destination has no photo.
        var lastPhotoRoute by remember { mutableStateOf<String?>(null) }
        val canvasOwner by rememberUpdatedState(if (page.usesPhotoCanvas()) page else lastPhotoRoute)
        if (page.usesPhotoCanvas()) SideEffect { lastPhotoRoute = page }
        pages.AnimatedContent(
            Modifier.fillMaxSize(),
            transitionSpec = { (EnterTransition.None togetherWith ExitTransition.None).using(SizeTransform(clip = false)) },
            contentKey = { if (it in rootTabs) "primary-tabs" else it },
        ) { shown ->
            // The visibility transition keeps one composition per route even when reversed.
            // A third tap must not redirect a page that is already leaving. Animating both
            // axes also keeps its position continuous when a nested inspector changes axis.
            val travel = remember(shown == page, trailingTabs, width, height, toolTravel) {
                val distance = if (peerTools) toolTravel / (if (verticalTools) height else width).coerceAtLeast(1f) else 1f
                if (verticalTools) Offset(0f, direction * distance) else Offset(direction * distance, 0f)
            }
            val displacement = transition.animateOffset(
                transitionSpec = {
                    when {
                        reduced -> snap()
                        toolOrder != null -> tween(if (peerTools) 250 else 300, easing = CubicBezierEasing(.22f, .61f, .36f, 1f))
                        else -> spring(dampingRatio = .95f, stiffness = 243f,
                            visibilityThreshold = Offset(.5f / width.coerceAtLeast(1f), .5f / height.coerceAtLeast(1f)))
                    }
                }, label = "pageOffset",
            ) { phase -> when (phase) { EnterExitState.PreEnter -> travel; EnterExitState.Visible -> Offset.Zero; EnterExitState.PostExit -> -travel } }
            val toolOpacity = if (toolOrder != null) transition.animateFloat(
                transitionSpec = {
                    when {
                        reduced -> snap()
                        targetState == EnterExitState.Visible -> tween(295, easing = CubicBezierEasing(.4f, 0f, .3f, 1f))
                        else -> tween(90)
                    }
                }, label = "toolOpacity",
            ) { phase -> if (peerTools && phase != EnterExitState.Visible) 0f else 1f } else null
            // Whole-route pushes move the native chrome and the Flutter texture together on X.
            // Tool-tab changes move only the inspector and leave the photograph stationary.
            val hierarchy = toolOrder == null
            val pageOffset = remember(reduced, width, displacement, hierarchy) {
                { if (reduced) 0f else (if (hierarchy) routeTranslation(displacement.value.x) else displacement.value.x) * width }
            }
            val canvasMotion by rememberUpdatedState(onCanvasMotion)
            if (shown.usesPhotoCanvas()) {
                LaunchedEffect(shown, page, pageOffset) {
                    snapshotFlow { pageOffset() }.collectLatest { x ->
                        if (canvasOwner == shown) {
                            val current = shown == page
                            val opacity = if (reduced && !current) 0f else 1f
                            canvasMotion(x, opacity, current && kotlin.math.abs(x) <= .5f)
                        }
                    }
                }
                DisposableEffect(shown) { onDispose { if (canvasOwner == shown) canvasMotion(0f, 0f, false) } }
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
                translationY = if (reduced) 0f else displacement.value.y * height
                // AnimatedContent can retain the outgoing subtree for its final frame even
                // with a snap spec. Never draw that frame through the incoming photo window.
                alpha = if (reduced) {
                    if (shown == page || (shown in rootTabs && page in rootTabs)) 1f else 0f
                } else toolOpacity?.value ?: 1f
            }.then(if (hierarchy) Modifier.drawWithContent {
                val travelX = displacement.value.x
                if (!reduced && travelX < 0f) {
                    // Clip in local coordinates: the front page's photo is a sibling TextureView
                    // behind Compose. Painting the outgoing list beneath that hole would hide it.
                    clipRect(right = size.width * exposedRouteWidth(travelX)) {
                        this@drawWithContent.drawContent()
                        drawRect(Color.Black.copy(alpha = (-.5f * travelX).coerceIn(0f, .5f)))
                    }
                } else drawContent()
            } else Modifier.background(MiuixTheme.colorScheme.background)).then(outgoingTool)) {
                if (shown.usesPhotoCanvas()) {
                    val ownsCanvas = remember(shown) { { canvasOwner == shown } }
                    CompositionLocalProvider(LocalOwnsPhotoCanvas provides ownsCanvas) { content(shown, pageOffset) }
                } else if (shown in rootTabs) {
                    val panes: @Composable () -> Unit = {
                        RetainedRootTabs(shown, rootTabs, width, height, trailingTabs, reduced, pageOffset, content)
                    }
                    if (primaryTabLayout == null) panes() else {
                        // The shared bar belongs to the page behind the leading edge. Only a
                        // hierarchy change contracts it; switching between the two tabs does not.
                        val capsuleModifier = if (reduced) Modifier else {
                            val scale = transition.animateFloat(transitionSpec = {
                                spring(dampingRatio = NavigationVisibilitySpring.damping, stiffness = NavigationVisibilitySpring.stiffness)
                            }, label = "primaryNavigationScale") { phase ->
                                if (phase == EnterExitState.Visible) 1f else ContractedNavigationScale
                            }
                            Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                        }
                        primaryTabLayout(shown, capsuleModifier, panes)
                    }
                } else content(shown, pageOffset)
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
    // Both opaque panes share a single position, including on reversal. Neither needs
    // a full-window intermediate alpha buffer to slide alongside the other.
    val position = animateFloatAsState(tabs.indexOf(page).toFloat(),
        animationSpec = if (reduced) snap() else spring(dampingRatio = .99f, stiffness = stockPressSpring(false).stiffness,
            visibilityThreshold = .5f / (if (trailingTabs) height else width).coerceAtLeast(1f)),
        label = "primaryTabPosition")
    // Keep visited tabs alive without composing the hidden settings page during cold startup.
    val visited = remember { mutableSetOf(page) }
    SideEffect { visited.add(page) }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        for ((index, tab) in tabs.withIndex()) if (tab == page || tab in visited) key(tab) {
            val combinedOffset = remember(parentOffset, position, trailingTabs, width, index) {
                { parentOffset() + if (trailingTabs) 0f else (index - position.value) * width }
            }
            Box(Modifier.fillMaxSize()
                .graphicsLayer {
                    translationX = if (trailingTabs) 0f else (index - position.value) * width
                    translationY = if (trailingTabs) (index - position.value) * height else 0f
                }
                .then(if (page != tab) Modifier.clearAndSetSemantics { } else Modifier)) {
                content(tab, combinedOffset)
            }
        }
    }
}
