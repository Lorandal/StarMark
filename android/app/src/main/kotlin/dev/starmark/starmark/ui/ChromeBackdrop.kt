package dev.starmark.starmark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Records only the scrolling native page, never chrome, popups or the Flutter photo layer. */
internal val LocalChromeBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** One recorder owns a pane through body transitions; floating chrome is always its sibling. */
@Composable
internal fun ChromePane(
    modifier: Modifier = Modifier,
    systemGlassLocalFallbackPossible: Boolean = true,
    body: @Composable BoxScope.() -> Unit,
    chrome: @Composable BoxScope.() -> Unit,
) {
    val effects = LocalStarMarkEffects.current
    val record = backdropRecordingNeeded(effects.glass, effects.systemGlass, isRuntimeShaderSupported(), systemGlassLocalFallbackPossible)
    val backdrop = rememberLayerBackdrop()
    CompositionLocalProvider(LocalChromeBackdrop provides backdrop) {
        Box(modifier) {
            Box(Modifier.fillMaxSize().then(if (record) Modifier.layerBackdrop(backdrop) else Modifier), content = body)
            chrome()
        }
    }
}

/** CTA and icon shells consume the same live page source as the floating navigation. */
@Composable
internal fun ChromeTextButton(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxWidth().height(52.dp).testTag(tag).clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = hapticClick(onClick = onClick))) {
        ChromeSurface(Modifier.fillMaxSize().stockPress(interaction, StockPressKind.Banner, enabled), cornerRadius = 26.dp) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text, fontSize = 17.sp, color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface)
            }
        }
    }
}

/** Padding belongs to scrollable items so they can travel underneath both bars. */
@Composable
internal fun PaddingValues.withChromeInsets(insets: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction) + insets.calculateStartPadding(direction),
        end = calculateEndPadding(direction) + insets.calculateEndPadding(direction),
        top = calculateTopPadding() + insets.calculateTopPadding(),
        bottom = calculateBottomPadding() + insets.calculateBottomPadding(),
    )
}

/** A screen-aligned live material, clipped to the capsule; the outside remains transparent. */
@Composable
internal fun ChromeSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp,
    shadowElevation: Dp = 4.dp,
    role: ChromeMaterialRole = ChromeMaterialRole.Standard,
    strength: Float = 1f,
    content: @Composable () -> Unit,
) {
    val effects = LocalStarMarkEffects.current
    if (effects.systemGlass) {
        MiuiBlurSurface(modifier = modifier, cornerRadius = cornerRadius, shadowElevation = shadowElevation,
            blurRadius = when (role) {
                ChromeMaterialRole.HeaderButton -> 20.dp
                ChromeMaterialRole.Navigation -> ChromeMaterialRecipe.navigationBlurRadiusDp.dp
                ChromeMaterialRole.Modal, ChromeMaterialRole.Popup -> ChromeMaterialRecipe.modalBlurRadiusDp.dp
                ChromeMaterialRole.Standard -> ChromeMaterialRecipe.nativeBlurRadiusDp.dp
            },
            role = role, strength = strength, content = content)
        return
    }
    val backdrop = LocalChromeBackdrop.current
    val dark = MiuixTheme.colorScheme.background.luminance() < .5f
    val glass = effects.glass && backdrop != null && isRuntimeShaderSupported()
    val portable = portableMaterialBackend(effects.glass, effects.systemGlass, backdrop != null,
        isRuntimeShaderSupported(), LocalPortableGlassEnabled.current) == PortableMaterialBackend.Haze
    val navigation = role == ChromeMaterialRole.Navigation
    val shape = MiuixCornerShape(cornerRadius)
    val material = if (glass) Modifier.textureBlur(
        backdrop = requireNotNull(backdrop), shape = shape, blurRadius = if (role == ChromeMaterialRole.Modal || role == ChromeMaterialRole.Popup) ChromeMaterialRecipe.modalBlurRadiusDp else ChromeMaterialRecipe.blurRadiusDp,
        colors = BlurDefaults.blurColors((if (navigation || role == ChromeMaterialRole.HeaderButton) ChromeMaterialRecipe.glass(dark) else ChromeMaterialRecipe.frosted(dark)).map {
            BlendColorEntry(Color(it.argb), BlurBlendMode(it.mode))
        }),
    ) else Modifier.background(Color(if (navigation) ChromeMaterialRecipe.navigationSolid(dark) else ChromeMaterialRecipe.solid(dark)), shape)
    // The portable renderer samples the live page. Only the capsule's narrow rim catches light.
    val rim = if ((navigation || portable) && glass) Modifier.border(1.dp, Brush.verticalGradient(listOf(
        Color.White.copy(alpha = if (dark) { if (portable) .38f else .14f } else .80f),
        Color.White.copy(alpha = if (dark) .035f else .16f),
        Color.White.copy(alpha = if (dark) { if (portable) .26f else .095f } else .54f),
    )), shape) else Modifier
    // Surface's default black ambient/spot shadows remain visible even with effects
    // disabled. Keep the existing outline/elevation, but use a quieter shadow in
    // each theme. Own this single shadow layer so Surface cannot add another one.
    val ambientAlpha = if (effects.glass) { if (dark) .40f else .24f } else { if (dark) .24f else .12f }
    val spotAlpha = if (effects.glass) { if (dark) .48f else .32f } else { if (dark) .30f else .18f }
    val shell = modifier
        .then(if (role == ChromeMaterialRole.HeaderButton) Modifier.graphicsLayer { alpha = strength } else Modifier)
        .shadow(shadowElevation, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = ambientAlpha),
            spotColor = Color.Black.copy(alpha = spotAlpha))
    Surface(shell, shape = shape, color = Color.Transparent, shadowElevation = 0.dp) {
        Box(rim.then(if (portable) Modifier else material), propagateMinConstraints = true) {
            if (portable) Box(Modifier.matchParentSize()) { PortableGlassLayer(requireNotNull(backdrop), cornerRadius, dark, material) }
            content()
        }
    }
}

/** Keep text sharp; blur and tint fade to the untouched scrolling page at the lower edge. */
@Composable
internal fun ChromeTopBar(settingsPane: Boolean = false, content: @Composable () -> Unit) {
    val backdrop = LocalChromeBackdrop.current
    val effects = LocalStarMarkEffects.current
    val glass = effects.glass && backdrop != null && isRuntimeShaderSupported()
    val background = MiuixTheme.colorScheme.background
    // The vendor gradient setter returns success on OS4.0.5.0, then its Adreno
    // Vulkan shader fails during real drawing (SIGSEGV). Keep top gradients on
    // Miuix's tested live backdrop; native capsule glass remains independent.
    // Gallery 5.4.2.29 keeps the underlying scene's hue/luminance and clears the
    // gradient just below the compact toolbar. The old .44 page-color blend
    // washed out photos; normalizing against the expandable title also stretched
    // the blur during collapse. A fixed band avoids both changes in appearance.
    val bandHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
    // Match Miuix TopAppBar's hit policy, including our SVG brand header.
    Box(Modifier.pointerInput(Unit) { detectTapGestures { /* Consume background taps. */ } }) {
        // The Settings fade continues below its 56dp toolbar without reserving list space
        // or intercepting touches in that tail. Photo/home chrome has its own profile.
        val band = if (settingsPane) Modifier.matchParentSize().layout { measurable, bounds ->
            val fade = measurable.measure(Constraints.fixed(bounds.maxWidth, bounds.maxHeight + 24.dp.roundToPx()))
            layout(bounds.maxWidth, bounds.maxHeight) { fade.place(0, 0) }
        } else Modifier.fillMaxWidth().height(bandHeight)
        if (glass) Box(band.testTag("header.gradient")
            .headerBackdropFeather()
            .progressiveTextureBlur(
                backdrop = requireNotNull(backdrop), shape = RectangleShape, blurRadius = 32f,
                gradient = ProgressiveBlur.Top.copy(startFraction = 0f, curve = 1f),
            ).then(if (settingsPane) Modifier.background(Brush.verticalGradient(
                0f to background, .4f to background, .56f to background.copy(alpha = .94f),
                .67f to background.copy(alpha = .82f), .82f to background.copy(alpha = .48f),
                1f to background.copy(alpha = 0f),
            )) else Modifier))
        else Box(Modifier.matchParentSize().background(background))
        content()
    }
}

/** Fade only the replacement scene in the low-radius end of the progressive band.
 * Native underlay cannot be recorded safely; zero replacement opacity converges
 * to its real visible pixels without fading any toolbar foreground.
 */
internal fun Modifier.headerBackdropFeather(): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithContent {
    drawContent()
    drawRect(Brush.verticalGradient(
        0f to Color.White, .75f to Color.White,
        .8125f to Color.White.copy(alpha = headerBackdropOpacity(.8125f)),
        .875f to Color.White.copy(alpha = headerBackdropOpacity(.875f)),
        .9375f to Color.White.copy(alpha = headerBackdropOpacity(.9375f)),
        1f to Color.Transparent,
    ), blendMode = BlendMode.DstIn)
}
