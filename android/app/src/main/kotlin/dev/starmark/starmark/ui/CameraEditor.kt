package dev.starmark.starmark.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.map
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val EditorToolsSidePadding = 12.dp

internal class PhotoEditorTools(val tab: MutableState<String>, val nested: MutableState<String?>)

@Composable
internal fun rememberPhotoEditorTools(photoId: String): PhotoEditorTools {
    val tab = rememberSaveable(photoId) { mutableStateOf("styles") }
    val nested = rememberSaveable(photoId) { mutableStateOf<String?>(null) }
    return remember(tab, nested) { PhotoEditorTools(tab, nested) }
}

@Composable
internal fun rememberPhotoToolEntries() = remember { listOf(
    FloatingNavEntry("styles", "水印", MiuixIcons.Image),
    FloatingNavEntry("content", "内容", MiuixIcons.Edit),
    FloatingNavEntry("fonts", "字体", MiuixIcons.Rename, iconText = "Aa"),
    FloatingNavEntry("position", "位置", ReferenceIcons.Adjust),
) }

@Composable
internal fun editorRailFitsFold(feature: FoldingFeature?): Boolean {
    if (feature?.orientation != FoldingFeature.Orientation.HORIZONTAL) return true
    val metrics = editorTrailingNavigationMetrics(rememberPhotoToolEntries())
    val heightPx = LocalWindowInfo.current.containerSize.height
    val lowerPane = with(LocalDensity.current) { (heightPx - feature.bounds.bottom).toDp() } - 8.dp
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    return lowerPane >= metrics.heightWithHistory + metrics.bottomPadding + safe.calculateBottomPadding()
}

@Composable
internal fun CameraEditorTopBar(state: Map<*, *>, command: UiCommand, sideHistory: Boolean = false,
                               feature: FoldingFeature? = null, openPanel: (String) -> Unit) {
    val ready = state.flag("ready")
    val interactive = state.editable()
    val template = state.obj("template").flag("active")
    val batch = state.obj("batch").flag("active")
    val saveLabel = if (batch) "完成" else "保存"
    val saveAction = if (template) "template.save" else if (batch) "batch.done" else "photo.export"
    val density = LocalDensity.current
    val preferredSave = (34f * density.fontScale + 34f).dp
    val minimumSave = maxOf(48.dp, with(density) { 17.sp.toDp() } * 2 + 12.dp)
    val endButton = if (template) maxOf(ButtonDefaults.MinWidth, with(density) { MiuixTheme.textStyles.button.fontSize.toDp() } * 2 + 32.dp) else 48.dp
    val leading = if (sideHistory) 48.dp else 48.dp * 3 + HistoryButtonSpacing * 2
    val trailing = preferredSave + HistoryButtonSpacing + endButton
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp)
        .then(if (feature == null) Modifier else Modifier.onGloballyPositioned { origin = it.positionInWindow() })) {
        val fold = feature?.relativeTo(origin, density)
        // Keep the normal edge actions when each physical side is clear. An off-centre hinge
        // can cut through an action group; use the larger contiguous side in that case.
        val before = (fold?.start?.dp ?: 0.dp) - 8.dp
        val after = (fold?.end?.dp ?: 0.dp) + 8.dp
        val vertical = fold?.vertical == true && after > 0.dp && before < maxWidth
        val interrupted = vertical && (before < (if (rtl) trailing else leading) || maxWidth - after < (if (rtl) leading else trailing))
        val left = if (interrupted && before < maxWidth - after) after.coerceIn(0.dp, maxWidth) else 0.dp
        val right = if (interrupted && before >= maxWidth - after) (maxWidth - before).coerceIn(0.dp, maxWidth) else 0.dp
        val width = maxWidth - left - right
        val fixedActions = 48.dp + endButton + HistoryButtonSpacing * 3
        val roomForSave = width - fixedActions - (if (sideHistory) 0.dp else (48.dp + HistoryButtonSpacing) * 2)
        val wrapHistory = !sideHistory && roomForSave < minimumSave
        val saveWidth = minOf(preferredSave, if (wrapHistory) width - fixedActions else roomForSave).coerceAtLeast(minimumSave)
        val toolbarHeight = if (wrapHistory) 120.dp else 72.dp
        val top = if (fold?.vertical == false && after > 0.dp && before < toolbarHeight) after else 0.dp
        Column(Modifier.fillMaxWidth().absolutePadding(left = left, right = right, top = top)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HistoryButtonSpacing)) {
                CameraCircle("关闭编辑", "navigation.back", MiuixIcons.Close, ready, interactive) { command.send("navigate.back") }
                if (!sideHistory && !wrapHistory) CameraHistoryButtons(state, command)
                if (template) Text(state.obj("template").text("title"), modifier = Modifier.weight(1f), maxLines = 1) else Spacer(Modifier.weight(1f))
                CameraGlassButton(saveLabel, saveAction, ready && interactive, width = saveWidth, onClick = { command.send(saveAction) }) {
                    Text(saveLabel, fontSize = 17.sp, maxLines = 1, color = if (ready) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface)
                }
                if (template) TextButton("重置", hapticClick { command.send("template.reset") }, modifier = Modifier.testTag("template.reset"))
                if (!template) MorphingMoreMenu(
                    entries = remember(batch) { photoMenuEntries(batch) },
                    onAction = { if (it == "history" || it == "saveStyle") openPanel(it) else command.send(it) },
                    enabled = ready, interactionEnabled = interactive, modifier = Modifier.size(48.dp),
                )
            }
            if (wrapHistory) Row(horizontalArrangement = Arrangement.spacedBy(HistoryButtonSpacing)) {
                CameraHistoryButtons(state, command)
            }
        }
    }
}

internal fun photoMenuEntries(batch: Boolean) = buildList {
    add(if (batch) MorphMenuEntry("batch.exportCurrent", "导出当前照片", MiuixIcons.Image)
        else MorphMenuEntry("photo.import", "更换照片", MiuixIcons.Image))
    add(MorphMenuEntry("history", "编辑历史", MiuixIcons.Undo))
    add(MorphMenuEntry("saveStyle", "保存水印样式", MiuixIcons.Edit))
    if (!batch) add(MorphMenuEntry("draft.save", "保存草稿", MiuixIcons.Ok))
}

@Composable
internal fun CameraHistoryButtons(state: Map<*, *>, command: UiCommand, glyphSize: Dp = 28.dp) {
    val ready = state.flag("ready")
    val interactive = state.editable()
    val history = state.obj("history")
    CameraCircle("撤销", "history.undo", MiuixIcons.Undo, ready && history.flag("canUndo"), interactive, glyphSize) { command.send("history.undo") }
    CameraCircle("重做", "history.redo", MiuixIcons.Redo, ready && history.flag("canRedo"), interactive, glyphSize) { command.send("history.redo") }
}

@Composable
internal fun CameraCircle(label: String, tag: String, icon: ImageVector, available: Boolean, interactive: Boolean, glyphSize: Dp = 28.dp, onClick: () -> Unit) {
    CameraGlassButton(label, tag, available && interactive, onClick = onClick) {
        Icon(icon, null, Modifier.size(glyphSize), tint = if (available) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface)
    }
}

/** Layout owns the 48dp bounds; press feedback transforms only the rendered shell. */
@Composable
internal fun CameraGlassButton(label: String, tag: String, enabled: Boolean, width: androidx.compose.ui.unit.Dp = 48.dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(width, 48.dp).testTag(tag)
            .semantics { contentDescription = label }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button,
                onClick = hapticClick(feedbackEnabled = tag != "navigation.back", onClick = onClick)),
        contentAlignment = Alignment.Center,
    ) {
        ChromeSurface(Modifier.fillMaxSize().padding(2.dp).stockPress(interaction, StockPressKind.Banner, enabled), cornerRadius = 22.dp,
            cornerKind = if (width == 48.dp) CornerKind.Circle else CornerKind.Capsule) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
        }
    }
}

@Composable
internal fun CameraEditorBody(
    state: Map<*, *>, command: UiCommand, wide: Boolean, openPanel: (String) -> Unit,
    onViewport: (androidx.compose.ui.geometry.Rect, androidx.compose.ui.geometry.Rect, Float) -> Unit, trailingTabs: Boolean = false,
    bodyInsets: PaddingValues = PaddingValues(0.dp), trayReveal: State<Float>? = null,
    feature: FoldingFeature? = separatingEditorFold(),
    tools: PhotoEditorTools = rememberPhotoEditorTools(state.obj("photo").text("id")),
) {
    var tool by tools.tab
    var nested by tools.nested
    var styleRequest by rememberSaveable(state.obj("photo").text("id")) { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun choose(next: String) {
        focus.clearFocus(); keyboard?.hide()
        if (tool != next || nested != null) command.send("interaction.cancel")
        tool = next
        nested = null
    }
    fun openInside(next: String) {
        command.send("interaction.cancel")
        if (next == "styleOptions") {
            styleRequest += 1
            choose("content")
        } else if (next in listOf("styles", "content", "fonts", "position")) choose(next) else if (next == "border") choose("position") else nested = next
    }
    BackHandler(enabled = nested != null) { command.send("interaction.cancel"); nested = null }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val rtl = layoutDirection == LayoutDirection.Rtl
    val windowSize = LocalWindowInfo.current.containerSize
    val landscape = wide && windowSize.width > windowSize.height
    var origin by remember { mutableStateOf(Offset.Zero) }
    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
    val batch = state.obj("batch")
    val showTray = batch.flag("active") && !keyboardOpen
    val expanded = batch.flag("expanded")
    LaunchedEffect(keyboardOpen) {
        if (keyboardOpen && batch.flag("active") && expanded) command.send("batch.tray", "expanded" to false)
    }
    BackHandler(enabled = showTray && expanded && nested == null && !batch.flag("confirmContent")) { command.send("batch.tray", "expanded" to false) }
    val bottomMargin = navigationBottomMargin(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().value)
    val entries = rememberPhotoToolEntries()
    val trailingMetrics = if (trailingTabs) editorTrailingNavigationMetrics(entries) else null
    Box(Modifier.fillMaxSize().testTag(if (state.obj("template").flag("active")) "screen.templateEditor" else "screen.editor")
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).imePadding()) {
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }, contentAlignment = AbsoluteAlignment.TopLeft) {
            val fold = feature?.relativeTo(origin, density)
            val controlsMinimum = if (keyboardOpen || trailingTabs) 204f else floatingNavigationMetrics(maxWidth.value, density.fontScale, 4).height + 188f + bottomMargin
            // Window and hinge geometry own the split. A tool's clearance belongs inside its pane.
            val railSpace = trailingMetrics?.clearance ?: 0.dp
            val panes = editorPaneLayout(maxWidth.value, maxHeight.value, landscape, controlsMinimum, fold, rtl,
                trailingSpace = railSpace.value)
            val controls = panes.controls
            val photoAlignment = if (trailingTabs && panes.sideBySide && panes.preview.right <= controls.left) {
                // Route transitions also translate the Flutter surface. Use the resting insets for its layout.
                val windowMid = with(density) { windowSize.height.toDp() / 2f }
                val safeLeft = WindowInsets.safeDrawing.asPaddingValues().calculateLeftPadding(layoutDirection)
                FoldPhotoAlignment(
                    centerYInPane = if (keyboardOpen) null else (windowMid - bodyInsets.calculateTopPadding()).value - panes.preview.top,
                    outerInset = maxOf(safeLeft, bodyInsets.calculateLeftPadding(layoutDirection)).value + panes.preview.left,
                    gapToCard = controls.left - panes.preview.right + EditorToolsSidePadding.value,
                )
            } else null
            val photoPane = panes.preview
            val tray = if (showTray) batchTrayLayout((photoPane.width.dp - EditorSettingsDefaults.PageInset * 2).coerceAtLeast(0.dp), batch, trayReveal) else null
            EditorPhotoWithTray(state, command, Modifier.inEditorPane(photoPane), photoAlignment, onViewport, tray,
                compact = !currentEditorWindow().wide, trayInset = EditorSettingsDefaults.PageInset)
            val railOverPhoto = trailingTabs && !keyboardOpen && panes.preview.right > controls.right
            val anchor = if (railOverPhoto) panes.preview else controls
            val toolsArea = if (railOverPhoto) EditorPaneBounds(0f, 0f, maxWidth.value, maxHeight.value) else controls
            ChromePane(Modifier.inEditorPane(toolsArea), systemGlassLocalFallbackPossible = false,
                externalPhotoOrigin = origin.takeIf { railOverPhoto }, body = {
                InlineEditorTools(state, command, tool, nested, ::openInside, { nested = null },
                        Modifier.align(AbsoluteAlignment.TopLeft).inEditorPane(controls, toolsArea).testTag("editor.controlsPane"),
                        styleRequest, panes.sideBySide, trailingTabs,
                        controlsRightInset = if (keyboardOpen || railOverPhoto) 0.dp else railSpace,
                        protectedHeight = (trailingMetrics?.heightWithHistory ?: 0.dp) + bottomMargin.dp + 8.dp)
            }, chrome = {
                if (!keyboardOpen) {
                    if (trailingMetrics != null) {
                        // Constrain the capsule to the real pane at the bottom right, including a tabletop hinge.
                        val chromeArea = anchor.copy(top = toolsArea.top)
                        Box(Modifier.align(AbsoluteAlignment.TopLeft).inEditorPane(chromeArea, toolsArea)) {
                            TrailingFloatingNavigation(entries, tool, ::choose, enabled = state.flag("ready"), interactionEnabled = state.editable(), metrics = trailingMetrics,
                                tagPrefix = "editor.tool", modifier = Modifier.align(AbsoluteAlignment.BottomRight),
                                aboveRail = {
                                    Column(verticalArrangement = Arrangement.spacedBy(HistoryButtonSpacing)) {
                                        CameraHistoryButtons(state, command)
                                    }
                                })
                        }
                    } else FloatingNavigation(entries, tool, ::choose, enabled = state.flag("ready"), interactionEnabled = state.editable(),
                        tagPrefix = "editor.tool", modifier = Modifier.align(Alignment.BottomCenter))
                }
            })
        }
    }
}

@Composable
internal fun EditorPhotoWithTray(
    state: Map<*, *>, command: UiCommand, modifier: Modifier, alignment: FoldPhotoAlignment?,
    onViewport: (androidx.compose.ui.geometry.Rect, androidx.compose.ui.geometry.Rect, Float) -> Unit,
    tray: BatchTrayLayout?, compact: Boolean, trayInset: androidx.compose.ui.unit.Dp = 0.dp,
    trayDensity: androidx.compose.ui.unit.Density = LocalDensity.current,
) {
    if (tray == null) {
        EditorCanvas(state, modifier.testTag("editor.previewPane"), alignment, onViewport)
        return
    }
    BoxWithConstraints(modifier) {
        val scale = trayDensity.density / LocalDensity.current.density
        val room = (maxHeight - 1.dp).coerceAtLeast(0.dp)
        val header = minOf(room, tray.headerHeight * scale)
        val visible = minOf(room, tray.height * scale)
        EditorCanvas(state, Modifier.fillMaxWidth().height(maxHeight - header).testTag("editor.previewPane"),
            alignment, onViewport, trayInset = visible - header)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(visible).padding(horizontal = trayInset)) {
            CompositionLocalProvider(LocalDensity provides trayDensity) {
                BatchPhotoTray(state, command, Modifier.fillMaxSize(), tray, compact)
            }
        }
    }
}

@Composable
internal fun separatingEditorFold(): FoldingFeature? {
    val activity = LocalActivity.current ?: return null
    val changes = remember(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map { info ->
            info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull {
                it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL
            }
        }
    }
    return changes.collectAsStateWithLifecycle(initialValue = null).value
}

private fun FoldingFeature.relativeTo(origin: Offset, density: androidx.compose.ui.unit.Density): EditorFold = with(density) {
    if (orientation == FoldingFeature.Orientation.VERTICAL)
        EditorFold(true, (bounds.left - origin.x).toDp().value, (bounds.right - origin.x).toDp().value)
    else EditorFold(false, (bounds.top - origin.y).toDp().value, (bounds.bottom - origin.y).toDp().value)
}

internal fun Modifier.inEditorPane(bounds: EditorPaneBounds, parent: EditorPaneBounds? = null) =
    absoluteOffset((bounds.left - (parent?.left ?: 0f)).dp, (bounds.top - (parent?.top ?: 0f)).dp)
        .size(bounds.width.dp, bounds.height.dp)

@Composable
private fun InlineEditorTools(
    state: Map<*, *>, command: UiCommand, tool: String, nested: String?,
    open: (String) -> Unit, closeNested: () -> Unit, modifier: Modifier, styleRequest: Int, sideBySide: Boolean, trailingTabs: Boolean,
    controlsRightInset: Dp, protectedHeight: Dp,
) {
    val density = LocalDensity.current
    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
    val bottomMargin = navigationBottomMargin(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().value).dp
    // Editing the document does not start a new browsing session. In particular,
    // preserve strip offsets on template/font changes and leaving for the library.
    val draftScope = state.obj("photo").text("id")
    val savedTools = key(draftScope) { rememberSaveableStateHolder() }
    BoxWithConstraints(modifier.background(MiuixTheme.colorScheme.background)) {
            val navHeight = floatingNavigationMetrics(maxWidth.value, density.fontScale, 4).height
            val paneWidth = maxWidth
            val paneHeight = maxHeight
            val selectedTool = nested ?: tool
            val bottomClearance = when {
                keyboardOpen -> 16.dp
                trailingTabs -> maxOf(16.dp, WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding())
                else -> navHeight.dp + bottomMargin
            }
            ReferencePageTransition(selectedTool, toolOrder = listOf("styles", "content", "fonts", "position", "color", "models"),
                trailingTabs = trailingTabs) { shown, _ ->
            val draftKey = "$shown:$draftScope"
            savedTools.SaveableStateProvider(draftKey) {
                val current = shown == selectedTool
                val guarded: UiCommand = { action, args -> if (current || (action == "adjust" && args["phase"] == "cancel")) command(action, args) }
                val panelScroll = rememberScrollState()
                var formReachesRail by rememberSaveable(paneWidth, paneHeight, controlsRightInset, protectedHeight) { mutableStateOf(false) }
                var handledStyleRequest by rememberSaveable { mutableIntStateOf(0) }
                LaunchedEffect(current, styleRequest) {
                    if (current && shown == "content" && styleRequest > handledStyleRequest) {
                        panelScroll.scrollTo(0)
                        handledStyleRequest = styleRequest
                    }
                }
                if (shown == "styles" && trailingTabs) {
                    FoldWatermarkLibrary(state, guarded, controlsRightInset) { if (current) open("styleOptions") }
                } else if (shown == "styles") {
                    val top = if (sideBySide) 16.dp else 4.dp
                    val bottom = if (keyboardOpen || trailingTabs) bottomClearance else bottomClearance + 8.dp
                    val shortPane = paneHeight - top - bottom < (if (sideBySide) 200.dp else 160.dp)
                    CameraWatermarkControls(state, guarded, { if (current) open(it) }, sideBySide,
                        Modifier.fillMaxSize()
                            .padding(start = EditorToolsSidePadding, end = EditorToolsSidePadding, top = top, bottom = bottom)
                            .then(if (shortPane) Modifier.verticalScroll(panelScroll) else Modifier)
                            .testTag("editor.inline.styles"))
                } else Box(
                    Modifier.fillMaxWidth()
                        .absolutePadding(right = if (formReachesRail) controlsRightInset else 0.dp)
                        .verticalScroll(panelScroll)
                        .heightIn(min = paneHeight)
                        .padding(start = EditorToolsSidePadding, end = EditorToolsSidePadding, top = 12.dp,
                            bottom = if (keyboardOpen || trailingTabs) bottomClearance else bottomClearance + 12.dp)
                        .testTag("editor.inline.$shown"),
                    contentAlignment = if (sideBySide) Alignment.TopStart else Alignment.BottomStart,
                ) {
                    // Measure the form's height, independent of scroll. Only its right edge gives way.
                    val formBounds = if (controlsRightInset > 0.dp) {
                        Modifier.onSizeChanged { size ->
                            val end = with(density) {
                                val lastAtEnd = paneHeight - bottomClearance
                                if (sideBySide) minOf(lastAtEnd, 12.dp + size.height.toDp()) else lastAtEnd
                            }
                            formReachesRail = end > paneHeight - protectedHeight
                        }
                    } else Modifier
                    Column(Modifier.fillMaxWidth().then(formBounds), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (shown in listOf("color", "models")) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ guarded.send("interaction.cancel"); if (current) closeNested() }, modifier = Modifier.size(48.dp).testTag("editor.inline.back")) { Icon(ReferenceIcons.Back, "返回") }
                            Text(if (shown == "color") "文字颜色" else "预设型号", style = MiuixTheme.textStyles.headline1)
                        }
                        EditorPanel(shown, state, guarded, { if (current) open(it) }, { if (current && nested != null) closeNested() })
                    }
                }
                }
            }
    }
}

@Composable
private fun CameraWatermarkControls(state: Map<*, *>, command: UiCommand, openPanel: (String) -> Unit, sideBySide: Boolean, modifier: Modifier) {
    val rows = styleRows(state)
    val selected = rows.firstOrNull { it.flag("selected") }
    val groups = rows.distinctBy { it.text("category") }
    var category by rememberSaveable { mutableStateOf(selected?.text("category") ?: "leica") }
    var observedSelectionCategory by rememberSaveable { mutableStateOf(selected?.text("category")) }
    val categoryStates = rememberSaveableStateHolder()
    LaunchedEffect(selected?.text("category")) {
        val next = selected?.text("category")
        if (next != observedSelectionCategory) {
            if (next != null) category = next
            observedSelectionCategory = next
        }
    }
    val enabled = state.obj("document").flag("enabled", true)
    val editable = state.editable()
    val accent = CameraWatermarkYellow
    val native = state.obj("native")
    val canChooseStyle = nativeStyleChoicesAvailable(native, LocalStarMarkEffects.current.systemGlassSupported || PortablePhotoGlass.supported())
    Column(modifier, verticalArrangement = if (sideBySide) Arrangement.spacedBy(8.dp) else Arrangement.SpaceEvenly) {
        CameraWatermarkQuickControls(state, command, accent) {
            CameraStyleOption(available = enabled && canChooseStyle, editable = editable) { openPanel("styleOptions") }
        }
        if (!state.obj("template").flag("active")) Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).height(44.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                groups.forEach { group ->
                    val id = group.text("category")
                    val labelColor = if (category == id) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary
                    TextButton(group.text("categoryTitle", "自定义").removeSuffix("水印"), hapticClick(HapticFeedbackType.SegmentTick, feedbackEnabled = category != id) { category = id }, enabled = editable,
                        modifier = Modifier.height(44.dp).testTag("editor.category.$id").semantics { this.selected = category == id },
                        minHeight = 44.dp, insideMargin = PaddingValues(horizontal = 12.dp),
                        textStyle = MiuixTheme.textStyles.main.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent,
                            textColor = labelColor, disabledTextColor = labelColor))
                }
            }
            IconButton(
                { command.send("navigate", "page" to "watermarks", "returnPage" to "editor") },
                enabled = editable, modifier = Modifier.size(48.dp).testTag("editor.openWatermarks").semantics { if (!editable) disabled() },
            ) {
                Icon(MiuixIcons.ChevronForward, "全部水印", Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurface)
            }
        }
        if (!state.obj("template").flag("active")) Row(Modifier.fillMaxWidth().height(88.dp).padding(start = 12.dp).testTag("editor.watermarkStrip"), verticalAlignment = Alignment.CenterVertically) {
            CameraNoWatermark(!enabled, editable, accent) { command.patch("关闭照片水印", "enabled" to false) }
            Spacer(Modifier.width(EditorStripDefaults.Gap))
            categoryStates.SaveableStateProvider(category) {
            // Center the 64dp tiles in the available row. At the intended 88dp row
            // this supplies 12dp above and below; on shorter rows only that air shrinks.
            LazyRow(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(EditorStripDefaults.Gap), verticalAlignment = Alignment.CenterVertically) {
                items(rows.filter { it.text("category") == category }, key = { it.text("id") }) { row ->
                    WatermarkTile(row, enabled && row.flag("selected"), 80.dp, accent, editable, height = EditorStripDefaults.FrameHeight) {
                        if (row.flag("selected") && enabled) {
                            openPanel("styleOptions")
                        } else command.send("style.apply", "id" to row.text("id"))
                    }
                }
            }
            }
        }

    }
}

@Composable
internal fun CameraWatermarkQuickControls(state: Map<*, *>, command: UiCommand, accent: Color, locationLabel: String? = null, sidePadding: Dp = 16.dp, faceHeight: Dp? = null, trailing: @Composable () -> Unit) {
    val enabled = state.obj("document").flag("enabled", true)
    val native = state.obj("native")
    val editable = state.editable()
    val options = state.obj("document").obj("xiaomiOptions")
    val metadata = state.obj("photo").obj("metadata")
    val supportsTime = native.flag("enabled") && native.flag("supportsTime")
    val supportsLocation = native.flag("enabled") && native.flag("supportsLocation")
    val hasTime = metadata.text("dateTime").isNotBlank()
    val locationTitle = locationLabel ?: if (native.text("locationKind") == "address") "地址" else "经纬度"
    val hasLocation = native.flag("locationAvailable")
    val locationReason = native.text("locationUnavailableReason").takeIf { it.isNotEmpty() }
    // Keep the controls mounted across templates. Unsupported options are disabled,
    // so rapid selection cannot remove/recreate the entire row or its glass source.
    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.fillMaxWidth().padding(horizontal = sidePadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CameraWatermarkOption(
                "时间", "editor.showTime", MiuixIcons.Timer, options.flag("showTime", true),
                available = enabled && supportsTime && hasTime, editable = editable,
                unavailableReason = if (!supportsTime) "此水印不支持时间开关" else if (!hasTime) "照片没有拍摄时间" else null, accent = accent, faceHeight = faceHeight,
            ) { command.patch("拍摄时间显示", "xiaomiOptions" to mapOf("showTime" to !options.flag("showTime", true))) }
            CameraWatermarkOption(
                locationTitle, "editor.showLocation", MiuixIcons.Location, options.flag("showLocation", true),
                available = enabled && supportsLocation && hasLocation, editable = editable,
                unavailableReason = if (!supportsLocation) "此水印不支持位置开关" else locationReason, accent = accent, faceHeight = faceHeight,
            ) { command.patch("${locationTitle}显示", "xiaomiOptions" to mapOf("showLocation" to !options.flag("showLocation", true))) }
            }
            trailing()
        }
    }
}

@Composable
private fun CameraStyleOption(available: Boolean, editable: Boolean, onClick: () -> Unit) {
    val enabled = available && editable
    val interaction = remember { MutableInteractionSource() }
    val foreground = if (available) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface
    val backdrop = rememberLayerBackdrop()
    Box(
        Modifier.heightIn(min = 48.dp)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = hapticClick(onClick = onClick))
            .testTag("editor.chooseStyle"),
        contentAlignment = Alignment.Center,
    ) {
        // This control is inside ChromePane's recorded body. Reading that parent
        // would record the glass back into its own source (RenderThread stack
        // overflow on portable glass). Its actual background is this flat pane;
        // record a sibling underlay, with the glass outside that recording.
        Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(MiuixTheme.colorScheme.background))
        CompositionLocalProvider(LocalChromeBackdrop provides backdrop) {
            ChromeSurface(Modifier.stockPress(interaction, StockPressKind.Banner, enabled), cornerRadius = 40.dp, cornerKind = CornerKind.Capsule, shadowElevation = 2.dp) {
                Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(ReferenceIcons.Adjust, null, Modifier.size(16.dp), tint = foreground)
                    Text("款式", fontSize = 13.sp, color = foreground, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun CameraNoWatermark(selected: Boolean, enabled: Boolean, accent: Color, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = MiuixCornerShape(20.dp)
    Box(
        // Keep the original 64dp square face; the selection ring has the same 4dp
        // inset on all four sides, independently of rectangular watermark previews.
        Modifier.sizeIn(maxWidth = 72.dp, maxHeight = 72.dp).aspectRatio(1f)
            .stockPress(interactionSource, StockPressKind.Watermark, enabled)
            .clip(shape)
            .then(if (selected) Modifier.border(BorderStroke(2.dp, accent), shape) else Modifier)
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, role = Role.Button,
                onClick = hapticClick(HapticFeedbackType.SegmentTick, feedbackEnabled = !selected, onClick = onClick))
            .semantics { this.selected = selected; contentDescription = "无水印" }
            .testTag("editor.noWatermark"),
        contentAlignment = Alignment.Center,
    ) {
        val face = Modifier.fillMaxSize().padding(4.dp)
        Icon(ReferenceIcons.NoneBackground, null, face, tint = MiuixTheme.colorScheme.surfaceContainerHigh)
        Icon(ReferenceIcons.None, null, face, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
internal fun CameraWatermarkOption(title: String, tag: String, icon: ImageVector, selected: Boolean, available: Boolean, editable: Boolean, unavailableReason: String?, accent: Color, stateLabel: String? = null, faceHeight: Dp? = null, onClick: () -> Unit) {
    // A short resource/command lock blocks input without flashing the established
    // selected appearance. Only actual capability or metadata changes affect color.
    val enabled = available && editable
    val interactionSource = remember { MutableInteractionSource() }
    val foreground = when {
        !available -> MiuixTheme.colorScheme.disabledOnSurface
        selected -> Color(0xE6000000)
        else -> MiuixTheme.colorScheme.onSurface
    }
    Box(
        Modifier.heightIn(min = 48.dp)
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, role = Role.Switch,
                onClick = hapticClick(toggleHaptic(selected), onClick = onClick))
            .semantics {
                toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
                stateDescription = unavailableReason ?: stateLabel ?: if (selected) "已显示" else "已隐藏"
            }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.background(if (selected && available) accent else MiuixTheme.colorScheme.surfaceContainer, MiuixCornerShape.Capsule)
                .then(if (faceHeight != null) Modifier.widthIn(min = if (tag == "editor.showLocation") 81.dp else 68.dp)
                    .heightIn(min = faceHeight).padding(start = 9.dp, end = 13.dp)
                    else Modifier.padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = foreground)
            Text(title, fontSize = 13.sp, color = foreground, maxLines = 1)
        }
    }
}
