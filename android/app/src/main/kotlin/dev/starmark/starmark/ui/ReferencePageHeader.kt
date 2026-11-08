package dev.starmark.starmark.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/** DeskClock's bundled MIUIX title geometry, using the existing Miuix scroll state.
 * The expandable title is measured, never assigned the unrelated 72dp resource.
 * APK sources/parameters: .tooling/089-header-audit/header-findings.md. */
@Composable
internal fun ReferencePageHeader(title: String, scrollBehavior: ScrollBehavior, compact: Boolean, showBack: Boolean = true, onBack: () -> Unit) {
    val state = scrollBehavior.state
    val effects = LocalStarMarkEffects.current
    // Miuix's limit is a plain var. Always observe the offset, even at a zero
    // compact limit, so restoring the measured range invalidates these effects.
    val collapsedFraction by remember(state) { derivedStateOf {
        val offset = state.heightOffset
        val limit = state.heightOffsetLimit
        if (limit < 0f) (offset / limit).coerceIn(0f, 1f) else 0f
    } }
    val compactVisible by remember(state, compact) { derivedStateOf { compact || collapsedFraction >= 1f / 3f } }
    val floating by remember(state, compact) { derivedStateOf { state.contentOffset < -1f && (compact || collapsedFraction >= .999f) } }
    val titleProgress by animateFloatAsState(if (compactVisible) 1f else 0f,
        if (effects.reduceMotion) snap() else folmeSpring(damping = 1f, response = if (compactVisible) .30f else .15f), label = "headerTitle")
    val materialProgress by animateFloatAsState(if (floating) 1f else 0f,
        if (effects.reduceMotion) snap() else tween(350, easing = LinearEasing), label = "headerMaterial")
    Layout(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().testTag("app.collapsingHeader"),
        content = {
            if (showBack) ReferenceBackButton(materialProgress, onBack) else Spacer(Modifier.size(48.dp))
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("header.compactTitle").graphicsLayer {
                    alpha = titleProgress; translationY = 20f * (1f - titleProgress)
                }.then(if (!compactVisible) Modifier.clearAndSetSemantics {} else Modifier))
            Box(Modifier.padding(horizontal = 28.dp).padding(top = 8.dp, bottom = 10.dp)
                .graphicsLayer { alpha = if (compact) 0f else 1f - (collapsedFraction * 3f).coerceIn(0f, 1f) }
                .then(if (compactVisible) Modifier.clearAndSetSemantics {} else Modifier)) {
                Text(title, fontSize = 32.sp, fontWeight = FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("header.largeTitle"))
            }
        },
    ) { children, constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val back = children[0].measure(Constraints.fixed(48.dp.roundToPx(), 48.dp.roundToPx()))
        val small = children[1].measure(loose.copy(maxWidth = (width - 120.dp.roundToPx()).coerceAtLeast(0)))
        val large = children[2].measure(loose)
        val rowHeight = maxOf(56.dp.roundToPx(), small.height + 8.dp.roundToPx())
        val expansion = if (compact) 0 else large.height
        val limit = -expansion.toFloat()
        if (state.heightOffsetLimit != limit) {
            val restoringFromCompact = state.heightOffsetLimit == 0f && limit < 0f
            val previousFraction = if (state.heightOffsetLimit < 0f)
                (state.heightOffset / state.heightOffsetLimit).coerceIn(0f, 1f) else 0f
            state.heightOffsetLimit = limit
            state.heightOffset = if (restoringFromCompact && state.contentOffset < -1f) limit
                else limit * previousFraction
        }
        val offset = state.heightOffset.roundToInt().coerceIn(-expansion, 0)
        layout(width, rowHeight + expansion + offset) {
            back.placeRelative(10.dp.roundToPx(), (rowHeight - back.height) / 2)
            small.placeRelative((width - small.width) / 2, (rowHeight - small.height) / 2)
            large.placeRelative(0, rowHeight + offset)
        }
    }
}

@Composable
private fun ReferenceBackButton(materialProgress: Float, onBack: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(48.dp).testTag("navigation.back")
        .clickable(interaction, indication = null, role = Role.Button, onClick = onBack), contentAlignment = Alignment.Center) {
        // Only the backdrop/blends animate. The native sampling View and chevron never move.
        if (materialProgress > 0f) {
            ChromeSurface(Modifier.size(44.dp).testTag("header.back.material"), cornerRadius = 22.dp,
                role = ChromeMaterialRole.HeaderButton, strength = materialProgress) {}
        }
        Icon(ReferenceIcons.Back, "返回", Modifier.size(28.dp), tint = MiuixTheme.colorScheme.onSurface)
    }
}
