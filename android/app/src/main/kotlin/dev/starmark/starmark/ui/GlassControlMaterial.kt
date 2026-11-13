package dev.starmark.starmark.ui

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButtonColors
import top.yukonga.miuix.kmp.squircle.rememberSquircleSurfaceShape
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

// Child surfaces inherit the material of their enclosing chrome.
internal val LocalGlassContainer = staticCompositionLocalOf { false }

@Composable
internal fun glassNestedFill(color: Color): Color =
    if (LocalGlassContainer.current && LocalStarMarkEffects.current.glass)
        color.copy(alpha = if (MiuixTheme.colorScheme.background.luminance() < .5f) .30f else .24f)
    else color

@Composable
internal fun glassCardColors() = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
    color = glassNestedFill(MiuixTheme.colorScheme.surfaceContainer))

@Composable
internal fun glassTextFieldColors() = top.yukonga.miuix.kmp.basic.TextFieldDefaults.textFieldColors(
    backgroundColor = glassNestedFill(MiuixTheme.colorScheme.secondaryContainer))

@Composable
internal fun glassTabColors() = top.yukonga.miuix.kmp.basic.TabRowDefaults.tabRowColors(
    backgroundColor = glassNestedFill(MiuixTheme.colorScheme.surface),
    selectedBackgroundColor = glassNestedFill(MiuixTheme.colorScheme.surfaceContainer))

/** Reuses the parent's already blurred scene. Child controls never register
 * another backdrop or native blur View, preventing recursive/double sampling. */
internal fun Modifier.glassActionPaint(color: Color, radius: Dp, dark: Boolean, pressed: Boolean = false, enabled: Boolean = true, shape: Shape = MiuixCornerShape(radius), frosted: Boolean = false): Modifier {
    val chroma = maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue)
    val colored = chroma > .18f
    // A reflection keeps the action's hue; cyan/peach outlines discolored other accents.
    val edge = if (colored) lerp(color.copy(alpha = 1f), Color.White, .48f) else Color.White
    return clip(shape).drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val neutralBody = if (dark) color else Color(0xFF858B94)
        val fill = if (colored) Brush.verticalGradient(listOf(
            color.copy(alpha = color.alpha * if (!enabled) .72f else if (pressed) .99f else .97f),
            color.copy(alpha = color.alpha * if (!enabled) .64f else if (pressed) .97f else .93f),
        )) else Brush.verticalGradient(listOf(
            neutralBody.copy(alpha = (if (pressed) .46f else .40f) * if (enabled) 1f else .6f),
            neutralBody.copy(alpha = (if (pressed) .40f else .34f) * if (enabled) 1f else .6f),
        ))
        val face = Brush.verticalGradient(listOf(
            Color.White.copy(alpha = if (colored) .025f else if (dark) .035f else .06f),
            Color.White.copy(alpha = 0f),
            Color.White.copy(alpha = if (colored) .012f else if (dark) .04f else .02f),
        ))
        val rim = Brush.linearGradient(listOf(
            edge.copy(alpha = .85f),
            edge.copy(alpha = .22f),
            edge.copy(alpha = .55f),
        ))
        // Subpixel-spaced coverage approximates a continuous Gaussian falloff along
        // the actual G2 outline, without the two hard inner bands of stacked borders.
        var coverage = 0f
        val feather = List(20) { index ->
            val distance = 3f * (20 - index) / 20f
            val next = (if (colored) .48f else .58f) *
                kotlin.math.exp(-.5f * (distance / .8f) * (distance / .8f))
            val alpha = (next - coverage) / (1f - coverage)
            coverage = next
            Stroke((distance * 2f).dp.toPx()) to alpha
        }
        onDrawWithContent {
            drawOutline(outline, fill)
            drawOutline(outline, face)
            val rimStrength = (if (!enabled) .22f else if (pressed) .8f else 1f) * if (frosted) .25f else 1f
            feather.forEach { (stroke, alpha) ->
                drawOutline(outline, rim, alpha = alpha * rimStrength, style = stroke)
            }
            drawContent()
        }
    }
}

@Composable
internal fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = ButtonDefaults.CornerRadius,
    minWidth: Dp = ButtonDefaults.MinWidth,
    minHeight: Dp = ButtonDefaults.MinHeight,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    insideMargin: PaddingValues = ButtonDefaults.InsideMargin,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current,
    glassMaterial: Boolean = LocalGlassContainer.current,
    content: @Composable RowScope.() -> Unit,
) {
    val active = glassMaterial && LocalStarMarkEffects.current.glass
    if (!active || colors.color.alpha == 0f) {
        top.yukonga.miuix.kmp.basic.Button(onClick, modifier, enabled, cornerRadius, minWidth, minHeight,
            colors, insideMargin, interactionSource, indication, content)
        return
    }
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dark = MiuixTheme.colorScheme.background.luminance() < .5f
    val fill = if (enabled) colors.color else colors.disabledColor
    val foreground = if (enabled) colors.contentColor else colors.disabledContentColor
    CompositionLocalProvider(LocalContentColor provides foreground) {
        Box(modifier.semantics { role = Role.Button }
            .glassActionPaint(fill, cornerRadius, dark, pressed && enabled, enabled = enabled,
                shape = rememberSquircleSurfaceShape(cornerRadius), frosted = LocalStarMarkEffects.current.mode == GlassMode.Frosted)
            .clickable(interaction, indication, enabled, role = Role.Button, onClick = onClick),
            propagateMinConstraints = true) {
            Row(Modifier.defaultMinSize(minWidth, minHeight).padding(insideMargin),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                content = content)
        }
    }
}

@Composable
internal fun TextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = ButtonDefaults.CornerRadius,
    minWidth: Dp = ButtonDefaults.MinWidth,
    minHeight: Dp = ButtonDefaults.MinHeight,
    colors: TextButtonColors = ButtonDefaults.textButtonColors(),
    insideMargin: PaddingValues = ButtonDefaults.InsideMargin,
    textStyle: TextStyle? = null,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current,
) {
    Button(onClick, modifier, enabled, cornerRadius, minWidth, minHeight,
        ButtonColors(colors.color, colors.disabledColor, colors.textColor, colors.disabledTextColor),
        insideMargin, interactionSource, indication) { Text(text, style = textStyle ?: MiuixTheme.textStyles.button) }
}
