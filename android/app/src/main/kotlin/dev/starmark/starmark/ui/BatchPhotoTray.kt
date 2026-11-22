package dev.starmark.starmark.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val DisclosureCaretSize = 10.dp
private val DisclosureLabelGap = 8.dp
private val DisclosureSidePadding = 12.dp

internal class BatchTrayLayout(
    val expanded: Boolean, val reveal: State<Float>,
    val stacked: Boolean, val headerHeight: Dp, val captionHeight: Dp, val disclosureSize: DpSize,
) {
    val progress get() = reveal.value.coerceIn(0f, 1f)
    val height get() = headerHeight + (EditorStripDefaults.FrameHeight + 4.dp + captionHeight + 8.dp) * progress
}

@Composable
internal fun rememberBatchTrayReveal(expanded: Boolean): State<Float> {
    val target = if (expanded) 1f else 0f
    if (LocalStarMarkEffects.current.reduceMotion) return rememberUpdatedState(target)
    val release = stockPressSpring(pressed = false)
    return animateFloatAsState(target, spring(release.damping, release.stiffness, visibilityThreshold = .001f),
        label = "batchTrayReveal")
}

@Composable
internal fun batchTrayLayout(width: Dp, batch: Map<*, *>, reveal: State<Float>? = null): BatchTrayLayout {
    val count = batch.rows("items").size
    val density = LocalDensity.current
    val measure = rememberTextMeasurer()
    val titleStyle = MiuixTheme.textStyles.main.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium)
    val actionStyle = MiuixTheme.textStyles.main.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val title = measure.measure("照片 $count / $count", style = titleStyle, maxLines = 1).size
    val openLabel = measure.measure("展开", style = actionStyle, maxLines = 1).size
    val closeLabel = measure.measure("收起", style = actionStyle, maxLines = 1).size
    val disclosureSize = with(density) {
        DpSize(
            width = maxOf(openLabel.width, closeLabel.width).toDp() + DisclosureLabelGap + DisclosureCaretSize + DisclosureSidePadding * 2,
            height = maxOf(40.dp, maxOf(openLabel.height, closeLabel.height).toDp() + 8.dp),
        )
    }
    val touch = EditorSettingsDefaults.TouchTarget
    val stacked = with(density) {
        width < EditorSettingsDefaults.RowInset * 2 + title.width.toDp() + 8.dp + touch * 2 + maxOf(touch, disclosureSize.width)
    }
    val actionHeight = maxOf(touch, disclosureSize.height)
    val header = with(density) {
        if (stacked) title.height.toDp() + 8.dp + actionHeight
        else maxOf(EditorSettingsDefaults.RowMinHeight, title.height.toDp() + 8.dp, actionHeight)
    }
    val expanded = batch.flag("expanded")
    return BatchTrayLayout(expanded, reveal ?: rememberBatchTrayReveal(expanded), stacked, header,
        with(density) { 16.sp.toDp() }, disclosureSize)
}

@Composable
internal fun BatchPhotoTray(state: Map<*, *>, command: UiCommand, modifier: Modifier, layout: BatchTrayLayout, compact: Boolean) {
    val batch = state.obj("batch")
    val photos = batch.rows("items")
    val id = batch.text("currentId")
    val index = photos.indexOfFirst { it.text("id") == id }
    if (index < 0) return
    val enabled = state.editable()
    val manager = LocalContext.current.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    val collapse = compact && !manager.isTouchExplorationEnabled
    val expanded = layout.expanded
    val bodyVisible by remember(layout) { derivedStateOf { layout.expanded || layout.progress > 0f } }
    val settled by remember(layout) { derivedStateOf { layout.progress == 0f || layout.progress == 1f } }
    var revision by remember(id) { mutableLongStateOf(state.longNumber("revision")) }
    val currentRevision = state.longNumber("revision")
    LaunchedEffect(currentRevision) {
        if (currentRevision != revision && collapse && expanded) command.send("batch.tray", "expanded" to false)
        revision = currentRevision
    }
    fun select(target: Int) {
        if (target in photos.indices) command.send("batch.edit", "id" to photos[target].text("id"), "collapse" to collapse)
    }
    EditorSettingsCard(modifier.testTag("batch.tray")) {
        Column(Modifier.clipToBounds().verticalScroll(rememberScrollState(), enabled = settled)) {
            BatchTrayHeader(index, photos.size, layout, enabled, ::select, onToggle = {
                command.send("batch.tray", "expanded" to !expanded)
            })
            if (bodyVisible) {
                BatchThumbnails(photos, index, layout, enabled, ::select, onAdd = { command.send("batch.add") })
            }
        }
    }
}

@Composable
private fun BatchTrayHeader(
    index: Int, count: Int, layout: BatchTrayLayout, enabled: Boolean,
    select: (Int) -> Unit, onToggle: () -> Unit,
) {
    val title: @Composable (Modifier) -> Unit = { placement ->
        Text("照片 ${index + 1} / $count", placement.testTag("batch.count"),
            style = MiuixTheme.textStyles.main, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
    val actions: @Composable () -> Unit = {
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val colors = MiuixTheme.colorScheme
        for (previous in listOf(true, false)) {
            val target = if (previous) index - 1 else index + 1
            val available = enabled && target in 0 until count
            val description = if (previous) "上一张照片" else "下一张照片"
            IconButton(hapticClick(HapticFeedbackType.SegmentTick) { select(target) }, enabled = available,
                modifier = Modifier.size(EditorSettingsDefaults.TouchTarget)
                    .testTag(if (previous) "batch.previous" else "batch.next")
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = description
                        if (!available) disabled()
                    }) {
                Icon(MiuixIcons.ChevronForward, null,
                    Modifier.size(20.dp).rotate(if (previous != rtl) 180f else 0f),
                    tint = if (available) colors.onSurface else colors.disabledOnSurface)
            }
        }
        BatchTrayDisclosure(layout, enabled, onToggle)
    }
    val header = Modifier.fillMaxWidth().height(layout.headerHeight).padding(horizontal = EditorSettingsDefaults.RowInset)
    if (layout.stacked) {
        Column(header, verticalArrangement = Arrangement.Center) {
            title(Modifier.padding(vertical = 4.dp))
            Row(Modifier.align(Alignment.End).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    } else Row(header, verticalAlignment = Alignment.CenterVertically) {
        title(Modifier.weight(1f).padding(end = 8.dp))
        actions()
    }
}

@Composable
private fun BatchTrayDisclosure(layout: BatchTrayLayout, enabled: Boolean, onToggle: () -> Unit) {
    val expanded = layout.expanded
    val face = layout.disclosureSize
    val effects = LocalStarMarkEffects.current
    val colors = MiuixTheme.colorScheme
    val dark = colors.background.luminance() < .5f
    val interaction = remember { MutableInteractionSource() }
    val held by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    var taps by remember { mutableIntStateOf(0) }
    val pressure = rememberNavigationPress(held, taps, enabled, effects.reduceMotion)
    val label = if (expanded) "收起" else "展开"
    val radius = with(LocalDensity.current) { (face.height.roundToPx() / 2f).toDp() }
    val shape = remember(radius) { MiuixCornerShape(radius, CornerKind.Capsule) }
    val backdrop = if (effects.glass && !effects.systemGlass) rememberLayerBackdrop() else null
    val touch = EditorSettingsDefaults.TouchTarget
    Box(Modifier.size(maxOf(touch, face.width), maxOf(touch, face.height)).testTag("batch.expand")
        .semantics(mergeDescendants = true) {
            contentDescription = label
            stateDescription = if (expanded) "照片栏已展开" else "照片栏已收起"
        }
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClickLabel = "${label}照片栏",
            onClick = hapticClick {
                taps++
                onToggle()
            }), contentAlignment = Alignment.Center) {
        // Portable glass samples the card, excluding the button and its label.
        if (backdrop != null) Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(glassNestedFill(colors.surfaceContainer)))
        CompositionLocalProvider(LocalChromeBackdrop provides backdrop) {
            ChromeSurface(Modifier.size(face), cornerRadius = radius, cornerKind = CornerKind.Capsule,
                role = ChromeMaterialRole.Navigation, shadowElevation = if (effects.glass) 2.dp else 0.dp) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val pressed = navigationOverlay(dark, pressed = true)
                    val resting = when {
                        effects.reduceMotion && held -> pressed
                        enabled && (focused || hovered) -> navigationOverlay(dark, hovered = true)
                        !effects.glass -> navigationOverlay(dark)
                        else -> Color.Transparent
                    }
                    Box(Modifier.matchParentSize().navigationPressFace(shape, resting, inset = 2.dp,
                        progress = { pressure.value }, pressedColor = pressed))
                    Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DisclosureLabelGap)) {
                        Box(contentAlignment = Alignment.Center) {
                            for (open in listOf(false, true)) {
                                Text(if (open) "收起" else "展开", Modifier.graphicsLayer {
                                    val weight = if (open) layout.progress else 1f - layout.progress
                                    alpha = ((weight - .35f) / .65f).coerceIn(0f, 1f)
                                }, style = MiuixTheme.textStyles.main, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    color = if (enabled) colors.onSurface else colors.disabledOnSurface, maxLines = 1, softWrap = false)
                            }
                        }
                        Icon(ReferenceIcons.DisclosureDown, null, Modifier.size(DisclosureCaretSize).graphicsLayer { rotationZ = 180f * layout.progress },
                            tint = if (enabled) colors.onSurfaceVariantActions else colors.disabledOnSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchThumbnails(
    photos: List<Map<*, *>>, index: Int, layout: BatchTrayLayout, enabled: Boolean,
    select: (Int) -> Unit, onAdd: () -> Unit,
) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = index)
    LaunchedEffect(photos[index].text("id")) { if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index) }
    LazyRow(Modifier.fillMaxWidth().graphicsLayer { alpha = layout.progress }.testTag("batch.thumbnails"),
        state = list, contentPadding = PaddingValues(start = EditorSettingsDefaults.RowInset, end = EditorSettingsDefaults.RowInset, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(EditorStripDefaults.Gap)) {
        itemsIndexed(photos, key = { _, p -> p.text("id") }) { i, p ->
            val shape = MiuixCornerShape(EditorStripDefaults.FrameRadius)
            val interaction = remember { MutableInteractionSource() }
            val active = enabled && layout.expanded
            Column(Modifier.width(EditorStripDefaults.FrameWidth).stockPress(interaction, StockPressKind.Watermark, active)
                .clickable(interaction, indication = null, enabled = active, role = Role.Button,
                    onClick = hapticClick(HapticFeedbackType.SegmentTick, feedbackEnabled = i != index) { select(i) })
                .testTag("batch.pick.$i").semantics {
                    selected = i == index
                    contentDescription = "第 ${i + 1} 张照片，${p.text("title")}"
                    if (p.flag("adjusted")) stateDescription = "已调整"
                },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(EditorStripDefaults.FrameHeight).clip(shape)
                    .then(if (i == index) Modifier.border(EditorStripDefaults.Stroke, CameraWatermarkYellow, shape) else Modifier)) {
                    PhotoThumbnail(p.text("image"), Modifier.fillMaxSize().padding(EditorStripDefaults.Inset),
                        contentScale = ContentScale.Fit, cornerRadius = EditorStripDefaults.ImageRadius)
                    if (p.flag("adjusted")) WatermarkConfigBadge(Modifier.align(Alignment.BottomEnd)
                        .padding(EditorStripDefaults.Inset + 2.dp).size(EditorStripDefaults.Badge))
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.height(layout.captionHeight), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
                }
            }
        }
        item(key = "add") {
            Box(Modifier.height(EditorStripDefaults.FrameHeight), contentAlignment = Alignment.Center) {
                IconButton(hapticClick { onAdd() }, enabled = enabled && layout.expanded,
                    modifier = Modifier.size(EditorSettingsDefaults.TouchTarget).testTag("batch.tray.add")) {
                    Icon(MiuixIcons.Add, "添加照片", Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
internal fun BatchConfirmations(state: Map<*, *>, command: UiCommand) {
    val batch = state.obj("batch")
    val pending = batch.flag("confirmContent")
    fun resolve(decision: String) {
        if (pending) command.send("batch.resolveContent", "decision" to decision)
    }
    ChromeDialog(pending, title = "当前照片有未应用的内容", onDismissRequest = { resolve("cancel") }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton("应用并继续", hapticClick(feedbackEnabled = pending) { resolve("apply") }, Modifier.fillMaxWidth().testTag("batch.pending.apply"), colors = ButtonDefaults.textButtonColorsPrimary())
            TextButton("放弃未应用内容", hapticClick(feedbackEnabled = pending) { resolve("discard") }, Modifier.fillMaxWidth().testTag("batch.pending.discard"))
            TextButton("继续编辑", hapticClick(feedbackEnabled = pending) { resolve("cancel") }, Modifier.fillMaxWidth().testTag("batch.pending.cancel"))
        }
    }
    val name = batch.text("confirmStyle")
    val count = batch.integer("selectedCount")
    val showing = name.isNotBlank()
    var previous by remember { mutableStateOf("" to 0) }
    if (showing) SideEffect { previous = name to count }
    val (displayName, displayCount) = if (showing) name to count else previous
    fun confirm(yes: Boolean) {
        if (showing) command.send("batch.confirmStyle", "confirmed" to yes)
    }
    ChromeDialog(showing, title = if (displayCount > 0) "将水印应用到选中照片" else "设置批量默认水印",
        summary = if (displayCount > 0) "用「$displayName」替换选中的 $displayCount 张照片的水印，并用于后续添加的照片" else "后续添加的照片将使用「$displayName」",
        onDismissRequest = { confirm(false) }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(EditorSettingsDefaults.GroupSpacing)) {
            TextButton("取消", hapticClick(feedbackEnabled = showing) { confirm(false) }, Modifier.weight(1f).testTag("batch.cancelStyle"))
            TextButton("确认", hapticClick(feedbackEnabled = showing) { confirm(true) }, Modifier.weight(1f).testTag("batch.confirmStyle"), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}
