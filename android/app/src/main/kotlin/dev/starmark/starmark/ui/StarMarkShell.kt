package dev.starmark.starmark.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Native Miuix chrome; the measured, input-free editor slot is rendered by Flutter. */
@Composable
fun StarMarkShell(
    state: Map<*, *>,
    onCommand: (String, Map<String, Any?>) -> Unit,
    onCanvasBounds: (Rect?) -> Unit,
    onBackgroundColor: (Int) -> Unit,
    onCanvasMotion: (Float, Float, Boolean) -> Unit = { _, _, _ -> },
    onMaterialCapability: (Boolean) -> Unit = {},
    onCanvasClip: (Rect?, Float) -> Unit = { _, _ -> },
) {
    StarMarkTheme(state.obj("settings")) {
        val supported = LocalStarMarkEffects.current.systemGlassSupported
        val materialCallback by rememberUpdatedState(onMaterialCapability)
        LaunchedEffect(supported) { materialCallback(supported) }
        val background = MiuixTheme.colorScheme.background
        SideEffect { onBackgroundColor(background.toArgb()) }
        var lastReadyState by remember { mutableStateOf(state) }
        SideEffect { if (state.flag("ready")) lastReadyState = state }
        val shellState = if (!state.flag("ready") && state.flag("busy"))
            lastReadyState + mapOf("busy" to true) else state
        ChromeOverlayHost {
            StartupReveal(state.flag("ready"), state.text("message")) {
                ShellContent(shellState, onCommand, onCanvasBounds, onCanvasMotion, onCanvasClip)
                PhotoExportProgress(state.obj("export"))
                ImportWorkflow(state.obj("import"), onCommand)
                val operation = state.obj("operation")
                DeferredOperationProgress(operation.flag("active") && !state.obj("export").flag("active") && !state.obj("import").flag("active"),
                    operation.integer("id"), "operation.progress", operation.text("title", "正在处理…"), "请稍候…")
                DeferredOperationProgress(state.flag("busy") && !state.flag("ready") && !operation.flag("active") && !state.obj("import").flag("active"),
                    "photo", "photo.loading", "正在准备照片…", "正在读取照片和水印资源")
            }
        }
    }
}

private val destinations = listOf("home" to "主页", "settings" to "设置")

@Composable
private fun ShellContent(state: Map<*, *>, command: UiCommand, onCanvasBounds: (Rect?) -> Unit, onCanvasMotion: (Float, Float, Boolean) -> Unit, onCanvasClip: (Rect?, Float) -> Unit) {
    val page = state.text("page", "home")
    val busy = state.flag("busy") || state.obj("batch").flag("running")
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingDraftIds by remember { mutableStateOf<List<String>?>(null) }
    var deletingWatermarkIds by remember { mutableStateOf<List<String>?>(null) }
    // A draft restore temporarily replaces the home body with a loading view.
    // Keep pager selection above that body and all route transitions.
    val homeBannerPage = rememberSaveable { mutableIntStateOf(1) }
    fun changePanel(next: String?) {
        if (next != panel) command.send("interaction.cancel")
        panel = next
    }
    val context = LocalContext.current
    val activeToast = remember { arrayOfNulls<Toast>(1) }
    val routeStates = rememberSaveableStateHolder()
    val colors = MiuixTheme.colorScheme
    LaunchedEffect(page) { changePanel(null) }
    LaunchedEffect(state.longNumber("messageId")) {
        val message = state.text("message")
        if (message.isNotBlank()) {
            // Same system Toast entry point as MiMediaEditor h10.a / mg0.b.
            // HyperOS owns the toast shape, color, placement and lifetime.
            android.util.Log.i("StarMarkMessage", message)
            val concise = when {
                message.startsWith("已保存 ") && message.endsWith("到相册") -> "已保存到相册"
                message == "样式已保存到“自定义”" -> "水印已保存"
                else -> message.lineSequence().first().take(80)
            }
            activeToast[0]?.cancel()
            activeToast[0] = Toast.makeText(context, concise, Toast.LENGTH_SHORT).also { it.show() }
            command.send("message.clear")
        }
    }
    DisposableEffect(Unit) {
        onDispose { activeToast[0]?.cancel(); onCanvasBounds(null) }
    }
    BackHandler(enabled = panel != null || page != "home") {
        if (panel != null) changePanel(null) else command.send("navigate.back")
    }
    // One root dialog host survives route transitions; each route moves its own bars.
    Scaffold(Modifier.fillMaxSize(), containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val compact = maxHeight < 500.dp
        val effects = LocalStarMarkEffects.current
        val reduceMotion = effects.reduceMotion
        val density = LocalDensity.current
        val startup = LocalStartupProgress.current
        val layoutDirection = LocalLayoutDirection.current
        val rootNavigationVisible = page in destinations.map { it.first }
        val shaderSupported = isRuntimeShaderSupported()
        val rootBackdropNeeded = rootNavigationVisible && backdropRecordingNeeded(effects.glass, effects.systemGlass, shaderSupported, false)
        // The top gradient and the cross-window menu can fall back locally even
        // while other chrome successfully uses the vendor glass implementation.
        val routeBackdropNeeded = backdropRecordingNeeded(effects.glass, effects.systemGlass, shaderSupported, true)
        val bottomMargin = navigationBottomMargin(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().value).dp
        val navigationHeight = floatingNavigationMetrics(maxWidth.value, density.fontScale, destinations.size).height.dp + 8.dp + bottomMargin
        // The recorder is owned by the shell, not either animated route.  Recording
        // only the lower screen keeps the persistent bar independent of page motion.
        val rootBackdrop = rememberLayerBackdrop(onDraw = {
            val contentScope = this
            clipRect(top = (size.height - (navigationHeight + 52.dp).toPx()).coerceAtLeast(0f)) { contentScope.drawContent() }
        })
        Box(Modifier.fillMaxSize().then(if (rootBackdropNeeded) Modifier.layerBackdrop(rootBackdrop) else Modifier)) {
        ReferencePageTransition(page, onCanvasMotion) { shown, offset ->
            routeStates.SaveableStateProvider(shown) {
            val current = shown == page
            val guarded: UiCommand = { action, args -> if (current || (action == "adjust" && args["phase"] == "cancel")) command(action, args) }
            val collapseEnabled = shown in destinations.map { it.first } + listOf("styles", "watermarks") && !compact
            val trackHeaderScroll = collapseEnabled || shown in listOf("styles", "watermarks", "settings")
            val barState = rememberTopAppBarState()
            val canCollapse = remember(trackHeaderScroll) { { trackHeaderScroll } }
            val scrollBehavior = if (shown == "home") {
                if (reduceMotion) rememberHomeHeaderScrollBehavior(barState, canCollapse, snap()) else rememberHomeHeaderScrollBehavior(barState, canCollapse)
            } else if (reduceMotion) MiuixScrollBehavior(barState, canCollapse, snap()) else MiuixScrollBehavior(barState, canCollapse)
            val title = when (shown) {
                "styles", "watermarks" -> "水印"
                "batch" -> "批量编辑"
                "settings" -> "设置"
                "experiments" -> "实验性功能"
                "components" -> "Miuix 组件"
                "licenses" -> "开放源代码许可"
                else -> "StarMark"
            }
            val templatePreviewBounds = remember { mutableStateOf<Rect?>(null) }
            // The photo editor's fixed preview starts below its chrome. The template editor
            // scrolls both its preview and its Compose preference rows under the header.
            val chromeBackdrop = if (shown == "templateEditor") {
                rememberTemplateHeaderBackdrop(routeBackdropNeeded && current, templatePreviewBounds, colors.background)
            } else rememberLayerBackdrop(onDraw = {
                if (shown == "editor" && effects.systemGlass) drawRect(colors.background)
                else drawContent()
            })
            CompositionLocalProvider(LocalChromeBackdrop provides chromeBackdrop) {
                Scaffold(
                    modifier = Modifier.fillMaxSize().scrollBoundaryHaptics { barState.heightOffset }.then(if (trackHeaderScroll) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier),
                    containerColor = Color.Transparent,
                    topBar = {
                        Box(Modifier.graphicsLayer { translationY = -12.dp.toPx() * (1f - startup) }) {
                        ChromeTopBar(settingsPane = shown == "templateEditor") {
                            when (shown) {
                                "home" -> BrandTopBar(if (collapseEnabled) barState else null)
                                "editor" -> CameraEditorTopBar(state, guarded) { if (current) changePanel(it) }
                                "templateEditor" -> TemplateEditorTopBar(state, guarded)
                                "styles", "watermarks", "settings" -> ReferencePageHeader(title, scrollBehavior, compact, showBack = shown != "settings") { guarded.send("navigate.back") }
                                else -> {
                                    val navigation: @Composable () -> Unit = {
                                        if (shown !in destinations.map { it.first }) ActionIcon("返回", "navigation.back", ReferenceIcons.Back) { guarded.send("navigate.back") }
                                    }
                                    val actions: @Composable RowScope.() -> Unit = {}
                                    if (shown == "components" || shown == "licenses" || compact) {
                                        SmallTopAppBar(title, navigationIcon = navigation, actions = actions, color = Color.Transparent)
                                    } else {
                                        TopAppBar(title, modifier = Modifier.testTag("app.collapsingHeader"), navigationIcon = navigation, actions = actions, color = Color.Transparent, titlePadding = 28.dp, scrollBehavior = scrollBehavior)
                                    }
                                }
                            }
                        }
                        }
                    },
                    bottomBar = {
                        Column {
                            if (shown == "watermarks" || shown == "styles") {
                                WatermarkSelectionBar(state, guarded, if (shown == "styles") "styles.import" else "watermarks.import") { deletingWatermarkIds = it }
                            }
                        }
                    },
                ) { routeInsets ->
                    val topInset = routeInsets.calculateTopPadding()
                    // The ordinary editor paints to the window edge. Its floating tools
                    // own navigation safety; only header/side insets constrain its viewport.
                    val editorInsets = if (shown == "editor") PaddingValues(
                        start = routeInsets.calculateStartPadding(layoutDirection),
                        top = topInset,
                        end = routeInsets.calculateEndPadding(layoutDirection),
                    ) else routeInsets
                    val pageInsets = if (shown in destinations.map { it.first }) PaddingValues(
                        start = routeInsets.calculateStartPadding(layoutDirection),
                        top = routeInsets.calculateTopPadding(),
                        end = routeInsets.calculateEndPadding(layoutDirection),
                        bottom = maxOf(routeInsets.calculateBottomPadding(), navigationHeight),
                    ) else routeInsets
                    val pageBackground = when (shown) {
                        "templateEditor" -> Modifier // transparent window onto the sibling Flutter preview
                        "editor" -> Modifier.drawBehind {
                        // The photo host remains transparent below the toolbar, but the
                        // backdrop recorder needs the real background behind top controls.
                            drawRect(colors.background, size = androidx.compose.ui.geometry.Size(size.width, topInset.toPx().coerceAtMost(size.height)))
                        }
                        else -> Modifier.background(colors.background)
                    }
                    Row(Modifier.fillMaxSize().then(if (routeBackdropNeeded) Modifier.layerBackdrop(chromeBackdrop) else Modifier).then(pageBackground)) {
                        Box(Modifier.weight(1f).fillMaxHeight().then(if (shown == "editor") Modifier.padding(editorInsets).consumeWindowInsets(editorInsets) else Modifier)) {
                            when (shown) {
                                "templateEditor" -> key(state.obj("photo").text("id")) {
                                    TemplateEditorBody(state, guarded, { if (current) changePanel(it) }, routeInsets, { rect ->
                                        val local = rect?.translate(Offset(-offset(), 0f))
                                        templatePreviewBounds.value = local
                                        onCanvasBounds(local)
                                    }, { rect, radius -> onCanvasClip(rect?.translate(Offset(-offset(), 0f)), radius) })
                                }
                                "editor" -> key(state.obj("photo").text("id")) {
                                    CameraEditorBody(state, guarded, wide, { if (current) changePanel(it) }, { rect -> onCanvasBounds(rect?.translate(Offset(-offset(), 0f))) })
                                }
                                "styles" -> Box(Modifier.fillMaxSize().testTag("screen.styles")) { WatermarkPickerPage(state, guarded, routeInsets) }
                                "watermarks" -> WatermarkPickerPage(state, guarded, routeInsets)
                                "batch" -> BatchPage(state, guarded, routeInsets)
                                "settings" -> SettingsPage(state, guarded, pageInsets)
                                "experiments" -> ExperimentalSettingsPage(state, guarded, routeInsets)
                                "components" -> ComponentCatalog(routeInsets)
                                "licenses" -> LicensesPage(routeInsets, reduceMotion)
                                else -> HomePage(state, guarded, pageInsets, homeBannerPage, selectionInChrome = true)
                            }
                        }
                    }
                }
            }
        }
        }
        }
        if (rootNavigationVisible) {
            CompositionLocalProvider(LocalChromeBackdrop provides rootBackdrop) {
                FloatingNavigation(
                    destinations.map { (id, label) -> FloatingNavEntry(id, label, destinationIcon(id)) },
                    page, { command.send("navigate", "page" to it) }, enabled = !busy,
                    modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer {
                        translationY = 24.dp.toPx() * (1f - startup)
                    },
                    actionsVisible = page == "home" && state.obj("draftSelection").flag("active"),
                    actionContent = { interactive ->
                        val selection = state.obj("draftSelection")
                        DraftSelectionActions(interactive && !busy, selection.integer("selectedCount") > 0, selection.flag("allSelected"),
                            onCancel = { command.send("draft.selection.cancel") },
                            onToggleAll = { command.send("draft.selection.all", "selected" to !selection.flag("allSelected")) },
                            onDelete = { deletingDraftIds = state.rows("drafts").filter { it.flag("selected") }.map { it.text("id") } })
                    },
                )
            }
        }
        deletingDraftIds?.let { ids ->
            DraftDeleteDialog(ids, busy, onDismiss = { deletingDraftIds = null }) {
                command.send("draft.delete", "ids" to ids, "confirmed" to true)
                deletingDraftIds = null
            }
        }
        deletingWatermarkIds?.let { ids ->
            LibraryDeleteDialog(ids, busy, "水印", "款自定义水印，已有照片与草稿将保留。", "watermark", onDismiss = { deletingWatermarkIds = null }) {
                command.send("watermark.delete", "ids" to ids, "confirmed" to true)
                deletingWatermarkIds = null
            }
        }
            val active = panel
            var lastPanel by remember { mutableStateOf<String?>(null) }
            val currentPanel by rememberUpdatedState(active)
            val focus = LocalFocusManager.current
            LaunchedEffect(active) {
                focus.clearFocus()
                if (active != null && active != "saveStyle") lastPanel = active
            }
            if (active == "saveStyle") {
                SaveStyleDialog(show = true, busy = busy, onDismiss = { changePanel(null) }) { titleValue ->
                    command.send("style.save", "title" to titleValue)
                    changePanel(null)
                }
            } else {
                ChromeBottomSheet(
                    show = active != null, title = panelTitle(active, state.obj("native").flag("enabled")),
                    onDismissRequest = { changePanel(null) },
                    endAction = { TextButton("关闭", { changePanel(null) }, modifier = Modifier.testTag("editor.panel.close")) },
                ) {
                    val offset = with(LocalDensity.current) { StockPanelOffsetDp.dp.roundToPx() }
                    AnimatedContent(
                        targetState = active ?: lastPanel,
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.TopStart,
                        transitionSpec = {
                            val enter = if (reduceMotion) 0 else StockPanelEnterMillis
                            val exit = if (reduceMotion) 0 else StockPanelExitMillis
                            val easing = Easing { stockEaseOutCubic(it) }
                            ((slideInHorizontally(tween(enter, easing = easing)) { if (reduceMotion) 0 else offset } + fadeIn(tween(enter, easing = easing))) togetherWith
                                (slideOutHorizontally(tween(exit, easing = easing)) { if (reduceMotion) 0 else -offset } + fadeOut(tween(exit, easing = easing)))).using(if (reduceMotion) null else SizeTransform(clip = true))
                        }, label = "mediaEditorPanel",
                    ) { shown ->
                        val isCurrent = shown == active
                        val guarded: UiCommand = { action, args ->
                            if (shown == currentPanel || (action == "adjust" && args["phase"] == "cancel")) command(action, args)
                        }
                        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight * .72f).verticalScroll(rememberScrollState()).imePadding()
                            .then(if (isCurrent) Modifier.testTag("editor.panel.${shown ?: "closed"}") else Modifier.clearAndSetSemantics {}.focusProperties { canFocus = false }.pointerInput(Unit) {
                                awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } } }
                            }), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (shown != null) EditorPanel(shown, if (isCurrent) state else state + mapOf("busy" to true), guarded, { if (shown == currentPanel) changePanel(it) }, { if (shown == currentPanel) changePanel(null) })
                        }
                    }
                }
            }
    }
}

}

@Composable
internal fun EditorCanvas(state: Map<*, *>, modifier: Modifier, onCanvasBounds: (Rect?) -> Unit) {
    val layout = state.obj("layout")
    val photo = state.obj("photo")
    val width = layout.number("width", photo.number("width", 1f)).coerceAtLeast(1f)
    val height = layout.number("height", photo.number("height", 1f)).coerceAtLeast(1f)
    Column(modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val ratio = width / height
            val slotWidth = minOf(maxWidth, maxHeight * ratio)
            val slotHeight = slotWidth / ratio
            // No background, pointerInput, clickable, Surface or scrolling parent in this slot.
            Box(Modifier.size(slotWidth, slotHeight).testTag("editor.canvas").onGloballyPositioned { onCanvasBounds(it.findRootCoordinates().localBoundingBoxOf(it, clipBounds = false)) })
        }
        val warnings = (state["modelWarnings"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        if (warnings.isNotEmpty()) SupportingText("部分机型字符缺少 SVG：${warnings.joinToString(" · ")}")
    }
}

@Composable
private fun ActionIcon(label: String, tag: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = hapticClick(feedbackEnabled = tag != "navigation.back", onClick = onClick), enabled = enabled, modifier = Modifier.testTag(tag)) {
        Icon(icon, label, Modifier.size(if (icon == ReferenceIcons.Back) 28.dp else 24.dp), tint = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface)
    }
}

private fun destinationIcon(id: String) = when (id) {
    "styles" -> MiuixIcons.Edit
    "batch" -> MiuixIcons.Photos
    "settings" -> ReferenceIcons.SettingsFilled
    else -> MiuixIcons.Home
}

private fun panelTitle(id: String?, native: Boolean): String = when (id) {
    "styles" -> "选择水印样式"
    "content" -> "水印内容"
    "fonts" -> "水印字体"
    "position" -> "位置与外观"
    "border" -> if (native) "原厂水印布局" else "边框与背景"
    "color" -> "文字颜色"
    "models" -> "预设型号"
    "history" -> "编辑历史"
    else -> "编辑照片"
}

@Composable
private fun SaveStyleDialog(show: Boolean, busy: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    ChromeDialog(show = show, title = "保存为自定义样式", summary = "保留完整字体、图片与原厂资源，方便下次使用。", onDismissRequest = onDismiss) {
        Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextField(title, { title = it.take(40) }, label = "样式名称（最多 40 字符）", singleLine = true, modifier = Modifier.fillMaxWidth().testTag("style.save.title"))
            MainButton("保存样式", "style.save.confirm", !busy && title.isNotBlank()) { onSave(title.trim()) }
        }
    }
}

