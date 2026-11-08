package dev.starmark.starmark.ui

import android.graphics.Typeface
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.security.MessageDigest

/** Built-in and privately imported fonts share the same undoable selection. */
@Composable
internal fun ImportedFontPicker(state: Map<*, *>, command: UiCommand) {
    val fonts = state.obj("fonts")
    val selected = state.obj("document").obj("font")
    val automatic = fonts.flag("automatic", selected.flag("automatic"))
    val enabled = state.editable()
    LaunchedEffect(Unit) { command.send("font.load") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(EditorSettingsDefaults.GroupSpacing)) {
        EditorSettingsCard {
            HapticCheckboxPreference(
                title = "自动字体", checked = automatic,
                onCheckedChange = { if (it) command.send("font.auto") }, enabled = enabled,
                allowUncheck = false,
                checkboxLocation = CheckboxLocation.End, insideMargin = EditorSettingsDefaults.RowPadding,
                modifier = Modifier.testTag("font.auto"),
            )
        }
        listOf("bundled" to "内置字体", "imported" to "导入字体").forEach { (source, title) ->
            val choices = fonts.rows(source)
            if (choices.isNotEmpty()) EditorSettingsSection(title) {
                EditorSettingsCard {
                    choices.forEach { font ->
                        val id = font.text("id")
                        key(source, id) {
                            ImportedFontChoice(font, !automatic && selected.text("source") == source && selected.text("id") == id, enabled, command)
                        }
                    }
                }
            }
        }
        EditorSettingsCard {
            ArrowPreference(
                "导入字体", modifier = Modifier.testTag("font.import"), enabled = enabled,
                insideMargin = EditorSettingsDefaults.RowPadding, onClick = hapticClick { command.send("font.import") },
            )
        }
    }
}

@Composable
private fun ImportedFontChoice(font: Map<*, *>, selected: Boolean, enabled: Boolean, command: UiCommand) {
    val choose = hapticClick(toggleHaptic(false), feedbackEnabled = enabled && !selected) {
        if (enabled && !selected) command.send("font.choose", "font" to font.stringKeys())
    }
    BasicComponent(
        modifier = Modifier.testTag("font.${font.text("source", "bundled")}.${font.text("id")}"),
        insideMargin = EditorSettingsDefaults.RowPadding,
        enabled = enabled, role = Role.Checkbox, onClick = choose,
        endActions = { WithoutChildHaptics { Checkbox(ToggleableState(selected), onClick = choose, enabled = enabled) } },
    ) {
        Text(font.text("title", font.text("id")), style = MiuixTheme.textStyles.headline1, fontWeight = FontWeight.Medium,
            color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSecondaryVariant)
        FontFacePreview(font, enabled)
    }
}

private sealed interface FontPreviewState {
    data object Loading : FontPreviewState
    data object Unavailable : FontPreviewState
    data class Ready(val family: FontFamily, val weight: FontWeight, val style: FontStyle) : FontPreviewState
}

/** Never substitute the UI font and present it as the selected font's preview. */
@Composable
private fun FontFacePreview(font: Map<*, *>, enabled: Boolean) {
    val context = LocalContext.current
    val source = fontPreviewSource(font)
    val preview by produceState<FontPreviewState>(FontPreviewState.Loading, context, source) {
        value = FontPreviewState.Loading
        value = withContext(Dispatchers.IO) {
            runCatching {
                val face = when (source) {
                    is FontPreviewSource.Asset -> {
                        // Confirm the Flutter asset exists; Typeface then reads that exact asset.
                        context.assets.open(source.path).use { }
                        Typeface.createFromAsset(context.assets, source.path)
                    }
                    is FontPreviewSource.PrivateFile -> {
                        val file = File(context.filesDir, source.relativePath)
                        require(file.isFile && file.length() in 12..(32L * 1024 * 1024))
                        val digest = MessageDigest.getInstance("SHA-256")
                        file.inputStream().use { stream ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                digest.update(buffer, 0, read)
                            }
                        }
                        require(digest.digest().joinToString("") { "%02x".format(it) } == source.fingerprint)
                        Typeface.createFromFile(file)
                    }
                    null -> error("Font preview source unavailable")
                }
                FontPreviewState.Ready(FontFamily(face), FontWeight(face.weight), if (face.isItalic) FontStyle.Italic else FontStyle.Normal)
            }.getOrDefault(FontPreviewState.Unavailable)
        }
    }
    when (val loaded = preview) {
        is FontPreviewState.Ready -> Text(
            "35mm f/1.4",
            modifier = Modifier.fillMaxWidth().testTag("font.preview.${font.text("id")}"),
            fontFamily = loaded.family, fontWeight = loaded.weight, fontStyle = loaded.style, fontSize = 14.sp,
            style = MiuixTheme.textStyles.main.copy(fontSynthesis = FontSynthesis.None),
            color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        FontPreviewState.Loading -> SupportingText("正在载入字体预览")
        FontPreviewState.Unavailable -> SupportingText("字体预览不可用")
    }
}
