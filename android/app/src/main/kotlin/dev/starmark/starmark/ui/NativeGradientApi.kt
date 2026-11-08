package dev.starmark.starmark.ui

import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Public reflective calls only: denied or missing vendor APIs fall back to Miuix. */
internal class NativeGradientApi private constructor(private val type: Class<*>) {
    private fun find(name: String, vararg arguments: Class<*>): Method? =
        runCatching { type.getMethod(name, *arguments) }.getOrNull()
    private val int = Int::class.javaPrimitiveType!!
    private val background = find("setMiBackgroundBlurMode", int)
    private val view = find("setMiViewBlurMode", int)
    private val blurType = find("setMiBackgroundBlurType", int)
    private val gradient = find("setBackgroundGradientBlurParams", FloatArray::class.java, int)
    val supported get() = background != null && view != null && blurType != null && gradient != null

    /** DeskClock MiuiBlurUtils.setBgCommonLinearGradientBlur orientation=1. */
    fun apply(target: Any, height: Int, radius: Float): Boolean {
        if (!supported || height <= 0 || !radius.isFinite() || radius <= 0f) return false
        val parameters = floatArrayOf(0f, (height - 255f).coerceAtLeast(0f), radius, 0f, height.toFloat(), 0f)
        return try {
            background!!.invoke(target, 1)
            view!!.invoke(target, 1)
            blurType!!.invoke(target, 2)
            gradient!!.invoke(target, parameters, 1)
            true
        } catch (_: Exception) {
            clear(target)
            false
        }
    }

    fun clear(target: Any) {
        runCatching { background?.invoke(target, 0) }
        runCatching { view?.invoke(target, 0) }
        runCatching { blurType?.invoke(target, 0) }
    }

    companion object {
        private val cache = ConcurrentHashMap<Class<*>, NativeGradientApi>()
        fun lookup(type: Class<*>) = cache.getOrPut(type) { NativeGradientApi(type) }
    }
}
