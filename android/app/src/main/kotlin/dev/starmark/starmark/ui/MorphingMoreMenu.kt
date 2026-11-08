package dev.starmark.starmark.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max

@Immutable
data class MorphMenuEntry(val id: String, val label: String, val icon: ImageVector? = null, val enabled: Boolean = true, val tag: String? = null)

/** Raw, caller-local dp tracks for native Compose integration tests; no accessibility prose. */
internal val MorphMenuGeometryKey = SemanticsPropertyKey<List<Float>>("StarMarkMorphGeometry")
internal val MorphMenuMaterialKey = SemanticsPropertyKey<Float>("StarMarkMorphMaterial")

/** The trigger stays fixed; the menu morphs in the same Activity window above the photo. */
@Composable
fun MorphingMoreMenu(
    entries: List<MorphMenuEntry>,
    onAction: (String) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    tag: String = "editor.more",
) {
    val density = LocalDensity.current
    val colors = MiuixTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    var mounted by remember { mutableStateOf(false) }
    var shellReady by remember { mutableStateOf(false) }
    var anchorScreen by remember { mutableStateOf<Rect?>(null) }
    val available = enabled && entries.isNotEmpty()
    LaunchedEffect(available) { if (!available) expanded = false }

    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier.size(48.dp)
                .onGloballyPositioned { coordinates ->
                    val origin = coordinates.localToScreen(Offset.Zero)
                    if (origin.x.isFinite() && origin.y.isFinite()) {
                        val inset = with(density) { 2.dp.toPx() }
                        anchorScreen = Rect(origin.x + inset, origin.y + inset, origin.x + coordinates.size.width - inset, origin.y + coordinates.size.height - inset)
                    }
                }.then(if (mounted) Modifier.clearAndSetSemantics {} else Modifier.testTag(tag))
                .semantics { if (!mounted) contentDescription = "更多" }
                .clickable(interactionSource = interaction, indication = null, enabled = available && !mounted, role = Role.Button,
                    onClick = hapticClick { mounted = true; expanded = !expanded }),
        contentAlignment = Alignment.Center,
    ) {
        if (!mounted || !shellReady) ChromeSurface(
            Modifier.fillMaxSize().padding(2.dp).stockPress(interaction, StockPressKind.Banner, available),
            cornerRadius = 22.dp,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(MiuixIcons.More, null, Modifier.size(28.dp).testTag("$tag.glyph"), tint = if (available) colors.onSurface else colors.disabledOnSurface)
            }
        }
    }
    // Registration has no layout node, so the toolbar never gains another Row gap.
    ChromeAnchoredPopup(
        show = expanded,
        anchorScreen = anchorScreen,
        title = "更多",
        onDismissRequest = { expanded = false },
        onDismissFinished = { mounted = false; shellReady = false },
        onShellReady = { shellReady = true },
        width = 200f,
        tag = "$tag.menu",
        source = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(MiuixIcons.More, null, Modifier.size(28.dp), tint = colors.onSurface)
            }
        },
    ) { interactive ->
        val rowHeight = if (density.fontScale >= 1.6f) max(44f, 36f * density.fontScale + 12f) else 44f
        MorphMenuContents(entries, rowHeight, interactive && available) { expanded = false; onAction(it) }
    }
}

/** Stable content is skipped while the shell/material frame values animate around it. */
@Composable
private fun MorphMenuContents(entries: List<MorphMenuEntry>, rowHeight: Float, enabled: Boolean, onAction: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        entries.forEach { entry ->
            Row(
                Modifier.fillMaxWidth().height(rowHeight.dp).testTag(entry.tag ?: "menu.action.${entry.id}")
                    .clickable(enabled = enabled && entry.enabled, role = Role.Button,
                        onClick = hapticClick(HapticFeedbackType.SegmentTick) { onAction(entry.id) }).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val tint = if (entry.enabled) colors.onSurface else colors.disabledOnSurface
                entry.icon?.let { Icon(it, null, Modifier.size(24.dp), tint = tint) }
                Text(entry.label, color = tint, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
