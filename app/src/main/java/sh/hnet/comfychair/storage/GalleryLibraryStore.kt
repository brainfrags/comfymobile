package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.gallery.GalleryItem
import sh.hnet.comfychair.util.DebugLogger
import java.io.File

/**
 * Per-server gallery organisation that is not an album:
 * - trash: items deleted by the user (file id -> time moved to trash)
 * - purged: items deleted from the trash; hidden for good
 * - dates: dates set by dragging an item to another place (file id -> ms since epoch);
 *   they win over the server's date
 * - moves: files the app moved into another output folder (original file id -> new path
 *   relative to the output folder), so history items still find their file
 * - folders: album folders created in the app (shown even while empty)
 * - covers: album cover chosen by the user (album id -> file id)
 * - albumOrder: custom album order set by drag and drop (album ids, first = top)
 * - hiddenAlbums: albums hidden from the album list (album ids)
 * - albumSorts: item order inside an album, if it has its own (album id -> sort order name)
 * - pendingMoves: prompts generated while a folder album was selected (prompt id -> folder);
 *   their files are moved into the folder once they appear
 *
 * Items are identified by [GalleryItem.libraryId].
 *
 * Stored in filesDir/local_gallery/{serverId}/library.json
 */
data class GalleryLibrary(
    val trash: Map<String, Long> = emptyMap(),
    val purged: Set<String> = emptySet(),
    val dates: Map<String, Long> = emptyMap(),
    val moves: Map<String, String> = emptyMap(),
    val folders: Set<String> = emptySet(),
    val pendingMoves: Map<String, String> = emptyMap(),
    val covers: Map<String, String> = emptyMap(),
    val albumOrder: List<String> = emptyList(),
    val hiddenAlbums: Set<String> = emptySet(),
    val albumSorts: Map<String, String> = emptyMap()
)

object GalleryLibraryStore {
    private const val TAG = "GalleryLibrary"
    private const val FILE = "library.json"

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
            val dates = mutableMapOf<String, Long>()
            o.optJSONObject("dates")?.let { m -> m.keys().forEach { k -> dates[k] = m.optLong(k) } }
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
            val hiddenAlbums = o.optJSONArray("hiddenAlbums")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                ?: emptySet()
            val albumSorts = mutableMapOf<String, String>()
            o.optJSONObject("albumSorts")?.let { m -> m.keys().forEach { k -> albumSorts[k] = m.optString(k) } }
            GalleryLibrary(trash, purged, dates, moves, folders, pendingMoves, covers, albumOrder, hiddenAlbums, albumSorts)
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
                put("dates", JSONObject().apply { library.dates.forEach { (k, v) -> put(k, v) } })
                put("moves", JSONObject().apply { library.moves.forEach { (k, v) -> put(k, v) } })
                put("folders", JSONArray(library.folders.toList()))
                put("pendingMoves", JSONObject().apply { library.pendingMoves.forEach { (k, v) -> put(k, v) } })
                put("covers", JSONObject().apply { library.covers.forEach { (k, v) -> put(k, v) } })
                put("albumOrder", JSONArray(library.albumOrder))
                put("hiddenAlbums", JSONArray(library.hiddenAlbums.toList()))
                put("albumSorts", JSONObject().apply { library.albumSorts.forEach { (k, v) -> put(k, v) } })
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
