package dev.starmark.starmark.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.starmark.starmark.configureEdgeToEdgeWindow
import dev.starmark.starmark.updateSystemBarAppearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** An app-owned gallery. Only the Android permission dialog is external. */
class AlbumPickerActivity : ComponentActivity() {
    private var access by mutableStateOf(AlbumAccess.Denied)
    private var photos by mutableStateOf<List<AlbumPhoto>>(emptyList())
    private var selection by mutableStateOf<List<String>>(emptyList())
    private var bucket by mutableStateOf<String?>(null)
    private var loading by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var permissionInFlight = false
    private var finished = false
    private var queryJob: Job? = null
    private var querySignal: CancellationSignal? = null
    private val multiple get() = intent.getBooleanExtra(EXTRA_MULTIPLE, false)
    private val thumbnails by lazy { AlbumThumbnails(contentResolver) }
    private var systemBarBackground: Int? = null
    private val permissionHistory by lazy { getSharedPreferences("album_permissions", MODE_PRIVATE) }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionInFlight = false
        if (currentAccess() == AlbumAccess.Denied) {
            Toast.makeText(this, "未获得照片权限，已取消导入", Toast.LENGTH_SHORT).show()
            complete(null)
        } else refreshLibrary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdgeWindow(window)
        selection = savedInstanceState?.getStringArrayList("selection") ?: emptyList()
        bucket = savedInstanceState?.getString("bucket")
        permissionInFlight = savedInstanceState?.getBoolean("permissionInFlight") ?: false
        setContent {
            StarMarkTheme(mapOf("theme" to (intent.getStringExtra(EXTRA_THEME) ?: "system"))) {
                val haptics = LocalHapticFeedback.current
                val background = MiuixTheme.colorScheme.background
                SideEffect {
                    systemBarBackground = background.toArgb()
                    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(background.toArgb()))
                    updateSystemBarAppearance(window, background.toArgb())
                }
                BackHandler { complete(null) }
                AlbumPickerScreen(
                    photos = photos, selection = selection, bucket = bucket, access = access,
                    loading = loading, error = error, multiple = multiple, thumbnails = thumbnails,
                    onBucket = { bucket = it }, onCancel = { complete(null) }, onRetry = ::refreshLibrary,
                    onMorePhotos = ::requestPhotoAccess,
                    onSelect = { photo ->
                        val next = toggleAlbumSelection(selection, photo.uri, multiple)
                        if (next == selection && photo.uri !in selection) {
                            haptics.performHapticFeedback(HapticFeedbackType.Reject)
                            Toast.makeText(this, "一次最多导入 30 张照片", Toast.LENGTH_SHORT).show()
                        }
                        if (next != selection) haptics.performHapticFeedback(toggleHaptic(photo.uri in selection))
                        selection = next
                    },
                    onImport = {
                        if (currentAccess() == AlbumAccess.Denied) complete(null)
                        else {
                            val selected = availableAlbumSelection(selection, photos.mapTo(HashSet()) { it.uri }, multiple)
                            if (selected.isNotEmpty()) complete(selected.map(Uri::parse))
                        }
                    },
                )
            }
        }
        if (!permissionInFlight) {
            if (currentAccess() == AlbumAccess.Denied) requestPhotoAccess()
            else if (needsMediaLocationRequest()) requestPermissions(listOf(Manifest.permission.ACCESS_MEDIA_LOCATION))
        }
    }

    override fun onResume() {
        super.onResume()
        // Android permits changing full/limited access while this activity is stopped.
        if (!permissionInFlight && !finished) {
            if (currentAccess() == AlbumAccess.Denied) complete(null) else refreshLibrary()
        }
    }

    override fun onStop() {
        querySignal?.cancel()
        queryJob?.cancel()
        thumbnails.clear()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("selection", ArrayList(selection))
        outState.putString("bucket", bucket)
        outState.putBoolean("permissionInFlight", permissionInFlight)
        super.onSaveInstanceState(outState)
    }

    private fun currentAccess() = albumAccess(
        Build.VERSION.SDK_INT,
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED,
        Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED,
    )

    private fun requestPhotoAccess() {
        val permissions = mutableListOf(Manifest.permission.READ_MEDIA_IMAGES)
        if (Build.VERSION.SDK_INT >= 34) permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        if (needsMediaLocationRequest()) permissions.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        requestPermissions(permissions)
    }

    private fun needsMediaLocationRequest() = shouldRequestMediaLocation(
        Build.VERSION.SDK_INT,
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED,
        permissionHistory.getBoolean("media_location_requested", false),
    )

    private fun requestPermissions(permissions: List<String>) {
        if (permissionInFlight || finished) return
        permissionInFlight = true
        if (Manifest.permission.ACCESS_MEDIA_LOCATION in permissions) {
            // Persist only request history, never permission status. A denial is
            // optional and must not trigger another prompt on each album visit.
            permissionHistory.edit().putBoolean("media_location_requested", true).apply()
        }
        permissionRequest.launch(permissions.toTypedArray())
    }

    private fun refreshLibrary() {
        if (finished) return
        access = currentAccess()
        if (access == AlbumAccess.Denied) { complete(null); return }
        querySignal?.cancel()
        queryJob?.cancel()
        val signal = CancellationSignal().also { querySignal = it }
        loading = true
        error = null
        photos = emptyList()
        thumbnails.clear()
        queryJob = lifecycleScope.launch {
            try {
                val available = loadAlbumPhotos(contentResolver, signal)
                photos = available
                selection = availableAlbumSelection(selection, available.mapTo(HashSet()) { it.uri }, multiple)
                if (bucket != null && available.none { it.bucketId == bucket }) bucket = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                selection = emptyList()
                error = "暂时无法读取相册，请重试"
            } finally {
                if (querySignal === signal) loading = false
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            configureEdgeToEdgeWindow(window)
            systemBarBackground?.let { updateSystemBarAppearance(window, it) }
        }
    }

    private fun complete(uris: List<Uri>?) {
        if (finished) return
        finished = true
        if (uris.isNullOrEmpty()) setResult(Activity.RESULT_CANCELED)
        else setResult(Activity.RESULT_OK, Intent().apply {
            data = uris.first()
            clipData = ClipData.newUri(contentResolver, "照片", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
        })
        finish()
    }

    companion object {
        const val EXTRA_MULTIPLE = "dev.starmark.album.multiple"
        const val EXTRA_THEME = "dev.starmark.album.theme"
    }
}

internal data class AlbumPhoto(val uri: String, val name: String, val bucketId: String, val bucketName: String)

private suspend fun loadAlbumPhotos(resolver: ContentResolver, signal: CancellationSignal): List<AlbumPhoto> = withContext(Dispatchers.IO) {
    val media = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
    val result = ArrayList<AlbumPhoto>()
    resolver.query(media, projection, "${MediaStore.Images.Media.MIME_TYPE} IN (?, ?, ?, ?) AND ${MediaStore.Images.Media.IS_PENDING} = 0 AND ${MediaStore.Images.Media.IS_TRASHED} = 0",
        arrayOf("image/jpeg", "image/png", "image/heic", "image/heif"), "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC", signal)?.use { cursor ->
        val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val name = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val bucketId = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
        val bucketName = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        while (cursor.moveToNext()) {
            currentCoroutineContext().ensureActive()
            result.add(AlbumPhoto(ContentUris.withAppendedId(media, cursor.getLong(id)).toString(),
                cursor.getString(name) ?: "照片", cursor.getString(bucketId) ?: "unknown",
                cursor.getString(bucketName)?.takeIf { it.isNotBlank() } ?: "其他照片"))
        }
    } ?: error("MediaStore unavailable")
    result
}

/** Small, bounded previews only; originals remain in MediaStore until importPhoto copies them. */
internal class AlbumThumbnails(private val resolver: ContentResolver) {
    private val permits = Semaphore(4)
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    fun clear() = cache.evictAll()
    suspend fun load(uri: String, signal: CancellationSignal): Bitmap? = permits.withPermit {
        withContext(Dispatchers.IO) {
            cache.get(uri) ?: try {
                resolver.loadThumbnail(Uri.parse(uri), Size(256, 256), signal).also { cache.put(uri, it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
}

@Composable
private fun AlbumPickerScreen(
    photos: List<AlbumPhoto>, selection: List<String>, bucket: String?, access: AlbumAccess,
    loading: Boolean, error: String?, multiple: Boolean, thumbnails: AlbumThumbnails,
    onBucket: (String?) -> Unit, onSelect: (AlbumPhoto) -> Unit, onCancel: () -> Unit,
    onRetry: () -> Unit, onMorePhotos: () -> Unit, onImport: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val albums = remember(photos) { photos.groupBy { it.bucketId } }
    val visible = remember(photos, bucket) { if (bucket == null) photos else photos.filter { it.bucketId == bucket } }
    ChromeOverlayHost {
    Column(Modifier.fillMaxSize().scrollBoundaryHaptics().background(colors.background).safeDrawingPadding()
        .semantics { testTagsAsResourceId = true }.testTag("screen.albumPicker")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton("取消", onCancel, modifier = Modifier.testTag("album.cancel"))
            Text("选择照片", Modifier.weight(1f), style = MiuixTheme.textStyles.title3)
            Text(if (multiple) "${selection.size} / 30" else "${selection.size} / 1",
                modifier = Modifier.padding(end = 12.dp).testTag("album.selectionCount"), color = colors.onSurfaceVariantSummary)
        }
        if (access == AlbumAccess.Limited) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("当前仅能访问获准的照片", Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary)
                TextButton("选择更多", onMorePhotos, modifier = Modifier.testTag("album.morePhotos"))
            }
        }
        if (!loading && photos.isNotEmpty()) LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.testTag("album.buckets"),
        ) {
            item(key = "all") { AlbumChip("全部照片 ${photos.size}", bucket == null) { onBucket(null) } }
            items(albums.entries.toList(), key = { it.key }) { album ->
                AlbumChip("${album.value.first().bucketName} ${album.value.size}", bucket == album.key) { onBucket(album.key) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                loading -> Unit
                error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, color = colors.onSurfaceVariantSummary)
                    TextButton("重试", onRetry, modifier = Modifier.testTag("album.retry"))
                }
                visible.isEmpty() -> Text("暂无可导入的照片", color = colors.onSurfaceVariantSummary, modifier = Modifier.testTag("album.empty"))
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp), contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize().testTag("album.grid"),
                ) {
                    items(visible, key = { it.uri }) { photo ->
                        AlbumPhotoTile(photo, selection.indexOf(photo.uri) + 1, thumbnails) { onSelect(photo) }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(if (selection.isEmpty()) "导入照片" else "导入 ${selection.size} 张照片", hapticClick(onClick = onImport),
                enabled = !loading && selection.isNotEmpty(), colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("album.import"))
        }
    }
    DeferredOperationProgress(loading, "album", "album.loading", "正在读取相册…", "正在准备照片列表", onDismissRequest = onCancel)
    }
}

@Composable
private fun AlbumChip(label: String, active: Boolean, onClick: () -> Unit) {
    TextButton(label, hapticClick(HapticFeedbackType.SegmentTick, feedbackEnabled = !active, onClick = onClick), colors = if (active) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
        modifier = Modifier.semantics { selected = active })
}

@Composable
private fun AlbumPhotoTile(photo: AlbumPhoto, selectionNumber: Int, thumbnails: AlbumThumbnails, onClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, photo.uri, thumbnails) {
        val signal = CancellationSignal()
        val job = launch { value = thumbnails.load(photo.uri, signal) }
        awaitDispose { signal.cancel(); job.cancel() }
    }
    val colors = MiuixTheme.colorScheme
    val shape = MiuixCornerShape(12.dp)
    Box(Modifier.aspectRatio(1f).clip(shape).background(colors.surfaceContainer)
        .then(if (selectionNumber > 0) Modifier.border(3.dp, colors.primary, shape) else Modifier)
        .clickable(role = Role.Checkbox, onClick = onClick)
        .semantics { selected = selectionNumber > 0; contentDescription = photo.name }
        .testTag("album.photo.${Uri.parse(photo.uri).lastPathSegment}")) {
        val loaded = bitmap
        if (loaded != null) Image(loaded.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(photo.name, modifier = Modifier.align(Alignment.Center).padding(8.dp), maxLines = 2,
            overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape)
            .background(if (selectionNumber > 0) colors.primary else Color.Black.copy(alpha = .35f))
            .border(1.5.dp, if (selectionNumber > 0) colors.primary else Color.White, CircleShape), contentAlignment = Alignment.Center) {
            if (selectionNumber > 0) Text(selectionNumber.toString(), color = colors.onPrimary, style = MiuixTheme.textStyles.footnote1)
        }
    }
}
