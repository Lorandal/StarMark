package dev.starmark.starmark.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.Canvas
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewOutlineProvider
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.LocalContentColor
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

internal enum class UiBlurBackend { Solid, MiuiView }
internal fun uiBlurBackend(sdk: Int, systemEnabled: Boolean, userEnabled: Boolean, methodsPresent: Boolean) =
    if (sdk >= 33 && systemEnabled && userEnabled && methodsPresent) UiBlurBackend.MiuiView else UiBlurBackend.Solid

internal fun uiBlurFallbackArgb(dark: Boolean): Long = ChromeMaterialRecipe.solid(dark)
internal fun miuiBlurRadius(radius: Float): Int = if (radius.isFinite()) radius.roundToInt().coerceIn(0, 400) else 0

/** Missing methods, including both self-blur overloads, are cached as null. */
internal class MiuiViewMethods private constructor(private val type: Class<*>) {
    private fun method(name: String, vararg args: Class<*>): Method? = runCatching { type.getMethod(name, *args) }.getOrNull()
    val backgroundMode = method("setMiBackgroundBlurMode", Int::class.javaPrimitiveType!!)
    val backgroundRadius = method("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType!!)
    val backgroundScale = method("setMiBackgroundBlurScaleRatio", Float::class.javaPrimitiveType!!)
    val selfBlur3 = method("setMiSelfBlur", Int::class.javaPrimitiveType!!, ArrayList::class.java, Int::class.javaPrimitiveType!!)
    val selfBlur2 = method("setMiSelfBlur", Int::class.javaPrimitiveType!!, ArrayList::class.java)
    val selfType = method("setMiSelfBlurType", Int::class.javaPrimitiveType!!)
    val smoothCorner = method("setSmoothCornerEnabled", Boolean::class.javaPrimitiveType!!)
    val passWindow = method("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType!!)
    val passWindowWhitelisted = method("isPassWindowBlurWhitelisted", String::class.java)
    val backgroundSupported get() = backgroundMode != null && backgroundRadius != null && backgroundScale != null
    val selfSupported get() = selfBlur3 != null || selfBlur2 != null

    fun canPassWindow(target: Any, packageName: String): Boolean = runCatching {
        passWindow != null && passWindowWhitelisted?.invoke(target, packageName) == true
    }.getOrDefault(false)

    fun setPassWindow(target: Any, enabled: Boolean): Boolean = runCatching {
        val setter = passWindow ?: return false
        val result = setter.invoke(target, enabled)
        result !is Boolean || result
    }.getOrDefault(false)

    companion object {
        private val cache = ConcurrentHashMap<Class<*>, MiuiViewMethods>()
        fun lookup(type: Class<*>): MiuiViewMethods = cache.getOrPut(type) { MiuiViewMethods(type) }
    }
}

/**
 * TransformAnimation supplies (radius, null); ArcAnimation first tries (radius, null, 0).
 * The ArrayList is optional blend configuration, not a synthetic blur kernel. A failed extended
 * signature falls back once to the older signature and stays on that working path thereafter.
 */
internal class MiuiSelfBlurController(private val methods: MiuiViewMethods) {
    private var extendedUsable = methods.selfBlur3 != null
    private var legacyUsable = methods.selfBlur2 != null
    private var typeConfigured = false
    private var applied = false
    private var lastRadius: Int? = null

    fun apply(target: Any, radius: Float, permitted: Boolean): Boolean {
        if (!permitted) {
            if (applied) invokeRadius(target, 0)
            applied = false
            lastRadius = null
            return false
        }
        if (!extendedUsable && !legacyUsable) return false
        val nextRadius = miuiBlurRadius(radius)
        if (applied && lastRadius == nextRadius) return true
        if (!typeConfigured) {
            // Gaussian is the reference default. Old platforms need no explicit type setter.
            runCatching { methods.selfType?.invoke(target, 0) }
            typeConfigured = true
        }
        val success = invokeRadius(target, nextRadius)
        applied = success
        lastRadius = if (success) nextRadius else null
        return success
    }

    private fun invokeRadius(target: Any, radius: Int): Boolean {
        if (extendedUsable) {
            if (runCatching { methods.selfBlur3!!.invoke(target, radius, null, 0) }.isSuccess) return true
            extendedUsable = false
        }
        if (legacyUsable) {
            if (runCatching { methods.selfBlur2!!.invoke(target, radius, null) }.isSuccess) return true
            legacyUsable = false
        }
        return false
    }
}

internal object MiuiMaterialCapability {
    private var glassFailed by mutableStateOf(false)
    private val vendorDevice: Boolean by lazy {
        listOf(Build.MANUFACTURER, Build.BRAND).any { value ->
            value.equals("xiaomi", true) || value.equals("redmi", true) || value.equals("poco", true)
        }
    }
    private val propertyMethod: Method? by lazy {
        if (!vendorDevice || Build.VERSION.SDK_INT < 33) null else runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
        }.getOrNull()
    }
    private val booleanPropertyMethod: Method? by lazy {
        if (!vendorDevice || Build.VERSION.SDK_INT < 33) null else runCatching {
            Class.forName("android.os.SystemProperties").getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType!!)
        }.getOrNull()
    }

    val glassOpticsEligible: Boolean by lazy {
        fun read(key: String) = runCatching { propertyMethod?.invoke(null, key, "") as? String }.getOrNull()
        usesHyperOsNavigationOptics(
            brand = Build.BRAND,
            osVersions = listOf(read("ro.mi.os.version.name"), read("ro.mi.os.version.code"), read("ro.mi.os.version.incremental")),
            androidIncremental = Build.VERSION.INCREMENTAL,
            legacyMiuiVersion = read("ro.miui.ui.version.name"),
        )
    }

    // Re-read on attach/focus/update: users and power-saving modes can switch support at runtime.
    fun supportedDevice(): Boolean = vendorDevice && Build.VERSION.SDK_INT >= 33

    fun enabled(): Boolean = runCatching {
        propertyMethod?.invoke(null, "persist.sys.background_blur_supported", "false") == "true"
    }.getOrDefault(false)

    fun glassSupported(context: Context): Boolean {
        if (!supportedDevice() || glassFailed) return false
        val profile = GlassProfileRegistry.current
        if (profile.shader.size != 42 || profile.shader.any { !it.isFinite() }) return false
        val bionic = runCatching {
            booleanPropertyMethod?.invoke(null, "persist.sys.bionic_material_supported", false) == true
        }.getOrDefault(false)
        val settingEnabled = runCatching { Settings.Secure.getInt(context.contentResolver, "background_blur_enable", 0) == 1 }.getOrDefault(false)
        val style = runCatching { Settings.Secure.getInt(context.contentResolver, "material_style", -1) }.getOrDefault(-1)
        val api = SystemGlassApi.lookup(View::class.java)
        return isSystemGlassReady(Build.VERSION.SDK_INT, enabled(), bionic, settingEnabled, style, api.supported && api.supportsRadii)
    }

    fun markGlassFailure() { glassFailed = true }
}

/** Only this component's rendered child composition receives the MIUI self-blur property. */
private class MiuiSelfBlurContentView(
    context: Context,
    parentContext: CompositionContext,
    wrapContentHeight: Boolean,
    content: @Composable () -> Unit,
) : FrameLayout(context) {
    private val blur = MiuiSelfBlurController(MiuiViewMethods.lookup(View::class.java))
    private var requestedRadius = 0f
    private var userEnabled = false
    private var systemEnabled = false
    private var acceptInput = true
    private val composition = ComposeView(context).apply {
        setParentCompositionContext(parentContext)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        setContent {
            // A nested ComposeView owns its own accessibility tree; publish the same stable tags.
            Box((if (wrapContentHeight) Modifier.fillMaxWidth() else Modifier.fillMaxSize()).semantics { testTagsAsResourceId = true }) { content() }
        }
    }

    init {
        clipChildren = false
        clipToPadding = false
        addView(composition, LayoutParams(LayoutParams.MATCH_PARENT, if (wrapContentHeight) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT))
    }

    fun configure(radius: Float, enabled: Boolean, accessible: Boolean) {
        requestedRadius = radius
        userEnabled = enabled
        acceptInput = accessible
        importantForAccessibility = if (accessible) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        applyBlur()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshCapability()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = acceptInput && super.dispatchTouchEvent(event)

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) refreshCapability()
    }

    override fun onDetachedFromWindow() {
        blur.apply(this, 0f, false)
        super.onDetachedFromWindow()
    }

    fun release() {
        blur.apply(this, 0f, false)
        composition.disposeComposition()
    }

    private fun refreshCapability() {
        systemEnabled = Build.VERSION.SDK_INT >= 33 && MiuiMaterialCapability.enabled()
        applyBlur()
    }

    private fun applyBlur() {
        blur.apply(this, requestedRadius, userEnabled && systemEnabled && isAttachedToWindow && isHardwareAccelerated)
    }
}

/**
 * On capable MIUI, retain a child ComposeView connected to this parent composition/theme and
 * self-blur its FrameLayout. Ordinary Android keeps the direct Compose path and its valid fades.
 * [radius] is the reference's raw View radius in pixels (Arc dismissal uses 30dp).
 */
@Composable
internal fun MiuiSelfBlurHost(
    radius: Float,
    modifier: Modifier = Modifier,
    accessible: Boolean = true,
    wrapContentHeight: Boolean = false,
    selfBlurEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val effects = LocalStarMarkEffects.current
    val nativeHost = remember(effects.glass, effects.reduceMotion, selfBlurEnabled) {
        selfBlurEnabled && effects.glass && !effects.reduceMotion && Build.VERSION.SDK_INT >= 33 &&
            MiuiMaterialCapability.supportedDevice() && MiuiViewMethods.lookup(View::class.java).selfSupported
    }
    if (!nativeHost) {
        Box(modifier.then(if (accessible) Modifier else Modifier.clearAndSetSemantics {}), propagateMinConstraints = true) { content() }
        return
    }
    val parentContext = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)
    AndroidView(
        factory = { context -> MiuiSelfBlurContentView(context, parentContext, wrapContentHeight) { currentContent() } },
        modifier = modifier,
        onReset = null,
        onRelease = { it.release() },
        update = { it.configure(radius, effects.glass && !effects.reduceMotion, accessible) },
    )
}

/** This View is only a backdrop under one component, never the Activity or photo TextureView. */
private class MiuiBackdropView(context: Context, private val onWindowFallback: () -> Unit) : FrameLayout(context) {
    private val materialView = View(context)
    private val excludedCorners = Path()
    private val materialBoundary = Path()
    private var dark = false
    private var userEnabled = false
    private var blurRadius = 0
    private var cornerRadius = 0f
    private var passWindow = false
    private var role = ChromeMaterialRole.Standard
    private var strength = 1f
    private var invocationFailed = false
    private var appliedNative = false
    private var appliedRadius = -1
    private var appliedPassWindow = false
    private var appliedDark: Boolean? = null
    private var appliedRole = ChromeMaterialRole.Standard
    private var appliedStrength = -1f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addView(materialView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        materialView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        // Keep the original native radius: the vendor optical shader reads this
        // RoundRect. A rectangular proxy changes the glass/refraction itself.
        runCatching { MiuiViewMethods.lookup(View::class.java).smoothCorner?.invoke(materialView, false) }
        materialView.clipToOutline = true
        materialView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
    }

    fun configure(dark: Boolean, enabled: Boolean, radius: Float, corners: Float, passWindow: Boolean, shadow: Float,
        role: ChromeMaterialRole, strength: Float) {
        val nextRadius = miuiBlurRadius(radius)
        if (appliedNative && appliedRole != role) clearNative()
        val outlineChanged = cornerRadius != corners || this.role != role
        val materialChanged = this.dark != dark || userEnabled != enabled || blurRadius != nextRadius || this.passWindow != passWindow || this.role != role || this.strength != strength
        this.dark = dark
        userEnabled = enabled
        blurRadius = nextRadius
        this.passWindow = passWindow
        this.role = role
        this.strength = strength
        // setMiShadow owns elevation (64 px for Regular). Resetting it on every
        // recomposition erased the cached shadow without reapplying the material.
        val api = SystemChromeApi.lookup(View::class.java)
        val hasShadow = if (role == ChromeMaterialRole.Navigation) api.supportsNavigationShadow else api.supportsShadow
        if (!hasShadow) materialView.elevation = shadow
        if (outlineChanged) {
            cornerRadius = corners
            materialView.invalidateOutline()
            updateCornerClip()
        }
        if (materialChanged) applyMaterial()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        materialView.invalidateOutline()
        updateCornerClip()
    }

    private fun updateCornerClip() {
        excludedCorners.rewind()
        materialBoundary.rewind()
        invalidate()
        if (width <= 0 || height <= 0 || cornerRadius <= 0f) return
        // A circle has no straight junction. Capsules do: they use exactly the
        // approved G2 shoulder while retaining the original optical RoundRect.
        if (width == height && cornerRadius >= width / 2f) return
        materialBoundary.addG2Rect(width.toFloat(), height.toFloat(), cornerRadius)
        excludedCorners.fillType = Path.FillType.WINDING
        excludedCorners.addRoundRect(0f,0f,width.toFloat(),height.toFloat(),cornerRadius,cornerRadius,Path.Direction.CW)
        // Remove only the narrow filled sliver between the original circle and
        // its G2 shoulder. Bounding-rectangle minus G2 would erase native shadow
        // in the empty corners and recreate the pale rectangular seam.
        if (!excludedCorners.op(materialBoundary, Path.Op.DIFFERENCE)) {
            excludedCorners.rewind()
            android.util.Log.w("StarMarkMaterial", "Local G2 material mask could not be composed")
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        if (!excludedCorners.isEmpty) canvas.clipOutPath(excludedCorners)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyMaterial()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) appliedDark = null
        if (hasWindowFocus) applyMaterial()
    }

    override fun onDetachedFromWindow() {
        clearNative()
        super.onDetachedFromWindow()
    }

    private fun clearNative() {
        if (appliedNative) {
            SystemChromeApi.lookup(View::class.java).clear(this, materialView, appliedRole)
            val methods = MiuiViewMethods.lookup(View::class.java)
            runCatching { methods.backgroundRadius?.invoke(this, 0) }
            runCatching { methods.backgroundMode?.invoke(this, 0) }
            methods.setPassWindow(this, false)
        }
        appliedNative = false
        appliedRadius = -1
        appliedPassWindow = false
        appliedDark = null
    }

    private fun applyMaterial() {
        val permitted = userEnabled && isAttachedToWindow && isHardwareAccelerated && !invocationFailed && MiuiMaterialCapability.glassSupported(context)
        val methods = if (permitted) MiuiViewMethods.lookup(View::class.java) else null
        val passAllowed = !passWindow || methods?.canPassWindow(this, context.packageName) == true
        if (permitted && passAllowed && methods != null) {
            if ((!appliedNative || appliedPassWindow != passWindow) &&
                (passWindow || appliedPassWindow) && !methods.setPassWindow(this, passWindow)) {
                // Cross-window denial affects this popup only, not the device's ordinary glass.
                onWindowFallback()
                clearNative()
                materialView.setBackgroundColor(uiBlurFallbackArgb(dark).toInt())
                return
            }
            val success = runCatching {
                if (!appliedNative || appliedRadius != blurRadius || appliedPassWindow != passWindow || appliedDark != dark || appliedRole != role || appliedStrength != strength) {
                    check(SystemChromeApi.lookup(View::class.java).apply(this, materialView,
                        blurRadius, resources.displayMetrics.density, dark, role, strength,
                        tryGlassOptics = MiuiMaterialCapability.glassOpticsEligible))
                }
            }.isSuccess
            if (success) {
                appliedNative = true
                appliedRadius = blurRadius
                appliedPassWindow = passWindow
                appliedDark = dark
                appliedRole = role
                appliedStrength = strength
                // The reference's color blend sequence is the material; a second translucent
                // drawable would flatten its contrast and tint both themes a second time.
                materialView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                return
            }
            // A vendor may expose a method but deny its invocation. Do not retry every frame.
            invocationFailed = true
            if (role == ChromeMaterialRole.Navigation) onWindowFallback() else MiuiMaterialCapability.markGlassFailure()
            // Clear partial setup even when a later call failed.
            appliedNative = true
        }
        // A popup may lack cross-window permission even when ordinary glass works.
        // Fall back for that popup alone instead of disabling glass application-wide.
        if (permitted && !passAllowed) onWindowFallback()
        clearNative()
        val fallback = if (role == ChromeMaterialRole.Navigation) ChromeMaterialRecipe.navigationSolid(dark) else uiBlurFallbackArgb(dark)
        materialView.setBackgroundColor(fallback.toInt())
    }
}

/**
 * Xiaomi's View glass material when available. The frosted and disabled modes use
 * [ChromeSurface], and a denied vendor call switches the composition back to frosted.
 * Content self-blur is independently applied by [MiuiSelfBlurHost].
 */
@Composable
internal fun MiuiBlurSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    blurRadius: Dp = ChromeMaterialRecipe.nativeBlurRadiusDp.dp,
    shadowElevation: Dp = 4.dp,
    passWindow: Boolean = false,
    role: ChromeMaterialRole = ChromeMaterialRole.Standard,
    strength: Float = 1f,
    content: @Composable () -> Unit,
) {
    val dark = MiuixTheme.colorScheme.background.luminance() < .5f
    val effects = LocalStarMarkEffects.current
    var windowFallback by remember(passWindow, effects.mode, role) { mutableStateOf(false) }
    if (effects.systemGlass && windowFallback) {
        CompositionLocalProvider(LocalStarMarkEffects provides effects.copy(mode = GlassMode.Frosted)) {
            ChromeSurface(modifier, cornerRadius, shadowElevation, role, strength, content)
        }
        return
    }
    if (!effects.systemGlass) {
        ChromeSurface(modifier, cornerRadius, shadowElevation, role, strength, content)
        return
    }
    val density = LocalDensity.current
    val radiusPx = with(density) { blurRadius.toPx() }
    val cornersPx = with(density) { cornerRadius.toPx() }
    val shadowPx = with(density) { shadowElevation.toPx() }
    // Keep the native outline/elevation on the material View. The material and the sharp
    // content share one measured box, with no additional Compose Surface clip around them.
    CompositionLocalProvider(LocalContentColor provides MiuixTheme.colorScheme.onSurface) {
        Box(modifier.semantics { isTraversalGroup = true }, propagateMinConstraints = true) {
            AndroidView(
                factory = { MiuiBackdropView(it) { windowFallback = true } },
                modifier = Modifier.matchParentSize(),
                update = { it.configure(dark, effects.systemGlass, radiusPx, cornersPx, passWindow, shadowPx, role, strength) },
            )
            content()
        }
    }
}
