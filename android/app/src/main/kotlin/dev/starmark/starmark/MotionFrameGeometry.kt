package dev.starmark.starmark

import kotlin.math.min
import kotlin.math.roundToInt

/** Pixel geometry, independent of video rotation (input dimensions are already upright). */
internal data class MotionFrameGeometry(
    val canvasWidth: Int, val canvasHeight: Int,
    val photoX: Int, val photoY: Int, val photoWidth: Int, val photoHeight: Int,
) {
    init {
        require(canvasWidth > 0 && canvasHeight > 0 && photoWidth > 0 && photoHeight > 0)
        require(photoX >= 0 && photoY >= 0 && photoX.toLong() + photoWidth <= canvasWidth && photoY.toLong() + photoHeight <= canvasHeight)
    }
    val hasFrame get() = photoX != 0 || photoY != 0 || photoWidth != canvasWidth || photoHeight != canvasHeight
    data class Placement(val width: Int, val height: Int, val x: Float, val y: Float, val videoWidth: Float, val videoHeight: Float) {
        val scaleX get() = videoWidth / width
        val scaleY get() = videoHeight / height
        val translateX get() = (x + videoWidth / 2) * 2 / width - 1
        val translateY get() = 1 - (y + videoHeight / 2) * 2 / height
    }
    fun place(videoWidth: Int, videoHeight: Int): Placement {
        require(videoWidth > 0 && videoHeight > 0)
        val resolutionScale = min(1.0, min(min(videoWidth.toDouble() / photoWidth, videoHeight.toDouble() / photoHeight), 2160.0 / maxOf(canvasWidth, canvasHeight)))
        fun even(value: Double) = (value / 2).roundToInt().coerceAtLeast(1) * 2
        val w = even(canvasWidth * resolutionScale)
        val h = even(canvasHeight * resolutionScale)
        val areaW = photoWidth.toDouble() * w / canvasWidth
        val areaH = photoHeight.toDouble() * h / canvasHeight
        val scale = min(areaW / videoWidth, areaH / videoHeight)
        return Placement(w, h,
            (photoX.toDouble() * w / canvasWidth + (areaW - videoWidth * scale) / 2).toFloat(),
            (photoY.toDouble() * h / canvasHeight + (areaH - videoHeight * scale) / 2).toFloat(),
            (videoWidth * scale).toFloat(), (videoHeight * scale).toFloat())
    }

    /** Camera frame removal: crop the encoded canvas, never squeeze it into the photo area. */
    fun crop(videoWidth: Int, videoHeight: Int): Placement {
        require(videoWidth > 0 && videoHeight > 0 && hasFrame)
        require(kotlin.math.abs((videoWidth.toDouble() / videoHeight) /
            (canvasWidth.toDouble() / canvasHeight) - 1) < .005) { "Live 原视频与相机外框比例不一致" }
        fun even(value: Double) = (value / 2).roundToInt().coerceAtLeast(1) * 2
        val w = even(videoWidth.toDouble() * photoWidth / canvasWidth)
        val h = even(videoHeight.toDouble() * photoHeight / canvasHeight)
        return Placement(w, h, -photoX.toFloat() * w / photoWidth, -photoY.toFloat() * h / photoHeight,
            canvasWidth.toFloat() * w / photoWidth, canvasHeight.toFloat() * h / photoHeight)
    }
}
