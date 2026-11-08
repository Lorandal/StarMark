package dev.starmark.starmark.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalStartupProgress = staticCompositionLocalOf { 1f }

/** Initial engine/catalog preparation owns the entire shell, including chrome. */
@Composable
internal fun StartupReveal(ready: Boolean, message: String, content: @Composable () -> Unit) {
    var mounted by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val reduced = LocalStarMarkEffects.current.reduceMotion
    LaunchedEffect(ready, reduced) {
        if (ready && !mounted) mounted = true
        if (mounted && progress.value < 1f) {
            if (reduced) progress.snapTo(1f) else {
                withFrameNanos { }
                progress.animateTo(1f, tween(420, easing = CubicBezierEasing(.22f, .61f, .36f, 1f)))
            }
        }
    }
    BackHandler(!mounted) { /* Startup is not a navigable page. */ }
    Box(Modifier.fillMaxSize()) {
        if (mounted) CompositionLocalProvider(LocalStartupProgress provides progress.value) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }
                .then(if (progress.value < 1f) Modifier.clearAndSetSemantics {} else Modifier)) { content() }
        }
        if (progress.value < 1f) {
            Box(Modifier.fillMaxSize().testTag("screen.loading")
                .graphicsLayer { alpha = (1f - progress.value * 2f).coerceAtLeast(0f) }
                .background(MiuixTheme.colorScheme.background)
                .pointerInput(Unit) { awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                } }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    StarMarkLogo(Modifier.width(200.dp))
                    if (message.isBlank()) CircularProgressIndicator() else Text(message, Modifier.padding(horizontal = 28.dp))
                }
            }
        }
    }
}
