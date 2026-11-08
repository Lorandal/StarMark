package dev.starmark.starmark.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Folme's second argument is the oscillator period in seconds, not Compose stiffness. */
internal data class FolmeSpring(val damping: Float, val response: Float) {
    val stiffness: Float get() = ((2.0 * PI / max(response.toDouble(), .08)).let { it * it }).toFloat()
}

/** FileExplorer SearchActionModeView's 400ms SpringInterpolator(.98, .75). */
internal fun stockSearchProgress(fraction: Float): Float {
    if (fraction <= 0f) return 0f
    if (fraction >= 1f) return 1f
    val damping = .98
    val omega = 2.0 * PI / .75
    val damped = omega * sqrt(1.0 - damping * damping)
    val t = fraction.toDouble()
    return (1.0 - exp(-damping * omega * t) *
        (cos(damped * t) + damping * omega / damped * sin(damped * t))).toFloat()
}

internal data class EdgeSprings(val left: FolmeSpring, val right: FolmeSpring)
internal fun indicatorSprings(left: Float, right: Float, targetLeft: Float, targetRight: Float): EdgeSprings {
    val fast = FolmeSpring(.70f, .40f)
    val slow = FolmeSpring(.75f, .50f)
    return if (targetLeft + targetRight < left + right) EdgeSprings(fast, slow) else EdgeSprings(slow, fast)
}

internal data class MenuSprings(val shell: FolmeSpring, val center: FolmeSpring)
internal fun menuSprings(open: Boolean) = if (open) {
    MenuSprings(FolmeSpring(.80f, .40f), FolmeSpring(.80f, .25f))
} else {
    MenuSprings(FolmeSpring(.80f, .28f), FolmeSpring(.80f, .40f))
}

/** An analytic oscillator: retargeting changes the force, never the current position or velocity. */
internal class SpringValue(initial: Float) {
    var value = initial
        private set
    var velocity = 0f
        private set
    var target = initial
        private set
    private var spec = FolmeSpring(1f, .30f)
    val running: Boolean get() = abs(value - target) > .01f || abs(velocity) > .01f

    fun retarget(target: Float, spec: FolmeSpring) {
        this.target = target
        this.spec = spec
    }

    fun snapTo(target: Float) {
        value = target
        this.target = target
        velocity = 0f
    }

    fun step(seconds: Float) {
        if (seconds <= 0f || !seconds.isFinite() || !running) return
        val time = seconds.toDouble()
        val omega = sqrt(spec.stiffness.toDouble())
        val damping = max(0.0, spec.damping.toDouble())
        val displacement = (value - target).toDouble()
        val speed = velocity.toDouble()
        val next: Double
        val nextSpeed: Double
        if (damping < .9999) {
            val frequency = omega * sqrt(1.0 - damping * damping)
            val decay = exp(-damping * omega * time)
            val cosine = cos(frequency * time)
            val sine = sin(frequency * time)
            val b = (speed + damping * omega * displacement) / frequency
            val wave = displacement * cosine + b * sine
            next = decay * wave
            nextSpeed = decay * (-damping * omega * wave - displacement * frequency * sine + b * frequency * cosine)
        } else if (damping <= 1.0001) {
            val decay = exp(-omega * time)
            val b = speed + omega * displacement
            next = decay * (displacement + b * time)
            nextSpeed = decay * (speed - omega * b * time)
        } else {
            val root = sqrt(damping * damping - 1.0)
            val r1 = -omega * (damping - root)
            val r2 = -omega * (damping + root)
            val a = (speed - r2 * displacement) / (r1 - r2)
            val b = displacement - a
            next = a * exp(r1 * time) + b * exp(r2 * time)
            nextSpeed = a * r1 * exp(r1 * time) + b * r2 * exp(r2 * time)
        }
        value = target + next.toFloat()
        velocity = nextSpeed.toFloat()
        if (!running) snapTo(target)
    }
}

internal data class FloatingNavigationMetrics(val width: Float, val height: Float, val radius: Float, val labelSize: Float)

internal const val navigationInset = 3f
private const val navigationOverlap = 10f

/** The clock's item Views overlap. The selected overlay is as wide as its View. */
internal data class FloatingNavigationTrack(val width: Float, val count: Int) {
    private val spaceAboveTouchMinimum = (width - 48f * count).coerceAtLeast(0f)
    private val inset = min(navigationInset, spaceAboveTouchMinimum / 2f)
    private val overlap = if (count == 1) 0f else min(navigationOverlap, spaceAboveTouchMinimum - 2f * inset)
    val itemWidth = (width - 2f * inset + (count - 1) * overlap) / count
    val focusWidth = itemWidth - overlap
    fun start(index: Int) = inset + index * focusWidth
    fun center(index: Int) = start(index) + itemWidth / 2f
    fun focusStart(index: Int) = center(index) - focusWidth / 2f
    fun nearest(x: Float) = (0 until count).minBy { abs(center(it) - x) }
}

internal fun floatingNavigationMetrics(
    availableWidth: Float, fontScale: Float, itemCount: Int = 4, widestLabel: Float = 0f,
): FloatingNavigationMetrics {
    val count = itemCount.coerceAtLeast(1)
    // Give up the 24dp side clearance in a very narrow pane before shrinking touch targets.
    val shellLimit = max(availableWidth - 48f, min(availableWidth, count * 48f)).coerceAtLeast(0f)
    val overlaps = (count - 1) * navigationOverlap
    val itemsSpace = shellLimit - 2f * navigationInset + overlaps
    val width = when {
        count <= 3 -> {
            val itemWidth = if (widestLabel + 20f <= 88f) 88f else widestLabel + 30f
            min(shellLimit, count * itemWidth - overlaps + 2f * navigationInset)
        }
        itemsSpace > 400f -> min(344f, shellLimit)
        else -> shellLimit
    }
    val large = fontScale >= 1.6f
    val height = if (large) max(80f, 40f + 38.4f * fontScale) else 40f + 15f * max(1f, fontScale)
    return FloatingNavigationMetrics(width, height, if (large) 31f else height / 2f, if (large) 16f else 11f)
}

internal fun navigationBottomMargin(inset: Float) = if (inset < 24f) 24f else inset + 8f

internal data class MotionRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val centerX get() = left + width / 2f
    val centerY get() = top + height / 2f
}

internal data class MorphGeometry(val centerX: Float, val centerY: Float, val width: Float, val height: Float, val radius: Float) {
    val left get() = centerX - width / 2f
    val top get() = centerY - height / 2f
}

/** Material is independent of the shell spring and keeps its current fraction when reversed. */
internal class MaterialValue(private val source: Boolean = false) {
    var value = 0f
        private set
    private var start = 0f
    private var target = 0f
    private var elapsed = 0f
    private var delay = 0f
    val running: Boolean get() = value != target
    val contentBlur get() = 50f * (1f - value)
    val sourceBlur get() = 50f * value

    fun retarget(open: Boolean) {
        val nextTarget = if (open) 1f else 0f
        if (nextTarget == target) return
        start = value
        target = nextTarget
        elapsed = 0f
        delay = if (open != source) .05f else 0f
    }

    fun snapTo(open: Boolean) {
        value = if (open) 1f else 0f
        start = value
        target = value
    }

    fun step(seconds: Float) {
        if (!running || seconds <= 0f) return
        elapsed += seconds
        val fraction = ((elapsed - delay) / .08f).coerceIn(0f, 1f)
        value = start + (target - start) * fraction
        if (fraction == 1f) value = target
    }
}

/** The live menu and JVM tests share these exact geometry tracks. Coordinates are caller-local. */
internal class MenuMotion(anchor: MotionRect, private val floatingSource: Boolean = true) {
    private val x = SpringValue(anchor.centerX)
    private val y = SpringValue(anchor.centerY)
    private val width = SpringValue(anchor.width)
    private val height = SpringValue(anchor.height)
    private val aspect = SpringValue(anchor.height / anchor.width.coerceAtLeast(1f))
    private val sourceRadius = if (floatingSource) min(anchor.width, anchor.height) / 2f else 4f
    private val radius = SpringValue(sourceRadius)
    val material = MaterialValue()
    val sourceMaterial = MaterialValue(source = true)
    // ArcAnimation interpolates width and aspect, not width and height. Its center
    // moves on a separate spring, letting the near edge move out then settle back.
    val geometry get() = MorphGeometry(x.value, y.value, width.value.coerceAtLeast(1f),
        (if (floatingSource) height.value else width.value * aspect.value).coerceAtLeast(1f), radius.value.coerceAtLeast(0f))
    val running get() = x.running || y.running || width.running || height.running || aspect.running || radius.running || material.running || sourceMaterial.running

    fun retarget(open: Boolean, anchor: MotionRect, menu: MotionRect, immediate: Boolean = false) {
        val rect = if (open) menu else anchor
        val springs = if (floatingSource) menuSprings(open) else MenuSprings(
            FolmeSpring(.8f, if (open) .35f else .22f),
            FolmeSpring(.8f, if (open) .22f else .35f),
        )
        x.retarget(rect.centerX, springs.center)
        y.retarget(rect.centerY, springs.center)
        width.retarget(rect.width, springs.shell)
        height.retarget(rect.height, springs.shell)
        if (!floatingSource) aspect.retarget(rect.height / rect.width.coerceAtLeast(1f), springs.shell)
        if (open || floatingSource) radius.retarget(if (open) 24f else sourceRadius, springs.shell)
        else radius.snapTo(radius.value) // ArcAnimation preserves the last open radius during dismissal.
        material.retarget(open)
        sourceMaterial.retarget(open)
        if (immediate) {
            x.snapTo(rect.centerX)
            y.snapTo(rect.centerY)
            width.snapTo(rect.width)
            height.snapTo(rect.height)
            if (!floatingSource) aspect.snapTo(rect.height / rect.width.coerceAtLeast(1f))
            radius.snapTo(if (open) 24f else if (floatingSource) sourceRadius else radius.value)
            material.snapTo(open)
            sourceMaterial.snapTo(open)
        }
    }

    fun step(seconds: Float) {
        x.step(seconds)
        y.step(seconds)
        width.step(seconds)
        height.step(seconds)
        aspect.step(seconds)
        radius.step(seconds)
        material.step(seconds)
        sourceMaterial.step(seconds)
    }
}
