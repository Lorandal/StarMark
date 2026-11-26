package dev.starmark.starmark.ui

import dev.starmark.starmark.ui.g2.SwitchPreference

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun styleRows(state: Map<*, *>): List<Map<*, *>> = (state["styles"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()

/** Geometry of the editor's compact 5:4 preview, including its selection ring. */
internal object EditorStripDefaults {
    val ImageHeight = 56.dp
    val Inset = 4.dp
    val FrameWidth = ImageHeight * 1.25f + Inset * 2
    val FrameHeight = ImageHeight + Inset * 2
    val FrameRadius = 6.dp
    val ImageRadius = FrameRadius - Inset
    val Stroke = 2.dp
    val Gap = 9.dp
    val Badge = 16.dp
}

@Composable
internal fun WatermarkPickerPage(state: Map<*, *>, command: UiCommand, chromeInsets: PaddingValues = PaddingValues(),
    galleryRightInset: Dp = 0.dp, editSelectedInPhoto: (() -> Unit)? = null) {
    val enabled = state.obj("document").flag("enabled", true)
    val rows = styleRows(state)
    val standalone = editSelectedInPhoto == null
    val selecting = standalone && state.obj("watermarkSelection").flag("active")
    val rowPadding = if (standalone) PaddingValues(0.dp)
        else PaddingValues.Absolute(left = 18.dp, right = maxOf(18.dp, galleryRightInset))
    val rowModifier = if (standalone) Modifier.padding(horizontal = EditorSettingsDefaults.PageInset) else Modifier
    val titleStyle = if (standalone) MiuixTheme.textStyles.body1.copy(
        fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium
    ) else MiuixTheme.textStyles.subtitle
    LazyColumn(Modifier.fillMaxSize().testTag("screen.watermarks"),
        contentPadding = PaddingValues(top = if (standalone) 4.dp else 12.dp, bottom = 24.dp).withChromeInsets(chromeInsets),
        verticalArrangement = Arrangement.spacedBy(if (standalone) 38.dp else 28.dp)) {
        if (standalone) item(key = "enabled") {
            EditorSettingsCard(Modifier.padding(horizontal = EditorSettingsDefaults.PageInset)) {
                SwitchPreference(enabled, { command.patch("照片水印开关", "enabled" to it) }, "照片水印",
                    enabled = state.editable() && !selecting, modifier = Modifier.testTag("watermarks.enabled"))
            }
        }
        rows.groupBy { it.text("category", "custom") }.forEach { (category, group) ->
            item(key = "group.$category") {
                val initialIndex = if (standalone && enabled && !selecting) group.indexOfFirst { it.flag("selected") }.coerceAtLeast(0) else 0
                val scrollState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
                Column(verticalArrangement = Arrangement.spacedBy(if (standalone) 8.dp else 12.dp)) {
                    Text(group.first().text("categoryTitle", "自定义水印"), style = titleStyle,
                        modifier = Modifier.padding(horizontal = 28.dp).semantics { heading() })
                    LazyRow(state = scrollState, contentPadding = rowPadding,
                        horizontalArrangement = Arrangement.spacedBy(if (standalone) 0.dp else 16.dp),
                        modifier = rowModifier.fillMaxWidth().testTag("watermarks.group.$category")
                            .semantics { collectionInfo = CollectionInfo(1, group.size) }) {
                        itemsIndexed(group, key = { _, row -> row.text("id") }) { index, row ->
                            val previewHeight = if (row.text("id").startsWith("factory-")) (160f / 1.25f + 16f).dp
                                else if (category == "film") 216.dp else 176.dp
                            Column {
                                WatermarkTile(row, if (selecting) row.flag("checked") else enabled && row.flag("selected"),
                                    176.dp, MiuixTheme.colorScheme.primary, state.editable(), height = previewHeight,
                                    modifier = Modifier.semantics { collectionItemInfo = CollectionItemInfo(0, 1, index, 1) },
                                    library = true, selecting = selecting,
                                    onLongClick = if (standalone) ({ command.send("watermark.selection.begin", "id" to row.text("id")) }) else null) {
                                    if (selecting) command.send("watermark.selection.toggle", "id" to row.text("id"))
                                    else if (row.flag("preserve")) command.send("style.describe", "id" to row.text("id"))
                                    else if (enabled && row.flag("selected")) {
                                        if (editSelectedInPhoto != null) editSelectedInPhoto() else command.send("template.edit", "id" to row.text("id"))
                                    } else command.send("style.apply", "id" to row.text("id"), "stayOnPage" to (!standalone || state.text("returnPage") != "batch"))
                                }
                                if (row.flag("preserve")) SupportingText("仅保留原包", Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("watermark.preserve.${row.text("id")}"))
                            }
                        }
                    }
                }
            }
        }
        if (rows.isEmpty()) item { SupportingText("正在载入原厂水印…", Modifier.padding(24.dp)) }
    }
}

@Composable
internal fun WatermarkTile(row: Map<*, *>, selected: Boolean, edge: Dp, accent: Color, enabled: Boolean, height: Dp = edge, modifier: Modifier = Modifier, library: Boolean = false, selecting: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val strip = height == EditorStripDefaults.FrameHeight
    val image = if (strip) row.text("stripImage", row.text("image")) else row.text("image")
    val inset = if (library) 8.dp else EditorStripDefaults.Inset
    val gap = inset.value * 2
    var aspect by remember(image) { mutableFloatStateOf(if (row.text("id").startsWith("factory-")) 1.25f else (edge.value - gap) / (height.value - gap)) }
    val shape = MiuixCornerShape(if (strip) EditorStripDefaults.FrameRadius else 12.dp)
    val interactionSource = remember(row.text("id")) { MutableInteractionSource() }
    val libraryIndicator = library && !selecting
    val clickLabel = when {
        selecting && selected -> "取消选择水印"
        !selecting && library && row.flag("preserve") -> "查看原包说明"
        !selecting && library && selected -> "编辑模板"
        else -> "选择水印"
    }
    Box(
        modifier.sizeIn(maxWidth = edge, maxHeight = height)
            .stockPress(interactionSource, StockPressKind.Watermark, enabled)
            .clip(shape)
            .then(if (selected && !libraryIndicator) Modifier.border(BorderStroke(if (library) 4.dp else EditorStripDefaults.Stroke, accent), shape) else Modifier)
            .combinedClickable(interactionSource = interactionSource, indication = null, enabled = enabled, role = Role.Button, onClickLabel = clickLabel, onLongClickLabel = if (onLongClick != null) "选择水印" else null, onLongClick = onLongClick,
                onClick = hapticClick(if (selecting) toggleHaptic(selected) else if (library && selected) HapticFeedbackType.ContextClick else HapticFeedbackType.SegmentTick,
                    feedbackEnabled = library || !selected, onClick = onClick))
            .semantics { this.selected = selected }
            .testTag("watermark.${row.text("id")}"),
    ) {
        // Fit the actual bitmap inside the available constraints first; the same
        // inset then defines every edge of the ring, even on a short editor pane.
        PhotoThumbnail(image, Modifier.padding(inset).aspectRatio(aspect), description = row.text("title"), contentScale = androidx.compose.ui.layout.ContentScale.Fit, cornerRadius = if (strip) EditorStripDefaults.ImageRadius else if (library) 4.dp else 8.dp,
            onAspectRatio = { if (it.isFinite() && it > 0f) aspect = it })
        if (selecting) Box(Modifier.matchParentSize()) {
            Checkbox(if (selected) ToggleableState.On else ToggleableState.Off, onClick = onClick, enabled = enabled,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("watermark.checked.${row.text("id")}"))
        } else if (libraryIndicator) {
            val strength = animateFloatAsState(if (selected) 1f else 0f,
                animationSpec = if (LocalStarMarkEffects.current.reduceMotion) snap()
                    else spring(dampingRatio = 1f, stiffness = if (selected) 215f else 165f, visibilityThreshold = .005f),
                label = "watermarkSelection")
            val visible by remember { derivedStateOf { strength.value > 0f } }
            if (selected || visible) Box(Modifier.matchParentSize().graphicsLayer { alpha = strength.value }
                .border(4.dp, accent, shape)) {
                if (!row.flag("preserve")) LibraryEditIcon(Modifier.align(Alignment.Center).size(28.dp)
                    .then(if (selected) Modifier.testTag("watermarks.edit.${row.text("id")}") else Modifier))
            }
        } else if (selected) Box(Modifier.matchParentSize()) {
            WatermarkConfigBadge(Modifier.align(Alignment.TopEnd).padding(8.dp).size(EditorStripDefaults.Badge))
        }
    }
}

/** Full-size camera library indicator. Only the pencil covers the photograph. */
@Composable
private fun LibraryEditIcon(modifier: Modifier) {
    val pencil = remember { PathParser().parsePathString(
        "M5.05,19.0 L18.7,5.2 Q19.25,4.65 19.85,5.23 L22.48,7.82 Q22.98,8.37 22.42,8.94 " +
        "L9.12,22.31 Q8.87,22.57 8.48,22.57 H5.49 Q5.03,22.57 5.03,22.1 Z " +
        "M14.95,20.18 H22.18 Q22.63,20.18 22.63,20.64 V22.18 Q22.63,22.57 22.18,22.57 " +
        "H13.05 Q12.62,22.57 12.94,22.19 Z"
    ).toPath() }
    Canvas(modifier) {
        withTransform({ scale(size.width / 28f, size.height / 28f, pivot = androidx.compose.ui.geometry.Offset.Zero) }) {
            drawPath(pencil, Color.Black.copy(alpha = .4f), style = Stroke(width = .7f, join = StrokeJoin.Round))
            drawPath(pencil, Color.White)
        }
    }
}

/** Exact 16x16 config badge from drawable/photo_watermark_menu_item_icon_config (res/0Vb.xml). */
@Composable
internal fun WatermarkConfigBadge(modifier: Modifier) {
    val background = remember { androidx.compose.ui.graphics.Path().apply { addG2Rect(16f, 16f, 2f) } }
    val pencil = remember { PathParser().parsePathString("M4.542,9.047L4.542,10.055C4.542,10.166 4.632,10.255 4.742,10.255L5.75,10.255C5.882,10.255 6.009,10.203 6.103,10.109L11.082,5.131C11.16,5.052 11.16,4.926 11.082,4.848L9.925,3.692C9.847,3.613 9.72,3.614 9.642,3.692L4.687,8.695C4.594,8.789 4.542,8.915 4.542,9.047Z").toPath() }
    Canvas(modifier) {
        val side = size.minDimension
        withTransform({
            translate((size.width - side) / 2f, (size.height - side) / 2f)
            scale(side / 16f, side / 16f, pivot = androidx.compose.ui.geometry.Offset.Zero)
        }) {
            drawPath(background, Color(0x80818181))
            drawPath(pencil, Color.White, style = Stroke(1f))
            drawRoundRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(4f, 11.564f), size = androidx.compose.ui.geometry.Size(8f, 1f), cornerRadius = CornerRadius(.2f))
        }
    }
}
