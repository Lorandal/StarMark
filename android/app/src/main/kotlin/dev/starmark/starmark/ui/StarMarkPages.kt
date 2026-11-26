package dev.starmark.starmark.ui

import dev.starmark.starmark.ui.g2.LinearProgressIndicator

import dev.starmark.starmark.ui.g2.SwitchPreference

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import dev.starmark.starmark.BuildConfig
import dev.starmark.starmark.xiaomi.XiaomiFilterBlur
import java.util.zip.GZIPInputStream

@Composable
internal fun HomePage(state: Map<*, *>, command: UiCommand, chromeInsets: PaddingValues = PaddingValues(), bannerPage: MutableIntState = rememberSaveable { mutableIntStateOf(1) }, selectionInChrome: Boolean = false, landscapeWorkspace: Boolean = currentEditorWindow().landscapeWorkspace) {
    val busy = state.flag("busy")
    val drafts = state.rows("drafts")
    val selection = state.obj("draftSelection")
    val selecting = selection.flag("active")
    var deletingIds by remember { mutableStateOf<List<String>?>(null) }
    BackHandler(enabled = selecting && state.text("page", "home") == "home") { command.send("draft.selection.cancel") }
    val list = rememberLazyListState()
    val padding = PaddingValues(top = 12.dp, bottom = 24.dp).withChromeInsets(chromeInsets)
    val railInsets = rootRailListInsets(list, padding.calculateBottomPadding(), drafts.size)
    LazyColumn(Modifier.fillMaxSize().testTag("screen.home"), state = list,
        contentPadding = padding.withChromeInsets(railInsets), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "home-banners") { HomeBannerCarousel(state, command, bannerPage, landscapeWorkspace) }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), cornerRadius = 20.dp) {
                ArrowPreference("水印样式", modifier = Modifier.testTag("home.styles"), onClick = { command.send("navigate", "page" to "styles") }, enabled = !busy)
            }
        }
        item {
            Row(Modifier.fillMaxWidth()
                .heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val heading = when {
                    drafts.isEmpty() -> "暂无草稿"
                    selecting && selectionInChrome -> "已选择 ${selection.integer("selectedCount")} 项"
                    else -> "最近草稿"
                }
                SmallTitle(heading, Modifier.weight(1f), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp))
                if (selecting && !selectionInChrome) TextButton("取消", hapticClick { command.send("draft.selection.cancel") }, enabled = !busy, modifier = Modifier.testTag("draft.selection.cancel"), insideMargin = PaddingValues(horizontal = 8.dp, vertical = 8.dp))
                if (drafts.isNotEmpty() && !selectionInChrome) {
                    IconButton(hapticClick(toggleHaptic(selection.flag("allSelected"))) { command.send("draft.selection.all", "selected" to !selection.flag("allSelected")) }, enabled = !busy, modifier = Modifier.size(48.dp).testTag("draft.selection.all")) {
                        Icon(MiuixIcons.SelectAll, if (selection.flag("allSelected")) "取消全选草稿" else "全选草稿", Modifier.size(22.dp))
                    }
                    if (!selectionInChrome) IconButton(hapticClick { deletingIds = drafts.filter { it.flag("selected") }.map { it.text("id") } }, enabled = !busy && selection.integer("selectedCount") > 0, modifier = Modifier.size(48.dp).testTag("draft.delete")) {
                        Icon(MiuixIcons.Delete, "删除选中的草稿", Modifier.size(22.dp), tint = if (selection.integer("selectedCount") > 0) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurface.copy(alpha = .3f))
                    }
                }
            }
        }
        items(drafts, key = { it.text("id") }) { draft ->
            val interaction = remember(draft.text("id")) { MutableInteractionSource() }
            val updated = draft.text("updated")
            val timestamp = remember(updated) { homeDraftTimestamp(updated) }
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(MiuixCornerShape(20.dp)).stockPress(interaction, StockPressKind.Row, !busy),
                cornerRadius = 20.dp,
            ) {
                Row(Modifier.fillMaxWidth()
                    .combinedClickable(interactionSource = interaction, indication = null, enabled = !busy, role = Role.Button,
                        onLongClickLabel = "选择草稿", onLongClick = { command.send("draft.selection.begin", "id" to draft.text("id")) },
                        onClick = hapticClick(toggleHaptic(draft.flag("selected")), feedbackEnabled = selecting) { command.send(if (selecting) "draft.selection.toggle" else "draft.restore", "id" to draft.text("id")) })
                    .testTag("draft.${draft.text("id")}").padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 60.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhotoThumbnail(draft.text("image"), Modifier.size(60.dp), draft.text("title"), cornerRadius = 12.dp, decodeEdge = 256)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(draft.text("title", "照片草稿"), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (timestamp.isNotEmpty()) Text(timestamp, fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("draft.time.${draft.text("id")}"))
                    }
                    if (selecting) Checkbox(if (draft.flag("selected")) ToggleableState.On else ToggleableState.Off,
                        { command.send("draft.selection.toggle", "id" to draft.text("id")) }, enabled = !busy,
                        modifier = Modifier.testTag("draft.selected.${draft.text("id")}").semantics { contentDescription = "选择${draft.text("title", "照片草稿")}" })
                }
            }
        }
    }
    DraftDeleteDialog(deletingIds, busy, onDismiss = { deletingIds = null }) { ids ->
        deletingIds = null
        command.send("draft.delete", "ids" to ids, "confirmed" to true)
    }
}

@Composable
internal fun DraftDeleteDialog(ids: List<String>?, busy: Boolean, onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit) {
    LibraryDeleteDialog(ids, busy, "草稿", "份草稿，原始照片将保留。", "draft", onDismiss,
        title = "确认删除草稿", onConfirm = onConfirm)
}

@Composable
internal fun LibraryDeleteDialog(ids: List<String>?, busy: Boolean, subject: String, summarySuffix: String,
    tagPrefix: String, onDismiss: () -> Unit,
    title: String = "确认删除$subject？", summary: String? = null,
    onConfirm: (List<String>) -> Unit) {
    var count by remember { mutableIntStateOf(0) }
    var remaining by remember { mutableIntStateOf(3) }
    val openedAt = remember(ids) { System.nanoTime() }
    LaunchedEffect(ids) {
        if (ids == null) return@LaunchedEffect
        count = ids.size
        remaining = 3
        while (remaining > 0) {
            delay(100)
            remaining = draftDeleteRemainingSeconds((System.nanoTime() - openedAt) / 1_000_000L)
        }
    }
    ChromeDialog(show = ids != null, title = title, summary = summary ?: "将删除选中的 ${ids?.size ?: count} $summarySuffix", onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton("取消", hapticClick(onClick = onDismiss), Modifier.weight(1f).testTag("$tagPrefix.delete.cancel"))
            TextButton(if (remaining > 0) "确认（$remaining）" else "确认", hapticClick(feedbackEnabled = remaining == 0 && !busy) {
                if (ids != null && !busy && draftDeleteRemainingSeconds((System.nanoTime() - openedAt) / 1_000_000L) == 0) onConfirm(ids)
            },
                Modifier.weight(1f).testTag("$tagPrefix.delete.confirm"), enabled = remaining == 0 && !busy,
                colors = ButtonDefaults.textButtonColorsPrimary(color = MiuixTheme.colorScheme.error, textColor = Color.White,
                    disabledColor = MiuixTheme.colorScheme.disabledSecondaryVariant, disabledTextColor = MiuixTheme.colorScheme.disabledOnSecondaryVariant))
        }
    }
}

@Composable
internal fun StylesPage(state: Map<*, *>, command: UiCommand) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    val busy = state.flag("busy")
    val rows = state.rows("styles").filter { row ->
        row.text("title").contains(query, ignoreCase = true) && when (filter) {
            1 -> row.flag("native")
            2 -> row.text("mode") == "overlay"
            3 -> row.text("mode") == "frame"
            4 -> row.flag("custom")
            else -> true
        }
    }
    PullToRefresh(isRefreshing = busy, onRefresh = { if (!busy) command.send("styles.refresh") }, modifier = Modifier.fillMaxSize().testTag("screen.styles"), refreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新…", "刷新完成")) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                SearchBar(
                    inputField = { InputField(query, { query = it }, { expanded = false }, expanded, { expanded = it }, label = "搜索样式", modifier = Modifier.testTag("styles.search")) },
                    expanded = expanded, onExpandedChange = { expanded = it },
                ) { SupportingText("按名称查找本地样式", Modifier.padding(horizontal = 16.dp)) }
            }
            item { TabRow(listOf("全部", "原厂", "叠加", "边框", "自定义"), filter, hapticSelection(filter) { filter = it }, Modifier.testTag("styles.filter")) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton("导入完整 ZIP", hapticClick { command.send("style.import") }, Modifier.weight(1f).testTag("styles.import"), enabled = !busy)
                    TextButton("刷新样式", hapticClick { command.send("styles.refresh") }, Modifier.weight(1f).testTag("styles.refresh"), enabled = !busy)
                }
            }
            if (rows.isEmpty()) item { EmptyState("没有找到样式", if (query.isNotBlank()) "试试其他名称，或切换分类。" else "导入完整 ZIP 样式包后即可在这里使用。") }
            items(rows, key = { it.text("id") }) { row ->
                StyleCard(row, busy, command)
            }
        }
    }
}

@Composable
internal fun StyleCard(style: Map<*, *>, busy: Boolean, command: UiCommand, afterApply: () -> Unit = {}) {
    val id = style.text("id")
    val selected = style.flag("selected")
    Card(Modifier.fillMaxWidth().testTag("style.$id"), colors = glassCardColors(), insideMargin = PaddingValues(12.dp), onClick = hapticClick(feedbackEnabled = !busy) { if (!busy) { command.send("style.apply", "id" to id); afterApply() } }) {
        PhotoThumbnail(style.text("image"), Modifier.fillMaxWidth().height(146.dp), style.text("title"))
        Row(Modifier.fillMaxWidth().padding(start = 5.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(style.text("title"), fontWeight = FontWeight.SemiBold)
                SupportingText(listOfNotNull(if (style.flag("native")) "原厂水印" else if (style.text("mode") == "frame") "外围边框" else "照片叠加", if (style.flag("custom")) "自定义" else null).joinToString(" · "))
            }
            if (selected) Badge { Text("使用中") }
            ChromeIconDropdownMenu(
                title = "样式操作",
                entry = DropdownEntry(listOf(
                    DropdownItem("应用样式", enabled = !busy, onClick = { command.send("style.apply", "id" to id); afterApply() }),
                    DropdownItem("导出完整 ZIP", enabled = !busy, onClick = { command.send("style.export", "id" to id) }),
                )), modifier = Modifier.testTag("style.$id.more"), enabled = !busy,
            ) { Icon(MiuixIcons.More, "样式操作") }
        }
    }
}

@Composable
internal fun BatchPage(state: Map<*, *>, command: UiCommand, chromeInsets: PaddingValues = PaddingValues()) {
    val batch = state.obj("batch")
    val photos = batch.rows("items")
    val running = batch.flag("running")
    val count = batch.integer("selectedCount")
    val completed = batch.integer("completed")
    val total = batch.integer("total")
    val failed = batch.integer("failed")
    val busy = state.flag("busy") || running
    val canRemove = !busy && count > 0
    val canSelectAll = !busy && photos.isNotEmpty()
    val allSelected = photos.isNotEmpty() && count == photos.size
    val defaultPackage = batch.text("defaultPackage")
    val selectedStyle = styleRows(state).firstOrNull { if (defaultPackage.isBlank()) it.flag("selected") else it.text("id") == defaultPackage }
    val watermarkEnabled = state.obj("document").flag("enabled", true)
    val gridGap = EditorSettingsDefaults.GroupSpacing
    val contentInset = PaddingValues(EditorSettingsDefaults.PageInset).withChromeInsets(chromeInsets)
    val cardPadding = PaddingValues(EditorSettingsDefaults.RowInset)
    val fullRow: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val direction = LocalLayoutDirection.current
        val innerWidth = maxWidth - contentInset.calculateLeftPadding(direction) - contentInset.calculateRightPadding(direction)
        val usualColumns = (maxWidth.value / 128f).toInt().coerceIn(3, 8)
        val columns = minOf(usualColumns, ((innerWidth + gridGap) / (EditorSettingsDefaults.TouchTarget * 2 + gridGap)).toInt().coerceAtLeast(1))
        LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize().testTag("screen.batch"),
            contentPadding = contentInset,
            verticalArrangement = Arrangement.spacedBy(gridGap), horizontalArrangement = Arrangement.spacedBy(gridGap)) {
            item(key = "summary", span = fullRow) {
                EditorSettingsCard(Modifier.testTag("batch.summary"), insideMargin = cardPadding) {
                    if (photos.isEmpty()) {
                        MainButton("添加照片", "batch.add", !busy,
                            Modifier.heightIn(min = EditorSettingsDefaults.TouchTarget), glassMaterial = true) { command.send("batch.add") }
                    } else {
                        Row(Modifier.fillMaxWidth().heightIn(min = EditorSettingsDefaults.RowMinHeight),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gridGap)) {
                            Text("已选 $count 张，共 ${photos.size} 张", modifier = Modifier.weight(1f),
                                fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            TextButton("添加照片", hapticClick { command.send("batch.add") }, enabled = !busy,
                                minHeight = EditorSettingsDefaults.TouchTarget, modifier = Modifier.testTag("batch.add"))
                        }
                        val resume = photos.firstOrNull { it.text("id") == batch.text("currentId") } ?: photos.first()
                        Spacer(Modifier.height(gridGap))
                        MainButton(if (batch.text("currentId").isEmpty()) "逐张编辑" else "继续逐张编辑", "batch.resume", !busy,
                            Modifier.heightIn(min = EditorSettingsDefaults.TouchTarget), glassMaterial = true) {
                            command.send("batch.edit", "id" to resume.text("id"))
                        }
                    }
                }
            }
            item(key = "watermarkStyle", span = fullRow) {
                EditorSettingsCard {
                    ArrowPreference(if (photos.isEmpty()) "新照片的水印" else "统一选中照片的水印",
                        summary = if (!watermarkEnabled) "无水印" else selectedStyle?.text("title") ?: "当前编辑样式",
                        enabled = !busy, modifier = Modifier.heightIn(min = EditorSettingsDefaults.RowMinHeight).testTag("batch.styles"),
                        insideMargin = EditorSettingsDefaults.RowPadding,
                        onClick = hapticClick { command.send("navigate", "page" to "watermarks", "returnPage" to "batch") },
                        endActions = {
                            if (watermarkEnabled && selectedStyle != null) PhotoThumbnail(selectedStyle.text("image"),
                                Modifier.size(60.dp, 48.dp), contentScale = ContentScale.Fit, cornerRadius = 4.dp)
                        })
                }
            }
            if (running || completed > 0 || batch.flag("cancelled")) item(key = "progress", span = fullRow) {
                EditorSettingsCard(Modifier.testTag("batch.progress"), insideMargin = cardPadding) {
                    Text(if (running) "正在导出 $completed / $total" else if (batch.flag("cancelled")) "已停止后续导出" else "批量导出完成", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else 0f, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    SupportingText("已保存 ${(completed - failed).coerceAtLeast(0)} 张 · 失败 $failed 张")
                    if (running) SupportingText(exportStageLabel(state.obj("export").text("stage")))
                    if (running && state.obj("export").flag("highResolution")) SupportingText(HIGH_RESOLUTION_EXPORT_NOTICE)
                    if (running) TextButton("停止后续照片", hapticClick { command.send("batch.cancel") }, Modifier.fillMaxWidth().padding(top = 8.dp).testTag("batch.cancel"))
                }
            }
            if (photos.isNotEmpty()) item(key = "actions", span = fullRow) {
                Row(Modifier.fillMaxWidth().testTag("batch.actions"), verticalAlignment = Alignment.CenterVertically) {
                    HapticCheckboxPreference("全选照片", checked = allSelected,
                        onCheckedChange = { command.send("batch.selectAll", "selected" to it) },
                        enabled = canSelectAll,
                        insideMargin = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("batch.selectAll").semantics(mergeDescendants = true) {
                            role = Role.Checkbox
                            toggleableState = if (allSelected) ToggleableState.On else ToggleableState.Off
                            if (!canSelectAll) disabled()
                        })
                    IconButton(hapticClick { command.send("batch.removeSelected") }, enabled = canRemove,
                        modifier = Modifier.size(48.dp).testTag("batch.removeSelected").semantics(mergeDescendants = true) {
                            role = Role.Button
                            contentDescription = "删除所选候选照片"
                            if (!canRemove) disabled()
                        }) {
                        Icon(MiuixIcons.Delete, null, Modifier.size(22.dp),
                            tint = if (canRemove) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface)
                    }
                }
            }
            items(photos, key = { "photo.${it.text("id")}" }) { photo ->
                val selected = photo.flag("selected")
                val id = photo.text("id")
                val shape = MiuixCornerShape(PhotoGridRadius)
                val interaction = remember { MutableInteractionSource() }
                val toggle = hapticClick(toggleHaptic(selected)) { command.send("batch.toggle", "id" to id) }
                Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    Box(Modifier.matchParentSize().stockPress(interaction, StockPressKind.Watermark, !busy)
                        .clip(shape).then(if (selected) Modifier.border(2.dp, MiuixTheme.colorScheme.primary, shape) else Modifier)
                        .clickable(interaction, indication = null, enabled = !busy, role = Role.Button, onClickLabel = "编辑照片",
                            onClick = hapticClick { command.send("batch.edit", "id" to id) }).testTag("batch.photo.$id")) {
                        PhotoThumbnail(photo.text("image"), Modifier.fillMaxSize(), photo.text("title", "照片"), cornerRadius = PhotoGridRadius)
                        val status = photo.text("result").takeUnless { it == "saving" || it.isBlank() } ?: if (photo.flag("adjusted")) "已调整" else ""
                        if (status.isNotEmpty()) Text(status, Modifier.align(Alignment.BottomStart).padding(4.dp)
                            .background(MiuixTheme.colorScheme.surfaceContainer, MiuixCornerShape(6.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
                            color = MiuixTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                    Box(Modifier.align(Alignment.TopEnd).size(EditorSettingsDefaults.TouchTarget)
                        .toggleable(selected, enabled = !busy, role = Role.Checkbox, onValueChange = { toggle() })
                        .semantics { contentDescription = "选择导出，${photo.text("title", "照片")}" }.testTag("batch.item.$id"),
                        contentAlignment = Alignment.Center) {
                        Checkbox(if (selected) ToggleableState.On else ToggleableState.Off, onClick = null, enabled = !busy)
                    }
                }
            }
            if (photos.isNotEmpty()) item(key = "export", span = fullRow) {
                EditorSettingsCard(insideMargin = cardPadding) {
                    MainButton("按各自水印导出所选 $count 张", "batch.export", !busy && count > 0,
                        Modifier.heightIn(min = EditorSettingsDefaults.TouchTarget), glassMaterial = true) { command.send("batch.export") }
                    if (photos.any { it.text("result") == "失败" }) TextButton("重试失败照片", hapticClick { command.send("batch.export", "failedOnly" to true) }, enabled = !busy)
                }
            }
        }
    }
}

@Composable
internal fun SettingsPage(state: Map<*, *>, command: UiCommand, chromeInsets: PaddingValues = PaddingValues()) {
    val settings = state.obj("settings")
    val appUpdate = state.obj("appUpdate")
    val xiaomiFilterSupported = remember { XiaomiFilterBlur.isAvailable() }
    val themes = listOf("system", "light", "dark")
    var askDetails by remember { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val versionInteraction = remember { MutableInteractionSource() }
    val list = rememberLazyListState()
    val padding = PaddingValues(EditorSettingsDefaults.PageInset).withChromeInsets(chromeInsets)
    val railInsets = rootRailListInsets(list, padding.calculateBottomPadding(), showDetails)
    val row = Modifier.heightIn(min = EditorSettingsDefaults.RowMinHeight)
    LazyColumn(Modifier.fillMaxSize().testTag("screen.settings"), state = list,
        contentPadding = padding.withChromeInsets(railInsets), verticalArrangement = Arrangement.spacedBy(EditorSettingsDefaults.GroupSpacing)) {
        item(key = "appearance") {
            EditorSettingsSection("外观") {
                EditorSettingsCard {
                    ChromeDropdownPreference(listOf("跟随系统", "浅色", "深色"),
                        themes.indexOf(settings.text("theme", "system")).coerceAtLeast(0), "应用主题",
                        modifier = row.testTag("settings.theme"), insideMargin = EditorSettingsDefaults.RowPadding,
                        onSelectedIndexChange = { command.send("settings.update", "patch" to mapOf("theme" to themes[it])) })
                    val effects = LocalStarMarkEffects.current
                    val modes = glassModeChoices(effects.systemGlassSupported)
                    ChromeDropdownPreference(modes.map { it.title }, modes.indexOf(effects.mode).coerceAtLeast(0), "柔光效果",
                        modifier = row.testTag("settings.glass"), insideMargin = EditorSettingsDefaults.RowPadding,
                        onSelectedIndexChange = { command.send("settings.update", "patch" to mapOf("glassMode" to modes[it].id)) })
                    SwitchPreference(settings.flag("reduceMotion"), { command.send("settings.update", "patch" to mapOf("reduceMotion" to it)) },
                        "减弱动态效果", modifier = Modifier.testTag("settings.reduceMotion"))
                    if (xiaomiFilterSupported) {
                        SwitchPreference(settings.flag("xiaomiImageBlur", true), { command.send("settings.update", "patch" to mapOf("xiaomiImageBlur" to it)) },
                            "小米水印模糊", modifier = Modifier.testTag("settings.xiaomiImageBlur"))
                    }
                }
            }
        }
        item(key = "export") {
            EditorSettingsCard {
                SwitchPreference(settings.flag("staticPhotoExport"),
                    { command.send("settings.update", "patch" to mapOf("staticPhotoExport" to it)) },
                    "将动态照片导出为静态照片",
                    modifier = Modifier.testTag("settings.staticPhotoExport"))
            }
        }
        item(key = "experiments") {
            EditorSettingsCard {
                ArrowPreference("实验性功能", modifier = row.testTag("settings.experiments"), insideMargin = EditorSettingsDefaults.RowPadding,
                    onClick = { command.send("navigate", "page" to "experiments") })
            }
        }
        item(key = "about") {
            EditorSettingsSection("关于") {
                EditorSettingsCard {
                    BasicComponent(title = buildTitle(BuildConfig.BUILD_CHANNEL, BuildConfig.VERSION_NAME),
                        summary = buildDetails(BuildConfig.BUILD_CHANNEL, BuildConfig.SOURCE_SHA256, BuildConfig.BUILD_TIMESTAMP),
                        insideMargin = EditorSettingsDefaults.DetailedRowPadding,
                        modifier = row.testTag("settings.version").combinedClickable(interactionSource = versionInteraction, indication = null,
                            role = Role.Button, onLongClickLabel = "查看开源代码许可和组件详情", onLongClick = { askDetails = true }, onClick = {}))
                    ArrowPreference("检查更新", summary = if (appUpdate.flag("checking")) "正在检查…" else null,
                        enabled = !appUpdate.flag("checking") && !state.flag("busy"), insideMargin = EditorSettingsDefaults.RowPadding,
                        modifier = row.testTag("settings.checkUpdate"), onClick = hapticClick { command.send("settings.checkUpdate") })
                    if (showDetails) {
                        ArrowPreference("Miuix 组件", modifier = row.testTag("settings.components"), insideMargin = EditorSettingsDefaults.RowPadding,
                            onClick = { command.send("navigate", "page" to "components") })
                        ArrowPreference("开放源代码许可", modifier = row.testTag("settings.licenses"), insideMargin = EditorSettingsDefaults.RowPadding,
                            onClick = { command.send("navigate", "page" to "licenses") })
                    }
                }
            }
        }
    }
    ChromeDialog(show = askDetails, title = "是否查看开源代码许可和组件详情", onDismissRequest = { askDetails = false }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton("是", hapticClick { showDetails = true; askDetails = false }, Modifier.weight(1f).testTag("settings.details.yes"))
            TextButton("否", hapticClick { showDetails = false; askDetails = false }, Modifier.weight(1f).testTag("settings.details.no"), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable
internal fun AppUpdateDialog(appUpdate: Map<*, *>, ready: Boolean, command: UiCommand) {
    val available = appUpdate.flag("available")
    var lastOffer by remember { mutableStateOf(appUpdate) }
    if (available) SideEffect { lastOffer = appUpdate }
    val offer = if (available) appUpdate else lastOffer
    ChromeDialog(show = ready && available, modifier = Modifier.testTag("settings.update.dialog"),
        title = "发现新版本", summary = "检测到最新版本（${offer.text("versionName")}），是否更新？",
        onDismissRequest = { command.send("settings.dismissUpdate") }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val notes = offer.text("releaseNotes")
            if (notes.isNotBlank()) {
                Text("更新日志", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                key(offer.text("versionName")) {
                    Text(notes, modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                        .heightIn(max = 240.dp).verticalScroll(rememberScrollState()).testTag("settings.update.notes"),
                        style = MiuixTheme.textStyles.body2)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("取消", hapticClick { command.send("settings.dismissUpdate") },
                    Modifier.weight(1f).testTag("settings.update.cancel"))
                TextButton("确认", hapticClick(feedbackEnabled = !offer.flag("opening")) { command.send("settings.openUpdate") },
                    Modifier.weight(1f).testTag("settings.update.confirm"), enabled = !offer.flag("opening"),
                    colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

@Composable
internal fun ExperimentalSettingsPage(state: Map<*, *>, command: UiCommand, chromeInsets: PaddingValues = PaddingValues()) {
    LazyColumn(Modifier.fillMaxSize().testTag("screen.experiments"), contentPadding = PaddingValues(EditorSettingsDefaults.PageInset).withChromeInsets(chromeInsets),
        verticalArrangement = Arrangement.spacedBy(EditorSettingsDefaults.GroupSpacing)) {
        item {
            EditorSettingsCard {
                if ((LocalStarMarkEffects.current.systemGlassSupported || PortablePhotoGlass.supported())) {
                    SwitchPreference(state.obj("settings").flag("glassWatermark"),
                        { command.send("settings.update", "patch" to mapOf("glassWatermark" to it)) },
                        "柔光玻璃水印", modifier = Modifier.testTag("settings.glassWatermark"))
                }
                SwitchPreference(state.obj("settings").flag("autoRemoveWatermark"),
                    { command.send("settings.update", "patch" to mapOf("autoRemoveWatermark" to it)) },
                    "自动移除小米手机默认水印",
                    modifier = Modifier.testTag("settings.autoRemoveWatermark"))
                SwitchPreference(state.obj("settings").flag("replaceDefaultParameterIcon"),
                    { command.send("settings.update", "patch" to mapOf("replaceDefaultParameterIcon" to it)) },
                    "统一替换默认参数图标", summary = "关闭后恢复各水印的设置",
                    enabled = !state.flag("busy"), modifier = Modifier.testTag("settings.replaceDefaultParameterIcon"))
            }
        }
        if (state.obj("settings").flag("replaceDefaultParameterIcon")) item(key = "parameterIcons") {
            EditorSettingsSection("款式") {
                ParameterIconSettingsCard(state, command)
            }
        }
    }
}

@Composable
internal fun LicensesPage(chromeInsets: PaddingValues = PaddingValues(), reduceMotion: Boolean = false) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val notices by produceState(LicenseNoticeCache.peek()) {
        value = withContext(Dispatchers.IO) {
          LicenseNoticeCache.load {
            val flutter = runCatching {
                val raw = context.assets.open("flutter_assets/NOTICES.Z").use { input -> GZIPInputStream(input).bufferedReader().use { it.readNoticeText() } }
                parseFlutterLicenseNotices(raw)
            }.getOrDefault(emptyList())
            val fonts = listOf("Noto Sans" to "NotoSans-LICENSE.txt", "Source Han Sans" to "SourceHanSans-LICENSE.txt").mapNotNull { (title, file) ->
                runCatching { LicenseNotice(title, context.assets.open("flutter_assets/assets/fonts/$file").bufferedReader().use { it.readText() }) }.getOrNull()
            }
            val haze = context.assets.open("flutter_assets/assets/licenses/haze-LICENSE.txt").bufferedReader().use { it.readText() }
            val miuix = context.assets.open("flutter_assets/assets/licenses/miuix-LICENSE.txt").bufferedReader().use { it.readText() }
            val media3 = context.assets.open("flutter_assets/assets/licenses/androidx-media3-LICENSE.txt").bufferedReader().use { it.readText() }
            val mozjpeg = context.assets.open("flutter_assets/assets/licenses/mozjpeg-LICENSE.txt").bufferedReader().use { it.readText() }
            groupLicenseNotices(listOf(LicenseNotice("Miuix · compose-miuix-ui", "Copyright compose-miuix-ui contributors\n\n$miuix"),
                LicenseNotice("Haze 2.1.0 · Christopher Banes and contributors", haze), LicenseNotice("AndroidX Media3 · Google", media3), LicenseNotice("mozjpeg · Mozilla / libjpeg-turbo / IJG", mozjpeg)) + fonts + flutter)
          }
        }
    }
    val filtered = remember(notices, query) { notices.orEmpty().filter { it.titles.any { title -> title.contains(query, true) } || it.text.contains(query, true) } }
    DeferredOperationProgress(notices == null, "licenses", "licenses.loading", "正在读取许可…", "正在整理软件包信息")
    LazyColumn(Modifier.fillMaxSize().testTag("screen.licenses"), contentPadding = PaddingValues(12.dp).withChromeInsets(chromeInsets), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ChromeSearchField(query, { query = it }, "搜索软件包或许可", "licenses.search") }
        items(filtered, key = { it.id }) { notice ->
            val isExpanded = expanded == notice.id
            Card(Modifier.fillMaxWidth().testTag("licenses.group.${notice.id}"), cornerRadius = 24.dp) {
                BasicComponent(title = notice.titles.joinToString(" · "), onClick = { expanded = if (isExpanded) null else notice.id }, modifier = Modifier.testTag("licenses.toggle.${notice.id}"),
                    endActions = { Icon(MiuixIcons.ChevronForward, if (isExpanded) "收起许可" else "展开许可", Modifier.size(16.dp).rotate(if (isExpanded) -90f else 90f), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) })
                AnimatedVisibility(visible = isExpanded, enter = if (reduceMotion) fadeIn() else expandVertically() + fadeIn(), exit = if (reduceMotion) fadeOut() else shrinkVertically() + fadeOut()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("licenses.text.${notice.id}")) {
                        notice.text.chunked(6000).forEach { chunk ->
                            Text(chunk, style = MiuixTheme.textStyles.footnote1, modifier = Modifier.padding(bottom = 8.dp))
                        }
                    }
                }
            }
        }
        if (notices != null && filtered.isEmpty()) item { EmptyState("没有匹配的许可", "请尝试其他软件包名称。") }
    }
}

private fun java.io.Reader.readNoticeText(limit: Int = 8 * 1024 * 1024): String {
    val result = StringBuilder()
    val buffer = CharArray(8192)
    while (result.length < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - result.length))
        if (count < 0) return result.toString()
        result.append(buffer, 0, count)
    }
    if (read() != -1) result.append("\n许可文件超过显示上限。完整许可随应用资源打包。")
    return result.toString()
}
