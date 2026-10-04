package sh.hnet.comfychair.viewmodel

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.GalleryAlbum
import sh.hnet.comfychair.repository.AlbumRepository
import sh.hnet.comfychair.storage.GalleryLibraryStore
import sh.hnet.comfychair.util.DebugLogger
import java.io.File

/**
 * Represents a gallery item (image or video).
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
}

/**
 * How the gallery grid is laid out.
 * [square] = thumbnails cropped to squares; otherwise original aspect ratio.
 */
enum class GalleryViewMode(val columns: Int, val square: Boolean) {
    GRID_2(2, true),
    GRID_3(3, true),
    GRID_4(4, true),
    MASONRY(2, false),
    SINGLE(1, false)
}

/**
 * Order of the gallery items. The repository delivers them newest first.
 */
enum class GallerySortOrder {
    NEWEST,
    OLDEST,
    NAME,
    /** Images first, then videos (newest first within each) */
    TYPE,
    /** Order set by holding and dragging items; [customOrder] = file ids, top first */
    CUSTOM;

    fun apply(items: List<GalleryItem>, customOrder: List<String> = emptyList()): List<GalleryItem> = when (this) {
        NEWEST -> items
        OLDEST -> items.asReversed()
        NAME -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.filename })
        TYPE -> items.sortedBy { it.isVideo }
        CUSTOM -> applyCustomOrder(items, customOrder)
    }

    companion object {
        /** Items in the saved custom order; items not ordered yet (new ones) stay on top. */
        fun applyCustomOrder(items: List<GalleryItem>, order: List<String>): List<GalleryItem> {
            if (order.isEmpty()) return items
            val position = HashMap<String, Int>(order.size * 2)
            order.forEachIndexed { i, id -> position.putIfAbsent(id, i) }
            val (ordered, fresh) = items.partition { GalleryLibraryStore.fileId(it) in position }
            return fresh + ordered.sortedBy { position[GalleryLibraryStore.fileId(it)] }
        }
    }
}

/** Order of the album list. */
enum class AlbumSortOrder {
    NAME,
    /** Album with the newest image first */
    RECENT,
    /** Most images first */
    COUNT,
    /** Order set by holding and dragging albums */
    CUSTOM
}

/** Top-level gallery sections, like Google Photos: all photos, albums, and the trash */
enum class GallerySection { PHOTOS, ALBUMS, TRASH }

/**
 * UI state for the Gallery screen
 */
data class GalleryUiState(
    val section: GallerySection = GallerySection.PHOTOS,
    /** Items shown: Photos = items in no album; Albums = the opened album's items (empty on the album list) */
    val items: List<GalleryItem> = emptyList(),
    /** Total number of items before album filtering */
    val totalCount: Int = 0,
    val viewMode: GalleryViewMode = GalleryViewMode.GRID_2,
    val sortOrder: GallerySortOrder = GallerySortOrder.NEWEST,
    val albumSortOrder: AlbumSortOrder = AlbumSortOrder.NAME,
    /** Albums in [albumSortOrder] */
    val albums: List<GalleryAlbum> = emptyList(),
    /** null = all items */
    val selectedAlbumId: String? = null,
    /** Number of existing gallery items in each album (albumId -> count) */
    val albumCounts: Map<String, Int> = emptyMap(),
    /** Cover item of each album (its first item in the current sort order) */
    val albumCovers: Map<String, GalleryItem> = emptyMap(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val selectedItems: Set<String> = emptySet(), // Set of "${promptId}_${filename}" keys
    val isSelectionMode: Boolean = false,
    val trashCount: Int = 0,
    /** Root copies of images that are also in an album, waiting for the user to confirm deleting them */
    val rootDuplicates: List<Pair<String, String>>? = null,
    /** Files are being moved between album folders */
    val isMoving: Boolean = false
) {
    val selectedAlbum: GalleryAlbum?
        get() = albums.firstOrNull { it.id == selectedAlbumId }

    /** Items can be reordered by drag and drop (not in the trash or on the album list). */
    val canReorder: Boolean
        get() = section == GallerySection.PHOTOS || (section == GallerySection.ALBUMS && selectedAlbumId != null)
}

/**
 * Events emitted by gallery operations
 */
sealed class GalleryEvent {
    data class ShowToast(val messageResId: Int) : GalleryEvent()
    data class ShowMedia(val item: GalleryItem, val bitmap: Bitmap?, val videoUri: Uri?) : GalleryEvent()
}

/**
 * ViewModel for the Gallery screen.
 * Uses GalleryRepository for data management and background loading.
 */
class GalleryViewModel : ViewModel() {

    // Constants
    companion object {
        private const val TAG = "Gallery"
    }

    // State
    private val repository = GalleryRepository.getInstance()

    // Selection state (local to this ViewModel)
    private val _selectedItems = MutableStateFlow<Set<String>>(emptySet())
    private val _isSelectionMode = MutableStateFlow(false)

    // View mode and section (albums and the opened album live in AlbumRepository)
    private data class ViewState(
        val viewMode: GalleryViewMode = GalleryViewMode.GRID_2,
        val sortOrder: GallerySortOrder = GallerySortOrder.NEWEST,
        val albumSortOrder: AlbumSortOrder = AlbumSortOrder.NAME,
        val section: GallerySection = GallerySection.PHOTOS,
        val isMoving: Boolean = false,
        val rootDuplicates: List<Pair<String, String>>? = null
    )
    private val _viewState = MutableStateFlow(ViewState())
    private var appContext: Context? = null

    // Combine repository state with local selection state
    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<GalleryEvent>()
    val events: SharedFlow<GalleryEvent> = _events.asSharedFlow()

    init {
        // Observe repository state and combine with local selection state
        viewModelScope.launch {
            combine(
                combine(
                    repository.galleryItems,
                    repository.isLoading,
                    repository.isManualRefreshing
                ) { items, isLoading, isManualRefreshing -> Triple(items, isLoading, isManualRefreshing) },
                combine(_selectedItems, _isSelectionMode) { selected, mode -> selected to mode },
                _viewState,
                combine(AlbumRepository.albums, AlbumRepository.currentAlbumId) { a, id -> a to id },
                combine(repository.trashedItems, repository.library) { trashed, library -> trashed to library }
            ) { (rawItems, isLoading, isManualRefreshing), (selectedItems, isSelectionMode), view, (albums, currentAlbumId), (trashed, library) ->
                val customOrder = library.order
                val covers = library.covers
                // Grid keys must be unique
                val items = view.sortOrder.apply(rawItems.distinctBy { getItemKey(it) }, customOrder)
                val album = albums.firstOrNull { it.id == currentAlbumId }
                fun GalleryAlbum.has(item: GalleryItem) = contains(item.promptId, getItemKey(item))
                // Photos shows only items not yet sorted into any album
                val unsorted = items.filter { item -> albums.none { it.has(item) } }
                val albumItems = albums.associate { a -> a.id to items.filter { a.has(it) } }
                GalleryUiState(
                    section = view.section,
                    items = when {
                        view.section == GallerySection.PHOTOS -> unsorted
                        view.section == GallerySection.TRASH -> trashed.distinctBy { getItemKey(it) }
                        album != null -> albumItems[album.id].orEmpty()
                        else -> emptyList()
                    },
                    totalCount = unsorted.size,
                    viewMode = view.viewMode,
                    sortOrder = view.sortOrder,
                    albumSortOrder = view.albumSortOrder,
                    albums = sortAlbums(albums, view.albumSortOrder, albumItems, rawItems, library.albumOrder),
                    selectedAlbumId = album?.id,
                    albumCounts = albumItems.mapValues { it.value.size },
                    // The chosen cover if it is still in the album, else the first item
                    albumCovers = albumItems.mapNotNull { (id, list) ->
                        val chosen = covers[id]?.let { fileId -> list.firstOrNull { GalleryLibraryStore.fileId(it) == fileId } }
                        (chosen ?: list.firstOrNull())?.let { id to it }
                    }.toMap(),
                    isLoading = isLoading,
                    isRefreshing = isManualRefreshing, // Only show indicator for manual refresh
                    selectedItems = selectedItems,
                    isSelectionMode = isSelectionMode,
                    trashCount = trashed.size,
                    rootDuplicates = view.rootDuplicates,
                    isMoving = view.isMoving
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun initialize(context: Context) {
        // MediaCache is used for all fetch operations
        // Repository is already initialized by GenerationViewModel
        appContext = context.applicationContext
        val mode = AppSettings.getGalleryViewMode(context)
            ?.let { name -> GalleryViewMode.entries.firstOrNull { it.name == name } }
            ?: GalleryViewMode.GRID_2
        val albumOrder = AppSettings.getAlbumSortOrder(context)
            ?.let { name -> AlbumSortOrder.entries.firstOrNull { it.name == name } }
            ?: AlbumSortOrder.NAME
        _viewState.value = _viewState.value.copy(albumSortOrder = albumOrder)
        val order = AppSettings.getGallerySortOrder(context)
            ?.let { name -> GallerySortOrder.entries.firstOrNull { it.name == name } }
            ?: GallerySortOrder.NEWEST
        AlbumRepository.ensureLoaded(context)
        // Open on the album selected for generation, if any
        val section = if (AlbumRepository.currentAlbumId.value != null) GallerySection.ALBUMS else GallerySection.PHOTOS
        _viewState.value = _viewState.value.copy(viewMode = mode, sortOrder = order, section = section)
    }

    fun setSection(section: GallerySection) {
        clearSelection()
        // Leaving an album (to Photos or the trash) deselects it, also for generation
        if (section != GallerySection.ALBUMS) AlbumRepository.select(null)
        _viewState.value = _viewState.value.copy(section = section)
    }

    // Album list order

    private fun sortAlbums(
        albums: List<GalleryAlbum>,
        order: AlbumSortOrder,
        albumItems: Map<String, List<GalleryItem>>,
        newestFirst: List<GalleryItem>,
        customOrder: List<String>
    ): List<GalleryAlbum> = when (order) {
        AlbumSortOrder.NAME -> albums.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        AlbumSortOrder.COUNT -> albums.sortedByDescending { albumItems[it.id]?.size ?: 0 }
        AlbumSortOrder.RECENT -> {
            // Position of each item in the gallery (newest first); empty albums go last
            val rank = HashMap<String, Int>(newestFirst.size * 2)
            newestFirst.forEachIndexed { i, item -> rank.putIfAbsent(getItemKey(item), i) }
            albums.sortedBy { a -> albumItems[a.id].orEmpty().minOfOrNull { rank[getItemKey(it)] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE }
        }
        AlbumSortOrder.CUSTOM -> {
            val position = customOrder.withIndex().associate { it.value to it.index }
            // Albums not placed yet (new ones) go last, by name
            albums.sortedWith(
                compareBy<GalleryAlbum> { position[it.id] ?: Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
        }
    }

    fun setAlbumSortOrder(order: AlbumSortOrder) {
        _viewState.value = _viewState.value.copy(albumSortOrder = order)
        appContext?.let { AppSettings.setAlbumSortOrder(it, order.name) }
    }

    /** Dragging an album switches to the custom order, starting from the order shown. */
    fun beginAlbumReorder() {
        if (_viewState.value.albumSortOrder == AlbumSortOrder.CUSTOM) return
        repository.setAlbumOrder(_uiState.value.albums.map { it.id })
        setAlbumSortOrder(AlbumSortOrder.CUSTOM)
    }

    /** Move album [fromId] to where [toId] is in the album list. */
    fun moveAlbum(fromId: String, toId: String) {
        if (fromId == toId) return
        val ids = _uiState.value.albums.map { it.id }.toMutableList()
        val from = ids.indexOf(fromId)
        val to = ids.indexOf(toId)
        if (from < 0 || to < 0) return
        ids.removeAt(from)
        ids.add(to, fromId)
        repository.setAlbumOrder(ids)
    }

    fun setSortOrder(order: GallerySortOrder) {
        _viewState.value = _viewState.value.copy(sortOrder = order)
        appContext?.let { AppSettings.setGallerySortOrder(it, order.name) }
    }

    // View mode

    fun setViewMode(mode: GalleryViewMode) {
        _viewState.value = _viewState.value.copy(viewMode = mode)
        appContext?.let { AppSettings.setGalleryViewMode(it, mode.name) }
    }

    // Albums

    /** (Re)load albums for the current server. Safe to call often. */
    fun reloadAlbums() {
        appContext?.let { AlbumRepository.ensureLoaded(it) }
    }

    private fun updateAlbums(transform: (List<GalleryAlbum>) -> List<GalleryAlbum>) {
        AlbumRepository.update(transform)
    }

    /** Open an album (null = back to the album list). Also selects it for generation. */
    fun selectAlbum(albumId: String?) {
        clearSelection()
        AlbumRepository.select(albumId)
        if (albumId != null) _viewState.value = _viewState.value.copy(section = GallerySection.ALBUMS)
    }

    private fun folderOf(albumId: String) = AlbumRepository.folderOf(albumId)

    /** A name usable as a folder name on Windows, macOS and Linux. */
    private fun folderName(name: String): String = AlbumRepository.folderName(name)

    /** Move items into an album folder ("" = output root) in the background and report the result. */
    private fun moveItems(items: List<GalleryItem>, folder: String, successMessage: Int, after: suspend () -> Unit = {}) {
        viewModelScope.launch {
            _viewState.value = _viewState.value.copy(isMoving = true)
            try {
                val failed = repository.moveToFolder(items, folder)
                after()
                _events.emit(GalleryEvent.ShowToast(if (failed == 0) successMessage else R.string.msg_some_items_failed_to_move))
            } finally {
                _viewState.value = _viewState.value.copy(isMoving = false)
            }
        }
    }

    private fun runFolderMove(successMessage: Int, move: suspend () -> Int) {
        viewModelScope.launch {
            _viewState.value = _viewState.value.copy(isMoving = true)
            try {
                val failed = move()
                _events.emit(GalleryEvent.ShowToast(if (failed == 0) successMessage else R.string.msg_some_items_failed_to_move))
                // An older extension can't move whole folders (other files keep the old folder)
                if (repository.isFileOpsOutdated) _events.emit(GalleryEvent.ShowToast(R.string.msg_extension_outdated))
            } finally {
                _viewState.value = _viewState.value.copy(isMoving = false)
            }
        }
    }

    /**
     * Create an album: a subfolder of ComfyUI's output folder. If items are selected,
     * their files are moved into it.
     */
    fun createAlbum(name: String) {
        val folder = folderName(name)
        if (folder.isEmpty()) return
        val selected = getSelectedItems()
        clearSelection()
        viewModelScope.launch { repository.createFolder(folder) }
        if (selected.isNotEmpty()) {
            leaveOtherAlbums(selected, AlbumRepository.folderAlbumId(folder))
            moveItems(selected, folder, R.string.msg_moved_to_album)
        }
    }

    /**
     * An image is in one album at a time: when it goes into [targetAlbumId], take it out of
     * every other app-only album (by key and by prompt) and cancel a pending move of its
     * prompt into another folder. Folder albums need nothing: the file itself moves.
     */
    private fun leaveOtherAlbums(items: List<GalleryItem>, targetAlbumId: String) {
        if (items.isEmpty()) return
        val keys = items.map { getItemKey(it) }.toSet()
        val promptIds = items.map { it.promptId }.toSet()
        updateAlbums { list ->
            list.map { album ->
                if (album.id == targetAlbumId) album
                else album.copy(members = album.members - keys, prompts = album.prompts - promptIds)
            }
        }
        repository.cancelPendingMoves(promptIds)
    }

    /** Rename an album; for a folder album its files are moved into the renamed folder. */
    fun renameAlbum(albumId: String, name: String) {
        if (AlbumRepository.isFolder(albumId)) {
            val newFolder = folderName(name)
            val oldFolder = folderOf(albumId)
            if (newFolder.isEmpty() || newFolder == oldFolder) return
            val newId = AlbumRepository.folderAlbumId(newFolder)
            if (AlbumRepository.currentAlbumId.value == albumId) AlbumRepository.select(newId)
            // Keep its place in a custom order and its cover
            repository.renameAlbumId(albumId, newId)
            runFolderMove(R.string.msg_album_renamed) { repository.moveFolder(oldFolder, newFolder) }
            return
        }
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        updateAlbums { list -> list.map { if (it.id == albumId) it.copy(name = trimmed) else it } }
    }

    /**
     * Deletes only the album; the items stay in the gallery
     * (a folder album's files go back to the output folder itself).
     */
    fun deleteAlbum(albumId: String) {
        if (AlbumRepository.isFolder(albumId)) {
            val folder = folderOf(albumId)
            if (AlbumRepository.currentAlbumId.value == albumId) AlbumRepository.select(null)
            // Everything in the folder goes up one level (the output folder itself for an album)
            runFolderMove(R.string.msg_album_deleted) { repository.moveFolder(folder, folder.substringBeforeLast('/', "")) }
            return
        }
        updateAlbums { list -> list.filterNot { it.id == albumId } }
    }

    /** Add the selection to an album; for a folder album the files are moved into its folder. */
    fun addSelectedToAlbum(albumId: String) {
        val keys = _selectedItems.value
        if (keys.isEmpty()) return
        val selected = getSelectedItems()
        leaveOtherAlbums(selected, albumId)
        if (AlbumRepository.isFolder(albumId)) {
            clearSelection()
            moveItems(selected, folderOf(albumId), R.string.msg_moved_to_album)
            return
        }
        // Into an app-only album: a file in a folder album goes back to the output folder itself
        val inFolders = selected.filter { it.type == "output" && it.subfolder.isNotEmpty() }
        if (inFolders.isNotEmpty()) viewModelScope.launch { repository.moveToFolder(inFolders, "") }
        updateAlbums { list -> list.map { if (it.id == albumId) it.copy(members = it.members + keys) else it } }
        clearSelection()
        viewModelScope.launch { _events.emit(GalleryEvent.ShowToast(R.string.msg_added_to_album)) }
    }

    // Duplicate cleanup

    /** Look for images in the output folder itself that are also in an album; asks to confirm. */
    fun findDuplicates() {
        viewModelScope.launch {
            _viewState.value = _viewState.value.copy(isMoving = true)
            val (result, found) = try { repository.findRootDuplicates() } finally {
                _viewState.value = _viewState.value.copy(isMoving = false)
            }
            when {
                result is ComfyUIClient.DuplicatesResult.NotInstalled ->
                    _events.emit(GalleryEvent.ShowToast(R.string.msg_duplicates_need_extension))
                result is ComfyUIClient.DuplicatesResult.Failed ->
                    _events.emit(GalleryEvent.ShowToast(R.string.msg_duplicates_failed))
                found.isEmpty() -> _events.emit(GalleryEvent.ShowToast(R.string.msg_no_duplicates))
                else -> _viewState.value = _viewState.value.copy(rootDuplicates = found)
            }
        }
    }

    fun dismissDuplicates() {
        _viewState.value = _viewState.value.copy(rootDuplicates = null)
    }

    /** Delete the root copies found by [findDuplicates]; the album copies stay. */
    fun deleteDuplicates() {
        val duplicates = _viewState.value.rootDuplicates ?: return
        _viewState.value = _viewState.value.copy(rootDuplicates = null, isMoving = true)
        viewModelScope.launch {
            try {
                val deleted = repository.deleteRootDuplicates(duplicates)
                _events.emit(
                    GalleryEvent.ShowToast(if (deleted == duplicates.size) R.string.msg_duplicates_deleted else R.string.msg_some_items_failed_to_delete)
                )
            } finally {
                _viewState.value = _viewState.value.copy(isMoving = false)
            }
        }
    }

    /** Use the (one) selected item as the cover of [albumId]. */
    fun setSelectedAsCover(albumId: String) {
        val item = getSelectedItems().singleOrNull() ?: return
        repository.setAlbumCover(albumId, GalleryLibraryStore.fileId(item))
        clearSelection()
        viewModelScope.launch { _events.emit(GalleryEvent.ShowToast(R.string.msg_album_cover_set)) }
    }

    /** Remove the selection from an album; for a folder album the files go back to the output folder itself. */
    fun removeSelectedFromAlbum(albumId: String) {
        val keys = _selectedItems.value
        if (keys.isEmpty()) return
        if (AlbumRepository.isFolder(albumId)) {
            val selected = getSelectedItems()
            clearSelection()
            moveItems(selected, "", R.string.msg_removed_from_album)
            return
        }
        // Also drop prompt-based membership (images generated while the album was selected)
        val promptIds = getSelectedItems().map { it.promptId }.toSet()
        updateAlbums { list ->
            list.map { if (it.id == albumId) it.copy(members = it.members - keys, prompts = it.prompts - promptIds) else it }
        }
        clearSelection()
        viewModelScope.launch { _events.emit(GalleryEvent.ShowToast(R.string.msg_removed_from_album)) }
    }

    private fun albumItems(albumId: String): List<GalleryItem> {
        val album = AlbumRepository.albums.value.firstOrNull { it.id == albumId } ?: return emptyList()
        return repository.galleryItems.value.filter { album.contains(it.promptId, getItemKey(it)) }
    }

    /**
     * Load gallery - delegates to repository.
     * If repository already has data, this will return immediately with cached data.
     * The repository handles background refreshing.
     */
    fun loadGallery() {
        DebugLogger.d(TAG, "Loading gallery")
        // Repository already has data from background preload, no need to reload
        // unless explicitly refreshed by user
        if (!repository.hasData()) {
            repository.refresh()
        }
    }

    /**
     * Manual refresh triggered by user (pull-to-refresh).
     * Shows the refresh indicator and displays a Toast on completion.
     */
    fun manualRefresh() {
        DebugLogger.i(TAG, "Manual refresh")
        repository.manualRefresh { success ->
            viewModelScope.launch {
                if (success) {
                    DebugLogger.d(TAG, "Refresh successful")
                    _events.emit(GalleryEvent.ShowToast(R.string.msg_gallery_refresh_success))
                } else {
                    DebugLogger.w(TAG, "Refresh failed")
                    _events.emit(GalleryEvent.ShowToast(R.string.error_gallery_refresh))
                }
            }
        }
    }

    /**
     * Background refresh (silent, no indicator).
     * Used when returning from other screens or after external changes.
     */
    fun refresh() {
        DebugLogger.d(TAG, "Background refresh")
        repository.refresh()
    }

    /** Move an item to the trash. */
    fun deleteItem(item: GalleryItem) {
        DebugLogger.i(TAG, "Moving item to trash: ${item.promptId}")
        repository.moveToTrash(listOf(item))
        viewModelScope.launch { _events.emit(GalleryEvent.ShowToast(R.string.msg_moved_to_trash)) }
    }

    // Drag and drop ordering

    /**
     * Called when the user starts dragging an item. Switches to the custom order, starting
     * from the order currently shown so nothing jumps.
     */
    fun beginReorder() {
        val sort = _viewState.value.sortOrder
        if (sort == GallerySortOrder.CUSTOM) return
        val full = sort.apply(repository.galleryItems.value.distinctBy { getItemKey(it) })
            .map { GalleryLibraryStore.fileId(it) }
        val shownIds = full.toHashSet()
        repository.setOrder(full + repository.library.value.order.filter { it !in shownIds })
        setSortOrder(GallerySortOrder.CUSTOM)
    }

    /**
     * Move [fromKey] to where [toKey] is in the shown list. The order is kept for the
     * whole gallery, so items keep their relative order in every view.
     */
    fun moveItem(fromKey: String, toKey: String) {
        if (fromKey == toKey) return
        val shown = _uiState.value.items
        val fromIndex = shown.indexOfFirst { getItemKey(it) == fromKey }
        val toIndex = shown.indexOfFirst { getItemKey(it) == toKey }
        if (fromIndex < 0 || toIndex < 0) return

        val full = GallerySortOrder.applyCustomOrder(
            repository.galleryItems.value.distinctBy { getItemKey(it) },
            repository.library.value.order
        ).map { GalleryLibraryStore.fileId(it) }.toMutableList()
        val fromId = GalleryLibraryStore.fileId(shown[fromIndex])
        val toId = GalleryLibraryStore.fileId(shown[toIndex])
        full.remove(fromId)
        val target = full.indexOf(toId)
        if (target < 0) return
        // Moving down lands after the target, moving up lands before it
        full.add(if (fromIndex < toIndex) target + 1 else target, fromId)
        // Keep the saved position of items not shown now (e.g. in the trash)
        val shownIds = full.toHashSet()
        repository.setOrder(full + repository.library.value.order.filter { it !in shownIds })
    }

    fun saveImageToGallery(context: Context, item: GalleryItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val key = MediaCacheKey(item.promptId, item.filename)
                val bitmap = MediaCache.getBitmap(key)
                    ?: MediaCache.fetchImage(key, item.subfolder, item.type)

                if (bitmap == null) {
                    _events.emit(GalleryEvent.ShowToast(R.string.error_save_image))
                    return@withContext
                }

                try {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "ComfyMobile_${System.currentTimeMillis()}.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ComfyMobile")
                    }

                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                    uri?.let { outputUri ->
                        resolver.openOutputStream(outputUri)?.use { outputStream ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                        }
                    }

                    _events.emit(GalleryEvent.ShowToast(R.string.msg_image_saved_to_gallery))
                } catch (e: Exception) {
                    _events.emit(GalleryEvent.ShowToast(R.string.error_save_image))
                }
            }
        }
    }

    fun saveVideoToGallery(context: Context, item: GalleryItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val key = MediaCacheKey(item.promptId, item.filename)
                val videoBytes = MediaCache.getVideoBytes(key)
                    ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)

                if (videoBytes == null) {
                    _events.emit(GalleryEvent.ShowToast(R.string.error_save_video))
                    return@withContext
                }

                try {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "ComfyMobile_${System.currentTimeMillis()}.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ComfyMobile")
                    }

                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

                    uri?.let { outputUri ->
                        resolver.openOutputStream(outputUri)?.use { outputStream ->
                            outputStream.write(videoBytes)
                        }
                    }

                    _events.emit(GalleryEvent.ShowToast(R.string.msg_video_saved_to_gallery))
                } catch (e: Exception) {
                    _events.emit(GalleryEvent.ShowToast(R.string.error_save_video))
                }
            }
        }
    }

    fun fetchFullImage(item: GalleryItem, onResult: (Bitmap?) -> Unit) {
        viewModelScope.launch {
            val key = MediaCacheKey(item.promptId, item.filename)
            val bitmap = MediaCache.getBitmap(key)
                ?: MediaCache.fetchImage(key, item.subfolder, item.type)
            onResult(bitmap)
        }
    }

    fun fetchVideoUri(context: Context, item: GalleryItem, onResult: (Uri?) -> Unit) {
        viewModelScope.launch {
            val key = MediaCacheKey(item.promptId, item.filename)
            // Ensure bytes are cached
            MediaCache.getVideoBytes(key)
                ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)

            // Get URI from cache (creates temp file from cached bytes)
            val uri = MediaCache.getVideoUri(key, context)
            onResult(uri)
        }
    }

    fun shareImage(context: Context, item: GalleryItem) {
        viewModelScope.launch {
            val key = MediaCacheKey(item.promptId, item.filename)
            val bitmap = MediaCache.getBitmap(key)
                ?: MediaCache.fetchImage(key, item.subfolder, item.type)

            if (bitmap == null) {
                _events.emit(GalleryEvent.ShowToast(R.string.error_share_image))
                return@launch
            }

            try {
                // Save to cache for sharing
                val shareFile = File(context.cacheDir, "share_image.png")
                withContext(Dispatchers.IO) {
                    shareFile.outputStream().use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    }
                }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )

                val shareIntent = android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    type = "image/png"
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(
                    android.content.Intent.createChooser(
                        shareIntent,
                        context.getString(R.string.share_image)
                    )
                )
            } catch (e: Exception) {
                _events.emit(GalleryEvent.ShowToast(R.string.error_share_image))
            }
        }
    }

    fun shareVideo(context: Context, item: GalleryItem) {
        viewModelScope.launch {
            val key = MediaCacheKey(item.promptId, item.filename)
            val videoBytes = MediaCache.getVideoBytes(key)
                ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)

            if (videoBytes == null) {
                _events.emit(GalleryEvent.ShowToast(R.string.error_share_video))
                return@launch
            }

            try {
                val shareFile = File(context.cacheDir, "share_video.mp4")
                withContext(Dispatchers.IO) {
                    shareFile.writeBytes(videoBytes)
                }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )

                val shareIntent = android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    type = "video/mp4"
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(
                    android.content.Intent.createChooser(
                        shareIntent,
                        context.getString(R.string.share_video)
                    )
                )
            } catch (e: Exception) {
                _events.emit(GalleryEvent.ShowToast(R.string.error_share_video))
            }
        }
    }

    // Selection mode functions

    private fun getItemKey(item: GalleryItem): String = "${item.promptId}_${item.filename}"

    fun toggleSelection(item: GalleryItem) {
        val key = getItemKey(item)
        val currentSelected = _selectedItems.value.toMutableSet()

        if (currentSelected.contains(key)) {
            currentSelected.remove(key)
        } else {
            currentSelected.add(key)
        }

        _selectedItems.value = currentSelected
        _isSelectionMode.value = currentSelected.isNotEmpty()
    }

    fun isItemSelected(item: GalleryItem): Boolean {
        return _selectedItems.value.contains(getItemKey(item))
    }

    fun clearSelection() {
        _selectedItems.value = emptySet()
        _isSelectionMode.value = false
    }

    fun enterSelectionMode() {
        _isSelectionMode.value = true
    }

    /** Replace the whole selection (used by drag-to-select and select all). */
    fun setSelection(keys: Set<String>) {
        _selectedItems.value = keys
        _isSelectionMode.value = keys.isNotEmpty()
    }

    /** Select every item currently shown (respects the selected album). */
    fun selectAll() {
        setSelection(uiState.value.items.map { getItemKey(it) }.toSet())
    }

    /** Move the selected items to the trash. */
    fun deleteSelected() {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) return
        repository.moveToTrash(selectedItems)
        clearSelection()
        viewModelScope.launch { _events.emit(GalleryEvent.ShowToast(R.string.msg_moved_to_trash)) }
    }

    // Trash

    fun restoreSelected() {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) return
        repository.restoreFromTrash(selectedItems)
        clearSelection()
        viewModelScope.launch { _events.emit(GalleryEvent.ShowToast(R.string.msg_restored_from_trash)) }
    }

    fun deleteSelectedPermanently() {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) return
        clearSelection()
        viewModelScope.launch {
            repository.deletePermanently(selectedItems)
            _events.emit(GalleryEvent.ShowToast(R.string.msg_items_deleted_success))
        }
    }

    fun emptyTrash() {
        val trashed = repository.trashedItems.value
        if (trashed.isEmpty()) return
        clearSelection()
        viewModelScope.launch {
            repository.deletePermanently(trashed)
            _events.emit(GalleryEvent.ShowToast(R.string.msg_trash_emptied))
        }
    }

    fun getSelectedItems(): List<GalleryItem> {
        val selectedKeys = _selectedItems.value
        return _uiState.value.items.filter { getItemKey(it) in selectedKeys }
    }

    fun saveSelectedToGallery(context: Context) {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) return

        viewModelScope.launch {
            var successCount = 0
            var failCount = 0

            for (item in selectedItems) {
                val success = if (item.isVideo) {
                    saveVideoToGalleryInternal(context, item)
                } else {
                    saveImageToGalleryInternal(context, item)
                }
                if (success) successCount++ else failCount++
            }

            // Show result toast
            if (failCount == 0) {
                _events.emit(GalleryEvent.ShowToast(R.string.msg_items_saved_to_gallery))
            } else {
                _events.emit(GalleryEvent.ShowToast(R.string.msg_some_items_failed_to_save))
            }

            // Clear selection after save
            clearSelection()
        }
    }

    private suspend fun saveImageToGalleryInternal(context: Context, item: GalleryItem): Boolean {
        return withContext(Dispatchers.IO) {
            val key = MediaCacheKey(item.promptId, item.filename)
            val bitmap = MediaCache.getBitmap(key)
                ?: MediaCache.fetchImage(key, item.subfolder, item.type)

            if (bitmap == null) return@withContext false

            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "ComfyMobile_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ComfyMobile")
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let { outputUri ->
                    resolver.openOutputStream(outputUri)?.use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    }
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private suspend fun saveVideoToGalleryInternal(context: Context, item: GalleryItem): Boolean {
        return withContext(Dispatchers.IO) {
            val key = MediaCacheKey(item.promptId, item.filename)
            val videoBytes = MediaCache.getVideoBytes(key)
                ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)

            if (videoBytes == null) return@withContext false

            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "ComfyMobile_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ComfyMobile")
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let { outputUri ->
                    resolver.openOutputStream(outputUri)?.use { outputStream ->
                        outputStream.write(videoBytes)
                    }
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    fun shareSelected(context: Context) {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) return

        viewModelScope.launch {
            try {
                val uris = mutableListOf<Uri>()

                for ((index, item) in selectedItems.withIndex()) {
                    val uri = if (item.isVideo) {
                        getVideoShareUri(context, item, index)
                    } else {
                        getImageShareUri(context, item, index)
                    }
                    uri?.let { uris.add(it) }
                }

                if (uris.isEmpty()) {
                    _events.emit(GalleryEvent.ShowToast(R.string.error_share_items))
                    return@launch
                }

                val shareIntent = android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND_MULTIPLE
                    putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                    type = if (selectedItems.all { it.isVideo }) "video/*"
                           else if (selectedItems.none { it.isVideo }) "image/*"
                           else "*/*"
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(
                    android.content.Intent.createChooser(
                        shareIntent,
                        context.getString(R.string.button_share)
                    )
                )

                // Clear selection after share
                clearSelection()
            } catch (e: Exception) {
                _events.emit(GalleryEvent.ShowToast(R.string.error_share_items))
            }
        }
    }

    private suspend fun getImageShareUri(context: Context, item: GalleryItem, index: Int): Uri? {
        return withContext(Dispatchers.IO) {
            val key = MediaCacheKey(item.promptId, item.filename)
            val bitmap = MediaCache.getBitmap(key)
                ?: MediaCache.fetchImage(key, item.subfolder, item.type)

            if (bitmap == null) return@withContext null

            try {
                val shareFile = File(context.cacheDir, "share_image_$index.png")
                shareFile.outputStream().use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }

                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun getVideoShareUri(context: Context, item: GalleryItem, index: Int): Uri? {
        return withContext(Dispatchers.IO) {
            val key = MediaCacheKey(item.promptId, item.filename)
            val videoBytes = MediaCache.getVideoBytes(key)
                ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)

            if (videoBytes == null) return@withContext null

            try {
                val shareFile = File(context.cacheDir, "share_video_$index.mp4")
                shareFile.writeBytes(videoBytes)

                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
