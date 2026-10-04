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
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.GalleryAlbum
import sh.hnet.comfychair.storage.GalleryAlbumStore
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
    val index: Int = 0 // For sorting
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

/** Gallery tabs shown in the app bar. */
enum class GalleryTab { PHOTOS, ALBUMS, TRASH }

/**
 * UI state for the Gallery screen
 */
data class GalleryUiState(
    val tab: GalleryTab = GalleryTab.PHOTOS,
    /**
     * Items shown in the grid: Photos = items not in any album, Albums = the open album
     * (empty while the album list is shown), Trash = deleted items
     */
    val items: List<GalleryItem> = emptyList(),
    /** Number of items in the Photos tab */
    val totalCount: Int = 0,
    val trashCount: Int = 0,
    val viewMode: GalleryViewMode = GalleryViewMode.GRID_2,
    /** User-made albums */
    val albums: List<GalleryAlbum> = emptyList(),
    /** One album per subfolder of the output folder (read only) */
    val folderAlbums: List<GalleryAlbum> = emptyList(),
    /** Open album in the Albums tab; null = album list */
    val selectedAlbumId: String? = null,
    /** Number of existing gallery items in each album (albumId -> count) */
    val albumCounts: Map<String, Int> = emptyMap(),
    /** First item of each album, used as its cover */
    val albumCovers: Map<String, GalleryItem> = emptyMap(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val selectedItems: Set<String> = emptySet(), // Set of "${promptId}_${filename}" keys
    val isSelectionMode: Boolean = false,
    /** Files are being moved between album folders */
    val isMoving: Boolean = false
) {
    val selectedAlbum: GalleryAlbum?
        get() = selectedAlbumId?.let { id -> (albums + folderAlbums).firstOrNull { it.id == id } }

    /** Items can be reordered by drag and drop (not in the trash or the album list). */
    val canReorder: Boolean
        get() = tab == GalleryTab.PHOTOS || (tab == GalleryTab.ALBUMS && selectedAlbumId != null)
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
        private const val FOLDER_ALBUM_PREFIX = "folder:"

        fun isFolderAlbum(albumId: String) = albumId.startsWith(FOLDER_ALBUM_PREFIX)
    }

    // State
    private val repository = GalleryRepository.getInstance()

    // Selection state (local to this ViewModel)
    private val _selectedItems = MutableStateFlow<Set<String>>(emptySet())
    private val _isSelectionMode = MutableStateFlow(false)

    // View mode and albums
    private data class ViewState(
        val tab: GalleryTab = GalleryTab.PHOTOS,
        val viewMode: GalleryViewMode = GalleryViewMode.GRID_2,
        val albums: List<GalleryAlbum> = emptyList(),
        val selectedAlbumId: String? = null,
        val isMoving: Boolean = false
    )
    private val _viewState = MutableStateFlow(ViewState())
    private var appContext: Context? = null
    private var albumsServerId: String? = null

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
                    repository.trashedItems,
                    repository.library,
                    repository.serverFolders
                ) { items, trashed, library, serverFolders ->
                    RepoState(items, trashed, library.order, library.folders + serverFolders)
                },
                combine(repository.isLoading, repository.isManualRefreshing) { loading, refreshing ->
                    loading to refreshing
                },
                _selectedItems,
                _isSelectionMode,
                _viewState
            ) { repo, (isLoading, isManualRefreshing), selectedItems, isSelectionMode, view ->
                buildUiState(repo, isLoading, isManualRefreshing, selectedItems, isSelectionMode, view)
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    private data class RepoState(
        val items: List<GalleryItem>,
        val trashed: List<GalleryItem>,
        val order: List<String>,
        /** Album folders known besides the ones that have items (created in the app / on the server) */
        val folders: Set<String>
    )

    private fun buildUiState(
        repo: RepoState,
        isLoading: Boolean,
        isManualRefreshing: Boolean,
        selectedItems: Set<String>,
        isSelectionMode: Boolean,
        view: ViewState
    ): GalleryUiState {
        // Grid keys must be unique
        val items = applyOrder(repo.items.distinctBy { getItemKey(it) }, repo.order)
        val folderAlbums = folderAlbumsOf(items, repo.folders)
        val allAlbums = view.albums + folderAlbums
        val album = allAlbums.firstOrNull { it.id == view.selectedAlbumId }
        // Photos shows only items not yet sorted into any album
        val inAnyAlbum = allAlbums.flatMapTo(HashSet()) { it.members }
        val unsorted = items.filter { getItemKey(it) !in inAnyAlbum }
        val albumItems = allAlbums.associate { a -> a.id to items.filter { getItemKey(it) in a.members } }
        val shown = when (view.tab) {
            GalleryTab.PHOTOS -> unsorted
            GalleryTab.ALBUMS -> album?.let { albumItems[it.id] } ?: emptyList()
            GalleryTab.TRASH -> repo.trashed.distinctBy { getItemKey(it) }
        }
        return GalleryUiState(
            tab = view.tab,
            items = shown,
            totalCount = unsorted.size,
            trashCount = repo.trashed.size,
            viewMode = view.viewMode,
            albums = view.albums,
            folderAlbums = folderAlbums,
            selectedAlbumId = album?.id,
            albumCounts = albumItems.mapValues { it.value.size },
            albumCovers = albumItems.mapNotNull { (id, list) -> list.firstOrNull()?.let { id to it } }.toMap(),
            isLoading = isLoading,
            isRefreshing = isManualRefreshing, // Only show indicator for manual refresh
            selectedItems = selectedItems,
            isSelectionMode = isSelectionMode,
            isMoving = view.isMoving
        )
    }

    /** Items in the saved custom order; items not ordered yet (new ones) stay on top. */
    private fun applyOrder(items: List<GalleryItem>, order: List<String>): List<GalleryItem> {
        if (order.isEmpty()) return items
        val position = HashMap<String, Int>(order.size * 2)
        order.forEachIndexed { i, id -> position.putIfAbsent(id, i) }
        val (ordered, fresh) = items.partition { GalleryLibraryStore.fileId(it) in position }
        return fresh + ordered.sortedBy { position[GalleryLibraryStore.fileId(it)] }
    }

    /** One album per output subfolder (also empty ones in [extraFolders]), with its items as members. */
    private fun folderAlbumsOf(items: List<GalleryItem>, extraFolders: Set<String>): List<GalleryAlbum> {
        val byFolder = items.filter { it.type == "output" && it.subfolder.isNotEmpty() }.groupBy { it.subfolder }
        return (byFolder.keys + extraFolders.filter { it.isNotBlank() })
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .map { subfolder ->
                GalleryAlbum(
                    id = FOLDER_ALBUM_PREFIX + subfolder,
                    name = subfolder,
                    members = byFolder[subfolder].orEmpty().mapTo(HashSet()) { getItemKey(it) }
                )
            }
    }

    private fun folderOf(albumId: String) = albumId.removePrefix(FOLDER_ALBUM_PREFIX)

    /** A name usable as a folder name on Windows, macOS and Linux. */
    private fun folderName(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").trim('.', ' ')

    /**
     * Move items into an album folder ("" = output root) in the background and report the result.
     */
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

    fun initialize(context: Context) {
        // MediaCache is used for all fetch operations
        // Repository is already initialized by GenerationViewModel
        appContext = context.applicationContext
        val mode = AppSettings.getGalleryViewMode(context)
            ?.let { name -> GalleryViewMode.entries.firstOrNull { it.name == name } }
            ?: GalleryViewMode.GRID_2
        _viewState.value = _viewState.value.copy(viewMode = mode)
        reloadAlbums()
    }

    // View mode

    fun setViewMode(mode: GalleryViewMode) {
        _viewState.value = _viewState.value.copy(viewMode = mode)
        appContext?.let { AppSettings.setGalleryViewMode(it, mode.name) }
    }

    // Albums

    /** (Re)load albums for the current server. Safe to call often. */
    fun reloadAlbums() {
        val ctx = appContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        if (serverId == albumsServerId) return
        albumsServerId = serverId
        viewModelScope.launch {
            val albums = withContext(Dispatchers.IO) { GalleryAlbumStore.load(ctx, serverId) }
            _viewState.value = _viewState.value.copy(albums = albums, selectedAlbumId = null)
        }
    }

    private fun updateAlbums(transform: (List<GalleryAlbum>) -> List<GalleryAlbum>) {
        val albums = transform(_viewState.value.albums)
        val selected = _viewState.value.selectedAlbumId
            ?.takeIf { id -> isFolderAlbum(id) || albums.any { it.id == id } }
        _viewState.value = _viewState.value.copy(albums = albums, selectedAlbumId = selected)
        val ctx = appContext ?: return
        val serverId = albumsServerId ?: return
        viewModelScope.launch(Dispatchers.IO) { GalleryAlbumStore.save(ctx, serverId, albums) }
    }

    fun selectTab(tab: GalleryTab) {
        clearSelection()
        _viewState.value = _viewState.value.copy(tab = tab, selectedAlbumId = null)
    }

    /** Open an album (null = back to the album list). */
    fun selectAlbum(albumId: String?) {
        clearSelection()
        _viewState.value = _viewState.value.copy(tab = GalleryTab.ALBUMS, selectedAlbumId = albumId)
    }

    /**
     * Create an album: a subfolder of the output folder. If items are selected, their
     * files are moved into it.
     */
    fun createAlbum(name: String) {
        val folder = folderName(name)
        if (folder.isEmpty()) return
        val selected = getSelectedItems()
        clearSelection()
        viewModelScope.launch { repository.createFolder(folder) }
        if (selected.isNotEmpty()) moveItems(selected, folder, R.string.msg_moved_to_album)
    }

    /** Rename an album; for a folder album its files are moved to the renamed folder. */
    fun renameAlbum(albumId: String, name: String) {
        if (isFolderAlbum(albumId)) {
            val newFolder = folderName(name)
            val oldFolder = folderOf(albumId)
            if (newFolder.isEmpty() || newFolder == oldFolder) return
            val members = albumItems(albumId)
            viewModelScope.launch { repository.createFolder(newFolder) }
            if (_viewState.value.selectedAlbumId == albumId) {
                _viewState.value = _viewState.value.copy(selectedAlbumId = FOLDER_ALBUM_PREFIX + newFolder)
            }
            moveItems(members, newFolder, R.string.msg_album_renamed) { repository.removeFolder(oldFolder) }
            return
        }
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        updateAlbums { list -> list.map { if (it.id == albumId) it.copy(name = trimmed) else it } }
    }

    /** Deletes only the album; the items stay in the gallery (a folder album's files go back to the output root). */
    fun deleteAlbum(albumId: String) {
        if (isFolderAlbum(albumId)) {
            val folder = folderOf(albumId)
            if (_viewState.value.selectedAlbumId == albumId) {
                _viewState.value = _viewState.value.copy(selectedAlbumId = null)
            }
            moveItems(albumItems(albumId), "", R.string.msg_album_deleted) { repository.removeFolder(folder) }
            return
        }
        updateAlbums { list -> list.filterNot { it.id == albumId } }
    }

    /** Add the selection to an album; for a folder album the files are moved into its folder. */
    fun addSelectedToAlbum(albumId: String) {
        val keys = _selectedItems.value
        if (keys.isEmpty()) return
        if (isFolderAlbum(albumId)) {
            val selected = getSelectedItems()
            clearSelection()
            moveItems(selected, folderOf(albumId), R.string.msg_moved_to_album)
            return
        }
        updateAlbums { list -> list.map { if (it.id == albumId) it.copy(members = it.members + keys) else it } }
        clearSelection()
        viewModelScope.launch { _events.emit(GalleryEvent.ShowToast(R.string.msg_added_to_album)) }
    }

    /** Remove the selection from an album; for a folder album the files go back to the output root. */
    fun removeSelectedFromAlbum(albumId: String) {
        val keys = _selectedItems.value
        if (keys.isEmpty()) return
        if (isFolderAlbum(albumId)) {
            val selected = getSelectedItems()
            clearSelection()
            moveItems(selected, "", R.string.msg_removed_from_album)
            return
        }
        updateAlbums { list -> list.map { if (it.id == albumId) it.copy(members = it.members - keys) else it } }
        clearSelection()
        viewModelScope.launch { _events.emit(GalleryEvent.ShowToast(R.string.msg_removed_from_album)) }
    }

    private fun albumItems(albumId: String): List<GalleryItem> {
        val album = _uiState.value.let { st -> (st.albums + st.folderAlbums).firstOrNull { it.id == albumId } }
            ?: return emptyList()
        return repository.galleryItems.value.filter { getItemKey(it) in album.members }
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
     * Move [fromKey] to where [toKey] is in the shown list. The order is kept for the
     * whole gallery, so items keep their relative order in every view.
     */
    fun moveItem(fromKey: String, toKey: String) {
        if (fromKey == toKey) return
        val shown = _uiState.value.items
        val fromIndex = shown.indexOfFirst { getItemKey(it) == fromKey }
        val toIndex = shown.indexOfFirst { getItemKey(it) == toKey }
        if (fromIndex < 0 || toIndex < 0) return

        val full = applyOrder(repository.galleryItems.value.distinctBy { getItemKey(it) }, repository.library.value.order)
            .map { GalleryLibraryStore.fileId(it) }
            .toMutableList()
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
