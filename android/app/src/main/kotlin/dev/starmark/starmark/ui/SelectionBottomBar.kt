package dev.starmark.starmark.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Both libraries use the same interrupted/reversed FileExplorer motion. */
@Composable
internal fun rememberBottomBarFrame(actions: Boolean, visible: Boolean): BottomBarFrame {
    val reduceMotion = LocalStarMarkEffects.current.reduceMotion
    val motion = remember { BottomBarMotion(actions, visible) }
    var frame by remember { mutableStateOf(motion.frame) }
    LaunchedEffect(actions, visible, reduceMotion) {
        motion.retarget(actions, visible, immediate = reduceMotion)
        frame = motion.frame
        var previous = withFrameNanos { it }
        while (motion.running) {
            val now = withFrameNanos { it }
            motion.step(((now - previous) / 1_000_000_000f).coerceAtMost(.064f))
            previous = now
            frame = motion.frame
        }
    }
    return frame
}

@Composable
internal fun FloatingActionSurface(frame: BottomBarFrame, width: Dp, height: Dp, radius: Dp,
    showActions: Boolean, enabled: Boolean, visible: Boolean, tagPrefix: String,
    content: @Composable (Boolean) -> Unit) {
    if (frame.actionsAlpha <= 0f) return
    val interactive = enabled && visible && showActions && frame.actionsAlpha >= .99f
    val effects = LocalStarMarkEffects.current
    ChromeSurface(
        modifier = Modifier.width(width).height(height)
            .testTag(if (showActions) "$tagPrefix.container" else "$tagPrefix.outgoing")
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.ModulateAlpha
                alpha = frame.actionsAlpha
                translationY = frame.actionsOffsetDp.dp.toPx()
            },
        cornerRadius = radius, role = ChromeMaterialRole.Navigation,
        shadowElevation = if (effects.glass) 8.dp else 0.dp,
    ) {
        MiuiSelfBlurHost(radius = 50f * (1f - frame.actionsAlpha), accessible = interactive,
            modifier = Modifier.fillMaxSize().testTag("$tagPrefix.actionsSurface")
                .then(if (interactive) Modifier else Modifier.clearAndSetSemantics {})) { content(interactive) }
    }
}

@Composable
internal fun WatermarkSelectionBar(state: Map<*, *>, command: UiCommand, importTag: String,
    onDelete: (List<String>) -> Unit) {
    val selection = state.obj("watermarkSelection")
    val selecting = selection.flag("active")
    val enabled = state.editable()
    val frame = rememberBottomBarFrame(selecting, true)
    val effects = LocalStarMarkEffects.current
    val dark = MiuixTheme.colorScheme.background.luminance() < .5f
    val density = LocalDensity.current
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val underlay = remember(dark) {
        val bottom = if (dark) Color(0xf5000000) else Color(0xf5f3f3f3)
        Brush.verticalGradient(listOf(bottom.copy(alpha = 0f), bottom))
    }
    BoxWithConstraints(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))) {
        val metrics = floatingNavigationMetrics(maxWidth.value, density.fontScale, 4)
        Column(Modifier.fillMaxWidth()
            .then(if (effects.systemGlass) Modifier.drawBehind { drawRect(underlay) } else Modifier)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .padding(top = if (effects.systemGlass) 20.dp else 8.dp,
                bottom = navigationBottomMargin(bottomInset.value).dp - bottomInset)) {
            Box(Modifier.fillMaxWidth().height(metrics.height.dp), contentAlignment = Alignment.Center) {
                if (frame.navigationAlpha > 0f) {
                    val interactive = enabled && !selecting && frame.navigationAlpha >= .99f
                    MiuiSelfBlurHost(radius = 50f * (1f - frame.navigationAlpha), accessible = interactive,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)
                            .testTag("watermark.navigationSurface").graphicsLayer {
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                                alpha = frame.navigationAlpha
                                scaleX = frame.navigationScale; scaleY = frame.navigationScale
                            }.then(if (interactive) Modifier else Modifier.clearAndSetSemantics {})) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            ChromeTextButton("导入水印文件", importTag, interactive) { command.send("style.import") }
                        }
                    }
                }
                FloatingActionSurface(frame, metrics.width.dp, metrics.height.dp, metrics.radius.dp,
                    selecting, enabled, true, "watermark") { interactive ->
                    fun selectedIds() = styleRows(state).filter { it.flag("checked") }.map { it.text("id") }
                    SelectionActions(interactive, selection.integer("selectedCount") > 0, selection.flag("allSelected"),
                        onCancel = { command.send("watermark.selection.cancel") },
                        onToggleAll = { command.send("watermark.selection.all", "selected" to !selection.flag("allSelected")) },
                        onDelete = { onDelete(selectedIds()) },
                        onExport = { command.send("watermark.export", "ids" to selectedIds()) },
                        canDelete = selection.flag("canDelete"))
                }
            }
        }
    }
}
