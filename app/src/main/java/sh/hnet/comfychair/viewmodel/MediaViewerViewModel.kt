package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.gallery.GalleryItem
import sh.hnet.comfychair.gallery.MediaExport
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.storage.LocalGalleryStore
import sh.hnet.comfychair.util.GenerationMetadata
import sh.hnet.comfychair.util.MetadataParser
import sh.hnet.comfychair.util.Mp4MetadataExtractor
import sh.hnet.comfychair.util.PngMetadataExtractor
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/**
 * Viewer mode
 */
enum class ViewerMode {
    GALLERY,  // Launched from gallery, supports navigation
    SINGLE    // Launched from generation screen, single item only
}

/**
 * Represents an item in the media viewer
 */
@Immutable
data class MediaViewerItem(
    val promptId: String,
    val filename: String,
    val subfolder: String,
    val type: String,
    val isVideo: Boolean,
    val index: Int = 0
) {
    /** Not a gallery item: an image handed over from a generation screen's preview */
    val isPreview: Boolean
        get() = promptId == PREVIEW_PROMPT_ID

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("promptId", promptId)
            put("filename", filename)
            put("subfolder", subfolder)
            put("type", type)
            put("isVideo", isVideo)
            put("index", index)
        }
    }

    companion object {
        const val PREVIEW_PROMPT_ID = "__preview__"

        fun fromJson(json: JSONObject): MediaViewerItem {
            return MediaViewerItem(
                promptId = json.optString("promptId", ""),
                filename = json.optString("filename", ""),
                subfolder = json.optString("subfolder", ""),
                type = json.optString("type", "output"),
                isVideo = json.optBoolean("isVideo", false),
                index = json.optInt("index", 0)
            )
        }

        fun listToJson(items: List<MediaViewerItem>): String {
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun listFromJson(json: String): List<MediaViewerItem> {
            return try {
                val array = JSONArray(json)
                (0 until array.length()).map { i ->
                    fromJson(array.getJSONObject(i))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

/**
 * UI state for the media viewer.
 * Caching is handled by MediaCache singleton - no local cache maps needed.
 */
@Stable
data class MediaViewerUiState(
    val mode: ViewerMode = ViewerMode.GALLERY,
    val items: List<MediaViewerItem> = emptyList(),
    val currentIndex: Int = 0,
    val isUiVisible: Boolean = true,
    val isLoading: Boolean = false,
    val currentBitmap: Bitmap? = null,
    val currentVideoUri: Uri? = null,
    val isSlideshowPlaying: Boolean = false
) {
    val currentItem: MediaViewerItem?
        get() = items.getOrNull(currentIndex)

    val totalCount: Int
        get() = items.size

    val canNavigateBack: Boolean
        get() = mode == ViewerMode.GALLERY && currentIndex > 0

    val canNavigateForward: Boolean
        get() = mode == ViewerMode.GALLERY && currentIndex < items.size - 1
}

/**
 * Events emitted by media viewer operations
 */
sealed class MediaViewerEvent {
    data class ShowToast(val messageResId: Int) : MediaViewerEvent()
    data object ItemDeleted : MediaViewerEvent()
    data object Close : MediaViewerEvent()
    /** A request was posted to ViewerHandoff: go back to the generation screens */
    data object OpenGeneration : MediaViewerEvent()
}

/**
 * ViewModel for the MediaViewer screen
 */
class MediaViewerViewModel : ViewModel() {

    private var applicationContext: Context? = null

    private val _uiState = MutableStateFlow(MediaViewerUiState())
    val uiState: StateFlow<MediaViewerUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MediaViewerEvent>()
    val events: SharedFlow<MediaViewerEvent> = _events.asSharedFlow()

    // Metadata state
    // Keyed by item ("promptId_filename"), not by position, so it stays right after deletions
    private val cachedMetadata = java.util.concurrent.ConcurrentHashMap<String, MetadataHolder>()
    private val _currentMetadata = MutableStateFlow<GenerationMetadata?>(null)
    val currentMetadata: StateFlow<GenerationMetadata?> = _currentMetadata.asStateFlow()

    private val _isLoadingMetadata = MutableStateFlow(false)
    val isLoadingMetadata: StateFlow<Boolean> = _isLoadingMetadata.asStateFlow()

    // Opened from the gallery's trash: deleting removes items for good
    private var isTrash = false

    // Track whether any items were deleted during this session
    private val _hasDeletedItems = MutableStateFlow(false)
    val hasDeletedItems: StateFlow<Boolean> = _hasDeletedItems.asStateFlow()

    fun initialize(
        context: Context,
        hostname: String,
        port: Int,
        mode: ViewerMode,
        items: List<MediaViewerItem>,
        initialIndex: Int,
        singleBitmap: Bitmap? = null,
        singleVideoUri: Uri? = null,
        startSlideshow: Boolean = false,
        isTrash: Boolean = false
    ) {
        applicationContext = context.applicationContext
        this.isTrash = isTrash

        val slideshow = startSlideshow && mode == ViewerMode.GALLERY && items.size > 1
        _uiState.value = MediaViewerUiState(
            mode = mode,
            items = items,
            currentIndex = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            currentBitmap = singleBitmap,
            currentVideoUri = singleVideoUri,
            isLoading = mode == ViewerMode.GALLERY && items.isNotEmpty(),
            isSlideshowPlaying = slideshow,
            isUiVisible = !slideshow
        )

        // For gallery mode, set up priorities and load current item
        if (mode == ViewerMode.GALLERY && items.isNotEmpty()) {
            val currentIdx = _uiState.value.currentIndex
            updateCachePrioritiesForIndex(currentIdx)
            triggerPrefetchForIndex(currentIdx)
            preloadMetadataForIndices()
            loadCurrentItem()
        }
    }

    fun toggleUiVisibility() {
        val state = _uiState.value
        if (state.isSlideshowPlaying) {
            // Tapping during a slideshow stops it and brings the controls back
            _uiState.value = state.copy(isSlideshowPlaying = false, isUiVisible = true)
            return
        }
        _uiState.value = state.copy(isUiVisible = !state.isUiVisible)
    }

    /** Send the current picture's prompt to Text to Image. */
    fun reusePrompt() {
        val item = _uiState.value.currentItem ?: return
        viewModelScope.launch {
            val metadata = cachedMetadata[item.metadataKey()]?.metadata
                ?: fetchMetadataForItem(item).also { cachedMetadata[item.metadataKey()] = MetadataHolder(it) }
            val positive = metadata?.positivePrompt
            if (positive.isNullOrBlank()) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.msg_no_generation_info))
                return@launch
            }
            ViewerHandoff.post(ViewerHandoff.Request.ReusePrompt(positive, metadata?.negativePrompt))
            _events.emit(MediaViewerEvent.OpenGeneration)
        }
    }

    /** Send the current picture to Image to Image as the source. */
    fun editImage() {
        val state = _uiState.value
        val bitmap = state.currentBitmap
        viewModelScope.launch {
            if (state.currentItem?.isVideo == true || bitmap == null) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_image_not_ready))
                return@launch
            }
            ViewerHandoff.post(ViewerHandoff.Request.EditImage(bitmap))
            _events.emit(MediaViewerEvent.OpenGeneration)
        }
    }

    /** Stop the slideshow and continue viewing the item it was showing. */
    fun stopSlideshow(atIndex: Int) {
        if (atIndex != _uiState.value.currentIndex) setCurrentIndex(atIndex)
        _uiState.value = _uiState.value.copy(isSlideshowPlaying = false, isUiVisible = true)
    }

    /** Start (hides the controls) or stop the slideshow. */
    fun setSlideshowPlaying(playing: Boolean) {
        val state = _uiState.value
        if (playing && (state.mode != ViewerMode.GALLERY || state.items.size < 2)) return
        _uiState.value = state.copy(isSlideshowPlaying = playing, isUiVisible = !playing)
    }

    fun setCurrentIndex(index: Int) {
        val state = _uiState.value
        if (index < 0 || index >= state.items.size) return

        // Clear metadata when navigating
        clearCurrentMetadata()

        val item = state.items[index]
        val key = item.toCacheKey()

        // Update image cache priorities based on new position
        updateCachePrioritiesForIndex(index)

        // Trigger prefetch IMMEDIATELY for adjacent items (before loading current)
        triggerPrefetchForIndex(index)

        // Pre-load metadata for current + adjacent items
        preloadMetadataForIndices()

        // Try to get from cache immediately (same approach for images and videos)
        val cachedBitmap = if (!item.isVideo) MediaCache.getBitmap(key) else null
        val cachedVideoUri = if (item.isVideo) MediaCache.getCachedVideoUri(key) else null

        if (!item.isVideo && cachedBitmap != null) {
            // Image is cached - show immediately
            _uiState.value = state.copy(
                currentIndex = index,
                currentBitmap = cachedBitmap,
                currentVideoUri = null,
                isLoading = false
            )
        } else if (item.isVideo && cachedVideoUri != null) {
            // Video is cached - show immediately
            _uiState.value = state.copy(
                currentIndex = index,
                currentBitmap = null,
                currentVideoUri = cachedVideoUri,
                isLoading = false
            )
        } else if (MediaCache.isPrefetchInProgress(key)) {
            // Prefetch is in progress - wait for it instead of starting new fetch
            _uiState.value = state.copy(
                currentIndex = index,
                currentBitmap = null,
                currentVideoUri = null,
                isLoading = true
            )
            viewModelScope.launch {
                MediaCache.awaitPrefetchCompletion(key)
                showFromCache(item, key)
            }
        } else {
            // Content not cached - load from server
            _uiState.value = state.copy(
                currentIndex = index,
                currentBitmap = null,
                currentVideoUri = null,
                isLoading = true
            )
            loadCurrentItem()
        }
    }

    /**
     * Show item from cache after prefetch completes.
     */
    private fun showFromCache(item: MediaViewerItem, key: MediaCacheKey) {
        // The user may have swiped on while waiting; never show another item's content
        if (_uiState.value.currentItem?.toCacheKey() != key) return
        if (item.isVideo) {
            val uri = MediaCache.getCachedVideoUri(key)
            _uiState.value = _uiState.value.copy(
                currentVideoUri = uri,
                isLoading = false
            )
        } else {
            val bitmap = MediaCache.getBitmap(key)
            _uiState.value = _uiState.value.copy(
                currentBitmap = bitmap,
                isLoading = false
            )
        }
    }

    /**
     * Update cache priorities based on current viewing position.
     * Ensures adjacent items have higher priority than distant items.
     */
    private fun updateCachePrioritiesForIndex(index: Int) {
        val state = _uiState.value
        if (state.mode != ViewerMode.GALLERY || state.items.isEmpty()) return

        val allKeys = state.items.map { it.toCacheKey() }
        MediaCache.updateNavigationPriorities(index, allKeys)
    }

    /**
     * Trigger prefetch for items around the given index.
     */
    private fun triggerPrefetchForIndex(index: Int) {
        val state = _uiState.value
        if (state.mode != ViewerMode.GALLERY || state.items.isEmpty()) return

        val prefetchItems = state.items.map { item ->
            MediaCache.PrefetchItem(
                key = item.toCacheKey(),
                isVideo = item.isVideo,
                subfolder = item.subfolder,
                type = item.type
            )
        }

        MediaCache.prefetchAround(index, prefetchItems)
    }

    /** Convert MediaViewerItem to MediaCacheKey */
    private fun MediaViewerItem.toCacheKey() = MediaCacheKey(promptId, filename)

    fun navigateNext() {
        val state = _uiState.value
        if (state.canNavigateForward) {
            setCurrentIndex(state.currentIndex + 1)
        }
    }

    fun navigatePrevious() {
        val state = _uiState.value
        if (state.canNavigateBack) {
            setCurrentIndex(state.currentIndex - 1)
        }
    }

    private fun loadCurrentItem() {
        val state = _uiState.value
        val item = state.currentItem ?: return
        val context = applicationContext ?: return

        val key = item.toCacheKey()

        // Check if already cached
        if (item.isVideo) {
            MediaCache.getCachedVideoUri(key)?.let { uri ->
                _uiState.value = state.copy(currentVideoUri = uri, isLoading = false)
                return
            }
        } else {
            MediaCache.getBitmap(key)?.let { bitmap ->
                _uiState.value = state.copy(currentBitmap = bitmap, isLoading = false)
                return
            }
        }

        _uiState.value = state.copy(isLoading = true)

        viewModelScope.launch {
            if (item.isVideo) {
                // Fetch video and create URI in one step
                val uri = MediaCache.fetchVideoUri(key, item.subfolder, item.type, context)
                if (_uiState.value.currentItem?.toCacheKey() != key) return@launch
                _uiState.value = _uiState.value.copy(
                    currentVideoUri = uri,
                    isLoading = false
                )
            } else {
                val bitmap = MediaCache.fetchImage(key, item.subfolder, item.type)
                if (_uiState.value.currentItem?.toCacheKey() != key) return@launch
                _uiState.value = _uiState.value.copy(
                    currentBitmap = bitmap,
                    isLoading = false
                )
            }
        }
    }

    /**
     * Load metadata for the current item.
     * Uses cached metadata if available from pre-loading, otherwise fetches on demand.
     */
    fun loadMetadata() {
        val item = _uiState.value.currentItem ?: return
        val itemKey = item.metadataKey()

        // Check cache first - metadata may have been pre-loaded
        cachedMetadata[itemKey]?.let {
            _currentMetadata.value = it.metadata
            return
        }

        // Not cached - load now (fallback for edge cases like rapid swiping)
        _isLoadingMetadata.value = true

        viewModelScope.launch {
            val metadata = fetchMetadataForItem(item)
            cachedMetadata[itemKey] = MetadataHolder(metadata)
            // Only show it if the user is still on that item
            if (_uiState.value.currentItem?.metadataKey() == itemKey) {
                _currentMetadata.value = metadata
            }
            _isLoadingMetadata.value = false
        }
    }

    private class MetadataHolder(val metadata: GenerationMetadata?)

    private fun MediaViewerItem.metadataKey() = "${promptId}_$filename"

    /**
     * Clear metadata when navigating to a different item.
     */
    private fun clearCurrentMetadata() {
        _currentMetadata.value = null
    }

    /**
     * Fetches metadata for a specific item.
     * Handles both images (PNG) and videos (MP4).
     * Returns null if metadata cannot be extracted.
     */
    private suspend fun fetchMetadataForItem(item: MediaViewerItem): GenerationMetadata? {
        val state = _uiState.value
        val context = applicationContext ?: return null

        return withContext(Dispatchers.IO) {
            val bytes: ByteArray? = when {
                // For SINGLE mode videos, try to read from local file first
                state.mode == ViewerMode.SINGLE && item.isVideo && state.currentVideoUri != null -> {
                    try {
                        context.contentResolver.openInputStream(state.currentVideoUri)?.use {
                            it.readBytes()
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                // Permanent on-device copy
                LocalGalleryStore.localFile(context, ConnectionManager.currentServerId, MediaCacheKey(item.promptId, item.filename)) != null -> {
                    LocalGalleryStore.localFile(context, ConnectionManager.currentServerId, MediaCacheKey(item.promptId, item.filename))?.readBytes()
                }
                // For items with server file info and a client, fetch from server
                item.filename.isNotEmpty() && ConnectionManager.clientOrNull != null -> {
                    kotlin.coroutines.suspendCoroutine { continuation ->
                        ConnectionManager.clientOrNull!!.fetchRawBytes(item.filename, item.subfolder, item.type) { rawBytes, _ ->
                            continuation.resumeWith(Result.success(rawBytes))
                        }
                    }
                }
                // No source available
                else -> null
            }

            // Saved generation record (survives ComfyUI restarts)
            val savedRecord = LocalGalleryStore.loadGenerationRecord(
                context, ConnectionManager.currentServerId, item.promptId
            )

            if (bytes == null && savedRecord == null) return@withContext null

            // Extract metadata based on file type, falling back to the saved record
            val jsonString = bytes?.let {
                if (item.isVideo) Mp4MetadataExtractor.extractPromptMetadata(it)
                else PngMetadataExtractor.extractPromptMetadata(it)
            } ?: savedRecord

            // Parse the workflow JSON
            jsonString?.let { MetadataParser.parseWorkflowJson(it) }
        }
    }

    /**
     * Pre-loads metadata for current and adjacent items in the background.
     * Called when navigating to have metadata ready before user opens sheet.
     * Mirrors the same indices as MediaCache.prefetchAround() for consistency.
     */
    private fun preloadMetadataForIndices() {
        val state = _uiState.value
        if (state.mode != ViewerMode.GALLERY || state.items.isEmpty()) return

        val currentIndex = state.currentIndex
        val itemCount = state.items.size

        // Same pattern as MediaCache.prefetchAround(): current ±1, ±2
        val indicesToPreload = listOf(
            currentIndex,      // Current item (highest priority)
            currentIndex - 1,  // Adjacent
            currentIndex + 1,
            currentIndex - 2,  // Nearby
            currentIndex + 2
        ).filter { it in 0 until itemCount }
         .map { state.items[it] }
         .filter { !cachedMetadata.containsKey(it.metadataKey()) }

        indicesToPreload.forEach { item ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val metadata = fetchMetadataForItem(item)
                    cachedMetadata[item.metadataKey()] = MetadataHolder(metadata)
                } catch (e: Exception) {
                    // Silently fail - metadata will load on-demand if needed
                }
            }
        }
    }

    /**
     * Move the current item to the gallery's trash (or, when viewing the trash,
     * delete it for good) and show the next one.
     */
    fun deleteCurrentItem() {
        val state = _uiState.value
        val item = state.currentItem ?: return
        if (item.isPreview || state.mode != ViewerMode.GALLERY) return
        val galleryItem = GalleryItem(item.promptId, item.filename, item.subfolder, item.type, item.isVideo, item.index)
        val repository = GalleryRepository.getInstance()

        viewModelScope.launch {
            if (isTrash) {
                repository.deletePermanently(listOf(galleryItem))
                _events.emit(MediaViewerEvent.ShowToast(R.string.msg_history_item_deleted_success))
            } else {
                // Leaves the gallery right away; don't wait for the file to be moved
                launch { repository.moveToTrash(listOf(galleryItem)) }
                _events.emit(MediaViewerEvent.ShowToast(R.string.msg_moved_to_trash))
            }

            // Mark that items were deleted (for result reporting)
            _hasDeletedItems.value = true

            // Get fresh state after async operation
            val currentState = _uiState.value
            val currentItems = currentState.items.toMutableList()

            // Make sure we're removing the correct item
            val actualIndexToRemove = currentItems.indexOfFirst {
                it.promptId == item.promptId && it.filename == item.filename
            }

            if (actualIndexToRemove >= 0) {
                currentItems.removeAt(actualIndexToRemove)
            }

            if (currentItems.isEmpty()) {
                // No more items, close viewer
                _events.emit(MediaViewerEvent.ItemDeleted)
                _events.emit(MediaViewerEvent.Close)
            } else {
                // Adjust index and show next/previous item
                val newIndex = actualIndexToRemove.coerceIn(0, currentItems.size - 1)
                _currentMetadata.value = null

                _uiState.value = currentState.copy(
                    items = currentItems,
                    currentIndex = newIndex,
                    currentBitmap = null,
                    currentVideoUri = null,
                    isLoading = true
                )
                _events.emit(MediaViewerEvent.ItemDeleted)
                loadCurrentItem()
            }
        }
    }

    /**
     * Save and share act on the item shown: in single mode (or for a preview) the bitmap or
     * video uri on screen, in gallery mode the item fetched from the cache or the server.
     */
    private val MediaViewerUiState.showsLocalMedia: Boolean
        get() = mode == ViewerMode.SINGLE || currentItem?.isPreview == true

    private val MediaViewerUiState.showsVideo: Boolean
        get() = currentItem?.isVideo == true || (showsLocalMedia && currentVideoUri != null)

    fun saveCurrentItem() {
        val context = applicationContext ?: return
        val state = _uiState.value
        val item = state.currentItem
        if (!state.showsLocalMedia && item == null) return
        val isVideo = state.showsVideo

        viewModelScope.launch {
            val saved = when {
                state.showsLocalMedia && isVideo -> state.currentVideoUri
                    ?.let { uri -> withContext(Dispatchers.IO) { readBytes(context, uri) } }
                    ?.let { MediaExport.saveVideo(context, it) }
                state.showsLocalMedia -> state.currentBitmap?.let { MediaExport.saveImage(context, it) }
                isVideo -> MediaExport.loadVideo(item!!.toCacheKey(), item.subfolder, item.type)
                    ?.let { MediaExport.saveVideo(context, it) }
                else -> MediaExport.loadImage(item!!.toCacheKey(), item.subfolder, item.type)
                    ?.let { MediaExport.saveImage(context, it) }
            } == true
            _events.emit(MediaViewerEvent.ShowToast(when {
                saved && isVideo -> R.string.msg_video_saved_to_gallery
                saved -> R.string.msg_image_saved_to_gallery
                isVideo -> R.string.error_save_video
                else -> R.string.error_save_image
            }))
        }
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray? =
        try { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }

    fun shareCurrentItem() {
        val context = applicationContext ?: return
        val state = _uiState.value
        val item = state.currentItem
        if (!state.showsLocalMedia && item == null) return
        val isVideo = state.showsVideo

        viewModelScope.launch {
            val uri = when {
                // Already a file other apps can read
                state.showsLocalMedia && isVideo -> state.currentVideoUri
                state.showsLocalMedia -> state.currentBitmap?.let { MediaExport.imageShareUri(context, it) }
                isVideo -> MediaExport.loadVideo(item!!.toCacheKey(), item.subfolder, item.type)
                    ?.let { MediaExport.videoShareUri(context, it) }
                else -> MediaExport.loadImage(item!!.toCacheKey(), item.subfolder, item.type)
                    ?.let { MediaExport.imageShareUri(context, it) }
            }
            try {
                checkNotNull(uri)
                MediaExport.share(
                    context, listOf(uri),
                    mimeType = if (isVideo) "video/mp4" else "image/png",
                    title = context.getString(if (isVideo) R.string.share_video else R.string.share_image)
                )
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(if (isVideo) R.string.error_share_video else R.string.error_share_image))
            }
        }
    }
}
