package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewComfy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import sh.hnet.comfychair.storage.GalleryAlbum
import sh.hnet.comfychair.viewmodel.GalleryViewMode
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.AppMenuDropdown
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import sh.hnet.comfychair.cache.ActiveView
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.rememberLazyBitmap
import sh.hnet.comfychair.viewmodel.ConnectionStatus
import sh.hnet.comfychair.viewmodel.GalleryEvent
import sh.hnet.comfychair.viewmodel.GalleryItem
import sh.hnet.comfychair.viewmodel.GalleryViewModel
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.MediaViewerItem
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import sh.hnet.comfychair.ui.components.rememberGalleryThumbnail
import sh.hnet.comfychair.ui.components.GalleryThumbnailCache
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import sh.hnet.comfychair.ui.components.DragSelectState
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.ui.text.style.TextOverflow
import sh.hnet.comfychair.viewmodel.GallerySection
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import sh.hnet.comfychair.viewmodel.GallerySortOrder
import androidx.compose.material.icons.filled.CreateNewFolder
import sh.hnet.comfychair.ui.components.gallerySelectGestures
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import sh.hnet.comfychair.repository.AlbumRepository
import sh.hnet.comfychair.viewmodel.AlbumSortOrder
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    generationViewModel: GenerationViewModel,
    galleryViewModel: GalleryViewModel,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by galleryViewModel.uiState.collectAsState()
    val connectionStatus by generationViewModel.connectionStatus.collectAsState()

    // Check offline mode
    val isOfflineMode = remember { AppSettings.isOfflineMode(context) }

    // State and effects
    // Activity result launcher for MediaViewerActivity
    val mediaViewerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Restore active view to Gallery
        MediaCache.setActiveView(ActiveView.GALLERY)
        // Deleting in the viewer only moves items to the trash (local), so nothing to reload
    }

    // Initialize ViewModel
    LaunchedEffect(Unit) {
        galleryViewModel.initialize(context)
    }

    // Load gallery when connected
    LaunchedEffect(connectionStatus) {
        if (connectionStatus == ConnectionStatus.CONNECTED) {
            galleryViewModel.loadGallery()
        }
        galleryViewModel.reloadAlbums()
    }

    // Event handling
    LaunchedEffect(Unit) {
        galleryViewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is GalleryEvent.ShowMedia -> {
                    // Handle media display
                }
            }
        }
    }

    // Helper function to convert GalleryItems to MediaViewerItems
    fun galleryItemsToViewerItems(items: List<GalleryItem>): List<MediaViewerItem> {
        return items.map { item ->
            MediaViewerItem(
                promptId = item.promptId,
                filename = item.filename,
                subfolder = item.subfolder,
                type = item.type,
                isVideo = item.isVideo,
                index = item.index
            )
        }
    }

    // Function to launch media viewer
    // [clickedKey] = item to open (null = last item, where the slideshow starts). The item is
    // looked up by key in the latest list, so a refresh between the tap and here can't open a
    // different image.
    fun launchMediaViewer(clickedKey: String?, startSlideshow: Boolean = false) {
        val state = galleryViewModel.uiState.value
        val items = state.items
        val clickedIndex = if (clickedKey == null) items.lastIndex
        else items.indexOfFirst { "${it.promptId}_${it.filename}" == clickedKey }
        if (clickedIndex !in items.indices) return

        // Set active view to MediaViewer
        MediaCache.setActiveView(ActiveView.MEDIA_VIEWER)

        // Update priorities to protect items around clicked index
        val allKeys = items.map { it.toCacheKey() }
        MediaCache.updateNavigationPriorities(clickedIndex, allKeys)

        val viewerItems = galleryItemsToViewerItems(items)
        val intent = MediaViewerActivity.createGalleryIntent(
            context = context,
            hostname = ConnectionManager.hostname,
            port = ConnectionManager.port,
            items = viewerItems,
            initialIndex = clickedIndex,
            startSlideshow = startSlideshow,
            isTrash = state.section == GallerySection.TRASH
        )
        mediaViewerLauncher.launch(intent)
    }

    // Grid state for scroll tracking
    // Regular grid for the square/single modes (stable layout), staggered only for masonry
    val gridState = rememberLazyGridState()
    val staggeredState = rememberLazyStaggeredGridState()
    val viewMode = uiState.viewMode
    val isMasonry = viewMode == GalleryViewMode.MASONRY
    val firstVisibleIndex = if (isMasonry) staggeredState.firstVisibleItemIndex else gridState.firstVisibleItemIndex
    val visibleCount = if (isMasonry) staggeredState.layoutInfo.visibleItemsInfo.size else gridState.layoutInfo.visibleItemsInfo.size
    val columns = viewMode.columns
    val spacing = if (columns >= 3) 4.dp else 8.dp

    // Where each visible cell is drawn, in grid coordinates (used to find the item under a finger).
    // Measured from the cells themselves, so content padding and headers can't shift the hit test.
    val itemBounds = remember { HashMap<String, Rect>() }
    fun visibleKeys(): List<Any> = if (isMasonry) {
        staggeredState.layoutInfo.visibleItemsInfo.map { it.key }
    } else {
        gridState.layoutInfo.visibleItemsInfo.map { it.key }
    }
    fun keyAtPosition(pos: Offset): String? =
        visibleKeys().firstOrNull { key -> (key as? String)?.let { itemBounds[it] }?.contains(pos) == true } as? String

    // Drag and drop ordering: the item being moved follows the finger
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    var grabOffset by remember { mutableStateOf(Offset.Zero) }

    // Tap / long-press / drag-to-select / drag-to-move handled at grid level (see gallerySelectGestures)
    val haptics = LocalHapticFeedback.current
    val dragSelectState = remember { DragSelectState() }
    val edgeThreshold = with(LocalDensity.current) { 64.dp.toPx() }
    val currentItems by rememberUpdatedState(uiState.items)
    val currentSelection by rememberUpdatedState(uiState.selectedItems)
    val currentSelectionMode by rememberUpdatedState(uiState.isSelectionMode)
    val currentCanReorder by rememberUpdatedState(uiState.canReorder)
    val launchViewer by rememberUpdatedState<(String) -> Unit>({ key -> launchMediaViewer(key) })
    fun itemKey(item: GalleryItem) = "${item.promptId}_${item.filename}"
    // Remembered so the gesture coroutine is not restarted on every recomposition
    val selectGestures = remember(isMasonry) { Modifier.gallerySelectGestures(
        state = dragSelectState,
        haptics = haptics,
        edgeThreshold = edgeThreshold,
        keyAt = { pos -> keyAtPosition(pos) },
        keys = { currentItems.map(::itemKey) },
        selection = { currentSelection },
        isSelectionMode = { currentSelectionMode },
        canReorder = { currentCanReorder },
        onTap = { key ->
            val index = currentItems.indexOfFirst { itemKey(it) == key }
            if (index >= 0) {
                if (currentSelectionMode) {
                    galleryViewModel.toggleSelection(currentItems[index])
                } else {
                    launchViewer(key)
                }
            }
        },
        onSelectionChange = { galleryViewModel.setSelection(it) },
        onReorderStart = { key, pos ->
            // Dragging switches to the custom order (day headers go away)
            galleryViewModel.beginReorder()
            draggingKey = key
            dragPosition = pos
            grabOffset = pos - (itemBounds[key]?.topLeft ?: pos)
        },
        onReorderMove = move@{ pos ->
            dragPosition = pos
            val from = draggingKey ?: return@move
            val target = keyAtPosition(pos) ?: return@move
            if (target == from) return@move
            // Moving the first visible item would make the grid follow it; keep the scroll position
            val first = visibleKeys().firstOrNull()
            if (first == from || first == target) {
                if (isMasonry) {
                    staggeredState.requestScrollToItem(
                        staggeredState.firstVisibleItemIndex, staggeredState.firstVisibleItemScrollOffset
                    )
                } else {
                    gridState.requestScrollToItem(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                }
            }
            galleryViewModel.moveItem(from, target)
        },
        onReorderEnd = { draggingKey = null }
    ) }

    // Items split into days (one group without a header when sorted by name/type)
    val dateGroups = remember(uiState.items, uiState.sortOrder) {
        groupByDay(uiState.items, byDate = uiState.sortOrder == GallerySortOrder.NEWEST || uiState.sortOrder == GallerySortOrder.OLDEST)
    }

    // Auto-scroll while drag-selecting or moving an item near the top/bottom edge
    LaunchedEffect(dragSelectState.autoScrollSpeed) {
        val speed = dragSelectState.autoScrollSpeed
        if (speed != 0f) {
            while (true) {
                if (isMasonry) staggeredState.scrollBy(speed * 0.3f) else gridState.scrollBy(speed * 0.3f)
                dragSelectState.onAutoScrolled()
                kotlinx.coroutines.delay(16)
            }
        }
    }

    // Album dialogs
    var showNewAlbumDialog by remember { mutableStateOf(false) }
    var showAddToAlbumDialog by remember { mutableStateOf(false) }
    var editingAlbum by remember { mutableStateOf<GalleryAlbum?>(null) }
    var showEmptyTrashDialog by remember { mutableStateOf(false) }
    var showDeleteForeverDialog by remember { mutableStateOf(false) }
    val selectedAlbum = uiState.selectedAlbum
    val isTrash = uiState.section == GallerySection.TRASH

    // Create prefetch items list from gallery items
    val prefetchItems = remember(uiState.items) {
        uiState.items.map { item ->
            MediaCache.PrefetchItem(
                key = item.toCacheKey(),
                isVideo = item.isVideo,
                subfolder = item.subfolder,
                type = item.type
            )
        }
    }

    // Track scroll position and update cache priorities with debounce
    // Only update when Gallery is the active view (not when MediaViewer is animating closed)
    LaunchedEffect(firstVisibleIndex, visibleCount) {
        if (prefetchItems.isNotEmpty() && MediaCache.isActiveView(ActiveView.GALLERY)) {
            // Debounce scroll updates to avoid excessive calls during fast scrolling
            kotlinx.coroutines.delay(100)
            MediaCache.updateGalleryPosition(
                firstVisibleIndex = firstVisibleIndex,
                visibleItemCount = visibleCount,
                allItems = prefetchItems,
                columnsInGrid = columns
            )
        }
    }

    // Start at the top when switching album, view mode or sort order
    LaunchedEffect(uiState.section, uiState.selectedAlbumId, viewMode, uiState.sortOrder) {
        if (isMasonry) staggeredState.scrollToItem(0) else gridState.scrollToItem(0)
    }

    // Prefetch when items become available or change (e.g., after manual refresh)
    // Use first item's key as part of the effect key to detect list changes
    val itemsKey = remember(uiState.items) {
        if (uiState.items.isEmpty()) "" else "${uiState.items.size}_${uiState.items.first().promptId}"
    }
    LaunchedEffect(itemsKey) {
        if (uiState.items.isNotEmpty()) {
            // Base prefetch on current scroll position instead of always starting from 0
            val startIndex = firstVisibleIndex.coerceAtLeast(0).coerceAtMost(uiState.items.size)
            val endIndex = (startIndex + 24).coerceAtMost(uiState.items.size)
            val initialItems = uiState.items.subList(startIndex, endIndex).map { item ->
                MediaCache.PrefetchItem(
                    key = item.toCacheKey(),
                    isVideo = item.isVideo,
                    subfolder = item.subfolder,
                    type = item.type
                )
            }
            MediaCache.initialPrefetch(initialItems)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        val inAlbum = uiState.section == GallerySection.ALBUMS && selectedAlbum != null
        val showAlbumList = uiState.section == GallerySection.ALBUMS && selectedAlbum == null
        TopAppBar(
            title = {
                when {
                    uiState.isSelectionMode -> Text(stringResource(R.string.gallery_selected_count, uiState.selectedItems.size))
                    inAlbum -> Text(selectedAlbum!!.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Photos / Albums / Trash tabs at the start of the app bar
                    else -> GalleryTabs(selected = uiState.section, onSelect = { galleryViewModel.setSection(it) })
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = {
                if (uiState.isSelectionMode) {
                    IconButton(onClick = { galleryViewModel.clearSelection() }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.button_cancel_selection))
                    }
                } else if (inAlbum) {
                    IconButton(onClick = { galleryViewModel.selectAlbum(null) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
                    }
                }
            },
            actions = {
                if (uiState.isSelectionMode) {
                    // Select all / deselect all (the shown items)
                    val allSelected = uiState.items.isNotEmpty() && uiState.items.all { itemKey(it) in uiState.selectedItems }
                    IconButton(onClick = {
                        if (allSelected) galleryViewModel.setSelection(emptySet()) else galleryViewModel.selectAll()
                    }) {
                        Icon(
                            if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                            contentDescription = stringResource(if (allSelected) R.string.gallery_deselect_all else R.string.gallery_select_all)
                        )
                    }
                    if (isTrash) {
                        // Trash: restore or delete for good
                        IconButton(onClick = { galleryViewModel.restoreSelected() }) {
                            Icon(Icons.Default.RestoreFromTrash, contentDescription = stringResource(R.string.gallery_restore))
                        }
                        IconButton(
                            onClick = { showDeleteForeverDialog = true },
                            enabled = uiState.selectedItems.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.DeleteForever,
                                contentDescription = stringResource(R.string.gallery_delete_forever),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                    IconButton(onClick = { galleryViewModel.deleteSelected() }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.button_delete_history_item),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    IconButton(onClick = { showAddToAlbumDialog = true }) {
                        Icon(Icons.Default.LibraryAdd, contentDescription = stringResource(R.string.gallery_add_to_album))
                    }
                    if (inAlbum && uiState.selectedItems.size == 1) {
                        IconButton(onClick = { galleryViewModel.setSelectedAsCover(selectedAlbum!!.id) }) {
                            Icon(Icons.Default.Wallpaper, contentDescription = stringResource(R.string.gallery_set_cover))
                        }
                    }
                    if (inAlbum) {
                        IconButton(onClick = { galleryViewModel.removeSelectedFromAlbum(selectedAlbum!!.id) }) {
                            Icon(Icons.Default.RemoveCircleOutline, contentDescription = stringResource(R.string.gallery_remove_from_album))
                        }
                    }
                    IconButton(onClick = { galleryViewModel.saveSelectedToGallery(context) }) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.button_save_image))
                    }
                    IconButton(onClick = { galleryViewModel.shareSelected(context) }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.button_share))
                    }
                    }
                } else if (isTrash) {
                    IconButton(
                        onClick = { showEmptyTrashDialog = true },
                        enabled = uiState.items.isNotEmpty()
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.gallery_empty_trash))
                    }
                    IconButton(
                        onClick = { galleryViewModel.enterSelectionMode() },
                        enabled = uiState.items.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.button_gallery_select))
                    }
                    AppMenuDropdown(
                        onSettings = onNavigateToSettings,
                        onLogout = onLogout
                    )
                } else {
                    if (!showAlbumList) {
                        // One menu for slideshow, view mode and sort order (keeps room for the tabs)
                        Box {
                            var showViewMenu by remember { mutableStateOf(false) }
                            IconButton(onClick = { showViewMenu = true }) {
                                Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.gallery_view_mode))
                            }
                            DropdownMenu(expanded = showViewMenu, onDismissRequest = { showViewMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.gallery_slideshow)) },
                                    onClick = {
                                        showViewMenu = false
                                        launchMediaViewer(null, startSlideshow = true)
                                    },
                                    enabled = uiState.items.isNotEmpty(),
                                    leadingIcon = { Icon(Icons.Default.Slideshow, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.gallery_clean_duplicates)) },
                                    onClick = {
                                        showViewMenu = false
                                        galleryViewModel.findDuplicates()
                                    },
                                    leadingIcon = { Icon(Icons.Default.CleaningServices, contentDescription = null) }
                                )
                                HorizontalDivider()
                                GalleryViewMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(viewModeLabel(mode))) },
                                        onClick = {
                                            galleryViewModel.setViewMode(mode)
                                            showViewMenu = false
                                        },
                                        leadingIcon = { Icon(viewModeIcon(mode), contentDescription = null) },
                                        trailingIcon = { if (mode == viewMode) Icon(Icons.Default.Check, contentDescription = null) }
                                    )
                                }
                                HorizontalDivider()
                                listOf(
                                    GallerySortOrder.NEWEST to R.string.gallery_sort_newest,
                                    GallerySortOrder.OLDEST to R.string.gallery_sort_oldest,
                                    GallerySortOrder.NAME to R.string.gallery_sort_name,
                                    GallerySortOrder.TYPE to R.string.gallery_sort_type,
                                    GallerySortOrder.CUSTOM to R.string.gallery_sort_custom
                                ).forEach { (order, labelRes) ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(labelRes)) },
                                        onClick = {
                                            galleryViewModel.setSortOrder(order)
                                            showViewMenu = false
                                        },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) },
                                        trailingIcon = { if (uiState.sortOrder == order) Icon(Icons.Default.Check, contentDescription = null) }
                                    )
                                }
                            }
                        }
                    }
                    if (inAlbum) {
                        IconButton(onClick = { editingAlbum = selectedAlbum }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.gallery_edit_album))
                        }
                    } else if (showAlbumList) {
                        // Album list order
                        Box {
                            var showAlbumSortMenu by remember { mutableStateOf(false) }
                            IconButton(onClick = { showAlbumSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.gallery_sort))
                            }
                            DropdownMenu(expanded = showAlbumSortMenu, onDismissRequest = { showAlbumSortMenu = false }) {
                                listOf(
                                    AlbumSortOrder.NAME to R.string.album_sort_name,
                                    AlbumSortOrder.RECENT to R.string.album_sort_recent,
                                    AlbumSortOrder.COUNT to R.string.album_sort_count,
                                    AlbumSortOrder.CUSTOM to R.string.gallery_sort_custom
                                ).forEach { (order, labelRes) ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(labelRes)) },
                                        onClick = {
                                            galleryViewModel.setAlbumSortOrder(order)
                                            showAlbumSortMenu = false
                                        },
                                        trailingIcon = { if (uiState.albumSortOrder == order) Icon(Icons.Default.Check, contentDescription = null) }
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { showNewAlbumDialog = true }) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = stringResource(R.string.gallery_new_album))
                        }
                    }
                    if (!showAlbumList) {
                        IconButton(onClick = { galleryViewModel.enterSelectionMode() }) {
                            Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.button_gallery_select))
                        }
                    }
                    AppMenuDropdown(
                        onSettings = onNavigateToSettings,
                        onLogout = onLogout
                    )
                }
            }
        )

        // Files are being moved between album folders
        if (uiState.isMoving) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            HorizontalDivider()
        }

        // Back: leave selection mode, then the opened album
        BackHandler(enabled = uiState.isSelectionMode || inAlbum) {
            if (uiState.isSelectionMode) galleryViewModel.clearSelection() else galleryViewModel.selectAlbum(null)
        }

        if (showAlbumList) {
            AlbumGrid(
                albums = uiState.albums,
                counts = uiState.albumCounts,
                covers = uiState.albumCovers,
                onOpen = { galleryViewModel.selectAlbum(it) },
                onNewAlbum = { showNewAlbumDialog = true },
                onReorderStart = { galleryViewModel.beginAlbumReorder() },
                onMove = { from, to -> galleryViewModel.moveAlbum(from, to) }
            )
        } else {
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { galleryViewModel.manualRefresh() },
            modifier = Modifier.fillMaxSize()
        ) {
            // Always use LazyVerticalGrid for consistent nested scroll behavior with pull-to-refresh
            NoOverscrollContainer(modifier = Modifier.fillMaxSize()) {
                // One grid cell; [placement] animates moves (not for the item being dragged)
                @Composable
                fun Cell(item: GalleryItem, placement: Modifier) {
                    val key = itemKey(item)
                    val isDragging = key == draggingKey
                    GalleryItemCard(
                        item = item,
                        isSelected = key in uiState.selectedItems,
                        isOfflineMode = isOfflineMode,
                        square = viewMode.square,
                        modifier = Modifier
                            .then(if (isDragging) Modifier.zIndex(1f) else placement)
                            .onGloballyPositioned { itemBounds[key] = it.boundsInParent() }
                            .graphicsLayer {
                                if (isDragging) {
                                    val bounds = itemBounds[key]
                                    if (bounds != null) {
                                        translationX = dragPosition.x - grabOffset.x - bounds.left
                                        translationY = dragPosition.y - grabOffset.y - bounds.top
                                    }
                                    scaleX = 1.05f
                                    scaleY = 1.05f
                                    alpha = 0.9f
                                    shadowElevation = 12.dp.toPx()
                                }
                            }
                    )
                }

                if (isMasonry) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(columns),
                    state = staggeredState,
                    contentPadding = PaddingValues(spacing),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalItemSpacing = spacing,
                    modifier = Modifier.fillMaxSize().then(selectGestures)
                ) {
                if (uiState.isLoading && uiState.items.isEmpty()) {
                    // Loading state - show as full-span item
                    item(span = StaggeredGridItemSpan.FullLine) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (uiState.items.isEmpty()) {
                    // Empty state - show as full-span item
                    item(span = StaggeredGridItemSpan.FullLine) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = stringResource(
                                        when {
                                            isTrash -> R.string.msg_trash_empty
                                            selectedAlbum != null -> R.string.msg_album_empty
                                            else -> R.string.msg_gallery_empty
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    // Gallery items, grouped by day
                    dateGroups.forEach { group ->
                        item(key = "header_${group.day}", span = StaggeredGridItemSpan.FullLine) {
                            DateGroupHeader(group, uiState.selectedItems, ::itemKey) { galleryViewModel.setSelection(it) }
                        }
                        itemsIndexed(group.items, key = { _, item -> "${item.promptId}_${item.filename}" }) { _, item ->
                        Cell(item, Modifier.animateItem())
                    }
                    }
                }
                }
                } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    contentPadding = PaddingValues(spacing),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                    modifier = Modifier.fillMaxSize().then(selectGestures)
                ) {
                if (uiState.isLoading && uiState.items.isEmpty()) {
                    // Loading state - show as full-span item
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (uiState.items.isEmpty()) {
                    // Empty state - show as full-span item
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = stringResource(
                                        when {
                                            isTrash -> R.string.msg_trash_empty
                                            selectedAlbum != null -> R.string.msg_album_empty
                                            else -> R.string.msg_gallery_empty
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    // Gallery items, grouped by day
                    dateGroups.forEach { group ->
                        item(key = "header_${group.day}", span = { GridItemSpan(maxLineSpan) }) {
                            DateGroupHeader(group, uiState.selectedItems, ::itemKey) { galleryViewModel.setSelection(it) }
                        }
                        gridItemsIndexed(group.items, key = { _, item -> "${item.promptId}_${item.filename}" }) { _, item ->
                        Cell(item, Modifier.animateItem())
                    }
                    }
                }
                }
                }
            }
        }
        }
    }

    if (showNewAlbumDialog) {
        AlbumNameDialog(
            title = stringResource(R.string.gallery_new_album),
            initialName = "",
            confirmLabel = stringResource(R.string.gallery_button_create),
            onConfirm = { name ->
                galleryViewModel.createAlbum(name)
                showNewAlbumDialog = false
            },
            onDismiss = { showNewAlbumDialog = false }
        )
    }

    editingAlbum?.let { album ->
        AlbumNameDialog(
            title = stringResource(R.string.gallery_edit_album),
            initialName = album.name,
            confirmLabel = stringResource(R.string.button_save),
            onConfirm = { name ->
                galleryViewModel.renameAlbum(album.id, name)
                editingAlbum = null
            },
            onDismiss = { editingAlbum = null },
            onDelete = {
                galleryViewModel.deleteAlbum(album.id)
                editingAlbum = null
            }
        )
    }

    if (showAddToAlbumDialog) {
        AddToAlbumDialog(
            albums = uiState.albums.filter { it.id != uiState.selectedAlbumId },
            albumCounts = uiState.albumCounts,
            onSelectAlbum = { albumId ->
                galleryViewModel.addSelectedToAlbum(albumId)
                showAddToAlbumDialog = false
            },
            onCreateAlbum = { name ->
                galleryViewModel.createAlbum(name)
                showAddToAlbumDialog = false
            },
            onDismiss = { showAddToAlbumDialog = false }
        )
    }

    uiState.rootDuplicates?.let { duplicates ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.gallery_clean_duplicates),
            message = stringResource(R.string.gallery_clean_duplicates_confirm, duplicates.size),
            onConfirm = { galleryViewModel.deleteDuplicates() },
            onDismiss = { galleryViewModel.dismissDuplicates() }
        )
    }

    if (showEmptyTrashDialog) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.gallery_empty_trash),
            message = stringResource(R.string.gallery_delete_forever_confirm, uiState.trashCount),
            onConfirm = {
                galleryViewModel.emptyTrash()
                showEmptyTrashDialog = false
            },
            onDismiss = { showEmptyTrashDialog = false }
        )
    }

    if (showDeleteForeverDialog) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.gallery_delete_forever),
            message = stringResource(R.string.gallery_delete_forever_confirm, uiState.selectedItems.size),
            onConfirm = {
                galleryViewModel.deleteSelectedPermanently()
                showDeleteForeverDialog = false
            },
            onDismiss = { showDeleteForeverDialog = false }
        )
    }
}

/**
 * Photos / Albums / Trash tabs, shown at the start of the app bar.
 */
@Composable
private fun GalleryTabs(
    selected: GallerySection,
    onSelect: (GallerySection) -> Unit
) {
    val tabs = listOf(
        GallerySection.PHOTOS to R.string.gallery_tab_photos,
        GallerySection.ALBUMS to R.string.gallery_tab_albums,
        GallerySection.TRASH to R.string.gallery_tab_trash
    )
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        tabs.forEach { (tab, labelRes) ->
            val isSelected = tab == selected
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.button_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
        }
    )
}

/** Items generated on the same day. [day] = epoch day, or null for no header. */
private class DateGroup(val day: Long?, val items: List<GalleryItem>)

/** Split [items] (already sorted) into runs of the same local day. */
private fun groupByDay(items: List<GalleryItem>, byDate: Boolean): List<DateGroup> {
    if (!byDate || items.isEmpty()) return listOf(DateGroup(null, items))
    val zone = java.time.ZoneId.systemDefault()
    val groups = mutableListOf<DateGroup>()
    var currentDay: Long? = null
    var current = mutableListOf<GalleryItem>()
    for (item in items) {
        val day = if (item.timestamp > 0) {
            java.time.Instant.ofEpochMilli(item.timestamp).atZone(zone).toLocalDate().toEpochDay()
        } else UNKNOWN_DAY
        if (day != currentDay && current.isNotEmpty()) {
            groups += DateGroup(currentDay, current)
            current = mutableListOf()
        }
        currentDay = day
        current += item
    }
    groups += DateGroup(currentDay, current)
    return groups
}

private const val UNKNOWN_DAY = Long.MIN_VALUE

/**
 * Day header ("Today", "Yesterday", or the date) with a select-all / deselect button
 * for that day's items. Nothing is shown for a group without a day.
 */
@Composable
private fun DateGroupHeader(
    group: DateGroup,
    selected: Set<String>,
    keyOf: (GalleryItem) -> String,
    onSelectionChange: (Set<String>) -> Unit
) {
    val day = group.day ?: return
    val label = when {
        day == UNKNOWN_DAY -> stringResource(R.string.gallery_date_unknown)
        else -> {
            val date = java.time.LocalDate.ofEpochDay(day)
            val today = java.time.LocalDate.now()
            when (date) {
                today -> stringResource(R.string.gallery_date_today)
                today.minusDays(1) -> stringResource(R.string.gallery_date_yesterday)
                else -> {
                    val locale = java.util.Locale.getDefault()
                    val skeleton = if (date.year == today.year) "MMMdEEE" else "yMMMdEEE"
                    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
                    date.format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
                }
            }
        }
    }
    val keys = group.items.map(keyOf)
    val allSelected = keys.isNotEmpty() && keys.all { it in selected }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { onSelectionChange(if (allSelected) selected - keys.toSet() else selected + keys) }) {
            Text(stringResource(if (allSelected) R.string.gallery_deselect_all else R.string.gallery_select_all))
        }
    }
}

private fun viewModeIcon(mode: GalleryViewMode): ImageVector = when (mode) {
    GalleryViewMode.GRID_2 -> Icons.Default.GridView
    GalleryViewMode.GRID_3 -> Icons.Default.Apps
    GalleryViewMode.GRID_4 -> Icons.Default.ViewComfy
    GalleryViewMode.MASONRY -> Icons.Default.Dashboard
    GalleryViewMode.SINGLE -> Icons.Default.ViewAgenda
}

private fun viewModeLabel(mode: GalleryViewMode): Int = when (mode) {
    GalleryViewMode.GRID_2 -> R.string.gallery_view_grid_2
    GalleryViewMode.GRID_3 -> R.string.gallery_view_grid_3
    GalleryViewMode.GRID_4 -> R.string.gallery_view_grid_4
    GalleryViewMode.MASONRY -> R.string.gallery_view_masonry
    GalleryViewMode.SINGLE -> R.string.gallery_view_single
}

/** Album list: cover, name and item count per album, plus a "new album" tile. */
@Composable
private fun AlbumGrid(
    albums: List<GalleryAlbum>,
    counts: Map<String, Int>,
    covers: Map<String, GalleryItem>,
    onOpen: (String) -> Unit,
    onNewAlbum: () -> Unit,
    onReorderStart: () -> Unit,
    onMove: (fromId: String, toId: String) -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val gridState = rememberLazyGridState()

    // Hold and drag an album to move it; the dragged album follows the finger
    val bounds = remember { HashMap<String, Rect>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    var grabOffset by remember { mutableStateOf(Offset.Zero) }
    fun albumAt(pos: Offset): String? = gridState.layoutInfo.visibleItemsInfo
        .mapNotNull { it.key as? String }
        .firstOrNull { id -> bounds[id]?.contains(pos) == true }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        val id = albumAt(pos) ?: return@detectDragGesturesAfterLongPress
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onReorderStart()
                        draggingId = id
                        dragPosition = pos
                        grabOffset = pos - (bounds[id]?.topLeft ?: pos)
                    },
                    onDrag = { change, amount ->
                        val from = draggingId ?: return@detectDragGesturesAfterLongPress
                        change.consume()
                        dragPosition += amount
                        val target = albumAt(dragPosition)
                        if (target != null && target != from) {
                            // Keep the scroll position when the first visible album moves
                            val first = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.key
                            if (first == from || first == target) {
                                gridState.requestScrollToItem(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                            }
                            onMove(from, target)
                        }
                    },
                    onDragEnd = { draggingId = null },
                    onDragCancel = { draggingId = null }
                )
            }
    ) {
        gridItemsIndexed(albums, key = { _, album -> album.id }) { _, album ->
            val isDragging = album.id == draggingId
            Column(
                modifier = Modifier
                    .then(if (isDragging) Modifier.zIndex(1f) else Modifier.animateItem())
                    .onGloballyPositioned { bounds[album.id] = it.boundsInParent() }
                    .graphicsLayer {
                        if (isDragging) {
                            bounds[album.id]?.let { b ->
                                translationX = dragPosition.x - grabOffset.x - b.left
                                translationY = dragPosition.y - grabOffset.y - b.top
                            }
                            scaleX = 1.05f
                            scaleY = 1.05f
                            shadowElevation = 12.dp.toPx()
                        }
                    }
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onOpen(album.id) }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val cover = covers[album.id]
                    if (cover != null) {
                        val (bitmap, _) = rememberGalleryThumbnail(cover, context)
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
                Row(
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (AlbumRepository.isFolder(album.id)) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = stringResource(R.string.gallery_folder_album),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        album.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    stringResource(R.string.gallery_album_item_count, counts[album.id] ?: 0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
        }
        item(key = "new_album") {
            Column(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(onClick = onNewAlbum)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(40.dp))
                }
                Text(
                    stringResource(R.string.gallery_new_album),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ViewModeButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    IconToggleButton(checked = selected, onCheckedChange = { onClick() }) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

/**
 * Dialog for naming a new album or renaming/deleting an existing one.
 */
@Composable
private fun AlbumNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.gallery_album_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (onDelete != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.gallery_delete_album), color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        text = stringResource(R.string.gallery_delete_album_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
        }
    )
}

/**
 * Dialog for adding selected items to an existing album or a new one.
 */
@Composable
private fun AddToAlbumDialog(
    albums: List<GalleryAlbum>,
    albumCounts: Map<String, Int>,
    onSelectAlbum: (String) -> Unit,
    onCreateAlbum: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gallery_add_to_album)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                albums.forEach { album ->
                    Text(
                        text = "${album.name} (${albumCounts[album.id] ?: 0})",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectAlbum(album.id) }
                            .padding(vertical = 12.dp)
                    )
                    HorizontalDivider()
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.gallery_new_album)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreateAlbum(newName) }, enabled = newName.isNotBlank()) {
                Text(stringResource(R.string.gallery_button_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
        }
    )
}

/**
 * Gallery item card with lazy bitmap loading.
 * Bitmaps are loaded from MediaCache on-demand.
 * Taps and long presses are handled by the grid (gallerySelectGestures).
 */
@Composable
private fun GalleryItemCard(
    item: GalleryItem,
    isSelected: Boolean,
    isOfflineMode: Boolean = false,
    square: Boolean = true,
    modifier: Modifier = Modifier
) {
    // Create cache key directly from item's stable properties
    val context = LocalContext.current
    val cacheKey = item.toCacheKey()
    val (bitmap, isLoading) = rememberGalleryThumbnail(item, context)

    // Square crop, or the media's original aspect ratio (remembered once known)
    val aspect = if (square) 1f else (
        bitmap?.let { if (it.height > 0) it.width.toFloat() / it.height else null }
            ?: GalleryThumbnailCache.aspectRatio(cacheKey.keyString)
            ?: 1f
        ).coerceIn(0.3f, 3f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.medium
                    )
                } else {
                    Modifier
                }
            )
            .clip(MaterialTheme.shapes.medium)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Thumbnail from cache
            when {
                bitmap != null -> {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = if (item.isVideo) {
                            stringResource(R.string.content_description_gallery_video_thumbnail)
                        } else {
                            stringResource(R.string.content_description_gallery_thumbnail)
                        },
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                isLoading -> {
                    // Loading placeholder
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
                else -> {
                    // Fallback placeholder - show CloudOff for offline mode, otherwise normal icon
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isOfflineMode) {
                                Icons.Default.CloudOff
                            } else if (item.isVideo) {
                                Icons.Default.PlayArrow
                            } else {
                                Icons.Default.Image
                            },
                            contentDescription = if (isOfflineMode) {
                                stringResource(R.string.content_description_not_cached)
                            } else {
                                null
                            },
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // Selection indicator
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.label_item_selected),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Video indicator (only show when not selected and not loading)
            if (item.isVideo && !isSelected && bitmap != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.content_description_video),
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}
