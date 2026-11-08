package dev.starmark.starmark.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.squircle.squircleClip

/** Non-input Miuix Card plus one external click source and the APK row/banner policy. */
@Composable
internal fun StockCard(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    kind: StockPressKind = StockPressKind.Row,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val clickable = if (onClick != null) {
        Modifier
            .heightIn(min = 85.dp)
            .squircleClip(20.dp)
            .stockPress(interaction, kind, enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
    } else {
        Modifier
    }
    // Explicitly select the non-clickable overload; no competing Miuix press recognizer.
    Card(
        modifier = modifier.then(clickable),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(start = 16.dp, top = 11.dp, end = 16.dp, bottom = 9.dp),
        content = content,
    )
}
