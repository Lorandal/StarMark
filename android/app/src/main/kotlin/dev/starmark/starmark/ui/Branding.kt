package dev.starmark.starmark.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.starmark.starmark.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun StarMarkLogo(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.starmark_logo), "StarMark", modifier.aspectRatio(500f / 109.53f), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackground))
}

@Composable
internal fun BrandTopBar(state: TopAppBarState? = null) {
    val density = LocalDensity.current
    val expanded = 96f + (density.fontScale - 1f).coerceAtLeast(0f) * 16f
    val collapseRange = expanded - 60f
    val limit = with(density) { -collapseRange.dp.toPx() }
    SideEffect { state?.heightOffsetLimit = limit }
    val fraction = state?.collapsedFraction?.coerceIn(0f, 1f) ?: 0f
    Column(Modifier.fillMaxWidth().statusBarsPadding().height((expanded - collapseRange * fraction).dp).padding(start = 28.dp, end = 24.dp, top = (24f - 10f * fraction).dp).testTag("home.brandHeader")) {
        StarMarkLogo(Modifier.width((174f - 54f * fraction).dp))
    }
}
