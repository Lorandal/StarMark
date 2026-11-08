package dev.starmark.starmark.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.*

/** Vendor background blur must share the Activity window with the Flutter texture. */
internal class ChromeOverlayState {
    data class Entry(val owner: Any, val content: @Composable () -> Unit)
    val entries = mutableStateListOf<Entry>()
    val entry: Entry? get() = entries.lastOrNull()

    fun show(owner: Any, content: @Composable () -> Unit) { entries.add(Entry(owner, content)) }
    fun remove(owner: Any) {
        val index = entries.indexOfFirst { it.owner === owner }
        // A menu may launch an independent dialog before its exit animation finishes.
        // Nested registrations dispose with their own composition; do not remove newer owners.
        if (index >= 0) entries.removeAt(index)
    }
}

private val LocalChromeOverlay = staticCompositionLocalOf<ChromeOverlayState?> { null }

@Composable
internal fun ChromeOverlayHost(content: @Composable () -> Unit) {
    val host = remember { ChromeOverlayState() }
    val focusManager = LocalFocusManager.current
    val pageFocus = remember { FocusRequester() }
    val effects = LocalStarMarkEffects.current
    // Never replay native blur Views into a captured scene: that shifts their sampling.
    val record = host.entry != null && effects.glass && !effects.systemGlass && isRuntimeShaderSupported()
    val backdrop = rememberModalBackdrop(record)
    LaunchedEffect(host.entry?.owner) {
        if (host.entry != null) focusManager.clearFocus(force = true)
        else pageFocus.requestFocus()
    }
    CompositionLocalProvider(LocalChromeOverlay provides host) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().focusRequester(pageFocus).focusRestorer().focusGroup()
                .then(if (record) Modifier.layerBackdrop(backdrop) else Modifier).then(if (host.entry != null)
                Modifier.focusProperties { canFocus = false }.clearAndSetSemantics {}
                else Modifier)) {
                content()
            }
            var sources = listOf<Backdrop>(backdrop)
            host.entries.toList().forEach { entry ->
                val previousSources = sources
                val captured = key(entry.owner) {
                    val captured = rememberLayerBackdrop()
                    val source = remember(previousSources) { StackedBackdrop(previousSources) }
                    val active = entry.owner === host.entry?.owner
                    val focus = remember { FocusRequester() }
                    Box(Modifier.fillMaxSize().scrollBoundaryHaptics().focusProperties {
                        canFocus = active
                        if (active) onExit = { cancelFocusChange() }
                    }.focusRequester(focus).focusRestorer().focusGroup().focusable()
                        .then(if (record && !active) Modifier.layerBackdrop(captured) else Modifier)
                        .then(if (active) Modifier else Modifier.clearAndSetSemantics {})) {
                        CompositionLocalProvider(LocalChromeBackdrop provides source, LocalChromeOverlayActive provides active) {
                            entry.content()
                        }
                    }
                    LaunchedEffect(active) { if (active) focus.requestFocus() }
                    captured
                }
                sources = sources + captured
            }
        }
    }
}

internal val LocalChromeOverlayActive = staticCompositionLocalOf { true }

/** Each popup samples the already drawn layers below it, never itself or its children. */
private class StackedBackdrop(private val layers: List<Backdrop>) : Backdrop {
    override val isCoordinatesDependent = true
    override val offsetResidualX get() = layers.first().offsetResidualX
    override val offsetResidualY get() = layers.first().offsetResidualY
    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?, downscaleFactor: Int) {
        layers.forEach { layer -> with(layer) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) } }
    }
}

@Composable
internal fun ChromeOverlay(content: @Composable () -> Unit) {
    val host = checkNotNull(LocalChromeOverlay.current) { "ChromeOverlay requires the Activity overlay host" }
    val owner = remember { Any() }
    val latestContent by rememberUpdatedState(content)
    DisposableEffect(host, owner) {
        host.show(owner) { latestContent() }
        onDispose { host.remove(owner) }
    }
}
