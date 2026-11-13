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
internal fun Modifier.glassActionPaint(color: Color, radius: Dp, dark: Boolean, pressed: Boolean = false, enabled: Boolean = true, shape: Shape = MiuixCornerShape(radius)): Modifier {
    val chroma = maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue)
    val colored = chroma > .18f
    val edge = when {
        !colored -> Color.White
        color.blue > color.red -> Color(0xFF3FDDFF)
        else -> Color(0xFFFFB199)
    }
    return clip(shape).drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        // Give light neutral actions a gray body, separate from the milky parent.
        // Keep the background visible and leave the existing dark material intact.
        val neutralBody = if (dark) color else Color(0xFF858B94)
        val fill = if (colored) Brush.verticalGradient(listOf(
            color.copy(alpha = color.alpha * if (pressed) .88f else .78f),
            color.copy(alpha = color.alpha * if (pressed) .82f else .69f),
        )) else Brush.verticalGradient(listOf(
            neutralBody.copy(alpha = if (dark) .40f else .36f),
            neutralBody.copy(alpha = if (dark) .29f else .30f),
        ))
        val face = Brush.verticalGradient(listOf(
            Color.White.copy(alpha = if (colored) .045f else if (dark) .035f else .10f),
            Color.Transparent,
            Color.White.copy(alpha = if (colored) .025f else if (dark) .06f else .04f),
        ))
        val rim = Brush.linearGradient(listOf(
            edge.copy(alpha = if (colored) .95f else if (dark) .47f else .95f),
            edge.copy(alpha = if (colored) .30f else if (dark) .08f else .22f),
            edge.copy(alpha = if (colored) .70f else if (dark) .28f else .70f),
        ))
        onDrawWithContent {
            drawOutline(outline, fill)
            drawOutline(outline, face)
            // Broad, low-opacity inner reflection, then a narrow luminous rim.
            // Both strokes are clipped to the unchanged G2/circular boundary.
            val rimStrength = if (enabled) 1f else .22f
            drawOutline(outline, rim, alpha = (if (pressed) .28f else .17f) * rimStrength, style = Stroke(5.dp.toPx()))
            drawOutline(outline, rim, alpha = rimStrength, style = Stroke(1.6.dp.toPx()))
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
                shape = rememberSquircleSurfaceShape(cornerRadius))
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
