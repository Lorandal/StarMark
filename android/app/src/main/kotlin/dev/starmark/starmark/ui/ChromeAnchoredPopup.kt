package dev.starmark.starmark.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Position/size change in layout, so native backdrop sampling stays in screen coordinates. */
@Composable
internal fun ChromeAnchoredPopup(
    show: Boolean,
    anchorScreen: Rect?,
    title: String,
    onDismissRequest: () -> Unit,
    onDismissFinished: (() -> Unit)? = null,
    width: Float = ChoicePopupWidth,
    tag: String = "choice.popup",
    source: (@Composable () -> Unit)? = null,
    onShellReady: (() -> Unit)? = null,
    content: @Composable (Boolean) -> Unit,
) {
    var retained by remember { mutableStateOf(show) }
    val effects = LocalStarMarkEffects.current
    val dismiss by rememberUpdatedState(onDismissRequest)
    val finished by rememberUpdatedState(onDismissFinished)
    // This popup may originate inside a dialog's BoxWithConstraints subcomposition.
    // Read visibility/anchor state in the portal itself; a remembered composable
    // lambda identity alone does not invalidate that separate host composition.
    val currentShow = rememberUpdatedState(show)
    val currentAnchor = rememberUpdatedState(anchorScreen)
    LaunchedEffect(show) { if (show) retained = true }
    if ((!show && !retained) || anchorScreen == null) return
    ChromeOverlay {
        val show = currentShow.value
        val anchorScreen = currentAnchor.value
        val active = LocalChromeOverlayActive.current
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        var origin by remember { mutableStateOf<Offset?>(null) }
        val safe = WindowInsets.safeDrawing.union(WindowInsets.ime)
        BackHandler(active) { dismiss() }
        BoxWithConstraints(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }
            .onGloballyPositioned { origin = it.localToScreen(Offset.Zero) }) {
            Box(Modifier.fillMaxSize().testTag("$tag.outside").pointerInput(active) {
                detectTapGestures { if (active) dismiss() }
            })
            val screenOrigin = origin ?: return@BoxWithConstraints
            val button = requireNotNull(anchorScreen)
            val anchor = popupLocalAnchor(MotionRect(button.left, button.top, button.width, button.height),
                screenOrigin.x, screenOrigin.y, density.density) ?: return@BoxWithConstraints
            val insetTop = with(density) { safe.getTop(this).toDp().value } + ChoicePopupSafeMargin
            val insetBottom = with(density) { safe.getBottom(this).toDp().value } + ChoicePopupSafeMargin
            val insetLeft = with(density) { safe.getLeft(this, direction).toDp().value } + ChoicePopupSafeMargin
            val insetRight = with(density) { safe.getRight(this, direction).toDp().value } + ChoicePopupSafeMargin
            val availableHeight = minOf(ChoicePopupMaxHeight, maxHeight.value - insetTop - insetBottom).coerceAtLeast(1f)
            val menuWidth = width.coerceAtMost((maxWidth.value - insetLeft - insetRight).coerceAtLeast(1f))
            var contentHeight by remember { mutableFloatStateOf(0f) }
            val menuHeight = contentHeight.coerceIn(1f, availableHeight)
            val placement = choicePopupPlacement(anchor,
                MotionRect(insetLeft, insetTop, (maxWidth.value - insetLeft - insetRight).coerceAtLeast(1f),
                    (maxHeight.value - insetTop - insetBottom).coerceAtLeast(1f)), menuWidth, menuHeight)
            val target = placement.bounds
            val start = if (source != null) anchor else placement.collapsed
            val motion = remember(contentHeight > 0f) { MenuMotion(start, floatingSource = source != null) }
            val shellAlpha = remember { Animatable(if (source == null) 0f else 1f) }
            val dismissBlur = remember { Animatable(0f) }
            var shellVisible by remember(contentHeight > 0f) { mutableStateOf(source != null) }
            var geometry by remember { mutableStateOf(motion.geometry) }
            var material by remember { mutableFloatStateOf(0f) }
            var sourceMaterial by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(show, start, target, effects.reduceMotion) {
                if (contentHeight <= 0f) return@LaunchedEffect
                val fade = if (source == null) launch {
                    shellAlpha.animateTo(if (show) 1f else 0f,
                        tween(if (effects.reduceMotion) 0 else if (show) 50 else 200, easing = LinearEasing))
                } else null
                val blur = if (source == null) launch {
                    // ArcAnimation opens sharply; only dismissal self-blurs, in physical pixels.
                    if (show || effects.reduceMotion) dismissBlur.snapTo(0f)
                    else dismissBlur.animateTo(30f * density.density, tween(200, easing = LinearEasing))
                } else null
                motion.retarget(show, start, target, effects.reduceMotion)
                fun publish() {
                    geometry = motion.geometry; material = motion.material.value; sourceMaterial = motion.sourceMaterial.value
                    if (geometry.width > start.width + (target.width - start.width) * .1f) shellVisible = true
                }
                publish()
                var previous = withFrameNanos { it }
                while (motion.running && (show || source != null || fade?.isCompleted != true)) {
                    val now = withFrameNanos { it }
                    motion.step(((now - previous) / 1_000_000_000f).coerceAtMost(.064f))
                    previous = now
                    publish()
                }
                fade?.join()
                blur?.join()
                if (!show) { retained = false; finished?.invoke() }
            }
            if (contentHeight > 0f) SideEffect { onShellReady?.invoke() }
            // Freeze one composed frame: a live read in offset/semantics can otherwise
            // get ahead of size(), mixing the next center with the previous dimensions.
            val frame = geometry
            val frameAlpha = if (shellVisible) shellAlpha.value else 0f
            // Compose RenderEffect and MIUI self-blur use different radius scales.
            // One-third reproduces the recorded 33/65ms text spread; passing the raw
            // MIUI radius to RenderEffect obscures the rows before they have folded.
            val frameBlur = if (source == null && effects.glass && !effects.reduceMotion) dismissBlur.value / 3f else 0f
            ChromeSurface(
                modifier = Modifier.offset { IntOffset((frame.left * density.density).roundToInt(), (frame.top * density.density).roundToInt()) }
                    .size(frame.width.dp, frame.height.dp)
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = frameAlpha
                        // Blur the whole composed popup only while dismissing. A second
                        // AndroidView/ComposeView around the shell delays resize/clipping.
                        // Capture appearance with geometry: draw-phase state reads otherwise
                        // advance blur/fade a frame before layout has committed the new bounds.
                        renderEffect = if (frameBlur > 0f) BlurEffect(frameBlur, frameBlur, TileMode.Decal) else null
                    }.testTag(tag)
                    .semantics {
                        paneTitle = title
                        this[MorphMenuGeometryKey] = listOf(frame.centerX, frame.centerY, frame.width, frame.height, frame.radius)
                        this[MorphMenuMaterialKey] = material
                        if (active) dismiss { dismiss(); true }
                    }.pointerInput(Unit) { detectTapGestures { } },
                cornerRadius = frame.radius.dp, shadowElevation = 12.dp, role = ChromeMaterialRole.Popup,
            ) {
                Box(Modifier.fillMaxSize().clip(MiuixCornerShape(frame.radius.dp))) {
                    // ArcAnimation scales both axes by width, capped at 1 during shell overshoot.
                    MiuiSelfBlurHost(
                        wrapContentHeight = true,
                        selfBlurEnabled = source != null,
                        radius = if (effects.reduceMotion || source == null) 0f else 50f * (1f - material),
                        accessible = active && show && (if (source == null) shellAlpha.value else material) >= .99f,
                        modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                            .requiredWidth(menuWidth.dp).heightIn(max = availableHeight.dp)
                            .onSizeChanged { contentHeight = it.height / density.density }
                            .graphicsLayer {
                                transformOrigin = TransformOrigin(0f, 0f)
                                scaleX = (frame.width / menuWidth).let { if (source == null) it.coerceAtMost(1f) else it }; scaleY = scaleX
                                alpha = if (source == null) 1f else material
                            },
                    ) { content(active && show && (if (source == null) shellAlpha.value else material) >= .99f) }
                    if (source != null && sourceMaterial < 1f) {
                        MiuiSelfBlurHost(
                            radius = if (effects.reduceMotion) 0f else 50f * sourceMaterial,
                            accessible = false,
                            modifier = Modifier.align(Alignment.Center).size(start.width.dp, start.height.dp)
                                .graphicsLayer { alpha = 1f - sourceMaterial; scaleX = frame.width / start.width },
                        ) { source() }
                    }
                }
            }
        }
    }
}
