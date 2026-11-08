package dev.starmark.starmark.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Production shell router: photo import retains its existing non-dismissible dialog. */
@Composable
internal fun ImportWorkflow(status: Map<*, *>, command: UiCommand) {
    if (status.text("stage") == "watermarks") {
        ChromeDialog(show = status.flag("active")) {
            Column(Modifier.fillMaxWidth().testTag("watermark.import.progress")
                .semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OperationProgressContent("watermark.import.counts", "正在导入水印…",
                    if (status.integer("total") > 0) "已处理 ${status.integer("completed")} / ${status.integer("total")} 款" else "正在检查水印集合…")
                if (status.text("path").isNotBlank()) Text(status.text("path"))
                ChromeTextButton("取消导入", "watermark.import.cancel") { command.send("style.import.cancel") }
            }
        }
    } else {
        PhotoImportProgress(status)
    }
    val result = status.obj("result")
    ChromeDialog(show = !status.flag("active") && result.isNotEmpty(),
        onDismissRequest = { command.send("style.import.dismiss") }) {
        Column(Modifier.fillMaxWidth().testTag("watermark.import.result"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (result.flag("cancelled")) "水印导入已取消" else if (result.flag("aborted")) "水印导入已停止" else "水印导入结果",
                style = MiuixTheme.textStyles.title4)
            Text("导入 ${result.integer("installed")} · 跳过 ${result.integer("skipped")} · 冲突 ${result.integer("conflicts")} · 失败 ${result.integer("failed")}")
            Text("已处理 ${result.integer("completed")} / ${result.integer("total")} 款；未处理 ${(result["remaining"] as? List<*>)?.size ?: 0} 款")
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (result.text("error").isNotBlank()) Text(result.text("error"), Modifier.testTag("watermark.import.error"))
                for ((index, item) in result.rows("items").withIndex()) {
                    val label = when (item.text("status")) {
                        "installed" -> "已导入"
                        "skipped" -> "内容重复，已跳过"
                        "conflict" -> "身份冲突"
                        "cancelled" -> "已取消，未导入"
                        "unprocessed" -> "未处理"
                        else -> "导入失败"
                    }
                    Text("${item.text("path")}：$label${if (item.text("message").isNotBlank()) " — ${item.text("message")}" else ""}",
                        Modifier.testTag("watermark.import.item.$index"))
                }
                val ignored = result["ignored"] as? List<*>
                if (!ignored.isNullOrEmpty()) Text("已忽略说明文件：${ignored.joinToString("、")}")
            }
            MainButton("完成", "watermark.import.dismiss") { command.send("style.import.dismiss") }
        }
    }
}
