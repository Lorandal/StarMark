package dev.starmark.starmark.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import kotlin.math.roundToInt

/** A small immutable snapshot of the Flutter texture, never a screenshot of the native shell. */
internal data class ChromePhotoFrame(
    val image: ImageBitmap,
    val boundsInWindow: Rect,
    val visibleBoundsInWindow: Rect,
    val alpha: Float,
)

/** Excludes unrelated UI messages so opening a menu cannot create an endless capture loop. */
internal data class ChromePhotoRevision(
    val page: String?,
    val photoId: String?,
    val revision: Long?,
    val materialRevision: Long?,
    val busy: Boolean = false,
    val geometryRevision: Int = 0,
)

internal val LocalChromePhotoCapture = staticCompositionLocalOf<() -> ChromePhotoFrame?> { { null } }
internal val LocalChromePhotoRevision = staticCompositionLocalOf { ChromePhotoRevision(null, null, null, null) }

@Composable
private fun rememberChromePhotoFrame(
    record: Boolean,
    revision: ChromePhotoRevision,
    slotSize: Size? = null,
): State<ChromePhotoFrame?> {
    val capture = LocalChromePhotoCapture.current
    val frame = remember(record, revision.page, revision.photoId, capture) { mutableStateOf<ChromePhotoFrame?>(null) }
    // A bounded refresh also covers the asynchronous Flutter paint following an edit.
    LaunchedEffect(record, revision, capture, slotSize) {
        if (!record || revision.busy) return@LaunchedEffect
        delay(100)
        withFrameNanos { }
        frame.value = capture()
        delay(200)
        withFrameNanos { }
        capture()?.let { frame.value = it }
    }
    return frame
}

/** The scrolling template preview is a sibling Flutter texture. Only its pixels are cached;
 * the header recorder positions and clips them at the actual slot on every scroll frame. */
@Composable
internal fun rememberTemplateHeaderBackdrop(record: Boolean, bounds: State<Rect?>, background: Color): LayerBackdrop {
    val view = LocalView.current
    val slotSize by remember(bounds, view) { derivedStateOf {
        bounds.value?.takeIf { it.bottom > 0f && it.top < view.height && !it.isEmpty }?.size
    } }
    // A scroll changes the host's geometryRevision but not the watermarked photo pixels.
    val revision = LocalChromePhotoRevision.current.copy(geometryRevision = 0)
    val frame by rememberChromePhotoFrame(record && slotSize != null, revision, slotSize)
    val path = remember { Path() }
    return rememberLayerBackdrop(onDraw = {
        drawRect(background)
        val slot = bounds.value
        val image = frame?.image
        if (slot != null && image != null && !slot.isEmpty) {
            path.reset()
            path.addG2Rect(slot.width, slot.height, TemplatePreviewRadius.toPx(), slot.topLeft)
            clipPath(path) {
                drawImage(image, dstOffset = IntOffset(slot.left.roundToInt(), slot.top.roundToInt()),
                    dstSize = IntSize(slot.width.roundToInt().coerceAtLeast(1), slot.height.roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Low)
            }
        }
        drawContent()
    })
}

/** Completes the full-size popup recorder with the external Flutter photo underneath it. */
@Composable
internal fun rememberModalBackdrop(record: Boolean): LayerBackdrop {
    val photo by rememberChromePhotoFrame(record, LocalChromePhotoRevision.current)
    val view = LocalView.current
    val windowOrigin = remember(view) { IntArray(2) }

    // Closing drops both the snapshot state and its recorded GraphicsLayer. Let reference
    // ownership release bitmap storage; recycle() could race the render thread's retained image.
    return key(record) {
        rememberLayerBackdrop(onDraw = {
            if (record) photo?.let { frame ->
                // ChromeOverlayHost fills the Compose root: this origin is also its draw origin.
                view.getLocationInWindow(windowOrigin)
                val x = windowOrigin[0].toFloat()
                val y = windowOrigin[1].toFloat()
                val bounds = frame.boundsInWindow
                val clip = frame.visibleBoundsInWindow
                clipRect(clip.left - x, clip.top - y, clip.right - x, clip.bottom - y) {
                    drawImage(
                        image = frame.image,
                        dstOffset = IntOffset((bounds.left - x).roundToInt(), (bounds.top - y).roundToInt()),
                        dstSize = IntSize(bounds.width.roundToInt().coerceAtLeast(1), bounds.height.roundToInt().coerceAtLeast(1)),
                        alpha = frame.alpha,
                        filterQuality = FilterQuality.Low,
                    )
                }
            }
            drawContent()
        })
    }
}
