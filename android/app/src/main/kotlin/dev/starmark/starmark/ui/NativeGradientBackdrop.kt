package dev.starmark.starmark.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

/** Only the toolbar underlay is affected; the rest of the window stays untouched. */
internal class NativeGradientBackdrop(context: Context, private val onResult: (Boolean) -> Unit) : View(context) {
    private val api = NativeGradientApi.lookup(View::class.java)
    private var requested = false
    private var radius = 0f
    private var tint = Color.TRANSPARENT
    private var applied = false
    private var appliedHeight = 0
    private var appliedRadius = 0f
    private var failed = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun configure(enabled: Boolean, blurRadius: Float, tintColor: Int) {
        requested = enabled
        radius = blurRadius
        if (tint != tintColor) {
            tint = tintColor
            if (applied) applyTint()
        }
        refresh()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); refresh() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); if (hasWindowFocus) refresh() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); refresh() }
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }

    fun release() = clear()

    private fun refresh() {
        val permitted = requested && api.supported && !failed && isAttachedToWindow && isHardwareAccelerated &&
            MiuiMaterialCapability.enabled() && height > 0
        if (!permitted) { clear(); return }
        if (applied && appliedHeight == height && appliedRadius == radius) return
        if (api.apply(this, height, radius)) {
            applied = true
            appliedHeight = height
            appliedRadius = radius
            applyTint()
            onResult(true)
        } else {
            failed = true
            clear()
        }
    }

    private fun applyTint() {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(tint, Color.TRANSPARENT))
    }

    private fun clear() {
        if (!applied) return
        api.clear(this)
        applied = false
        appliedHeight = 0
        setBackgroundColor(Color.TRANSPARENT)
        onResult(false)
    }
}
