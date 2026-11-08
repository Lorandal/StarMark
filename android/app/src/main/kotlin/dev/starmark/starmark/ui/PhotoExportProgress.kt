package dev.starmark.starmark.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.delay

internal fun exportStageLabel(stage: String) = when (stage) {
    "preparing" -> "正在读取原图"
    "rendering" -> "正在生成水印"
    "saving" -> "正在编码并保存照片"
    else -> "正在处理照片"
}

/** MiMediaEditor uses an indeterminate, non-dismissible saving dialog, not a fake percentage. */
@Composable
internal fun PhotoExportProgress(status: Map<*, *>) {
    PhotoOperationProgress(status.flag("active") && status.flag("single"), "photo.export.progress",
        "正在保存…", exportStageLabel(status.text("stage")), status.flag("highResolution"))
}

internal const val HIGH_RESOLUTION_EXPORT_NOTICE = "当前图片分辨率较高，导出速度可能较慢"

@Composable
internal fun PhotoImportProgress(status: Map<*, *>) {
    PhotoOperationProgress(status.flag("active"), "photo.import.progress", "正在导入…",
        if (status.text("stage") == "reading") "正在读取原图" else "正在准备照片预览", false)
}

@Composable
internal fun PhotoOperationProgress(show: Boolean, tag: String, title: String, stage: String, highResolution: Boolean = false, onDismissRequest: (() -> Unit)? = null) {
    ChromeDialog(show = show, onDismissRequest = onDismissRequest) {
        OperationProgressContent(tag, title, stage, highResolution)
    }
}

/** Short work finishes without a flashing modal; the timer is cancelled on completion. */
@Composable
internal fun DeferredOperationProgress(active: Boolean, id: Any, tag: String, title: String, stage: String, onDismissRequest: (() -> Unit)? = null) {
    var visible by remember(id) { mutableStateOf(false) }
    LaunchedEffect(active, id) {
        if (active) { delay(180); visible = true } else visible = false
    }
    PhotoOperationProgress(active && visible, tag, title, stage, onDismissRequest = onDismissRequest)
}

@Composable
internal fun OperationProgressContent(tag: String, title: String, stage: String, highResolution: Boolean = false) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(tag)
            .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            CircularProgressIndicator(Modifier.size(32.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MiuixTheme.textStyles.title4)
                Text(stage, style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                if (highResolution) Text(HIGH_RESOLUTION_EXPORT_NOTICE,
                    style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
}
