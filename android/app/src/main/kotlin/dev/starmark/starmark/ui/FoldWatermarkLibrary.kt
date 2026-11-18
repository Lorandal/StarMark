package dev.starmark.starmark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val CameraWatermarkYellow = Color(0xFFFFCE16)

@Composable
internal fun FoldWatermarkLibrary(state: Map<*, *>, command: UiCommand, rightInset: Dp, editSelected: () -> Unit) {
    val watermarkOn = state.obj("document").flag("enabled", true)
    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).testTag("editor.inline.styles")) {
        CameraWatermarkQuickControls(state, command, CameraWatermarkYellow, locationLabel = "经纬度") {
            CameraWatermarkOption(
                title = "水印", tag = "watermarks.enabled", icon = MiuixIcons.Image,
                selected = watermarkOn, available = true, editable = state.editable(),
                unavailableReason = null, accent = CameraWatermarkYellow,
                stateLabel = if (watermarkOn) "已开启" else "已关闭",
            ) {
                command.patch("照片水印开关", "enabled" to !watermarkOn)
            }
        }
        WatermarkPickerPage(state, command, chromeInsets = PaddingValues(bottom = 12.dp),
            galleryRightInset = rightInset, editSelectedInPhoto = editSelected)
    }
}
