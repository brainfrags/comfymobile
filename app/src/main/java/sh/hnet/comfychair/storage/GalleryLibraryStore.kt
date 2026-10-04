package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.viewmodel.GalleryItem
import java.io.File

/**
 * Per-server gallery organisation that is not an album:
 * - trash: items deleted by the user (file id -> time moved to trash)
 * - purged: items deleted from the trash; hidden for good
 * - order: custom item order set by drag and drop (file ids, first = top)
 * - moves: files the app moved into another output folder (original file id -> new path
 *   relative to the output folder), so history items still find their file
 * - folders: album folders created in the app (shown even while empty)
 * - covers: album cover chosen by the user (album id -> file id)
 * - albumOrder: custom album order set by drag and drop (album ids, first = top)
 * - pendingMoves: prompts generated while a folder album was selected (prompt id -> folder);
 *   their files are moved into the folder once they appear
 *
 * Items are identified by [itemId]: a generated image by its prompt and filename (ComfyUI
 * reuses a filename once the old file was moved or deleted, so a path alone could point a
 * new image at an old one's state), a file found only in the output folder by its path.
 *
 * Stored in filesDir/local_gallery/{serverId}/library.json
 */
data class GalleryLibrary(
    val trash: Map<String, Long> = emptyMap(),
    val purged: Set<String> = emptySet(),
    val order: List<String> = emptyList(),
    val moves: Map<String, String> = emptyMap(),
    val folders: Set<String> = emptySet(),
    val pendingMoves: Map<String, String> = emptyMap(),
    val covers: Map<String, String> = emptyMap(),
    val albumOrder: List<String> = emptyList()
)

object GalleryLibraryStore {
    private const val TAG = "GalleryLibrary"
    private const val FILE = "library.json"

    /** Stable identity of the file behind a gallery item. */
    fun fileId(item: GalleryItem): String =
        "${item.type}/${item.subfolder.replace('\\', '/').trim('/')}/${item.filename}"

    /** Prefix of [itemId] for images from the history */
    const val HISTORY_PREFIX = "h:"

    /** Stable identity of an item for the library (trash, order, covers, moves, purged). */
    fun itemId(item: GalleryItem): String =
        if (item.promptId.startsWith("file:")) fileId(item) else "$HISTORY_PREFIX${item.promptId}_${item.filename}"

    /** File id of a path relative to the output folder ("sub/a.png"). */
    fun outputFileId(path: String): String =
        "output/${path.substringBeforeLast('/', "")}/${path.substringAfterLast('/')}"

    private fun file(context: Context, serverId: String): File =
        File(File(File(context.filesDir, "local_gallery"), serverId).apply { mkdirs() }, FILE)

    fun load(context: Context, serverId: String): GalleryLibrary {
        return try {
            val f = file(context, serverId)
            if (!f.exists()) return GalleryLibrary()
            val o = JSONObject(f.readText())
            val trash = mutableMapOf<String, Long>()
            o.optJSONObject("trash")?.let { t -> t.keys().forEach { k -> trash[k] = t.optLong(k) } }
            val purged = o.optJSONArray("purged")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                ?: emptySet()
            val order = o.optJSONArray("order")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                ?: emptyList()
            val moves = mutableMapOf<String, String>()
            o.optJSONObject("moves")?.let { m -> m.keys().forEach { k -> moves[k] = m.optString(k) } }
            val folders = o.optJSONArray("folders")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                ?: emptySet()
            val pendingMoves = mutableMapOf<String, String>()
            o.optJSONObject("pendingMoves")?.let { m -> m.keys().forEach { k -> pendingMoves[k] = m.optString(k) } }
            val covers = mutableMapOf<String, String>()
            o.optJSONObject("covers")?.let { m -> m.keys().forEach { k -> covers[k] = m.optString(k) } }
            val albumOrder = o.optJSONArray("albumOrder")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                ?: emptyList()
            GalleryLibrary(trash, purged, order, moves, folders, pendingMoves, covers, albumOrder)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to load library: ${e.message}")
            GalleryLibrary()
        }
    }

    fun save(context: Context, serverId: String, library: GalleryLibrary) {
        try {
            val o = JSONObject().apply {
                put("trash", JSONObject().apply { library.trash.forEach { (k, v) -> put(k, v) } })
                put("purged", JSONArray(library.purged.toList()))
                put("order", JSONArray(library.order))
                put("moves", JSONObject().apply { library.moves.forEach { (k, v) -> put(k, v) } })
                put("folders", JSONArray(library.folders.toList()))
                put("pendingMoves", JSONObject().apply { library.pendingMoves.forEach { (k, v) -> put(k, v) } })
                put("covers", JSONObject().apply { library.covers.forEach { (k, v) -> put(k, v) } })
                put("albumOrder", JSONArray(library.albumOrder))
            }
            val f = file(context, serverId)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to save library: ${e.message}")
        }
    }
}
