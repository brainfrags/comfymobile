package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger
import java.io.File

/**
 * Dates of output files read from the server's Last-Modified header, for files the history
 * has no time for and the output listing gives no date (no ComfyMobile extension).
 * Path relative to the output folder -> ms since epoch (0 = the server gave no date).
 *
 * Stored in filesDir/local_gallery/{serverId}/file_times.json
 */
object FileTimeStore {
    private const val TAG = "FileTimeStore"
    private const val FILE = "file_times.json"

    private fun file(context: Context, serverId: String) =
        File(File(File(context.filesDir, "local_gallery"), serverId).apply { mkdirs() }, FILE)

    fun load(context: Context, serverId: String): MutableMap<String, Long> {
        val map = HashMap<String, Long>()
        try {
            val f = file(context, serverId)
            if (!f.exists()) return map
            val json = JSONObject(f.readText())
            for (path in json.keys()) map[path] = json.optLong(path, 0L)
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to load file times: ${e.message}")
        }
        return map
    }

    fun save(context: Context, serverId: String, times: Map<String, Long>) {
        try {
            val f = file(context, serverId)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(JSONObject(times).toString())
            tmp.renameTo(f)
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to save file times: ${e.message}")
        }
    }
}
