package sh.hnet.comfychair.storage

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.viewmodel.GalleryItem
import java.io.File
import kotlin.coroutines.resume

/**
 * Keeps a permanent on-device copy of every gallery item, so outputs survive
 * even after they are removed from the ComfyUI server's history/output folder.
 *
 * Layout: filesDir/local_gallery/{serverId}/index.json + original files.
 * Optionally also saves each item once to the phone's shared Photos
 * (Pictures/ComfyMobile, Movies/ComfyMobile).
 */
object LocalGalleryStore {
    private const val TAG = "LocalGallery"
    private const val ROOT_DIR = "local_gallery"
    private const val INDEX_FILE = "index.json"

    private class Entry(
        var item: GalleryItem,
        val seq: Long,
        var downloaded: Boolean,
        var savedToPhone: Boolean,
        /** When it was generated (ms since epoch), 0 if unknown */
        var time: Long = 0L
    ) {
        fun toItem() = item.copy(index = seq.toInt(), timestamp = time)
    }

    // serverId -> (keyString -> entry)
    private val indexes = mutableMapOf<String, LinkedHashMap<String, Entry>>()
    private val lock = Any()

    private fun serverDir(context: Context, serverId: String) =
        File(File(context.filesDir, ROOT_DIR), serverId).apply { mkdirs() }

    private fun safeName(keyString: String) = keyString.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** Local file for an item, or null if not stored on device. */
    fun localFile(context: Context, serverId: String?, key: MediaCacheKey): File? {
        if (serverId == null) return null
        val file = File(serverDir(context, serverId), safeName(key.keyString))
        return if (file.exists() && file.length() > 0) file else null
    }

    private fun index(context: Context, serverId: String): LinkedHashMap<String, Entry> =
        indexes.getOrPut(serverId) { load(context, serverId) }

    private fun load(context: Context, serverId: String): LinkedHashMap<String, Entry> {
        val map = LinkedHashMap<String, Entry>()
        try {
            val f = File(serverDir(context, serverId), INDEX_FILE)
            if (!f.exists()) return map
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val seq = o.getLong("seq")
                val item = GalleryItem(
                    promptId = o.getString("promptId"),
                    filename = o.getString("filename"),
                    subfolder = o.optString("subfolder", ""),
                    type = o.optString("type", "output"),
                    isVideo = o.optBoolean("isVideo", false),
                    index = seq.toInt()
                )
                val key = item.toCacheKey().keyString
                // Older indexes have no time: use the local copy's date as an approximation
                val time = o.optLong("time", 0L).takeIf { it > 0 }
                    ?: File(serverDir(context, serverId), safeName(key)).takeIf { it.exists() }?.lastModified()
                    ?: 0L
                map[key] = Entry(item, seq, o.optBoolean("downloaded"), o.optBoolean("savedToPhone"), time)
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to load index: ${e.message}")
        }
        return map
    }

    private fun persist(context: Context, serverId: String) {
        try {
            val arr = JSONArray()
            index(context, serverId).values.forEach { e ->
                arr.put(JSONObject().apply {
                    put("promptId", e.item.promptId)
                    put("filename", e.item.filename)
                    put("subfolder", e.item.subfolder)
                    put("type", e.item.type)
                    put("isVideo", e.item.isVideo)
                    put("seq", e.seq)
                    put("downloaded", e.downloaded)
                    put("savedToPhone", e.savedToPhone)
                    put("time", e.time)
                })
            }
            val f = File(serverDir(context, serverId), INDEX_FILE)
            val tmp = File(f.parentFile, "$INDEX_FILE.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(f)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to save index: ${e.message}")
        }
    }

    /**
     * Register the items currently on the server and return the merged list:
     * server items plus items only kept on the device, newest first.
     * [serverItems] must be ordered newest first.
     */
    fun mergeWithServer(context: Context, serverId: String, serverItems: List<GalleryItem>): List<GalleryItem> =
        synchronized(lock) {
            val idx = index(context, serverId)
            var nextSeq = (idx.values.maxOfOrNull { it.seq } ?: 0L) + 1
            // Oldest first so newer items get higher sequence numbers
            for (item in serverItems.asReversed()) {
                val k = item.toCacheKey().keyString
                val existing = idx[k]
                if (existing == null) {
                    val time = item.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
                    idx[k] = Entry(item, nextSeq++, downloaded = false, savedToPhone = false, time = time)
                } else {
                    // The server's time is exact; prefer it over an approximation
                    if (item.timestamp > 0) existing.time = item.timestamp
                    if (existing.item.subfolder != item.subfolder || existing.item.type != item.type) {
                        // File was moved to another folder on the server; keep the same entry
                        existing.item = item.copy(index = existing.item.index)
                    }
                }
            }
            persist(context, serverId)
            val onServer = serverItems.map { it.toCacheKey().keyString }.toHashSet()
            idx.values
                .filter { it.downloaded || it.item.toCacheKey().keyString in onServer }
                .sortedByDescending { it.seq }
                .map { it.toItem() }
        }

    /** Items available on device only (for offline mode). */
    fun storedItems(context: Context, serverId: String): List<GalleryItem> = synchronized(lock) {
        index(context, serverId).values
            .filter { it.downloaded }
            .sortedByDescending { it.seq }
            .map { it.toItem() }
    }

    /**
     * Download every registered item not yet kept on the device and, if enabled,
     * save it to the phone's Photos. Runs sequentially; safe to call repeatedly.
     */
    suspend fun syncDownloads(context: Context, serverId: String, client: ComfyUIClient) {
        val saveToPhone = AppSettings.isSaveToPhoneEnabled(context)
        val pending = synchronized(lock) {
            index(context, serverId).values
                .filter { !it.downloaded || (saveToPhone && !it.savedToPhone) }
                .sortedByDescending { it.seq }
        }
        if (pending.isEmpty()) return
        DebugLogger.i(TAG, "Keeping ${pending.size} items on device")

        for (entry in pending) {
            val item = entry.item
            val file = File(serverDir(context, serverId), safeName(item.toCacheKey().keyString))
            val bytes: ByteArray = (if (entry.downloaded && file.exists()) {
                try { file.readBytes() } catch (_: Exception) { null }
            } else {
                suspendCancellableCoroutine<ByteArray?> { cont ->
                    client.fetchRawBytes(item.filename, item.subfolder, item.type) { b, _ -> cont.resume(b) }
                }?.also { fetched ->
                    try {
                        file.writeBytes(fetched)
                    } catch (e: Exception) {
                        DebugLogger.e(TAG, "Failed to write local copy: ${e.message}")
                        return@also
                    }
                    synchronized(lock) {
                        entry.downloaded = true
                        persist(context, serverId)
                    }
                }
            }) ?: continue  // Not available now; retry on next sync

            if (entry.downloaded && saveToPhone && !entry.savedToPhone && saveToPhotos(context, item, bytes)) {
                synchronized(lock) {
                    entry.savedToPhone = true
                    persist(context, serverId)
                }
            }
        }
    }

    private fun mimeFor(filename: String, isVideo: Boolean): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            else -> if (isVideo) "video/mp4" else "image/png"
        }
    }

    private fun saveToPhotos(context: Context, item: GalleryItem, bytes: ByteArray): Boolean {
        val mime = mimeFor(item.filename, item.isVideo)
        val isVideoMime = mime.startsWith("video/")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                (if (isVideoMime) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/ComfyMobile"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (isVideoMime) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to save to Photos: ${e.message}")
            resolver.delete(uri, null, null)
            false
        }
    }

    // Generation records (the prompt/graph used), kept per promptId so the
    // generation info survives a ComfyUI restart even if the file has no metadata.

    private fun recordFile(context: Context, serverId: String, promptId: String): File =
        File(File(serverDir(context, serverId), "records").apply { mkdirs() }, "${safeName(promptId)}.json")

    /** Save the prompt graph of every history entry not saved yet. */
    fun saveGenerationRecords(context: Context, serverId: String, historyJson: JSONObject) {
        for (promptId in historyJson.keys()) {
            try {
                val file = recordFile(context, serverId, promptId)
                if (file.exists()) continue
                // History entry: { "prompt": [number, prompt_id, {graph}, extra_data, outputs], ... }
                val graph = historyJson.optJSONObject(promptId)
                    ?.optJSONArray("prompt")
                    ?.optJSONObject(2) ?: continue
                file.writeText(graph.toString())
            } catch (e: Exception) {
                DebugLogger.w(TAG, "Failed to save generation record: ${e.message}")
            }
        }
    }

    /** The saved prompt graph (API format JSON) for a prompt, or null. */
    fun loadGenerationRecord(context: Context, serverId: String?, promptId: String): String? {
        if (serverId == null) return null
        val file = recordFile(context, serverId, promptId)
        return if (file.exists()) try { file.readText() } catch (_: Exception) { null } else null
    }

    /**
     * Remove single items (by cache key string) from the device copy. The generation
     * record of a prompt is removed once none of its items are left.
     */
    fun removeKeys(context: Context, serverId: String?, keyStrings: Set<String>) {
        if (serverId == null || keyStrings.isEmpty()) return
        synchronized(lock) {
            val idx = index(context, serverId)
            val removed = keyStrings.mapNotNull { k -> idx.remove(k)?.also { File(serverDir(context, serverId), safeName(k)).delete() } }
            if (removed.isEmpty()) return
            val remainingPrompts = idx.values.mapTo(HashSet()) { it.item.promptId }
            removed.map { it.item.promptId }.distinct()
                .filter { it !in remainingPrompts }
                .forEach { recordFile(context, serverId, it).delete() }
            persist(context, serverId)
        }
    }

    /** Remove items from the device copy (the phone Photos copy is left alone). */
    fun remove(context: Context, serverId: String?, promptIds: Set<String>) {
        if (serverId == null) return
        synchronized(lock) {
            val idx = index(context, serverId)
            val keys = idx.filterValues { it.item.promptId in promptIds }.keys.toList()
            if (keys.isEmpty()) return
            keys.forEach { k ->
                File(serverDir(context, serverId), safeName(k)).delete()
                idx.remove(k)
            }
            promptIds.forEach { recordFile(context, serverId, it).delete() }
            persist(context, serverId)
        }
    }
}
