package dev.starmark.starmark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Locale

/** Restores only pixels actually stored by the camera. Never changes [source]. */
internal object XiaomiWatermarkRecovery {
    data class Inspection(val canRestore: Boolean, val reason: String, val mode: String? = null)
    data class RecoveryResult(val file: File, val mode: String, val byteExact: Boolean, val mimeType: String)

    private data class Plan(
        val sourceLength: Long, val sourceModified: Long, val exif: ExifInterface,
        val bytes: ByteArray, val mode: String, val width: Int, val height: Int,
        val rotation: Int = 0, val patchMatrix: FloatArray? = null,
        val hdr: Boolean = false, val motion: Boolean = false,
        val frame: XiaomiFrameCrop? = null,
        val streamFrame: Boolean = false,
        val streamPatch: Boolean = false,
    )

    fun inspect(source: File): Inspection = try {
        val plan = plan(source)
        Inspection(true, when (plan.mode) {
            "original_image" -> "可提取相机保存的完整原图"
            "madrid_frame" -> "可按相机记录的原图区域移除外框"
            else -> "可恢复相机保存的原始局部像素"
        }, plan.mode)
    } catch (error: Exception) {
        Inspection(false, "不支持恢复：${error.message ?: "恢复数据无效"}")
    } catch (_: OutOfMemoryError) {
        Inspection(false, "不支持恢复：可用内存不足，已保留原片")
    }

    /** SDR static patches use lossless PNG; HDR/Live covers require JPEG. */
    fun recover(source: File, destination: File, context: android.content.Context? = null): RecoveryResult? {
        var temporary: File? = null
        return try {
            require(source.canonicalFile != destination.canonicalFile && !destination.exists()) { "输出不能覆盖原件或已有文件" }
            val plan = plan(source)
            val parent = destination.absoluteFile.parentFile
            require(parent?.isDirectory == true) { "输出目录不存在" }
            temporary = File.createTempFile("starmark-recovery-", ".tmp", parent)
            val mime = if (plan.mode == "original_image") {
                temporary.writeBytes(plan.bytes)
                plan.frame?.let { frame ->
                    // The camera's embedded original can carry its processing timestamp/model
                    // suffix. Keep the photograph's published EXIF without reencoding pixels.
                    PhotoContainers.rewriteExifPreservingAuxiliary(temporary) { file ->
                        val outputExif = ExifInterface(file)
                        PhotoExportMetadata.apply(plan.exif, outputExif, frame.width, frame.height)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path, bounds)
                        outputExif.setAttribute(ExifInterface.TAG_COLOR_SPACE, if (bounds.outColorSpace?.isSrgb == true) "1" else "65535")
                        outputExif.saveAttributes()
                    }
                }
                "image/jpeg"
            } else {
                if (plan.frame != null) restoreFrame(source, temporary, plan) else restorePatch(source, temporary, plan)
                if (plan.hdr || plan.motion || plan.streamFrame) "image/jpeg" else "image/png"
            }
            if (plan.motion) {
                val cover = temporary
                val packed = File.createTempFile("starmark-motion-recovery-", ".jpg", parent)
                var video: File? = null
                try {
                    if (plan.frame?.video == true) {
                        video = File(parent, "cropped-motion-${System.nanoTime()}.mp4")
                        val frame = plan.frame
                        MotionFrameRenderer.render(requireNotNull(context), source,
                            requireNotNull(PhotoContainers.inspect(source).motion), cover,
                            MotionFrameGeometry(plan.width, plan.height, frame.x, frame.y, frame.width, frame.height),
                            video, removeFrame = true)
                    }
                    PhotoContainers.appendMotionToCover(cover, source, packed, video)
                    cover.delete()
                    temporary = packed
                } catch (error: Throwable) { packed.delete(); throw error }
                finally { video?.delete() }
            }
            require(source.length() == plan.sourceLength && source.lastModified() == plan.sourceModified) { "恢复期间原件发生变化" }
            // No replace option: a competing writer cannot make us overwrite another file.
            Files.move(temporary.toPath(), destination.toPath())
            temporary = null
            RecoveryResult(destination, plan.mode, plan.mode == "original_image" && !plan.motion && plan.frame == null, mime)
        } catch (error: Exception) {
            android.util.Log.w("StarMarkRecovery", "Camera recovery failed; original retained", error)
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            temporary?.delete()
        }
    }

    private fun plan(source: File): Plan {
        require(source.isFile && source.length() in 4..(512L * 1024 * 1024)) { "文件缺失或超过恢复检查预算" }
        val length = source.length()
        val modified = source.lastModified()
        val container = PhotoContainers.inspect(source)
        require(container.jpegEnd > 0) { "目前仅支持 JPEG 恢复数据" }
        require(container.reversible == null) { "请先提取 StarMark 规范原件" }
        val exif = ExifInterface(source)
        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.lowercase(Locale.ROOT)
        require(make in setOf("xiaomi", "redmi", "poco", "xiaomi communications co., ltd.")) { "缺少小米相机 EXIF 来源" }
        val xmp = exif.getAttribute(ExifInterface.TAG_XMP) ?: error("没有相机保存的水印恢复数据")
        val meta = XiaomiRecoveryMetadata.parse(xmp)
        require(!meta.unsupported) { "特殊水印或编辑信息尚未验证（包括类型 143）" }
        val coverEnd = PhotoContainers.validatedCoverEnd(source)
        meta.validatedRanges(length, coverEnd, container.motion?.range?.offset ?: length)
        val hdr = PhotoHdr.inspect(source)
        val sourceHdr = hdr.mayContainGainmap || hdr.hasGainmap == true
        meta.frame?.takeIf { meta.original == null }?.let { frame ->
            require(meta.blocks.isEmpty() && meta.patch == null) { "组合外框恢复数据尚未验证" }
            require(!sourceHdr || (Build.VERSION.SDK_INT >= 34 && hdr.hasGainmap == true)) { "外框 HDR 增益图无法解码" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            checkDimensions(bounds.outWidth, bounds.outHeight, TiledPhotoExporter.MAX_PIXELS)
            frame.validate(bounds.outWidth, bounds.outHeight)
            checkDimensions(frame.width, frame.height, TiledPhotoExporter.SOURCE_PIXELS)
            val stream = bounds.outWidth.toLong() * bounds.outHeight + frame.width.toLong() * frame.height > 32_000_000L
            require(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) in 0..1) { "旋转外框坐标尚未验证" }
            return Plan(length, modified, exif, ByteArray(0), "madrid_frame", bounds.outWidth, bounds.outHeight,
                hdr = sourceHdr, motion = container.motion != null, frame = frame, streamFrame = stream)
        }
        val original = meta.original
        if (original != null) {
            val bytes = readBlock(source, original.range(length, container.jpegEnd))
            require(jpegEnd(bytes) > 0) { "完整原图不是有效 JPEG" }
            val bounds = bounds(bytes)
            require(bounds.outMimeType == "image/jpeg") { "完整原图编码不受支持" }
            var canvasWidth = bounds.outWidth
            var canvasHeight = bounds.outHeight
            meta.frame?.let { frame ->
                require(meta.patch == null && meta.blocks.size == 1) { "组合恢复块尚未验证" }
                val canvas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source.path, canvas)
                checkDimensions(canvas.outWidth, canvas.outHeight, TiledPhotoExporter.MAX_PIXELS)
                checkDimensions(frame.width, frame.height, TiledPhotoExporter.SOURCE_PIXELS)
                frame.validate(canvas.outWidth, canvas.outHeight)
                require(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) in 0..1 &&
                    bounds.outWidth == frame.width && bounds.outHeight == frame.height) { "完整原图与外框恢复区域不符" }
                canvasWidth = canvas.outWidth; canvasHeight = canvas.outHeight
            }
            val sample = decodeSample(bytes, bounds.outWidth, bounds.outHeight)
            try {
                val hasGainmap = Build.VERSION.SDK_INT >= 34 && sample.hasGainmap()
                require(!sourceHdr || hasGainmap) { "完整恢复块不能确认保留 HDR，已保留原片" }
                val originalExif = ExifInterface(ByteArrayInputStream(bytes))
                require(meta.frame == null || originalExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) in 0..1) { "完整原图方向与外框不符" }
                val originalXmp = originalExif.getAttribute(ExifInterface.TAG_XMP).orEmpty()
                require(!XiaomiRecoveryMetadata.hasMotionDeclaration(originalXmp)) { "内嵌原图含未验证动态容器" }
                // With no gainmap, unexplained bytes after the JPEG are not a verified original.
                require(hasGainmap || jpegEnd(bytes) == bytes.size) { "完整原图包含未知附件" }
            } finally { sample.recycle() }
            return Plan(length, modified, exif, bytes, "original_image", canvasWidth, canvasHeight,
                motion = container.motion != null, frame = meta.frame)
        }
        // Xiaomi's editor draws the saved SDR patch onto the base and retains its pre-watermark
        // gainmap (Lqw/t.y / Lgv/q.b). We retain that map; we do not generate new HDR pixels.
        require(!sourceHdr || (Build.VERSION.SDK_INT >= 34 && hdr.hasGainmap == true)) {
            "原片 HDR 增益图无法解码，已保留原片"
        }
        val patch = meta.patch ?: error("没有完整原图或原始局部图像")
        val sourceBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, sourceBounds)
        checkDimensions(sourceBounds.outWidth, sourceBounds.outHeight, TiledPhotoExporter.SOURCE_PIXELS)
        for ((tag, expected) in listOf(ExifInterface.TAG_PIXEL_X_DIMENSION to sourceBounds.outWidth, ExifInterface.TAG_PIXEL_Y_DIMENSION to sourceBounds.outHeight)) {
            val declared = exif.getAttributeInt(tag, 0)
            require(declared == 0 || declared == expected) { "照片尺寸与相机元数据不符" }
        }
        val rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED -> 0
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> error("暂不支持镜像方向恢复")
        }
        val bytes = readBlock(source, patch.block.range(length, container.jpegEnd))
        require(jpegEnd(bytes) == bytes.size || isCompletePng(bytes)) { "局部图像编码不完整" }
        val patchBounds = bounds(bytes)
        checkDimensions(patchBounds.outWidth, patchBounds.outHeight, TiledPhotoExporter.SOURCE_PIXELS)
        val workingPixels = sourceBounds.outWidth.toLong() * sourceBounds.outHeight * (if (rotation == 0) 1 else 2) +
            patchBounds.outWidth.toLong() * patchBounds.outHeight
        val matrix = patch.matrix(sourceBounds.outWidth, sourceBounds.outHeight, rotation, patchBounds.outWidth, patchBounds.outHeight)
        val sample = decodeSample(bytes, patchBounds.outWidth, patchBounds.outHeight)
        try {
            require(!sample.hasAlpha()) { "局部图像存在不确定的透明像素" }
            require(Build.VERSION.SDK_INT < 34 || !sample.hasGainmap()) { "局部图像含独立 HDR 信息" }
        }
        finally { sample.recycle() }
        return Plan(length, modified, exif, bytes, "subimage", sourceBounds.outWidth, sourceBounds.outHeight, rotation, matrix,
            sourceHdr, container.motion != null, streamPatch = workingPixels > 8_000_000L)
    }

    /** Crop only the camera-declared original rectangle; apply the same crop to its gainmap. */
    private fun restoreFrame(source: File, output: File, plan: Plan) {
        val frame = requireNotNull(plan.frame)
        if (plan.streamFrame) {
            PhotoFrameCropper.write(source, output, plan.width, plan.height, frame, plan.hdr)
            PhotoContainers.rewriteExifPreservingAuxiliary(output) { file ->
                val result = ExifInterface(file)
                PhotoExportMetadata.apply(plan.exif, result, frame.width, frame.height)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                result.setAttribute(ExifInterface.TAG_COLOR_SPACE, if (bounds.outColorSpace?.isSrgb == true) "1" else "65535")
                result.saveAttributes()
            }
            return
        }
        val base = requireNotNull(BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })) { "外框照片解码失败" }
        var cropped: Bitmap? = null
        var mapBitmap: Bitmap? = null
        try {
            require(base.width == plan.width && base.height == plan.height) { "外框照片尺寸发生变化" }
            val gainmap = if (plan.hdr && Build.VERSION.SDK_INT >= 34) {
                require(base.hasGainmap()) { "完整外框 HDR 增益图无法解码" }
                requireNotNull(base.gainmap).also { base.gainmap = null }
            } else null
            cropped = Bitmap.createBitmap(base, frame.x, frame.y, frame.width, frame.height)
            if (gainmap != null && Build.VERSION.SDK_INT >= 34) {
                val result = PhotoHdr.cropGainmap(gainmap, base.width, base.height,
                    Rect(frame.x, frame.y, frame.x + frame.width, frame.y + frame.height))
                mapBitmap = result.gainmapContents
                cropped.gainmap = result
            }
            val jpeg = plan.hdr || plan.motion
            output.outputStream().use { require(cropped.compress(
                if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 100, it)) { "外框恢复编码失败" } }
            fun writeExif(file: File) {
                val result = ExifInterface(file)
                PhotoExportMetadata.apply(plan.exif, result, frame.width, frame.height)
                result.setAttribute(ExifInterface.TAG_COLOR_SPACE, if (cropped.colorSpace?.isSrgb == true) "1" else "65535")
                result.saveAttributes()
            }
            if (jpeg) PhotoContainers.rewriteExifPreservingAuxiliary(output, ::writeExif) else writeExif(output)
            if (plan.hdr) require(PhotoHdr.inspect(output).hasGainmap == true) { "外框恢复未保留 HDR 增益图" }
        } finally {
            if (cropped !== base) cropped?.recycle()
            mapBitmap?.recycle()
            base.recycle()
        }
    }

    private fun restorePatch(source: File, output: File, plan: Plan) {
        if (plan.streamPatch) {
            val space = PhotoPatchRestorer.write(source, output, plan.bytes, plan.width, plan.height,
                plan.rotation, requireNotNull(plan.patchMatrix), plan.hdr, plan.motion)
            val swapped = !plan.hdr && plan.rotation % 180 != 0
            fun writeExif(file: File) {
                val exif = ExifInterface(file)
                PhotoExportMetadata.apply(plan.exif, exif, if (swapped) plan.height else plan.width,
                    if (swapped) plan.width else plan.height, space.isSrgb)
                if (plan.hdr) exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                    plan.exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL).toString())
                exif.saveAttributes()
            }
            if (plan.hdr || plan.motion) PhotoContainers.rewriteExifPreservingAuxiliary(output, ::writeExif) else writeExif(output)
            if (plan.hdr) require(PhotoHdr.inspect(output).hasGainmap == true) { "恢复编码未保留 HDR 增益图" }
            return
        }
        val options = BitmapFactory.Options().apply {
            inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val base = requireNotNull(BitmapFactory.decodeFile(source.path, options)) { "照片解码失败" }
        var target: Bitmap? = null
        var patch: Bitmap? = null
        try {
            require(base.width == plan.width && base.height == plan.height) { "照片尺寸发生变化" }
            val bitmap = if (plan.rotation == 0 || plan.hdr) base else {
                val width = if (plan.rotation == 180) base.width else base.height
                val height = if (plan.rotation == 180) base.height else base.width
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, false,
                    base.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)).also { upright ->
                    Canvas(upright).drawBitmap(base, Matrix().apply {
                        postRotate(plan.rotation.toFloat())
                        postTranslate(if (plan.rotation == 270) 0f else width.toFloat(),
                            if (plan.rotation == 90) 0f else height.toFloat())
                    }, null)
                }
            }
            target = bitmap
            patch = requireNotNull(BitmapFactory.decodeByteArray(plan.bytes, 0, plan.bytes.size, options)) { "局部图像解码失败" }
            require(!patch.hasAlpha()) { "局部图像存在不确定的透明像素" }
            val placement = Matrix().apply { setValues(requireNotNull(plan.patchMatrix)) }
            if (plan.hdr && plan.rotation != 0) {
                // Patch metadata is in upright coordinates. Keep the HDR base in raw coordinates
                // so its gainmap needs neither resampling nor a guessed orientation transform.
                val upright = Matrix().apply {
                    postRotate(plan.rotation.toFloat())
                    postTranslate(if (plan.rotation == 270) 0f else (if (plan.rotation == 180) base.width else base.height).toFloat(),
                        if (plan.rotation == 90) 0f else (if (plan.rotation == 180) base.height else base.width).toFloat())
                }
                val inverse = Matrix()
                require(upright.invert(inverse)) { "照片方向矩阵无效" }
                placement.postConcat(inverse)
            }
            val gainmap = if (plan.hdr && Build.VERSION.SDK_INT >= 34) {
                require(base.hasGainmap()) { "完整照片 HDR 增益图无法解码" }
                requireNotNull(base.gainmap)
            } else null
            Canvas(bitmap).drawBitmap(patch, placement, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            if (gainmap != null && Build.VERSION.SDK_INT >= 34) bitmap.gainmap = gainmap
            val jpeg = plan.hdr || plan.motion
            output.outputStream().use { require(bitmap.compress(
                if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 100, it)) { "恢复图像编码失败" } }
            fun writeExif(file: File) {
                val resultExif = ExifInterface(file)
                PhotoExportMetadata.apply(plan.exif, resultExif, bitmap.width, bitmap.height)
                resultExif.setAttribute(ExifInterface.TAG_COLOR_SPACE, if (bitmap.colorSpace?.isSrgb == true) "1" else "65535")
                if (plan.hdr) resultExif.setAttribute(ExifInterface.TAG_ORIENTATION,
                    plan.exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL).toString())
                resultExif.saveAttributes()
            }
            if (jpeg) PhotoContainers.rewriteExifPreservingAuxiliary(output, ::writeExif) else writeExif(output)
            if (plan.hdr) require(PhotoHdr.inspect(output).hasGainmap == true) { "恢复编码未保留 HDR 增益图" }
        } finally {
            patch?.recycle()
            if (target !== base) target?.recycle()
            base.recycle()
        }
    }

    private fun readBlock(file: File, range: XiaomiRecoveryRange): ByteArray = RandomAccessFile(file, "r").use {
        it.seek(range.start)
        ByteArray(range.length.toInt()).also(it::readFully)
    }

    private fun bounds(bytes: ByteArray): BitmapFactory.Options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
        // Xiaomi's marketed 200MP mode is actually 201,326,592 pixels.
        // Large pixel patches are region decoded; complete originals are copied as compressed bytes.
        checkDimensions(outWidth, outHeight, 210_000_000L)
    }

    private fun decodeSample(bytes: ByteArray, width: Int, height: Int): Bitmap {
        var sample = 1
        while (width.toLong() * height / sample / sample > 1_000_000L) sample *= 2
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })) { "恢复图像无法解码" }
    }

    private fun checkDimensions(width: Int, height: Int, budget: Long) {
        require(width in 1..32768 && height in 1..32768 && width.toLong() * height <= budget) { "恢复图像尺寸无效或超过像素预算" }
    }

    private fun isCompletePng(bytes: ByteArray): Boolean = bytes.size >= 20 &&
        bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)) &&
        bytes.copyOfRange(bytes.size - 12, bytes.size).contentEquals(byteArrayOf(0, 0, 0, 0, 73, 69, 78, 68, 174.toByte(), 66, 96, 130.toByte()))

    /** Walk markers, including escaped entropy bytes; never find an EOI inside APP payload. */
    private fun jpegEnd(bytes: ByteArray): Int {
        fun u(i: Int) = bytes[i].toInt() and 255
        if (bytes.size < 4 || u(0) != 255 || u(1) != 216) return -1
        var p = 2; var entropy = false
        while (p < bytes.size) {
            if (u(p++) != 255) { if (entropy) continue else return -1 }
            while (p < bytes.size && u(p) == 255) p++
            if (p == bytes.size) return -1
            val marker = u(p++)
            if (entropy && (marker == 0 || marker in 208..215)) continue
            if (marker == 217) return p
            if (marker == 0 || marker == 216 || p + 2 > bytes.size) return -1
            val length = (u(p) shl 8) or u(p + 1)
            if (length < 2 || length > bytes.size - p) return -1
            p += length
            entropy = marker == 218
        }
        return -1
    }
}

