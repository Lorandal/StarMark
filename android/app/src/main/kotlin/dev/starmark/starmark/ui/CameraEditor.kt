package dev.starmark.starmark.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CameraEditorTopBar(state: Map<*, *>, command: UiCommand, openPanel: (String) -> Unit) {
    val ready = state.flag("ready") && !state.flag("busy")
    val history = state.obj("history")
    val template = state.obj("template").flag("active")
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 72.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CameraCircle("关闭编辑", "navigation.back", MiuixIcons.Close, ready) { command.send("navigate.back") }
        CameraCircle("撤销", "history.undo", MiuixIcons.Undo, ready && history.flag("canUndo")) { command.send("history.undo") }
        CameraCircle("重做", "history.redo", MiuixIcons.Redo, ready && history.flag("canRedo")) { command.send("history.redo") }
        if (template) Text(state.obj("template").text("title"), modifier = Modifier.weight(1f), maxLines = 1) else Spacer(Modifier.weight(1f))
        CameraGlassButton("保存", if (template) "template.save" else "photo.export", ready, width = (34f * LocalDensity.current.fontScale + 34f).dp, onClick = { command.send(if (template) "template.save" else "photo.export") }) {
            Text("保存", fontSize = 17.sp, maxLines = 1, color = if (ready) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface)
        }
        if (template) TextButton("重置", hapticClick { command.send("template.reset") }, modifier = Modifier.testTag("template.reset"))
        if (!template) MorphingMoreMenu(
            entries = remember { listOf(
                MorphMenuEntry("photo.import", "更换照片", MiuixIcons.Image),
                MorphMenuEntry("history", "编辑历史", MiuixIcons.Undo),
                MorphMenuEntry("saveStyle", "保存水印样式", MiuixIcons.Edit),
                MorphMenuEntry("draft.save", "保存草稿", MiuixIcons.Ok),
            ) },
            onAction = { if (it == "history" || it == "saveStyle") openPanel(it) else command.send(it) },
            enabled = ready,
            modifier = Modifier.size(48.dp),
        )
    }
}

@Composable
private fun CameraCircle(label: String, tag: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    CameraGlassButton(label, tag, enabled, onClick = onClick) {
        Icon(icon, null, Modifier.size(28.dp), tint = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface)
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
        ChromeSurface(Modifier.fillMaxSize().padding(2.dp).stockPress(interaction, StockPressKind.Banner, enabled), cornerRadius = 22.dp) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
        }
    }
}

@Composable
internal fun CameraEditorBody(state: Map<*, *>, command: UiCommand, wide: Boolean, openPanel: (String) -> Unit, onCanvasBounds: (androidx.compose.ui.geometry.Rect?) -> Unit) {
    val photoId = state.obj("photo").text("id")
    var tool by rememberSaveable(photoId) { mutableStateOf("styles") }
    var nested by rememberSaveable(photoId) { mutableStateOf<String?>(null) }
    var styleRequest by rememberSaveable(photoId) { mutableIntStateOf(0) }
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
    val sideBySide = wide && LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    BoxWithConstraints(Modifier.fillMaxSize().imePadding().testTag(if (state.obj("template").flag("active")) "screen.templateEditor" else "screen.editor")) {
        val compactHeight = maxHeight < 400.dp
        if (sideBySide) {
            Row(Modifier.fillMaxSize()) {
                EditorCanvas(state, Modifier.weight(.54f).fillMaxHeight(), onCanvasBounds)
                InlineEditorTools(state, command, tool, nested, ::choose, ::openInside, { nested = null }, Modifier.weight(.46f).fillMaxHeight(), styleRequest)
            }
        } else {
            val density = LocalDensity.current
            val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
            val bottomMargin = navigationBottomMargin(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().value).dp
            // Content reaches the window edge, while fixed controls reserve the gesture area.
            val controlsMinimum = if (keyboardOpen) 204.dp
                else floatingNavigationMetrics(maxWidth.value, density.fontScale, 4).height.dp + 188.dp + bottomMargin
            // Small windows reserve enough room for the fixed watermark controls; the normal
            // portrait layout remains 65%, while the IME keeps the existing compact arrangement.
            val previewHeight = minOf(maxHeight * (if (compactHeight) .34f else .65f),
                (maxHeight - controlsMinimum).coerceAtLeast(0.dp))
            Column(Modifier.fillMaxSize()) {
                EditorCanvas(state, Modifier.height(previewHeight).fillMaxWidth(), onCanvasBounds)
                InlineEditorTools(state, command, tool, nested, ::choose, ::openInside, { nested = null }, Modifier.weight(1f).fillMaxWidth(), styleRequest)
            }
        }
    }
}

@Composable
private fun InlineEditorTools(
    state: Map<*, *>, command: UiCommand, tool: String, nested: String?,
    choose: (String) -> Unit, open: (String) -> Unit, closeNested: () -> Unit, modifier: Modifier, styleRequest: Int,
) {
    val density = LocalDensity.current
    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
    val bottomMargin = navigationBottomMargin(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().value).dp
    // Editing the document does not start a new browsing session. In particular,
    // preserve strip offsets on template/font changes and leaving for the library.
    val draftScope = state.obj("photo").text("id")
    val savedTools = key(draftScope) { rememberSaveableStateHolder() }
    val entries = remember { listOf(
        FloatingNavEntry("styles", "水印", MiuixIcons.Image),
        FloatingNavEntry("content", "内容", MiuixIcons.Edit),
        FloatingNavEntry("fonts", "字体", MiuixIcons.Rename, iconText = "Aa"),
        FloatingNavEntry("position", "位置", ReferenceIcons.Adjust),
    ) }
    BoxWithConstraints(modifier.background(MiuixTheme.colorScheme.background)) {
            val navHeight = floatingNavigationMetrics(maxWidth.value, density.fontScale, 4).height
            val paneHeight = maxHeight
            val selectedTool = nested ?: tool
            // This pane's chrome is only the inline navigation; it has no cross-window popup.
            ChromePane(Modifier.fillMaxSize(), systemGlassLocalFallbackPossible = false, body = {
            ReferencePageTransition(selectedTool, toolOrder = listOf("styles", "content", "fonts", "position", "color", "models")) { shown, _ ->
            val draftKey = "$shown:$draftScope"
            savedTools.SaveableStateProvider(draftKey) {
                val current = shown == selectedTool
                val guarded: UiCommand = { action, args -> if (current || (action == "adjust" && args["phase"] == "cancel")) command(action, args) }
                val panelScroll = rememberScrollState()
                var handledStyleRequest by rememberSaveable { mutableIntStateOf(0) }
                LaunchedEffect(current, styleRequest) {
                    if (current && shown == "content" && styleRequest > handledStyleRequest) {
                        panelScroll.scrollTo(0)
                        handledStyleRequest = styleRequest
                    }
                }
                if (shown == "styles") {
                    CameraWatermarkControls(state, guarded, open,
                        Modifier.fillMaxSize()
                            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = if (keyboardOpen) 16.dp else navHeight.dp + 8.dp + bottomMargin)
                            .testTag("editor.inline.styles"))
                } else Column(
                    Modifier.fillMaxWidth()
                        .verticalScroll(panelScroll)
                        .heightIn(min = paneHeight)
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = if (keyboardOpen) 16.dp else navHeight.dp + 12.dp + bottomMargin)
                        .testTag("editor.inline.$shown"),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
                ) {
                    if (shown in listOf("color", "models")) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ guarded.send("interaction.cancel"); if (current) closeNested() }, modifier = Modifier.size(48.dp).testTag("editor.inline.back")) { Icon(ReferenceIcons.Back, "返回") }
                        Text(if (shown == "color") "文字颜色" else "预设型号", style = MiuixTheme.textStyles.headline1)
                    }
                    EditorPanel(shown, if (current) state else state + mapOf("busy" to true), guarded, open, { if (current && nested != null) closeNested() })
                }
                }
            }
            }, chrome = {
                if (!keyboardOpen) FloatingNavigation(entries, tool, choose, enabled = state.editable(), tagPrefix = "editor.tool", modifier = Modifier.align(Alignment.BottomCenter))
            })
    }
}

@Composable
private fun CameraWatermarkControls(state: Map<*, *>, command: UiCommand, openPanel: (String) -> Unit, modifier: Modifier) {
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
    val accent = Color(0xFFFFCE16)
    val native = state.obj("native")
    val options = state.obj("document").obj("xiaomiOptions")
    val metadata = state.obj("photo").obj("metadata")
    val supportsTime = native.flag("enabled") && native.flag("supportsTime")
    val supportsLocation = native.flag("enabled") && native.flag("supportsLocation")
    val hasTime = metadata.text("dateTime").isNotBlank()
    val locationTitle = if (native.text("locationKind") == "address") "地址" else "经纬度"
    val hasLocation = native.flag("locationAvailable")
    val locationReason = native.text("locationUnavailableReason").takeIf { it.isNotEmpty() }
    val canChooseStyle = nativeStyleChoicesAvailable(native, LocalStarMarkEffects.current.systemGlassSupported || PortablePhotoGlass.supported())
    Column(modifier, verticalArrangement = Arrangement.SpaceEvenly) {
        // Keep the controls mounted across templates. Unsupported options are disabled,
        // so rapid selection cannot remove/recreate the entire row or its glass source.
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CameraWatermarkOption(
                    "时间", "editor.showTime", MiuixIcons.Timer, options.flag("showTime", true),
                    available = enabled && supportsTime && hasTime, editable = editable,
                    unavailableReason = if (!supportsTime) "此水印不支持时间开关" else if (!hasTime) "照片没有拍摄时间" else null, accent = accent,
                ) { command.patch("拍摄时间显示", "xiaomiOptions" to mapOf("showTime" to !options.flag("showTime", true))) }
                CameraWatermarkOption(
                    locationTitle, "editor.showLocation", MiuixIcons.Location, options.flag("showLocation", true),
                    available = enabled && supportsLocation && hasLocation, editable = editable,
                    unavailableReason = if (!supportsLocation) "此水印不支持位置开关" else locationReason, accent = accent,
                ) { command.patch("${locationTitle}显示", "xiaomiOptions" to mapOf("showLocation" to !options.flag("showLocation", true))) }
                }
                CameraStyleOption(available = enabled && canChooseStyle, editable = editable) { openPanel("styleOptions") }
            }
        }
        if (!state.obj("template").flag("active")) Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).height(44.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                groups.forEach { group ->
                    val id = group.text("category")
                    TextButton(group.text("categoryTitle", "自定义").removeSuffix("水印"), hapticClick(HapticFeedbackType.SegmentTick, feedbackEnabled = category != id) { category = id }, enabled = editable,
                        modifier = Modifier.height(44.dp).testTag("editor.category.$id").semantics { this.selected = category == id },
                        minHeight = 44.dp, insideMargin = PaddingValues(horizontal = 12.dp),
                        textStyle = MiuixTheme.textStyles.main.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        colors = ButtonDefaults.textButtonColors(color = Color.Transparent, textColor = if (category == id) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary))
                }
            }
            IconButton(
                { command.send("navigate", "page" to "watermarks", "returnPage" to "editor") },
                enabled = editable, modifier = Modifier.size(48.dp).testTag("editor.openWatermarks"),
            ) {
                Icon(MiuixIcons.ChevronForward, "全部水印", Modifier.size(20.dp), tint = if (editable) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface)
            }
        }
        if (!state.obj("template").flag("active")) Row(Modifier.fillMaxWidth().height(88.dp).padding(start = 12.dp).testTag("editor.watermarkStrip"), verticalAlignment = Alignment.CenterVertically) {
            CameraNoWatermark(!enabled, editable, accent) { command.patch("关闭照片水印", "enabled" to false) }
            Spacer(Modifier.width(9.dp))
            categoryStates.SaveableStateProvider(category) {
            // Center the 64dp tiles in the available row. At the intended 88dp row
            // this supplies 12dp above and below; on shorter rows only that air shrinks.
            LazyRow(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                items(rows.filter { it.text("category") == category }, key = { it.text("id") }) { row ->
                    WatermarkTile(row, enabled && row.flag("selected"), 80.dp, accent, editable, height = 64.dp) {
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
            ChromeSurface(Modifier.stockPress(interaction, StockPressKind.Banner, enabled), cornerRadius = 40.dp, shadowElevation = 2.dp) {
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
private fun CameraWatermarkOption(title: String, tag: String, icon: ImageVector, selected: Boolean, available: Boolean, editable: Boolean, unavailableReason: String?, accent: Color, onClick: () -> Unit) {
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
                stateDescription = unavailableReason ?: if (selected) "已显示" else "已隐藏"
            }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.background(if (selected && available) accent else MiuixTheme.colorScheme.surfaceContainer, MiuixCornerShape(50.dp))
                .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = foreground)
            Text(title, fontSize = 13.sp, color = foreground, maxLines = 1)
        }
    }
}

