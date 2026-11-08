package dev.starmark.starmark.ui

import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** No hidden-API bypass is attempted; inaccessible methods make glass unavailable. */
internal class SystemGlassApi private constructor(private val type: Class<*>) {
    private fun method(name: String, vararg types: Class<*>): Method? =
        runCatching { type.getMethod(name, *types) }.getOrNull()
    private val integer = Int::class.javaPrimitiveType!!
    private val background = method("setMiBackgroundBlurMode", integer)
    private val viewMode = method("setMiViewBlurMode", integer)
    private val material = method("setMiViewMaterialType", integer)
    private val radii = method("setMiGlassBlurRadius", integer, integer)
    private val shader = method("setMiGlass", FloatArray::class.java)
    private val clearBlends = method("clearMiBackgroundBlendColor")
    private val addBlend = method("addMiBackgroundBlendColor", integer, integer)
    val supported get() = background != null && viewMode != null && material != null && shader != null
    val supportsRadii get() = radii != null

    fun clearOptics(target: Any) {
        runCatching { shader?.invoke(target, floatArrayOf() as Any) }
        runCatching { radii?.invoke(target, 0, 0) }
    }

    fun apply(target: Any, params: FloatArray, radius: Pair<Int, Int>?): Boolean {
        // The reference app sends an empty array to clear glass configuration.
        if (!supported || params.isEmpty() || params.any { !it.isFinite() } || (radius != null && radii == null)) return false
        return try {
            invokeRequired(background!!, target, 1)
            invokeRequired(viewMode!!, target, 1)
            invokeRequired(material!!, target, 1)
            if (radius != null) invokeRequired(radii!!, target, radius.first, radius.second)
            invokeRequired(shader!!, target, params as Any)
            true
        } catch (_: Exception) {
            clear(target)
            false
        }
    }

    /** Replace, never append to the previous theme's material. False is a vendor denial. */
    fun applyBlends(target: Any, colors: List<MaterialBlend>): Boolean {
        if (clearBlends == null || addBlend == null) return false
        return try {
            invokeRequired(clearBlends, target)
            colors.forEach { invokeRequired(addBlend, target, it.argb.toInt(), it.mode) }
            true
        } catch (_: Exception) {
            runCatching { clearBlends?.invoke(target) }
            false
        }
    }

    private fun invokeRequired(method: Method, target: Any, vararg args: Any) {
        check(method.invoke(target, *args) != false) { "Vendor material setter denied ${method.name}" }
    }

    fun clear(target: Any) {
        runCatching { shader?.invoke(target, floatArrayOf() as Any) }
        runCatching { clearBlends?.invoke(target) }
        runCatching { material?.invoke(target, 0) }
        runCatching { viewMode?.invoke(target, 0) }
        runCatching { background?.invoke(target, 0) }
    }

    companion object {
        private val cache = ConcurrentHashMap<Class<*>, SystemGlassApi>()
        fun lookup(type: Class<*>): SystemGlassApi = cache.getOrPut(type) { SystemGlassApi(type) }
    }
}

internal data class MaterialBlend(val argb: Long, val mode: Int)
internal enum class ChromeMaterialRole { Standard, HeaderButton, Modal, Popup, Navigation }

/** MediaEditor MixBar and DeskClock library ColorBlendTokens. Order is significant.
 * Mode IDs are the APK's BlendModeToken values and Miuix BlurBlendMode values.
 * See docs/research/2026-10-28-widget-glass-audit.md for actual widget callsites. */
internal object ChromeMaterialRecipe {
    const val blurRadiusDp = 20f
    const val nativeBlurRadiusDp = 40f
    const val navigationBlurRadiusDp = 20f
    // PopupView_Glass_Material and Default_Dialog_Material both use setMaskBlur(60).
    const val modalBlurRadiusDp = 60f
    fun smallStroke(density: Float) = floatArrayOf(
        1f, 180f, 1f, 1f, 1f, .1f, 1f, .2f, .5f, 0f, 1f,
        1f, 1f, .7f, .8f, .9f, 0f, 1f, 1f, 1f, .7f,
    ).apply { this[0] = this[0] * density + .5f; this[6] = this[6] * density + .5f }
    fun headerStroke(density: Float, dark: Boolean) = floatArrayOf(
        1f, 180f, 1f, 1f, 1f, if (dark) .10f else .05f, 1f, .5f, .7f, 0f, 1f,
        1f, 1f, .7f, .5f, .7f, 0f, 1f, 1f, 1f, .6f,
    ).apply { this[0] = this[0] * density + .5f; this[6] = this[6] * density + .5f }

    fun fadeBlends(colors: List<MaterialBlend>, strength: Float) = colors.map {
        val alpha = (((it.argb ushr 24) and 255) * strength.coerceIn(0f, 1f) + .5f).toLong()
        it.copy(argb = (it.argb and 0x00ffffffL) or (alpha shl 24))
    }
    fun glass(dark: Boolean): List<MaterialBlend> = if (dark) listOf(
        MaterialBlend(0x1a000000, 120), MaterialBlend(0x66565656, 28), MaterialBlend(0x993f3f3f, 15),
    ) else listOf(
        MaterialBlend(0x05000000, 120), MaterialBlend(0x99ffffff, 21), MaterialBlend(0x66ffffff, 20),
    )

    // PopupView_Glass_Material uses Pured_Thin_Glass. StarMark intentionally uses
    // this same floating material for menus and bottom dialogs (shared appearance).
    fun floating(dark: Boolean): List<MaterialBlend> = glass(dark)

    // AlertController and FloatingEffectHelper both select Glass_Stroke_Middle.
    fun modalStroke(density: Float, dark: Boolean): FloatArray = headerStroke(density, dark).apply {
        // HyperBloomStrokeUtils.setBloomStrokeWithDp rounds to an integer pixel.
        this[0] = this[0].toInt().toFloat()
        this[6] = this[6].toInt().toFloat()
    }

    fun frosted(dark: Boolean): List<MaterialBlend> = if (dark) listOf(
        MaterialBlend(0x947d7d7d, 120), MaterialBlend(0x66000000, 3),
    ) else listOf(
        MaterialBlend(0x306d6d6d, 121), MaterialBlend(0x5effffff, 121), MaterialBlend(0x66ffffff, 3),
    )

    fun solid(dark: Boolean): Long = if (dark) 0xff242424 else 0xfff3f3f3
    fun navigationSolid(dark: Boolean): Long = if (dark) 0xff2c2c2c else 0xffffffff
}

/** Xiaomi DeskClock's named `Glass_Common_Small_Regular` GlassToken preset.
 * The values follow GlassToken.toShaderParams(), not the empty clear command.
 * Actual rendering remains gated by device capability and reflective access. */
internal data class VerifiedGlassProfile(val shader: FloatArray, val radii: Pair<Int, Int>)
internal object GlassProfileRegistry {
    /** DeskClock 18.31.0, InternalMaterial.Pured_Thin_Glass -> Glass_Component_Regular.
     * The optical branch clears the blur blends and uses the two raw vendor radii. */
    fun navigation(dark: Boolean) = VerifiedGlassProfile(
        if (dark) floatArrayOf(
            1f, 1f, 0f, 1f, .2f, 2f, 0f, 0f, .4f, .8f,
            0f, .16f, .16f, .16f, .2f, 0f, .2f, 1.2f, 1f,
            48f, 2.4f, 80f, 300f, 1f, .8f, -.5f, .5f, -.8f, 1.4f, 1.2f, 1f, 1.1f,
            3f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f,
        ) else floatArrayOf(
            1f, 1f, .7f, 1f, .3f, 2.5f, .2f, .1f, -.001f, 0f,
            0f, .9f, .9f, .9f, .5f, 0f, .6f, .7f, 1f,
            40f, 2.6f, 68f, 300f, 1f, .8f, -.5f, .5f, -.8f, 1.2f, 1f, 1f, 1.06f,
            3f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f,
        ),
        28 to 500,
    )

    val current: VerifiedGlassProfile get() = VerifiedGlassProfile(
        floatArrayOf(
            .5f, 1f, .8f, 1.2f, .24f, 1.6f, .14f, .2f, .6f, .9f,
            .01f, 1f, 1f, 1f, .05f, .3f, .5f, 1.5f, 1f,
            20f, 1.6f, 100f, 110f, 1.2f, 1.4f,
            -.5f, .5f, 0f, 1.1f, .5f, .3f, 1f,
            1.4f, 1.5f, .08f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
        ),
        8 to 500,
    )
}
