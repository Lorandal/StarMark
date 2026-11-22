package dev.starmark.starmark.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/** A same-window Miuix dialog. Back commits a dismissal; gesture progress never moves it. */
@Composable
internal fun ChromeDialog(
    show: Boolean,
    modifier: Modifier = Modifier,
    title: String? = null,
    summary: String? = null,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) = ChromeModal(show, modifier, title, summary, onDismissRequest, onDismissFinished, null, null, content)

@Composable
internal fun ChromeBottomSheet(
    show: Boolean,
    modifier: Modifier = Modifier,
    title: String? = null,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    startAction: (@Composable () -> Unit)? = null,
    endAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) = ChromeModal(show, modifier, title, null, onDismissRequest, onDismissFinished, startAction, endAction, content)

@Composable
private fun ChromeModal(
    show: Boolean,
    modifier: Modifier,
    title: String?,
    summary: String?,
    onDismissRequest: (() -> Unit)?,
    onDismissFinished: (() -> Unit)?,
    startAction: (@Composable () -> Unit)?,
    endAction: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    var retained by remember { mutableStateOf(show) }
    if (!show && !retained) return
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = show
    val transition = rememberTransition(visibility, label = "modal")
    val reduced = LocalStarMarkEffects.current.reduceMotion
    val progress by transition.animateFloat(transitionSpec = {
        if (reduced) snap() else spring(dampingRatio = .99f, stiffness = stockPressSpring(false).stiffness,
            visibilityThreshold = .002f)
    }, label = "modalReveal") { if (it) 1f else 0f }
    val dismissRequest by rememberUpdatedState(onDismissRequest)
    val dismissFinished by rememberUpdatedState(onDismissFinished)
    LaunchedEffect(show, visibility.isIdle) {
        if (show) retained = true
        else if (visibility.isIdle) {
            retained = false
            dismissFinished?.invoke()
        }
    }
    ChromeOverlay {
        val active = LocalChromeOverlayActive.current
        val dismiss: () -> Unit = {
            if (active && show) dismissRequest?.invoke()
        }
        BackHandler(enabled = active) { dismiss() }
        val closing = if (show) Modifier else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
        }
        Box(Modifier.fillMaxSize().testTag("modal.overlay").then(closing)) {
            Box(Modifier.matchParentSize().drawBehind { drawRect(Color.Black, alpha = .28f * progress.coerceIn(0f, 1f)) }
                .pointerInput(active, show) { detectTapGestures { dismiss() } })
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp)) {
                val centered = maxWidth >= 816.dp && maxHeight >= 456.dp
                val availableHeight = maxHeight
                val bottomInsets = WindowInsets.safeDrawing.union(WindowInsets.ime)
                ChromeSurface(
                    modifier = modifier.align(if (centered) Alignment.Center else Alignment.BottomCenter)
                        .widthIn(max = 420.dp).fillMaxWidth().heightIn(max = availableHeight)
                        .layout { measurable, constraints ->
                            val panel = measurable.measure(constraints)
                            layout(panel.width, panel.height) {
                                val travel = if (centered) 16.dp.toPx() else panel.height + bottomInsets.getBottom(this@layout) + 24.dp.toPx()
                                panel.place(0, ((1f - progress) * travel).roundToInt())
                            }
                        }
                        .then(if (centered) Modifier.graphicsLayer {
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                            alpha = progress.coerceIn(0f, 1f)
                        } else Modifier)
                        .testTag("modal.surface")
                        .semantics {
                            paneTitle = title ?: "选项"
                            if (active) dismiss { dismiss(); true }
                        }
                        .pointerInput(Unit) { detectTapGestures { /* Consume taps inside the panel. */ } },
                    cornerRadius = 32.dp, shadowElevation = 12.dp, role = ChromeMaterialRole.Modal,
                ) {
                    Column(Modifier.fillMaxWidth().heightIn(max = availableHeight).verticalScroll(rememberScrollState()).padding(24.dp)) {
                        if (title != null || startAction != null || endAction != null) {
                            SymmetricModalTitle(title.orEmpty(), startAction, endAction)
                        }
                        summary?.let {
                            Text(it, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                style = MiuixTheme.textStyles.body1, textAlign = TextAlign.Center)
                        }
                        // Inner lists and editor panels need finite constraints even though
                        // the title/summary and actions can scroll together at large text sizes.
                        Box(Modifier.fillMaxWidth().heightIn(max = (availableHeight - 96.dp).coerceAtLeast(64.dp))) { content() }
                    }
                }
            }
        }
    }
}

/** Measure action slots first: an end-only action must not displace the title. */
@Composable
internal fun SymmetricModalTitle(title: String, startAction: (@Composable () -> Unit)?, endAction: (@Composable () -> Unit)?) {
    androidx.compose.ui.layout.Layout(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), content = {
        Box { startAction?.invoke() }
        Box { endAction?.invoke() }
        Text(title, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }) { children, constraints ->
        val actionConstraints = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = constraints.maxWidth / 3)
        val start = children[0].measure(actionConstraints)
        val end = children[1].measure(actionConstraints)
        val slot = maxOf(start.width, end.width)
        val titleWidth = (constraints.maxWidth - 2 * slot).coerceAtLeast(0)
        val label = children[2].measure(constraints.copy(minWidth = titleWidth, maxWidth = titleWidth, minHeight = 0))
        val height = maxOf(start.height, end.height, label.height)
        layout(constraints.maxWidth, height) {
            start.placeRelative(0, (height - start.height) / 2)
            end.placeRelative(constraints.maxWidth - end.width, (height - end.height) / 2)
            label.placeRelative(slot, (height - label.height) / 2)
        }
    }
}
