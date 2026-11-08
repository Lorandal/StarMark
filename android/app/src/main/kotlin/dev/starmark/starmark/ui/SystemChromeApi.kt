package dev.starmark.starmark.ui

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** Shared new optical material, with each component's existing decoration and fallback. */
internal class SystemChromeApi private constructor(private val type: Class<*>) {
    private fun method(name: String, vararg args: Class<*>) = runCatching { type.getMethod(name, *args) }.getOrNull()
    private val integer = Int::class.javaPrimitiveType!!
    private val float = Float::class.javaPrimitiveType!!
    private val sourceMode = method("setMiBackgroundBlurMode", integer)
    private val radius = method("setMiBackgroundBlurRadius", integer)
    private val viewMode = method("setMiViewBlurMode", integer)
    private val material = method("setMiViewMaterialType", integer)
    private val clearBlends = method("clearMiBackgroundBlendColor")
    private val addBlend = method("addMiBackgroundBlendColor", integer, integer)
    private val bloom = method("setMiBloomStroke", FloatArray::class.java)
    private val shadow = method("setMiShadow", integer, float, float, float, float)
    private val clippedShadow = method("setMiShadow", integer, float, float, float, float, Boolean::class.javaPrimitiveType!!)
    private val blurType = method("setMiBackgroundBlurType", integer)
    private val mixEffect = method("setMixEffectEnabled", Boolean::class.javaPrimitiveType!!)
    private val alpha = method("setAlpha", float)
    val supported get() = listOf(sourceMode, radius, viewMode, material, clearBlends, addBlend).all { it != null }
    val supportsShadow get() = shadow != null
    val supportsNavigationShadow get() = clippedShadow != null || supportsShadow

    fun apply(source: Any, target: Any, radiusPx: Int, density: Float, dark: Boolean,
        role: ChromeMaterialRole = ChromeMaterialRole.Standard, strength: Float = 1f,
        tryGlassOptics: Boolean = false): Boolean {
        if (radiusPx < 0 || !density.isFinite() || density <= 0f || !strength.isFinite()) return false
        if (!supported && !tryGlassOptics) return false
        val header = role == ChromeMaterialRole.HeaderButton
        val modal = role == ChromeMaterialRole.Modal || role == ChromeMaterialRole.Popup
        return runCatching {
            fun call(method: java.lang.reflect.Method?, receiver: Any, vararg args: Any) {
                check(requireNotNull(method).invoke(receiver, *args) != false)
            }
            val glass = SystemGlassApi.lookup(type)
            fun applyOptics(): Boolean = if (tryGlassOptics && glass.supported && glass.supportsRadii) {
                // Navigation already shipped this profile. Share its exact values;
                // bounds and the native RoundRect radius remain owned by the View.
                val profile = GlassProfileRegistry.navigation(dark)
                runCatching {
                    radius?.let { call(it, target, 0) }
                    clearBlends?.let { call(it, target) }
                    glass.apply(target, profile.shader, profile.radii)
                }.getOrDefault(false)
            } else false
            if (role == ChromeMaterialRole.Navigation) {
                alpha?.let { call(it, target, 1f) }
                val optical = applyOptics()
                if (!optical) {
                    glass.clearOptics(target)
                    call(material, target, 0)
                    blurType?.let { call(it, target, 0) }
                    call(sourceMode, target, 1)
                    call(radius, target, radiusPx)
                    call(viewMode, target, 1)
                    call(clearBlends, target)
                    ChromeMaterialRecipe.glass(dark).forEach { call(addBlend, target, it.argb.toInt(), it.mode) }
                }
                // Glass_Stroke_Middle widths, and Regular shadow distances, use display density.
                runCatching { bloom?.invoke(target, ChromeMaterialRecipe.headerStroke(density, dark) as Any) }
                runCatching { navigationShadow(target, 0x59000000, (2f * density).roundToInt().toFloat(),
                    (64f * density).roundToInt().toFloat(), .8f) }
                return@runCatching
            }
            if (header) mixEffect?.let { call(it, target, false) }
            val optical = applyOptics()
            if (optical && role == ChromeMaterialRole.Standard && source !== target) {
                // A prior failed optical attempt may have enabled the legacy MixBar.
                // New glass samples its own backdrop and must not retain that blur.
                sourceMode?.let { call(it, source, 0) }
                radius?.let { call(it, source, 0) }
            }
            // Fade the material View only. Sharp foreground content stays in its
            // existing sibling composition; a zero-strength header stays clear.
            alpha?.let { call(it, target, if (optical && header) strength.coerceIn(0f, 1f) else 1f) }
            if (!optical) {
                glass.clearOptics(target)
                call(material, target, 0)
                if (header || modal) {
                    // Header/popup/dialog capture belongs to the material itself.
                    call(sourceMode, target, 1)
                } else {
                    call(sourceMode, source, 1)
                    call(radius, source, 50) // Legacy MixBar parent uses raw pixels.
                }
                call(viewMode, target, 1)
                call(radius, target, radiusPx)
                call(clearBlends, target)
                val colors = if (modal) ChromeMaterialRecipe.floating(dark) else ChromeMaterialRecipe.glass(dark)
                (if (header) ChromeMaterialRecipe.fadeBlends(colors, strength) else colors).forEach { call(addBlend, target, it.argb.toInt(), it.mode) }
                if (header || modal) blurType?.let { call(it, target, 0) }
            }
            // Bloom/shadow are decorations. Their absence must not discard a working blur.
            runCatching { bloom?.invoke(target, (when {
                modal -> ChromeMaterialRecipe.modalStroke(density, dark)
                header -> ChromeMaterialRecipe.headerStroke(density, dark)
                else -> ChromeMaterialRecipe.smallStroke(density)
            }) as Any) }
            runCatching {
                if (header) shadow?.invoke(target, 0x59000000, 0f, 2f, ((if (dark) 20f else 64f) * strength).coerceAtLeast(2f), 1f)
                else if (modal) shadow?.invoke(target, 0x59000000, 0f, 2f, 64f, .5f)
                else shadow?.invoke(target, 0x59000000, 0f, 0f, 44f, .5f)
            }
        }.onFailure { clear(source, target, role) }.isSuccess
    }

    private fun navigationShadow(target: Any, color: Int, dy: Float, radius: Float, dispersion: Float) {
        clippedShadow?.let { setter ->
            if (runCatching { setter.invoke(target, color, 0f, dy, radius, dispersion, true) != false }.getOrDefault(false)) return
        }
        shadow?.invoke(target, color, 0f, dy, radius, dispersion)
    }

    fun clear(source: Any, target: Any, role: ChromeMaterialRole = ChromeMaterialRole.Standard) {
        SystemGlassApi.lookup(type).clearOptics(target)
        runCatching { alpha?.invoke(target, 1f) }
        runCatching { clearBlends?.invoke(target) }
        runCatching { bloom?.invoke(target, floatArrayOf() as Any) }
        runCatching {
            if (role == ChromeMaterialRole.Navigation) navigationShadow(target, 0, 0f, 0f, 0f)
            else shadow?.invoke(target, 0, 0f, 0f, 0f, 0f)
        }
        runCatching { viewMode?.invoke(target, 0) }
        runCatching { material?.invoke(target, 0) }
        runCatching { radius?.invoke(target, 0) }
        runCatching { sourceMode?.invoke(target, 0) }
        runCatching { radius?.invoke(source, 0) }
        runCatching { sourceMode?.invoke(source, 0) }
    }

    companion object {
        private val cache = ConcurrentHashMap<Class<*>, SystemChromeApi>()
        fun lookup(type: Class<*>) = cache.getOrPut(type) { SystemChromeApi(type) }
    }
}
