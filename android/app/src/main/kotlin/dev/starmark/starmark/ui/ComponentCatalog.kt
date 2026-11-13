package dev.starmark.starmark.ui

import dev.starmark.starmark.ui.g2.LinearProgressIndicator

import dev.starmark.starmark.ui.g2.RangeSliderPreference

import dev.starmark.starmark.ui.g2.SliderPreference

import dev.starmark.starmark.ui.g2.Slider

import dev.starmark.starmark.ui.g2.SwitchPreference

import dev.starmark.starmark.ui.g2.Switch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BreadcrumbBar
import top.yukonga.miuix.kmp.basic.BreadcrumbItem
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.FloatingToolbar
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Snackbar
import top.yukonga.miuix.kmp.basic.SnackbarData
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarVisuals
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Expected-55 checklist, in the same order as the approved Miuix design specification. */
val CatalogComponentNames: List<String> = listOf(
    // Scaffold: 1.
    "Scaffold",
    // Basics: 30. ProgressIndicator, Tooltip and Divider are official component families.
    "Surface", "Card", "BasicComponent", "TopAppBar", "NavigationBar", "NavigationRail",
    "TabRow", "BreadcrumbBar", "Button", "IconButton", "Text", "SmallTitle", "TextField",
    "SearchBar", "Switch", "Checkbox", "RadioButton", "Slider", "NumberPicker",
    "ProgressIndicator", "Snackbar", "Tooltip", "Badge", "Icon", "FloatingActionButton",
    "FloatingToolbar", "Divider", "PullToRefresh", "ColorPalette", "ColorPicker",
    // Extensions: 24.
    "ArrowPreference", "SwitchPreference", "CheckboxPreference", "RadioButtonPreference",
    "SliderPreference", "RangeSliderPreference", "OverlayListPopup", "OverlayCascadingListPopup",
    "OverlayDropdownPreference", "OverlaySpinnerPreference", "OverlayDropdownMenu",
    "OverlayIconDropdownMenu", "OverlayIconCascadingDropdownMenu", "OverlayBottomSheet",
    "OverlayDialog", "WindowListPopup", "WindowCascadingListPopup", "WindowDropdownPreference",
    "WindowSpinnerPreference", "WindowDropdownMenu", "WindowIconDropdownMenu",
    "WindowIconCascadingDropdownMenu", "WindowBottomSheet", "WindowDialog",
)

private val CatalogOptions = listOf("人像", "旅途", "夜景")

/**
 * Local, disposable demo state only. No native bridge, file, photograph or settings commands.
 * The caller supplies the real Miuix theme, font and outer Scaffold/popup host.
 */
@Composable
fun ComponentCatalog(chromeInsets: PaddingValues = PaddingValues()) {
    val scope = rememberCoroutineScope()
    val layers = remember(scope) { CatalogLayers(scope) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("component.catalog"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp).withChromeInsets(chromeInsets),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "catalog.intro") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Miuix 组件预览", style = MiuixTheme.textStyles.title1)
                Text("55 项 · 1 个脚手架 · 30 个基础 · 24 个扩展")
                Text(
                    "这里的输入、选择和刷新只影响示例。弹出层保留组件选项与操作，统一使用应用的玻璃外观；可点空白处、返回或关闭按钮退出。",
                    style = MiuixTheme.textStyles.footnote1,
                )
            }
        }
        items(CatalogComponentNames, key = { it }, contentType = { "component" }) { name ->
            if (name == "Scaffold" || name == "Surface" || name == "ArrowPreference") {
                SmallTitle(
                    when (name) {
                        "Scaffold" -> "脚手架 · 1"
                        "Surface" -> "基础组件 · 30"
                        else -> "扩展组件 · 24"
                    },
                )
            }
            Card(
                modifier = Modifier.fillMaxWidth().testTag("component.$name"),
                insideMargin = PaddingValues(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(name, modifier = Modifier.testTag("component.$name.heading").semantics { heading() }, style = MiuixTheme.textStyles.subtitle)
                    CatalogSample(name, layers)
                }
            }
        }
    }
}

/** Keeps anchors locked while a previous layer is dismissing, including wrapper-owned popups. */
@Stable
private class CatalogLayers(private val scope: CoroutineScope) {
    private var owner by mutableStateOf<String?>(null)
    private var closing by mutableStateOf(false)
    private var generation = 0

    fun available(name: String): Boolean = owner == null || (owner == name && !closing)
    fun shown(name: String): Boolean = owner == name && !closing

    fun open(name: String) {
        if (!available(name)) return
        generation++
        owner = name
        closing = false
    }

    fun close(name: String) {
        if (owner != name || closing) return
        closing = true
        val token = ++generation
        // Dismiss-finished normally releases the owner. Keep a fallback for disposed anchors.
        scope.launch {
            delay(1000)
            if (generation == token) finished(name)
        }
    }

    fun expanded(name: String, expanded: Boolean) {
        if (expanded) open(name) else close(name)
    }

    fun finished(name: String) {
        if (owner == name) {
            generation++
            owner = null
            closing = false
        }
    }

    fun dispose(name: String) {
        if (owner == name || owner?.startsWith("$name/") == true) {
            generation++
            owner = null
            closing = false
        }
    }
}

@Composable
private fun CatalogSample(name: String, layers: CatalogLayers) {
    DisposableEffect(name) { onDispose { layers.dispose(name) } }
    when (name) {
        "Scaffold" -> CatalogScaffold()
        "Surface" -> {
            var count by rememberSaveable { mutableIntStateOf(0) }
            Surface(onClick = { count++ }, modifier = Modifier.fillMaxWidth()) {
                Text("点击表面 · $count", modifier = Modifier.padding(20.dp))
            }
        }
        "Card" -> {
            var count by rememberSaveable { mutableIntStateOf(0) }
            Card(
                onClick = { count++ },
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(20.dp),
                showIndication = true,
            ) { Text("可点击卡片 · $count") }
        }
        "BasicComponent" -> {
            var count by rememberSaveable { mutableIntStateOf(0) }
            BasicComponent(
                title = "示例项目",
                summary = "已点击 $count 次",
                startAction = { Icon(MiuixIcons.Image, "照片示例") },
                onClick = { count++ },
            )
        }
        "TopAppBar" -> {
            var count by rememberSaveable { mutableIntStateOf(0) }
            TopAppBar(
                title = "示例页面",
                subtitle = "更多操作 $count 次",
                defaultWindowInsetsPadding = false,
                actions = {
                    IconButton(onClick = { count++ }) { Icon(MiuixIcons.More, "示例更多操作") }
                },
            )
        }
        "NavigationBar" -> CatalogNavigationBar()
        "NavigationRail" -> CatalogNavigationRail()
        "TabRow" -> {
            var selected by rememberSaveable { mutableIntStateOf(0) }
            TabRow(CatalogOptions, selected, { selected = it })
            Text("当前：${CatalogOptions[selected]}")
        }
        "BreadcrumbBar" -> {
            var selected by rememberSaveable { mutableIntStateOf(2) }
            BreadcrumbBar(
                items = listOf(
                    BreadcrumbItem("/", "预览"),
                    BreadcrumbItem("/album", "相册"),
                    BreadcrumbItem("/album/travel", "旅途"),
                ),
                onItemClick = { selected = it },
                highlightIndex = selected,
            )
            Text("已选路径层级：${selected + 1}")
        }
        "Button" -> {
            var count by rememberSaveable { mutableIntStateOf(0) }
            Button(onClick = { count++ }) { Text("点击示例 · $count") }
        }
        "IconButton" -> {
            var count by rememberSaveable { mutableIntStateOf(0) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { count++ }) { Icon(MiuixIcons.Add, "增加示例计数") }
                Text("计数：$count")
            }
        }
        "Text" -> {
            var large by rememberSaveable { mutableStateOf(false) }
            Text(
                "照片里的每一刻 · StarMark",
                style = if (large) MiuixTheme.textStyles.title1 else MiuixTheme.textStyles.main,
            )
            Text("MiSans 字体继承自应用主题", style = MiuixTheme.textStyles.footnote1)
            Button(onClick = { large = !large }) { Text("切换文字层级") }
        }
        "SmallTitle" -> {
            var alternate by rememberSaveable { mutableStateOf(false) }
            SmallTitle(if (alternate) "已切换的小标题" else "分组小标题")
            Button(onClick = { alternate = !alternate }) { Text("切换标题") }
        }
        "TextField" -> {
            var value by rememberSaveable { mutableStateOf("StarMark") }
            HalfWidthTextInput {
                TextField(value = value, onValueChange = { value = it }, label = "示例文字")
            }
            Text("${value.length} 个字符", style = MiuixTheme.textStyles.footnote1)
        }
        "SearchBar" -> CatalogSearchBar()
        "Switch" -> {
            var checked by rememberSaveable { mutableStateOf(true) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Switch(checked, { checked = it })
                Text(if (checked) "示例已开启" else "示例已关闭")
            }
        }
        "Checkbox" -> {
            var state by rememberSaveable { mutableIntStateOf(0) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Checkbox(
                    state = listOf(ToggleableState.Off, ToggleableState.On, ToggleableState.Indeterminate)[state],
                    onClick = { state = (state + 1) % 3 },
                )
                Text(listOf("未选择", "已选择", "部分选择")[state])
            }
        }
        "RadioButton" -> {
            var selected by rememberSaveable { mutableIntStateOf(0) }
            CatalogOptions.take(2).forEachIndexed { index, label ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RadioButton(selected == index, { selected = index })
                    Text(label)
                }
            }
        }
        "Slider" -> {
            var value by rememberSaveable { mutableFloatStateOf(50f) }
            Slider(value, { value = it }, valueRange = 0f..100f)
            Text("示例数值：${value.toInt()}%")
        }
        "NumberPicker" -> {
            var value by rememberSaveable { mutableIntStateOf(5) }
            NumberPicker(value, { value = it }, range = 1..9, visibleItemCount = 3)
            Text("已选择：$value")
        }
        "ProgressIndicator" -> {
            var progress by rememberSaveable { mutableFloatStateOf(0.6f) }
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(progress = progress)
                CircularProgressIndicator()
                Text("${(progress * 100).toInt()}%")
            }
            Slider(progress, { progress = it })
        }
        "Snackbar" -> CatalogSnackbar()
        "Tooltip" -> CatalogTooltip(layers)
        "Badge" -> {
            var count by rememberSaveable { mutableIntStateOf(3) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(MiuixIcons.Image, "示例照片")
                Badge { Text(count.toString()) }
                Badge()
            }
            Button(onClick = { count = if (count == 9) 0 else count + 1 }) { Text("增加数量") }
        }
        "Icon" -> {
            var highlighted by rememberSaveable { mutableStateOf(false) }
            Icon(
                MiuixIcons.Favorites,
                if (highlighted) "已标记的示例图标" else "示例图标",
                modifier = Modifier.size(40.dp),
                tint = if (highlighted) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            )
            Button(onClick = { highlighted = !highlighted }) { Text("切换图标颜色") }
        }
        "FloatingActionButton" -> {
            var count by rememberSaveable { mutableIntStateOf(0) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                FloatingActionButton(onClick = { count++ }) { Icon(MiuixIcons.Add, "增加示例项目") }
                Text("示例项目：$count")
            }
        }
        "FloatingToolbar" -> {
            var selection by rememberSaveable { mutableStateOf("编辑") }
            FloatingToolbar {
                Row {
                    IconButton(onClick = { selection = "编辑" }) { Icon(MiuixIcons.Edit, "预览编辑工具") }
                    IconButton(onClick = { selection = "照片" }) { Icon(MiuixIcons.Image, "预览照片工具") }
                    IconButton(onClick = { selection = "设置" }) { Icon(MiuixIcons.Settings, "预览设置工具") }
                }
            }
            Text("当前工具：$selection")
        }
        "Divider" -> {
            Text("水平分隔线")
            HorizontalDivider()
            Row(modifier = Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("左侧", modifier = Modifier.weight(1f))
                VerticalDivider()
                Text("右侧", modifier = Modifier.weight(1f).padding(start = 16.dp))
            }
        }
        "PullToRefresh" -> CatalogPullToRefresh()
        "ColorPalette" -> {
            var argb by rememberSaveable { mutableIntStateOf(0xFF3C7DFF.toInt()) }
            ColorPalette(Color(argb), { argb = it.toArgb() }, modifier = Modifier.fillMaxWidth())
            Text("示例颜色：${catalogHex(argb)}")
        }
        "ColorPicker" -> {
            var argb by rememberSaveable { mutableIntStateOf(0xFF3C7DFF.toInt()) }
            ColorPicker(Color(argb), { argb = it.toArgb() }, modifier = Modifier.fillMaxWidth())
            Text("示例颜色：${catalogHex(argb)}")
        }
        "ArrowPreference" -> {
            var count by rememberSaveable { mutableIntStateOf(0) }
            ArrowPreference(title = "示例导航项目", summary = "已点击 $count 次", onClick = { count++ })
        }
        "SwitchPreference" -> {
            var checked by rememberSaveable { mutableStateOf(true) }
            SwitchPreference(checked, { checked = it }, title = "示例开关", summary = "仅影响当前预览")
        }
        "CheckboxPreference" -> {
            var checked by rememberSaveable { mutableStateOf(false) }
            CheckboxPreference("示例多选项目", checked, { checked = it }, summary = "点击整行或复选框")
        }
        "RadioButtonPreference" -> {
            var selected by rememberSaveable { mutableIntStateOf(0) }
            CatalogOptions.take(2).forEachIndexed { index, label ->
                RadioButtonPreference(label, selected == index, { selected = index }, summary = "示例互斥选项")
            }
        }
        "SliderPreference" -> {
            var value by rememberSaveable { mutableFloatStateOf(0.5f) }
            SliderPreference(value, { value = it }, title = "示例透明度", valueText = "${(value * 100).toInt()}%")
        }
        "RangeSliderPreference" -> {
            var start by rememberSaveable { mutableFloatStateOf(0.2f) }
            var end by rememberSaveable { mutableFloatStateOf(0.8f) }
            RangeSliderPreference(
                start..end,
                { start = it.start; end = it.endInclusive },
                title = "示例范围",
                valueText = "${(start * 100).toInt()}–${(end * 100).toInt()}%",
            )
        }
        "OverlayListPopup", "WindowListPopup" -> CatalogListPopup(name, layers)
        "OverlayCascadingListPopup", "WindowCascadingListPopup" -> CatalogCascadingPopup(name, layers)
        "OverlayDropdownPreference", "WindowDropdownPreference" -> CatalogDropdownPreference(name, layers)
        "OverlaySpinnerPreference", "WindowSpinnerPreference" -> CatalogSpinnerPreference(name, layers)
        "OverlayDropdownMenu", "WindowDropdownMenu", "OverlayIconDropdownMenu", "WindowIconDropdownMenu",
        "OverlayIconCascadingDropdownMenu", "WindowIconCascadingDropdownMenu" -> CatalogMenu(name, layers)
        "OverlayBottomSheet", "WindowBottomSheet" -> CatalogBottomSheet(name, layers)
        "OverlayDialog", "WindowDialog" -> CatalogDialog(name, layers)
        else -> error("Missing real Miuix sample for $name")
    }
}

@Composable
private fun CatalogScaffold() {
    var count by rememberSaveable { mutableIntStateOf(0) }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Scaffold(
        modifier = Modifier.fillMaxWidth().height((220 * fontScale).dp),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { BasicComponent(title = "独立脚手架示例", insideMargin = PaddingValues(12.dp)) },
        bottomBar = { HorizontalDivider() },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("内容与标题栏由真实 Scaffold 布局")
            Button(onClick = { count++ }) { Text("示例操作 · $count") }
        }
    }
}

@Composable
private fun CatalogNavigationBar() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    NavigationBar(defaultWindowInsetsPadding = false) {
        listOf(MiuixIcons.Home, MiuixIcons.Image, MiuixIcons.Settings).forEachIndexed { index, icon ->
            NavigationBarItem(selected == index, { selected = index }, icon, listOf("主页", "照片", "设置")[index])
        }
    }
    Text("当前示例页：${listOf("主页", "照片", "设置")[selected]}")
}

@Composable
private fun CatalogNavigationRail() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Row(modifier = Modifier.fillMaxWidth().height((264 * fontScale).dp)) {
        NavigationRail(defaultWindowInsetsPadding = false) {
            listOf(MiuixIcons.Home, MiuixIcons.Image, MiuixIcons.Settings).forEachIndexed { index, icon ->
                NavigationRailItem(selected == index, { selected = index }, icon, listOf("主页", "照片", "设置")[index])
            }
        }
        Column(modifier = Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("宽屏导航示例")
            Text("当前：${listOf("主页", "照片", "设置")[selected]}")
            Text("左侧导航可滚动", style = MiuixTheme.textStyles.footnote1)
        }
    }
}

@Composable
private fun CatalogSearchBar() {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    SearchBar(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        inputField = {
            InputField(
                query, { query = it }, onSearch = { expanded = true },
                expanded = expanded, onExpandedChange = { expanded = it }, label = "搜索示例分类",
            )
        },
        outsideEndAction = { Button(onClick = { expanded = false }) { Text("收起") } },
    ) {
        val matches = CatalogOptions.filter { it.contains(query, ignoreCase = true) }
        if (matches.isEmpty()) Text("没有匹配的示例", modifier = Modifier.padding(12.dp))
        matches.forEach { label ->
            BasicComponent(title = label, onClick = { query = label; expanded = false })
        }
    }
}

@Composable
private fun CatalogSnackbar() {
    var visible by rememberSaveable { mutableStateOf(false) }
    var result by rememberSaveable { mutableStateOf("等待显示") }
    val data = remember {
        object : SnackbarData {
            override val visuals = SnackbarVisuals("这是一条预览提示", "知道了", true, SnackbarDuration.Indefinite)
            override suspend fun dismiss() { visible = false; result = "已关闭" }
            override suspend fun performAction() { visible = false; result = "已确认" }
        }
    }
    Button(onClick = { visible = true; result = "提示已显示" }, enabled = !visible) { Text("显示 Snackbar") }
    if (visible) Snackbar(data)
    Text(result, style = MiuixTheme.textStyles.footnote1)
}

@Composable
private fun CatalogTooltip(layers: CatalogLayers) {
    Button(onClick = { layers.open("Tooltip") }, enabled = layers.available("Tooltip")) { Text("显示提示") }
    ChromeDialog(
        show = layers.shown("Tooltip"), title = "提示",
        onDismissRequest = { layers.close("Tooltip") },
        onDismissFinished = { layers.finished("Tooltip") },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("这是提示气泡内容，使用与应用一致的玻璃弹层展示。")
            Button(onClick = { layers.close("Tooltip") }, modifier = Modifier.fillMaxWidth()) { Text("关闭提示") }
        }
    }
}

@Composable
private fun CatalogPullToRefresh() {
    var refreshing by rememberSaveable { mutableStateOf(false) }
    var generation by rememberSaveable { mutableIntStateOf(0) }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    LaunchedEffect(refreshing) {
        if (refreshing) { delay(800); generation++; refreshing = false }
    }
    Text("在下面的示例列表向下拉动 · 已刷新 $generation 次", style = MiuixTheme.textStyles.footnote1)
    PullToRefresh(isRefreshing = refreshing, onRefresh = { refreshing = true }) {
        LazyColumn(modifier = Modifier.fillMaxWidth().height((180 * fontScale).dp)) {
            items(5) { index -> BasicComponent(title = "预览项目 ${index + 1}", summary = "本地示例批次 $generation") }
        }
    }
    Button(onClick = { refreshing = true }, enabled = !refreshing) { Text("触发示例刷新") }
}

@Composable
private fun CatalogListPopup(name: String, layers: CatalogLayers) {
    var selected by rememberSaveable { mutableStateOf("未选择") }
    var anchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Button(onClick = { layers.open(name) }, enabled = layers.available(name), modifier = Modifier.onGloballyPositioned {
        val p = it.localToScreen(androidx.compose.ui.geometry.Offset.Zero)
        anchor = androidx.compose.ui.geometry.Rect(p.x, p.y, p.x + it.size.width, p.y + it.size.height)
    }) { Text("打开列表") }
    ChromeMenuDialog(
        anchorScreen = anchor,
        show = layers.shown(name), title = "示例列表",
        entries = listOf(DropdownEntry(CatalogOptions.map { label ->
            DropdownItem(label, selected = selected == label, onClick = { selected = label })
        })),
        onDismissRequest = { layers.close(name) },
        onDismissFinished = { layers.finished(name) },
    )
    Text("已选：$selected")
}

@Composable
private fun CatalogCascadingPopup(name: String, layers: CatalogLayers) {
    var selected by rememberSaveable { mutableStateOf("未选择") }
    val entries = catalogCascadingEntries(selected) { selected = it }
    var anchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Button(onClick = { layers.open(name) }, enabled = layers.available(name), modifier = Modifier.onGloballyPositioned {
        val p = it.localToScreen(androidx.compose.ui.geometry.Offset.Zero)
        anchor = androidx.compose.ui.geometry.Rect(p.x, p.y, p.x + it.size.width, p.y + it.size.height)
    }) { Text("打开级联列表") }
    ChromeMenuDialog(
        anchorScreen = anchor,
        show = layers.shown(name), title = "示例级联列表", entries = entries,
        onDismissRequest = { layers.close(name) },
        onDismissFinished = { layers.finished(name) },
    )
    Text("已选：$selected")
}

@Composable
private fun CatalogDropdownPreference(name: String, layers: CatalogLayers) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    ChromeDropdownPreference(
        items = CatalogOptions, selectedIndex = selected, title = "示例分类",
        enabled = layers.available(name), onExpandedChange = { layers.expanded(name, it) },
        onDismissFinished = { layers.finished(name) },
        onSelectedIndexChange = { selected = it },
    )
    Text("已选：${CatalogOptions[selected]}")
}

@Composable
private fun CatalogSpinnerPreference(name: String, layers: CatalogLayers) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var dialogSelected by rememberSaveable { mutableIntStateOf(1) }
    val items = CatalogOptions.map { label ->
        DropdownItem(text = label, summary = "示例分类说明", icon = { Icon(MiuixIcons.Image, null, it) })
    }
    val dialogOwner = "$name/dialog"
    ChromeSpinnerPreference(
        items = items, selectedIndex = selected, title = "列表选择器",
        enabled = layers.available(name), onExpandedChange = { layers.expanded(name, it) },
        onDismissFinished = { layers.finished(name) },
        onSelectedIndexChange = { selected = it },
    )
    ChromeSpinnerPreference(
        items = items, selectedIndex = dialogSelected, title = "对话框选择器", dialogButtonString = "完成",
        enabled = layers.available(dialogOwner), onExpandedChange = { layers.expanded(dialogOwner, it) },
        onDismissFinished = { layers.finished(dialogOwner) },
        onSelectedIndexChange = { dialogSelected = it },
    )
    Text("列表：${CatalogOptions[selected]} · 对话框：${CatalogOptions[dialogSelected]}")
}

@Composable
private fun CatalogMenu(name: String, layers: CatalogLayers) {
    var selected by rememberSaveable { mutableStateOf("未选择") }
    val cascading = name.contains("Cascading")
    val entry = if (cascading) {
        catalogCascadingEntries(selected) { selected = it }.single()
    } else {
        DropdownEntry(CatalogOptions.map { label -> DropdownItem(label, selected = selected == label, onClick = { selected = label }) })
    }
    val enabled = layers.available(name)
    val expanded: (Boolean) -> Unit = { layers.expanded(name, it) }
    if (name.contains("Icon")) {
        ChromeIconDropdownMenu(
            entry = entry, title = if (cascading) "示例级联菜单" else "示例操作菜单",
            enabled = enabled, onExpandedChange = expanded,
            onDismissFinished = { layers.finished(name) },
        ) {
            Icon(MiuixIcons.More, if (cascading) "打开级联图标菜单" else "打开图标菜单")
        }
    } else {
        ChromeDropdownMenu(
            entry = entry, title = "示例操作菜单", enabled = enabled, onExpandedChange = expanded,
            onDismissFinished = { layers.finished(name) },
        )
    }
    Text("已选：$selected")
}

private fun catalogCascadingEntries(selected: String, select: (String) -> Unit): List<DropdownEntry> = listOf(
    DropdownEntry(
        listOf(
            DropdownItem(
                "照片分类",
                children = CatalogOptions.map { label ->
                    DropdownItem(label, selected = selected == label, onClick = { select(label) })
                },
            ),
            DropdownItem(
                "排列方式",
                children = listOf("按名称", "按时间").map { label ->
                    DropdownItem(label, selected = selected == label, onClick = { select(label) })
                },
            ),
        ),
    ),
)

@Composable
private fun CatalogBottomSheet(name: String, layers: CatalogLayers) {
    var value by rememberSaveable { mutableFloatStateOf(0.5f) }
    var label by rememberSaveable { mutableStateOf("示例面板") }
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("这是可以编辑的预览面板")
            HalfWidthTextInput {
                TextField(label, { label = it }, label = "预览名称", singleLine = true)
            }
            SliderPreference(value, { value = it }, title = "预览强度", valueText = "${(value * 100).toInt()}%")
            Button(onClick = { layers.close(name) }, modifier = Modifier.fillMaxWidth()) { Text("完成预览") }
        }
    }
    Button(onClick = { layers.open(name) }, enabled = layers.available(name)) { Text("打开底部面板") }
    Text("$label · ${(value * 100).toInt()}%")
    ChromeBottomSheet(
        show = layers.shown(name), title = "示例面板", onDismissRequest = { layers.close(name) },
        onDismissFinished = { layers.finished(name) }, content = content,
    )
}

@Composable
private fun CatalogDialog(name: String, layers: CatalogLayers) {
    var value by rememberSaveable { mutableStateOf("示例名称") }
    var saved by rememberSaveable { mutableStateOf("尚未确认") }
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HalfWidthTextInput {
                TextField(value, { value = it }, label = "示例名称", singleLine = true)
            }
            // Vertical actions keep both actions reachable at a large system font scale.
            Button(onClick = { saved = value; layers.close(name) }, modifier = Modifier.fillMaxWidth()) { Text("确认") }
            Button(onClick = { layers.close(name) }, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
    }
    Button(onClick = { layers.open(name) }, enabled = layers.available(name)) { Text("打开对话框") }
    Text("结果：$saved")
    ChromeDialog(
        show = layers.shown(name), title = "示例对话框", summary = "输入后确认，或取消关闭。",
        onDismissRequest = { layers.close(name) }, onDismissFinished = { layers.finished(name) }, content = content,
    )
}

private fun catalogHex(argb: Int): String = "#" + argb.toUInt().toString(16).uppercase().padStart(8, '0')
