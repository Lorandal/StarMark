package dev.starmark.starmark

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.Context
import android.graphics.*
import android.graphics.fonts.SystemFonts
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.android.RenderMode
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.*
import dev.starmark.starmark.ui.MiuixHostLayout
import dev.starmark.starmark.ui.StarMarkShell
import dev.starmark.starmark.ui.ChromePhotoRevision
import dev.starmark.starmark.ui.LocalChromePhotoCapture
import dev.starmark.starmark.ui.LocalChromePhotoRevision
import dev.starmark.starmark.updates.installedAppUpdateInfo
import dev.starmark.starmark.updates.openUpdateInBrowser

@Suppress("DEPRECATION")
internal fun configureEdgeToEdgeWindow(window: Window) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
    window.isStatusBarContrastEnforced = false
    window.isNavigationBarContrastEnforced = false
}

internal fun updateSystemBarAppearance(window: Window, background: Int) {
    configureEdgeToEdgeWindow(window)
    val light = ColorUtils.calculateLuminance(background) > .5
    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = light
        isAppearanceLightNavigationBars = light
    }
}

class MainActivity : FlutterFragmentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val pickedZipCopies = mutableSetOf<String>()
    private var pendingPicker: MethodChannel.Result? = null
    private var pickerKind = "photo"
    private var pickerRemoveWatermark = false
    private var exportRequest: Map<*,*>? = null
    private val fontFiles = mutableMapOf<String, android.graphics.fonts.Font>()
    private val baseDir get() = File(filesDir,"starmark").apply { mkdirs() }
    private var uiState by mutableStateOf<Map<*, *>>(emptyMap<Any, Any>())
    private var uiChannel: MethodChannel? = null
    private lateinit var host: MiuixHostLayout
    private var currentSystemBarBackground: Int? = null
    private var lastMaterialSupport: Boolean? = null

    override fun provideRootLayout(context: Context): FrameLayout =
        MiuixHostLayout(context).also { host = it }

    override fun getRenderMode(): RenderMode = RenderMode.texture

    // FlutterFragmentActivity forwards this directly to Flutter, bypassing the
    // native Compose BackHandlers that own our pages and editor sheets.
    @Deprecated("Legacy key Back bridge; predictive Back uses the same dispatcher")
    override fun onBackPressed() = onBackPressedDispatcher.onBackPressed()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdgeWindow(window)
        val nativeShell = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@MainActivity)
            setViewTreeSavedStateRegistryOwner(this@MainActivity)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setContent {
                val photoCapture = remember(host) { { host.capturePhotoForChrome() } }
                val photoRevision = ChromePhotoRevision(
                    uiState["page"] as? String,
                    (uiState["photo"] as? Map<*, *>)?.get("id") as? String,
                    (uiState["revision"] as? Number)?.toLong(),
                    (uiState["materialRevision"] as? Number)?.toLong(),
                    uiState["busy"] == true,
                    host.photoGeometryRevision,
                )
                CompositionLocalProvider(LocalChromePhotoCapture provides photoCapture, LocalChromePhotoRevision provides photoRevision) {
                    Box(Modifier.semantics { testTagsAsResourceId = true }) {
                        StarMarkShell(
                            state = uiState,
                            onCommand = ::sendUiCommand,
                            onCanvasBounds = host::updateViewport,
                            onCanvasClip = host::updateCanvasClip,
                            onCanvasMotion = host::updatePageMotion,
                            onMaterialCapability = ::materialCapabilityChanged,
                            onBackgroundColor = { color ->
                                host.setBackgroundColor(color)
                                currentSystemBarBackground = color
                                updateSystemBarAppearance(window, color)
                            },
                        )
                    }
                }
            }
        }
        host.installShell(nativeShell)
    }

    override fun onPostResume() {
        super.onPostResume()
        // Flutter may restore its system UI when the activity resumes.
        configureEdgeToEdgeWindow(window)
        currentSystemBarBackground?.let { updateSystemBarAppearance(window, it) }
        materialCapabilityChanged(dev.starmark.starmark.ui.MiuiMaterialCapability.glassSupported(this))
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            dev.starmark.starmark.ui.ThumbnailCache.clear()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            configureEdgeToEdgeWindow(window)
            currentSystemBarBackground?.let { updateSystemBarAppearance(window, it) }
        }
    }

    private fun materialCapabilityChanged(supported: Boolean) {
        val previous = lastMaterialSupport
        lastMaterialSupport = supported
        if (previous != null && previous != supported) sendUiCommand("material.changed", emptyMap())
    }

    private fun sendUiCommand(action: String, args: Map<String, Any?>) {
        if (action == "navigate" && (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            android.util.Log.d("StarMarkUi", "navigate page=${args["page"]}")
        }
        uiChannel?.invokeMethod("command", args + mapOf("version" to 1, "action" to action), object : MethodChannel.Result {
            override fun success(result: Any?) = Unit
            override fun notImplemented() = showUiError("编辑引擎尚未就绪，请稍候")
            override fun error(code: String, message: String?, details: Any?) = showUiError(message ?: "操作失败")
        })
    }

    private fun showUiError(message: String) {
        uiState = uiState + mapOf("message" to message, "messageId" to System.nanoTime())
    }

    fun uiSnapshotForTest(): Map<*, *> = uiState
    fun canvasBoundsForTest(): android.graphics.Rect? = host.canvasBounds()

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        uiChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "dev.starmark/ui").apply {
            setMethodCallHandler { call, result ->
                if (call.method != "state") {
                    result.notImplemented()
                } else {
                    val snapshot = call.arguments as? Map<*, *>
                    if (snapshot?.get("version") != 1) {
                        result.error("protocol", "不支持的界面协议", null)
                    } else {
                        uiState = snapshot.toMap()
                        result.success(null)
                    }
                }
            }
        }
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger,"dev.starmark/native").setMethodCallHandler { call,result ->
            val args = call.arguments as? Map<*,*> ?: emptyMap<Any,Any>()
            when(call.method) {
                "directory" -> result.success(baseDir.absolutePath)
                "app.updateInfo" -> background(result) { installedAppUpdateInfo(this) }
                "app.openUpdate" -> {
                    try {
                        val url = args["url"] as? String ?: error("更新下载地址无效，请重新检查更新")
                        openUpdateInBrowser(this, url)
                        result.success(null)
                    } catch (error: Exception) {
                        result.error("update_browser", error.message ?: "无法打开系统浏览器，请稍后重试", null)
                    }
                }
                "capabilities" -> result.success(mapOf("api" to android.os.Build.VERSION.SDK_INT,"imageBlur" to true,"manufacturer" to android.os.Build.MANUFACTURER))
                "pickPhotos", "pickZip", "pickFont", "pickRegionImage" -> {
                    if(pendingPicker!=null){result.error("busy","选择器已打开",null);return@setMethodCallHandler}
                    pickerKind=when(call.method) { "pickZip" -> "zip"; "pickFont" -> "font"; "pickRegionImage" -> "region"; else -> "photo" }
                    pickerRemoveWatermark = args["autoRemoveWatermark"] == true
                    val intent=if(pickerKind=="photo") XiaomiGalleryPicker.resolve(this, args["multiple"]==true,
                        (uiState["settings"] as? Map<*,*>)?.get("theme") as? String ?: "system")
                    else Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        // Providers disagree on font MIME types; validate bytes
                        // instead of excluding valid TTF/OTF documents here.
                        type=if(pickerKind=="zip") "application/zip" else "*/*"
                        if(pickerKind=="font")putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("font/ttf","font/otf","font/sfnt","application/x-font-ttf","application/x-font-opentype","application/vnd.ms-opentype","application/octet-stream"))
                        // Xiaomi's image-only provider view omits SVG files even
                        // when image/svg+xml is listed. Browse openable files;
                        // RegionAssets validates the selected bytes as PNG/JPEG/SVG.
                        addCategory(Intent.CATEGORY_OPENABLE)
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE,args["multiple"]==true)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    pendingPicker=result
                    try { startActivityForResult(intent,4201) } catch(e:Exception){ pendingPicker=null;result.error("picker",e.message,null) }
                }
                "storage.availableBytes" -> background(result) { android.os.StatFs(baseDir.path).availableBytes }
                "releasePickedZip" -> background(result) {
                    val path = args["path"] as? String
                    if (path != null && pickedZipCopies.remove(path)) {
                        privateFile(path).delete()
                    } else false
                }
                "saveJpeg" -> background(result) { saveJpeg(args) }
                "blur" -> background(result) { blur(args) }
                "xiaomi.blur" -> background(result) { xiaomiBlur(args) }
                "xiaomi.glass" -> background(result) { xiaomiBlur(args, glass = true) }
                "date.lunar" -> background(result) {
                    xiaomiLunarDate((args["year"] as Number).toInt(), (args["month"] as Number).toInt(), (args["day"] as Number).toInt())
                }
                "photo.metadata" -> background(result) {
                    val source = privateFile(args["path"] as String)
                    require(source.isFile) { "原始照片不存在" }
                    photoMetadata(ExifInterface(source))
                }
                "photo.features" -> background(result) {
                    PhotoImportPreparation.features(privateFile(args["path"] as String))
                }
                "fonts" -> background(result) { listFonts() }
                "prepareFont" -> background(result) { prepareFont(args["id"] as String) }
                "saveZip" -> {
                    if(pendingPicker!=null){result.error("busy","文件操作进行中",null);return@setMethodCallHandler}
                    val path=privateFile(args["path"] as String)
                    if(!path.exists()){result.error("file","样式文件不存在",null);return@setMethodCallHandler}
                    pendingPicker=result;pickerKind="saveZip";exportRequest=args
                    val suggested=(args["suggestedName"] as? String)?.takeIf {
                        it.length <= 200 && it.endsWith(".zip") && it.none { c -> c == '/' || c == '\\' || c.code < 32 }
                    } ?: "StarMark-${System.currentTimeMillis()}.zip"
                    val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type="application/zip";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_TITLE,suggested) }
                    startActivityForResult(intent,4201)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun background(result:MethodChannel.Result,work:()->Any?) {
        executor.execute {
            try { val value=work(); runOnUiThread { result.success(value) } }
            catch (_: OutOfMemoryError) { runOnUiThread { result.error("memory", "处理照片时可用内存不足，请关闭其他应用后重试", null) } }
            catch (e: Exception) { runOnUiThread { result.error("native", e.message ?: "图片操作失败", null) } }
        }
    }
    private fun privateFile(path:String):File {
        val file=File(path).canonicalFile
        require(file.path.startsWith(baseDir.canonicalPath+File.separator)) { "文件不在应用资源目录" }
        return file
    }
    @Deprecated("Legacy activity result bridge")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=4201)return
        val result=pendingPicker?:return
        pendingPicker=null
        if(resultCode!=Activity.RESULT_OK||data==null){result.success(null);return}
        val kind=pickerKind; val removeWatermark = pickerRemoveWatermark
        if(kind=="saveZip") {
            val uri=data.data?:run{result.success(null);return}
            val path=exportRequest?.get("path") as? String ?:run{result.error("file","文件不存在",null);return}
            background(result){ contentResolver.openOutputStream(uri)?.use { output->privateFile(path).inputStream().use { it.copyTo(output) } }?:error("无法写入目标文件"); uri.toString() }
            return
        }
        val uris=mutableListOf<android.net.Uri>()
        data.clipData?.let { clip->for(i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
        if(uris.isEmpty())data.data?.let{uris.add(it)}
        background(result){
            if(kind=="font" || kind=="region") {
                val uri=uris.firstOrNull()?:error("无法读取资源文件")
                var title=if(kind=="region") "导入图片" else "导入字体"
                contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { cursor ->
                    if(cursor.moveToFirst()) {
                        val nameIndex=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if(nameIndex>=0)title=cursor.getString(nameIndex)?:title
                        val sizeIndex=cursor.getColumnIndex(OpenableColumns.SIZE)
                        if(sizeIndex>=0&&!cursor.isNull(sizeIndex))require(cursor.getLong(sizeIndex)<=32L*1024*1024){"资源超过 32 MiB"}
                    }
                }
                val bytes=java.io.ByteArrayOutputStream().use { output ->
                    contentResolver.openInputStream(uri)?.use { input ->
                        val buffer=ByteArray(8192);var total=0
                        while(true){val read=input.read(buffer);if(read<0)break;total+=read;require(total<=32*1024*1024){"资源超过 32 MiB"};output.write(buffer,0,read)}
                    }?:error("无法读取资源文件")
                    output.toByteArray()
                }
                // Do not persist access to the provider: Dart validates and
                // privately copies the resource before publishing selection.
                mapOf("title" to title,"bytes" to bytes)
            } else if(kind=="zip") {
                val file=File(baseDir,"import-${UUID.randomUUID()}.zip")
                val uri=uris.first()
                val declaredSize=contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use { cursor ->
                    if(cursor.moveToFirst()) {
                        val column=cursor.getColumnIndex(OpenableColumns.SIZE)
                        if(column>=0 && !cursor.isNull(column))cursor.getLong(column) else null
                    } else null
                }
                try { contentResolver.openInputStream(uri)?.use { input ->
                    copyWatermarkZip(input, { file.outputStream() }, declaredSize, { android.os.StatFs(baseDir.path).availableBytes })
                }?:error("无法读取样式包")
                pickedZipCopies.add(file.absolutePath)
                file.absolutePath } catch(e:Exception){file.delete();throw e}
            } else uris.take(30).map { uri -> importPhoto(uri, removeWatermark) }
        }
    }
    private fun importPhoto(uri:android.net.Uri, autoRemove: Boolean = false):Map<String,Any?> {
        runOnUiThread { uiChannel?.invokeMethod("import.progress", "reading") }
        val originalName = runCatching {
            contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
                val column=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if(column>=0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: if(uri.scheme=="file") uri.lastPathSegment else null
        val folder=File(baseDir,"assets/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var source=File(folder,"source.img")
            val originalAllowed = shouldReadOriginalPhoto(uri.scheme, uri.authority,
                checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            copyPhotoSource(source,
                original = if (originalAllowed) ({ contentResolver.openInputStream(MediaStore.setRequireOriginal(uri)) }) else null,
                regular = { contentResolver.openInputStream(uri) })
            // A Gallery URI may label a JPEG Live photo as a generic file,
            // image/jpg, video, or no MIME at all. Validate the copied cover's
            // actual codec, never discard its video trailer or trust the label.
            val importedBounds = checkedPhotoBounds(source)
            val prepared = PhotoImportPreparation.prepare(source, autoRemove, this)
            runOnUiThread { uiChannel?.invokeMethod("import.progress", "preparing") }
            val bounds = if (source == prepared.source) importedBounds else checkedPhotoBounds(prepared.source)
            source = prepared.source
            val exif=ExifInterface(source)
            val orientation=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)
                .let { if (it == ExifInterface.ORIENTATION_UNDEFINED) ExifInterface.ORIENTATION_NORMAL else it }
            val swapped=orientation in 5..8
            val preview=decodeOriented(source,orientation,2048)
            val previewFile=File(folder,"preview.png")
            try { previewFile.outputStream().use { check(preview.compress(Bitmap.CompressFormat.PNG,100,it)) { "照片预览保存失败" } } }
            finally { preview.recycle() }
            return mapOf("id" to folder.name,"path" to source.path,"preview" to previewFile.path,"width" to if(swapped)bounds.outHeight else bounds.outWidth,"height" to if(swapped)bounds.outWidth else bounds.outHeight,"orientation" to orientation,
                "metadata" to photoMetadata(exif),
                "originalName" to originalName,
                "restoredDocument" to prepared.documentJson?.let { org.json.JSONObject(it).toString() },
                "factoryRestored" to prepared.factoryRestored) + PhotoImportPreparation.features(source)
        }catch(e:Exception){folder.deleteRecursively();throw e}
        catch(e:OutOfMemoryError){folder.deleteRecursively();throw e}
    }
    private fun checkedPhotoBounds(source: File): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outMimeType in setOf("image/jpeg", "image/png", "image/heic", "image/heif")) {
            "请选择 JPEG、PNG 或 HEIF 照片"
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "照片无法解码" }
        return bounds
    }
    private fun photoMetadata(exif: ExifInterface): Map<String, Any?> {
        fun number(tag: String) = exif.getAttributeDouble(tag, 0.0)
            .takeIf { it.isFinite() && it > 0 } ?: 0.0
        return mapOf(
            "make" to (exif.getAttribute(ExifInterface.TAG_MAKE) ?: ""),
            "model" to (exif.getAttribute(ExifInterface.TAG_MODEL) ?: ""),
            "iso" to number(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
            "aperture" to number(ExifInterface.TAG_F_NUMBER),
            "focal" to number(ExifInterface.TAG_FOCAL_LENGTH),
            "focal35mm" to number(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM),
            "exposure" to number(ExifInterface.TAG_EXPOSURE_TIME),
            "dateTime" to (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: ""),
            "latitude" to exif.latLong?.get(0),
            "longitude" to exif.latLong?.get(1),
        )
    }
    private fun decodeOriented(source:File,orientation:Int,maxEdge:Int):Bitmap {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(source.path,bounds)
        var sample=1
        while(max(bounds.outWidth,bounds.outHeight)/sample>maxEdge*2)sample*=2
        val bitmap=BitmapFactory.decodeFile(source.path,BitmapFactory.Options().apply {inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})?:error("照片解码失败")
        val matrix=Matrix().apply { when(orientation){2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f);5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f);7->{setRotate(-90f);postScale(-1f,1f)};8->setRotate(-90f)} }
        // Combine orientation and preview scaling: never allocate a second full sampled image.
        val scale=minOf(1f,maxEdge.toFloat()/max(bitmap.width,bitmap.height))
        matrix.postScale(scale,scale)
        return try {
            val output=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
            if(output!==bitmap)bitmap.recycle()
            output
        } catch(error:Throwable) { bitmap.recycle();throw error }
    }
    private fun xiaomiBlur(args: Map<*, *>, glass: Boolean = false): String? {
        val tone = args["tone"] as? String
        require(tone == "light" || tone == "dark") { "无效模糊颜色" }
        val source = privateFile(args["path"] as String)
        val custom = args["custom"] == true
        val customRadius = ((args["radius"] as? Number)?.toFloat() ?: .035f) * (4f / .035f)
        if (!glass && custom) require(customRadius.isFinite() && customRadius >= 0f) { "无效模糊半径" }
        val bitmap = decodeOriented(source, (args["orientation"] as? Number)?.toInt() ?: 1, 1600)
        try {
            // Offline View glass has an implicit vendor zoom (about 1.07x on
            // the reference device). Use explicit cover coordinates and the
            // same current portable soft-glass recipe on every photo backend.
            val output = (if (glass) dev.starmark.starmark.ui.PortablePhotoGlass.render(bitmap,
                (args["width"] as Number).toInt(), (args["height"] as Number).toInt(), tone == "light")
                else runCatching { dev.starmark.starmark.xiaomi.XiaomiFilterBlur.process(bitmap, tone == "light",
                    radius = if (custom) customRadius.coerceIn(.1f, 64f) else 4f, applyTone = !custom) }.getOrNull()
                ?: dev.starmark.starmark.ui.PortablePhotoGlass.render(bitmap,
                    (args["width"] as Number).toInt(), (args["height"] as Number).toInt(), tone == "light", glass,
                    radius = if (custom) ((args["radius"] as? Number)?.toFloat() ?: .035f) else .012f, applyTone = !custom))
                ?: return null
            try {
                val file = File(baseDir, "blur-${UUID.randomUUID()}.png")
                file.outputStream().use { check(output.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                return file.path
            } finally { output.recycle() }
        } finally { bitmap.recycle() }
    }
    private fun blur(args:Map<*,*>):String {
        val source=privateFile(args["path"] as String)
        val targetWidth=(args["width"] as Number).toInt();val targetHeight=(args["height"] as Number).toInt()
        require(dev.starmark.starmark.TiledPhotoExporter.validSize(targetWidth,targetHeight)){"无效背景尺寸"}
        val factor=min(1.0,1400.0/max(targetWidth,targetHeight));val w=max(1,(targetWidth*factor).roundToInt());val h=max(1,(targetHeight*factor).roundToInt())
        val bitmap=decodeOriented(source,(args["orientation"] as? Number)?.toInt()?:1,1600)
        val node=RenderNode("starmark-image-blur");node.setPosition(0,0,w,h)
        val canvas=node.beginRecording();val s=max(w.toFloat()/bitmap.width,h.toFloat()/bitmap.height)
        canvas.drawBitmap(bitmap,null,RectF((w-bitmap.width*s)/2,(h-bitmap.height*s)/2,(w+bitmap.width*s)/2,(h+bitmap.height*s)/2),Paint(Paint.FILTER_BITMAP_FLAG));node.endRecording()
        val radius=((args["radius"] as? Number)?.toFloat()?:.035f)*min(w,h)
        node.setRenderEffect(RenderEffect.createBlurEffect(max(0.1f,radius),max(0.1f,radius),Shader.TileMode.CLAMP))
        val reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,1,HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer=HardwareRenderer();var image:android.media.Image?=null
        try {
            renderer.setSurface(reader.surface);renderer.setContentRoot(node)
            val status=renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            require(status==HardwareRenderer.SYNC_OK||status==HardwareRenderer.SYNC_FRAME_DROPPED){"系统模糊渲染失败 ($status)"}
            image=reader.acquireNextImage()?:error("系统模糊无输出")
            val buffer=image.hardwareBuffer?:error("系统模糊无硬件缓冲")
            val hardware=Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB))?:error("系统模糊读取失败")
            val output=hardware.copy(Bitmap.Config.ARGB_8888,false);hardware.recycle();buffer.close()
            val file=File(baseDir,"blur-${UUID.randomUUID()}.png");file.outputStream().use { output.compress(Bitmap.CompressFormat.PNG,100,it) };output.recycle();return file.path
        }finally{image?.close();renderer.destroy();reader.close();node.discardDisplayList();bitmap.recycle()}
    }
    private fun saveJpeg(args:Map<*,*>):Map<String,Any?> {
        val input=privateFile(args["path"] as String)
        val source=(args["sourcePath"] as? String)?.let(::privateFile)
        return PhotoExporter.save(this,input,source,args)
    }
    private fun listFonts():List<Map<String,Any?>> {
        fontFiles.clear()
        return SystemFonts.getAvailableFonts().filter{it.ttcIndex==0}.sortedBy{it.file?.name?:""}.mapNotNull { font->
            val file=font.file?:return@mapNotNull null
            if(!file.canRead())return@mapNotNull null
            val axes=font.axes?.associate{it.tag to it.styleValue.toDouble()}?:emptyMap()
            val axisKey=axes.entries.sortedBy{it.key}.joinToString("-"){"${it.key}${it.value}"}
            val id="sys-${file.name}-${font.style.weight}-${font.style.slant}-${font.ttcIndex}-$axisKey"
            fontFiles[id]=font
            mapOf("id" to id,"title" to file.nameWithoutExtension,"weight" to font.style.weight,"style" to if(font.style.slant==1)"italic" else "normal","axes" to axes,"source" to "system","fingerprint" to "${file.length()}-${file.lastModified()}")
        }.distinctBy{it["id"]}.take(150)
    }
    private fun prepareFont(id:String):Map<String,String> {
        if(fontFiles[id]==null)listFonts()
        val font=fontFiles[id]?:error("系统字体不可用，请重新选择")
        val bytes=font.buffer.duplicate().apply{rewind()}.let { b->ByteArray(b.remaining()).also{b.get(it)} }
        val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        val dir=File(baseDir,"fonts").apply{mkdirs()};val file=File(dir,"$digest.font")
        if(!file.exists())file.writeBytes(bytes)
        return mapOf("path" to file.path,"fingerprint" to digest,"family" to "System_$digest")
    }
    override fun onDestroy(){
        uiChannel?.setMethodCallHandler(null)
        uiChannel = null
        pendingPicker?.error("cancelled","编辑器已关闭",null);pendingPicker=null
        executor.shutdownNow();super.onDestroy()
    }
}
