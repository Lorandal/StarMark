package dev.starmark.starmark.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max
import kotlin.math.min

data class FloatingNavEntry(val id: String, val label: String, val icon: ImageVector, val iconText: String? = null)

internal val FloatingIndicatorEdgesKey = SemanticsPropertyKey<List<Float>>("StarMarkFloatingIndicatorEdges")

internal fun navigationOverlay(dark: Boolean, pressed: Boolean = false, hovered: Boolean = false): Color {
    val alpha = when {
        pressed -> (if (dark) 36f else 26f) / 255f
        hovered -> (if (dark) 20f else 10f) / 255f
        else -> if (dark) .12f else .06f
    }
    return (if (dark) Color.White else Color.Black).copy(alpha = alpha)
}

/** Miuix native content with the reference's independent leading/trailing edge springs. */
@Composable
fun FloatingNavigation(
    items: List<FloatingNavEntry>,
    selected: String,
    onSelected: (String) -> Unit,
    enabled: Boolean = true,
    visible: Boolean = true,
    modifier: Modifier = Modifier,
    tagPrefix: String = "navigation",
    actionsVisible: Boolean = false,
    actionContent: (@Composable (Boolean) -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val effects = LocalStarMarkEffects.current
    val density = LocalDensity.current
    val dark = MiuixTheme.colorScheme.background.luminance() < .5f
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val itemIds = remember(items) { items.map { it.id } }
    val selectedIndex = items.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    val showActions = actionsVisible && actionContent != null
    val frame = rememberBottomBarFrame(showActions, visible)
    val navigationAlpha = frame.navigationAlpha
    val navigationScale = frame.navigationScale
    val actionsAlpha = frame.actionsAlpha
    val navigationInteractive = enabled && visible && !showActions && navigationAlpha >= .99f

    BoxWithConstraints(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))) {
        val baseMetrics = floatingNavigationMetrics(maxWidth.value, density.fontScale, items.size)
        val measurer = rememberTextMeasurer()
        val labelStyle = MiuixTheme.textStyles.main.copy(fontSize = baseMetrics.labelSize.sp,
            lineHeight = (if (baseMetrics.labelSize == 11f) 15f else baseMetrics.labelSize * 1.2f).sp,
            fontWeight = FontWeight.Medium)
        val widestLabel = remember(items, labelStyle, density, measurer) {
            if (items.size > 3) 0f else with(density) {
                items.maxOf { measurer.measure(it.label, style = labelStyle, maxLines = 1).size.width }.toDp().value
            }
        }
        val metrics = floatingNavigationMetrics(maxWidth.value, density.fontScale, items.size, widestLabel)
        val actionWidth = floatingNavigationMetrics(maxWidth.value, density.fontScale, 3).width
        val track = remember(metrics.width, items.size) { FloatingNavigationTrack(metrics.width, items.size) }
        val iconTextStyle = MiuixTheme.textStyles.main.copy(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
        val iconSlot = with(density) {
            maxOf(28.dp.roundToPx(), items.mapNotNull { it.iconText }.maxOfOrNull {
                measurer.measure(it, style = iconTextStyle).size.height
            } ?: 0).toDp()
        }
        val labelWidth = (track.itemWidth - 20f).coerceAtLeast(0f).dp
        val labelSlot = with(density) {
            val maxLabelWidth = labelWidth.roundToPx().coerceAtLeast(1)
            maxOf(labelStyle.lineHeight.toDp(), items.maxOf { item ->
                measurer.measure(item.label, style = labelStyle, maxLines = 2,
                    constraints = Constraints(maxWidth = maxLabelWidth)).size.height
            }.toDp())
        }
        val shellHeight = maxOf(metrics.height.dp, iconSlot + labelSlot + 12.dp)
        val cornerRadius = minOf(metrics.radius.dp, shellHeight / 2f)
        val gesture = remember(itemIds) { FloatingDragState() }
        var dragCenter by remember { mutableFloatStateOf(Float.NaN) }
        var pointerHeld by remember { mutableStateOf(false) }
        var pointerIndex by remember { mutableIntStateOf(-1) }
        var releasedIndex by remember { mutableStateOf<Int?>(null) }
        val haptics = LocalHapticFeedback.current
        val currentSelected by rememberUpdatedState<(String) -> Unit>({ id ->
            if (id != selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onSelected(id)
        })
        LaunchedEffect(selectedIndex) { releasedIndex = null }
        LaunchedEffect(releasedIndex) { if (releasedIndex != null) { delay(500); releasedIndex = null } }
        val activeIndex = (releasedIndex ?: selectedIndex).coerceIn(items.indices)
        val interactions = remember(itemIds) { List(items.size) { MutableInteractionSource() } }
        val activePressed by interactions[activeIndex].collectIsPressedAsState()
        val activeHovered by interactions[activeIndex].collectIsHoveredAsState()
        val activeFocused by interactions[activeIndex].collectIsFocusedAsState()
        val held = enabled && (pointerHeld || activePressed)
        val pressFraction by animateFloatAsState(if (held && !effects.reduceMotion) 1f else 0f,
            animationSpec = if (effects.reduceMotion) snap() else spring(dampingRatio = 1f, stiffness = FolmeSpring(1f, .15f).stiffness),
            label = "floatingSelectionPress")
        val initialLeft = track.start(selectedIndex)
        val initialRight = initialLeft + track.itemWidth
        val left = remember(itemIds, track) { SpringValue(initialLeft) }
        val right = remember(itemIds, track) { SpringValue(initialRight) }
        var edges by remember(itemIds, track) { mutableStateOf(initialLeft to initialRight) }
        val targetChanges = remember(left, right) { Channel<Unit>(Channel.CONFLATED) }
        // Read pointer state in composition; SideEffect itself is not a snapshot observer.
        val center = if (dragCenter.isFinite()) dragCenter else track.center(activeIndex)
        val targetLeft = center - track.itemWidth / 2f
        val targetRight = center + track.itemWidth / 2f
        SideEffect {
            if (left.target != targetLeft || right.target != targetRight || (effects.reduceMotion && (left.running || right.running))) {
                val specs = indicatorSprings(left.value, right.value, targetLeft, targetRight)
                left.retarget(targetLeft, specs.left); right.retarget(targetRight, specs.right)
                if (effects.reduceMotion) {
                    left.snapTo(targetLeft); right.snapTo(targetRight)
                    edges = left.value to right.value
                } else targetChanges.trySend(Unit)
            }
        }
        // Retargeting never cancels this frame clock. Frequent MOVE events retain velocity.
        LaunchedEffect(left, right, effects.reduceMotion) {
            for (ignored in targetChanges) {
                var previous = withFrameNanos { it }
                while (left.running || right.running) {
                    val now = withFrameNanos { it }
                    val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(.064f)
                    previous = now
                    left.step(seconds); right.step(seconds)
                    edges = left.value to right.value
                }
            }
        }
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val underlay = remember(dark) {
            val bottom = if (dark) Color(0xf5000000) else Color(0xf5f3f3f3)
            Brush.verticalGradient(listOf(bottom.copy(alpha = 0f), bottom))
        }
        // DeskClock's outer GradientDrawable sits behind its material and reaches the window edge.
        // Drawing here keeps page scrolling and the system gesture area outside the capsule's hit box.
        val gradient = if (effects.systemGlass) Modifier.drawBehind {
            drawRect(underlay, alpha = max(navigationAlpha, actionsAlpha))
        } else Modifier
        Column(
            Modifier.fillMaxWidth().then(gradient).windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .padding(top = if (effects.systemGlass) 20.dp else 8.dp, bottom = navigationBottomMargin(bottomInset.value).dp - bottomInset),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(shellHeight), contentAlignment = Alignment.Center) {
                if (navigationAlpha > 0f) ChromeSurface(
                    modifier = Modifier.width(metrics.width.dp).height(shellHeight)
                        .testTag(if (!showActions) "$tagPrefix.container" else "$tagPrefix.outgoing")
                        .graphicsLayer {
                            // An offscreen alpha buffer excludes the page that native glass samples.
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                            alpha = navigationAlpha
                            scaleX = navigationScale
                            scaleY = scaleX
                        }
                        .pointerInput(itemIds, navigationInteractive, track, shellHeight, rtl) {
                            fun logicalX(px: Float): Float = if (rtl) track.width - px / density.density else px / density.density
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                val begun = gesture.begin(logicalX(down.position.x), down.position.y / density.density,
                                    metrics.width, shellHeight.value, items.size, navigationInteractive && !down.isConsumed)
                                if (!begun) return@awaitEachGesture
                                down.consume()
                                pointerHeld = true
                                dragCenter = gesture.center
                                pointerIndex = gesture.nearestIndex
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || event.changes.any { it.id != down.id && it.pressed }) { gesture.cancel(); break }
                                        if (change.isConsumed) { gesture.cancel(); break }
                                        change.consume()
                                        val x = logicalX(change.position.x)
                                        val y = change.position.y / density.density
                                        if (!change.pressed) {
                                            val target = gesture.release(x, y)
                                            if (target != null) { releasedIndex = target; currentSelected(items[target].id) }
                                            break
                                        }
                                        gesture.move(x, y)
                                        if (gesture.active) { dragCenter = gesture.center; pointerIndex = gesture.nearestIndex }
                                    }
                                } finally {
                                    gesture.cancel(); pointerHeld = false; pointerIndex = -1; dragCenter = Float.NaN
                                }
                            }
                        }
                        .semantics { this[FloatingIndicatorEdgesKey] = listOf(edges.first, edges.second) },
                    cornerRadius = cornerRadius,
                    role = ChromeMaterialRole.Navigation,
                    shadowElevation = if (effects.glass) 8.dp else 0.dp,
                ) {
                    MiuiSelfBlurHost(
                        radius = 50f * (1f - navigationAlpha),
                        accessible = navigationInteractive,
                        modifier = Modifier.fillMaxSize().testTag("$tagPrefix.navigationSurface")
                            .then(if (navigationInteractive) Modifier.selectableGroup() else Modifier.clearAndSetSemantics {}),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                        val l = min(edges.first, edges.second).coerceIn(0f, metrics.width)
                        val r = max(edges.first, edges.second).coerceIn(l, metrics.width)
                        val overlayHeight = shellHeight - navigationInset.dp * 2
                        val overlayShape = MiuixCornerShape(cornerRadius - navigationInset.dp)
                        Box(
                            Modifier.offset(x = l.dp, y = navigationInset.dp).width((r - l).dp).height(overlayHeight)
                                .graphicsLayer {
                                    // Clock presses the selection inward by 6dp along each axis.
                                    val shrink = 6.dp.toPx() * pressFraction
                                    scaleX = (1f - shrink / size.width.coerceAtLeast(1f)).coerceAtLeast(.5f)
                                    scaleY = (1f - shrink / size.height.coerceAtLeast(1f)).coerceAtLeast(.5f)
                                }
                                .background(navigationOverlay(dark, held, enabled && (activeHovered || activeFocused)), overlayShape),
                        )
                        items.forEachIndexed { index, item ->
                            val interaction = interactions[index]
                            val hovered by interaction.collectIsHoveredAsState()
                            val focused by interaction.collectIsFocusedAsState()
                            val semanticPressed by interaction.collectIsPressedAsState()
                            val pressed = semanticPressed || (pointerHeld && pointerIndex == index)
                            val contentColor = when {
                                !enabled -> (if (dark) Color.White else Color.Black).copy(alpha = .38f)
                                pressed -> if (dark) Color(0x91ffffff) else Color(0x99000000)
                                dark -> Color(0xf2ffffff)
                                else -> Color.Black
                            }
                            val itemStart = track.start(index).dp
                            if (index != activeIndex && enabled && !pointerHeld && (hovered || focused)) {
                                Box(Modifier.offset(x = itemStart, y = navigationInset.dp).width(track.itemWidth.dp)
                                    .height(overlayHeight).background(navigationOverlay(dark, hovered = true), overlayShape))
                            }
                            // Only the painted capsules overlap. Disjoint focus nodes keep
                            // their accessible bounds centered on the same icons in either direction.
                            Column(
                                Modifier.offset(x = track.focusStart(index).dp).width(track.focusWidth.dp).fillMaxHeight()
                                    .testTag("$tagPrefix.${item.id}")
                                    .selectable(
                                        selected = index == selectedIndex,
                                        enabled = navigationInteractive,
                                        role = Role.Tab,
                                        interactionSource = interaction,
                                        indication = null,
                                        onClick = { currentSelected(item.id) },
                                    ),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Box(Modifier.height(iconSlot).testTag("$tagPrefix.icon.${item.id}"), contentAlignment = Alignment.Center) {
                                    if (item.iconText == null) Icon(item.icon, null, Modifier.size(28.dp), tint = contentColor)
                                    else Text(item.iconText, modifier = Modifier.clearAndSetSemantics {},
                                        style = iconTextStyle, color = contentColor)
                                }
                                Box(Modifier.width(labelWidth).height(labelSlot), contentAlignment = Alignment.Center) {
                                    Text(item.label, modifier = Modifier.fillMaxWidth(), style = labelStyle, color = contentColor,
                                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        }
                    }
                }
                if (actionContent != null && actionsAlpha > 0f) {
                    FloatingActionSurface(frame, actionWidth.dp, shellHeight, cornerRadius,
                        showActions, enabled, visible, tagPrefix, actionContent)
                }
            }
        }
    }
}
