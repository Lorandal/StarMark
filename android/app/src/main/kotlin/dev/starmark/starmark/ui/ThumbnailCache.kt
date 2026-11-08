package dev.starmark.starmark.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** Retain decoded thumbnail objects (and their GPU uploads) across route disposal. */
internal object ThumbnailCache {
    private data class Entry(val stamp: String, val bitmap: ImageBitmap)
    private val cache = object : LruCache<String, Entry>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Entry) = value.bitmap.width * value.bitmap.height * 4
    }
    // Coalesce equal keys without making a slow image block every visible tile.
    // Two decoders bound transient bitmap memory; the retained LRU stays at 16 MiB.
    private val decodeLocks = Array(16) { Mutex() }
    private val decoders = Semaphore(2)
    private fun key(path: String, edge: Int) = "$edge:$path"
    private fun assetPath(path: String): String? = when {
        path.startsWith("flutter_assets/") -> path
        path.startsWith("assets/") || path.startsWith("asset:") -> "flutter_assets/${path.removePrefix("asset:").removePrefix("/")}"
        else -> null
    }
    fun peek(path: String, edge: Int): ImageBitmap? {
        // These files are immutable imports / bundled assets. Other user paths
        // are stat-validated on IO before reuse, so replacements never go stale.
        if (assetPath(path) == null && !path.replace('\\', '/').contains("/starmark/assets/")) return null
        return cache.get(key(path, edge))?.bitmap
    }
    suspend fun load(context: Context, path: String, edge: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val id = key(path, edge)
        decodeLocks[(id.hashCode() and Int.MAX_VALUE) % decodeLocks.size].withLock {
          decoders.withPermit {
            runCatching {
                if (path.isBlank()) return@runCatching null
                val asset = assetPath(path)
                val file = if (asset == null) File(path.removePrefix("file://")) else null
                val stamp = if (file != null) "${file.length()}:${file.lastModified()}" else "asset"
                cache.get(id)?.takeIf { it.stamp == stamp }?.let { return@runCatching it.bitmap }
                fun stream() = if (asset != null) context.assets.open(asset) else requireNotNull(file).inputStream()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                stream().use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                val target = edge.coerceIn(128, 800)
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
                stream().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                    ?.asImageBitmap()?.also { cache.put(id, Entry(stamp, it)) }
            }.getOrNull()
          }
        }
    }
    fun clear() = cache.evictAll()
    internal fun retainedBytes() = cache.size()
}
