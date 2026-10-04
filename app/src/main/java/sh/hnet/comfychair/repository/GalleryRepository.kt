package sh.hnet.comfychair.repository

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.GalleryLibrary
import sh.hnet.comfychair.storage.GalleryLibraryStore
import sh.hnet.comfychair.storage.GalleryMetadataCache
import sh.hnet.comfychair.storage.LocalGalleryStore
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.viewmodel.GalleryItem

/**
 * Repository for managing gallery data with background preloading and caching.
 * This is a singleton that persists across activities.
 */
class GalleryRepository private constructor() {

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

    // Every known item (history, kept on device, output folder) as loaded, before moves,
    // purges and the trash are applied
    private var rawItems: List<GalleryItem> = emptyList()

    // Every item at its current location, minus purged ones (derived from rawItems)
    private var allItems: List<GalleryItem> = emptyList()

    // Subfolders of the output folder reported by the server (ComfyMobile extension only)
    private val _serverFolders = MutableStateFlow<List<String>>(emptyList())
    val serverFolders: StateFlow<List<String>> = _serverFolders.asStateFlow()

    /** Whether the server can move files (ComfyMobile extension installed). */
    val canMoveFiles: Boolean
        get() = comfyUIClient?.hasFileOps == true

    // Trash / purged / custom order for the current server
    private val _library = MutableStateFlow(GalleryLibrary())
    val library: StateFlow<GalleryLibrary> = _library.asStateFlow()
    private var libraryServerId: String? = null
    private val stateLock = Any()

    // Gallery data state: items not in the trash
    private val _galleryItems = MutableStateFlow<List<GalleryItem>>(emptyList())
    val galleryItems: StateFlow<List<GalleryItem>> = _galleryItems.asStateFlow()

    // Items in the trash, most recently deleted first
    private val _trashedItems = MutableStateFlow<List<GalleryItem>>(emptyList())
    val trashedItems: StateFlow<List<GalleryItem>> = _trashedItems.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Manual refresh triggered by user (pull-to-refresh) - shows indicator
    private val _isManualRefreshing = MutableStateFlow(false)
    val isManualRefreshing: StateFlow<Boolean> = _isManualRefreshing.asStateFlow()

    // Any refresh in progress (manual or background)
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _lastRefreshTime = MutableStateFlow(0L)
    val lastRefreshTime: StateFlow<Long> = _lastRefreshTime.asStateFlow()

    // Periodic refresh job
    private var periodicRefreshJob: Job? = null

    // Track if initial load has been done
    private var hasLoadedOnce = false

    companion object {
        private const val TAG = "GalleryRepo"

        @Volatile
        private var instance: GalleryRepository? = null

        private val VIDEO_EXTENSIONS = listOf(".mp4", ".m4v", ".webm", ".gif", ".avi", ".mov", ".mkv")
        private const val PERIODIC_REFRESH_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

        /** Prompt id prefix for files found in the output folder but not in the history */
        private const val OUTPUT_FILE_PREFIX = "file:"

        fun isOutputFilePromptId(promptId: String) = promptId.startsWith(OUTPUT_FILE_PREFIX)

        /** Gallery item for a file in the output folder ("a.png" or "sub/dir/b.png"). */
        fun outputFileItem(path: String): GalleryItem {
            val subfolder = path.substringBeforeLast('/', "")
            val filename = path.substringAfterLast('/')
            return GalleryItem(
                // Unique per file, and safe to use in cache file names (no '/')
                promptId = OUTPUT_FILE_PREFIX + path.replace('/', ':'),
                filename = filename,
                subfolder = subfolder,
                type = "output",
                isVideo = VIDEO_EXTENSIONS.any { filename.lowercase().endsWith(it) }
            )
        }

        fun getInstance(): GalleryRepository {
            return instance ?: synchronized(this) {
                instance ?: GalleryRepository().also { instance = it }
            }
        }
    }

    /**
     * Start background preloading of gallery data.
     * Called after WebSocket connection is established, or when entering offline mode.
     */
    fun startBackgroundPreload() {
        val context = applicationContext
        val isOffline = context != null && AppSettings.isOfflineMode(context)

        // In online mode, require a client
        if (!isOffline && comfyUIClient == null) {
            return
        }

        if (_isLoading.value || _isRefreshing.value) {
            return
        }

        scope.launch {
            // Small delay to let UI settle after connection
            delay(500)
            loadGalleryInternal(isRefresh = false)
        }

        // Start periodic refresh only in online mode
        if (!isOffline) {
            startPeriodicRefresh()
        }
    }

    /**
     * Start periodic background refresh
     */
    private fun startPeriodicRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = scope.launch {
            while (true) {
                delay(PERIODIC_REFRESH_INTERVAL_MS)
                if (comfyUIClient != null && !_isLoading.value && !_isRefreshing.value) {
                    loadGalleryInternal(isRefresh = true)
                }
            }
        }
    }

    /**
     * Stop periodic refresh (call when disconnecting)
     */
    fun stopPeriodicRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
    }

    /**
     * Manual refresh triggered by user (pull-to-refresh).
     * Clears the thumbnail cache and fetches fresh data from server.
     *
     * @param onComplete Callback with success status (true if refresh succeeded)
     */
    fun manualRefresh(onComplete: (Boolean) -> Unit = {}) {
        if (_isLoading.value || _isRefreshing.value) {
            onComplete(false)
            return
        }

        scope.launch {
            _isManualRefreshing.value = true

            // Clear thumbnail cache to force re-fetch
            MediaCache.clearForRefresh()

            val success = loadGalleryInternal(isRefresh = true)

            _isManualRefreshing.value = false
            onComplete(success)
        }
    }

    /**
     * Background refresh (silent, no indicator).
     * Called after generation completes, periodically, or when returning from other screens.
     */
    fun refresh() {
        if (_isLoading.value || _isRefreshing.value) {
            DebugLogger.d(TAG, "Refresh skipped - already in progress (loading=${_isLoading.value}, refreshing=${_isRefreshing.value})")
            return
        }

        if (comfyUIClient == null) {
            DebugLogger.w(TAG, "Refresh skipped - no client available")
            return
        }

        DebugLogger.d(TAG, "Starting background refresh")
        scope.launch {
            loadGalleryInternal(isRefresh = true)
        }
    }

    /**
     * Load gallery data in background.
     * @return true if load succeeded, false otherwise
     */
    private suspend fun loadGalleryInternal(isRefresh: Boolean): Boolean {
        val context = applicationContext
        val serverId = ConnectionManager.currentServerId

        // Check if in offline mode - load from cache instead
        if (context != null && AppSettings.isOfflineMode(context)) {
            return loadFromOfflineCache()
        }

        val client = comfyUIClient ?: run {
            return false
        }

        if (isRefresh) {
            _isRefreshing.value = true
        } else {
            _isLoading.value = true
        }

        try {
            val historyJson = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.fetchAllHistory { history ->
                        continuation.resumeWith(Result.success(history))
                    }
                }
            }

            if (historyJson == null) {
                _isLoading.value = false
                _isRefreshing.value = false
                return false
            }

            if (context != null && serverId != null) ensureLibrary(context, serverId)
            val library = _library.value

            // Point history items at files moved by the app; items deleted for good are not
            // registered again (and not downloaded again)
            var items = parseHistoryToGalleryItems(historyJson)
                .map { applyMove(it, library.moves) }
                .filter { GalleryLibraryStore.fileId(it) !in library.purged }

            // Merge with items kept on the device (survive server-side deletion)
            if (context != null && serverId != null) {
                items = withContext(Dispatchers.IO) {
                    LocalGalleryStore.saveGenerationRecords(context, serverId, historyJson)
                    LocalGalleryStore.mergeWithServer(context, serverId, items)
                }
            }

            // Everything else in the output folder (no generation info; that's fine)
            val listing = withContext(Dispatchers.IO) { client.listOutputFiles() }
            if (listing != null) {
                val known = items.mapTo(HashSet()) { GalleryLibraryStore.fileId(applyMove(it, library.moves)) }
                items = items + listing.files.mapNotNull { path ->
                    outputFileItem(path).takeIf { GalleryLibraryStore.fileId(it) !in known }
                }
                _serverFolders.value = listing.folders
            }

            val previousCount = _galleryItems.value.size
            setAllItems(items)
            _lastRefreshTime.value = System.currentTimeMillis()
            hasLoadedOnce = true
            DebugLogger.d(TAG, "Gallery refresh complete: ${items.size} items (was $previousCount)")

            // Cache gallery metadata for offline mode
            if (context != null && serverId != null) {
                withContext(Dispatchers.IO) {
                    GalleryMetadataCache.saveMetadata(context, serverId, items)
                }
                startLocalSync(context, serverId, client)
            }
            processPendingMoves()

            return true
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Gallery refresh failed: ${e.message}")
            return false
        } finally {
            _isLoading.value = false
            _isRefreshing.value = false
        }
    }

    // Only one download sync at a time
    private var localSyncJob: Job? = null

    /**
     * Download new items to the device (and phone Photos) in the background.
     */
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
     * Load gallery data from offline cache.
     * @return true if cache was loaded successfully, false otherwise
     */
    private fun loadFromOfflineCache(): Boolean {
        val context = applicationContext ?: return false
        val serverId = ConnectionManager.currentServerId ?: return false

        val cachedItems = LocalGalleryStore.storedItems(context, serverId).takeIf { it.isNotEmpty() }
            ?: GalleryMetadataCache.loadMetadata(context, serverId)
        if (cachedItems != null) {
            ensureLibrary(context, serverId)
            setAllItems(cachedItems)
            _lastRefreshTime.value = GalleryMetadataCache.getCacheTimestamp(context, serverId)
            hasLoadedOnce = true
            DebugLogger.d(TAG, "Gallery loaded from offline cache: ${cachedItems.size} items")
            return true
        }

        DebugLogger.w(TAG, "No offline cache available for gallery")
        return false
    }

    /**
     * Check if gallery data is available (has been loaded at least once)
     */
    fun hasData(): Boolean = hasLoadedOnce && _galleryItems.value.isNotEmpty()

    // Trash

    private fun ensureLibrary(context: Context, serverId: String) {
        synchronized(stateLock) {
            if (libraryServerId == serverId) return
            libraryServerId = serverId
            _library.value = GalleryLibraryStore.load(context, serverId)
        }
    }

    private fun setAllItems(items: List<GalleryItem>) {
        synchronized(stateLock) {
            rawItems = items
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
            .distinctBy { GalleryLibraryStore.fileId(it) }
            .filter { GalleryLibraryStore.fileId(it) !in library.purged }
        val (trashed, visible) = allItems.partition { GalleryLibraryStore.fileId(it) in trash }
        _galleryItems.value = visible
        _trashedItems.value = trashed.sortedByDescending { trash[GalleryLibraryStore.fileId(it)] ?: 0L }
    }

    private fun updateLibrary(transform: (GalleryLibrary) -> GalleryLibrary) {
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

    /** Move items to the trash. They can be restored until the trash is emptied. */
    fun moveToTrash(items: Collection<GalleryItem>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        updateLibrary { lib -> lib.copy(trash = lib.trash + items.map { GalleryLibraryStore.fileId(it) to now }) }
    }

    fun restoreFromTrash(items: Collection<GalleryItem>) {
        if (items.isEmpty()) return
        val ids = items.map { GalleryLibraryStore.fileId(it) }.toSet()
        updateLibrary { lib -> lib.copy(trash = lib.trash - ids) }
    }

    /** Save a custom item order (file ids, top first). */
    fun setOrder(order: List<String>) {
        updateLibrary { lib -> lib.copy(order = order) }
    }

    /** The item at the location the app moved its file to, if it was moved. */
    private fun applyMove(item: GalleryItem, moves: Map<String, String>): GalleryItem {
        val path = moves[GalleryLibraryStore.fileId(item)] ?: return item
        return item.copy(
            type = "output",
            subfolder = path.substringBeforeLast('/', ""),
            filename = path.substringAfterLast('/')
        )
    }

    // Album folders

    /** Create an album folder in the output folder (shown even while empty). */
    suspend fun createFolder(folder: String) {
        updateLibrary { lib -> lib.copy(folders = lib.folders + folder) }
        val client = comfyUIClient ?: return
        if (client.hasFileOps) withContext(Dispatchers.IO) { client.createOutputFolder(folder) }
    }

    /**
     * Forget an album folder; it is removed from the server too once it is empty
     * (ComfyMobile extension only).
     */
    suspend fun removeFolder(folder: String) {
        updateLibrary { lib -> lib.copy(folders = lib.folders - folder) }
        _serverFolders.value = _serverFolders.value - folder
        val client = comfyUIClient ?: return
        if (client.hasFileOps) withContext(Dispatchers.IO) { client.removeOutputFolder(folder) }
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

        // fileId of each item -> its new path relative to the output folder
        val newPaths: Map<String, String> = withContext(Dispatchers.IO) {
            if (client.hasFileOps) {
                val byKey = toMove.associateBy { "${it.type}/${pathOf(it)}" }
                val moved = client.moveOutputFiles(byKey.keys.map { k -> byKey.getValue(k).let { it.type to pathOf(it) } }, folder)
                    ?: emptyMap()
                moved.mapNotNull { (key, newPath) -> byKey[key]?.let { GalleryLibraryStore.fileId(it) to newPath } }.toMap()
            } else {
                toMove.mapNotNull { item ->
                    val bytes = kotlin.coroutines.suspendCoroutine<ByteArray?> { cont ->
                        client.fetchRawBytes(item.filename, item.subfolder, item.type) { b, _ -> cont.resumeWith(Result.success(b)) }
                    } ?: return@mapNotNull null
                    client.uploadToOutput(bytes, item.filename, folder)?.let { GalleryLibraryStore.fileId(item) to it }
                }.toMap()
            }
        }

        val copied = !client.hasFileOps
        updateLibrary { lib ->
            val moves = lib.moves.toMutableMap()
            val order = lib.order.toMutableList()
            val purged = lib.purged.toMutableSet()
            val covers = lib.covers.toMutableMap()
            for ((currentId, newPath) in newPaths) {
                val newId = GalleryLibraryStore.outputFileId(newPath)
                // Keep the move keyed by the file's original id (what the history reports)
                val originalId = moves.entries.firstOrNull { GalleryLibraryStore.outputFileId(it.value) == currentId }?.key
                    ?: currentId
                moves[originalId] = newPath
                // A copied original is still listed in the output folder; hide it
                if (copied) purged.add(currentId)
                purged.remove(newId)
                val i = order.indexOf(currentId)
                if (i >= 0) order[i] = newId
                // A cover follows its file
                covers.entries.filter { it.value == currentId }.forEach { it.setValue(newId) }
            }
            lib.copy(moves = moves, order = order, purged = purged, covers = covers)
        }
        // Thumbnails and copies are keyed by name; refresh so the new location is listed
        refresh()
        return toMove.size - newPaths.size
    }

    /** Move what [promptId] generates into [folder] once it shows up in the gallery. */
    fun queuePromptMove(promptId: String, folder: String) {
        updateLibrary { lib -> lib.copy(pendingMoves = lib.pendingMoves + (promptId to folder)) }
    }

    /**
     * Files in the output folder itself that also exist (same content) in an album folder.
     * Pairs of (root path, album copy that is kept). Null when the server can't compare
     * files (no ComfyMobile extension).
     */
    suspend fun findRootDuplicates(): List<Pair<String, String>>? {
        val client = comfyUIClient ?: return null
        val groups = withContext(Dispatchers.IO) { client.findOutputDuplicates() } ?: return null
        return groups.flatMap { group ->
            val inAlbums = group.filter { '/' in it }
            val inRoot = group.filter { '/' !in it }
            val keep = inAlbums.firstOrNull() ?: return@flatMap emptyList()
            inRoot.map { it to keep }
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
        // History items that point at a root copy now point at the kept copy
        val rootToKeep = duplicates.associate { (root, keep) -> GalleryLibraryStore.outputFileId(root) to keep }
        val historyIds = synchronized(stateLock) {
            rawItems.filter { !isOutputFilePromptId(it.promptId) }.map { GalleryLibraryStore.fileId(it) }.toSet()
        }
        updateLibrary { lib ->
            val moves = lib.moves.toMutableMap()
            for ((rootId, keep) in rootToKeep) {
                val originalId = moves.entries.firstOrNull { GalleryLibraryStore.outputFileId(it.value) == rootId }?.key
                    ?: rootId.takeIf { it in historyIds }
                    ?: continue
                moves[originalId] = keep
            }
            lib.copy(moves = moves)
        }
        val deleted = withContext(Dispatchers.IO) { client.deleteOutputFiles(duplicates.map { it.first }) }
        // Gone from disk: drop the root listing right away (a refresh confirms it)
        val deletedIds = deleted.map { GalleryLibraryStore.outputFileId(it) }.toSet()
        synchronized(stateLock) {
            rawItems = rawItems.filterNot { isOutputFilePromptId(it.promptId) && GalleryLibraryStore.fileId(it) in deletedIds }
            publish()
        }
        refresh()
        return deleted.size
    }

    /** Choose an album's cover (file id), or null to use its first item. */
    fun setAlbumCover(albumId: String, fileId: String?) {
        updateLibrary { lib ->
            lib.copy(covers = if (fileId == null) lib.covers - albumId else lib.covers + (albumId to fileId))
        }
    }

    /** Forget queued moves of these prompts (they were put into another album by hand). */
    fun cancelPendingMoves(promptIds: Set<String>) {
        if (_library.value.pendingMoves.keys.none { it in promptIds }) return
        updateLibrary { lib -> lib.copy(pendingMoves = lib.pendingMoves - promptIds) }
    }

    private var pendingMovesJob: Job? = null

    /** Move newly generated items into the album folder that was selected when they were queued. */
    private fun processPendingMoves() {
        val pending = _library.value.pendingMoves
        if (pending.isEmpty() || pendingMovesJob?.isActive == true) return
        val found = _galleryItems.value.filter { it.promptId in pending }
        if (found.isEmpty()) return
        pendingMovesJob = scope.launch {
            found.groupBy { pending.getValue(it.promptId) }.forEach { (folder, items) -> moveToFolder(items, folder) }
            val done = found.mapTo(HashSet()) { it.promptId }
            updateLibrary { lib -> lib.copy(pendingMoves = lib.pendingMoves - done) }
        }
    }

    private fun pathOf(item: GalleryItem) =
        if (item.subfolder.isEmpty()) item.filename else "${item.subfolder}/${item.filename}"

    /**
     * Delete items for good: they are hidden permanently, removed from the device copy,
     * and a history entry is removed from the server once none of its images are left.
     * ComfyUI has no API to delete files, so the files stay in the server's output folder.
     */
    suspend fun deletePermanently(items: Collection<GalleryItem>) {
        if (items.isEmpty()) return
        val ids = items.map { GalleryLibraryStore.fileId(it) }.toSet()
        updateLibrary { lib ->
            lib.copy(trash = lib.trash - ids, purged = lib.purged + ids, order = lib.order - ids)
        }
        val remainingPrompts = synchronized(stateLock) { allItems.mapTo(HashSet()) { it.promptId } }

        val context = applicationContext
        val serverId = ConnectionManager.currentServerId
        withContext(Dispatchers.IO) {
            if (context != null && serverId != null) {
                LocalGalleryStore.removeKeys(context, serverId, items.map { it.toCacheKey().keyString }.toSet())
            }
            items.forEach { MediaCache.evict(it.toCacheKey()) }
        }

        // Remove history entries that have no images left (best effort; hidden either way)
        val client = comfyUIClient ?: return
        items.map { it.promptId }.distinct()
            .filter { !isOutputFilePromptId(it) && it !in remainingPrompts }
            .forEach { promptId ->
                withContext(Dispatchers.IO) {
                    kotlin.coroutines.suspendCoroutine<Boolean> { continuation ->
                        client.deleteHistoryItem(promptId) { success ->
                            continuation.resumeWith(Result.success(success))
                        }
                    }
                }
            }
    }

    /**
     * Clear cached data (for logout/disconnect)
     * Note: Uses MediaCache.reset() to preserve disk cache for offline mode.
     * User can still clear disk cache manually via Settings → Clear Cache.
     */
    fun clearCache() {
        DebugLogger.i(TAG, "Clearing cache")
        _serverFolders.value = emptyList()
        synchronized(stateLock) {
            rawItems = emptyList()
            allItems = emptyList()
            libraryServerId = null
            _library.value = GalleryLibrary()
            publish()
        }
        _lastRefreshTime.value = 0L
        hasLoadedOnce = false
        stopPeriodicRefresh()
        localSyncJob?.cancel()
        localSyncJob = null
        // Clear media cache (preserves disk cache for offline mode)
        MediaCache.reset()
    }

    /**
     * Reset repository state (for logout/disconnect).
     * Called by ConnectionManager when disconnecting.
     */
    fun reset() {
        DebugLogger.i(TAG, "Resetting repository")
        clearCache()
    }

    /**
     * Parse history JSON to gallery items.
     * Does NOT fetch bitmaps - those are loaded lazily via MediaCache.
     */
    /**
     * When a prompt ran, from its status messages ("execution_start"/"execution_success"
     * carry a "timestamp" in ms). Returns 0 if the server does not report it.
     */
    private fun historyTimestamp(promptHistory: JSONObject): Long {
        val messages = promptHistory.optJSONObject("status")?.optJSONArray("messages") ?: return 0L
        var result = 0L
        for (i in 0 until messages.length()) {
            val message = messages.optJSONArray(i) ?: continue
            val ts = message.optJSONObject(1)?.optLong("timestamp", 0L) ?: 0L
            if (ts > result) result = ts
        }
        return result
    }

    private fun parseHistoryToGalleryItems(historyJson: JSONObject): List<GalleryItem> {
        val items = mutableListOf<GalleryItem>()
        var index = 0

        val promptIds = historyJson.keys()
        while (promptIds.hasNext()) {
            val promptId = promptIds.next()
            val promptHistory = historyJson.optJSONObject(promptId) ?: continue
            val outputs = promptHistory.optJSONObject("outputs") ?: continue
            val timestamp = historyTimestamp(promptHistory)

            val nodeIds = outputs.keys()
            while (nodeIds.hasNext()) {
                val nodeId = nodeIds.next()
                val nodeOutput = outputs.optJSONObject(nodeId) ?: continue

                // Check for videos first
                val videos = nodeOutput.optJSONArray("videos")
                    ?: nodeOutput.optJSONArray("gifs")

                if (videos != null && videos.length() > 0) {
                    for (i in 0 until videos.length()) {
                        val videoInfo = videos.optJSONObject(i) ?: continue
                        val filename = videoInfo.optString("filename", "")
                        if (filename.isEmpty()) continue

                        val subfolder = videoInfo.optString("subfolder", "")
                        val type = videoInfo.optString("type", "output")

                        items.add(GalleryItem(
                            promptId = promptId,
                            filename = filename,
                            subfolder = subfolder,
                            type = type,
                            isVideo = true,
                            index = index++,
                            timestamp = timestamp
                        ))
                    }
                }

                // Check for images
                val images = nodeOutput.optJSONArray("images")
                if (images != null && images.length() > 0) {
                    for (i in 0 until images.length()) {
                        val imageInfo = images.optJSONObject(i) ?: continue
                        val filename = imageInfo.optString("filename", "")
                        if (filename.isEmpty()) continue

                        // Skip if it's actually a video
                        if (VIDEO_EXTENSIONS.any { filename.lowercase().endsWith(it) }) {
                            val subfolder = imageInfo.optString("subfolder", "")
                            val type = imageInfo.optString("type", "output")

                            items.add(GalleryItem(
                                promptId = promptId,
                                filename = filename,
                                subfolder = subfolder,
                                type = type,
                                isVideo = true,
                                index = index++,
                                timestamp = timestamp
                            ))
                            continue
                        }

                        val subfolder = imageInfo.optString("subfolder", "")
                        val type = imageInfo.optString("type", "output")

                        items.add(GalleryItem(
                            promptId = promptId,
                            filename = filename,
                            subfolder = subfolder,
                            type = type,
                            isVideo = false,
                            index = index++,
                            timestamp = timestamp
                        ))
                    }
                }
            }
        }

        // Sort by index descending (newest first)
        return items.sortedByDescending { it.index }
    }
}
