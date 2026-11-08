package dev.starmark.starmark.ui

import dev.starmark.starmark.ui.g2.SliderPreference

import dev.starmark.starmark.ui.g2.Slider

import dev.starmark.starmark.ui.g2.SwitchPreference

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.UUID
import kotlin.math.roundToInt

@Composable
internal fun EditorPanel(id: String, state: Map<*, *>, command: UiCommand, openPanel: (String) -> Unit, closePanel: () -> Unit) {
    if (state.obj("staruw").flag("enabled") && id !in setOf("styles", "history")) {
        StaruwControlsPanel(state, command)
        return
    }
    when (id) {
        "styles" -> StylesPanel(state, command, closePanel)
        "content" -> ContentPanel(state, command, openPanel, closePanel)
        "fonts" -> ImportedFontPicker(state, command)
        "position" -> PositionPanel(state, command, openPanel, closePanel)
        "border" -> if (state.obj("native").flag("enabled")) NativeLayoutPanel(state, command) else BorderPanel(state, command, closePanel)
        "color" -> if (!state.obj("native").flag("enabled") || state.obj("native").flag("supportsColor")) ColorPanel(state, command, closePanel)
        "models" -> ModelPanel(state, command, closePanel)
        "history" -> HistoryPanel(state, command, closePanel)
    }
}

internal fun Map<*, *>.editable(): Boolean = !flag("busy") && !obj("batch").flag("running")

internal fun nativeStyleChoicesAvailable(native: Map<*, *>, glassSupported: Boolean): Boolean =
    native.flag("canChooseStyle") && (native.rows("backgrounds").count { !it.flag("glass") || glassSupported } > 1 ||
        native.rows("patterns").any { it.rows("options").size > 1 })

/** Persist pending input only while its source document field is unchanged.
 * rememberSaveable inputs alone cannot validate state restored after a route leaves. */
@Composable
internal fun <T : Any> rememberDocumentField(source: T): MutableState<T> = key("bound-document-field-v1") {
    @Suppress("UNCHECKED_CAST")
    val saver = listSaver<MutableState<T>, Any>(
        save = { listOf(source, it.value) },
        restore = { saved -> mutableStateOf(if (saved.size == 2 && saved[0] == source) saved[1] as T else source) },
    )
    rememberSaveable(source, saver = saver) { mutableStateOf(source) }
}

@Composable
private fun StylesPanel(state: Map<*, *>, command: UiCommand, close: () -> Unit) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChromeSearchField(search, { search = it }, "搜索样式", "editor.styles.search")
        TabRow(listOf("全部", "原厂", "叠加", "边框", "自定义"), filter, hapticSelection(filter) { filter = it }, Modifier.testTag("editor.styles.filter"))
        val rows = state.rows("styles").filter { row ->
            row.text("title").contains(search, true) && when (filter) {
                1 -> row.flag("native")
                2 -> row.text("mode") == "overlay"
                3 -> row.text("mode") == "frame"
                4 -> row.flag("custom")
                else -> true
            }
        }
        rows.forEach { style -> StyleCard(style, !state.editable(), command, close) }
        if (rows.isEmpty()) SupportingText("没有匹配的样式")
        TextButton("导入完整 ZIP", hapticClick { command.send("style.import") }, enabled = state.editable(), modifier = Modifier.fillMaxWidth().testTag("editor.style.import"))
    }
}

@Composable
internal fun ContentPanel(state: Map<*, *>, command: UiCommand, open: (String) -> Unit, close: () -> Unit) {
    val doc = state.obj("document")
    val native = state.obj("native")
    val isNative = native.flag("enabled")
    val options = doc.obj("xiaomiOptions")
    val exif = options.obj("exifOverrides")
    val metadata = state.obj("photo").obj("metadata")
    fun originalValue(key: String) = metadata[key]?.toString()?.removeSuffix(".0") ?: ""
    val exposure = metadata.number("exposure")
    val shutter = if (exposure <= 0f) "" else if (exposure < 1f) "1/${(1f / exposure).roundToInt()}" else "$exposure"
    val enabled = state.editable()
    val template = state.obj("template").flag("active")
    fun supports(field: String) = !isNative || native.flag(field)
    val editsModel = !template && supports("supportsModel")
    val editsParameters = !template && supports("supportsParameters")
    val editsCaption = supports("supportsCaption")
    val editsText = editsCaption || editsModel || editsParameters
    var caption by rememberDocumentField(doc.text("caption"))
    var model by rememberDocumentField(doc.text("modelOverride"))
    var focal by rememberDocumentField(exif.text("focal"))
    var aperture by rememberDocumentField(exif.text("aperture"))
    var speed by rememberDocumentField(exif.text("speed"))
    var iso by rememberDocumentField(exif.text("iso"))
    val regions = listOf("model", "parameters").filter { state.obj("regions").flag(it) }
    val mixFields = if (isNative) native.rows("mixFields") else emptyList()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(EditorSettingsDefaults.GroupSpacing)) {
        if ("model" in regions) EditorSettingsCard(Modifier.testTag("content.regions")) {
            val replacements = doc.obj("regionReplacements")
            ModelReplacementControls(replacements, enabled, command)
        }
        if ("parameters" in regions) Column {
            SupportingText("参数图标", Modifier.padding(start = EditorSettingsDefaults.RowInset, bottom = 8.dp))
            ParameterIconRegionCard(state, command)
        }
        if (isNative && !template && nativeStyleChoicesAvailable(native, (LocalStarMarkEffects.current.systemGlassSupported || PortablePhotoGlass.supported()))) {
            NativeLayoutPanel(state, command, showLayouts = false)
        }
        if (mixFields.isNotEmpty()) EditorSettingsCard {
            mixFields.forEach { field ->
                val id = field.text("id")
                key(doc.text("packageId"), id) {
                    val choices = field.rows("types")
                    var text by rememberDocumentField(field.text("text"))
                    ChromeDropdownPreference(
                        title = field.text("label"), items = choices.map { it.text("label") },
                        selectedIndex = choices.indexOfFirst { it.text("value") == field.text("type") }.coerceAtLeast(0),
                        modifier = Modifier.testTag("content.mix.$id"), enabled = enabled, insideMargin = EditorSettingsDefaults.RowPadding,
                        onSelectedIndexChange = { index ->
                            command.patch("切换原厂内容", "xiaomiOptions" to mapOf("mix" to mapOf(id to mapOf("type" to choices[index].text("value")))))
                        },
                    )
                    if (field.text("type") == "custom_text") {
                        PanelTextField(text, { text = it }, "自定义文字", "content.mixText.$id", enabled)
                        MainButton("应用文字", "content.mixApply.$id", enabled, insetFormAction) {
                            command.patch("修改原厂文字", "xiaomiOptions" to mapOf("mix" to mapOf(id to mapOf("type" to "custom_text", "text" to text))))
                        }
                    }
                }
            }
        }
        if (isNative && listOf("supportsCaption", "supportsModel", "supportsParameters", "supportsParametersToggle", "supportsModelToggle", "supportsBrandToggle").none { native.flag(it) }) {
            SupportingText("此水印使用照片中的机型与拍摄信息", Modifier.padding(horizontal = EditorSettingsDefaults.RowInset))
        }
        if (editsParameters) {
            val fields = native.obj("parameterFields")
            ParameterFieldGrid { cell ->
                PanelTextField(
                    focal, { focal = it.take(32) }, "焦距", "content.focal", enabled && (!isNative || fields.flag("focal")), cell,
                    placeholder = state.obj("photo").text("displayFocal", originalValue("focal")).removeSuffix(".0"), compact = true,
                )
                PanelTextField(
                    aperture, { aperture = it.take(32) }, "光圈", "content.aperture", enabled && (!isNative || fields.flag("aperture")), cell,
                    placeholder = originalValue("aperture"), compact = true,
                )
                PanelTextField(
                    speed, { speed = it.take(32) }, "快门", "content.speed", enabled && (!isNative || fields.flag("speed")), cell,
                    placeholder = shutter, compact = true,
                )
                PanelTextField(
                    iso, { iso = it.take(32) }, "ISO", "content.iso", enabled && (!isNative || fields.flag("iso")), cell,
                    placeholder = originalValue("iso"), compact = true,
                )
            }
        }
        val hasModelPicker = supports("supportsModel")
        val hasToggles = listOf("supportsParametersToggle", "supportsModelToggle", "supportsBrandToggle").any(::supports)
        if (hasModelPicker || editsCaption || hasToggles) EditorSettingsCard {
            if (editsModel) {
                PanelTextField(model, { model = it.take(100) }, "型号", "content.model", enabled, placeholder = metadata.text("model"))
            }
            if (editsCaption) PanelTextField(caption, { caption = it }, "签名", "content.caption", enabled)
            if (supports("supportsParametersToggle")) SwitchPreference(
                doc.flag("showParameters", true), { command.patch("参数显示", "showParameters" to it) }, "显示拍摄参数",
                enabled = enabled, insideMargin = EditorSettingsDefaults.RowPadding, modifier = Modifier.testTag("content.showParameters"),
            )
            if (supports("supportsModelToggle")) SwitchPreference(
                doc.flag("showModel", true), { command.patch("型号显示", "showModel" to it) }, "显示型号",
                enabled = enabled, insideMargin = EditorSettingsDefaults.RowPadding, modifier = Modifier.testTag("content.showModel"),
            )
            if (supports("supportsBrandToggle")) SwitchPreference(
                doc.flag("showBrand", true), { command.patch("品牌显示", "showBrand" to it) }, "显示品牌图标",
                enabled = enabled, insideMargin = EditorSettingsDefaults.RowPadding, modifier = Modifier.testTag("content.showBrand"),
            )
            if (hasModelPicker) ArrowPreference(
                "预设型号", modifier = Modifier.testTag("content.models"), enabled = enabled,
                insideMargin = EditorSettingsDefaults.RowPadding, onClick = hapticClick { open("models") },
                endActions = {
                    Text((state.rows("models").firstOrNull { it.text("id") == doc.text("modelId") }?.text("title") ?: doc.text("modelOverride")).ifEmpty { "按照片自动匹配" }, style = MiuixTheme.textStyles.body2,
                        color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantActions else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                        textAlign = TextAlign.End)
                },
            )
        }
        if (editsText) MainButton("应用内容", "content.apply", enabled, Modifier.heightIn(min = EditorSettingsDefaults.TouchTarget)) {
            val patch = mutableMapOf<String, Any?>()
            if (editsCaption) patch["caption"] = caption
            if (editsModel) patch["modelOverride"] = model
            if (editsParameters) {
                patch["xiaomiOptions"] = mapOf("exifOverrides" to mapOf("focal" to focal.trim(), "aperture" to aperture.trim(), "speed" to speed.trim(), "iso" to iso.trim()))
            }
            command.send("document.apply", "patch" to patch, "label" to "修改水印内容")
            if (!isNative || !editsCaption) close()
        }
    }
}

private val insetFormAction = Modifier
    .padding(start = EditorSettingsDefaults.RowInset, end = EditorSettingsDefaults.RowInset, top = 4.dp, bottom = 12.dp)
    .heightIn(min = EditorSettingsDefaults.TouchTarget)

@Composable
private fun ModelReplacementControls(replacements: Map<*, *>, enabled: Boolean, command: UiCommand) {
    val region = "model"
    val design = replacements.obj(region)
    val source = design.text("source", "original")
    val sources = listOf("original", "text", "image")
    val labels = listOf("原始型号", "文字签名", "图片签名")
    fun update(field: String, value: String) {
        val updated = design.stringKeys() + (field to value)
        command.patch("区域替换", "regionReplacements" to (replacements.stringKeys() + (region to updated)))
    }
    fun importImage() = command.send("region.import", "region" to region)
    ChromeDropdownPreference(
        labels, sources.indexOf(source).coerceAtLeast(0), "型号区域",
        modifier = Modifier.testTag("region.$region.source"), enabled = enabled, insideMargin = EditorSettingsDefaults.RowPadding,
        onSelectedIndexChange = { index ->
            val next = sources[index]
            if (next == "image" && design.text("asset").isEmpty()) importImage() else update("source", next)
        },
    )
    if (source == "text") {
        var signature by rememberDocumentField(design.text("text"))
        PanelTextField(signature, { signature = it.take(2000) }, "文字签名", "region.model.text", enabled)
        MainButton("应用签名", "region.model.apply", enabled, insetFormAction) { update("text", signature) }
    }
    if (source == "image") {
        ArrowPreference("上传图片", modifier = Modifier.testTag("region.$region.upload"), enabled = enabled,
            insideMargin = EditorSettingsDefaults.RowPadding, onClick = hapticClick(onClick = ::importImage))
    }
    if (source != "original") BasicComponent(
        title = "恢复原始内容", modifier = Modifier.testTag("region.$region.reset"), enabled = enabled,
        insideMargin = EditorSettingsDefaults.RowPadding,
        onClick = hapticClick { command.patch("重置区域", "regionReplacements" to (replacements.stringKeys() - region)) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParameterFieldGrid(content: @Composable (Modifier) -> Unit) {
    EditorSettingsCard(insideMargin = PaddingValues(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val minCellWidth = maxOf(EditorSettingsDefaults.TouchTarget, 60.dp * density.fontScale)
            val gap = 6.dp
            val columns = when {
                maxWidth >= minCellWidth * 4 + gap * 3 -> 4
                maxWidth >= minCellWidth * 2 + gap -> 2
                else -> 1
            }
            val cellWidth = with(density) { ((constraints.maxWidth - gap.roundToPx() * (columns - 1)) / columns).toDp() }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                content(Modifier.width(cellWidth))
            }
        }
    }
}

/** Keep the name in view when a field is empty or has keyboard focus. */
@Composable
internal fun PanelTextField(
    value: String, onValueChange: (String) -> Unit, title: String, tag: String, enabled: Boolean,
    modifier: Modifier = Modifier, placeholder: String = "", compact: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme
    val textColor = if (enabled) colors.onSurface else colors.disabledOnSecondaryVariant
    val shape = MiuixCornerShape(10.dp)
    val fieldSurface = Modifier.background(colors.surfaceContainerHigh, shape)
        .border(1.dp, if (focused) colors.primary else Color.Transparent, shape)
    val alignment = if (compact) TextAlign.Start else TextAlign.End
    val style = if (compact) MiuixTheme.textStyles.body1 else MiuixTheme.textStyles.body2
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = style.copy(color = textColor, textAlign = alignment),
        cursorBrush = SolidColor(colors.primary),
        modifier = modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = title }.onFocusChanged { focused = it.isFocused },
        decorationBox = { input ->
            val entry: @Composable () -> Unit = {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(
                    placeholder, style = style, color = colors.onSurfaceVariantSummary,
                    textAlign = alignment, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                input()
            }
            if (compact) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = EditorSettingsDefaults.RowMinHeight)
                        .then(fieldSurface).padding(8.dp), verticalArrangement = Arrangement.Center,
                ) {
                    Text(title, style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
                    Box(Modifier.fillMaxWidth()) { entry() }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = EditorSettingsDefaults.RowMinHeight)
                        .padding(horizontal = EditorSettingsDefaults.RowInset, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(title, style = MiuixTheme.textStyles.headline1, fontWeight = FontWeight.Medium, color = textColor)
                    Box(
                        Modifier.weight(1f).heightIn(min = 36.dp).then(fieldSurface).padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) { entry() }
                }
            }
        },
    )
}

@Composable
private fun ModelPanel(state: Map<*, *>, command: UiCommand, close: () -> Unit) {
    val current = state.obj("document").text("modelId")
    var query by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChromeSearchField(query, { query = it }, "搜索型号", "model.search", enabled = state.editable())
        Card(Modifier.fillMaxWidth(), cornerRadius = if (state.obj("template").flag("active")) 20.dp else 24.dp) {
            RadioButtonPreference("按照片自动匹配", current.isEmpty(), { command.send("model.choose", "id" to ""); close() }, enabled = state.editable(), modifier = Modifier.testTag("model.auto"))
            state.rows("models").filter { it.text("title").contains(query, true) }.forEach { row ->
                RadioButtonPreference(row.text("title"), current == row.text("id"), { command.send("model.choose", "id" to row.text("id")); close() }, enabled = state.editable(), modifier = Modifier.testTag("model.${row.text("id")}"))
            }
        }
    }
}

@Composable
internal fun PositionPanel(state: Map<*, *>, command: UiCommand, open: (String) -> Unit, close: () -> Unit) {
    val doc = state.obj("document")
    val native = state.obj("native")
    val isNative = native.flag("enabled")
    val options = doc.obj("xiaomiOptions")
    val needsBlur = if (isNative) native.flag("needsBlur") else doc.text("mode") == "frame" && doc.text("background").endsWith("Blur")
    val hasLayouts = native.rows("layouts").size > 1
    val supportsScale = !isNative || native.flag("supportsScale")
    val hasDetails = isNative && (native.flag("supportsLocation") || native.flag("supportsTime"))
    var index by rememberSaveable { mutableIntStateOf(0) }
    if (isNative && !hasLayouts && !supportsScale && !hasDetails) {
        if (native.rows("backgrounds").size <= 1 && native.rows("patterns").isEmpty()) {
            SupportingText("此水印使用固定构图", Modifier.padding(horizontal = EditorSettingsDefaults.RowInset))
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(EditorSettingsDefaults.GroupSpacing)) {
        if (!isNative || hasLayouts) EditorSettingsSection("布局类型") {
            EditorSettingsCard(insideMargin = EditorSettingsDefaults.RowPadding) {
                if (isNative) NativeAlignmentControls(state, command)
                else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf("vertical", "horizontal").forEach { axis ->
                        val values = listOf("start", "center", "end")
                        AlignmentSegments(axis,
                            if (axis == "vertical") listOf("顶部", "垂直居中", "底部") else listOf("左对齐", "水平居中", "右对齐"),
                            values.indexOf(native.text(if (axis == "vertical") "alignmentVertical" else "alignmentHorizontal")),
                            state.editable(), Modifier.width(156.dp),
                        ) { selection -> command.send("position.align", "axis" to axis, "value" to values[selection]) }
                    }
                }
            }
        }
        if (supportsScale) EditorSettingsSection("内容大小") {
            EditorSettingsCard(insideMargin = EditorSettingsDefaults.RowPadding) {
                if (state.obj("template").flag("active")) TemplateSizeSlider(state, command)
                else WatermarkSizePresets(if (isNative) native.number("scale", 1f) else doc.number("scale", 1f), state.editable()) { preset ->
                    command.send("interaction.cancel")
                    command.patch("水印内容大小", "scale" to preset.scale)
                }
            }
        }
        if (hasDetails) EditorSettingsCard {
            if (native.flag("supportsLocation")) {
                val locationTitle = if (native.text("locationKind") == "address") "地址" else "经纬度"
                val reason = native.text("locationUnavailableReason")
                SwitchPreference(options.flag("showLocation", true), {
                    command.patch("${locationTitle}显示", "xiaomiOptions" to mapOf("showLocation" to it))
                }, locationTitle, enabled = state.editable() && native.flag("locationAvailable"),
                    insideMargin = EditorSettingsDefaults.RowPadding,
                    modifier = Modifier.testTag("position.showLocation").semantics { if (reason.isNotEmpty()) stateDescription = reason })
            }
            if (native.flag("supportsTime")) SwitchPreference(options.flag("showTime", true), {
                command.patch("时间显示", "xiaomiOptions" to mapOf("showTime" to it))
            }, "时间", enabled = state.editable(), insideMargin = EditorSettingsDefaults.RowPadding, modifier = Modifier.testTag("position.showTime"))
        }
        val controls = if (isNative) emptyList() else listOf("x" to "水平", "y" to "垂直", "opacity" to "透明度") +
            if (needsBlur) listOf("blur" to "模糊强度", "tint" to "背景明暗") else emptyList()
        if (controls.isNotEmpty()) EditorSettingsCard(insideMargin = EditorSettingsDefaults.RowPadding) {
            val selectedIndex = index.coerceIn(controls.indices)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("position.control"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                controls.forEachIndexed { itemIndex, row ->
                    Surface(onClick = hapticClick(HapticFeedbackType.SegmentTick, feedbackEnabled = itemIndex != selectedIndex) { command.send("interaction.cancel"); index = itemIndex }, enabled = state.editable(),
                        modifier = Modifier.testTag("position.select.${row.first}"), shape = MiuixCornerShape(12.dp),
                        color = if (itemIndex == selectedIndex) MiuixTheme.colorScheme.primary.copy(alpha = .15f) else Color.Transparent,
                        contentColor = if (itemIndex == selectedIndex) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface) {
                        Box(Modifier.sizeIn(minWidth = EditorSettingsDefaults.TouchTarget, minHeight = EditorSettingsDefaults.TouchTarget).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                            Text(row.second, fontSize = 14.sp, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TransactionalAdjustment(state, command, controls[selectedIndex].first, Modifier.fillMaxWidth())
        }
        if (!isNative) BorderPanel(state, command, close)
        if (!isNative) EditorSettingsCard {
            ArrowPreference("文字颜色", onClick = hapticClick { open("color") }, enabled = state.editable(), insideMargin = EditorSettingsDefaults.RowPadding, modifier = Modifier.testTag("position.color"))
            ArrowPreference("复位位置与大小", onClick = hapticClick { command.patch("复位水印", "x" to .5, "y" to .94, "scale" to 1.0) }, enabled = state.editable(), insideMargin = EditorSettingsDefaults.RowPadding, modifier = Modifier.testTag("position.reset"))
        }
    }
}

@Composable
private fun WatermarkSizePresets(scale: Float, enabled: Boolean, onSelected: (WatermarkSizePreset) -> Unit) {
    val selected = watermarkSizePreset(scale)
    Row(Modifier.fillMaxWidth().testTag("position.scale"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WatermarkSizePreset.entries.forEach { preset ->
            val active = selected == preset
            Surface(
                onClick = hapticClick(HapticFeedbackType.SegmentTick, feedbackEnabled = !active) { onSelected(preset) }, enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = EditorSettingsDefaults.TouchTarget).testTag("position.scale.${preset.name.lowercase()}")
                    .semantics { this.selected = active },
                shape = MiuixCornerShape(12.dp),
                color = if (active) MiuixTheme.colorScheme.primary.copy(alpha = .15f) else MiuixTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(preset.title, fontSize = 16.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Uses the same begin/update/end protocol as the original position panel. */
@Composable
private fun TransactionalAdjustment(state: Map<*, *>, command: UiCommand, control: String, modifier: Modifier = Modifier) {
    val doc = state.obj("document")
    val range = when (control) { "scale" -> .3f..2.5f; "blur" -> 0f..0.12f; else -> 0f..1f }
    val default = when (control) { "scale", "opacity" -> 1f; "x" -> .5f; "y" -> .94f; "blur" -> .035f; else -> .45f }
    val external = doc.number(control, default).coerceIn(range)
    val session = remember(control) { AdjustmentSession() }
    var value by remember(control) { mutableFloatStateOf(external) }
    val revision = state.longNumber("revision")
    val currentCommand by rememberUpdatedState(command)
    LaunchedEffect(external, revision) {
        session.invalidateRevision(revision)
        if (session.id == null) value = external
    }
    fun event(phase: String, newValue: Float) {
        currentCommand.send("adjust", "control" to control, "phase" to phase, "value" to newValue.toDouble(), "interactionId" to session.id, "revision" to session.revision)
    }
    DisposableEffect(control) {
        onDispose {
            if (session.id != null) event("cancel", session.latest)
            session.finish()
        }
    }
    Slider(
        value = value, valueRange = range, height = 28.dp, enabled = state.editable(), modifier = modifier.testTag("adjust.$control"),
        onValueChange = { next ->
            if (session.invalidateRevision(revision)) value = external
            if (!session.blockedUntilFinish) {
                if (session.id == null && session.begin(UUID.randomUUID().toString(), revision, value)) event("begin", value)
                value = next; session.update(next); event("update", next)
            }
        },
        onValueChangeFinished = { if (session.id != null) event("end", session.latest); session.finish() },
    )
}

@Composable
private fun NativeAlignmentControls(state: Map<*, *>, command: UiCommand) {
    val haptics = LocalHapticFeedback.current
    val native = state.obj("native")
    val layouts = native.rows("layouts")
    val choices = layouts.map { FactoryLayoutOption(it.integer("value"), it.text("layout")) }
    val selected = choices.firstOrNull { it.group == native.integer("groupIndex") }
    val axes = factoryLayoutAxes(choices, selected)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("native.layout"), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        axes.forEach { axis ->
            Row(Modifier.clip(MiuixCornerShape(8.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                axis.choices.forEach { choice ->
                    val active = axis.selected == choice.value
                    val label = layouts.firstOrNull { it.integer("value") == choice.destination.group && it.text("layout") == choice.destination.name }?.text("label") ?: choice.value
                    val icon = when (choice.value) {
                        "top" -> ReferenceIcons.AlignTop
                        "bottom" -> ReferenceIcons.AlignBottom
                        "center" -> ReferenceIcons.AlignVerticalCenter
                        "left", "inner_border_left", "outer_border_left" -> ReferenceIcons.AlignLeft
                        "right", "inner_border_right", "outer_border_right" -> ReferenceIcons.AlignRight
                        else -> ReferenceIcons.AlignCenter
                    }
                    Box(
                        Modifier.size(52.dp, EditorSettingsDefaults.TouchTarget).testTag("native.layout.${axis.name}.${choice.value}")
                            .semantics { contentDescription = label }
                            .selectable(selected = active, enabled = state.editable(), role = Role.RadioButton) {
                                if (!active) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                command.patch("切换原厂布局", "xiaomiOptions" to mapOf("layoutGroup" to choice.destination.group, "layout" to choice.destination.name))
                            }, contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(52.dp, 42.dp).background(if (active) MiuixTheme.colorScheme.primary.copy(alpha = .30f) else MiuixTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                            Icon(icon, null, Modifier.size(20.dp), tint = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlignmentSegments(axis: String, labels: List<String>, selectedIndex: Int, enabled: Boolean, modifier: Modifier = Modifier, onSelected: (Int) -> Unit) {
    Row(modifier.clip(MiuixCornerShape(12.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index == selectedIndex
            val color = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
            Surface(
                onClick = hapticClick(HapticFeedbackType.SegmentTick, feedbackEnabled = !active) { onSelected(index) }, enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = EditorSettingsDefaults.TouchTarget).testTag("position.align.$axis.$index")
                    .semantics { contentDescription = label; selected = active },
                color = if (active) MiuixTheme.colorScheme.primary.copy(alpha = .23f) else MiuixTheme.colorScheme.surfaceContainerHigh,
                shape = androidx.compose.ui.graphics.RectangleShape,
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val icons = if (axis == "vertical") listOf(ReferenceIcons.AlignTop, ReferenceIcons.AlignVerticalCenter, ReferenceIcons.AlignBottom) else listOf(ReferenceIcons.AlignLeft, ReferenceIcons.AlignCenter, ReferenceIcons.AlignRight)
                    Icon(icons[index], label, Modifier.size(20.dp), tint = color)
                }
            }
        }
    }
}

@Composable
private fun BorderPanel(state: Map<*, *>, command: UiCommand, close: () -> Unit) {
    val doc = state.obj("document")
    val initial = doc.obj("border")
    var mode by rememberDocumentField(doc.text("mode", "overlay"))
    var top by rememberDocumentField(initial.number("top"))
    var bottom by rememberDocumentField(initial.number("bottom", .12f))
    var left by rememberDocumentField(initial.number("left"))
    var right by rememberDocumentField(initial.number("right"))
    var background by rememberDocumentField(doc.text("background", "white"))
    val glassEnabled = state.obj("settings").flag("glassWatermark") && (LocalStarMarkEffects.current.systemGlassSupported || PortablePhotoGlass.supported())
    val backgrounds = listOf("white", "black", "darkBlur", "lightBlur") + if (glassEnabled) listOf("darkGlass", "lightGlass") else emptyList()
    val backgroundLabels = listOf("纯白", "纯黑", "暗色模糊", "亮色模糊") + if (glassEnabled) listOf("黑透玻璃", "白透玻璃") else emptyList()
    val enabled = state.editable()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TabRow(listOf("直接叠加", "外围扩边"), if (mode == "frame") 1 else 0, hapticSelection(if (mode == "frame") 1 else 0) { mode = if (it == 1) "frame" else "overlay" }, Modifier.testTag("border.mode"))
        if (mode == "frame") {
            EditorSettingsCard {
                BorderSlider("上边框", "top", top, { top = it }, enabled)
                BorderSlider("下边框", "bottom", bottom, { bottom = it }, enabled)
                BorderSlider("左边框", "left", left, { left = it }, enabled)
                BorderSlider("右边框", "right", right, { right = it }, enabled)
                ChromeDropdownPreference(backgroundLabels, backgrounds.indexOf(background).coerceAtLeast(0), "边框背景", enabled = enabled, insideMargin = EditorSettingsDefaults.RowPadding, onSelectedIndexChange = { background = backgrounds[it] }, modifier = Modifier.testTag("border.background"))
            }
        }
        val photo = state.obj("photo")
        val w = photo.integer("width")
        val h = photo.integer("height")
        val short = minOf(w, h)
        val outputWidth = w + if (mode == "frame") (left * short).roundToInt() + (right * short).roundToInt() else 0
        val outputHeight = h + if (mode == "frame") (top * short).roundToInt() + (bottom * short).roundToInt() else 0
        val withinBudget = dev.starmark.starmark.TiledPhotoExporter.validSize(outputWidth,outputHeight)
        if (!withinBudget) Text("请减小边框", color = MiuixTheme.colorScheme.primary)
        MainButton("应用边框", "border.apply", enabled && withinBudget) {
            command.patch("调整边框与背景", "mode" to mode, "background" to background, "border" to mapOf("top" to top.toDouble(), "bottom" to bottom.toDouble(), "left" to left.toDouble(), "right" to right.toDouble()), "textColor" to borderTextColor(doc.longNumber("textColor", 0xffffffffL), doc.text("background", "white"), background))
            close()
        }
    }
}

@Composable
private fun BorderSlider(title: String, key: String, value: Float, onChange: (Float) -> Unit, enabled: Boolean) {
    SliderPreference(value, onChange, title = title, valueText = "${(value * 100).roundToInt()}%", valueRange = 0f..0.3f, enabled = enabled, modifier = Modifier.testTag("border.$key"))
}

@Composable
internal fun NativeLayoutPanel(state: Map<*, *>, command: UiCommand, showLayouts: Boolean = true) {
    val native = state.obj("native")
    val layouts = native.rows("layouts")
    val backgrounds = native.rows("backgrounds").filter { !it.flag("glass") || (LocalStarMarkEffects.current.systemGlassSupported || PortablePhotoGlass.supported()) }
    val patterns = native.rows("patterns").filter { it.rows("options").size > 1 }
    val hasLayouts = showLayouts && layouts.size > 1
    if (!hasLayouts && patterns.isEmpty() && backgrounds.size <= 1) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(EditorSettingsDefaults.GroupSpacing)) {
        if (hasLayouts) EditorSettingsSection("布局类型") {
            EditorSettingsCard(insideMargin = EditorSettingsDefaults.RowPadding) { NativeAlignmentControls(state, command) }
        }
        patterns.forEach { setting ->
            val choices = setting.rows("options")
            NativeOptionChoices(
                setting.text("label", "图案"), choices, state.editable(), "native.pattern.${setting.text("id")}",
                selected = { it.text("value") == setting.text("selected") },
            ) { choice ->
                command.patch("切换原厂图形", "xiaomiOptions" to mapOf("customizeSettings" to mapOf(setting.text("id") to choice.text("value"))))
            }
        }
        if (backgrounds.size > 1) NativeOptionChoices(
            "款式", backgrounds, state.editable(), "native.background",
            selected = { it.text("value") == native.text("backgroundName") && it.flag("glass") == native.flag("glassBackground") },
        ) { choice ->
            // One OEM background can still have a glass experiment variant.
            // Keep that fixed background locked; only the material flag changes.
            val patch = mutableMapOf<String, Any>()
            if (backgrounds.map { it.text("value") }.distinct().size > 1) patch["background"] = choice.text("value")
            if (native.flag("supportsGlassBackground")) patch["glassBackground"] = choice.flag("glass")
            if (patch.isNotEmpty()) command.patch("切换原厂款式", "xiaomiOptions" to patch)
        }
    }
}

/** Original package icons and literal colors make every declared variant visible. */
@Composable
private fun NativeOptionChoices(
    title: String, choices: List<Map<*, *>>, enabled: Boolean, tag: String,
    selected: (Map<*, *>) -> Boolean, onSelected: (Map<*, *>) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val dark = colors.background.luminance() < .5f
    val scroll = key(choices.map { it.text("value") + it.flag("glass") }) { rememberScrollState() }
    EditorSettingsSection(title) {
        EditorSettingsCard(insideMargin = PaddingValues(vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().testTag(tag).horizontalScroll(scroll).selectableGroup()
                    .padding(horizontal = EditorSettingsDefaults.RowInset),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                choices.forEach { choice ->
                    val active = selected(choice)
                    val label = choice.text("label")
                    val value = choice.text("value") + if (choice.flag("glass")) ".glass" else ""
                    val image = if (dark) choice.text("darkImagePath", choice.text("imagePath")) else choice.text("imagePath")
                    Box(
                        Modifier.size(56.dp).clip(CircleShape)
                            .selectable(selected = active, enabled = enabled, role = Role.RadioButton,
                                onClick = hapticClick(HapticFeedbackType.SegmentTick, feedbackEnabled = !active) { onSelected(choice) })
                            .semantics { contentDescription = label }
                            .testTag("$tag.choice.$value"),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (active) Box(Modifier.size(54.dp).border(2.dp, colors.primary, CircleShape))
                        val previewModifier = Modifier.size(42.dp).clip(CircleShape)
                        // Preview-only contrast: preserve the package color sent to
                        // the renderer while keeping white visible on a white card.
                        val swatchColor = Color(choice.longNumber("color").toInt()).let {
                            if (!dark && it == Color.White) Color(0xfff6f6f6) else it
                        }
                        if (choice.flag("useColorSwatch") && choice["color"] is Number) {
                            Box(previewModifier.background(swatchColor).testTag("$tag.swatch.$value"))
                        } else if (image.isNotEmpty()) {
                            PhotoThumbnail(image, previewModifier, contentScale = ContentScale.Fit, cornerRadius = 21.dp)
                        } else if (choice.text("blurTone").isNotEmpty()) {
                            val lightMaterial = choice.text("blurTone") == "light"
                            Box(previewModifier.background(androidx.compose.ui.graphics.Brush.linearGradient(
                                if (lightMaterial) listOf(Color(0xffefc6d7), Color(0xffffe7b9), Color(0xffb5dce8))
                                else listOf(Color(0xff824b73), Color(0xffba8540), Color(0xff315e85)))))
                        } else if (choice["color"] is Number) {
                            Box(previewModifier.background(swatchColor))
                        } else {
                            // Text-backed OEM Leica marks have no bitmap. Keep their
                            // official name inside the same circular preview area.
                            Box(previewModifier.background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                                Text(label.removePrefix("LEICA ").replace(" ", "\n"),
                                    modifier = Modifier.padding(4.dp), textAlign = TextAlign.Center,
                                    fontSize = 8.sp, lineHeight = 9.sp, maxLines = 3,
                                    color = colors.onSurface, fontWeight = FontWeight.Medium)
                            }
                        }
                        // Draw on top of every image/swatch, inside its existing
                        // diameter; the separate selection-ring gap stays equal.
                        Box(Modifier.size(42.dp).border(1.dp,
                            if (dark) Color.White.copy(alpha = .18f) else Color.Black.copy(alpha = .16f), CircleShape))
                        if (choice.flag("glass")) {
                            // A reflection badge distinguishes the experimental
                            // material without altering the OEM artwork or ring gap.
                            Canvas(Modifier.offset(x = 8.dp, y = (-8).dp).size(14.dp)
                                .clip(CircleShape).background(colors.onSurface.copy(alpha = .82f))
                                .testTag("$tag.glass.$value")) {
                                val stroke = 1.2.dp.toPx()
                                drawLine(colors.surface, Offset(size.width * .25f, size.height * .60f), Offset(size.width * .60f, size.height * .25f), strokeWidth = stroke, cap = StrokeCap.Round)
                                drawLine(colors.surface, Offset(size.width * .40f, size.height * .75f), Offset(size.width * .75f, size.height * .40f), strokeWidth = stroke, cap = StrokeCap.Round)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorPanel(state: Map<*, *>, command: UiCommand, close: () -> Unit) {
    val doc = state.obj("document")
    val native = state.obj("native").flag("enabled")
    val options = doc.obj("xiaomiOptions")
    val initial = if (native) options.longNumber("textColor", doc.longNumber("textColor", 0xffffffffL)) else doc.longNumber("textColor", 0xffffffffL)
    var color by remember(initial) { mutableStateOf(Color(initial.toInt())) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TabRow(listOf("调色板", "精细选择"), tab, hapticSelection(tab) { tab = it }, Modifier.testTag("color.mode"))
        if (tab == 0) ColorPalette(color, hapticSelection(color) { color = it }, Modifier.fillMaxWidth().testTag("color.palette"))
        else ColorPicker(color, { color = it }, Modifier.fillMaxWidth().testTag("color.picker"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton("白色", hapticClick(HapticFeedbackType.SegmentTick, color != Color.White) { color = Color.White }, Modifier.weight(1f).testTag("color.white"))
            TextButton("深黑", hapticClick(HapticFeedbackType.SegmentTick, color != Color(0xff181818)) { color = Color(0xff181818) }, Modifier.weight(1f).testTag("color.black"))
        }
        SupportingText("#%08X".format(color.toArgb()))
        MainButton("应用文字颜色", "color.apply", state.editable()) {
            val argb = color.toArgb().toLong() and 0xffffffffL
            if (native) command.patch("文字颜色", "xiaomiOptions" to mapOf("textColor" to argb)) else command.patch("文字颜色", "textColor" to argb)
            close()
        }
    }
}

@Composable
private fun HistoryPanel(state: Map<*, *>, command: UiCommand, close: () -> Unit) {
    val history = state.obj("history")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth(), cornerRadius = if (state.obj("template").flag("active")) 20.dp else 24.dp) {
            history.rows("entries").reversed().forEach { row ->
                RadioButtonPreference(row.text("label", "编辑"), history.integer("cursor") == row.integer("index"), { command.send("history.jump", "index" to row.integer("index")); close() }, enabled = state.editable(), modifier = Modifier.testTag("history.entry.${row.integer("index")}"))
            }
        }
    }
}

