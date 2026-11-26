package dev.starmark.starmark.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Production shell router: photo import retains its existing non-dismissible dialog. */
@Composable
internal fun ImportWorkflow(status: Map<*, *>, command: UiCommand) {
    val active = status.flag("active")
    var lastProgress by remember { mutableStateOf<Map<*, *>>(emptyMap<Any, Any>()) }
    if (active) SideEffect { lastProgress = status }
    val progress = if (active) status else lastProgress
    if (progress.text("stage") == "watermarks") {
        ChromeDialog(show = active, onDismissFinished = { lastProgress = emptyMap<Any, Any>() }) {
            Column(Modifier.fillMaxWidth().testTag("watermark.import.progress")
                .semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OperationProgressContent("watermark.import.counts", "正在导入水印…",
                    if (progress.integer("total") > 0) "已处理 ${progress.integer("completed")} / ${progress.integer("total")} 款" else "正在检查水印集合…")
                if (progress.text("path").isNotBlank()) Text(progress.text("path"))
                ChromeTextButton("取消导入", "watermark.import.cancel") { command.send("style.import.cancel") }
            }
        }
    } else {
        PhotoImportProgress(status)
    }
    val incoming = status.obj("result")
    var lastResult by remember { mutableStateOf<Map<*, *>>(emptyMap<Any, Any>()) }
    val showResult = !active && incoming.isNotEmpty()
    if (showResult) SideEffect { lastResult = incoming }
    val result = if (showResult) incoming else lastResult
    val installed = result.integer("installed")
    val remaining = (result["remaining"] as? List<*>).orEmpty()
    val allImported = installed > 0 && installed == result.integer("total") && remaining.isEmpty()
        && result.integer("skipped") == 0 && result.integer("conflicts") == 0 && result.integer("failed") == 0
        && !result.flag("aborted") && !result.flag("cancelled") && result.text("error").isBlank()
    val title = when {
        result.flag("cancelled") -> "水印导入已取消"
        result.flag("aborted") -> "水印导入已停止"
        allImported -> "已导入 $installed 款水印"
        else -> "水印导入结果"
    }
    ChromeDialog(show = showResult, title = title, onDismissFinished = { lastResult = emptyMap<Any, Any>() },
        onDismissRequest = { command.send("style.import.dismiss") }) {
        key(result) {
            var expanded by remember { mutableStateOf(false) }
            val (issues, details) = remember(result) {
                result.rows("items").withIndex().partition { it.value.text("status") !in detailStatuses }
            }
            val ignored = (result["ignored"] as? List<*>).orEmpty()
            val unlisted = remember(result) {
                val paths = result.rows("items").mapTo(mutableSetOf()) { it.text("path") }
                remaining.filterNot { it in paths }
            }
            val counts = remember(result) {
                buildList {
                    if (installed > 0) add("已导入 $installed 款")
                    for ((key, label) in listOf("skipped" to "重复跳过", "conflicts" to "冲突", "failed" to "失败")) {
                        val count = result.integer(key)
                        if (count > 0) add("$label $count 款")
                    }
                    if (remaining.isNotEmpty()) add("未处理 ${remaining.size} 款")
                }.joinToString(" · ")
            }
            val error = result.text("error")
            val hasDetails = details.isNotEmpty() || ignored.isNotEmpty() || unlisted.isNotEmpty()
            Column(Modifier.fillMaxWidth().testTag("watermark.import.result"),
                verticalArrangement = Arrangement.spacedBy(EditorSettingsDefaults.GroupSpacing)) {
                if (!allImported && counts.isNotEmpty()) Text(counts)
                if (counts.isEmpty() && error.isBlank() && result.integer("total") == 0 &&
                    !result.flag("aborted") && !result.flag("cancelled")) Text("没有可导入的水印")
                if (error.isNotBlank() || issues.isNotEmpty() || expanded) {
                    val listState = rememberLazyListState()
                    LaunchedEffect(expanded) { listState.scrollToItem(0) }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 300.dp),
                        state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (error.isNotBlank()) item { Text(error, Modifier.testTag("watermark.import.error")) }
                        items(issues, key = { it.index }) { row -> ImportResultRow(row) }
                        if (expanded) {
                            items(details, key = { it.index }) { row -> ImportResultRow(row) }
                            items(unlisted.size) { Text("${unlisted[it]}：未处理") }
                            if (ignored.isNotEmpty()) item { Text("已忽略说明文件：${ignored.joinToString("、")}") }
                        }
                    }
                }
                if (hasDetails) ImportResultActions(expanded, { expanded = !expanded }) { command.send("style.import.dismiss") }
                else MainButton("完成", "watermark.import.dismiss") { command.send("style.import.dismiss") }
            }
        }
    }
}

private val detailStatuses = setOf("installed", "skipped", "unprocessed")

@Composable
private fun ImportResultRow(row: IndexedValue<Map<*, *>>) {
    val item = row.value
    val label = when (item.text("status")) {
        "installed" -> "已导入"
        "skipped" -> "内容重复，已跳过"
        "conflict" -> "身份冲突"
        "cancelled" -> "已取消，未导入"
        "unprocessed" -> "未处理"
        else -> "导入失败"
    }
    val message = item.text("message")
    val reason = when {
        message.isBlank() -> label
        label in message -> message
        else -> "$label — $message"
    }
    Text("${item.text("path")}：$reason", Modifier.testTag("watermark.import.item.${row.index}"))
}

@Composable
private fun ImportResultActions(expanded: Boolean, toggle: () -> Unit, done: () -> Unit) {
    val label = if (expanded) "收起明细" else "查看明细"
    val gap = EditorSettingsDefaults.GroupSpacing
    val measurer = rememberTextMeasurer()
    val style = MiuixTheme.textStyles.button
    val width = with(LocalDensity.current) { measurer.measure(label, style, maxLines = 1).size.width.toDp() }
    val padding = ButtonDefaults.InsideMargin
    val direction = LocalLayoutDirection.current
    val minButton = maxOf(EditorSettingsDefaults.TouchTarget, width + padding.calculateLeftPadding(direction) + padding.calculateRightPadding(direction))
    val actions: @Composable (Modifier) -> Unit = { slot ->
        TextButton(label, hapticClick(onClick = toggle), slot.testTag("watermark.import.details"), minHeight = EditorSettingsDefaults.TouchTarget)
        TextButton("完成", hapticClick(onClick = done), slot.testTag("watermark.import.dismiss"), minHeight = EditorSettingsDefaults.TouchTarget,
            colors = ButtonDefaults.textButtonColorsPrimary())
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= minButton * 2 + gap) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            actions(Modifier.weight(1f))
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
            actions(Modifier.fillMaxWidth())
        }
    }
}
