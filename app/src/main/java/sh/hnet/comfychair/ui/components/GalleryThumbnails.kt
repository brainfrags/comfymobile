package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.LocalGalleryStore
import sh.hnet.comfychair.gallery.GalleryItem
import java.util.concurrent.ConcurrentHashMap

/**
 * Small, downsampled bitmaps for the gallery grid, kept separately from the
 * full-size media cache so scrolling doesn't evict/reload big images.
 */
object GalleryThumbnailCache {
    private const val MAX_SIDE = 512

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** width / height per item; tiny and never evicted, so layouts don't jump on reload. */
    private val aspectRatios = ConcurrentHashMap<String, Float>()

    fun get(key: String): Bitmap? = cache.get(key)
    fun aspectRatio(key: String): Float? = aspectRatios[key]

    private fun remember(key: String, bitmap: Bitmap): Bitmap {
        if (bitmap.height > 0) aspectRatios[key] = bitmap.width.toFloat() / bitmap.height
        cache.put(key, bitmap)
        return bitmap
    }

    private fun downscale(src: Bitmap): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= MAX_SIDE) return src
        val scale = MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true
        )
    }

    private fun decodeSampled(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Load (or reuse) the thumbnail for an item. Runs on IO. */
    suspend fun load(item: GalleryItem, context: android.content.Context): Bitmap? = withContext(Dispatchers.IO) {
        val key = item.toCacheKey()
        get(key.keyString)?.let { return@withContext it }

        // 1) Permanent on-device copy: decode a small version straight from the file
        if (!item.isVideo) {
            LocalGalleryStore.localFile(context, ConnectionManager.currentServerId, key)?.let { file ->
                decodeSampled(file.absolutePath)?.let { return@withContext remember(key.keyString, it) }
            }
        }

        // 2) Otherwise use the media cache (server / disk cache / video frame) and shrink it
        val full = MediaCache.fetchBitmap(key, item.isVideo, item.subfolder, item.type) ?: return@withContext null
        remember(key.keyString, downscale(full))
    }
}

data class ThumbnailState(val bitmap: Bitmap?, val isLoading: Boolean)

/**
 * Thumbnail for a gallery grid cell. Retries a couple of times if loading fails
 * (e.g. a brief network hiccup) instead of staying blank.
 */
@Composable
fun rememberGalleryThumbnail(item: GalleryItem, context: android.content.Context): ThumbnailState {
    val keyString = item.toCacheKey().keyString
    val state = remember(keyString) {
        val cached = GalleryThumbnailCache.get(keyString)
        mutableStateOf(ThumbnailState(cached, cached == null))
    }
    LaunchedEffect(keyString) {
        if (state.value.bitmap != null) return@LaunchedEffect
        repeat(3) { attempt ->
            val bmp = GalleryThumbnailCache.load(item, context)
            if (bmp != null) {
                state.value = ThumbnailState(bmp, false)
                return@LaunchedEffect
            }
            if (attempt < 2) delay(1500L * (attempt + 1))
        }
        state.value = ThumbnailState(null, false)
    }
    return state.value
}
