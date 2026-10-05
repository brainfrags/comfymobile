package sh.hnet.comfychair.gallery

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import java.io.File
import java.io.OutputStream

/**
 * Saving media to the phone's Photos (Pictures/ComfyMobile, Movies/ComfyMobile) and
 * sharing it with other apps. Used by the gallery and the media viewer.
 */
object MediaExport {

    /** The full image, from the cache or the server. */
    suspend fun loadImage(key: MediaCacheKey, subfolder: String, type: String): Bitmap? =
        MediaCache.getBitmap(key) ?: MediaCache.fetchImage(key, subfolder, type)

    /** The video's bytes, from the cache or the server. */
    suspend fun loadVideo(key: MediaCacheKey, subfolder: String, type: String): ByteArray? =
        MediaCache.getVideoBytes(key) ?: MediaCache.fetchVideoBytes(key, subfolder, type)

    suspend fun loadImage(item: GalleryItem) = loadImage(item.toCacheKey(), item.subfolder, item.type)
    suspend fun loadVideo(item: GalleryItem) = loadVideo(item.toCacheKey(), item.subfolder, item.type)

    /** Save an image to Photos as PNG. @return true if saved */
    suspend fun saveImage(context: Context, bitmap: Bitmap): Boolean =
        saveToPhotos(context, isVideo = false) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    /** Save a video to Photos as MP4. @return true if saved */
    suspend fun saveVideo(context: Context, bytes: ByteArray): Boolean =
        saveToPhotos(context, isVideo = true) { it.write(bytes) }

    /** Save a gallery item to Photos, fetching it if needed. @return true if saved */
    suspend fun saveItem(context: Context, item: GalleryItem): Boolean =
        if (item.isVideo) loadVideo(item)?.let { saveVideo(context, it) } == true
        else loadImage(item)?.let { saveImage(context, it) } == true

    private suspend fun saveToPhotos(context: Context, isVideo: Boolean, write: (OutputStream) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val values = ContentValues().apply {
                    if (isVideo) {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "ComfyMobile_${System.currentTimeMillis()}.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ComfyMobile")
                    } else {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "ComfyMobile_${System.currentTimeMillis()}.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ComfyMobile")
                    }
                }
                val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val resolver = context.contentResolver
                val uri = resolver.insert(collection, values) ?: return@withContext false
                resolver.openOutputStream(uri)?.use(write) ?: return@withContext false
                true
            } catch (e: Exception) {
                false
            }
        }

    /** Write an image to a cache file other apps can read. */
    suspend fun imageShareUri(context: Context, bitmap: Bitmap, name: String = "share_image.png"): Uri? =
        shareUri(context, name) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    /** Write a video to a cache file other apps can read. */
    suspend fun videoShareUri(context: Context, bytes: ByteArray, name: String = "share_video.mp4"): Uri? =
        shareUri(context, name) { it.write(bytes) }

    /** A shareable uri for a gallery item, fetching it if needed; [index] keeps file names apart. */
    suspend fun itemShareUri(context: Context, item: GalleryItem, index: Int = 0): Uri? =
        if (item.isVideo) loadVideo(item)?.let { videoShareUri(context, it, "share_video_$index.mp4") }
        else loadImage(item)?.let { imageShareUri(context, it, "share_image_$index.png") }

    private suspend fun shareUri(context: Context, name: String, write: (OutputStream) -> Unit): Uri? =
        withContext(Dispatchers.IO) {
            try {
                val file = File(context.cacheDir, name)
                file.outputStream().use(write)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: Exception) {
                null
            }
        }

    /** Open the share sheet for [uris]. Works with an application context too. */
    fun share(context: Context, uris: List<Uri>, mimeType: String, title: String) {
        val intent = Intent().apply {
            if (uris.size == 1) {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, uris.single())
            } else {
                action = Intent.ACTION_SEND_MULTIPLE
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            type = mimeType
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
