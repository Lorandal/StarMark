package dev.starmark.starmark.ui

import dev.starmark.starmark.ui.g2.SwitchPreference

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun styleRows(state: Map<*, *>): List<Map<*, *>> = (state["styles"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()

@Composable
internal fun WatermarkPickerPage(state: Map<*, *>, command: UiCommand, chromeInsets: PaddingValues = PaddingValues()) {
    val enabled = state.obj("document").flag("enabled", true)
    val rows = styleRows(state)
    val selecting = state.obj("watermarkSelection").flag("active")
    LazyColumn(Modifier.fillMaxSize().testTag("screen.watermarks"), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp).withChromeInsets(chromeInsets), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        item {
            Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                SwitchPreference(enabled, { command.patch("照片水印开关", "enabled" to it) }, "照片水印", enabled = state.editable() && !selecting, modifier = Modifier.testTag("watermarks.enabled"))
            }
        }
        rows.groupBy { it.text("category", "custom") }.forEach { (category, group) ->
            item(key = "group.$category") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(group.first().text("categoryTitle", "自定义水印"), modifier = Modifier.padding(horizontal = 28.dp), style = MiuixTheme.textStyles.subtitle)
                    LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("watermarks.group.$category")) {
                        items(group, key = { it.text("id") }) { row ->
                            // Reviewed cloud previews are all 5:4. Match the image box;
                            // keep the full image inside a four-dp ring and four-dp gap.
                            val previewHeight = if (row.text("id").startsWith("factory-")) (160f / 1.25f + 16f).dp
                                else if (category == "film") 216.dp else 176.dp
                            Column {
                            WatermarkTile(row, if (selecting) row.flag("checked") else enabled && row.flag("selected"), 176.dp, MiuixTheme.colorScheme.primary, state.editable(), height = previewHeight, library = true, selecting = selecting,
                                onLongClick = { command.send("watermark.selection.begin", "id" to row.text("id")) }) {
                                if (selecting) command.send("watermark.selection.toggle", "id" to row.text("id"))
                                else if (row.flag("preserve")) command.send("style.describe", "id" to row.text("id"))
                                else if (enabled && row.flag("selected")) command.send("template.edit", "id" to row.text("id"))
                                else command.send("style.apply", "id" to row.text("id"), "stayOnPage" to (state.text("returnPage") != "batch"))
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
    val strip = height == 64.dp
    val image = if (strip) row.text("stripImage", row.text("image")) else row.text("image")
    val gap = if (library) 16f else 8f
    var aspect by remember(image) { mutableFloatStateOf(if (row.text("id").startsWith("factory-")) 1.25f else (edge.value - gap) / (height.value - gap)) }
    val shape = MiuixCornerShape(if (strip) 6.dp else 12.dp)
    val interactionSource = remember(row.text("id")) { MutableInteractionSource() }
    Box(
        modifier.sizeIn(maxWidth = edge, maxHeight = height)
            .stockPress(interactionSource, StockPressKind.Watermark, enabled)
            .clip(shape)
            .then(if (selected) Modifier.border(BorderStroke(if (library) 4.dp else 2.dp, accent), shape) else Modifier)
            .combinedClickable(interactionSource = interactionSource, indication = null, enabled = enabled, role = Role.Button, onClickLabel = if (selecting && selected) "取消选择水印" else if (!selecting && library && selected) "编辑模板" else "选择水印", onLongClickLabel = if (onLongClick != null) "选择水印" else null, onLongClick = onLongClick,
                onClick = hapticClick(if (selecting) toggleHaptic(selected) else if (library && selected) HapticFeedbackType.ContextClick else HapticFeedbackType.SegmentTick,
                    feedbackEnabled = library || !selected, onClick = onClick))
            .semantics { this.selected = selected }
            .testTag("watermark.${row.text("id")}"),
    ) {
        // Fit the actual bitmap inside the available constraints first; the same
        // inset then defines every edge of the ring, even on a short editor pane.
        PhotoThumbnail(image, Modifier.padding((gap / 2).dp).aspectRatio(aspect), description = row.text("title"), contentScale = androidx.compose.ui.layout.ContentScale.Fit, cornerRadius = if (strip) 2.dp else if (library) 4.dp else 8.dp,
            onAspectRatio = { if (it.isFinite() && it > 0f) aspect = it })
        if (selecting) Box(Modifier.matchParentSize()) {
            Checkbox(if (selected) ToggleableState.On else ToggleableState.Off, onClick = onClick, enabled = enabled,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("watermark.checked.${row.text("id")}"))
        } else if (selected) Box(Modifier.matchParentSize()) {
            WatermarkConfigBadge(if (library) Modifier.align(Alignment.Center).size(32.dp).testTag("watermarks.edit.${row.text("id")}") else Modifier.align(Alignment.TopEnd).padding(8.dp).size(16.dp))
        }
    }
}

/** Exact 16x16 config badge from drawable/photo_watermark_menu_item_icon_config (res/0Vb.xml). */
@Composable
private fun WatermarkConfigBadge(modifier: Modifier) {
    val background = remember { androidx.compose.ui.graphics.Path().apply { addG2Rect(16f, 16f, 2f) } }
    val pencil = remember { PathParser().parsePathString("M4.542,9.047L4.542,10.055C4.542,10.166 4.632,10.255 4.742,10.255L5.75,10.255C5.882,10.255 6.009,10.203 6.103,10.109L11.082,5.131C11.16,5.052 11.16,4.926 11.082,4.848L9.925,3.692C9.847,3.613 9.72,3.614 9.642,3.692L4.687,8.695C4.594,8.789 4.542,8.915 4.542,9.047Z").toPath() }
    Canvas(modifier) {
        scale(size.width / 16f, size.height / 16f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(background, Color(0x80818181))
            drawPath(pencil, Color.White, style = Stroke(1f))
            drawRoundRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(4f, 11.564f), size = androidx.compose.ui.geometry.Size(8f, 1f), cornerRadius = CornerRadius(.2f))
        }
    }
}
