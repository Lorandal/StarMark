package dev.starmark.starmark.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.starmark.starmark.ui.g2.SwitchPreference
import top.yukonga.miuix.kmp.basic.TextButton
import kotlin.math.abs
import kotlin.math.round

/** Only author-declared controls cross the bridge; no generic scene patch UI. */
@Composable
internal fun StaruwControlsPanel(state: Map<*, *>, command: UiCommand) {
    val protocol = state.obj("staruw")
    val enabled = state.editable()
    val fonts = protocol.rows("fonts")
    val controls = protocol.rows("controls")
    Column(Modifier.fillMaxWidth().testTag("staruw.controls"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (protocol.flag("preserve")) {
            SupportingText("当前版本无法编辑此模板，可在水印库长按导出原包。")
        } else {
            if (protocol.obj("report").text("renderStatus") == "fallback") {
                SupportingText("正在使用模板提供的玻璃替代效果。原效果会随水印包保留。")
            }
            if (controls.isEmpty()) SupportingText("此模板没有可调整的项目，内容会按照片自动生成。")
            listOf("layout" to "构图", "design" to "水印样式", "photo" to "当前照片").forEach { (stage, title) ->
                val rows = controls.filter { it.text("stage", "design") == stage }
                if (rows.isNotEmpty()) EditorSettingsSection(title) {
                    rows.forEach { control ->
                        key(state.obj("document").text("packageId"), control.text("id")) {
                            StaruwControl(control, fonts, enabled, command)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StaruwControl(control: Map<*, *>, fonts: List<Map<*, *>>, enabled: Boolean, command: UiCommand) {
    val id = control.text("id")
    val title = control.text("label")
    val tag = "staruw.$id"
    if (control.flag("globalParameterIcon")) {
        top.yukonga.miuix.kmp.basic.BasicComponent(title = title,
            summary = "已由实验性功能中的“替换默认参数图标”统一设置",
            modifier = Modifier.testTag("$tag.globalDefault"), insideMargin = EditorSettingsDefaults.RowPadding)
        return
    }
    fun set(value: Any?) = command.send("staruw.set", "id" to id, "value" to value)
    when (control.text("type")) {
        "toggle" -> SwitchPreference(control["value"] == true, { set(it) }, title,
            enabled = enabled, modifier = Modifier.testTag(tag))
        "choice" -> {
            val choices = control.rows("options")
            ChromeDropdownPreference(title = title, items = choices.map { it.text("label") },
                selectedIndex = choices.indexOfFirst { it["id"] == control["value"] },
                enabled = enabled, modifier = Modifier.testTag(tag), insideMargin = EditorSettingsDefaults.RowPadding,
                onSelectedIndexChange = { set(choices[it]["id"]) })
        }
        "number", "text" -> {
            val numeric = control.text("type") == "number"
            val current = control["value"]?.toString()?.removeSuffix(if (numeric) ".0" else "") ?: ""
            var text by rememberDocumentField(current)
            val value = text.toDoubleOrNull()
            val minimum = (control["min"] as? Number)?.toDouble() ?: 0.0
            val maximum = (control["max"] as? Number)?.toDouble() ?: 0.0
            val step = (control["step"] as? Number)?.toDouble() ?: 1.0
            val valid = !numeric || value != null && value.isFinite() && value in minimum..maximum &&
                abs((value - minimum) / step - round((value - minimum) / step)) <= 1e-9
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelTextField(text, { text = it.take(65536) }, title, tag, enabled)
                if (numeric) SupportingText("范围 $minimum–$maximum，步长 $step")
                MainButton("应用", "$tag.apply", enabled && valid && text != current, Modifier.fillMaxWidth()) {
                    set(if (numeric) value else text)
                }
            }
        }
        "image", "region" -> {
            val model = control.text("type") == "region"
            val replacement = control.obj("value")
            val modes = if (model) (control["allowModes"] as? List<*>)?.filterIsInstance<String>().orEmpty() else listOf("native", "image")
            val mode = replacement.text("mode", "native")
            val labels = mapOf("native" to "原样", "text" to "文字签名", "image" to "自选图片")
            ChromeDropdownPreference(title = title, items = modes.map { labels[it] ?: it },
                selectedIndex = modes.indexOf(mode).coerceAtLeast(0), enabled = enabled,
                modifier = Modifier.testTag(tag), insideMargin = EditorSettingsDefaults.RowPadding,
                onSelectedIndexChange = { index ->
                    when (modes[index]) {
                        "native" -> set(mapOf("mode" to "native"))
                        "image" -> command.send("staruw.image", "id" to id)
                        "text" -> set(mapOf("mode" to "text", "text" to "", "fontId" to control.text("defaultFontId", fonts.firstOrNull()?.text("id") ?: "")))
                    }
                })
            if (mode == "text") {
                var text by rememberDocumentField(replacement.text("text"))
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelTextField(text, { text = it.take(65536) }, "签名文字", "$tag.text", enabled)
                    MainButton("应用文字", "$tag.text.apply", enabled, Modifier.fillMaxWidth()) {
                        set(mapOf("mode" to "text", "text" to text, "fontId" to replacement.text("fontId")))
                    }
                }
                ChromeDropdownPreference(title = "签名字体", items = fonts.map { it.text("label") },
                    selectedIndex = fonts.indexOfFirst { it.text("id") == replacement.text("fontId") }.coerceAtLeast(0),
                    enabled = enabled, modifier = Modifier.testTag("$tag.font"), insideMargin = EditorSettingsDefaults.RowPadding,
                    onSelectedIndexChange = { set(mapOf("mode" to "text", "text" to replacement.text("text"), "fontId" to fonts[it].text("id"))) })
                TextButton("导入签名字体", hapticClick { command.send("staruw.font", "id" to id) },
                    enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("$tag.font.import"))
            }
            if (mode == "image") {
                val field = if (model) "fit" else "shape"
                val choices = (control[if (model) "allowFits" else "allowShapes"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                val names = mapOf("contain" to "完整显示", "cover" to "填满裁切", "rect" to "原比例", "circle" to "圆形")
                ChromeDropdownPreference(title = "图片显示", items = choices.map { names[it] ?: it },
                    selectedIndex = choices.indexOf(replacement.text(field)).coerceAtLeast(0), enabled = enabled,
                    modifier = Modifier.testTag("$tag.fit"), insideMargin = EditorSettingsDefaults.RowPadding,
                    onSelectedIndexChange = { set(mapOf("mode" to "image", "resourceId" to replacement.text("resourceId"), field to choices[it])) })
                TextButton("更换图片", hapticClick { command.send("staruw.image", "id" to id) },
                    enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("$tag.image.import"))
            }
        }
    }
    if (control.text("scope") == "photo" && control.flag("hasValue")) {
        TextButton("恢复照片原值", hapticClick { command.send("staruw.clear", "id" to id) },
            enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("$tag.clear"))
    }
}
