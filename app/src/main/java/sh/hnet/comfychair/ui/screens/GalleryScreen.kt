package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import sh.hnet.comfychair.viewmodel.GalleryTab
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
import androidx.compose.material3.LinearProgressIndicator
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
import sh.hnet.comfychair.ui.components.gallerySelectGestures

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

    val tab = uiState.tab
    val selectedAlbum = uiState.selectedAlbum
    val isTrash = tab == GalleryTab.TRASH
    val showAlbumList = tab == GalleryTab.ALBUMS && selectedAlbum == null

    // Back: leave selection mode, then close the open album
    BackHandler(enabled = uiState.isSelectionMode || selectedAlbum != null) {
        if (uiState.isSelectionMode) galleryViewModel.clearSelection()
        else galleryViewModel.selectAlbum(null)
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
    // [clickedKey] = item to open (null = first item). The item is looked up by key in the
    // latest list, so a refresh between the tap and here can't open a different image.
    fun launchMediaViewer(clickedKey: String?, startSlideshow: Boolean = false) {
        val items = galleryViewModel.uiState.value.items
        val clickedIndex = if (clickedKey == null) 0
        else items.indexOfFirst { "${it.promptId}_${it.filename}" == clickedKey }
        if (clickedIndex !in items.indices) return

        // Set active view to MediaViewer
        MediaCache.setActiveView(ActiveView.MEDIA_VIEWER)

        // Update priorities to protect items around clicked index
        val allKeys = items.map { it.toCacheKey() }
        MediaCache.updateNavigationPriorities(clickedIndex, allKeys)

        val intent = MediaViewerActivity.createGalleryIntent(
            context = context,
            hostname = ConnectionManager.hostname,
            port = ConnectionManager.port,
            items = galleryItemsToViewerItems(items),
            initialIndex = clickedIndex,
            startSlideshow = startSlideshow,
            isTrash = galleryViewModel.uiState.value.tab == GalleryTab.TRASH
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

    // Where each visible cell is drawn, in grid coordinates (used to find the item under a finger)
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

    // Dialogs
    var showNewAlbumDialog by remember { mutableStateOf(false) }
    var showAddToAlbumDialog by remember { mutableStateOf(false) }
    var editingAlbum by remember { mutableStateOf<GalleryAlbum?>(null) }
    var showEmptyTrashDialog by remember { mutableStateOf(false) }
    var showDeleteForeverDialog by remember { mutableStateOf(false) }

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

    // Start at the top when switching tab, album or view mode
    LaunchedEffect(tab, uiState.selectedAlbumId, viewMode) {
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
        TopAppBar(
            title = {
                when {
                    uiState.isSelectionMode ->
                        Text(stringResource(R.string.gallery_selected_count, uiState.selectedItems.size))
                    selectedAlbum != null ->
                        Text(selectedAlbum.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else -> GalleryTabs(
                        selected = tab,
                        onSelect = { galleryViewModel.selectTab(it) }
                    )
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = {
                if (uiState.isSelectionMode) {
                    IconButton(onClick = { galleryViewModel.clearSelection() }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.button_cancel_selection))
                    }
                } else if (selectedAlbum != null) {
                    IconButton(onClick = { galleryViewModel.selectAlbum(null) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
                    }
                }
            },
            actions = {
                if (uiState.isSelectionMode && isTrash) {
                    // Trash selection: restore or delete for good
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
                } else if (uiState.isSelectionMode) {
                    // Selection mode actions: Trash, albums, Save and Share
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
                    if (selectedAlbum != null) {
                        IconButton(onClick = { galleryViewModel.removeSelectedFromAlbum(selectedAlbum.id) }) {
                            Icon(Icons.Default.RemoveCircleOutline, contentDescription = stringResource(R.string.gallery_remove_from_album))
                        }
                    }
                    IconButton(onClick = { galleryViewModel.saveSelectedToGallery(context) }) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.button_save_image))
                    }
                    IconButton(onClick = { galleryViewModel.shareSelected(context) }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.button_share))
                    }
                } else {
                    when {
                        showAlbumList -> {
                            IconButton(onClick = { showNewAlbumDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.gallery_new_album))
                            }
                        }
                        isTrash -> {
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
                        }
                        else -> {
                            if (selectedAlbum != null) {
                                IconButton(onClick = { editingAlbum = selectedAlbum }) {
                                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.gallery_edit_album))
                                }
                            }
                            IconButton(
                                onClick = { launchMediaViewer(null, startSlideshow = true) },
                                enabled = uiState.items.isNotEmpty()
                            ) {
                                Icon(Icons.Default.Slideshow, contentDescription = stringResource(R.string.gallery_slideshow))
                            }
                            IconButton(onClick = { galleryViewModel.enterSelectionMode() }) {
                                Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.button_gallery_select))
                            }
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
        }

        // View mode switcher (not for the album list)
        if (!showAlbumList) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val modes = listOf(
                    Triple(GalleryViewMode.GRID_2, Icons.Default.GridView, R.string.gallery_view_grid_2),
                    Triple(GalleryViewMode.GRID_3, Icons.Default.Apps, R.string.gallery_view_grid_3),
                    Triple(GalleryViewMode.GRID_4, Icons.Default.ViewComfy, R.string.gallery_view_grid_4),
                    Triple(GalleryViewMode.MASONRY, Icons.Default.Dashboard, R.string.gallery_view_masonry),
                    Triple(GalleryViewMode.SINGLE, Icons.Default.ViewAgenda, R.string.gallery_view_single)
                )
                modes.forEach { (mode, icon, labelRes) ->
                    ViewModeButton(
                        icon = icon,
                        label = stringResource(labelRes),
                        selected = viewMode == mode,
                        onClick = { galleryViewModel.setViewMode(mode) }
                    )
                }
                if (uiState.isSelectionMode && uiState.items.isNotEmpty()) {
                    Spacer(modifier = Modifier.weight(1f))
                    val allSelected = uiState.items.all { itemKey(it) in uiState.selectedItems }
                    TextButton(
                        onClick = {
                            if (allSelected) galleryViewModel.setSelection(emptySet())
                            else galleryViewModel.selectAll()
                        }
                    ) {
                        Text(stringResource(if (allSelected) R.string.gallery_deselect_all else R.string.gallery_select_all))
                    }
                }
            }
            HorizontalDivider()
        }

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { galleryViewModel.manualRefresh() },
            modifier = Modifier.fillMaxSize()
        ) {
            NoOverscrollContainer(modifier = Modifier.fillMaxSize()) {
                if (showAlbumList) {
                    AlbumList(
                        albums = uiState.albums + uiState.folderAlbums,
                        counts = uiState.albumCounts,
                        covers = uiState.albumCovers,
                        isOfflineMode = isOfflineMode,
                        onOpen = { galleryViewModel.selectAlbum(it.id) },
                        onNewAlbum = { showNewAlbumDialog = true }
                    )
                } else {

                val emptyMessage = when {
                    isTrash -> R.string.msg_trash_empty
                    selectedAlbum != null -> R.string.msg_album_empty
                    else -> R.string.msg_gallery_empty
                }

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
                if (uiState.isLoading && uiState.items.isEmpty() && !isTrash) {
                    // Loading state - show as full-span item
                    item(span = StaggeredGridItemSpan.FullLine) { GridLoading() }
                } else if (uiState.items.isEmpty()) {
                    // Empty state - show as full-span item
                    item(span = StaggeredGridItemSpan.FullLine) { GridEmpty(emptyMessage) }
                } else {
                    // Gallery items
                    itemsIndexed(uiState.items, key = { _, item -> itemKey(item) }) { _, item ->
                        Cell(item, Modifier.animateItem())
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
                if (uiState.isLoading && uiState.items.isEmpty() && !isTrash) {
                    // Loading state - show as full-span item
                    item(span = { GridItemSpan(maxLineSpan) }) { GridLoading() }
                } else if (uiState.items.isEmpty()) {
                    // Empty state - show as full-span item
                    item(span = { GridItemSpan(maxLineSpan) }) { GridEmpty(emptyMessage) }
                } else {
                    // Gallery items
                    gridItemsIndexed(uiState.items, key = { _, item -> itemKey(item) }) { _, item ->
                        Cell(item, Modifier.animateItem())
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
            albums = (uiState.albums + uiState.folderAlbums).filter { it.id != uiState.selectedAlbumId },
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
    selected: GalleryTab,
    onSelect: (GalleryTab) -> Unit
) {
    val tabs = listOf(
        GalleryTab.PHOTOS to R.string.gallery_tab_photos,
        GalleryTab.ALBUMS to R.string.gallery_tab_albums,
        GalleryTab.TRASH to R.string.gallery_tab_trash
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
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun GridLoading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun GridEmpty(messageRes: Int) {
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
                text = stringResource(messageRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Album list: user albums, then one album per output subfolder, then a "New album" tile.
 */
@Composable
private fun AlbumList(
    albums: List<GalleryAlbum>,
    counts: Map<String, Int>,
    covers: Map<String, GalleryItem>,
    isOfflineMode: Boolean,
    onOpen: (GalleryAlbum) -> Unit,
    onNewAlbum: () -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        gridItemsIndexed(albums, key = { _, album -> album.id }) { _, album ->
            Column(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onOpen(album) }
            ) {
                val cover = covers[album.id]
                if (cover != null) {
                    GalleryItemCard(item = cover, isSelected = false, isOfflineMode = isOfflineMode, square = true)
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
                Row(
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (GalleryViewModel.isFolderAlbum(album.id)) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = stringResource(R.string.gallery_folder_album),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = (counts[album.id] ?: 0).toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
        }
        item(key = "new_album") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                    .clickable { onNewAlbum() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(32.dp))
                    Text(stringResource(R.string.gallery_new_album), style = MaterialTheme.typography.labelLarge)
                }
            }
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
