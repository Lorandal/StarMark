package dev.starmark.starmark.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal object EditorSettingsDefaults {
    val GroupSpacing = 12.dp
    val CardRadius = 20.dp
    val RowInset = 16.dp
    val RowMinHeight = 56.dp
    val TouchTarget = 48.dp
    val RowPadding = PaddingValues(horizontal = RowInset, vertical = 12.dp)
}

/** Retain the form row and trailing edge; halve only its editable surface. */
@Composable
internal fun HalfWidthTextInput(modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(Modifier.fillMaxWidth(if (enabled) .5f else 1f)) { content() }
    }
}

/** The page owns the outer gutter. Each preference owns its single inset inside this card. */
@Composable
internal fun EditorSettingsCard(
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = EditorSettingsDefaults.CardRadius,
        insideMargin = insideMargin,
        colors = glassCardColors(),
        content = content,
    )
}

@Composable
internal fun EditorSettingsSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            modifier = Modifier
                .padding(horizontal = EditorSettingsDefaults.RowInset, vertical = 8.dp)
                .semantics { heading() },
            style = MiuixTheme.textStyles.footnote1,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        content()
    }
}
