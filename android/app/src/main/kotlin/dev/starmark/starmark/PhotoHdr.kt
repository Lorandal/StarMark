package dev.starmark.starmark

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.graphics.Rect
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Platform Ultra HDR support. Call on a worker thread. No proprietary codec or native library.
 *
 * Android's Gainmap definition is the source of the decoding formula, including gamma's direction:
 * https://developer.android.com/reference/android/graphics/Gainmap
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/Gainmap.java
 *
 * At full headroom H = (B + epsilonSdr) * exp(mix(log(min), log(max), G^gamma)) - epsilonHdr.
 * We transform that HDR difference into the output's linear ICC space, then
 * Hout = Bout + coverage * (H - B). Native exports retain the original P3/sRGB primaries.
 * New equal epsilons and a range containing gain=1 keep added SDR graphics neutral. Gamma and
 * display transition thresholds are preserved; gain bounds and epsilons are intentionally rebuilt.
 * Intermediate display headrooms interpolate the new endpoints and are therefore approximate.
 */
internal object PhotoHdr {
    data class Inspection(
        val apiSupported: Boolean,
        val hasGainmap: Boolean?,
        val gainmapWidth: Int? = null,
        val gainmapHeight: Int? = null,
        val status: String,
        val mayContainGainmap: Boolean,
        /** Gainmap dimensions describe this sampled decode, not guessed original dimensions. */
        val sampleSize: Int = 0,
    )

    data class Attachment(
        val attached: Boolean,
        val status: String,
        val gainmapWidth: Int? = null,
        val gainmapHeight: Int? = null,
        /** Number of channel samples beyond the representable positive 1/256..256 ratio. */
        val clampedSamples: Int = 0,
    )

    private const val EPSILON = 1f / 65536f
    private val SRGB = ColorSpace.get(ColorSpace.Named.SRGB) as ColorSpace.Rgb
    private val SRGB_LINEAR = DoubleArray(256) { SRGB.eotf.applyAsDouble(it / 255.0) }

    @SuppressLint("NewApi")
    fun inspect(source: File): Inspection {
        val supported = Build.VERSION.SDK_INT >= 34
        val sdrStatus = try { quickSdrStatus(source) } catch (_: Exception) {
            return Inspection(supported, null, status = "read_failed", mayContainGainmap = true)
        }
        if (sdrStatus != null) return Inspection(supported, false, status = sdrStatus, mayContainGainmap = false)
        if (!supported) return Inspection(false, null, status = "api_unsupported", mayContainGainmap = true)
        var decoded: Bitmap? = null
        return try {
            val options = decodeOptions(source, 256)
                ?: return Inspection(true, null, status = "decode_failed", mayContainGainmap = true)
            decoded = BitmapFactory.decodeFile(source.path, options)
                ?: return Inspection(true, null, status = "decode_failed", mayContainGainmap = true)
            val actual = decoded.hasGainmap()
            if (!actual && !isJpeg(source)) {
                // PQ/HLG PNG and HDR HEIF can carry HDR without a Gainmap. A negative gainmap
                // query cannot certify their SDR rendition; preserve the export refusal signal.
                return Inspection(true, null, status = "no_gainmap_hdr_unconfirmed",
                    mayContainGainmap = true, sampleSize = options.inSampleSize)
            }
            val map = if (actual) decoded.gainmap?.gainmapContents else null
            Inspection(true, actual, map?.width, map?.height,
                if (actual) "gainmap" else "no_decodable_gainmap", true, options.inSampleSize)
        } catch (_: Exception) {
            Inspection(true, null, status = "decode_failed", mayContainGainmap = true)
        } catch (_: OutOfMemoryError) {
            Inspection(true, null, status = "memory_limit", mayContainGainmap = true)
        } finally { decoded?.recycle() }
    }

    /**
     * Attaches a newly calculated gainmap to the caller-owned opaque SDR RGB output bitmap.
     * [photoRect] is the entire oriented source image's placement (no source crop). EXIF
     * [sourceOrientation] is 1..8, or EXIF's undefined value 0 (normal pixel order).
     * BitmapFactory does not apply EXIF orientation.
     * [contributionMask] spans the FULL output; white=photo, black=SDR graphics, gray=coverage.
     * Mask RGB values are numeric coverage (NOT gamma-decoded); alpha multiplies coverage.
     * A null mask means the complete photo rectangle is unoccluded. Callers adding a watermark
     * inside the photo must supply its mask. Inputs are never recycled or otherwise altered.
     *
     * This preserves HDR illumination under SDR edits; it does not reconstruct a separately
     * authored HDR grade. Reduced masks/gainmaps approximate subpixel and intermediate-headroom
     * behavior. Borders are conservatively inset by one gainmap texel to avoid bright halos.
     */
    @SuppressLint("NewApi")
    fun attachToExport(
        source: File,
        output: Bitmap,
        photoRect: Rect,
        sourceOrientation: Int = 1,
        contributionMask: Bitmap? = null,
        maxGainmapEdge: Int = 1024,
    ): Attachment {
        if (Build.VERSION.SDK_INT < 34) return Attachment(false, "api_unsupported")
        // Xiaomi camera can write ORIENTATION_UNDEFINED (0). Decode/preview already treat
        // it as unchanged pixels; retain the same interpretation for existing drafts too.
        val orientation = if (sourceOrientation == 0) 1 else sourceOrientation
        if (output.isRecycled || output.config == Bitmap.Config.HARDWARE ||
            output.colorSpace !is ColorSpace.Rgb || output.hasGainmap()) {
            return Attachment(false, "output_must_be_sdr_rgb")
        }
        if (photoRect.isEmpty || photoRect.left < 0 || photoRect.top < 0 ||
            photoRect.right > output.width || photoRect.bottom > output.height ||
            orientation !in 1..8 || maxGainmapEdge !in 16..2048 ||
            contributionMask?.isRecycled == true || contributionMask?.config == Bitmap.Config.HARDWARE) {
            return Attachment(false, "invalid_geometry_or_mask")
        }
        var decoded: Bitmap? = null
        var createdMap: Bitmap? = null
        var transferred = false
        return try {
            quickSdrStatus(source)?.let { return Attachment(false, it) }
            val options = decodeOptions(source, 4096) ?: return Attachment(false, "decode_failed")
            decoded = BitmapFactory.decodeFile(source.path, options) ?: return Attachment(false, "decode_failed")
            if (!decoded.hasGainmap()) return Attachment(false, "no_decodable_gainmap")
            val original = decoded.gainmap ?: return Attachment(false, "no_decodable_gainmap")
            if (Build.VERSION.SDK_INT >= 36 && original.gainmapDirection != Gainmap.GAINMAP_DIRECTION_SDR_TO_HDR) {
                return Attachment(false, "hdr_base_unsupported")
            }
            val sourceSpace = decoded.colorSpace as? ColorSpace.Rgb
                ?: return Attachment(false, "source_color_space_unsupported")
            val mathSpace = if (Build.VERSION.SDK_INT >= 36) {
                (original.alternativeImagePrimaries ?: sourceSpace) as? ColorSpace.Rgb
                    ?: return Attachment(false, "gainmap_color_space_unsupported")
            } else sourceSpace
            val parameters = Parameters(original)
            if (!parameters.valid()) return Attachment(false, "invalid_gainmap_parameters")
            val sourceToMath = linearTransform(sourceSpace, mathSpace)
            val outputSpace = output.colorSpace as ColorSpace.Rgb
            val mathToOutput = linearTransform(mathSpace, outputSpace)
            val sourceSampler = LinearSampler(decoded, sourceSpace)
            val gainSampler = PixelSampler(original.gainmapContents, numeric = true)
            val outputSampler = LinearSampler(output, outputSpace)
            val scale = min(1.0, maxGainmapEdge.toDouble() / max(output.width, output.height))
            val width = max(1, ceil(output.width * scale).toInt())
            val height = max(1, ceil(output.height * scale).toInt())
            val maskCoverage = contributionMask?.let { coverageMinima(it, width, height) }
            val ratios = FloatArray(width * height * 3)
            val lower = DoubleArray(3) { 0.0 }
            val upper = DoubleArray(3) { 0.0 }
            val base = DoubleArray(3)
            val mathBase = DoubleArray(3)
            val delta = DoubleArray(3)
            val outputDelta = DoubleArray(3)
            val gain = DoubleArray(3)
            val exported = DoubleArray(3)
            val raw = DoubleArray(2)
            val stepX = output.width.toDouble() / width
            val stepY = output.height.toDouble() / height
            var clamped = 0
            for (y in 0 until height) for (x in 0 until width) {
                val ox = (x + .5) * stepX
                val oy = (y + .5) * stepY
                val insetX = if (stepX > 1.001) stepX else 0.0
                val insetY = if (stepY > 1.001) stepY else 0.0
                var coverage = if (ox >= photoRect.left + insetX && ox < photoRect.right - insetX &&
                    oy >= photoRect.top + insetY && oy < photoRect.bottom - insetY) 1.0 else 0.0
                if (coverage > 0.0 && maskCoverage != null) coverage = maskCoverage[y * width + x].toDouble()
                val offset = (y * width + x) * 3
                if (coverage <= 0.0) continue // log(gain=1) is already zero.
                val u = (ox - photoRect.left) / photoRect.width()
                val v = (oy - photoRect.top) / photoRect.height()
                toRaw(u, v, orientation, raw)
                sourceSampler.sample(raw[0], raw[1], base)
                multiply(sourceToMath, base, mathBase)
                gainSampler.sample(raw[0], raw[1], gain)
                for (c in 0..2) {
                    val logGain = parameters.logMin[c] +
                        (parameters.logMax[c] - parameters.logMin[c]) * gain[c].pow(parameters.gamma[c].toDouble())
                    val hdr = (mathBase[c] + parameters.epsilonSdr[c]) * exp(logGain) - parameters.epsilonHdr[c]
                    delta[c] = hdr - mathBase[c]
                }
                multiply(mathToOutput, delta, outputDelta)
                outputSampler.sample(ox / output.width, oy / output.height, exported)
                for (c in 0..2) {
                    val target = exported[c] + coverage * outputDelta[c]
                    val ratio = (target + EPSILON) / (exported[c] + EPSILON)
                    if (!ratio.isFinite()) return Attachment(false, "invalid_gainmap_result")
                    val bounded = ratio.coerceIn(1.0 / 256.0, 256.0)
                    if (bounded != ratio) clamped++
                    val value = ln(bounded)
                    ratios[offset + c] = value.toFloat()
                    lower[c] = min(lower[c], value)
                    upper[c] = max(upper[c], value)
                }
            }
            // Move the range endpoints so that neutral gain is represented EXACTLY by an 8-bit
            // sample, including source maps which contain attenuation as well as amplification.
            for (c in 0..2) alignNeutral(lower, upper, c, parameters.gamma[c].toDouble())
            val pixels = IntArray(width * height)
            for (i in pixels.indices) {
                var pixel = -0x1000000
                for (c in 0..2) {
                    val fraction = ((ratios[i * 3 + c] - lower[c]) / (upper[c] - lower[c])).coerceIn(0.0, 1.0)
                    val channel = (255.0 * fraction.pow(1.0 / parameters.gamma[c])).roundToInt().coerceIn(0, 255)
                    pixel = pixel or (channel shl (16 - 8 * c))
                }
                pixels[i] = pixel
            }
            createdMap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            val remapped = Gainmap(createdMap).apply {
                setRatioMin(exp(lower[0]).toFloat(), exp(lower[1]).toFloat(), exp(lower[2]).toFloat())
                setRatioMax(exp(upper[0]).toFloat(), exp(upper[1]).toFloat(), exp(upper[2]).toFloat())
                setGamma(parameters.gamma[0], parameters.gamma[1], parameters.gamma[2])
                setEpsilonSdr(EPSILON, EPSILON, EPSILON)
                setEpsilonHdr(EPSILON, EPSILON, EPSILON)
                minDisplayRatioForHdrTransition = original.minDisplayRatioForHdrTransition
                displayRatioForFullHdr = original.displayRatioForFullHdr
            }
            output.gainmap = remapped
            transferred = true // The output bitmap now owns the new gainmap; do not recycle it.
            Attachment(true, if (clamped == 0) "attached" else "attached_with_clipping", width, height, clamped)
        } catch (_: Exception) {
            Attachment(false, "hdr_processing_failed")
        } catch (_: OutOfMemoryError) {
            Attachment(false, "memory_limit")
        } finally {
            decoded?.recycle()
            if (!transferred) createdMap?.recycle()
        }
    }

    /** Gainmap pixels are numeric data, including ALPHA_8; Canvas/color conversion loses them. */
    @SuppressLint("NewApi")
    fun cropGainmap(original: Gainmap, sourceWidth: Int, sourceHeight: Int, crop: Rect): Gainmap {
        require(Build.VERSION.SDK_INT >= 34 && sourceWidth > 0 && sourceHeight > 0)
        require(!crop.isEmpty && crop.left >= 0 && crop.top >= 0 && crop.right <= sourceWidth && crop.bottom <= sourceHeight)
        val source = original.gainmapContents
        val width = max(1, (crop.width().toDouble() * source.width / sourceWidth).roundToInt())
        val height = max(1, (crop.height().toDouble() * source.height / sourceHeight).roundToInt())
        require(width.toLong() * height <= 16_777_216) { "HDR 裁切尺寸超出安全范围" }
        val pixels = IntArray(width)
        val map = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val sampler = PixelSampler(source, numeric = true)
        val values = DoubleArray(3)
        try { for (y in 0 until height) {
          for (x in 0 until width) {
            sampler.sample((crop.left + (x + .5) * crop.width() / width) / sourceWidth,
                (crop.top + (y + .5) * crop.height() / height) / sourceHeight, values)
            pixels[x] = Color.rgb((values[0] * 255).roundToInt().coerceIn(0, 255),
                (values[1] * 255).roundToInt().coerceIn(0, 255), (values[2] * 255).roundToInt().coerceIn(0, 255))
          }
          map.setPixels(pixels, 0, width, 0, y, width, 1)
        } } catch (error: Throwable) { map.recycle(); throw error }
        try {
            // The platform copy constructor also retains API 36 primaries and gain direction.
            return if (Build.VERSION.SDK_INT >= 35) Gainmap(original, map) else Gainmap(map).apply {
                original.ratioMin.let { setRatioMin(it[0], it[1], it[2]) }
                original.ratioMax.let { setRatioMax(it[0], it[1], it[2]) }
                original.gamma.let { setGamma(it[0], it[1], it[2]) }
                original.epsilonSdr.let { setEpsilonSdr(it[0], it[1], it[2]) }
                original.epsilonHdr.let { setEpsilonHdr(it[0], it[1], it[2]) }
                minDisplayRatioForHdrTransition = original.minDisplayRatioForHdrTransition
                displayRatioForFullHdr = original.displayRatioForFullHdr
            }
        } catch (error: Throwable) { map.recycle(); throw error }
    }

    private fun isJpeg(source: File): Boolean = source.inputStream().use { it.read() == 0xff && it.read() == 0xd8 }

    /** Null means uncertain. No Bitmap/Gainmap API is required for these header-only SDR paths. */
    private fun quickSdrStatus(source: File): String? = RandomAccessFile(source, "r").use { file ->
        if (file.length() < 4) return@use null
        if (file.readUnsignedShort() != 0xffd8) {
            file.seek(0)
            return@use if (isOrdinarySdrPng(file)) "sdr_png" else null
        }
        // Probe JPEG markers before SOS; entropy-coded pixels and trailers are not scanned.
        while (file.filePointer < min(file.length(), 4L * 1024 * 1024)) {
            if (file.readUnsignedByte() != 0xff) return@use null
            var marker = file.readUnsignedByte()
            while (marker == 0xff) marker = file.readUnsignedByte()
            if (marker == 0xda || marker == 0xd9) return@use "sdr_jpeg"
            if (marker == 0x01 || marker in 0xd0..0xd7) continue
            val length = file.readUnsignedShort() - 2
            if (length < 0 || file.filePointer + length > file.length()) return@use null
            if (marker == 0xe1 || marker == 0xe2) {
                val bytes = ByteArray(length)
                file.readFully(bytes)
                val content = String(bytes, Charsets.ISO_8859_1)
                if (content.startsWith("MPF\u0000") || content.contains("hdr-gain-map", true) ||
                    content.contains("hdrgm:", true) || content.contains("21496") ||
                    content.contains("HDRGainMap", true)) return@use null
            } else file.seek(file.filePointer + length)
        }
        null // An unreasonably large/invalid header is unknown, never asserted to be SDR.
    }

    /**
     * A narrow PNG exclusion, not an HDR decoder. Only ordinary 8-bit PNG with implicit/explicit
     * sRGB colour and known harmless chunks qualifies. Pixel data is seeked over, not decoded.
     * PNG Third Edition gives cICP precedence over sRGB and defines mDCV/cLLI HDR metadata:
     * https://www.w3.org/TR/png-3/#11cICP . Unknown ICC, high bit depth, HDR/unknown chunks, APNG,
     * compressed text, or trailing payload remain uncertain, even when a platform ignores them.
     */
    private fun isOrdinarySdrPng(file: RandomAccessFile): Boolean {
        val signature = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)
        if (file.length() < 33) return false
        val actual = ByteArray(8).also { file.readFully(it) }
        if (!actual.contentEquals(signature)) return false
        var chunks = 0
        var metadataBytes = 0L
        var hasHeader = false
        var hasPixels = false
        var hasExif = false
        while (file.filePointer <= file.length() - 12 && ++chunks <= 4096) {
            val size = file.readInt().toLong() and 0xffffffffL
            val typeBytes = ByteArray(4).also { file.readFully(it) }
            val type = String(typeBytes, Charsets.US_ASCII)
            if (size > file.length() - file.filePointer - 4 || (!hasHeader && type != "IHDR")) return false
            if (type == "IDAT") {
                if (!hasHeader || size == 0L) return false
                hasPixels = true
                file.seek(file.filePointer + size + 4) // Image validation remains the import decoder's job.
                continue
            }
            metadataBytes += size
            if (metadataBytes > 1024 * 1024 || size > 65536) return false
            val data = ByteArray(size.toInt()).also { file.readFully(it) }
            val checksum = file.readInt().toLong() and 0xffffffffL
            if (CRC32().apply { update(typeBytes); update(data) }.value != checksum) return false
            when (type) {
                "IHDR" -> {
                    if (hasHeader || data.size != 13 || data[8].toInt() != 8 ||
                        data[9].toInt() !in setOf(0, 2, 3, 4, 6) || data[10].toInt() != 0 ||
                        data[11].toInt() != 0 || data[12].toInt() !in 0..1) return false
                    fun dimension(at: Int): Long = (0..3).fold(0L) { v, i -> (v shl 8) or (data[at + i].toLong() and 255) }
                    if (dimension(0) !in 1..Int.MAX_VALUE.toLong() || dimension(4) !in 1..Int.MAX_VALUE.toLong()) return false
                    hasHeader = true
                }
                "sRGB" -> if (hasPixels || data.size != 1 || data[0].toInt() !in 0..3) return false
                "cICP" -> if (hasPixels || !data.contentEquals(byteArrayOf(1, 13, 0, 1))) return false
                "gAMA" -> if (hasPixels || !data.contentEquals(byteArrayOf(0, 0, 0xb1.toByte(), 0x8f.toByte()))) return false
                "cHRM" -> {
                    val expected = intArrayOf(31270, 32900, 64000, 33000, 30000, 60000, 15000, 6000)
                    if (hasPixels || data.size != 32) return false
                    for (i in expected.indices) {
                        var value = 0L
                        for (j in 0..3) value = (value shl 8) or (data[i * 4 + j].toLong() and 255)
                        if (value != expected[i].toLong()) return false
                    }
                }
                "PLTE", "tRNS", "sBIT", "bKGD", "pHYs", "tIME" -> Unit
                "eXIf" -> {
                    if (hasExif || !isSrgbCameraExif(data)) return false
                    hasExif = true
                }
                "tEXt" -> {
                    val text = String(data, Charsets.ISO_8859_1)
                    if (listOf("hdr", "gainmap", "gain-map", "xmp", "cicp", "21496").any { text.contains(it, true) }) return false
                }
                "IEND" -> return data.isEmpty() && hasPixels && file.filePointer == file.length()
                else -> return false
            }
        }
        return false
    }

    /** Bounded TIFF allowlist for ordinary camera metadata, never opaque MakerNote/XMP/ICC. */
    private fun isSrgbCameraExif(bytes: ByteArray): Boolean {
        if (bytes.size !in 8..65536) return false
        val little = when (String(bytes, 0, 2, Charsets.US_ASCII)) {
            "II" -> true
            "MM" -> false
            else -> return false
        }
        fun uint(at: Int, width: Int): Long {
            if (at < 0 || at > bytes.size - width) throw IllegalArgumentException("EXIF value outside TIFF")
            var value = 0L
            for (i in 0 until width) {
                val index = if (little) at + width - 1 - i else at + i
                value = (value shl 8) or (bytes[index].toLong() and 255)
            }
            return value
        }
        if (uint(2, 2) != 42L) return false
        val rootTags = setOf(0x100, 0x101, 0x10e, 0x10f, 0x110, 0x112, 0x11a, 0x11b,
            0x128, 0x131, 0x132, 0x13b, 0x213, 0x8298, 0x8769, 0x8825)
        val cameraTags = setOf(0x829a, 0x829d, 0x8822, 0x8827, 0x8830, 0x8831, 0x8832,
            0x8833, 0x8834, 0x8835, 0x9000, 0x9003, 0x9004, 0x9010, 0x9011, 0x9012,
            0x9101, 0x9102, 0x9201, 0x9202, 0x9203, 0x9204, 0x9205, 0x9206, 0x9207,
            0x9208, 0x9209, 0x920a, 0x9286, 0x9290, 0x9291, 0x9292, 0xa000, 0xa001,
            0xa002, 0xa003, 0xa005, 0xa20b, 0xa215, 0xa217, 0xa300, 0xa301, 0xa401,
            0xa402, 0xa403, 0xa404, 0xa405, 0xa406, 0xa407, 0xa408, 0xa409, 0xa40a,
            0xa40c, 0xa420, 0xa430, 0xa431, 0xa432, 0xa433, 0xa434, 0xa435)
        val queue = java.util.ArrayDeque<Pair<Long, Int>>()
        queue.add(uint(4, 4) to 0)
        val visited = HashSet<Long>()
        var explicitSrgb = false
        var totalTags = 0
        while (queue.isNotEmpty()) {
            val (offset, kind) = queue.removeFirst()
            if (offset < 8 || offset > bytes.size - 6 || !visited.add(offset) || visited.size > 4) return false
            val count = uint(offset.toInt(), 2).toInt()
            totalTags += count
            if (totalTags > 256 || offset + 2L + count * 12L + 4 > bytes.size) return false
            val tags = HashSet<Int>()
            for (i in 0 until count) {
                val at = offset.toInt() + 2 + i * 12
                val tag = uint(at, 2).toInt()
                if (!tags.add(tag) || when (kind) {
                    0 -> tag !in rootTags
                    1 -> tag !in cameraTags
                    2 -> tag !in 0..31 // Standard GPS metadata only.
                    else -> tag !in setOf(1, 2) // sRGB interoperability index/version.
                }) return false
                val type = uint(at + 2, 2).toInt()
                val unit = when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11 -> 4; 5, 10, 12 -> 8; else -> return false }
                val elements = uint(at + 4, 4)
                val length = elements * unit
                val value = if (length <= 4) (at + 8).toLong() else uint(at + 8, 4)
                if (length < 1 || length > bytes.size || value < 0 || value > bytes.size - length) return false
                if (tag == 0x8769 || tag == 0x8825 || tag == 0xa005) {
                    if (type != 4 || elements != 1L) return false
                    queue.add(uint(value.toInt(), 4) to when (tag) { 0x8769 -> 1; 0x8825 -> 2; else -> 3 })
                } else if (kind == 1 && tag == 0xa001) {
                    if (type != 3 || elements != 1L || uint(value.toInt(), 2) != 1L) return false
                    explicitSrgb = true
                } else if (kind == 3 && tag == 1 &&
                    (type != 2 || elements != 4L || !bytes.copyOfRange(value.toInt(), value.toInt() + 4).contentEquals(byteArrayOf(82, 57, 56, 0)))) return false
            }
            // Thumbnails and chained/vendor IFDs are outside the controlled camera metadata path.
            if (uint(offset.toInt() + 2 + count * 12, 4) != 0L) return false
        }
        return explicitSrgb
    }

    private fun decodeOptions(source: File, edge: Int): BitmapFactory.Options? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > edge && sample < (1 shl 20)) sample *= 2
        return BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // Preserve native primaries; forcing sRGB here would invalidate per-channel gains.
        }
    }

    @SuppressLint("NewApi")
    private class Parameters(gainmap: Gainmap) {
        val min = gainmap.ratioMin
        val max = gainmap.ratioMax
        val gamma = gainmap.gamma
        val epsilonSdr = gainmap.epsilonSdr
        val epsilonHdr = gainmap.epsilonHdr
        val logMin = DoubleArray(3) { ln(min[it].toDouble()) }
        val logMax = DoubleArray(3) { ln(max[it].toDouble()) }
        val transition = gainmap.minDisplayRatioForHdrTransition
        val full = gainmap.displayRatioForFullHdr
        fun valid() = (0..2).all { c ->
            min[c].isFinite() && min[c] > 0 && max[c].isFinite() && max[c] >= min[c] &&
                gamma[c].isFinite() && gamma[c] > 0 && epsilonSdr[c].isFinite() && epsilonHdr[c].isFinite()
        } && transition.isFinite() && transition >= 1 && full.isFinite() && full > transition
    }

    private fun alignNeutral(low: DoubleArray, high: DoubleArray, c: Int, gamma: Double) {
        if (low[c] >= 0) { low[c] = 0.0; high[c] = max(high[c], .001); return }
        if (high[c] <= 0) { high[c] = 0.0; return }
        val neutral = (255 * (-low[c] / (high[c] - low[c])).pow(1 / gamma)).roundToInt().coerceIn(1, 254)
        val t = (neutral / 255.0).pow(gamma)
        val span = max(-low[c] / t, high[c] / (1 - t))
        low[c] = -span * t
        high[c] = span * (1 - t)
    }

    internal fun toRaw(u: Double, v: Double, orientation: Int, result: DoubleArray) {
        result[0] = when (orientation) { 2, 3 -> 1 - u; 5, 6 -> v; 7, 8 -> 1 - v; else -> u }
        result[1] = when (orientation) { 3, 4 -> 1 - v; 5, 8 -> u; 6, 7 -> 1 - u; else -> v }
    }

    private fun linearTransform(from: ColorSpace.Rgb, to: ColorSpace.Rgb): DoubleArray {
        val a = (ColorSpace.adapt(from, ColorSpace.ILLUMINANT_D50) as ColorSpace.Rgb).transform
        val b = (ColorSpace.adapt(to, ColorSpace.ILLUMINANT_D50) as ColorSpace.Rgb).inverseTransform
        return DoubleArray(9) { i ->
            val row = i % 3
            val col = i / 3
            (0..2).sumOf { k -> b[k * 3 + row].toDouble() * a[col * 3 + k] }
        }
    }

    private fun multiply(matrix: DoubleArray, value: DoubleArray, into: DoubleArray) {
        for (r in 0..2) into[r] = matrix[r] * value[0] + matrix[3 + r] * value[1] + matrix[6 + r] * value[2]
    }

    /**
     * Exact rectangular minima over EVERY input mask pixel in a gain texel's bilinear support.
     * A gain texel contributes from its centre minus one gain texel to centre plus one texel.
     * The half mask-pixel extension also includes the mask's bilinear reconstruction neighbours.
     * Separable monotonic queues make this O(mask.width*mask.height + map.width*mask.height),
     * instead of either sparse point sampling or scanning overlapping large rectangles repeatedly.
     */
    private fun coverageMinima(mask: Bitmap, mapWidth: Int, mapHeight: Int): FloatArray {
        val maskWidth = mask.width
        val maskHeight = mask.height
        val horizontal = FloatArray(Math.multiplyExact(mapWidth, maskHeight))
        val result = FloatArray(mapWidth * mapHeight)
        val input = CachedPixels(mask, preserveNumeric = true)
        val row = FloatArray(maskWidth)
        val deque = IntArray(max(maskWidth, maskHeight))
        fun first(index: Int, inputSize: Int, mapSize: Int) =
            floor((index - .5) * inputSize / mapSize - .5).toInt().coerceIn(0, inputSize - 1)
        fun last(index: Int, inputSize: Int, mapSize: Int) =
            ceil((index + 1.5) * inputSize / mapSize - .5).toInt().coerceIn(0, inputSize - 1)
        val xFirst = IntArray(mapWidth) { first(it, maskWidth, mapWidth) }
        val xLast = IntArray(mapWidth) { last(it, maskWidth, mapWidth) }
        val yFirst = IntArray(mapHeight) { first(it, maskHeight, mapHeight) }
        val yLast = IntArray(mapHeight) { last(it, maskHeight, mapHeight) }
        for (y in 0 until maskHeight) {
            for (x in 0 until maskWidth) {
                val pixel = input.get(x, y)
                row[x] = if (mask.config == Bitmap.Config.ALPHA_8) Color.alpha(pixel) / 255f
                    else Color.red(pixel) * Color.alpha(pixel) / 65025f
            }
            var head = 0
            var tail = 0
            var added = -1
            for (x in 0 until mapWidth) {
                while (added < xLast[x]) {
                    added++
                    while (tail > head && row[deque[tail - 1]] >= row[added]) tail--
                    deque[tail++] = added
                }
                while (head < tail && deque[head] < xFirst[x]) head++
                horizontal[y * mapWidth + x] = row[deque[head]]
            }
        }
        for (x in 0 until mapWidth) {
            var head = 0
            var tail = 0
            var added = -1
            for (y in 0 until mapHeight) {
                while (added < yLast[y]) {
                    added++
                    val next = horizontal[added * mapWidth + x]
                    while (tail > head && horizontal[deque[tail - 1] * mapWidth + x] >= next) tail--
                    deque[tail++] = added
                }
                while (head < tail && deque[head] < yFirst[y]) head++
                result[y * mapWidth + x] = horizontal[deque[head] * mapWidth + x]
            }
        }
        return result
    }

    /** Bounded 2 MiB tile cache: no per-sample allocations or per-pixel JNI on the common path. */
    private class CachedPixels(private val bitmap: Bitmap, private val preserveNumeric: Boolean = false) {
        private val tags = IntArray(128) { -1 }
        private val tiles = Array(128) { IntArray(64 * 64) }
        private val columns = (bitmap.width + 63) / 64
        private val nativeNumeric = preserveNumeric && bitmap.colorSpace?.isSrgb == false
        fun get(x: Int, y: Int): Int {
            val tileX = x / 64
            val tileY = y / 64
            val key = tileY * columns + tileX
            // Unlike key % 128, this does not thrash when an EXIF rotation walks image columns.
            val slot = (tileX * 31 + tileY * 67) and 127
            val pixels = tiles[slot]
            if (tags[slot] != key) {
                val left = tileX * 64
                val top = tileY * 64
                val width = min(64, bitmap.width - left)
                val height = min(64, bitmap.height - top)
                if (nativeNumeric) {
                    // Gainmaps ignore their tagged color space. Preserve those numeric values.
                    for (dy in 0 until height) for (dx in 0 until width) {
                        val color = bitmap.getColor(left + dx, top + dy)
                        pixels[dy * 64 + dx] = Color.argb((color.alpha() * 255).roundToInt().coerceIn(0, 255),
                            (color.red() * 255).roundToInt().coerceIn(0, 255),
                            (color.green() * 255).roundToInt().coerceIn(0, 255),
                            (color.blue() * 255).roundToInt().coerceIn(0, 255))
                    }
                } else bitmap.getPixels(pixels, 0, 64, left, top, width, height)
                tags[slot] = key
            }
            return pixels[(y and 63) * 64 + (x and 63)]
        }
    }

    private abstract class Sampler(protected val bitmap: Bitmap) {
        protected val scratch = DoubleArray(3)
        protected abstract fun read(x: Int, y: Int, result: DoubleArray)
        fun sample(u: Double, v: Double, result: DoubleArray) {
            val x = (u * bitmap.width - .5).coerceIn(0.0, (bitmap.width - 1).toDouble())
            val y = (v * bitmap.height - .5).coerceIn(0.0, (bitmap.height - 1).toDouble())
            val ix = floor(x).toInt()
            val iy = floor(y).toInt()
            result.fill(0.0)
            for (dy in 0..1) for (dx in 0..1) {
                val weight = (if (dx == 0) 1 - (x - ix) else x - ix) * (if (dy == 0) 1 - (y - iy) else y - iy)
                if (weight == 0.0) continue
                read(min(ix + dx, bitmap.width - 1), min(iy + dy, bitmap.height - 1), scratch)
                for (c in 0..2) result[c] += weight * scratch[c]
            }
        }
    }

    private class LinearSampler(bitmap: Bitmap, private val space: ColorSpace.Rgb) : Sampler(bitmap) {
        private val cached = if (space.isSrgb) CachedPixels(bitmap) else null
        override fun read(x: Int, y: Int, result: DoubleArray) {
            if (space.isSrgb) {
                val pixel = cached!!.get(x, y)
                result[0] = SRGB_LINEAR[Color.red(pixel)]
                result[1] = SRGB_LINEAR[Color.green(pixel)]
                result[2] = SRGB_LINEAR[Color.blue(pixel)]
            } else {
                // getPixel() converts to/clips sRGB; getColor() retains the original primaries.
                val color = bitmap.getColor(x, y)
                result[0] = space.eotf.applyAsDouble(color.red().toDouble())
                result[1] = space.eotf.applyAsDouble(color.green().toDouble())
                result[2] = space.eotf.applyAsDouble(color.blue().toDouble())
            }
        }
    }

    private class PixelSampler(bitmap: Bitmap, private val numeric: Boolean) : Sampler(bitmap) {
        private val cached = CachedPixels(bitmap, preserveNumeric = numeric)
        override fun read(x: Int, y: Int, result: DoubleArray) {
            val pixel = cached.get(x, y)
            if (bitmap.config == Bitmap.Config.ALPHA_8) {
                val value = Color.alpha(pixel) / 255.0
                result[0] = value; result[1] = value; result[2] = value
            } else if (numeric) {
                result[0] = Color.red(pixel) / 255.0
                result[1] = Color.green(pixel) / 255.0
                result[2] = Color.blue(pixel) / 255.0
            } else {
                result[0] = SRGB_LINEAR[Color.red(pixel)]
                result[1] = SRGB_LINEAR[Color.green(pixel)]
                result[2] = SRGB_LINEAR[Color.blue(pixel)]
            }
        }
    }
}
