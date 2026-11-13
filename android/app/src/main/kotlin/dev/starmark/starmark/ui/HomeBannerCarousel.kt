package dev.starmark.starmark.ui

import android.graphics.Typeface
import android.view.ViewConfiguration
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

private val bannerTitles = listOf("继续编辑", "添加水印", "批量编辑")
private object BannerTypeface {
    @Volatile var family: FontFamily? = null
}

/** Three retained image layers; pointer updates only transform layers, never bridge state. */
@Composable
internal fun HomeBannerCarousel(state: Map<*, *>, command: UiCommand, selectedPage: MutableIntState) {
    val context = LocalContext.current
    val effects = LocalStarMarkEffects.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val interactions = remember { List(3) { MutableInteractionSource() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val family by produceState(BannerTypeface.family, context) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching { FontFamily(Typeface.createFromAsset(context.assets, "flutter_assets/assets/fonts/Qinghe.otf")) }
                .getOrDefault(FontFamily.SansSerif).also { BannerTypeface.family = it }
        }
    }
    val styles = state.rows("styles")
    val previews = remember(styles) { styles.filter { it.flag("factory") }.map { it.text("image") }.filter { it.isNotBlank() }.distinct() }
    var preview by remember { mutableStateOf("") }
    val resume = state.obj("homeResume")
    var page by selectedPage
    val position = remember { mutableFloatStateOf(page.toFloat()) }
    var moving by remember { mutableStateOf(false) }
    var motion by remember { mutableStateOf<Job?>(null) }
    var startPage by remember { mutableIntStateOf(1) }
    var draggedPx by remember { mutableFloatStateOf(0f) }
    val active = resumed && state.text("page", "home") == "home" && !state.flag("busy")
    LaunchedEffect(previews, active, moving) {
        if (preview !in previews) preview = previews.randomOrNull().orEmpty()
        if (active && !moving && previews.size > 1) while (true) {
            delay(20_000)
            preview = previews.filter { it != preview }.random()
        }
    }
    val touch = LocalViewConfiguration.current
    val pagingTouch = remember(touch, context) {
        object : androidx.compose.ui.platform.ViewConfiguration by touch {
            override val touchSlop = ViewConfiguration.get(context).scaledPagingTouchSlop.toFloat()
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("home.bannerPager")) {
        val cardSize = maxWidth * (890f / 1200f)
        val stride = cardSize + maxWidth * (10f / 1200f)
        val stridePx = with(density) { stride.toPx() }
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        fun settle(target: Int, velocity: Float = 0f) {
            motion?.cancel()
            val destination = target.coerceIn(0, 2)
            page = destination
            motion = scope.launch {
                moving = true
                val duration = if (effects.reduceMotion) 0 else CalendarPagerMotion.duration((destination - position.floatValue) * stridePx, stridePx, velocity)
                if (duration == 0) position.floatValue = destination.toFloat()
                else animate(position.floatValue, destination.toFloat(), animationSpec = tween(duration, easing = Easing(CalendarPagerMotion::ease))) { value, _ -> position.floatValue = value }
                // A cancelled settle must not clear the next drag's moving flag.
                moving = false
            }
        }
        // Outside the view or tab, stop rendering animation frames and keep a settled page.
        LaunchedEffect(active) { if (!active) { motion?.cancel(); position.floatValue = page.toFloat(); moving = false } }
        DisposableEffect(Unit) { onDispose { motion?.cancel() } }
        CompositionLocalProvider(LocalViewConfiguration provides pagingTouch) {
            Box(Modifier.fillMaxWidth().height(cardSize + 16.dp).clipToBounds()
                .semantics {
                    stateDescription = "${bannerTitles[page]}，${page + 1}/3"
                    horizontalScrollAxisRange = ScrollAxisRange({ position.floatValue }, { 2f })
                    scrollBy { x, _ -> settle(page + if (x > 0) 1 else -1); true }
                }
                .draggable(rememberDraggableState { delta ->
                    draggedPx += delta
                    position.floatValue = (position.floatValue - delta / stridePx).coerceIn(maxOf(0, startPage - 1).toFloat(), minOf(2, startPage + 1).toFloat())
                }, Orientation.Horizontal, enabled = active,
                    startDragImmediately = moving,
                    onDragStarted = { motion?.cancel(); moving = true; startPage = page; draggedPx = 0f },
                    onDragStopped = { velocity -> settle(CalendarPagerMotion.target(startPage, position.floatValue, draggedPx, velocity, density.density), velocity) }),
                contentAlignment = Alignment.Center,
            ) {
                repeat(3) { index ->
                    val background = when (index) {
                        0 -> resume.text("image").ifBlank { "assets/home/background2.webp" }
                        1 -> "assets/home/background1.webp"
                        else -> "assets/home/background3.webp"
                    }
                    val fixed = resume.text("watermarkPreview").ifBlank { homeResumePreview(styles) }
                    val thumbnail = if (index == 0 && resume.text("image").isNotBlank()) fixed else preview
                    Box(Modifier.size(cardSize).graphicsLayer {
                        val transform = homeBannerCardTransform(viewportWidthPx, index, position.floatValue)
                        scaleX = transform.scale
                        scaleY = transform.scale
                        translationX = transform.translationX
                    }.then(if (index != page) Modifier.clearAndSetSemantics {} else Modifier)
                        .testTag("home.banner.$index")) {
                        HomeBannerCard(index, background, thumbnail, family ?: FontFamily.SansSerif, interactions[index],
                            enabled = active && index == page && !moving,
                            bannerWidthDp = cardSize.value,
                            decodeEdge = with(density) { cardSize.toPx().roundToInt().coerceAtMost(1200) },
                        ) {
                            when (index) {
                                0 -> if (resume.text("draftId").isNotBlank()) command.send("draft.restore", "id" to resume.text("draftId")) else command.send("navigate", "page" to "editor")
                                1 -> command.send("photo.import")
                                else -> command.send("navigate", "page" to "batch")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeBannerCard(index: Int, background: String, thumbnail: String, family: FontFamily, interaction: MutableInteractionSource, enabled: Boolean, bannerWidthDp: Float, decodeEdge: Int, onClick: () -> Unit) {
    val effects = LocalStarMarkEffects.current
    val backdrop = rememberLayerBackdrop()
    val glass = effects.glass && isRuntimeShaderSupported()
    val frosted = effects.mode == GlassMode.Frosted
    val shape = MiuixCornerShape(22.dp)
    val footer = if (MiuixTheme.colorScheme.background.luminance() < .5f) MiuixTheme.colorScheme.background else Color.White
    val surface = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize().then(if (glass) Modifier.bannerGlassBloom(frosted, MiuixTheme.colorScheme.background.luminance() < .5f) else Modifier).clip(shape)) {
        Box(Modifier.fillMaxSize().then(if (glass) Modifier.layerBackdrop(surface) else Modifier)) {
            PhotoThumbnail(background, Modifier.fillMaxSize().then(if (glass) Modifier.layerBackdrop(backdrop) else Modifier), cornerRadius = 0.dp, decodeEdge = decodeEdge)
            Box(Modifier.fillMaxWidth().height(96.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .24f), Color.Transparent))))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(128.dp)
                .then(if (glass) Modifier.progressiveTextureBlur(backdrop = backdrop, shape = RectangleShape, blurRadius = 32f, gradient = ProgressiveBlur.Bottom.copy(startFraction = 0f, curve = 1f)) else Modifier)
                .background(Brush.verticalGradient(listOf(footer.copy(alpha = 0f), footer.copy(alpha = .35f), footer.copy(alpha = .92f)))))
        }
        if (glass) Box(Modifier.matchParentSize().bannerGlassEdge(surface, frosted))
        Text(bannerTitles[index], Modifier.align(Alignment.TopStart).padding(20.dp), fontFamily = family, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal, color = Color.White, maxLines = 1)
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            HomeBannerPreview(thumbnail, bannerWidthDp, Modifier.width(72.dp).height(57.6.dp).testTag("home.preview.$index"))
            CompositionLocalProvider(LocalChromeBackdrop provides backdrop) {
                val tag = listOf("home.continue", "photo.import", "home.batch")[index]
                Box(Modifier.size(56.dp).testTag(tag)
                    .semantics { contentDescription = bannerTitles[index] }
                    .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = hapticClick(onClick = onClick))) {
                    // The material, blue fill and icon are real children of this card.
                    // Their position is owned solely by the card's layer and footer Row.
                    HomeBannerActionVisual(index, interaction, enabled)
                }
            }
        }
    }
}

/** Clip the image bounds, not a square letterbox: official previews are 5:4. */
@Composable
private fun HomeBannerPreview(path: String, bannerWidthDp: Float, modifier: Modifier) {
    val context = LocalContext.current.applicationContext
    val effects = LocalStarMarkEffects.current
    val bitmap by produceState<ImageBitmap?>(null, path) {
        // Keep the previous pixels until the next preview is decoded; no blank flash.
        value = ThumbnailCache.load(context, path, 256)
    }
    Crossfade(bitmap, modifier, animationSpec = tween(if (effects.reduceMotion) 0 else 500), label = "home.preview") { image ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            if (image != null) {
                val ratio = image.width.toFloat() / image.height
                val width = minOf(72f, 57.6f * ratio)
                val height = width / ratio
                Image(image, "水印预览", Modifier.size(width.dp, height.dp).clip(MiuixCornerShape((width * 22f / bannerWidthDp).dp)), contentScale = ContentScale.Fit)
            } else Box(Modifier.size(57.6.dp).clip(MiuixCornerShape(5.5.dp)).background(Color.White.copy(alpha = .4f)), contentAlignment = Alignment.Center) {
                Icon(MiuixIcons.Image, "无水印", tint = Color(0xFF747474), modifier = Modifier.size(28.dp))
            }
        }
    }
}


@Composable
private fun HomeBannerActionVisual(index: Int, interaction: MutableInteractionSource, enabled: Boolean) {
    val effects = LocalStarMarkEffects.current
    // A vendor BlurView cannot safely enter the page recorder. Substitute only this
    // action's material with the existing live Compose glass backend; retain mode,
    // refraction/highlight, tint, and the common card-local placement of all pixels.
    CompositionLocalProvider(LocalStarMarkEffects provides effects.copy(systemGlassSupported = false)) {
        ChromeSurface(Modifier.fillMaxSize().stockPress(interaction, StockPressKind.Banner, enabled).testTag("home.actionVisual.$index"), cornerRadius = 28.dp, role = ChromeMaterialRole.HeaderButton) {
            Box(Modifier.fillMaxSize().then(if (effects.glass) Modifier.glassActionPaint(Color(0xFF3482FF), 28.dp, MiuixTheme.colorScheme.background.luminance() < .5f, enabled = enabled, frosted = effects.mode == GlassMode.Frosted) else Modifier.clip(CircleShape).background(Color(0xFF3482FF).copy(alpha = if (effects.glass) .64f else 1f))), contentAlignment = Alignment.Center) {
                Icon(when (index) { 0 -> MiuixIcons.Edit; 1 -> MiuixIcons.Add; else -> MiuixIcons.Image }, null,
                    Modifier.fillMaxSize(24f / 56f), tint = Color.White)
            }
        }
    }
}
