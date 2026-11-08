package dev.starmark.starmark.ui

import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

/** These are platform IDs from LinearVibrator, NOT Miuix's logical constants. */
internal enum class StarMarkHaptic(val platformIds: List<Int>) {
    // Settings Spinner: BUTTON_SMALL, with POPUP_LIGHT / MESH_NORMAL fallbacks.
    Popup(listOf(0x10000013, 0x10000009)),
    Selection(listOf(0x10000013, 0x10000005)),
    Switch(listOf(0x10000018, 0x10000003)),
    Tick(listOf(0x10000014, 0x10000006)),
    Boundary(listOf(0x1000000f, 0x10000005)),
    LongPress(listOf(0x10000007)),
}

internal class HapticRouter(
    private val supports: (Int) -> Boolean,
    private val perform: (Int) -> Boolean,
    private val enabled: () -> Boolean,
    private val nowMillis: () -> Long,
) {
    private var lastTick: Long? = null
    fun perform(kind: StarMarkHaptic?, fallback: () -> Unit) {
        if (!enabled()) return
        if (kind == StarMarkHaptic.Tick) {
            val now = nowMillis()
            if (lastTick?.let { now - it in 0..25 } == true) return
            lastTick = now
        }
        val id = kind?.platformIds?.firstOrNull { runCatching { supports(it) }.getOrDefault(false) }
        if (id == null || !runCatching { perform(id) }.getOrDefault(false)) fallback()
    }
}

private object MiuiHapticCapability {
    private val support by lazy {
        runCatching {
            Class.forName("miui.util.HapticFeedbackUtil")
                .getMethod("isSupportLinearMotorVibrate", Int::class.javaPrimitiveType!!)
        }.getOrNull()
    }
    private val ids = mutableMapOf<Int, Boolean>()
    fun supports(id: Int): Boolean = ids.getOrPut(id) {
        runCatching { support?.invoke(null, id) == true }.getOrDefault(false)
    }
}

internal class StarMarkHapticFeedback(view: View, private val fallback: HapticFeedback) : HapticFeedback {
    private val router = HapticRouter(MiuiHapticCapability::supports,
        { view.performHapticFeedback(it) },
        {
            view.isHapticFeedbackEnabled && runCatching {
                Settings.System.getInt(view.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
            }.getOrDefault(true)
        }, SystemClock::uptimeMillis)

    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        val kind = when (hapticFeedbackType) {
            HapticFeedbackType.ContextClick -> StarMarkHaptic.Popup
            HapticFeedbackType.SegmentTick -> StarMarkHaptic.Selection
            HapticFeedbackType.SegmentFrequentTick -> StarMarkHaptic.Tick
            HapticFeedbackType.ToggleOn, HapticFeedbackType.ToggleOff -> StarMarkHaptic.Switch
            HapticFeedbackType.TextHandleMove -> StarMarkHaptic.Tick
            HapticFeedbackType.GestureThresholdActivate -> StarMarkHaptic.Boundary
            HapticFeedbackType.LongPress -> StarMarkHaptic.LongPress
            else -> null
        }
        router.perform(kind) { fallback.performHapticFeedback(hapticFeedbackType) }
    }
}

/** Call from accepted user actions only, never from recomposition or state synchronization. */
@Composable
internal fun hapticClick(
    type: HapticFeedbackType = HapticFeedbackType.ContextClick,
    feedbackEnabled: Boolean = true,
    onClick: () -> Unit,
): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return {
        if (feedbackEnabled) haptics.performHapticFeedback(type)
        onClick()
    }
}

internal fun toggleHaptic(selected: Boolean) =
    if (selected) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn

@Composable
internal fun <T> hapticSelection(current: T, onSelected: (T) -> Unit): (T) -> Unit {
    val haptics = LocalHapticFeedback.current
    return { next ->
        if (next != current) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onSelected(next)
    }
}

@Composable
internal fun rememberStarMarkHapticFeedback(): HapticFeedback {
    val view = LocalView.current
    val fallback = LocalHapticFeedback.current
    return remember(view, fallback) { StarMarkHapticFeedback(view, fallback) }
}
