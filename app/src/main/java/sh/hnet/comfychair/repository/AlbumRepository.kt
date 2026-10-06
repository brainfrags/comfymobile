package sh.hnet.comfychair.repository

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.gallery.GalleryItem
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.GalleryAlbum
import sh.hnet.comfychair.storage.GalleryAlbumStore
import sh.hnet.comfychair.storage.GalleryLibrary

/**
 * Albums of the current server, and the album currently selected.
 * Shared by the gallery and the generation screens so their album selection stays in sync:
 * opening an album in the gallery selects it for generation, and new images generated while
 * an album is selected are added to it.
 *
 * Two kinds of albums:
 * - Folder albums: subfolders of ComfyUI's output folder. Their items are the files in the
 *   folder; adding items moves the files there (see [GalleryRepository.moveToFolder]).
 *   IDs start with [FOLDER_PREFIX].
 * - Older app-only albums (kept on the device), which just remember their members. They are
 *   turned into folder albums once the server can move files ([syncToServer]).
 *
 * An item is in one album at a time.
 */
object AlbumRepository {
    const val FOLDER_PREFIX = "folder:"

    fun isFolder(albumId: String) = albumId.startsWith(FOLDER_PREFIX)
    fun folderOf(albumId: String) = albumId.removePrefix(FOLDER_PREFIX)
    fun folderAlbumId(folder: String) = FOLDER_PREFIX + folder

    /** A name usable as a folder name on Windows, macOS and Linux. */
    fun folderName(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").trim('.', ' ')

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gallery = GalleryRepository.getInstance()

    // App-only albums (persisted in albums.json)
    private val _localAlbums = MutableStateFlow<List<GalleryAlbum>>(emptyList())

    /** All albums: app-only ones first, then one per output subfolder (A-Z). */
    val albums: StateFlow<List<GalleryAlbum>> = combine(
        _localAlbums,
        gallery.galleryItems,
        gallery.library,
        gallery.serverFolders
    ) { local, items, library, serverFolders ->
        local + folderAlbums(items, library.folders + serverFolders, library.pendingMoves)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Selected album ID, or null for none (the whole gallery) */
    private val _currentAlbumId = MutableStateFlow<String?>(null)
    val currentAlbumId: StateFlow<String?> = _currentAlbumId.asStateFlow()

    /**
     * Album selected last. Kept when the gallery leaves the album (back to Photos), so new
     * images still go there; only choosing "no folder" on a generation screen clears it.
     */
    private val _lastAlbumId = MutableStateFlow<String?>(null)

    /** Album new images go into: the selected one, else the one selected last (null = none). */
    val targetAlbumId: StateFlow<String?> = combine(_currentAlbumId, _lastAlbumId, albums) { current, last, all ->
        current ?: last?.takeIf { id -> all.any { it.id == id } }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private var appContext: Context? = null
    private var serverId: String? = null

    /** One album per output subfolder (also empty ones in [extraFolders]), with its items as members. */
    private fun folderAlbums(
        items: List<GalleryItem>,
        extraFolders: Set<String>,
        pendingMoves: Map<String, String>
    ): List<GalleryAlbum> {
        val byFolder = items.filter { it.type == "output" && it.subfolder.isNotEmpty() }.groupBy { it.subfolder }
        val pendingByFolder = pendingMoves.entries.groupBy({ it.value }, { it.key })
        return (byFolder.keys + extraFolders.filter { it.isNotBlank() } + pendingByFolder.keys)
            // The trash folder is the trash, not an album
            .filterNot { GalleryItem.isTrashFolder(it) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .map { folder ->
                GalleryAlbum(
                    id = folderAlbumId(folder),
                    name = folder,
                    members = byFolder[folder].orEmpty().mapTo(HashSet()) { it.key },
                    // Generated while this album was selected and not moved into it yet
                    prompts = pendingByFolder[folder].orEmpty().toSet()
                )
            }
    }

    // Loading and selection

    /** Load albums for the current server (no-op if already loaded). Safe to call often. */
    @Synchronized
    fun ensureLoaded(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val id = ConnectionManager.currentServerId ?: return
        if (id == serverId) return
        serverId = id
        val loaded = GalleryAlbumStore.load(ctx, id)
        _localAlbums.value = loaded
        // Folder albums are only known once the gallery has loaded, so keep a folder selection as is
        _currentAlbumId.value = AppSettings.getCurrentAlbumId(ctx, id)
            ?.takeIf { a -> isFolder(a) || loaded.any { it.id == a } }
        _lastAlbumId.value = (AppSettings.getLastAlbumId(ctx, id) ?: _currentAlbumId.value)
            ?.takeIf { a -> isFolder(a) || loaded.any { it.id == a } }
    }

    fun select(albumId: String?) {
        val id = albumId?.takeIf { a -> isFolder(a) || _localAlbums.value.any { it.id == a } }
        _currentAlbumId.value = id
        if (id != null) setLast(id)
        val ctx = appContext ?: return
        val server = serverId ?: return
        AppSettings.setCurrentAlbumId(ctx, server, id)
    }

    /**
     * Choose where new images go (generation screens). Unlike [select], choosing none also
     * forgets the last album, so new images stay out of every album.
     */
    fun selectTarget(albumId: String?) {
        select(albumId)
        if (albumId == null) setLast(null)
    }

    private fun setLast(albumId: String?) {
        _lastAlbumId.value = albumId
        val ctx = appContext ?: return
        val server = serverId ?: return
        AppSettings.setLastAlbumId(ctx, server, albumId)
    }

    /** Change the app-only albums (folder albums change by moving files). */
    private fun updateLocal(transform: (List<GalleryAlbum>) -> List<GalleryAlbum>) {
        val albums = synchronized(this) {
            transform(_localAlbums.value).also { _localAlbums.value = it }
        }
        val current = _currentAlbumId.value
        if (current != null && !isFolder(current) && albums.none { it.id == current }) select(null)
        val last = _lastAlbumId.value
        if (last != null && !isFolder(last) && albums.none { it.id == last }) setLast(null)
        val ctx = appContext ?: return
        val server = serverId ?: return
        scope.launch(Dispatchers.IO) { GalleryAlbumStore.save(ctx, server, albums) }
    }

    // Album operations (all return the number of items whose files could not be moved)

    /** Create an album: a subfolder of ComfyUI's output folder. [items] are moved into it. */
    suspend fun create(name: String, items: List<GalleryItem>): Int {
        val folder = folderName(name)
        if (folder.isEmpty()) return items.size
        createFolder(folder)
        return addItems(folderAlbumId(folder), items)
    }

    /** Add items to an album; for a folder album the files are moved into its folder. */
    suspend fun addItems(albumId: String, items: List<GalleryItem>): Int {
        if (items.isEmpty()) return 0
        leaveOtherAlbums(items, albumId)
        if (isFolder(albumId)) return gallery.moveToFolder(items, folderOf(albumId))
        val keys = items.mapTo(HashSet()) { it.key }
        updateLocal { list -> list.map { if (it.id == albumId) it.copy(members = it.members + keys) else it } }
        // Into an app-only album: a file in a folder album goes back to the output folder itself
        return gallery.moveToFolder(items.filter { it.type == "output" && it.subfolder.isNotEmpty() }, "")
    }

    /** Remove items from an album; for a folder album the files go back to the output folder itself. */
    suspend fun removeItems(albumId: String, items: List<GalleryItem>): Int {
        if (items.isEmpty()) return 0
        if (isFolder(albumId)) return gallery.moveToFolder(items, "")
        // Also drop prompt-based membership (images generated while the album was selected)
        val keys = items.mapTo(HashSet()) { it.key }
        val promptIds = items.mapTo(HashSet()) { it.promptId }
        updateLocal { list ->
            list.map { if (it.id == albumId) it.copy(members = it.members - keys, prompts = it.prompts - promptIds) else it }
        }
        return 0
    }

    /**
     * Rename an album; for a folder album its files are moved into the renamed folder.
     * @return null if no files were moved (app-only album, or the name did not change)
     */
    suspend fun rename(albumId: String, name: String): Int? {
        if (!isFolder(albumId)) {
            val trimmed = name.trim()
            if (trimmed.isNotEmpty()) updateLocal { list -> list.map { if (it.id == albumId) it.copy(name = trimmed) else it } }
            return null
        }
        val newFolder = folderName(name)
        val oldFolder = folderOf(albumId)
        if (newFolder.isEmpty() || newFolder == oldFolder) return null
        val newId = folderAlbumId(newFolder)
        if (_currentAlbumId.value == albumId) select(newId)
        if (_lastAlbumId.value == albumId) setLast(newId)
        // Keep its place in a custom order, its cover, sort and whether it is hidden
        gallery.updateLibrary { lib -> lib.withAlbumIdChanged(albumId, newId) }
        return moveFolder(oldFolder, newFolder)
    }

    /**
     * Delete only the album; the items stay in the gallery (a folder album's files go up one
     * level: the output folder itself for an album).
     * @return null if no files were moved (app-only album)
     */
    suspend fun delete(albumId: String): Int? {
        gallery.updateLibrary { lib -> lib.copy(hiddenAlbums = lib.hiddenAlbums - albumId, albumSorts = lib.albumSorts - albumId) }
        if (!isFolder(albumId)) {
            updateLocal { list -> list.filterNot { it.id == albumId } }
            return null
        }
        val folder = folderOf(albumId)
        if (_currentAlbumId.value == albumId) select(null)
        if (_lastAlbumId.value == albumId) setLast(null)
        return moveFolder(folder, folder.substringBeforeLast('/', ""))
    }

    /** Use [item] as the cover of [albumId] (null = its first item). */
    fun setCover(albumId: String, item: GalleryItem?) = setCoverId(albumId, item?.libraryId)

    private fun setCoverId(albumId: String, libraryId: String?) {
        gallery.updateLibrary { lib ->
            lib.copy(covers = if (libraryId == null) lib.covers - albumId else lib.covers + (albumId to libraryId))
        }
    }

    /** Hide an album from the album list, or show it again. */
    fun setHidden(albumId: String, hidden: Boolean) {
        gallery.updateLibrary { lib ->
            lib.copy(hiddenAlbums = if (hidden) lib.hiddenAlbums + albumId else lib.hiddenAlbums - albumId)
        }
    }

    /** Sort the items of an album by [sortOrder] (a sort order name), or null to follow the gallery's. */
    fun setItemSort(albumId: String, sortOrder: String?) {
        gallery.updateLibrary { lib ->
            lib.copy(albumSorts = if (sortOrder == null) lib.albumSorts - albumId else lib.albumSorts + (albumId to sortOrder))
        }
    }

    /** The library with what it keeps per album (order, cover, hidden, sort) moved to another album id. */
    private fun GalleryLibrary.withAlbumIdChanged(oldId: String, newId: String) = copy(
        albumOrder = albumOrder.map { if (it == oldId) newId else it },
        covers = covers[oldId]?.let { (covers - oldId) + (newId to it) } ?: covers,
        hiddenAlbums = if (oldId in hiddenAlbums) hiddenAlbums - oldId + newId else hiddenAlbums,
        albumSorts = albumSorts[oldId]?.let { (albumSorts - oldId) + (newId to it) } ?: albumSorts
    )

    /** Save a custom album order (album ids, top first). */
    fun setOrder(order: List<String>) {
        gallery.updateLibrary { lib -> lib.copy(albumOrder = order) }
    }

    /**
     * When items go into [targetAlbumId], take them out of every other app-only album (by key
     * and by prompt) and cancel a pending move of their prompt into another folder. Folder
     * albums need nothing: the file itself moves.
     */
    private fun leaveOtherAlbums(items: List<GalleryItem>, targetAlbumId: String) {
        val keys = items.mapTo(HashSet()) { it.key }
        val promptIds = items.mapTo(HashSet()) { it.promptId }
        updateLocal { list ->
            list.map { album ->
                if (album.id == targetAlbumId) album
                else album.copy(members = album.members - keys, prompts = album.prompts - promptIds)
            }
        }
        if (gallery.library.value.pendingMoves.keys.any { it in promptIds }) {
            gallery.updateLibrary { lib -> lib.copy(pendingMoves = lib.pendingMoves - promptIds) }
        }
    }

    // New images

    /**
     * Put everything a prompt generates into the selected album, else the one selected last
     * ([targetAlbumId]; none if the user chose no folder). For a folder album
     * the files are moved into its folder once the gallery sees them ([processPendingMoves]).
     */
    fun addPromptToCurrent(promptId: String) {
        val albumId = targetAlbumId.value ?: return
        if (isFolder(albumId)) {
            gallery.updateLibrary { lib -> lib.copy(pendingMoves = lib.pendingMoves + (promptId to folderOf(albumId))) }
            return
        }
        updateLocal { list -> list.map { if (it.id == albumId) it.copy(prompts = it.prompts + promptId) else it } }
    }

    private var pendingMovesJob: Job? = null

    /** Move newly generated items into the album folder that was selected when they were queued. */
    internal fun processPendingMoves() {
        val pending = gallery.library.value.pendingMoves
        if (pending.isEmpty() || pendingMovesJob?.isActive == true) return
        val found = gallery.galleryItems.value.filter { it.promptId in pending }
        if (found.isEmpty()) return
        pendingMovesJob = scope.launch {
            found.groupBy { pending.getValue(it.promptId) }.forEach { (folder, items) -> gallery.moveToFolder(items, folder) }
            val done = found.mapTo(HashSet()) { it.promptId }
            gallery.updateLibrary { lib -> lib.copy(pendingMoves = lib.pendingMoves - done) }
        }
    }

    // Album folders

    private val comfyUIClient: ComfyUIClient?
        get() = ConnectionManager.clientOrNull

    /** Create an album folder in the output folder (shown even while empty). */
    private suspend fun createFolder(folder: String) {
        gallery.updateLibrary { lib -> lib.copy(folders = lib.folders + folder) }
        val client = comfyUIClient ?: return
        if (client.hasFileOps) withContext(Dispatchers.IO) { client.createOutputFolder(folder) }
    }

    /**
     * Forget an album folder; it is removed from the server too once it is empty
     * (ComfyMobile extension only).
     */
    private suspend fun removeFolder(folder: String) {
        gallery.updateLibrary { lib -> lib.copy(folders = lib.folders - folder) }
        gallery.updateServerFolders { it - folder }
        val client = comfyUIClient ?: return
        if (client.hasFileOps) withContext(Dispatchers.IO) { client.removeOutputFolder(folder) }
    }

    /**
     * Move a whole album folder into [to] (rename; "" = back into the output folder itself).
     * @return Number of items that could not be moved
     */
    private suspend fun moveFolder(from: String, to: String): Int {
        if (!gallery.isConnected) return 1
        // Show the renamed album right away (empty until the files are moved)
        val addedTarget = to.isNotEmpty() && to !in gallery.library.value.folders
        if (addedTarget) gallery.updateLibrary { lib -> lib.copy(folders = lib.folders + to) }

        val move = gallery.moveFolderFiles(from, to)
        if (!move.asWhole && move.items > 0 && move.failed == move.items) {
            // Nothing could be moved: don't leave an empty renamed album behind
            if (addedTarget) gallery.updateLibrary { lib -> lib.copy(folders = lib.folders - to) }
            return move.failed
        }

        gallery.updateLibrary { lib ->
            lib.copy(
                folders = (lib.folders - from).let { if (to.isNotEmpty()) it + to else it },
                pendingMoves = lib.pendingMoves.mapValues { (_, folder) -> if (folder == from) to else folder }
                    .filterValues { it.isNotEmpty() }
            )
        }
        gallery.updateServerFolders { folders ->
            folders.filterNot { it == from || it.startsWith("$from/") }
                .let { if (to.isNotEmpty() && to !in it) it + to else it }
        }
        if (!move.asWhole) {
            if (to.isNotEmpty()) createFolder(to)
            // Only removed when empty; other files keep the old folder on the PC
            removeFolder(from)
        }
        gallery.refresh()
        return move.failed
    }

    /**
     * Make the PC match the app's albums (ComfyMobile extension only): album folders made in
     * the app are created on the PC, and app-only albums become output subfolders.
     */
    internal suspend fun syncToServer(client: ComfyUIClient) {
        val missing = gallery.library.value.folders - gallery.serverFolders.value.toSet()
        if (missing.isNotEmpty()) {
            withContext(Dispatchers.IO) { missing.forEach { client.createOutputFolder(it) } }
            gallery.updateServerFolders { it + missing }
        }
        convertLocalAlbumsToFolders()
    }

    /**
     * Turn app-only albums into output subfolders (same name): their files are moved into the
     * folder, its cover, place, sort, hidden state and selection follow, and the app-only album is removed. An album
     * whose files could not all be moved is kept and tried again on the next sync.
     */
    private suspend fun convertLocalAlbumsToFolders() {
        for (album in _localAlbums.value) {
            val folder = folderName(album.name).ifEmpty { "Folder" }
            val members = gallery.galleryItems.value.filter { album.contains(it.promptId, it.key) }
            createFolder(folder)
            if (gallery.moveToFolder(members, folder) > 0) continue
            val folderId = folderAlbumId(folder)
            gallery.updateLibrary { lib -> lib.withAlbumIdChanged(album.id, folderId) }
            if (_currentAlbumId.value == album.id) select(folderId)
            updateLocal { list -> list.filterNot { it.id == album.id } }
        }
    }
}
