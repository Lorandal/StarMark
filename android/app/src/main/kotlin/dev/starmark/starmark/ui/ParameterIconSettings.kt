package dev.starmark.starmark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ParameterIconShapePreference(shape: String, enabled: Boolean, modifier: Modifier = Modifier, onSelected: (String) -> Unit) {
    ChromeDropdownPreference(
        listOf("方形 · 完整显示", "圆形 · 居中裁剪"), if (shape == "circle") 1 else 0, "图标形状",
        modifier = modifier, enabled = enabled, insideMargin = EditorSettingsDefaults.RowPadding,
        onSelectedIndexChange = { onSelected(if (it == 1) "circle" else "square") },
    )
}

@Composable
internal fun ParameterIconSettingsCard(state: Map<*, *>, command: UiCommand) {
    val settings = state.obj("settings")
    ParameterIconChoiceCard(
        icons = state.rows("parameterIcons"), chosen = settings.text("defaultParameterIcon"),
        shape = settings.text("defaultParameterIconShape", "square"), enabled = !state.flag("busy"),
        reduceMotion = settings.flag("reduceMotion"), tag = "settings.parameterIcon",
        onSelected = { command.send("settings.update", "patch" to mapOf("defaultParameterIcon" to it)) },
        onImport = { command.send("parameterIcon.import") },
        onShape = { command.send("settings.update", "patch" to mapOf("defaultParameterIconShape" to it)) },
    )
}

/** Settings and both editors use the same thumbnails, hit targets and selection ring. */
@Composable
internal fun ParameterIconChoiceCard(
    icons: List<Map<*, *>>, chosen: String, shape: String, enabled: Boolean, reduceMotion: Boolean,
    tag: String, onSelected: (String) -> Unit, onImport: () -> Unit, onShape: (String) -> Unit,
    onDefault: (() -> Unit)? = null, summary: String? = null,
) {
    val scroll = rememberLazyListState()
    val choices = if (onDefault != null) listOf(mapOf("id" to "default", "asset" to "", "title" to "模板默认图标")) + icons else icons
    LaunchedEffect(chosen, choices.size) {
        val index = choices.indexOfFirst { it.text("asset") == chosen }
        if (index >= 0 && scroll.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            if (reduceMotion) scroll.scrollToItem(index) else scroll.animateScrollToItem(index)
        }
    }
    val primary = MiuixTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth().testTag("$tag.card"), cornerRadius = 20.dp) {
        LazyRow(Modifier.fillMaxWidth().testTag("$tag.gallery"), state = scroll,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            items(choices, key = { it.text("id") }) { icon ->
                val active = chosen == icon.text("asset")
                val interaction = remember { MutableInteractionSource() }
                Box(Modifier.size(60.dp).testTag("$tag.${icon.text("id")}")
                    .semantics { selected = active; contentDescription = icon.text("title") }
                    .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.RadioButton,
                        onClick = hapticClick { if (icon.text("asset").isEmpty()) onDefault?.invoke() else onSelected(icon.text("asset")) }),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(60.dp).stockPress(interaction, StockPressKind.Banner, enabled)
                        .then(if (active) Modifier.border(2.dp, primary, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                        if (icon.text("asset").isEmpty()) {
                            Box(Modifier.size(48.dp).border(1.dp, MiuixTheme.colorScheme.outline.copy(alpha = .4f), CircleShape), contentAlignment = Alignment.Center) {
                                Text("默认", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface)
                            }
                        } else PhotoThumbnail(icon.text("image"), Modifier.size(48.dp).clip(CircleShape).testTag("$tag.face.${icon.text("id")}"),
                            contentScale = ContentScale.Fit, cornerRadius = 24.dp, decodeEdge = 144)
                    }
                }
            }
            item(key = "import") {
                val interaction = remember { MutableInteractionSource() }
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(60.dp).testTag("$tag.import")
                    .semantics { contentDescription = "导入参数图标" }
                    .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button,
                        onClick = hapticClick(onClick = onImport)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(48.dp).stockPress(interaction, StockPressKind.Banner, enabled).testTag("$tag.import.face")) {
                        // This button lives inside the recorded page. Sample only its sibling
                        // underlay, never the ancestor page that also contains the glass itself.
                        Box(Modifier.matchParentSize().layerBackdrop(backdrop)
                            .background(MiuixTheme.colorScheme.surface, CircleShape))
                        CompositionLocalProvider(LocalChromeBackdrop provides backdrop) {
                            ChromeSurface(Modifier.fillMaxSize(), cornerRadius = 24.dp) {
                                Box(Modifier.fillMaxSize().clip(CircleShape).background(primary.copy(alpha = .22f)), contentAlignment = Alignment.Center) {
                                    Icon(MiuixIcons.Add, null, Modifier.size(26.dp), tint = primary)
                                }
                            }
                        }
                    }
                }
            }
        }
        ParameterIconShapePreference(shape, enabled && (onDefault == null || chosen.isNotEmpty()), Modifier.testTag("$tag.shape"), onShape)
        if (summary != null) SupportingText(summary, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
    }
}

@Composable
internal fun ParameterIconRegionCard(state: Map<*, *>, command: UiCommand) {
    val replacements = state.obj("document").obj("regionReplacements")
    val design = replacements.obj("parameters")
    val global = state.obj("regions").flag("globalDefault")
    LaunchedEffect(state.obj("document").text("packageId")) { command.send("parameterIcon.load") }
    ParameterIconChoiceCard(
        icons = state.rows("parameterIcons"),
        chosen = if (design.text("source") == "image") design.text("asset") else "",
        shape = design.text("shape", "square"), enabled = state.editable() && !global,
        reduceMotion = state.obj("settings").flag("reduceMotion"), tag = "region.parameters",
        onSelected = { command.send("region.icon", "asset" to it) },
        onImport = { command.send("region.import", "region" to "parameters") },
        onShape = { command.patch("图标形状", "regionReplacements" to (replacements.stringKeys() + ("parameters" to (design.stringKeys() + ("shape" to it))))) },
        onDefault = if (global) null else ({ command.patch("恢复模板图标", "regionReplacements" to (replacements.stringKeys() - "parameters")) }),
        summary = if (global) "已由实验性功能中的“替换默认参数图标”统一设置" else null,
    )
}
