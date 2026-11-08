package dev.starmark.starmark.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent as MiuixBasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownImpl as MiuixDropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Native Miuix choice rows hosted in an anchored glass menu. */
@Composable
internal fun ChromeDropdownPreference(
    items: List<String>,
    selectedIndex: Int,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    onSelectedIndexChange: ((Int) -> Unit)? = null,
) {
    ChromeSpinnerPreference(
        items = items.map { DropdownItem(it) },
        selectedIndex = selectedIndex,
        title = title,
        modifier = modifier,
        summary = summary,
        enabled = enabled,
        insideMargin = insideMargin,
        onExpandedChange = onExpandedChange,
        onDismissFinished = onDismissFinished,
        onSelectedIndexChange = onSelectedIndexChange,
    )
}

@Composable
internal fun ChromeSpinnerPreference(
    items: List<DropdownItem>,
    selectedIndex: Int,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    dialogButtonString: String? = null,
    enabled: Boolean = true,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    onSelectedIndexChange: ((Int) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    var anchorHidden by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val setExpanded: (Boolean) -> Unit = {
        if (expanded != it) {
            if (it) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            expanded = it
            if (it) anchorHidden = true
            onExpandedChange?.invoke(it)
        }
    }
    val actualEnabled = enabled && items.isNotEmpty()
    ChromeChoiceAnchor(
        title = title,
        summary = summary,
        value = items.getOrNull(selectedIndex)?.text,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        enabled = actualEnabled,
        expanded = expanded,
        anchorHidden = anchorHidden,
        insideMargin = insideMargin,
        onAnchorBounds = { anchor = it },
        onClick = { setExpanded(true) },
    )
    val entry = DropdownEntry(
        enabled = actualEnabled,
        items = items.mapIndexed { index, item ->
            item.copy(selected = index == selectedIndex, onClick = {
                onSelectedIndexChange?.invoke(index)
                item.onClick?.invoke()
            })
        },
    )
    ChromeMenuDialog(
        show = expanded,
        anchorScreen = anchor,
        entries = listOf(entry),
        title = title,
        dialogButtonString = dialogButtonString,
        onDismissRequest = { setExpanded(false) },
        onDismissFinished = { anchorHidden = false; onDismissFinished?.invoke() },
    )
}

@Composable
private fun ChromeChoiceAnchor(
    title: String,
    summary: String?,
    value: String?,
    modifier: Modifier,
    enabled: Boolean,
    expanded: Boolean,
    anchorHidden: Boolean = expanded,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    onAnchorBounds: (Rect) -> Unit = {},
    onClick: () -> Unit,
) {
    val effects = LocalStarMarkEffects.current
    // Spinner.apply/revertAnchorTransition uses AnimConfig's default (.95,.35).
    // Keep the trigger hidden until the popup's reverse animation has completed.
    val anchorAlpha by animateFloatAsState(if (anchorHidden) 0f else 1f,
        animationSpec = if (effects.reduceMotion) snap() else spring(dampingRatio = .95f, stiffness = FolmeSpring(.95f, .35f).stiffness),
        label = "choiceAnchorAlpha")
    val actionColor = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantActions
        else MiuixTheme.colorScheme.disabledOnSecondaryVariant
    MiuixBasicComponent(
        modifier = modifier.semantics {
            stateDescription = listOfNotNull(value, if (expanded) "已展开" else "已收起").joinToString("，")
            if (!enabled) disabled()
        },
        title = title,
        summary = summary,
        insideMargin = insideMargin,
        enabled = enabled,
        role = Role.DropdownList,
        onClick = onClick,
        endActions = {
            Row(Modifier.onGloballyPositioned {
                val p = it.localToScreen(Offset.Zero)
                onAnchorBounds(Rect(p.x, p.y, p.x + it.size.width, p.y + it.size.height))
            }.graphicsLayer { alpha = anchorAlpha }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!value.isNullOrEmpty()) {
                Text(
                    text = value,
                    modifier = Modifier.padding(end = 8.dp).weight(1f, fill = false),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = actionColor,
                    textAlign = TextAlign.End,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownArrowEndAction(actionColor)
            }
        },
    )
}

@Composable
internal fun ChromeDropdownMenu(
    entry: DropdownEntry,
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(false) }
    var anchorHidden by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val setExpanded: (Boolean) -> Unit = {
        if (expanded != it) {
            if (it) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            expanded = it
            if (it) anchorHidden = true
            onExpandedChange?.invoke(it)
        }
    }
    val actualEnabled = enabled && entry.enabled && entry.items.isNotEmpty()
    ChromeChoiceAnchor(title, null, null, modifier, actualEnabled, expanded, anchorHidden = anchorHidden, onAnchorBounds = { anchor = it }) { setExpanded(true) }
    ChromeMenuDialog(
        show = expanded,
        anchorScreen = anchor,
        entries = listOf(entry.copy(enabled = actualEnabled)),
        title = title,
        onDismissRequest = { setExpanded(false) },
        onDismissFinished = { anchorHidden = false; onDismissFinished?.invoke() },
    )
}

@Composable
internal fun ChromeIconDropdownMenu(
    entry: DropdownEntry,
    modifier: Modifier = Modifier,
    title: String = "操作",
    enabled: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val setExpanded: (Boolean) -> Unit = {
        if (expanded != it) {
            if (it) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            expanded = it
            onExpandedChange?.invoke(it)
        }
    }
    val actualEnabled = enabled && entry.enabled && entry.items.isNotEmpty()
    IconButton(
        onClick = { setExpanded(true) },
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).onGloballyPositioned { val p = it.localToScreen(Offset.Zero); anchor = Rect(p.x, p.y, p.x + it.size.width, p.y + it.size.height) },
        enabled = actualEnabled,
        content = content,
    )
    ChromeMenuDialog(
        show = expanded,
        anchorScreen = anchor,
        entries = listOf(entry.copy(enabled = actualEnabled)),
        title = title,
        onDismissRequest = { setExpanded(false) },
        onDismissFinished = onDismissFinished,
    )
}

private data class ChromeMenuOption(val item: DropdownItem, val enabled: Boolean)

/** Cascading navigation stays inside one material host, with ordinary back navigation. */
@Composable
internal fun ChromeMenuDialog(
    show: Boolean,
    entries: List<DropdownEntry>,
    title: String,
    onDismissRequest: () -> Unit,
    onDismissFinished: (() -> Unit)? = null,
    dialogButtonString: String? = null,
    anchorScreen: Rect? = null,
) {
    val haptics = LocalHapticFeedback.current
    var path by remember { mutableStateOf<List<Int>>(emptyList()) }
    var options = entries.flatMap { group -> group.items.map { ChromeMenuOption(it, group.enabled && it.enabled) } }
    var currentTitle = title
    var validPath = emptyList<Int>()
    for (index in path) {
        val parent = options.getOrNull(index) ?: break
        val children = parent.item.children?.takeIf { it.isNotEmpty() } ?: break
        currentTitle = parent.item.text
        validPath = validPath + index
        options = children.map { ChromeMenuOption(it, parent.enabled && it.enabled) }
    }
    val displayedOptions = options
    val displayedPath = validPath
    ChromeAnchoredPopup(
        show = show,
        anchorScreen = anchorScreen,
        title = currentTitle,
        onDismissRequest = onDismissRequest,
        onDismissFinished = {
            path = emptyList()
            onDismissFinished?.invoke()
        },
    ) { interactive ->
        BackHandler(enabled = show && LocalChromeOverlayActive.current && displayedPath.isNotEmpty()) { path = displayedPath.dropLast(1) }
        Column(Modifier.fillMaxWidth()) {
            if (displayedPath.isNotEmpty()) {
                TextButton("返回上一级", hapticClick { path = displayedPath.dropLast(1) }, enabled = interactive)
            }
            key(displayedPath) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    val colors = DropdownDefaults.dropdownColors(
                        containerColor = Color.Transparent,
                        selectedContainerColor = Color.Transparent,
                    )
                    displayedOptions.forEachIndexed { index, option ->
                        val hasSubmenu = !option.item.children.isNullOrEmpty()
                        Box(Modifier.fillMaxWidth(), propagateMinConstraints = true) { MiuixDropdownImpl(
                            item = option.item,
                            optionSize = displayedOptions.size,
                            isSelected = option.item.selected,
                            index = index,
                            dropdownColors = colors,
                            // Interaction handoff must not paint enabled choices as disabled.
                            enabled = option.enabled,
                            dialogMode = false,
                            hasSubmenu = hasSubmenu,
                            onSelectedIndexChange = {
                                if (!interactive || !option.enabled) Unit
                                else if (hasSubmenu) {
                                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    path = displayedPath + index
                                } else {
                                    if (!option.item.selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    // Close before invoking an action that may remove the anchor's page.
                                    onDismissRequest()
                                    option.item.onClick?.invoke()
                                }
                            },
                        ) }
                    }
                }
            }
            dialogButtonString?.let { label ->
                TextButton(label, onDismissRequest, Modifier.fillMaxWidth())
            }
        }
    }
}
