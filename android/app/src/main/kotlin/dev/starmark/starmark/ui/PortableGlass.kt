package dev.starmark.starmark.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import top.yukonga.miuix.kmp.blur.Backdrop
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.*
import dev.chrisbanes.haze.glass.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalPortableGlassEnabled = staticCompositionLocalOf { true }
// Haze's sampling padding is blur + displacement * strength + edge softness:
// 11 + 8 * .45 + 1 = 15.6dp at this material's widest (light) setting.
private val PortableSourceOutset = 16.dp
internal fun portableInput(state: HazeState) = HazeInput.Sources(state, retention = HazeSourceRetention.ClearWhenUnavailable)

@Composable
internal fun portableGlassMaterial(input: HazeInput.Sources, radius: Dp, dark: Boolean): Modifier {
    val style = remember(radius, dark) {
        GlassStyle.regular.then {
            // Regular follows host uiMode; StarMark's explicit app theme can differ.
            // Override every theme-dependent Regular property after replaying the preset.
            specularIntensity(if (dark) .50f else .65f)
            edgeShadow(Color.Black.copy(alpha = if (dark) .07f else .03f))
            ambientResponse(if (dark) .08f else .12f)
            contrast(PortableGlassRecipe.contrast(dark))
            whitePoint(PortableGlassRecipe.whitePoint(dark))
            chromaMultiplier(PortableGlassRecipe.chroma)
            shape(RoundedCornerShape(radius))
            // Neutral material tint retains the native surface's luminance floor
            // over an empty dark page, while the graded source stays visible.
            // Pure black/white tint matched the grid but missed flat backgrounds.
            tint(Color(PortableGlassRecipe.tint(dark)).copy(alpha = PortableGlassRecipe.tintAlpha))
            optics(GlassOptics.regular.copy(
                // Match the new native material's broad softened detail without
                // adding sharp source lines back over the blur. Keep only a small
                // edge displacement; it must not magnify the whole component.
                depth = OpticalSizeValue.Fixed(1f),
                blurRadius = OpticalSizeValue.Fixed(PortableGlassRecipe.blurDp(dark).dp),
                refractionDisplacement = 8.dp,
                refractionStrength = .45f,
                refractionProfile = RefractionProfile.Edge(PortableGlassRecipe.edgeBandDp.dp),
                refractionDetailIntensity = 0f,
            ))
            chromaticAberrationStrength(0f)
        }
    }
    return Modifier.hazeGlass(input = input, style = style, performanceMode = HazePerformanceMode.Balanced)
}

/** The existing scoped recorder remains the authority. This local source only replays
 * that recorder; the consuming effect and sharp content are siblings, never captured.
 * Modal recorders already contain the single bounded Flutter frame composition. */
@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun PortableGlassLayer(backdrop: Backdrop, radius: Dp, dark: Boolean, fallback: Modifier) {
    var failed by remember(backdrop) { mutableStateOf(false) }
    if (failed) {
        Box(Modifier.fillMaxSize().then(fallback))
        return
    }
    val state = rememberHazeState()
    val input = remember(state) { portableInput(state) }
    val density = LocalDensity.current
    val background = MiuixTheme.colorScheme.background
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var recordingPosition by remember { mutableStateOf(Offset.Unspecified) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().onGloballyPositioned {
            coordinates = it
            recordingPosition = it.positionInWindow()
        }
            // Complete the scene in Haze's existing, control-sized source layer.
            // Replaying DstOver directly into the opaque window cannot fill holes
            // behind the feathered glass edge; this shared layer also backs it.
            .hazeSource(state, inputSharing = HazeInputSharing.Always,
                inputSharingOutsets = LayerOutsets(PortableSourceOutset, PortableSourceOutset,
                    PortableSourceOutset, PortableSourceOutset))
            .drawBehind {
                // LayoutCoordinates is reused when an ancestor moves. The replay
                // itself depends on its window position, so moving a dialog must
                // invalidate the recorded pixels even when source/effect stay aligned.
                if (recordingPosition.isSpecified) {
                    try { with(backdrop) { drawBackdrop(density, coordinates, null, 1) } }
                    catch (error: Exception) {
                        android.util.Log.w("StarMarkMaterial", "Portable source failed; using Miuix", error)
                        failed = true
                    }
                }
                // Haze preserves input alpha after tinting. Scoped recordings can
                // have holes while the sibling Flutter photo is unavailable, so
                // complete only their missing pixels before Haze grades the scene.
                // DstOver preserves opaque source pixels. The completed source is
                // drawn once, so the outer G2 clip gets no second AA edge fill.
                val outset = PortableSourceOutset.toPx()
                drawRect(background, topLeft = Offset(-outset, -outset),
                    size = Size(size.width + 2 * outset, size.height + 2 * outset),
                    blendMode = BlendMode.DstOver)
            })
        // Haze has its own renderer fallback. Do not run a second live blur on
        // every successful frame; our Miuix fallback is installed only on error.
        Box(Modifier.matchParentSize().drawWithContent {
            try { drawContent() } catch (error: Exception) {
                // Drop source/effect and install Miuix on the next composition.
                // Requested material mode is not changed.
                android.util.Log.w("StarMarkMaterial", "Portable UI draw failed; using Miuix", error)
                failed = true
            }
        }.then(portableGlassMaterial(input, radius, dark)))
    }
}
