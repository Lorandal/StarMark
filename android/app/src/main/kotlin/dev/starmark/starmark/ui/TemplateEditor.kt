package dev.starmark.starmark.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
internal fun TemplateEditorTopBar(state: Map<*, *>, command: UiCommand) {
    val enabled = state.flag("ready") && state.editable()
    val history = state.obj("history")
    val saveWidth = (34f * LocalDensity.current.fontScale + 34f).dp
    BoxWithConstraints(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp)) {
        val titleInset = saveWidth + 48.dp + 8.dp
        val titleMaxWidth = maxWidth - titleInset * 2
        val separateTitle = titleMaxWidth < with(LocalDensity.current) { 54.sp.toDp() }
        val title: @Composable (Modifier) -> Unit = { modifier ->
            Text(state.obj("template").text("title"), modifier.testTag("template.title").semantics { heading() },
                fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("template.toolbar")) {
                Box(Modifier.align(Alignment.CenterStart)) {
                    CameraGlassButton("取消编辑", "navigation.back", enabled, onClick = { command.send("navigate.back") }) {
                        Icon(ReferenceIcons.Back, null, Modifier.size(28.dp))
                    }
                }
                Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CameraGlassButton("保存", "template.save", enabled, width = saveWidth, onClick = { command.send("template.save") }) {
                        Text("保存", fontSize = 17.sp, maxLines = 1)
                    }
                    MorphingMoreMenu(listOf(
                        MorphMenuEntry("history.undo", "撤销", MiuixIcons.Undo, history.flag("canUndo"), tag = "history.undo"),
                        MorphMenuEntry("history.redo", "重做", MiuixIcons.Redo, history.flag("canRedo"), tag = "history.redo"),
                        MorphMenuEntry("template.reset", "恢复默认", MiuixIcons.Refresh, tag = "template.reset"),
                        MorphMenuEntry("navigate.back", "取消编辑", MiuixIcons.Close),
                    ), onAction = { command.send(it) }, enabled = enabled, modifier = Modifier.size(48.dp), tag = "template.actions")
                }
                if (!separateTitle) title(Modifier.align(Alignment.Center).widthIn(max = titleMaxWidth))
            }
            if (separateTitle) title(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

internal val TemplatePreviewRadius = 10.dp

/** One scroll owner; the actual Flutter child is clipped by MiuixHostLayout. */
@Composable
internal fun TemplateEditorBody(state: Map<*, *>, command: UiCommand, open: (String) -> Unit, insets: PaddingValues,
    bounds: (Rect?) -> Unit, clip: (Rect?, Float) -> Unit) {
    val density = LocalDensity.current
    val radius = with(density) { TemplatePreviewRadius.toPx() }
    var viewport by remember { mutableStateOf<Rect?>(null) }
    val currentBounds by rememberUpdatedState(bounds)
    val currentClip by rememberUpdatedState(clip)
    SideEffect { currentClip(viewport, radius) }
    DisposableEffect(Unit) { onDispose { currentBounds(null); currentClip(null, 0f) } }
    val layout = state.obj("layout")
    val photo = state.obj("photo")
    val ratio = layout.number("width", photo.number("width", 1f)).coerceAtLeast(1f) / layout.number("height", photo.number("height", 1f)).coerceAtLeast(1f)
    // Keep both insets inside the scroll range. The viewport covers the gesture area;
    // at the end of the list the final control rests above the gesture or IME.
    val direction = LocalLayoutDirection.current
    val sidesAndTop = PaddingValues(start = insets.calculateStartPadding(direction), top = insets.calculateTopPadding(),
        end = insets.calculateEndPadding(direction))
    Column(Modifier.fillMaxSize().imePadding().testTag("screen.templateEditor")
        .onGloballyPositioned { viewport = it.findRootCoordinates().localBoundingBoxOf(it, clipBounds = true) }
        .verticalScroll(rememberScrollState()).padding(PaddingValues(horizontal = 12.dp, vertical = 16.dp).withChromeInsets(sidesAndTop)),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val width = minOf(maxWidth, 240.dp, 240.dp * ratio)
            Box(Modifier.size(width, width / ratio).testTag("template.preview").onGloballyPositioned {
                currentBounds(it.findRootCoordinates().localBoundingBoxOf(it, clipBounds = false))
                currentClip(viewport, radius)
            })
        }
        Spacer(Modifier.height(12.dp))
        if (state.obj("staruw").flag("enabled")) {
            StaruwControlsPanel(state, command)
        } else {
        PositionPanel(state, command, open, {})
        if (state.obj("native").flag("enabled")) NativeLayoutPanel(state, command, showLayouts = false)
        EditorSettingsSection("内容与签名", Modifier.testTag("template.content")) {
            ContentPanel(state, command, open, {})
        }
        EditorSettingsSection("字体") {
            ImportedFontPicker(state, command)
        }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/** Local staging avoids an OEM continuous transform; only a completed gesture commits. */
@Composable
internal fun TemplateSizeSlider(state: Map<*, *>, command: UiCommand) {
    val external = if (state.obj("native").flag("enabled")) state.obj("native").number("scale", 1f) else state.obj("document").number("scale", 1f)
    val selected = WatermarkSizePreset.entries.indices.minBy { kotlin.math.abs(WatermarkSizePreset.entries[it].scale - external) }
    val revision = state.longNumber("revision")
    val gesture = remember(revision, selected) { TemplateSizeGesture() }
    var value by remember(selected, revision) { mutableIntStateOf(selected) }
    val latestRevision by rememberUpdatedState(revision)
    val currentCommand by rememberUpdatedState(command)
    val enabled = state.editable()
    val haptics = LocalHapticFeedback.current
    val trackPath = remember { androidx.compose.ui.graphics.Path() }
    val colors = MiuixTheme.colorScheme
    fun stage(index: Int) {
        if (enabled && latestRevision == revision && value != index) {
            haptics.performHapticFeedback(if (index == 0 || index == 2)
                HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.TextHandleMove)
            value = index
        }
    }
    fun commit(index: Int) {
        if (enabled && latestRevision == revision && templateSizeSelectionChanges(external, index)) {
            currentCommand.send("interaction.cancel")
            currentCommand.patch("水印内容大小", "scale" to WatermarkSizePreset.entries[index].scale)
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("A", fontSize = 14.sp)
        Canvas(Modifier.weight(1f).height(48.dp).testTag("position.scale")
            .semantics { contentDescription = "内容大小"; stateDescription = WatermarkSizePreset.entries[value].title
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..2f, 1)
                if (enabled) setProgress { next -> val index = next.roundToInt().coerceIn(0, 2); stage(index); commit(index); true } else disabled()
            }
            .pointerInput(enabled, revision, selected) {
                if (enabled) detectTapGestures { point -> val index = ((point.x - 14.dp.toPx()) / (size.width - 28.dp.toPx()).coerceAtLeast(1f) * 2).roundToInt().coerceIn(0, 2); stage(index); commit(index) }
            }
            .pointerInput(enabled, revision, selected) {
                if (enabled) detectHorizontalDragGestures(
                    onDragStart = { gesture.begin(revision, selected) },
                    onDragCancel = { gesture.cancel(); value = selected }, onDragEnd = { gesture.finish(latestRevision)?.let(::commit) },
                    onHorizontalDrag = { change, _ -> change.consume(); val index = ((change.position.x - 14.dp.toPx()) / (size.width - 28.dp.toPx()).coerceAtLeast(1f) * 2).roundToInt().coerceIn(0, 2); stage(index); gesture.update(value) },
                )
            }) {
            val inset = 14.dp.toPx(); val y = size.height / 2; val end = size.width - inset
            drawG2Track(trackPath, colors.onSurface.copy(alpha = .06f), Offset(inset, y), Offset(end, y), 28.dp.toPx())
            val x = inset + (end - inset) * value / 2
            drawG2Track(trackPath, colors.primary, Offset(inset, y), Offset(x, y), 28.dp.toPx())
            repeat(3) { i -> drawCircle(if (i <= value) Color.White.copy(alpha = .4f) else colors.onSurface.copy(alpha = .1f), 4.dp.toPx(), Offset(inset + (end - inset) * i / 2, y)) }
            drawCircle(Color.White, 10.dp.toPx(), Offset(x, y))
        }
        Text("A", fontSize = 28.sp)
    }
}

/** No document mutation until finish; revisions, cancellation and duplicate release are inert. */
internal class TemplateSizeGesture {
    private var revision: Long? = null
    private var initial = 1
    private var latest = 1
    fun begin(revision: Long, selected: Int) { this.revision = revision; initial = selected; latest = selected }
    fun update(index: Int) { if (revision != null) latest = index.coerceIn(0, 2) }
    fun cancel() { revision = null }
    fun finish(currentRevision: Long): Int? {
        val result = latest.takeIf { revision == currentRevision && it != initial }
        cancel()
        return result
    }
}

internal fun templateSizeSelectionChanges(external: Float, index: Int): Boolean =
    kotlin.math.abs(WatermarkSizePreset.entries[index].scale - external.toDouble()) >= .001
