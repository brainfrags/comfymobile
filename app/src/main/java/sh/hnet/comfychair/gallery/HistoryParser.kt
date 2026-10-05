package sh.hnet.comfychair.gallery

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads gallery items out of ComfyUI's /history response.
 * Does NOT fetch bitmaps - those are loaded lazily via MediaCache.
 */
object HistoryParser {

    /** Every output of every prompt, newest first. */
    fun parse(historyJson: JSONObject): List<GalleryItem> {
        val items = mutableListOf<GalleryItem>()
        for (promptId in historyJson.keys()) {
            val promptHistory = historyJson.optJSONObject(promptId) ?: continue
            val outputs = promptHistory.optJSONObject("outputs") ?: continue
            val timestamp = timestamp(promptHistory)

            for (nodeId in outputs.keys()) {
                val nodeOutput = outputs.optJSONObject(nodeId) ?: continue
                fun add(files: JSONArray?, isVideo: (String) -> Boolean) {
                    if (files == null) return
                    for (i in 0 until files.length()) {
                        val info = files.optJSONObject(i) ?: continue
                        val filename = info.optString("filename", "")
                        if (filename.isEmpty()) continue
                        items.add(GalleryItem(
                            promptId = promptId,
                            filename = filename,
                            subfolder = info.optString("subfolder", ""),
                            type = info.optString("type", "output"),
                            isVideo = isVideo(filename),
                            index = items.size,
                            timestamp = timestamp
                        ))
                    }
                }
                add(nodeOutput.optJSONArray("videos") ?: nodeOutput.optJSONArray("gifs")) { true }
                // "images" can hold videos too
                add(nodeOutput.optJSONArray("images"), GalleryItem::isVideoFile)
            }
        }
        // Newest first
        return items.sortedByDescending { it.index }
    }

    /**
     * When a prompt ran, from its status messages ("execution_start"/"execution_success"
     * carry a "timestamp" in ms). Returns 0 if the server does not report it.
     */
    private fun timestamp(promptHistory: JSONObject): Long {
        val messages = promptHistory.optJSONObject("status")?.optJSONArray("messages") ?: return 0L
        var result = 0L
        for (i in 0 until messages.length()) {
            val message = messages.optJSONArray(i) ?: continue
            val ts = message.optJSONObject(1)?.optLong("timestamp", 0L) ?: 0L
            if (ts > result) result = ts
        }
        return result
    }
}
