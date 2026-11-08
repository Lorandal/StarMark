package dev.starmark.starmark

import android.graphics.*
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Large photos never enter a whole-image RGBA buffer or a GPU texture. */
internal object TiledPhotoExporter {
    const val MAX_PIXELS = 520_000_000L
    const val MAX_EDGE = 32768
    const val SOURCE_PIXELS = 210_000_000L
    const val STRIP_HEIGHT = 128
    const val TILE_WIDTH = 2048
    data class Result(val width: Int, val height: Int, val isSrgb: Boolean, val hdr: PhotoHdr.Attachment?)
    private data class Tile(val rect: Rect, val file: File)

    fun validSize(width: Int, height: Int) = width in 1..MAX_EDGE && height in 1..MAX_EDGE &&
        width.toLong() * height <= MAX_PIXELS

    fun render(manifest: File, source: File, destination: File, args: Map<*, *>): Result {
        require(manifest.length() in 2..4_194_304) { "大图分块清单无效" }
        val json = JSONObject(manifest.readText())
        val width = json.getInt("width"); val height = json.getInt("height")
        require(validSize(width, height)) { "大图画布超过支持范围" }
        require((args["width"] as Number).toInt() == width && (args["height"] as Number).toInt() == height)
        require(json.getInt("tileWidth") == TILE_WIDTH && json.getInt("tileHeight") == STRIP_HEIGHT)
        val root = requireNotNull(manifest.canonicalFile.parentFile)
        fun privateFile(path: String): File = File(path).canonicalFile.also {
            require(it.isFile && it.toPath().startsWith(root.toPath()) && it != source.canonicalFile && it != destination.canonicalFile)
        }
        fun tiles(array: JSONArray): List<Tile> {
            require(array.length() == ((width + TILE_WIDTH - 1) / TILE_WIDTH) * ((height + STRIP_HEIGHT - 1) / STRIP_HEIGHT)) {
                "大图分块数量不完整"
            }
            val occupied = HashSet<Pair<Int, Int>>()
            return (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                val x = row.getInt("x"); val y = row.getInt("y")
                val w = row.getInt("width"); val h = row.getInt("height")
                require(x in 0 until width && y in 0 until height && x % TILE_WIDTH == 0 && y % STRIP_HEIGHT == 0)
                require(w == minOf(TILE_WIDTH, width - x) && h == minOf(STRIP_HEIGHT, height - y) && occupied.add(x to y))
                Tile(Rect(x,y,x+w,y+h), privateFile(row.getString("path")))
            }
        }
        val decoration = tiles(json.getJSONArray("tiles")).groupBy { it.rect.top }
        val background = json.optJSONArray("backgroundTiles")?.takeIf { it.length() > 0 }?.let(::tiles)?.groupBy { it.rect.top }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth in 1..MAX_EDGE && bounds.outHeight in 1..MAX_EDGE &&
            bounds.outWidth.toLong() * bounds.outHeight <= SOURCE_PIXELS) { "原图超过 2.1 亿像素范围" }
        val space = bounds.outColorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
        val linear = args["linearComposition"] == true
        require(space is ColorSpace.Rgb) { "原片色彩空间不受支持" }
        val rect = Rect((args["photoX"] as Number).toInt(), (args["photoY"] as Number).toInt(), 0, 0).apply {
            right = left + (args["photoWidth"] as Number).toInt(); bottom = top + (args["photoHeight"] as Number).toInt()
        }
        require(!rect.isEmpty && Rect(0,0,width,height).contains(rect))
        val orientation = (args["sourceOrientation"] as? Number)?.toInt() ?: 1
        require(orientation in 0..8)
        val transform = sourceMatrix(bounds.outWidth, bounds.outHeight, rect, orientation)
        val inverse = Matrix().apply { check(transform.invert(this)) }
        @Suppress("DEPRECATION")
        val decoder = BitmapRegionDecoder.newInstance(source.path, false) ?: error("原片不支持分块解码")
        try {
            StreamingJpeg(destination, width, height, (args["quality"] as? Number)?.toInt()?.coerceIn(60,100) ?: 100,
                iccProfile(space)).use { encoder ->
                var y = 0
                while (y < height) {
                    val h = minOf(STRIP_HEIGHT, height-y)
                    val strip = Bitmap.createBitmap(width,h,
                        if (linear) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888,
                        false, if (linear) ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB) else space)
                    try {
                        val canvas = Canvas(strip)
                        canvas.drawColor(Color.WHITE)
                        canvas.translate(0f,-y.toFloat())
                        background?.get(y)?.let { rows ->
                            canvas.save(); canvas.clipRect(rect)
                            drawTiles(canvas, rows)
                            canvas.restore()
                        }
                        val visible = Rect(rect)
                        if (visible.intersect(0,y,width,y+h)) {
                            val raw = RectF(visible)
                            inverse.mapRect(raw)
                            // One-pixel overlap supplies bilinear taps at strip boundaries.
                            val region = Rect(maxOf(0, floor(raw.left).toInt()-1), maxOf(0,floor(raw.top).toInt()-1),
                                minOf(bounds.outWidth,ceil(raw.right).toInt()+1), minOf(bounds.outHeight,ceil(raw.bottom).toInt()+1))
                            val photo = decoder.decodeRegion(region, BitmapFactory.Options().apply {
                                inPreferredConfig = if (linear) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
                                inPreferredColorSpace = space
                            }) ?: error("原片分块解码失败")
                            try {
                                if (Build.VERSION.SDK_INT >= 34 && photo.hasGainmap()) photo.setGainmap(null)
                                canvas.save(); canvas.clipRect(rect); canvas.concat(transform)
                                canvas.drawBitmap(photo,region.left.toFloat(),region.top.toFloat(),Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
                                canvas.restore()
                            } finally { photo.recycle() }
                        }
                        drawTiles(canvas, decoration.getValue(y))
                        if (linear) {
                            // Quantize only after linear extended-range blending,
                            // in the same ICC space passed to the JPEG encoder.
                            val encodedStrip = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888, false, space)
                            try {
                                Canvas(encodedStrip).drawBitmap(strip, 0f, 0f, Paint(Paint.DITHER_FLAG))
                                encoder.write(encodedStrip)
                            } finally { encodedStrip.recycle() }
                        } else encoder.write(strip)
                    } finally { strip.recycle() }
                    y += h
                }
                encoder.finish()
            }
        } finally { decoder.recycle() }

        // Gainmap reconstruction already samples the source at <=4096 and the map at <=1024.
        // A bounded colour-managed composition supplies the same normalized output geometry.
        val preview = decodeBounded(privateFile(json.getString("previewPath")))
        var backgroundPreview: Bitmap? = null
        val scaledRect = Rect((rect.left.toDouble()*preview.width/width).roundToInt(),
            (rect.top.toDouble()*preview.height/height).roundToInt(), (rect.right.toDouble()*preview.width/width).roundToInt(),
            (rect.bottom.toDouble()*preview.height/height).roundToInt())
        var composition: Bitmap? = null
        var mask: Bitmap? = null
        var carrier: File? = null
        var grafted: File? = null
        val hdr: PhotoHdr.Attachment
        try {
            backgroundPreview = json.optString("backgroundPreviewPath").takeIf { it.isNotEmpty() }?.let { decodeBounded(privateFile(it)) }
            composition = PhotoCompositor.compose(source, preview, scaledRect, orientation, backgroundPreview, decodeEdge = 4096, linear = linear)
            mask = (args["hdrMaskPath"] as? String)?.let { decodeBounded(privateFile(it)) }
            hdr = PhotoHdr.attachToExport(source, composition, scaledRect, orientation, mask)
            check(args["hdrExpected"] != true || hdr.attached) { "无法完整保留这张照片的 HDR 数据" }
            if (hdr.attached) {
                val carrierFile = File.createTempFile("hdr-carrier-", ".jpg", root).also { carrier=it }
                val graftedFile = File.createTempFile("hdr-full-", ".jpg", root).also { grafted=it }
                carrierFile.outputStream().buffered().use { check(composition.compress(Bitmap.CompressFormat.JPEG,100,it)) }
                PhotoContainers.attachGainmap(destination,carrierFile,graftedFile)
                java.nio.file.Files.move(graftedFile.toPath(),destination.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            preview.recycle(); backgroundPreview?.recycle(); composition?.recycle(); mask?.recycle()
            carrier?.delete(); grafted?.delete()
        }
        return Result(width,height,space.isSrgb,hdr)
    }

    private fun decodeBounded(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path,bounds)
        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong()*bounds.outHeight <= 4_194_304)
        return BitmapFactory.decodeFile(file.path) ?: error("分块预览无法读取")
    }

    private fun drawTiles(canvas: Canvas, rows: List<Tile>) {
        for (tile in rows) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tile.file.path,bounds)
            require(bounds.outWidth == tile.rect.width() && bounds.outHeight == tile.rect.height())
            val bitmap = BitmapFactory.decodeFile(tile.file.path) ?: error("水印分块无法读取")
            try { canvas.drawBitmap(bitmap,tile.rect.left.toFloat(),tile.rect.top.toFloat(),null) }
            finally { bitmap.recycle() }
        }
    }

    private fun sourceMatrix(width: Int,height: Int,rect: Rect,orientation: Int): Matrix {
        val src = FloatArray(6); val raw = DoubleArray(2)
        for (i in 0..2) {
            PhotoHdr.toRaw(if (i==1) 1.0 else 0.0,if (i==2) 1.0 else 0.0,if (orientation==0) 1 else orientation,raw)
            src[i*2]=(raw[0]*width).toFloat(); src[i*2+1]=(raw[1]*height).toFloat()
        }
        return Matrix().apply { check(setPolyToPoly(src,0,floatArrayOf(rect.left.toFloat(),rect.top.toFloat(),
            rect.right.toFloat(),rect.top.toFloat(),rect.left.toFloat(),rect.bottom.toFloat()),0,3)) }
    }

    /** Let Android describe its exact bitmap colour space; no hand-written ICC matrices. */
    internal fun iccProfile(space: ColorSpace): ByteArray? {
        val bitmap = Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888,false,space)
        val bytes = try { ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG,100,it)) }.toByteArray() }
            finally { bitmap.recycle() }
        var p=2; val parts = sortedMapOf<Int,ByteArray>(); var count=0
        val signature="ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)
        while (p+4 <= bytes.size && bytes[p]==0xff.toByte()) {
            val marker=bytes[p+1].toInt() and 255
            if (marker==0xda || marker==0xd9) break
            val length=((bytes[p+2].toInt() and 255) shl 8) or (bytes[p+3].toInt() and 255)
            require(length>=2 && p+2+length<=bytes.size)
            if (marker==0xe2 && length>=16 && signature.indices.all { bytes[p+4+it]==signature[it] }) {
                val number=bytes[p+16].toInt() and 255; count=bytes[p+17].toInt() and 255
                require(number in 1..count && !parts.containsKey(number))
                parts[number]=bytes.copyOfRange(p+18,p+2+length)
            }
            p+=length+2
        }
        if (parts.isEmpty()) { check(space.isSrgb) { "无法保存原片 ICC 色彩配置" }; return null }
        check(parts.size==count && parts.keys.toList()==(1..count).toList())
        return ByteArrayOutputStream().also { out -> parts.values.forEach { out.write(it) } }.toByteArray()
    }
}
