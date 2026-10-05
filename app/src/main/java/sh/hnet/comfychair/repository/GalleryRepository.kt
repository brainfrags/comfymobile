package sh.hnet.comfychair.repository

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.gallery.GalleryItem
import sh.hnet.comfychair.gallery.HistoryParser
import sh.hnet.comfychair.gallery.LegacyLibraryIds
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.GalleryLibrary
import sh.hnet.comfychair.storage.GalleryLibraryStore
import sh.hnet.comfychair.storage.GalleryMetadataCache
import sh.hnet.comfychair.storage.LocalGalleryStore
import sh.hnet.comfychair.util.DebugLogger
import kotlin.coroutines.resume

/**
 * The gallery's items and their library state (trash, order, moved files), with background
 * loading and caching. This is a singleton that persists across activities.
 *
 * Albums are built on top of this by [AlbumRepository].
 */
class GalleryRepository private constructor() {

    companion object {
        private const val TAG = "GalleryRepo"
        private const val PERIODIC_REFRESH_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

        @Volatile
        private var instance: GalleryRepository? = null

        fun getInstance(): GalleryRepository {
            return instance ?: synchronized(this) {
                instance ?: GalleryRepository().also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Application context for accessing settings and cache
    private var applicationContext: Context? = null

    // Accessor for shared client from ConnectionManager
    private val comfyUIClient: ComfyUIClient?
        get() = ConnectionManager.clientOrNull

    /**
     * Initialize with application context.
     * Called when needed to access settings/cache.
     */
    fun initialize(context: Context) {
        if (applicationContext == null) {
            applicationContext = context.applicationContext
        }
    }

    // State

    private val stateLock = Any()

    // Every known item (history, kept on device, output folder) as loaded, before moves,
    // purges and the trash are applied
    private var rawItems: List<GalleryItem> = emptyList()

    // Every item at its current location, minus purged ones (derived from rawItems)
    private var allItems: List<GalleryItem> = emptyList()

    // Files found only in the output folder that were just moved; the next listing shows them
    // at their new place, so this is not kept (their old path may be reused by a new image)
    private val recentFileMoves = HashMap<String, String>()

    // Trash / purged / custom order / moves / album folders for the current server
    private val _library = MutableStateFlow(GalleryLibrary())
    val library: StateFlow<GalleryLibrary> = _library.asStateFlow()
    private var libraryServerId: String? = null

    // Items not in the trash
    private val _galleryItems = MutableStateFlow<List<GalleryItem>>(emptyList())
    val galleryItems: StateFlow<List<GalleryItem>> = _galleryItems.asStateFlow()

    // Items in the trash, most recently deleted first
    private val _trashedItems = MutableStateFlow<List<GalleryItem>>(emptyList())
    val trashedItems: StateFlow<List<GalleryItem>> = _trashedItems.asStateFlow()

    // Subfolders of the output folder reported by the server (ComfyMobile extension only)
    private val _serverFolders = MutableStateFlow<List<String>>(emptyList())
    val serverFolders: StateFlow<List<String>> = _serverFolders.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Manual refresh triggered by user (pull-to-refresh) - shows indicator
    private val _isManualRefreshing = MutableStateFlow(false)
    val isManualRefreshing: StateFlow<Boolean> = _isManualRefreshing.asStateFlow()

    // Any refresh in progress (manual or background)
    private val _isRefreshing = MutableStateFlow(false)

    private val isBusy: Boolean
        get() = _isLoading.value || _isRefreshing.value

    // Track if initial load has been done
    private var hasLoadedOnce = false

    /** Whether the server is connected (files can be moved or deleted). */
    val isConnected: Boolean
        get() = comfyUIClient != null

    /** True when the PC's ComfyMobile extension is older than this app needs. */
    val isFileOpsOutdated: Boolean
        get() = comfyUIClient?.let { it.hasFileOps && it.fileOpsVersion < 2 } == true

    /**
     * Check if gallery data is available (has been loaded at least once)
     */
    fun hasData(): Boolean = hasLoadedOnce && _galleryItems.value.isNotEmpty()

    // Loading

    private var periodicRefreshJob: Job? = null
    private var localSyncJob: Job? = null
    private var serverSyncJob: Job? = null

    /**
     * Start background preloading of gallery data.
     * Called after WebSocket connection is established, or when entering offline mode.
     */
    fun startBackgroundPreload() {
        val context = applicationContext
        val isOffline = context != null && AppSettings.isOfflineMode(context)

        // In online mode, require a client
        if (!isOffline && comfyUIClient == null) return
        if (isBusy) return

        scope.launch {
            // Small delay to let UI settle after connection
            delay(500)
            load(isRefresh = false)
        }

        // Start periodic refresh only in online mode
        if (!isOffline) startPeriodicRefresh()
    }

    private fun startPeriodicRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = scope.launch {
            while (true) {
                delay(PERIODIC_REFRESH_INTERVAL_MS)
                if (comfyUIClient != null && !isBusy) load(isRefresh = true)
            }
        }
    }

    /**
     * Manual refresh triggered by user (pull-to-refresh).
     * Clears the thumbnail cache and fetches fresh data from server.
     *
     * @param onComplete Callback with success status (true if refresh succeeded)
     */
    fun manualRefresh(onComplete: (Boolean) -> Unit = {}) {
        if (isBusy) {
            onComplete(false)
            return
        }
        scope.launch {
            _isManualRefreshing.value = true
            // Clear thumbnail cache to force re-fetch
            MediaCache.clearForRefresh()
            val success = load(isRefresh = true)
            _isManualRefreshing.value = false
            onComplete(success)
        }
    }

    /**
     * Background refresh (silent, no indicator).
     * Called after generation completes, periodically, or when returning from other screens.
     */
    fun refresh() {
        if (isBusy) {
            DebugLogger.d(TAG, "Refresh skipped - already in progress (loading=${_isLoading.value}, refreshing=${_isRefreshing.value})")
            return
        }
        if (comfyUIClient == null) {
            DebugLogger.w(TAG, "Refresh skipped - no client available")
            return
        }
        DebugLogger.d(TAG, "Starting background refresh")
        scope.launch { load(isRefresh = true) }
    }

    /**
     * Load gallery data: the history, items kept on the device, and every other file in the
     * output folder. In offline mode, from the device only.
     * @return true if load succeeded, false otherwise
     */
    private suspend fun load(isRefresh: Boolean): Boolean {
        val context = applicationContext
        val serverId = ConnectionManager.currentServerId

        if (context != null && AppSettings.isOfflineMode(context)) return loadFromOfflineCache()
        val client = comfyUIClient ?: return false

        val busy = if (isRefresh) _isRefreshing else _isLoading
        busy.value = true
        try {
            val historyJson = withContext(Dispatchers.IO) {
                suspendCancellableCoroutine<JSONObject?> { cont -> client.fetchAllHistory { cont.resume(it) } }
            } ?: return false

            if (context != null && serverId != null) ensureLibrary(context, serverId)
            val historyItems = HistoryParser.parse(historyJson)
            // Listing first: it tells which files really exist (needed to read older saved data)
            val listing = withContext(Dispatchers.IO) { client.listOutputFiles() }
            LegacyLibraryIds.migrate(_library.value, historyItems, listing?.files, listing?.fileOps == true)
                ?.let { migrated -> updateLibrary { migrated } }
            val library = _library.value
            // File dates (extension), for items the history reports no time for
            val fileTimes = listing?.times.orEmpty()
            fun GalleryItem.withFileTime(): GalleryItem =
                if (timestamp > 0 || type != "output") this else fileTimes[path]?.let { copy(timestamp = it) } ?: this

            // Point history items at files moved by the app; items deleted for good are not
            // registered again (and not downloaded again)
            var items = historyItems
                .map { applyMove(it, library.moves).withFileTime() }
                .filter { it.libraryId !in library.purged }

            // Merge with items kept on the device (survive server-side deletion). Items the
            // history forgot (ComfyUI restarted) stay as they were while their file is there,
            // instead of coming back from the listing below without date or generation info.
            val listedIds = listing?.files?.mapTo(HashSet()) { GalleryItem.outputFileId(it) }
            if (context != null && serverId != null) {
                items = withContext(Dispatchers.IO) {
                    LocalGalleryStore.saveGenerationRecords(context, serverId, historyJson)
                    LocalGalleryStore.mergeWithServer(context, serverId, items) { item ->
                        listedIds != null && applyMove(item, library.moves).fileId in listedIds
                    }
                }
            }

            // Everything else in the output folder (no generation info; that's fine)
            if (listing != null) {
                synchronized(stateLock) { recentFileMoves.clear() }
                val known = items.mapTo(HashSet()) { applyMove(it, library.moves).fileId }
                items = items + listing.files.mapNotNull { path ->
                    GalleryItem.outputFile(path, fileTimes[path] ?: 0L).takeIf { it.fileId !in known }
                }
                _serverFolders.value = listing.folders
                if (listing.fileOps) startServerSync(client, listing.files)
            }

            val previousCount = _galleryItems.value.size
            setAllItems(items)
            hasLoadedOnce = true
            DebugLogger.d(TAG, "Gallery refresh complete: ${items.size} items (was $previousCount)")

            // Cache gallery metadata for offline mode
            if (context != null && serverId != null) {
                withContext(Dispatchers.IO) { GalleryMetadataCache.saveMetadata(context, serverId, items) }
                startLocalSync(context, serverId, client)
            }
            AlbumRepository.processPendingMoves()
            return true
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Gallery refresh failed: ${e.message}")
            return false
        } finally {
            busy.value = false
        }
    }

    /**
     * Load gallery data from offline cache.
     * @return true if cache was loaded successfully, false otherwise
     */
    private fun loadFromOfflineCache(): Boolean {
        val context = applicationContext ?: return false
        val serverId = ConnectionManager.currentServerId ?: return false

        val cachedItems = LocalGalleryStore.storedItems(context, serverId).takeIf { it.isNotEmpty() }
            ?: GalleryMetadataCache.loadMetadata(context, serverId)
        if (cachedItems == null) {
            DebugLogger.w(TAG, "No offline cache available for gallery")
            return false
        }
        ensureLibrary(context, serverId)
        setAllItems(cachedItems)
        hasLoadedOnce = true
        DebugLogger.d(TAG, "Gallery loaded from offline cache: ${cachedItems.size} items")
        return true
    }

    /** Download new items to the device (and phone Photos) in the background. */
    private fun startLocalSync(context: Context, serverId: String, client: ComfyUIClient) {
        if (localSyncJob?.isActive == true) return
        localSyncJob = scope.launch(Dispatchers.IO) {
            try {
                LocalGalleryStore.syncDownloads(context, serverId, client)
            } catch (e: Exception) {
                DebugLogger.w(TAG, "Local sync failed: ${e.message}")
            }
        }
    }

    /**
     * Make the PC's output folder match the app (ComfyMobile extension only):
     * - files deleted in the app (or originals left behind by copying) are deleted on the PC
     * - albums made in the app become folders on the PC (see [AlbumRepository.syncToServer])
     */
    private fun startServerSync(client: ComfyUIClient, listedFiles: List<String>) {
        if (serverSyncJob?.isActive == true) return
        serverSyncJob = scope.launch {
            try {
                // Only files no history image uses (a new image may have reused a deleted name)
                val purged = _library.value.purged
                val historyPaths = synchronized(stateLock) {
                    allItems.filter { !it.isOutputFile }.mapTo(HashSet()) { it.fileId }
                }
                val leftovers = listedFiles.filter {
                    val id = GalleryItem.outputFileId(it)
                    id in purged && id !in historyPaths
                }
                if (leftovers.isNotEmpty()) {
                    DebugLogger.i(TAG, "Deleting ${leftovers.size} files deleted in the app")
                    val deleted = withContext(Dispatchers.IO) { client.deleteOutputFiles(leftovers) }
                    // Gone: a new file that gets the same name later is not hidden
                    val ids = deleted.mapTo(HashSet()) { GalleryItem.outputFileId(it) }
                    if (ids.isNotEmpty()) updateLibrary { lib -> lib.copy(purged = lib.purged - ids) }
                }
                AlbumRepository.syncToServer(client)
            } catch (e: Exception) {
                DebugLogger.w(TAG, "Server sync failed: ${e.message}")
            }
        }
    }

    // Library state

    private fun ensureLibrary(context: Context, serverId: String) {
        synchronized(stateLock) {
            if (libraryServerId == serverId) return
            libraryServerId = serverId
            _library.value = GalleryLibraryStore.load(context, serverId)
        }
    }

    /** Newest first; items without a date last (keeps each day in one run for the day headers). */
    private fun setAllItems(items: List<GalleryItem>) {
        synchronized(stateLock) {
            rawItems = items.sortedByDescending { it.timestamp }
            publish()
        }
    }

    /**
     * Apply moves, purges and the trash to the loaded items, and split them into gallery
     * and trash. Call with [stateLock] held.
     */
    private fun publish() {
        val library = _library.value
        val trash = library.trash
        // Moved items first come from the history (with generation info); the output folder
        // listing of the same file is dropped as a duplicate
        allItems = rawItems
            .map { applyMove(it, library.moves) }
            .distinctBy { it.fileId }
            .filter { it.libraryId !in library.purged }
        // In the trash: marked in the app, or in the trash folder on the PC
        val (trashed, visible) = allItems.partition { it.libraryId in trash || it.isInTrashFolder }
        _galleryItems.value = visible
        _trashedItems.value = trashed.sortedByDescending { trash[it.libraryId] ?: 0L }
    }

    /** Change the library of the current server; the items are updated and it is saved. */
    fun updateLibrary(transform: (GalleryLibrary) -> GalleryLibrary) {
        val context = applicationContext
        val serverId = ConnectionManager.currentServerId
        synchronized(stateLock) {
            if (context != null && serverId != null) ensureLibrary(context, serverId)
            _library.value = transform(_library.value)
            publish()
        }
        if (context != null && serverId != null) scheduleLibrarySave(context, serverId)
    }

    private var librarySaveJob: Job? = null

    /** Save the latest library shortly (drag reordering changes it many times in a row). */
    private fun scheduleLibrarySave(context: Context, serverId: String) {
        synchronized(stateLock) {
            librarySaveJob?.cancel()
            librarySaveJob = scope.launch(Dispatchers.IO) {
                delay(300)
                val library = synchronized(stateLock) {
                    if (libraryServerId != serverId) return@launch
                    _library.value
                }
                GalleryLibraryStore.save(context, serverId, library)
            }
        }
    }

    /** Change the known output subfolders (until the next listing). */
    fun updateServerFolders(transform: (List<String>) -> List<String>) {
        _serverFolders.value = transform(_serverFolders.value)
    }

    /** The item at the location the app moved its file to, if it was moved. */
    private fun applyMove(item: GalleryItem, moves: Map<String, String>): GalleryItem {
        val path = (if (item.isOutputFile) recentFileMoves[item.fileId] else moves[item.libraryId]) ?: return item
        return item.movedTo(path)
    }

    // Trash and order

    /**
     * Move items to the trash. They can be restored until the trash is emptied.
     *
     * They leave the gallery right away. With the ComfyMobile extension their files are then
     * moved into output/_trash (below it, the folder they were in, so they can go back);
     * otherwise, and for files that could not be moved, the trash is kept in the app only.
     * The move finishes even if the caller is cancelled (e.g. the viewer is closed).
     *
     * @return Number of items whose files could not be moved to the trash folder
     */
    suspend fun moveToTrash(items: Collection<GalleryItem>): Int {
        if (items.isEmpty()) return 0
        val now = System.currentTimeMillis()
        updateLibrary { lib -> lib.copy(trash = lib.trash + items.map { it.libraryId to now }) }

        val client = comfyUIClient
        if (client == null || !client.hasFileOps) return 0
        val toMove = items.filter { !it.isInTrashFolder }
        if (toMove.isEmpty()) return 0
        return withContext(NonCancellable) {
            var failed = 0
            // A file found only in the output folder is identified by its path, which changes
            val renamed = mutableMapOf<String, String>()
            for ((folder, group) in toMove.groupBy { it.trashFolder }) {
                val moved = moveFiles(client, group, folder)
                failed += group.size - moved.size
                moved.forEach { (item, path) ->
                    if (item.isOutputFile) renamed[item.libraryId] = GalleryItem.outputFileId(path)
                }
            }
            if (renamed.isNotEmpty()) {
                updateLibrary { lib -> lib.copy(trash = lib.trash - renamed.keys + renamed.values.map { it to now }) }
            }
            refresh()
            failed
        }
    }

    /**
     * Take items out of the trash; files in output/_trash go back to the folder they came from.
     * @return Number of items whose files could not be moved back (they stay in the trash)
     */
    suspend fun restoreFromTrash(items: Collection<GalleryItem>): Int {
        if (items.isEmpty()) return 0
        val ids = items.mapTo(HashSet()) { it.libraryId }
        updateLibrary { lib -> lib.copy(trash = lib.trash - ids) }

        val inTrashFolder = items.filter { it.isInTrashFolder }
        if (inTrashFolder.isEmpty()) return 0
        val client = comfyUIClient
        if (client == null || !client.hasFileOps) return inTrashFolder.size
        return withContext(NonCancellable) {
            var failed = 0
            for ((folder, group) in inTrashFolder.groupBy { it.folderBeforeTrash }) {
                failed += group.size - moveFiles(client, group, folder).size
            }
            refresh()
            failed
        }
    }

    /** Save a custom item order (library ids, top first). */
    fun setOrder(order: List<String>) {
        updateLibrary { lib -> lib.copy(order = order) }
    }

    /**
     * Delete items for good: they are hidden permanently, removed from the device copy,
     * deleted on the PC (ComfyMobile extension), and a history entry is removed from the
     * server once none of its images are left.
     */
    suspend fun deletePermanently(items: Collection<GalleryItem>) {
        if (items.isEmpty()) return
        val ids = items.mapTo(HashSet()) { it.libraryId }
        updateLibrary { lib ->
            lib.copy(trash = lib.trash - ids, purged = lib.purged + ids, order = lib.order - ids)
        }
        val remainingPrompts = synchronized(stateLock) { allItems.mapTo(HashSet()) { it.promptId } }

        val context = applicationContext
        val serverId = ConnectionManager.currentServerId
        withContext(Dispatchers.IO) {
            if (context != null && serverId != null) {
                LocalGalleryStore.removeKeys(context, serverId, items.mapTo(HashSet()) { it.key })
            }
            items.forEach { MediaCache.evict(it.toCacheKey()) }
        }

        val client = comfyUIClient
        val outputs = items.filter { it.type == "output" }
        val deleted = if (client != null && client.hasFileOps && outputs.isNotEmpty()) {
            withContext(Dispatchers.IO) { client.deleteOutputFiles(outputs.map { it.path }) }
        } else emptySet()
        // Files still on the PC (no extension, or deleting failed) are hidden by their path too,
        // or the output folder listing would bring them back once the history forgets them.
        // The next sync with the extension deletes them.
        val left = outputs.filter { it.path !in deleted }.mapTo(HashSet()) { it.fileId }
        if (left.isNotEmpty()) updateLibrary { lib -> lib.copy(purged = lib.purged + left) }
        client ?: return

        // Remove history entries that have no images left (best effort; hidden either way)
        items.filter { !it.isOutputFile }.map { it.promptId }.distinct()
            .filter { it !in remainingPrompts }
            .forEach { promptId ->
                withContext(Dispatchers.IO) {
                    suspendCancellableCoroutine<Boolean> { cont -> client.deleteHistoryItem(promptId) { cont.resume(it) } }
                }
            }
    }

    // Moving files

    /** Every item (also trashed ones) whose file is in output subfolder [folder] or below it. */
    private fun itemsInFolder(folder: String): List<GalleryItem> = synchronized(stateLock) {
        allItems.filter { it.isInFolder(folder) }
    }

    /**
     * Move items into an output subfolder ("" = the output root).
     *
     * With the ComfyMobile extension the files are really moved on the server. Without it,
     * ComfyUI can only add files, so each file is copied there with the upload API and the
     * original is hidden in the app (it stays on the server's disk).
     *
     * @return Number of items that could not be moved
     */
    suspend fun moveToFolder(items: Collection<GalleryItem>, folder: String): Int {
        val client = comfyUIClient ?: return items.size
        val toMove = items.filter { it.type != "output" || it.subfolder != folder }
        if (toMove.isEmpty()) return 0
        val moved = moveFiles(client, toMove, folder)
        // Thumbnails and copies are keyed by name; refresh so the new location is listed
        refresh()
        return toMove.size - moved.size
    }

    /**
     * Move (or, without the extension, copy) files into [folder] and remember where they went.
     * @return The new path (relative to the output folder) of each item that was moved
     */
    private suspend fun moveFiles(client: ComfyUIClient, toMove: List<GalleryItem>, folder: String): Map<GalleryItem, String> {
        val newPaths: Map<GalleryItem, String> = withContext(Dispatchers.IO) {
            if (client.hasFileOps) {
                val byKey = toMove.associateBy { "${it.type}/${it.path}" }
                val moved = client.moveOutputFiles(byKey.values.map { it.type to it.path }, folder) ?: emptyMap()
                moved.mapNotNull { (key, newPath) -> byKey[key]?.let { it to newPath } }.toMap()
            } else {
                toMove.mapNotNull { item ->
                    val bytes = suspendCancellableCoroutine<ByteArray?> { cont ->
                        client.fetchRawBytes(item.filename, item.subfolder, item.type) { b, _ -> cont.resume(b) }
                    } ?: return@mapNotNull null
                    client.uploadToOutput(bytes, item.filename, folder)?.let { item to it }
                }.toMap()
            }
        }

        recordMoves(newPaths, copied = !client.hasFileOps)
        return newPaths
    }

    /** Result of [moveFolderFiles] */
    class FolderMove(
        /** The folder was moved on the PC as a whole (nothing left behind) */
        val asWhole: Boolean,
        /** Items that were in the folder */
        val items: Int,
        /** Items that could not be moved */
        val failed: Int
    )

    /**
     * Move everything in output subfolder [from] into [to] ("" = the output folder itself).
     * With the ComfyMobile extension 2+ the folder is moved on the PC with everything in it;
     * otherwise its images are moved one by one.
     */
    suspend fun moveFolderFiles(from: String, to: String): FolderMove {
        val client = comfyUIClient
        val moved = if (client != null && client.hasFileOps && client.fileOpsVersion >= 2) {
            withContext(Dispatchers.IO) { client.moveOutputFolder(from, to) }
        } else null
        val items = itemsInFolder(from)
        if (moved == null) return FolderMove(asWhole = false, items = items.size, failed = moveToFolder(items, to))

        val byPath = items.groupBy { it.fileId }
        recordMoves(
            moved.entries.flatMap { (old, new) -> byPath[GalleryItem.outputFileId(old)].orEmpty().map { it to new } }.toMap(),
            copied = false
        )
        return FolderMove(asWhole = true, items = items.size, failed = 0)
    }

    /**
     * Remember where files went: [newPaths] maps items to their new path (relative to the
     * output folder). [copied]: the original is still on the PC (hidden in the app).
     */
    private fun recordMoves(newPaths: Map<GalleryItem, String>, copied: Boolean) {
        if (newPaths.isEmpty()) return
        synchronized(stateLock) {
            for ((item, newPath) in newPaths) {
                if (item.isOutputFile) recentFileMoves[item.fileId] = newPath
            }
        }
        updateLibrary { lib ->
            val moves = lib.moves.toMutableMap()
            val purged = lib.purged.toMutableSet()
            for ((item, newPath) in newPaths) {
                // Generated images: keyed by prompt + filename, so a new image that gets the
                // old filename is not sent here too
                if (!item.isOutputFile) moves[item.libraryId] = newPath
                // A copied original is still listed in the output folder; hide that file
                if (copied) purged.add(item.fileId)
            }
            lib.copy(moves = moves, purged = purged)
        }
    }

    // Duplicate cleanup

    /**
     * Files in the output folder itself that also exist (same content) in an album folder.
     * [ComfyUIClient.DuplicatesResult.Found] holds pairs of (root path, album copy that is kept).
     */
    suspend fun findRootDuplicates(): Pair<ComfyUIClient.DuplicatesResult, List<Pair<String, String>>> {
        val client = comfyUIClient ?: return ComfyUIClient.DuplicatesResult.Failed to emptyList()
        val result = withContext(Dispatchers.IO) { client.findOutputDuplicates() }
        val groups = (result as? ComfyUIClient.DuplicatesResult.Found)?.groups ?: return result to emptyList()
        return result to groups.flatMap { group ->
            // Never keep only the copy in the trash
            val keep = group.firstOrNull { '/' in it && !GalleryItem.isTrashFolder(it.substringBeforeLast('/')) }
                ?: return@flatMap emptyList()
            group.filter { '/' !in it }.map { it to keep }
        }
    }

    /**
     * Delete root copies found by [findRootDuplicates]. An image's generation info stays
     * with the album copy that is kept.
     * @return Number of files deleted
     */
    suspend fun deleteRootDuplicates(duplicates: List<Pair<String, String>>): Int {
        val client = comfyUIClient ?: return 0
        if (duplicates.isEmpty()) return 0
        // History images whose file is a root copy now point at the kept copy
        val rootToKeep = duplicates.associate { (root, keep) -> GalleryItem.outputFileId(root) to keep }
        val atRoot = synchronized(stateLock) {
            allItems.filter { !it.isOutputFile && it.fileId in rootToKeep }
        }
        if (atRoot.isNotEmpty()) {
            updateLibrary { lib ->
                lib.copy(moves = lib.moves + atRoot.associate { it.libraryId to rootToKeep.getValue(it.fileId) })
            }
        }
        val deleted = withContext(Dispatchers.IO) { client.deleteOutputFiles(duplicates.map { it.first }) }
        // Gone from disk: drop the root listing right away (a refresh confirms it)
        val deletedIds = deleted.mapTo(HashSet()) { GalleryItem.outputFileId(it) }
        synchronized(stateLock) {
            rawItems = rawItems.filterNot { it.isOutputFile && it.fileId in deletedIds }
            publish()
        }
        refresh()
        return deleted.size
    }

    /**
     * Reset repository state (for logout/disconnect).
     * Called by ConnectionManager when disconnecting.
     * Note: Uses MediaCache.reset() to preserve disk cache for offline mode.
     * User can still clear disk cache manually via Settings → Clear Cache.
     */
    fun reset() {
        DebugLogger.i(TAG, "Resetting repository")
        _serverFolders.value = emptyList()
        synchronized(stateLock) {
            rawItems = emptyList()
            allItems = emptyList()
            libraryServerId = null
            _library.value = GalleryLibrary()
            publish()
        }
        hasLoadedOnce = false
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
        localSyncJob?.cancel()
        localSyncJob = null
        // Clear media cache (preserves disk cache for offline mode)
        MediaCache.reset()
    }
}
