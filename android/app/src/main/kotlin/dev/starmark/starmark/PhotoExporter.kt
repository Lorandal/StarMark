package dev.starmark.starmark

import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel

/** Encodes the already rendered, full-resolution pixels once. Never rewrites the source photo. */
internal object PhotoExporter {
    private fun photoExportName(original: String?, source: File?): String {
        val name = original?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }
            ?: source?.name?.takeIf { it.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "heif", "heic") }
            ?: "photo_${System.currentTimeMillis()}.jpg" // Legacy drafts did not record names.
        val renamed = when {
            name.startsWith("MVIMG") -> "MVSIMG" + name.removePrefix("MVIMG")
            name.startsWith("IMG") -> "SIMG" + name.removePrefix("IMG")
            else -> "S_$name"
        }
        // The encoder emits JPEG even for PNG/HEIF inputs; never mislabel its bytes.
        val extension = renamed.substringAfterLast('.', "")
        return if (extension.lowercase() in setOf("jpg", "jpeg")) renamed
            else renamed.substringBeforeLast('.', renamed) + ".jpg"
    }

    fun save(context: Context, input: File, source: File?, args: Map<*, *>): Map<String, Any?> {
        val resolver = context.contentResolver
        require(source == null || source.canonicalFile != input.canonicalFile) { "原图不能作为导出临时文件" }
        var bitmap: Bitmap? = null
        val framedMotion = File(input.parentFile, "${input.name}.motion.mp4")
        var uri: Uri? = null
        val encoded = File.createTempFile("export-cover-", ".jpg", input.parentFile)
        val packed = File.createTempFile("export-complete-", ".jpg", input.parentFile)
        try {
            val sourceExif = source?.let {
                require(it.isFile) { "原始照片不存在，无法保留拍摄信息" }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(it.path, bounds)
                if (ExifInterface.isSupportedMimeType(bounds.outMimeType.orEmpty())) ExifInterface(it) else null
            }
            val rendered = if (args["pixelFormat"] == "tiled-png") {
                TiledPhotoExporter.render(input, requireNotNull(source), encoded, args)
            } else {
            bitmap = if (args["pixelFormat"] == "rgba8888") {
                val width = (args["width"] as Number).toInt()
                val height = (args["height"] as Number).toInt()
                checkSize(width, height)
                require(input.length() == width.toLong() * height * 4) { "导出像素数据不完整" }
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { target ->
                    try {
                        FileInputStream(input).channel.use { channel ->
                            // Android ARGB_8888's native little-endian bytes match Flutter rawRgba.
                            target.copyPixelsFromBuffer(channel.map(FileChannel.MapMode.READ_ONLY, 0, input.length()))
                        }
                    } catch (error: Throwable) { target.recycle(); throw error }
                }
            } else {
                require(args["pixelFormat"] == null) { "不支持的导出像素格式" }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(input.path, bounds)
                checkSize(bounds.outWidth, bounds.outHeight)
                BitmapFactory.decodeFile(input.path) ?: error("导出图片无法读取")
            }
            val width = bitmap.width
            val height = bitmap.height
            if (args["photoLayerSeparated"] == true) {
                val original = requireNotNull(source) { "缺少用于色彩合成的原始照片" }
                val x = (args["photoX"] as? Number)?.toInt() ?: 0
                val y = (args["photoY"] as? Number)?.toInt() ?: 0
                val w = (args["photoWidth"] as? Number)?.toInt() ?: width
                val h = (args["photoHeight"] as? Number)?.toInt() ?: height
                val background = (args["photoBackgroundPath"] as? String)?.let { path ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    require(bounds.outWidth == width && bounds.outHeight == height) { "照片背景尺寸不匹配" }
                    BitmapFactory.decodeFile(path) ?: error("照片背景无法读取")
                }
                val composited = try {
                    PhotoCompositor.compose(original, bitmap, Rect(x,y,x+w,y+h),
                        (args["sourceOrientation"] as? Number)?.toInt() ?: 1, background,
                        linear = args["linearComposition"] == true)
                } finally { background?.recycle() }
                bitmap.recycle()
                bitmap = composited
            }
            val isSrgb = bitmap.colorSpace?.isSrgb != false
            val maskPath = args["hdrMaskPath"] as? String
            val mask = maskPath?.let {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(it, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 4_194_304) { "HDR 遮罩无效" }
                BitmapFactory.decodeFile(it) ?: error("HDR 遮罩读取失败")
            }
            val hdr = try {
                source?.let {
                    val x = (args["photoX"] as? Number)?.toInt() ?: 0
                    val y = (args["photoY"] as? Number)?.toInt() ?: 0
                    val w = (args["photoWidth"] as? Number)?.toInt() ?: width
                    val h = (args["photoHeight"] as? Number)?.toInt() ?: height
                    PhotoHdr.attachToExport(it, bitmap, Rect(x, y, x + w, y + h),
                        (args["sourceOrientation"] as? Number)?.toInt() ?: 1, mask)
                }
            } finally { mask?.recycle() }
            check(args["hdrExpected"] != true || hdr?.attached == true) { "无法完整保留这张照片的 HDR 数据" }
            encoded.outputStream().buffered().use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG,
                    (args["quality"] as? Number)?.toInt()?.coerceIn(60, 100) ?: 100, it)) { "JPEG 编码失败" }
            }
            bitmap.recycle()
            bitmap = null
            TiledPhotoExporter.Result(width, height, isSrgb, hdr)
            }
            val width = rendered.width; val height = rendered.height
            val isSrgb = rendered.isSrgb; val hdr = rendered.hdr
            val geometry = MotionFrameGeometry(width, height,
                (args["photoX"] as? Number)?.toInt() ?: 0, (args["photoY"] as? Number)?.toInt() ?: 0,
                (args["photoWidth"] as? Number)?.toInt() ?: width, (args["photoHeight"] as? Number)?.toInt() ?: height)
            val writeExif: (File) -> Unit = { target ->
                val outputExif = ExifInterface(target)
                PhotoExportMetadata.apply(sourceExif, outputExif, width, height, isSrgb)
                outputExif.saveAttributes()
            }
            // AndroidX's JPEG metadata writer does not repair MPF indexes.
            // Insert its new EXIF into the intact HDR cover with checked offsets.
            if (hdr?.attached == true) PhotoContainers.rewriteExifPreservingAuxiliary(encoded, writeExif)
            else writeExif(encoded)
            val packaged = source?.let {
                PhotoContainers.packagePhoto(encoded, it, packed,
                    args["documentJson"] as? String ?: "{}", args["preserveMotion"] != false,
                    if (geometry.hasFrame) { original, motion ->
                        MotionFrameRenderer.render(context, original, motion, encoded, geometry, framedMotion)
                    } else null)
            }
            val ready = if (packaged != null) packed else encoded
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, photoExportName(args["sourceName"] as? String, source))
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StarMark")
                put(MediaStore.Images.Media.IS_PENDING, 1)
                sourceExif?.dateTimeOriginal?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
            }) ?: error("无法创建相册文件")
            resolver.openOutputStream(uri, "w")?.use {
                ready.inputStream().buffered().use { input -> input.copyTo(it) }
            } ?: error("无法写入相册")
            val bytes = ready.length()
            check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.WIDTH, width)
                put(MediaStore.Images.Media.HEIGHT, height)
                put(MediaStore.Images.Media.ORIENTATION, 0)
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null) == 1) { "相册保存未完成" }
            return mapOf("uri" to uri.toString(), "width" to width, "height" to height, "bytes" to bytes,
                "hdr" to (hdr?.attached == true), "hdrStatus" to hdr?.status,
                "live" to (packaged?.info?.motion != null), "reversible" to (packaged != null))
        } catch (error: Throwable) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            throw error
        } finally {
            bitmap?.recycle()
            input.delete()
            encoded.delete()
            packed.delete()
            framedMotion.delete()
        }
    }

    private fun checkSize(width: Int, height: Int) {
        require(width > 0 && height > 0 && width.toLong() * height <= 32_000_000) { "导出尺寸无效或超过 3200 万像素预算" }
    }
}

internal object PhotoExportMetadata {
    // Capture metadata remains factual even when the watermark text is edited. Do not
    // copy MakerNote/XMP/thumbnail offsets: they can refer to the old pixels or motion/HDR payload.
    private val captureTags = listOf(
        "Make", "Model", "LensMake", "LensModel", "LensSpecification", "LensSerialNumber", "BodySerialNumber",
        "DateTime", "DateTimeOriginal", "DateTimeDigitized", "OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized",
        "SubSecTime", "SubSecTimeOriginal", "SubSecTimeDigitized", "ExposureTime", "FNumber", "ExposureProgram",
        "PhotographicSensitivity", "SensitivityType", "StandardOutputSensitivity", "RecommendedExposureIndex",
        "ISOSpeed", "ISOSpeedLatitudeyyy", "ISOSpeedLatitudezzz", "ShutterSpeedValue", "ApertureValue",
        "BrightnessValue", "ExposureBiasValue", "MaxApertureValue", "SubjectDistance", "MeteringMode", "LightSource",
        "Flash", "FocalLength", "FocalLengthIn35mmFilm", "FlashEnergy", "ExposureIndex", "SensingMethod",
        "FileSource", "SceneType", "CustomRendered", "ExposureMode", "WhiteBalance", "DigitalZoomRatio",
        "SceneCaptureType", "GainControl", "Contrast", "Saturation", "Sharpness", "SubjectDistanceRange",
        "Artist", "Copyright", "ImageDescription", "UserComment", "ImageUniqueID", "CameraOwnerName",
        "GPSVersionID", "GPSLatitudeRef", "GPSLatitude", "GPSLongitudeRef", "GPSLongitude", "GPSAltitudeRef",
        "GPSAltitude", "GPSTimeStamp", "GPSSatellites", "GPSStatus", "GPSMeasureMode", "GPSDOP", "GPSSpeedRef",
        "GPSSpeed", "GPSTrackRef", "GPSTrack", "GPSImgDirectionRef", "GPSImgDirection", "GPSMapDatum",
        "GPSDestLatitudeRef", "GPSDestLatitude", "GPSDestLongitudeRef", "GPSDestLongitude", "GPSDestBearingRef",
        "GPSDestBearing", "GPSDestDistanceRef", "GPSDestDistance", "GPSProcessingMethod", "GPSAreaInformation",
        "GPSDateStamp", "GPSDifferential", "GPSHPositioningError",
    )

    fun apply(source: ExifInterface?, output: ExifInterface, width: Int, height: Int, isSrgb: Boolean = true) {
        source?.let { original -> captureTags.forEach { tag ->
            original.getAttribute(tag)?.let { output.setAttribute(tag, it) }
        } }
        output.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        output.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
        output.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
        output.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        output.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        // P3 has no EXIF enum; the encoder's embedded ICC is authoritative.
        output.setAttribute(ExifInterface.TAG_COLOR_SPACE, if (isSrgb) "1" else "65535")
        output.setAttribute(ExifInterface.TAG_SOFTWARE, "StarMark ${BuildConfig.VERSION_NAME}")
    }
}
