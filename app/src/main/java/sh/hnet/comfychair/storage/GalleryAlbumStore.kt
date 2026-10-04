package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger
import java.io.File

/**
 * A user-made album grouping gallery items.
 * [members] holds item keys ("${promptId}_${filename}").
 * [prompts] holds prompt IDs whose outputs all belong to the album (used for images
 * generated while the album was selected: their filenames are unknown at submit time).
 */
data class GalleryAlbum(
    val id: String,
    val name: String,
    val members: Set<String> = emptySet(),
    val prompts: Set<String> = emptySet()
) {
    fun contains(promptId: String, key: String): Boolean = key in members || promptId in prompts
}

/**
 * Persists gallery albums per server: filesDir/local_gallery/{serverId}/albums.json
 */
object GalleryAlbumStore {
    private const val TAG = "GalleryAlbums"

    private fun file(context: Context, serverId: String): File =
        File(File(File(context.filesDir, "local_gallery"), serverId).apply { mkdirs() }, "albums.json")

    fun load(context: Context, serverId: String): List<GalleryAlbum> {
        return try {
            val f = file(context, serverId)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val m = o.optJSONArray("members") ?: JSONArray()
                val p = o.optJSONArray("prompts") ?: JSONArray()
                GalleryAlbum(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    members = (0 until m.length()).map { m.getString(it) }.toSet(),
                    prompts = (0 until p.length()).map { p.getString(it) }.toSet()
                )
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to load albums: ${e.message}")
            emptyList()
        }
    }

    fun save(context: Context, serverId: String, albums: List<GalleryAlbum>) {
        try {
            val arr = JSONArray()
            albums.forEach { a ->
                arr.put(JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                    put("members", JSONArray(a.members.toList()))
                    put("prompts", JSONArray(a.prompts.toList()))
                })
            }
            val f = file(context, serverId)
            val tmp = File(f.parentFile, "albums.json.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(f)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to save albums: ${e.message}")
        }
    }
}
