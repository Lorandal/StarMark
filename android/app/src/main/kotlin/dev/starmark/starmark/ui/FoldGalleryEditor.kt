package dev.starmark.starmark.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun FoldGalleryEditor(state: Map<*, *>, command: UiCommand, dialog: (String) -> Unit,
                              trayReveal: State<Float>?, onViewport: (Rect, Rect, Float) -> Unit) {
    val deviceDensity = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().testTag("screen.editor")) {
        // A single physical unit for both axes keeps circles, glyphs and text undistorted.
        val unit = (minOf(constraints.maxWidth / 2364f, constraints.maxHeight / 1672f) * 112f / 44f)
            .coerceIn(deviceDensity.density * .8f, deviceDensity.density * 1.15f)
        val actualGapPx = with(deviceDensity) { 12.dp.toPx() }
        val actualLiftPx = with(deviceDensity) { 8.dp.toPx() }
        val batch = state.obj("batch")
        val tray = if (batch.flag("active") && WindowInsets.ime.getBottom(deviceDensity) == 0)
            batchTrayLayout(maxWidth * (1198f / 2364f), batch, trayReveal) else null
        CompositionLocalProvider(LocalDensity provides Density(unit, deviceDensity.fontScale * .875f)) {
            FoldGalleryBody(state, command, dialog, (actualGapPx / unit).dp, (actualLiftPx / unit).dp, deviceDensity, tray, onViewport)
        }
    }
}

@Composable
private fun FoldGalleryBody(state: Map<*, *>, command: UiCommand, dialog: (String) -> Unit,
                            categoryGap: Dp, photoLift: Dp, deviceDensity: Density, tray: BatchTrayLayout?,
                            onViewport: (Rect, Rect, Float) -> Unit) {
    val photoId = state.obj("photo").text("id")
    var tool by rememberSaveable(photoId) { mutableStateOf("styles") }
    var nested by rememberSaveable(photoId) { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    fun choose(next: String) {
        focus.clearFocus()
        keyboard?.hide()
        if (tool != next || nested != null) command.send("interaction.cancel")
        tool = next
        nested = null
    }
    fun open(next: String) {
        when (next) {
            "styleOptions" -> choose("content")
            "border" -> choose("position")
            "content", "fonts", "position", "styles" -> choose(next)
            "history", "saveStyle" -> dialog(next)
            else -> { command.send("interaction.cancel"); nested = next }
        }
    }
    BackHandler(enabled = nested != null) { command.send("interaction.cancel"); nested = null }
    val tabs = remember { listOf(FloatingNavEntry("styles", "水印", MiuixIcons.Image),
        FloatingNavEntry("content", "内容", MiuixIcons.Edit), FloatingNavEntry("fonts", "字体", MiuixIcons.Rename, "Aa"),
        FloatingNavEntry("position", "位置", ReferenceIcons.Adjust)) }
    val metrics = editorTrailingNavigationMetrics(tabs)
    val bg = MiuixTheme.colorScheme.background
    val ready = state.flag("ready")
    val editable = state.editable()
    val rows = styleRows(state)
    val groups = remember(rows) { rows.groupBy { it.text("category", "custom") } }
    var groupKey by rememberSaveable(photoId) { mutableStateOf<String?>(null) }
    val active = groupKey?.takeIf { groups.containsKey(it) } ?: groups.keys.firstOrNull()
    val pageStore = rememberSaveableStateHolder()
    val categoryScroll = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val x = maxWidth / 2364
        val y = maxHeight / 1672
        val safe = WindowInsets.safeDrawing.asPaddingValues()
        val side = maxOf(x * 132, safe.calculateLeftPadding(direction), safe.calculateRightPadding(direction))
        val axis = maxWidth - side - 22.dp
        val faceTop = maxOf(y * 156, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 4.dp)
        val railBottom = maxHeight - maxOf(y * 104, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 4.dp)
        val railHeight = metrics.capsule.track.width.dp
        val railTop = railBottom - railHeight
        val redoBoxTop = railTop - 24.dp - 48.dp
        val undoBoxTop = redoBoxTop - HistoryButtonSpacing - 48.dp
        val categoriesTop = faceTop + 44.dp + categoryGap
        val categoriesBottom = undoBoxTop + 2.dp - categoryGap
        val stripWidth = 64.dp
        val stripLeft = axis - stripWidth / 2
        val photoLeft = x * 123
        val photoWidth = x * 1198
        val baselineMid = y * 885.5f
        val baselineChips = railBottom - (48.dp + 27.dp) / 2
        val halfViewport = minOf(baselineMid - (faceTop + 44.dp + 10.dp), baselineChips - 12.dp - baselineMid)
        val batch = state.obj("batch")
        val batchActive = batch.flag("active")
        val photoTop = baselineMid - halfViewport
        val photoSlotHeight = (halfViewport * 2).coerceAtLeast(1.dp)
        val liftPx = with(density) { photoLift.roundToPx() }
        val liftGroup = Modifier.absoluteOffset { androidx.compose.ui.unit.IntOffset(0, -liftPx) }
        val backdrop = rememberLayerBackdrop()
        val panelTop = y * 324
        val record = LocalStarMarkEffects.current.glass && isRuntimeShaderSupported()
        val headerScene = rememberLayerBackdrop(onDraw = {
            val scene = this
            // Only the top band and its blur kernel can reach the header.
            clipRect(bottom = (panelTop + 96.dp).toPx().coerceAtMost(size.height)) { scene.drawContent() }
        })
        val ime = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        val visiblePanelHeight = (maxHeight - ime).coerceAtLeast(1.dp)
        val scrollEnd = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp
        CompositionLocalProvider(LocalChromeBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize().then(if (record) Modifier.layerBackdrop(headerScene).graphicsLayer() else Modifier)) {
        // The recorded surface contains only the actual page background around the Flutter opening.
        // The gallery, categories, shortcut glyphs and chrome are all painted after this layer.
        Box(Modifier.fillMaxSize().then(if (record) Modifier.layerBackdrop(backdrop) else Modifier).drawBehind {
            val l = photoLeft.toPx(); val r = l + photoWidth.toPx()
            val trayPx = ((tray?.height?.value ?: 0f) * deviceDensity.density).coerceAtMost(photoSlotHeight.toPx() - 1.dp.toPx())
            val t = photoTop.toPx() - liftPx; val b = t + photoSlotHeight.toPx() - trayPx
            drawRect(bg, size = Size(size.width, t))
            drawRect(bg, Offset(0f, b), Size(size.width, size.height - b))
            drawRect(bg, Offset(0f, t), Size(l, b - t))
            drawRect(bg, Offset(r, t), Size(size.width - r, b - t))
        })
            val gridLeft = x * 1572
            val panelLeft = x * 1510
            val panelWidth = (stripLeft - 12.dp - panelLeft).coerceAtLeast(1.dp)
            Box(Modifier.absoluteOffset(x = panelLeft).size(panelWidth, visiblePanelHeight)
                .clipToBounds().background(bg).testTag("editor.controlsPane")) {
                ReferencePageTransition(nested ?: tool, toolOrder = tabs.map { it.id } + listOf("color", "models"),
                    trailingTabs = true) { shown, _ ->
                    pageStore.SaveableStateProvider(shown) {
                        if (shown == "styles") {
                            val gap = x * 30
                            val gridWidth = minOf(x * 440, panelWidth - (gridLeft - panelLeft))
                            val group = groups[active].orEmpty()
                            key(active) {
                                LazyVerticalGrid(GridCells.Fixed(2),
                                    modifier = Modifier.absoluteOffset(x = gridLeft - panelLeft).width(gridWidth).fillMaxHeight()
                                        .testTag("editor.gallery"),
                                    contentPadding = PaddingValues(top = panelTop, bottom = scrollEnd),
                                    horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(y * 31)) {
                                    items(group, key = { it.text("id") }) { row ->
                                        val id = row.text("id")
                                        val marked = state.obj("document").flag("enabled", true) && row.flag("selected")
                                        val shape = MiuixCornerShape(EditorStripDefaults.FrameRadius)
                                        val interaction = remember { MutableInteractionSource() }
                                        val chooseStyle = hapticClick(if (marked || row.flag("preserve")) HapticFeedbackType.ContextClick else HapticFeedbackType.SegmentTick) {
                                            when {
                                                row.flag("preserve") -> command.send("style.describe", "id" to id)
                                                marked -> choose("content")
                                                else -> command.send("style.apply", "id" to id, "stayOnPage" to true)
                                            }
                                        }
                                        Box(Modifier.fillMaxWidth().aspectRatio(1.25f)
                                            .stockPress(interaction, StockPressKind.Watermark, editable).clip(shape)
                                            .clickable(interaction, indication = null, enabled = editable, role = Role.Button, onClick = chooseStyle)
                                            .semantics { selected = marked }.testTag("watermark.$id")) {
                                            PhotoThumbnail(row.text("image"), Modifier.fillMaxSize().padding(EditorStripDefaults.Inset), row.text("title"),
                                                contentScale = ContentScale.Fit, cornerRadius = EditorStripDefaults.ImageRadius)
                                            if (marked) Box(Modifier.matchParentSize().border(EditorStripDefaults.Stroke, CameraWatermarkYellow, shape))
                                        }
                                    }
                                }
                            }
                        } else {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = panelTop, bottom = scrollEnd)
                                .testTag("editor.inline.$shown"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (shown == "color" || shown == "models") {
                                    Text("‹  返回", Modifier.clickable { nested = null }.padding(12.dp))
                                }
                                EditorPanel(shown, state, command, ::open, { nested = null }, stackModelField = true)
                            }
                        }
                    }
                }
            }
        }
            CompositionLocalProvider(LocalChromeBackdrop provides headerScene) {
                ChromeTopBar(foldEditorBand = panelTop) {}
            }
            if (tool == "styles" && nested == null && active != null && categoriesBottom > categoriesTop) {
                GalleryCategoryStrip(groups, active, categoryScroll, { groupKey = it },
                    Modifier.absoluteOffset(stripLeft, categoriesTop).size(stripWidth, categoriesBottom - categoriesTop))
            }
            EditorPhotoWithTray(state, command, liftGroup.absoluteOffset(photoLeft, photoTop).size(photoWidth, photoSlotHeight),
                null, onViewport, tray, compact = false, trayDensity = deviceDensity)
            if (ime == 0.dp) {
                Box(liftGroup.absoluteOffset(photoLeft, baselineChips).width(photoWidth).testTag("editor.photoShortcuts")) {
                    CameraWatermarkQuickControls(state, command, CameraWatermarkYellow, "经纬度", sidePadding = 0.dp, faceHeight = 27.dp) {
                        val on = state.obj("document").flag("enabled", true)
                        CameraWatermarkOption("水印", "watermarks.enabled", MiuixIcons.Image, on, true, editable, null,
                            CameraWatermarkYellow, if (on) "已开启" else "已关闭", faceHeight = 27.dp) { command.patch("照片水印开关", "enabled" to !on) }
                    }
                }
                Column(Modifier.absoluteOffset(axis - 24.dp, undoBoxTop), verticalArrangement = Arrangement.spacedBy(HistoryButtonSpacing)) {
                    CameraHistoryButtons(state, command, glyphSize = 24.5.dp)
                }
                Box(Modifier.absoluteOffset(axis - 24.dp, railTop)) {
                    FloatingNavigationCapsule(tabs, tool, ::choose, ready, editable && ready, metrics.capsule, true, "editor.tool", "editor.tool.rail")
                }
            }
            Box(Modifier.absoluteOffset(side - 2.dp, faceTop - 2.dp)) {
                CameraCircle("关闭编辑", "navigation.back", MiuixIcons.Close, ready, editable, glyphSize = 24.5.dp) { command.send("navigate.back") }
            }
            val saveFace = 44.dp * (162f / 112f)
            Box(Modifier.absoluteOffset(axis - 22.dp - x * 20 - saveFace - 2.dp, faceTop - 2.dp)) {
                val saveAction = if (batchActive) "batch.done" else "photo.export"
                val saveLabel = if (batchActive) "完成" else "保存"
                CameraGlassButton(saveLabel, saveAction, ready && editable, saveFace + 4.dp, { command.send(saveAction) }) {
                    Text(saveLabel, fontSize = 15.75.sp, maxLines = 1)
                }
            }
            Box(Modifier.absoluteOffset(axis - 24.dp, faceTop - 2.dp)) {
                MorphingMoreMenu(remember(batchActive) { photoMenuEntries(batchActive) },
                    onAction = { if (it == "history" || it == "saveStyle") dialog(it) else command.send(it) },
                    enabled = ready, interactionEnabled = editable, glyphSize = 22.dp)
            }
        }
    }
}

@Composable
private fun GalleryCategoryStrip(groups: Map<String, List<Map<*, *>>>, active: String,
                                 scroll: androidx.compose.foundation.lazy.LazyListState, select: (String) -> Unit, modifier: Modifier) {
    val bg = MiuixTheme.colorScheme.background
    val ink = MiuixTheme.colorScheme.onSurface
    val edge = 14.dp
    Box(modifier.background(bg).clipToBounds().testTag("editor.categories").drawWithCache {
        val band = minOf(edge.toPx(), size.height / 2)
        val clear = bg.copy(alpha = 0f)
        val top = Brush.verticalGradient(listOf(bg, clear), 0f, band)
        val bottom = Brush.verticalGradient(listOf(clear, bg), size.height - band, size.height)
        onDrawWithContent {
            drawContent()
            drawRect(top, size = Size(size.width, band))
            drawRect(bottom, Offset(0f, size.height - band), Size(size.width, band))
        }
    }) {
        LazyColumn(state = scroll, contentPadding = PaddingValues(vertical = edge), modifier = Modifier.fillMaxSize()) {
            items(groups.keys.toList(), key = { it }) { id ->
                val title = groups.getValue(id).first().text("categoryTitle", id).removeSuffix("水印").ifBlank { "自定义" }
                val selected = id == active
                Box(Modifier.fillMaxWidth().height(44.dp).clickable(role = Role.Tab,
                    onClick = hapticClick(HapticFeedbackType.SegmentTick, feedbackEnabled = !selected) { select(id) })
                    .semantics { this.selected = selected }.testTag("editor.category.$id"), contentAlignment = Alignment.Center) {
                    Text(title, maxLines = 1, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected) ink else ink.copy(alpha = .55f), modifier = Modifier.graphicsLayer {
                            val info = scroll.layoutInfo
                            val item = info.visibleItemsInfo.firstOrNull { it.key == id }
                            val center = if (item == null) Float.MAX_VALUE else item.offset + item.size * .5f
                            val distance = minOf(center - info.viewportStartOffset, info.viewportEndOffset - center)
                            val blur = (1f - distance / (edge.toPx() * 1.8f)).coerceIn(0f, 1f) * 2.6.dp.toPx()
                            renderEffect = if (item != null && blur > .1f) BlurEffect(blur, blur, TileMode.Decal) else null
                        })
                }
            }
        }
    }
}
