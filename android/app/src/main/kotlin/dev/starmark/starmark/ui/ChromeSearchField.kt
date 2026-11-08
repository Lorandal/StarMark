package dev.starmark.starmark.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextField
import kotlin.math.abs

/** Inline adaptation of FileExplorer's SearchActionModeView; the editor stays mounted. */
@Composable
internal fun ChromeSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    label: String,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val effects = LocalStarMarkEffects.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val active = focused && enabled
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var keyboardWasVisible by remember { mutableStateOf(false) }
    fun close() { focus.clearFocus(force = true); keyboard?.hide() }
    LaunchedEffect(active, imeVisible) {
        if (!active) keyboardWasVisible = false
        else if (imeVisible) keyboardWasVisible = true
        else if (keyboardWasVisible) close()
    }
    BackHandler(active && !imeVisible) { close() }

    val geometry = remember { Animatable(0f) }
    val easing = remember { Easing(::stockSearchProgress) }
    LaunchedEffect(active, effects.reduceMotion) {
        val target = if (active) 1f else 0f
        if (effects.reduceMotion) geometry.snapTo(target)
        else geometry.animateTo(target, tween((400f * abs(target - geometry.value)).toInt(), easing = easing))
    }
    // SearchActionModeView: 100ms entrance delay, then 150ms alpha/self-blur.
    val backAlpha by animateFloatAsState(if (active) 1f else 0f,
        animationSpec = if (effects.reduceMotion) snap() else tween(150, if (active) 100 else 0, LinearEasing),
        label = "searchBackAlpha")
    val progress = geometry.value.coerceIn(0f, 1f)
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width((48f * progress).dp).height(48.dp).clipToBounds()) {
            if (active || progress > 0f) {
                MiuiSelfBlurHost(radius = 50f * (1f - backAlpha), accessible = active && backAlpha >= .99f,
                    modifier = Modifier.requiredSize(48.dp).graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = backAlpha
                        scaleX = progress; scaleY = progress
                        translationX = -direction * 48.dp.toPx() * (1f - progress)
                    }) {
                    IconButton({ close() }, Modifier.size(48.dp).testTag("$tag.cancel"), enabled = active) {
                        Icon(ReferenceIcons.Back, "收起搜索", Modifier.size(24.dp))
                    }
                }
            }
        }
        // One TextField preserves selection, composing text, original radius and height.
        // Query changes filter immediately and never restart the focus animation.
        TextField(query, onQueryChange, Modifier.weight(1f).testTag(tag), label = label,
            enabled = enabled, singleLine = true, interactionSource = interaction,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { close() }))
    }
}
