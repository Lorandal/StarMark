package dev.starmark.starmark.ui

import android.graphics.Typeface
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.lightColorScheme

internal data class StarMarkEffects(
    val mode: GlassMode = GlassMode.Frosted,
    val systemGlassSupported: Boolean = false,
    val reduceMotion: Boolean = false,
    val durationScale: Float = 1f,
) {
    val glass: Boolean get() = mode != GlassMode.Off
    val systemGlass: Boolean get() = mode == GlassMode.SystemGlass && systemGlassSupported
}
internal val LocalStarMarkEffects = staticCompositionLocalOf { StarMarkEffects() }
internal val LocalUiFontReady = staticCompositionLocalOf { true }

private object UiTypeface {
    @Volatile var family: FontFamily? = null
}

/** UI typography is independent of the photograph's selected watermark font. */
@Composable
internal fun StarMarkTheme(settings: Map<*, *>, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.text("theme", "system")) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    val context = LocalContext.current
    var capabilityRevision by remember(context) { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { capabilityRevision++ }
        }
        val resolver = context.contentResolver
        listOf(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            Settings.Secure.getUriFor("background_blur_enable"), Settings.Secure.getUriFor("material_style"))
            .forEach { uri -> runCatching { resolver.registerContentObserver(uri, false, observer) } }
        onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }
    val assets = context.applicationContext.assets
    val bundledFamily by produceState(UiTypeface.family, assets) {
        if (value == null) value = withContext(Dispatchers.IO) {
            synchronized(UiTypeface) {
                UiTypeface.family ?: runCatching {
                    FontFamily(Typeface.createFromAsset(assets, "flutter_assets/assets/fonts/MiSansVF.ttf"))
                }.getOrDefault(FontFamily.SansSerif).also { UiTypeface.family = it }
            }
        }
    }
    val family = bundledFamily ?: FontFamily.SansSerif
    val textStyles = remember(family) {
        val defaults = defaultTextStyles()
        defaults.copy(
            main = defaults.main.copy(fontFamily = family),
            paragraph = defaults.paragraph.copy(fontFamily = family),
            body1 = defaults.body1.copy(fontFamily = family),
            body2 = defaults.body2.copy(fontFamily = family),
            button = defaults.button.copy(fontFamily = family),
            footnote1 = defaults.footnote1.copy(fontFamily = family),
            footnote2 = defaults.footnote2.copy(fontFamily = family),
            headline1 = defaults.headline1.copy(fontFamily = family),
            headline2 = defaults.headline2.copy(fontFamily = family),
            subtitle = defaults.subtitle.copy(fontFamily = family),
            title1 = defaults.title1.copy(fontFamily = family),
            title2 = defaults.title2.copy(fontFamily = family),
            title3 = defaults.title3.copy(fontFamily = family),
            title4 = defaults.title4.copy(fontFamily = family),
        )
    }
    val colors = remember(dark) {
        if (dark) darkColorScheme(
            background = Color(0xFF101112), surface = Color(0xFF101112),
            surfaceContainer = Color(0xFF242527), surfaceContainerHigh = Color(0xFF303134),
            surfaceContainerHighest = Color(0xFF3A3B3F), surfaceVariant = Color(0xFF242527),
        ) else lightColorScheme(background = Color(0xFFF3F3F3))
    }
    val durationScale = remember(context, capabilityRevision) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            .takeIf { it.isFinite() && it >= 0f } ?: 1f
    }
    val systemGlassSupported = MiuiMaterialCapability.glassSupported(context)
    val mode = resolveGlassMode(settings["glassMode"] as? String, settings["glass"] as? Boolean, systemGlassSupported)
    CompositionLocalProvider(
        LocalUiFontReady provides (bundledFamily != null),
        LocalStarMarkEffects provides StarMarkEffects(mode, systemGlassSupported, durationScale == 0f || settings.flag("reduceMotion"), durationScale),
        LocalHapticFeedback provides rememberStarMarkHapticFeedback(),
    ) {
        MiuixTheme(colors = colors, textStyles = textStyles, content = content)
    }
}
