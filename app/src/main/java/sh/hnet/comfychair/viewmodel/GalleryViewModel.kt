package sh.hnet.comfychair.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.gallery.GalleryItem
import sh.hnet.comfychair.gallery.MediaExport
import sh.hnet.comfychair.repository.AlbumRepository
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.util.DebugLogger

/**
 * ViewModel for the Gallery screen.
 * Items come from [GalleryRepository], albums from [AlbumRepository]; this holds the view
 * settings and the selection, and turns user actions into repository calls.
 */
class GalleryViewModel : ViewModel() {

    companion object {
        private const val TAG = "Gallery"
    }

    private val repository = GalleryRepository.getInstance()
    private var appContext: Context? = null

    private val view = MutableStateFlow(GalleryViewState())

    val uiState: StateFlow<GalleryUiState> = combine(
        combine(repository.galleryItems, repository.trashedItems, repository.library) { items, trashed, library ->
            Triple(items, trashed, library)
        },
        combine(AlbumRepository.albums, AlbumRepository.currentAlbumId) { albums, id -> albums to id },
        combine(repository.isLoading, repository.isManualRefreshing) { loading, refreshing -> loading to refreshing },
        view
    ) { (items, trashed, library), (albums, albumId), (loading, refreshing), view ->
        buildGalleryUiState(GallerySource(items, trashed, library, albums, albumId, loading, refreshing), view)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GalleryUiState())

    private val _events = MutableSharedFlow<GalleryEvent>()
    val events: SharedFlow<GalleryEvent> = _events.asSharedFlow()

    private fun toast(messageResId: Int) {
        viewModelScope.launch { _events.emit(GalleryEvent.ShowToast(messageResId)) }
    }

    fun initialize(context: Context) {
        // Repository is already initialized by MainContainerActivity
        appContext = context.applicationContext
        AlbumRepository.ensureLoaded(context)
        view.update {
            it.copy(
                viewMode = AppSettings.getGalleryViewMode(context).toEnum(GalleryViewMode.GRID_2),
                sortOrder = AppSettings.getGallerySortOrder(context).toEnum(GallerySortOrder.NEWEST),
                albumSortOrder = AppSettings.getAlbumSortOrder(context).toEnum(AlbumSortOrder.NAME),
                // Open on the album selected for generation, if any
                section = if (AlbumRepository.currentAlbumId.value != null) GallerySection.ALBUMS else GallerySection.PHOTOS
            )
        }
    }

    private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
        enumValues<T>().firstOrNull { it.name == this } ?: default

    // Loading

    /**
     * Load gallery - delegates to repository.
     * If repository already has data (from the background preload), nothing is reloaded.
     */
    fun loadGallery() {
        DebugLogger.d(TAG, "Loading gallery")
        if (!repository.hasData()) repository.refresh()
    }

    /** (Re)load albums for the current server. Safe to call often. */
    fun reloadAlbums() {
        appContext?.let { AlbumRepository.ensureLoaded(it) }
    }

    /**
     * Manual refresh triggered by user (pull-to-refresh).
     * Shows the refresh indicator and displays a Toast on completion.
     */
    fun manualRefresh() {
        DebugLogger.i(TAG, "Manual refresh")
        repository.manualRefresh { success ->
            DebugLogger.d(TAG, if (success) "Refresh successful" else "Refresh failed")
            toast(if (success) R.string.msg_gallery_refresh_success else R.string.error_gallery_refresh)
        }
    }

    // View settings

    fun setSection(section: GallerySection) {
        clearSelection()
        // Leaving an album (to Photos or the trash) deselects it, also for generation
        if (section != GallerySection.ALBUMS) AlbumRepository.select(null)
        view.update { it.copy(section = section) }
    }

    /** Open an album (null = back to the album list). Also selects it for generation. */
    fun selectAlbum(albumId: String?) {
        clearSelection()
        AlbumRepository.select(albumId)
        if (albumId != null) view.update { it.copy(section = GallerySection.ALBUMS) }
    }

    fun setViewMode(mode: GalleryViewMode) {
        view.update { it.copy(viewMode = mode) }
        appContext?.let { AppSettings.setGalleryViewMode(it, mode.name) }
    }

    /** Sort the items shown: in an open album only that album, else the gallery. */
    fun setSortOrder(order: GallerySortOrder) {
        val album = uiState.value.selectedAlbum?.takeIf { uiState.value.isInAlbum }
        if (album != null) {
            AlbumRepository.setItemSort(album.id, order.name)
            return
        }
        view.update { it.copy(sortOrder = order) }
        appContext?.let { AppSettings.setGallerySortOrder(it, order.name) }
    }

    fun setAlbumSortOrder(order: AlbumSortOrder) {
        view.update { it.copy(albumSortOrder = order) }
        appContext?.let { AppSettings.setAlbumSortOrder(it, order.name) }
    }

    // Drag and drop ordering

    /**
     * Called when the user starts dragging an item. Switches to the custom order, starting
     * from the order currently shown so nothing jumps.
     */
    fun beginReorder() {
        val sort = uiState.value.sortOrder
        if (sort == GallerySortOrder.CUSTOM) return
        saveOrder(sort.apply(repository.galleryItems.value.distinctBy { it.key }).map { it.libraryId })
        setSortOrder(GallerySortOrder.CUSTOM)
    }

    /**
     * Move [fromKey] to where [toKey] is in the shown list. The order is kept for the
     * whole gallery, so items keep their relative order in every view.
     */
    fun moveItem(fromKey: String, toKey: String) {
        if (fromKey == toKey) return
        val shown = uiState.value.items
        val fromIndex = shown.indexOfFirst { it.key == fromKey }
        val toIndex = shown.indexOfFirst { it.key == toKey }
        if (fromIndex < 0 || toIndex < 0) return

        val full = GallerySortOrder.applyCustomOrder(
            repository.galleryItems.value.distinctBy { it.key },
            repository.library.value.order
        ).map { it.libraryId }.toMutableList()
        val fromId = shown[fromIndex].libraryId
        val toId = shown[toIndex].libraryId
        full.remove(fromId)
        val target = full.indexOf(toId)
        if (target < 0) return
        // Moving down lands after the target, moving up lands before it
        full.add(if (fromIndex < toIndex) target + 1 else target, fromId)
        saveOrder(full)
    }

    /** Save [order] for the gallery, keeping the saved position of items not in it (e.g. in the trash). */
    private fun saveOrder(order: List<String>) {
        val ids = order.toHashSet()
        repository.setOrder(order + repository.library.value.order.filter { it !in ids })
    }

    /** Dragging an album switches to the custom order, starting from the order shown. */
    fun beginAlbumReorder() {
        if (view.value.albumSortOrder == AlbumSortOrder.CUSTOM) return
        AlbumRepository.setOrder(uiState.value.albums.map { it.id })
        setAlbumSortOrder(AlbumSortOrder.CUSTOM)
    }

    /** Move album [fromId] to where [toId] is in the album list. */
    fun moveAlbum(fromId: String, toId: String) {
        if (fromId == toId) return
        val ids = uiState.value.albums.map { it.id }.toMutableList()
        val from = ids.indexOf(fromId)
        val to = ids.indexOf(toId)
        if (from < 0 || to < 0) return
        ids.removeAt(from)
        ids.add(to, fromId)
        AlbumRepository.setOrder(ids)
    }

    // Selection

    /** The selected items among those shown */
    private fun selectedItems(): List<GalleryItem> {
        val keys = view.value.selection
        return uiState.value.items.filter { it.key in keys }
    }

    fun toggleSelection(item: GalleryItem) {
        val keys = view.value.selection
        setSelection(if (item.key in keys) keys - item.key else keys + item.key)
    }

    /** Replace the whole selection (used by drag-to-select and select all). */
    fun setSelection(keys: Set<String>) {
        view.update { it.copy(selection = keys, isSelectionMode = keys.isNotEmpty()) }
    }

    /** Select every item currently shown (respects the selected album). */
    fun selectAll() {
        setSelection(uiState.value.items.mapTo(HashSet()) { it.key })
    }

    fun clearSelection() = setSelection(emptySet())

    fun enterSelectionMode() {
        view.update { it.copy(isSelectionMode = true) }
    }

    /** Take the selected items and leave selection mode. */
    private fun takeSelection(): List<GalleryItem> = selectedItems().also { clearSelection() }

    // Albums

    /**
     * Run a file move with the progress bar shown, then report it.
     * [move] returns the number of items that could not be moved, or null if nothing was moved.
     */
    private fun runMove(successMessage: Int, move: suspend () -> Int?) {
        viewModelScope.launch {
            view.update { it.copy(isMoving = true) }
            try {
                val failed = move() ?: return@launch
                _events.emit(GalleryEvent.ShowToast(if (failed == 0) successMessage else R.string.msg_some_items_failed_to_move))
            } finally {
                view.update { it.copy(isMoving = false) }
            }
        }
    }

    /** Like [runMove] for moving a whole album folder, which an older extension can't do. */
    private fun runFolderMove(successMessage: Int, move: suspend () -> Int?) = runMove(successMessage) {
        move()?.also {
            // Other files keep the old folder on the PC
            if (repository.isFileOpsOutdated) _events.emit(GalleryEvent.ShowToast(R.string.msg_extension_outdated))
        }
    }

    /**
     * Create an album: a subfolder of ComfyUI's output folder. If items are selected,
     * their files are moved into it.
     */
    fun createAlbum(name: String) {
        if (AlbumRepository.folderName(name).isEmpty()) return
        val selected = takeSelection()
        if (selected.isEmpty()) viewModelScope.launch { AlbumRepository.create(name, selected) }
        else runMove(R.string.msg_moved_to_album) { AlbumRepository.create(name, selected) }
    }

    /** Add the selection to an album; for a folder album the files are moved into its folder. */
    fun addSelectedToAlbum(albumId: String) {
        val selected = takeSelection()
        if (selected.isEmpty()) return
        val message = if (AlbumRepository.isFolder(albumId)) R.string.msg_moved_to_album else R.string.msg_added_to_album
        runMove(message) { AlbumRepository.addItems(albumId, selected) }
    }

    /** Remove the selection from an album; for a folder album the files go back to the output folder itself. */
    fun removeSelectedFromAlbum(albumId: String) {
        val selected = takeSelection()
        if (selected.isEmpty()) return
        runMove(R.string.msg_removed_from_album) { AlbumRepository.removeItems(albumId, selected) }
    }

    /** Use the (one) selected item as the cover of [albumId]. */
    fun setSelectedAsCover(albumId: String) {
        val item = selectedItems().singleOrNull() ?: return
        AlbumRepository.setCover(albumId, item)
        clearSelection()
        toast(R.string.msg_album_cover_set)
    }

    /** Rename an album; for a folder album its files are moved into the renamed folder. */
    fun renameAlbum(albumId: String, name: String) =
        runFolderMove(R.string.msg_album_renamed) { AlbumRepository.rename(albumId, name) }

    /**
     * Deletes only the album; the items stay in the gallery
     * (a folder album's files go back to the output folder itself).
     */
    fun deleteAlbum(albumId: String) =
        runFolderMove(R.string.msg_album_deleted) { AlbumRepository.delete(albumId) }

    /**
     * Hide an album from the album list, or show it again. Hiding the open album goes back to
     * the album list (unless hidden albums are shown).
     */
    fun setAlbumHidden(albumId: String, hidden: Boolean) {
        AlbumRepository.setHidden(albumId, hidden)
        if (hidden && !view.value.showHiddenAlbums && AlbumRepository.currentAlbumId.value == albumId) selectAlbum(null)
        toast(if (hidden) R.string.msg_album_hidden else R.string.msg_album_unhidden)
    }

    /** Show hidden albums in the album list too (until the gallery is closed). */
    fun setShowHiddenAlbums(show: Boolean) {
        view.update { it.copy(showHiddenAlbums = show) }
    }

    // Duplicate cleanup

    /** Look for images in the output folder itself that are also in an album; asks to confirm. */
    fun findDuplicates() {
        viewModelScope.launch {
            view.update { it.copy(isMoving = true) }
            val (result, found) = try {
                repository.findRootDuplicates()
            } finally {
                view.update { it.copy(isMoving = false) }
            }
            when {
                result is ComfyUIClient.DuplicatesResult.NotInstalled -> toast(R.string.msg_duplicates_need_extension)
                result is ComfyUIClient.DuplicatesResult.Failed -> toast(R.string.msg_duplicates_failed)
                found.isEmpty() -> toast(R.string.msg_no_duplicates)
                else -> view.update { it.copy(rootDuplicates = found) }
            }
        }
    }

    fun dismissDuplicates() {
        view.update { it.copy(rootDuplicates = null) }
    }

    /** Delete the root copies found by [findDuplicates]; the album copies stay. */
    fun deleteDuplicates() {
        val duplicates = view.value.rootDuplicates ?: return
        view.update { it.copy(rootDuplicates = null, isMoving = true) }
        viewModelScope.launch {
            try {
                val deleted = repository.deleteRootDuplicates(duplicates)
                toast(if (deleted == duplicates.size) R.string.msg_duplicates_deleted else R.string.msg_some_items_failed_to_delete)
            } finally {
                view.update { it.copy(isMoving = false) }
            }
        }
    }

    // Trash

    /** Move the selected items to the trash (their files into output/_trash, with the extension). */
    fun deleteSelected() {
        val selected = takeSelection()
        if (selected.isEmpty()) return
        runMove(R.string.msg_moved_to_trash) { repository.moveToTrash(selected) }
    }

    /** Restore the selected items (their files back to their folders). */
    fun restoreSelected() {
        val selected = takeSelection()
        if (selected.isEmpty()) return
        runMove(R.string.msg_restored_from_trash) { repository.restoreFromTrash(selected) }
    }

    fun deleteSelectedPermanently() {
        val selected = takeSelection()
        if (selected.isEmpty()) return
        viewModelScope.launch {
            repository.deletePermanently(selected)
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

    // Save and share

    fun saveSelectedToGallery(context: Context) {
        val selected = selectedItems()
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val failed = selected.count { !MediaExport.saveItem(context, it) }
            _events.emit(GalleryEvent.ShowToast(
                if (failed == 0) R.string.msg_items_saved_to_gallery else R.string.msg_some_items_failed_to_save
            ))
            clearSelection()
        }
    }

    fun shareSelected(context: Context) {
        val selected = selectedItems()
        if (selected.isEmpty()) return
        viewModelScope.launch {
            try {
                val uris = selected.mapIndexedNotNull { index, item -> MediaExport.itemShareUri(context, item, index) }
                if (uris.isEmpty()) {
                    _events.emit(GalleryEvent.ShowToast(R.string.error_share_items))
                    return@launch
                }
                val mimeType = when {
                    selected.all { it.isVideo } -> "video/*"
                    selected.none { it.isVideo } -> "image/*"
                    else -> "*/*"
                }
                MediaExport.share(context, uris, mimeType, context.getString(R.string.button_share))
                clearSelection()
            } catch (e: Exception) {
                _events.emit(GalleryEvent.ShowToast(R.string.error_share_items))
            }
        }
    }
}
