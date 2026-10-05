package sh.hnet.comfychair.gallery

import sh.hnet.comfychair.storage.GalleryLibrary

/**
 * Reads library entries saved by older versions, which identified history images by path
 * ("output/sub/name.png").
 */
object LegacyLibraryIds {

    private fun isLegacy(id: String) = id.startsWith("output/") || id.startsWith("temp/") || id.startsWith("input/")

    /**
     * Moves, trash, order and covers are moved to the image they meant (the oldest history
     * image with that path); deletions are kept only for files that are not in the history,
     * so a new image that reused a deleted file's name shows up.
     *
     * @param listedFiles Files in the output folder (paths relative to it), null if unknown
     * @param fileOps The server really moves files (ComfyMobile extension)
     * @return The converted library, or null if there is nothing to convert
     */
    fun migrate(
        lib: GalleryLibrary,
        historyItems: List<GalleryItem>,
        listedFiles: List<String>?,
        fileOps: Boolean
    ): GalleryLibrary? {
        val byPath = historyItems.groupBy { it.fileId }
        // Path ids are still right for files that are only in the output folder
        val legacy = (lib.trash.keys + lib.order + lib.covers.values + lib.purged).filter { isLegacy(it) && it in byPath } +
            lib.moves.keys.filter(::isLegacy)
        if (legacy.isEmpty()) return null
        val listed = listedFiles?.mapTo(HashSet()) { GalleryItem.outputFileId(it) } ?: emptySet()
        // The image an old path id meant: the oldest history image at that path
        fun owner(pathId: String): GalleryItem? = byPath[pathId]?.minByOrNull { if (it.timestamp > 0) it.timestamp else it.index.toLong() }
        fun convert(id: String): String = if (isLegacy(id)) owner(id)?.libraryId ?: id else id

        val moves = mutableMapOf<String, String>()
        for ((key, path) in lib.moves) {
            if (!isLegacy(key)) { moves[key] = path; continue }
            val candidates = byPath[key].orEmpty()
            // With real moves the old path is free; one image there that still exists on the PC
            // is a new image that reused the name, not the one that was moved
            if (candidates.size == 1 && fileOps && key in listed) continue
            owner(key)?.let { moves[it.libraryId] = path }
        }
        return lib.copy(
            moves = moves,
            trash = lib.trash.mapKeys { (k, _) -> convert(k) },
            order = lib.order.map(::convert),
            covers = lib.covers.mapValues { (_, v) -> convert(v) },
            purged = lib.purged.filterTo(HashSet()) { id -> !isLegacy(id) || id !in byPath }
        )
    }
}
