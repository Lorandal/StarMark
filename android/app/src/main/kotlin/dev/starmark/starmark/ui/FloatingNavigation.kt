package dev.starmark.starmark.ui

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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

internal data class NavigationCapsuleLayout(
    val track: FloatingNavigationTrack,
    val thickness: Dp,
    val radius: Dp,
    val selectionRadius: Dp,
    val cornerKind: CornerKind,
    val iconHeight: Dp,
    val labelWidth: Dp,
    val labelHeight: Dp,
    val labelStyle: TextStyle,
    val iconTextStyle: TextStyle,
    val iconsOnly: Boolean = false,
)

internal fun FloatingNavigationMetrics.layerRadius(thickness: Dp, density: Density, inset: Dp = 0.dp): Dp =
    with(density) {
        // The shell and its inset indicator each round their own layout size to whole pixels.
        val halfPx = (thickness - inset * 2).roundToPx() / 2f
        val half = halfPx.toDp()
        if (radius < height / 2f) return@with minOf(radius.dp - inset, half)
        // Miuix switches corner profiles at exactly half the short side.
        if (half.toPx() < halfPx) Math.nextUp(half.value).dp else half
    }

@Composable
private fun navigationCapsuleLayout(
    items: List<FloatingNavEntry>, availableLength: Dp, vertical: Boolean = false,
): NavigationCapsuleLayout {
    val density = LocalDensity.current
    val count = items.size.coerceAtLeast(1)
    val base = floatingNavigationMetrics(availableLength.value, density.fontScale, count)
    val labelStyle = MiuixTheme.textStyles.main.copy(
        fontSize = base.labelSize.sp,
        lineHeight = (if (base.labelSize == 11f) 15f else base.labelSize * 1.2f).sp,
        fontWeight = FontWeight.Medium,
    )
    val iconStyle = MiuixTheme.textStyles.main.copy(
        fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium,
    )
    val measurer = rememberTextMeasurer()
    return remember(items, availableLength, vertical, density, labelStyle, iconStyle, measurer) {
        with(density) {
            val widestLabel = if (count > 3) 0 else items.maxOfOrNull {
                measurer.measure(it.label, style = labelStyle, maxLines = 1).size.width
            } ?: 0
            val metrics = floatingNavigationMetrics(availableLength.value, fontScale, count, widestLabel.toDp().value)
            val track = FloatingNavigationTrack(metrics.width, count)
            val iconHeight = maxOf(28.dp.roundToPx(), items.mapNotNull { it.iconText }.maxOfOrNull {
                measurer.measure(it, style = iconStyle).size.height
            } ?: 0).toDp()
            fun labelHeight(width: Dp): Dp {
                val bounds = Constraints(maxWidth = width.roundToPx().coerceAtLeast(1))
                val height = items.maxOfOrNull {
                    measurer.measure(it.label, style = labelStyle, maxLines = 2, constraints = bounds).size.height
                } ?: 0
                return maxOf(labelStyle.lineHeight.toDp(), height.toDp())
            }
            val bottomLabelWidth = (track.itemWidth - 20f).coerceAtLeast(0f).dp
            val bottomLabelHeight = labelHeight(bottomLabelWidth)
            val thickness = maxOf(metrics.height.dp, iconHeight + bottomLabelHeight + 12.dp)
            val labelWidth = if (vertical) (thickness - 12.dp).coerceAtLeast(1.dp) else bottomLabelWidth
            val uprightLabelHeight = if (vertical) labelHeight(labelWidth) else bottomLabelHeight
            val fittedTrack = if (vertical) track.fitUprightContent(
                (iconHeight + uprightLabelHeight + 12.dp).value, availableLength.value,
            ) else track
            NavigationCapsuleLayout(fittedTrack, thickness, metrics.layerRadius(thickness, density),
                metrics.layerRadius(thickness, density, navigationInset.dp), navigationCornerKind(fontScale),
                iconHeight, labelWidth, uprightLabelHeight, labelStyle, iconStyle)
        }
    }
}

private val RailTouchSize = navigationTouchSize.dp
private val RailFaceWidth = 44.dp
private val RailSelectionSize = 40.dp
private val RailIconSize = 20.dp
private val RailHistoryGap = 24.dp
internal val HistoryButtonSpacing = 4.dp

internal data class TrailingNavigationMetrics(
    val capsule: NavigationCapsuleLayout,
    val rightPadding: Dp,
    val bottomPadding: Dp,
) {
    val clearance get() = maxOf(capsule.thickness, RailTouchSize) + rightPadding + 8.dp
    val heightWithHistory get() = capsule.track.width.dp + RailHistoryGap + RailTouchSize * 2 + HistoryButtonSpacing
}

@Composable
internal fun rootTrailingNavigationMetrics(items: List<FloatingNavEntry>): TrailingNavigationMetrics {
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val right = safe.calculateRightPadding(LocalLayoutDirection.current)
    val bottom = safe.calculateBottomPadding()
    val bottomGap = navigationBottomMargin(bottom.value).dp
    val available = (windowHeight - safe.calculateTopPadding() - bottomGap).coerceAtLeast(0.dp)
    return TrailingNavigationMetrics(navigationCapsuleLayout(items, available, vertical = true),
        navigationBottomMargin(right.value).dp - right, bottomGap - bottom)
}

@Composable
internal fun editorTrailingNavigationMetrics(items: List<FloatingNavEntry>): TrailingNavigationMetrics {
    val density = LocalDensity.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val bottom = safe.calculateBottomPadding()
    val bottomGap = navigationBottomMargin(bottom.value).dp
    val track = compactRailTrack(items.size)
    val metrics = FloatingNavigationMetrics(track.width, RailFaceWidth.value, RailFaceWidth.value / 2f, 0f)
    val iconStyle = with(density) {
        MiuixTheme.textStyles.main.copy(fontSize = 16.dp.toSp(), lineHeight = RailIconSize.toSp(), fontWeight = FontWeight.Medium)
    }
    return TrailingNavigationMetrics(NavigationCapsuleLayout(track, RailFaceWidth,
        metrics.layerRadius(RailFaceWidth, density), RailSelectionSize / 2, CornerKind.Capsule,
        RailIconSize, 0.dp, 0.dp, TextStyle.Default, iconStyle, iconsOnly = true), 10.dp, bottomGap - bottom)
}

internal val FloatingIndicatorEdgesKey = SemanticsPropertyKey<List<Float>>("StarMarkFloatingIndicatorEdges")

internal fun navigationOverlay(dark: Boolean, pressed: Boolean = false, hovered: Boolean = false): Color {
    val alpha = when {
        pressed -> (if (dark) 36f else 26f) / 255f
        hovered -> (if (dark) 20f else 10f) / 255f
        else -> if (dark) .12f else .06f
    }
    return (if (dark) Color.White else Color.Black).copy(alpha = alpha)
}

private fun navigationContentColor(dark: Boolean, enabled: Boolean, pressed: Boolean): Color = when {
    !enabled -> (if (dark) Color.White else Color.Black).copy(alpha = .38f)
    pressed -> if (dark) Color(0x91ffffff) else Color(0x99000000)
    dark -> Color(0xf2ffffff)
    else -> Color.Black
}

@Composable
private fun FloatingTabIcon(item: FloatingNavEntry, color: Color, textStyle: TextStyle, modifier: Modifier, imageSize: Dp = 28.dp) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (item.iconText == null) Icon(item.icon, null, Modifier.size(imageSize), tint = color)
        else Text(item.iconText, modifier = Modifier.clearAndSetSemantics {}, style = textStyle, color = color)
    }
}

@Composable
private fun Modifier.navigationUnderlay(vertical: Boolean, alpha: Float): Modifier {
    if (!LocalStarMarkEffects.current.systemGlass) return this
    val dark = MiuixTheme.colorScheme.background.luminance() < .5f
    val brush = remember(dark, vertical) {
        val edge = if (dark) Color(0xf5000000) else Color(0xf5f3f3f3)
        val colors = listOf(edge.copy(alpha = 0f), edge)
        if (vertical) Brush.horizontalGradient(colors) else Brush.verticalGradient(colors)
    }
    return drawBehind { drawRect(brush, alpha = alpha) }
}

@Composable
internal fun TrailingFloatingNavigation(
    items: List<FloatingNavEntry>, selected: String, onSelected: (String) -> Unit, enabled: Boolean,
    metrics: TrailingNavigationMetrics, modifier: Modifier = Modifier, tagPrefix: String = "navigation",
    interactionEnabled: Boolean = enabled,
    capsuleModifier: Modifier = Modifier,
    aboveRail: (@Composable () -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val effects = LocalStarMarkEffects.current
    Box(modifier, contentAlignment = AbsoluteAlignment.BottomRight) {
        Column(Modifier.navigationUnderlay(vertical = true, alpha = 1f)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Right + WindowInsetsSides.Vertical))
            .absolutePadding(left = if (effects.systemGlass) 20.dp else 8.dp,
                right = metrics.rightPadding, bottom = metrics.bottomPadding),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (aboveRail != null) {
                aboveRail()
                Spacer(Modifier.height(RailHistoryGap)) // Plus the 2dp inset on the history button face.
            }
            Box(capsuleModifier) {
                FloatingNavigationCapsule(items, selected, onSelected, enabled, interactive = enabled && interactionEnabled,
                    layout = metrics.capsule, vertical = true, tagPrefix = tagPrefix, containerTag = "$tagPrefix.rail")
            }
        }
    }
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
    interactionEnabled: Boolean = enabled,
    capsuleModifier: Modifier = Modifier,
    actionContent: (@Composable (Boolean) -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val effects = LocalStarMarkEffects.current
    val density = LocalDensity.current
    val showActions = actionsVisible && actionContent != null
    val frame = rememberBottomBarFrame(showActions, visible)
    val interactive = enabled && interactionEnabled && visible && !showActions && frame.navigationAlpha >= .99f
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        val capsule = navigationCapsuleLayout(items, maxWidth)
        val actionWidth = floatingNavigationMetrics(maxWidth.value, density.fontScale, 3).width.dp
        Column(
            Modifier.fillMaxWidth().navigationUnderlay(vertical = false, alpha = max(frame.navigationAlpha, frame.actionsAlpha))
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .padding(top = if (effects.systemGlass) 20.dp else 8.dp,
                    bottom = navigationBottomMargin(bottomInset.value).dp - bottomInset),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(capsule.thickness).then(capsuleModifier), contentAlignment = Alignment.Center) {
                FloatingNavigationCapsule(items, selected, onSelected, enabled, interactive, capsule, vertical = false,
                    tagPrefix = tagPrefix, containerTag = if (showActions) "$tagPrefix.outgoing" else "$tagPrefix.container",
                    navigationAlpha = frame.navigationAlpha, scale = frame.navigationScale)
                if (actionContent != null && frame.actionsAlpha > 0f) {
                    FloatingActionSurface(frame, actionWidth, capsule.thickness, capsule.radius,
                        showActions, enabled && interactionEnabled, visible, tagPrefix, actionContent)
                }
            }
        }
    }
}

/** Start and length are always in the logical direction of travel; content is never rotated. */
private fun Modifier.trackBounds(vertical: Boolean, start: Dp, cross: Dp, length: Dp, thickness: Dp): Modifier =
    if (vertical) offset(x = cross, y = start).size(width = thickness, height = length)
    else offset(x = start, y = cross).size(width = length, height = thickness)

private val NavigationPressSpring = spring<Float>(dampingRatio = 1f, stiffness = FolmeSpring(1f, .15f).stiffness)

@Composable
internal fun rememberNavigationPress(held: Boolean, taps: Int, interactive: Boolean, reduced: Boolean): State<Float> {
    val press = remember { Animatable(0f) }
    var paintedTap by remember { mutableIntStateOf(taps) }
    LaunchedEffect(held, taps, interactive, reduced) {
        val tapped = taps != paintedTap
        paintedTap = taps
        if (!interactive || reduced) {
            press.snapTo(0f)
        } else if (held) {
            press.animateTo(1f, NavigationPressSpring)
        } else {
            // DOWN and UP can share a frame. Paint at least 3dp of the original 6dp
            // compression on release, then let the same spring recover immediately.
            if (tapped && press.value < .5f) {
                press.snapTo(.5f)
                withFrameNanos { }
            }
            press.animateTo(0f, NavigationPressSpring)
        }
    }
    return press.asState()
}

@Composable
private fun NavigationIndicator(edges: State<Pair<Float, Float>>, track: FloatingNavigationTrack, vertical: Boolean,
                                inset: Dp, thickness: Dp, shape: MiuixCornerShape, color: Color, pressedColor: Color,
                                pressure: State<Float>, tag: String) {
    val (first, second) = edges.value
    val start = min(first, second).coerceIn(0f, track.width)
    val end = max(first, second).coerceIn(start, track.width)
    Box(Modifier.trackBounds(vertical, start.dp, inset, (end - start).dp, thickness)
        .testTag(tag).navigationPressFace(shape, color, progress = { pressure.value }, pressedColor = pressedColor))
}

@Composable
internal fun FloatingNavigationCapsule(
    items: List<FloatingNavEntry>,
    selected: String,
    onSelected: (String) -> Unit,
    enabled: Boolean,
    interactive: Boolean,
    layout: NavigationCapsuleLayout,
    vertical: Boolean,
    tagPrefix: String,
    containerTag: String,
    modifier: Modifier = Modifier,
    navigationAlpha: Float = 1f,
    scale: Float = 1f,
) {
    val track = layout.track
    val effects = LocalStarMarkEffects.current
    val dark = MiuixTheme.colorScheme.background.luminance() < .5f
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val itemIds = remember(items) { items.map { it.id } }
    val selectedIndex = items.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    val gesture = remember(itemIds, vertical, rtl) { FloatingDragState(vertical, rtl) }
    var dragCenter by remember(gesture) { mutableFloatStateOf(Float.NaN) }
    var pointerHeld by remember(gesture) { mutableStateOf(false) }
    var completedTaps by remember(gesture) { mutableIntStateOf(0) }
    var pointerIndex by remember(gesture) { mutableIntStateOf(-1) }
    var releasedIndex by remember(itemIds) { mutableStateOf<Int?>(null) }
    val currentSelected by rememberUpdatedState(hapticSelection(selected, onSelected))
    LaunchedEffect(releasedIndex, selectedIndex) {
        val target = releasedIndex ?: return@LaunchedEffect
        // An earlier response can arrive while a newer tab is already under the finger.
        if (selectedIndex != target) delay(500)
        releasedIndex = null
    }
    val activeIndex = (releasedIndex ?: selectedIndex).coerceIn(items.indices)
    val interactions = remember(itemIds) { List(items.size) { MutableInteractionSource() } }
    val activePressed by interactions[activeIndex].collectIsPressedAsState()
    val activeHovered by interactions[activeIndex].collectIsHoveredAsState()
    val activeFocused by interactions[activeIndex].collectIsFocusedAsState()
    val held = enabled && (pointerHeld || activePressed)
    val pressFraction = if (layout.iconsOnly) rememberNavigationPress(held, completedTaps, interactive, effects.reduceMotion)
        else animateFloatAsState(if (held && !effects.reduceMotion) 1f else 0f,
            animationSpec = if (effects.reduceMotion) snap() else NavigationPressSpring, label = "floatingSelectionPress")
    val center = if (dragCenter.isFinite()) dragCenter else track.center(activeIndex)
    val indicatorLength = if (layout.iconsOnly) RailSelectionSize.value else track.itemWidth
    val edges = rememberIndicatorEdges(itemIds, track, selectedIndex, center, indicatorLength, effects.reduceMotion)
    if (navigationAlpha <= 0f) return
    val width = if (vertical) maxOf(layout.thickness, RailTouchSize) else track.width.dp
    val height = if (vertical) track.width.dp else layout.thickness
    val interactionBounds = Modifier.size(width, height)
            .graphicsLayer {
                // An offscreen alpha buffer excludes the page that native glass samples.
                compositingStrategy = CompositingStrategy.ModulateAlpha
                alpha = navigationAlpha
                scaleX = scale
                scaleY = scale
            }
            .pointerInput(gesture, interactive, width, height) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val begun = gesture.begin(down.position.x.toDp().value, down.position.y.toDp().value,
                        width.value, height.value, items.size, interactive && !down.isConsumed)
                    if (!begun) return@awaitEachGesture
                    down.consume()
                    pointerHeld = true
                    dragCenter = gesture.center
                    pointerIndex = gesture.nearestIndex
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                            change.consume()
                            val x = change.position.x.toDp().value
                            val y = change.position.y.toDp().value
                            if (!change.pressed) {
                                val target = gesture.release(x, y)
                                if (target != null) {
                                    if (layout.iconsOnly) completedTaps++
                                    releasedIndex = target
                                    currentSelected(items[target].id)
                                }
                                break
                            }
                            gesture.move(x, y)
                            dragCenter = gesture.center
                            pointerIndex = gesture.nearestIndex
                        }
                    } finally {
                        gesture.cancel()
                        pointerHeld = false
                        pointerIndex = -1
                        dragCenter = Float.NaN
                    }
                }
            }
            .semantics { this[FloatingIndicatorEdgesKey] = listOf(edges.value.first, edges.value.second) }
    val contents: @Composable () -> Unit = {
        MiuiSelfBlurHost(
            radius = 50f * (1f - navigationAlpha),
            accessible = interactive,
            modifier = Modifier.fillMaxSize().testTag("$tagPrefix.navigationSurface")
                .then(if (interactive) Modifier.selectableGroup() else Modifier.clearAndSetSemantics {}),
        ) {
            Box(Modifier.fillMaxSize()) {
                val inset = if (layout.iconsOnly) (width - RailSelectionSize) / 2 else navigationInset.dp
                val overlayThickness = if (layout.iconsOnly) RailSelectionSize else layout.thickness - inset * 2
                val shape = MiuixCornerShape(layout.selectionRadius, layout.cornerKind)
                val overlay = if (layout.iconsOnly) {
                    if (dark) Color(0xA30A0C0F) else Color.Black.copy(alpha = .12f)
                } else navigationOverlay(dark, held, enabled && (activeHovered || activeFocused))
                val pressedOverlay = if (layout.iconsOnly) {
                    if (dark) Color.White.copy(alpha = .21f) else Color.Black.copy(alpha = .22f)
                } else overlay
                NavigationIndicator(edges, track, vertical, inset, overlayThickness, shape, overlay,
                    pressedOverlay, pressFraction, "$tagPrefix.indicator")
                items.forEachIndexed { index, item ->
                    val interaction = interactions[index]
                    val hovered by interaction.collectIsHoveredAsState()
                    val focused by interaction.collectIsFocusedAsState()
                    val semanticPressed by interaction.collectIsPressedAsState()
                    val pressed = semanticPressed || (pointerHeld && pointerIndex == index)
                    val contentColor = navigationContentColor(dark, enabled, pressed)
                    if (index != activeIndex && enabled && !pointerHeld && (hovered || focused)) {
                        val hoverLength = indicatorLength.dp
                        Box(Modifier.trackBounds(vertical, track.center(index).dp - hoverLength / 2, inset, hoverLength, overlayThickness)
                            .background(navigationOverlay(dark, hovered = true), shape))
                    }
                    // Only the painted capsules overlap. Accessible bounds meet at the midpoints.
                    Column(
                        Modifier.trackBounds(vertical, track.focusStart(index).dp, 0.dp, track.focusWidth.dp, if (vertical) width else layout.thickness)
                            .testTag("$tagPrefix.${item.id}")
                            .then(if (layout.iconsOnly) Modifier.semantics { contentDescription = item.label } else Modifier)
                            .selectable(selected = index == selectedIndex, enabled = interactive, role = Role.Tab,
                                interactionSource = interaction, indication = null, onClick = { currentSelected(item.id) }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        FloatingTabIcon(item, contentColor, layout.iconTextStyle,
                            Modifier.height(layout.iconHeight).testTag("$tagPrefix.icon.${item.id}"), if (layout.iconsOnly) RailIconSize else 28.dp)
                        if (!layout.iconsOnly) Box(Modifier.width(layout.labelWidth).height(layout.labelHeight), contentAlignment = Alignment.Center) {
                            Text(item.label, modifier = Modifier.fillMaxWidth(), style = layout.labelStyle, color = contentColor,
                                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    val elevation = if (effects.glass) 8.dp else 0.dp
    if (layout.iconsOnly) {
        // The glass is as wide as More. Its two transparent 2dp gutters also receive gestures.
        Box(interactionBounds, contentAlignment = Alignment.Center) {
            ChromeSurface(modifier.size(layout.thickness, height).testTag(containerTag),
                cornerRadius = layout.radius, cornerKind = layout.cornerKind,
                role = ChromeMaterialRole.Navigation, shadowElevation = elevation) {}
            contents()
        }
    } else {
        ChromeSurface(modifier.then(interactionBounds).testTag(containerTag),
            cornerRadius = layout.radius, cornerKind = layout.cornerKind,
            role = ChromeMaterialRole.Navigation, shadowElevation = elevation) { contents() }
    }
}

@Composable
private fun rememberIndicatorEdges(
    itemIds: List<String>, track: FloatingNavigationTrack, selectedIndex: Int, center: Float, length: Float, reduceMotion: Boolean,
): State<Pair<Float, Float>> {
    val initialStart = track.center(selectedIndex) - length / 2f
    val durationScale = LocalStarMarkEffects.current.durationScale
    val initialEnd = initialStart + length
    val start = remember(itemIds, track, length) { SpringValue(initialStart) }
    val end = remember(itemIds, track, length) { SpringValue(initialEnd) }
    val edges = remember(start, end) { mutableStateOf(initialStart to initialEnd) }
    val changes = remember(start, end) { Channel<Unit>(Channel.CONFLATED) }
    val targetStart = center - length / 2f
    val targetEnd = center + length / 2f
    SideEffect {
        if (start.target != targetStart || end.target != targetEnd || (reduceMotion && (start.running || end.running))) {
            val specs = indicatorSprings(start.value, end.value, targetStart, targetEnd)
            start.retarget(targetStart, specs.left)
            end.retarget(targetEnd, specs.right)
            if (reduceMotion) {
                start.snapTo(targetStart)
                end.snapTo(targetEnd)
                edges.value = start.value to end.value
            } else changes.trySend(Unit)
        }
    }
    // Retargeting never cancels this frame clock. Frequent MOVE events retain velocity.
    LaunchedEffect(start, end, durationScale) {
        do {
            runMotionFrames(durationScale, { start.running || end.running }) { seconds ->
                start.step(seconds)
                end.step(seconds)
                edges.value = start.value to end.value
            }
        } while (changes.receiveCatching().isSuccess)
    }
    return edges
}
