package sh.hnet.comfychair.gallery

import sh.hnet.comfychair.cache.MediaCacheKey

/**
 * A gallery item (image or video): an output of a prompt in the history, or a file found
 * only in the output folder ([isOutputFile]).
 * Bitmaps are stored in MediaCache, not in this data class.
 */
data class GalleryItem(
    val promptId: String,
    val filename: String,
    val subfolder: String,
    val type: String,
    val isVideo: Boolean,
    val index: Int = 0, // For sorting
    /** When it was generated (ms since epoch), 0 if unknown */
    val timestamp: Long = 0L
) {
    /** Create a cache key for this item */
    fun toCacheKey() = MediaCacheKey(promptId, filename)

    /** Unique key in lists and selections (same as the cache key string) */
    val key: String
        get() = "${promptId}_$filename"

    /** Path relative to its root folder ("a.png" or "sub/dir/a.png") */
    val path: String
        get() = if (subfolder.isEmpty()) filename else "$subfolder/$filename"

    /** Found in the output folder but not in the history (no generation info) */
    val isOutputFile: Boolean
        get() = promptId.startsWith(OUTPUT_FILE_PREFIX)

    /** Stable identity of the file behind the item. */
    val fileId: String
        get() = "$type/${subfolder.replace('\\', '/').trim('/')}/$filename"

    /**
     * Stable identity of the item for the library (trash, order, covers, moves, purged):
     * a generated image by its prompt and filename (ComfyUI reuses a filename once the old
     * file was moved or deleted, so a path alone could point a new image at an old one's
     * state), a file found only in the output folder by its path.
     */
    val libraryId: String
        get() = if (isOutputFile) fileId else "$HISTORY_PREFIX${promptId}_$filename"

    /** The file is in output subfolder [folder] or below it. */
    fun isInFolder(folder: String): Boolean =
        type == "output" && (subfolder == folder || subfolder.startsWith("$folder/"))

    /** The file is in the trash folder (output/_trash) */
    val isInTrashFolder: Boolean
        get() = isInFolder(TRASH_FOLDER)

    /** Where the file goes in the trash folder: its folder below output/_trash, so it can go back */
    val trashFolder: String
        get() = if (type == "output" && subfolder.isNotEmpty()) "$TRASH_FOLDER/$subfolder" else TRASH_FOLDER

    /** The folder a file in the trash folder came from ("" = the output folder itself) */
    val folderBeforeTrash: String
        get() = subfolder.removePrefix(TRASH_FOLDER).trimStart('/')

    /** The same item at another path relative to the output folder. */
    fun movedTo(path: String): GalleryItem = copy(
        type = "output",
        subfolder = path.substringBeforeLast('/', ""),
        filename = path.substringAfterLast('/')
    )

    companion object {
        /** Output subfolder trashed files are moved to (ComfyMobile extension) */
        const val TRASH_FOLDER = "_trash"

        /** [folder] is the trash folder or below it (not an album) */
        fun isTrashFolder(folder: String) = folder == TRASH_FOLDER || folder.startsWith("$TRASH_FOLDER/")

        private val VIDEO_EXTENSIONS = listOf(".mp4", ".m4v", ".webm", ".gif", ".avi", ".mov", ".mkv")

        /** Prompt id prefix for files found in the output folder but not in the history */
        private const val OUTPUT_FILE_PREFIX = "file:"

        /** Prefix of [libraryId] for images from the history */
        private const val HISTORY_PREFIX = "h:"

        fun isVideoFile(filename: String) = VIDEO_EXTENSIONS.any { filename.lowercase().endsWith(it) }

        /**
         * Gallery item for a file in the output folder ("a.png" or "sub/dir/b.png").
         * [timestamp] = the file's date (ms since epoch), 0 if unknown.
         */
        fun outputFile(path: String, timestamp: Long = 0L): GalleryItem {
            val filename = path.substringAfterLast('/')
            return GalleryItem(
                // Unique per file, and safe to use in cache file names (no '/')
                promptId = OUTPUT_FILE_PREFIX + path.replace('/', ':'),
                filename = filename,
                subfolder = path.substringBeforeLast('/', ""),
                type = "output",
                isVideo = isVideoFile(filename),
                timestamp = timestamp
            )
        }

        /** [fileId] of a path relative to the output folder ("sub/a.png"). */
        fun outputFileId(path: String): String =
            "output/${path.substringBeforeLast('/', "")}/${path.substringAfterLast('/')}"
    }
}
