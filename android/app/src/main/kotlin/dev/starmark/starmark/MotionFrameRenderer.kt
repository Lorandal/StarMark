package dev.starmark.starmark

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.*
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Only exterior frames need a new video canvas. Overlay-only exports keep the exact MP4. */
@androidx.annotation.OptIn(UnstableApi::class)
internal object MotionFrameRenderer {
    fun render(context: Context, source: File, motion: MotionInfo, cover: File,
        geometry: MotionFrameGeometry, destination: File, removeFrame: Boolean = false): File {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Live 导出必须在后台执行" }
        val clip = File.createTempFile("motion-input-", ".mp4", destination.parentFile)
        val thread = HandlerThread("StarMark-MotionFrame").apply { start() }
        val handler = Handler(thread.looper)
        val finished = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        var transformer: Transformer? = null
        var overlay: Bitmap? = null
        try {
            RandomAccessFile(source, "r").use { input ->
                input.seek(motion.range.offset)
                clip.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var remaining = motion.range.length
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        check(count > 0) { "Live 视频不完整" }
                        output.write(buffer, 0, count); remaining -= count
                    }
                }
            }
            val retriever = MediaMetadataRetriever()
            val placement: MotionFrameGeometry.Placement
            val bitrate: Int
            try {
                retriever.setDataSource(clip.path)
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val uprightW = if (rotation % 180 == 0) w else h
                val uprightH = if (rotation % 180 == 0) h else w
                placement = if (removeFrame) geometry.crop(uprightW, uprightH) else geometry.place(uprightW, uprightH)
                val sourceBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 8_000_000L
                bitrate = (sourceBitrate * 1.25 * placement.width * placement.height / (w.toDouble() * h)).toLong().coerceIn(4_000_000L, 80_000_000L).toInt()
            } finally { retriever.release() }
            val frame = if (removeFrame) null else {
              val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = 1
                while (geometry.canvasWidth / (inSampleSize * 2) >= placement.width && geometry.canvasHeight / (inSampleSize * 2) >= placement.height) inSampleSize *= 2
            }
              val decoded = BitmapFactory.decodeFile(cover.path, options) ?: error("Live 边框读取失败")
              val frame = Bitmap.createBitmap(placement.width, placement.height, Bitmap.Config.ARGB_8888)
            overlay = frame
            Canvas(frame).apply {
                drawBitmap(decoded, null, Rect(0, 0, frame.width, frame.height), Paint(Paint.FILTER_BITMAP_FLAG))
                drawRect(RectF(geometry.photoX.toFloat() * frame.width / geometry.canvasWidth,
                    geometry.photoY.toFloat() * frame.height / geometry.canvasHeight,
                    (geometry.photoX + geometry.photoWidth).toFloat() * frame.width / geometry.canvasWidth,
                    (geometry.photoY + geometry.photoHeight).toFloat() * frame.height / geometry.canvasHeight),
                    Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
            }
            decoded.recycle()
            // Media3's HDR overlay shader requires an Ultra HDR bitmap even for an SDR
            // border. A neutral gainmap preserves the frame's intended SDR luminance.
            if (Build.VERSION.SDK_INT >= 34) {
                frame.setGainmap(Gainmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }).apply {
                    setRatioMin(1f, 1f, 1f); setRatioMax(1f, 1f, 1f)
                    setGamma(1f, 1f, 1f); setEpsilonSdr(0f, 0f, 0f); setEpsilonHdr(0f, 0f, 0f)
                    setDisplayRatioForFullHdr(2f); setMinDisplayRatioForHdrTransition(1f)
                })
            }
              frame
            }
            val transformation = object : MatrixTransformation {
                private var matrix = Matrix()
                override fun configure(inputWidth: Int, inputHeight: Int): Size {
                    val fitted = if (removeFrame) geometry.crop(inputWidth, inputHeight) else geometry.place(inputWidth, inputHeight)
                    check(fitted.width == placement.width && fitted.height == placement.height) { "Live 视频方向或像素比例不一致" }
                    matrix = Matrix().apply {
                        setScale(fitted.scaleX, fitted.scaleY)
                        postTranslate(fitted.translateX, fitted.translateY)
                    }
                    return Size(placement.width, placement.height)
                }
                override fun getMatrix(presentationTimeUs: Long) = matrix
            }
            handler.post {
                try {
                    val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(clip)))
                        .setEffects(Effects(emptyList(), buildList {
                            add(transformation)
                            if (frame != null) add(OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(frame))))
                        })).build()
                    transformer = Transformer.Builder(context.applicationContext).setLooper(thread.looper)
                        .setVideoMimeType(MimeTypes.VIDEO_H265)
                        .setEncoderFactory(DefaultEncoderFactory.Builder(context.applicationContext)
                            .setEnableFallback(false)
                            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build()).build())
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) { finished.countDown() }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                error.set(exportException); finished.countDown()
                            }
                            override fun onFallbackApplied(composition: Composition, original: TransformationRequest, fallback: TransformationRequest) {
                                if (original.hdrMode != fallback.hdrMode) {
                                    error.set(IllegalStateException("当前设备无法保留这段 Live 视频的 HDR，请选择其他水印或静态导出"))
                                    transformer?.cancel(); finished.countDown()
                                }
                            }
                        }).build()
                    transformer!!.start(item, destination.path)
                } catch (failure: Throwable) { error.set(failure); finished.countDown() }
            }
            check(finished.await(120, TimeUnit.SECONDS)) { "Live 视频处理超时，请重试" }
            error.get()?.let { throw IllegalStateException("Live 视频边框处理失败：${it.message}", it) }
            check(destination.length() > 0) { "Live 视频导出为空" }
            if (removeFrame) MotionColorMetadata.restoreCroppedP3(clip, destination)
            // No silent encoder resize: the player must receive the same canvas aspect as the cover.
            val outputInfo = MediaMetadataRetriever()
            try {
                outputInfo.setDataSource(destination.path)
                val w = outputInfo.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
                val h = outputInfo.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
                val rotation = outputInfo.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val ratio = if (rotation % 180 == 0) w.toDouble() / h else h.toDouble() / w
                val targetRatio = if (removeFrame) geometry.photoWidth.toDouble() / geometry.photoHeight
                    else geometry.canvasWidth.toDouble() / geometry.canvasHeight
                check(kotlin.math.abs(ratio / targetRatio - 1) < 0.005) { "Live 视频画布比例不一致" }
            } finally { outputInfo.release() }
            return destination
        } finally {
            val released = CountDownLatch(1)
            handler.post { try { transformer?.cancel() } finally { released.countDown() } }
            released.await(5, TimeUnit.SECONDS)
            thread.quitSafely()
            thread.join(5000)
            overlay?.recycle()
            clip.delete()
        }
    }
}
