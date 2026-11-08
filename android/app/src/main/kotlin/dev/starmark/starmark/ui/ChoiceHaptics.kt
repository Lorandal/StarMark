package dev.starmark.starmark.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference

private object SilentChildHaptics : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

/** The parent callback owns this choice's feedback for both row and child targets. */
@Composable
internal fun WithoutChildHaptics(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHapticFeedback provides SilentChildHaptics, content = content)
}

/** Miuix 0.9.4 CheckboxPreference only vibrates its checkbox, not the surrounding row. */
@Composable
internal fun HapticCheckboxPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    allowUncheck: Boolean = true,
    checkboxLocation: CheckboxLocation = CheckboxLocation.Start,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
) {
    val haptics = LocalHapticFeedback.current
    WithoutChildHaptics {
        CheckboxPreference(title = title, checked = checked, onCheckedChange = { next ->
            if (enabled && next != checked && (next || allowUncheck)) {
                haptics.performHapticFeedback(toggleHaptic(checked))
                onCheckedChange(next)
            }
        }, modifier = modifier, enabled = enabled, checkboxLocation = checkboxLocation, insideMargin = insideMargin)
    }
}
