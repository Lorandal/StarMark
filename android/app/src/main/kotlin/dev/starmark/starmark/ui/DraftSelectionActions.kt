package dev.starmark.starmark.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The selection surface uses the same rounded press backdrop as ordinary navigation. */
@Composable
internal fun DraftSelectionActions(enabled: Boolean, hasSelection: Boolean, allSelected: Boolean,
    onCancel: () -> Unit, onToggleAll: () -> Unit, onDelete: () -> Unit) {
    SelectionActions(enabled, hasSelection, allSelected, onCancel, onToggleAll, onDelete)
}

@Composable
internal fun SelectionActions(enabled: Boolean, hasSelection: Boolean, allSelected: Boolean,
    onCancel: () -> Unit, onToggleAll: () -> Unit, onDelete: () -> Unit,
    onExport: (() -> Unit)? = null, canDelete: Boolean = hasSelection) {
    val watermark = onExport != null
    val subject = if (watermark) "水印" else "草稿"
    val prefix = if (watermark) "watermark" else "draft"
    @Composable fun RowScope.Action(label: String, tag: String, icon: ImageVector, active: Boolean, selected: Boolean = false,
        feedback: HapticFeedbackType = HapticFeedbackType.ContextClick, click: () -> Unit) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val hovered by interaction.collectIsHoveredAsState()
        val focused by interaction.collectIsFocusedAsState()
        val effects = LocalStarMarkEffects.current
        val dark = MiuixTheme.colorScheme.background.luminance() < .5f
        val held = active && pressed
        val highlighted = held || (active && (hovered || focused))
        val fraction by animateFloatAsState(if (highlighted) 1f else 0f,
            animationSpec = if (effects.reduceMotion) snap() else tween(200, easing = LinearEasing), label = "draftActionPressAlpha")
        val shrink by animateFloatAsState(if (held) 1f else 0f,
            animationSpec = if (effects.reduceMotion) snap() else tween(200, easing = LinearEasing), label = "draftActionPressInset")
        val color = (if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
            .copy(alpha = if (!active) .38f else if (held) .6f else 1f)
        val backdropColor = navigationOverlay(dark, pressed = held || !(active && (hovered || focused)),
            hovered = !held && active && (hovered || focused))
        Box(Modifier.weight(1f).fillMaxHeight().drawWithCache {
            val inset = navigationInset.dp.toPx()
            val height = (size.height - inset * 2f).coerceAtLeast(1f)
            val outline = MiuixCornerShape(24.5.dp).createOutline(Size(size.width, height), layoutDirection, this)
            onDrawBehind {
                val delta = 6.dp.toPx() * shrink
                withTransform({
                    translate(top = inset)
                    scale((1f - delta / size.width.coerceAtLeast(1f)).coerceAtLeast(.5f),
                        (1f - delta / height).coerceAtLeast(.5f), Offset(size.width / 2f, height / 2f))
                }) { drawOutline(outline, backdropColor, alpha = fraction) }
            }
        }.testTag(tag).semantics {
            contentDescription = label
            if (tag == "$prefix.selection.all") stateDescription = if (allSelected) "已全选" else "未全选"
        }.clickable(enabled = active, role = Role.Button, interactionSource = interaction, indication = null,
            onClick = hapticClick(feedback, onClick = click)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(28.dp), tint = color)
        }
    }
    Row(Modifier.fillMaxSize().padding(horizontal = 3.dp)) {
        Action("取消选择$subject", "$prefix.selection.cancel", MiuixIcons.Close, enabled, click = onCancel)
        if (onExport != null) Action("导出选中的水印", "watermarks.export", MiuixIcons.Share, enabled && hasSelection, click = onExport)
        Action(if (allSelected) "取消全选$subject" else "全选$subject", "$prefix.selection.all", MiuixIcons.SelectAll, enabled,
            selected = allSelected, feedback = toggleHaptic(allSelected), click = onToggleAll)
        Action("删除选中的$subject", "$prefix.delete", MiuixIcons.Delete, enabled && canDelete, click = onDelete)
    }
}
