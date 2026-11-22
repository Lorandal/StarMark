package dev.starmark.starmark.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.TextureView
import android.view.MotionEvent
import android.os.Looper
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt

/** One retained Flutter fragment below the complete native Compose interface. */
class MiuixHostLayout(context: Context) : FrameLayout(context) {
    internal var photoGeometryRevision by mutableIntStateOf(0)
        private set
    private var shell: View? = null
    private var canvasView: View? = null
    private var viewport: android.graphics.Rect? = null
    private var surfaceBounds: android.graphics.Rect? = null
    private var fitBias: Float? = null
    private var photoScale = 1f
    private var photoOffsetX = 0f
    private var photoOffsetY = 0f
    private var pageOffsetX = 0f
    private var previewClip: Rect? = null
    private var previewRadius = 0f
    private val previewPath = android.graphics.Path()
    private var previewPathDirty = true
    private var pageAlpha = 0f

    fun updateCanvasClip(rect: Rect?, radius: Float) {
        if (previewClip == rect && previewRadius == radius) return
        previewClip = rect; previewRadius = radius
        previewPathDirty = true
        invalidate()
    }

    override fun drawChild(canvas: android.graphics.Canvas, child: View, drawingTime: Long): Boolean {
        if (child !== canvasView || previewRadius <= 0f) return super.drawChild(canvas, child, drawingTime)
        val area = viewport ?: return false
        val visible = previewClip ?: return false
        if (previewPathDirty) {
            previewPath.rewind()
            previewPath.addG2Rect(area.width().toFloat(), area.height().toFloat(), previewRadius, area.left.toFloat(), area.top.toFloat())
            previewPathDirty = false
        }
        val save = canvas.save()
        canvas.translate(child.translationX, 0f)
        canvas.clipPath(previewPath)
        canvas.translate(-child.translationX, 0f)
        canvas.clipRect(visible.left + child.translationX, visible.top, visible.right + child.translationX, visible.bottom)
        val drawn = super.drawChild(canvas, child, drawingTime)
        canvas.restoreToCount(save)
        return drawn
    }
    private var canvasInput = false

    fun updatePageMotion(offset: Float, alpha: Float, interactive: Boolean) {
        pageAlpha = alpha.coerceIn(0f, 1f)
        canvasInput = interactive
        pageOffsetX = offset
        canvasView?.translationX = pageOffsetX + photoOffsetX
        canvasView?.alpha = pageAlpha
        updateCanvasVisibility()
    }

    private fun updateCanvasVisibility() {
        canvasView?.visibility = if (viewport != null && pageAlpha > 0f) VISIBLE else INVISIBLE
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        if (!canvasInput) shell?.dispatchTouchEvent(event) ?: false else super.dispatchTouchEvent(event)

    fun installShell(view: View) {
        check(shell == null) { "Only one native shell may be attached" }
        shell = view
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        bringChildToFront(view)
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        if (child !== shell) {
            check(canvasView == null || canvasView === child) { "Only one Flutter view may be hosted" }
            canvasView = child
            applyPhotoPlacement()
            child.alpha = pageAlpha
            updateCanvasVisibility()
            shell?.let(::bringChildToFront)
        }
    }

    override fun onViewRemoved(child: View) {
        if (canvasView === child) canvasView = null
        super.onViewRemoved(child)
    }

    private fun Rect?.pixelBounds() = this?.takeIf {
        it.left.isFinite() && it.top.isFinite() && it.right.isFinite() &&
            it.bottom.isFinite() && it.width >= 1f && it.height >= 1f
    }?.let {
        android.graphics.Rect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt())
            .takeIf { bounds -> bounds.width() > 0 && bounds.height() > 0 }
    }

    fun updateViewport(rect: Rect?, surface: Rect? = rect, batchFitBias: Float? = null) {
        val next = rect.pixelBounds()
        val nextSurface = surface.pixelBounds()
        if (next == viewport && nextSurface == surfaceBounds && fitBias == batchFitBias) return
        val resize = nextSurface != surfaceBounds
        viewport = next
        surfaceBounds = nextSurface
        fitBias = batchFitBias
        applyPhotoPlacement()
        previewPathDirty = true
        photoGeometryRevision++
        updateCanvasVisibility()
        if (resize) requestLayout() else invalidate()
    }

    /** Move the existing texture along with the drawer. The Flutter buffer retains its resolution. */
    private fun applyPhotoPlacement() {
        val child = canvasView ?: return
        val surface = surfaceBounds
        val photo = viewport
        val bias = fitBias
        if (photo != null && surface != null && bias != null) {
            val buffer = EditorPaneBounds(0f, 0f, surface.width().toFloat(), surface.height().toFloat())
            val original = EditorPhotoViewport(buffer, bias).fit(photo.width().toFloat() / photo.height())
            photoScale = photo.width() / original.width
            photoOffsetX = photo.left - surface.left - original.left * photoScale
            photoOffsetY = photo.top - surface.top - original.top * photoScale
            child.clipBounds = android.graphics.Rect(kotlin.math.floor(original.left).toInt(), kotlin.math.floor(original.top).toInt(),
                kotlin.math.ceil(original.right).toInt(), kotlin.math.ceil(original.bottom).toInt())
        } else {
            photoScale = 1f
            photoOffsetX = 0f
            photoOffsetY = 0f
            child.clipBounds = null
        }
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = photoScale
        child.scaleY = photoScale
        child.translationX = pageOffsetX + photoOffsetX
        child.translationY = photoOffsetY
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        shell?.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
        val area = surfaceBounds
        canvasView?.measure(
            MeasureSpec.makeMeasureSpec(area?.width() ?: 1, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(area?.height() ?: 1, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val area = surfaceBounds
        canvasView?.layout(area?.left ?: 0, area?.top ?: 0, area?.right ?: 1, area?.bottom ?: 1)
        shell?.layout(0, 0, right - left, bottom - top)
    }

    /** Read-only geometry used by the native UI integration test. */
    fun canvasBounds(): android.graphics.Rect? = viewport?.let { android.graphics.Rect(it) }

    /** Main-thread, bounded readback of only the external Flutter photo viewport. */
    internal fun capturePhotoForChrome(): ChromePhotoFrame? {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Texture capture must run on the main thread" }
        val canvas = canvasView ?: return null
        if (viewport == null || pageAlpha <= 0f || !canvas.isShown || !canvas.isAttachedToWindow) return null
        fun findTexture(view: View): TextureView? {
            if (!view.isShown || view.alpha <= 0f) return null
            if (view is TextureView && view.isAvailable && view.width > 0 && view.height > 0) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                findTexture(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        val texture = findTexture(canvas) ?: return null
        val rootVisible = android.graphics.Rect()
        if (!texture.getGlobalVisibleRect(rootVisible) || rootVisible.isEmpty) return null
        val location = IntArray(2).also(texture::getLocationInWindow)
        val left = location[0].toFloat()
        val top = location[1].toFloat()
        val bounds = Rect(left, top, left + texture.width * photoScale, top + texture.height * photoScale)
        val photo = viewport ?: return null
        // The visible rect already includes ancestor transforms. Intersect all three rectangles in window pixels.
        val rootOrigin = IntArray(2).also(texture.rootView::getLocationInWindow)
        val hostOrigin = IntArray(2).also(::getLocationInWindow)
        val x = hostOrigin[0] + pageOffsetX
        val y = hostOrigin[1].toFloat()
        val visible = Rect((rootOrigin[0] + rootVisible.left).toFloat(), (rootOrigin[1] + rootVisible.top).toFloat(),
            (rootOrigin[0] + rootVisible.right).toFloat(), (rootOrigin[1] + rootVisible.bottom).toFloat())
            .intersect(bounds)
            .intersect(Rect(x + photo.left, y + photo.top, x + photo.right, y + photo.bottom))
        if (visible.isEmpty) return null
        var alpha = 1f
        var ancestor: View? = texture
        while (ancestor != null) {
            alpha *= ancestor.alpha
            if (ancestor === canvas) break
            ancestor = ancestor.parent as? View
        }
        if (alpha <= 0f) return null
        // TextureView.getBitmap(w,h) scales directly into this small buffer. Calling the
        // no-argument overload then resizing would allocate a full-resolution photo first.
        val scale = minOf(1f, 384f / maxOf(texture.width, texture.height))
        val width = (texture.width * scale).roundToInt().coerceIn(1, 384)
        val height = (texture.height * scale).roundToInt().coerceIn(1, 384)
        return runCatching {
            texture.getBitmap(width, height)?.let {
                ChromePhotoFrame(it.asImageBitmap(), bounds, visible, alpha.coerceIn(0f, 1f))
            }
        }.getOrNull()
    }
}
