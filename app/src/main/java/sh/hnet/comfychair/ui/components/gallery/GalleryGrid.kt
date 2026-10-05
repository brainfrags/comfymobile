package sh.hnet.comfychair.ui.components.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.ActiveView
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.gallery.GalleryItem
import sh.hnet.comfychair.ui.components.GalleryThumbnailCache
import sh.hnet.comfychair.ui.components.rememberGalleryThumbnail
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import sh.hnet.comfychair.viewmodel.GallerySection
import sh.hnet.comfychair.viewmodel.GalleryUiState
import sh.hnet.comfychair.viewmodel.GalleryViewMode
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems

/**
 * The gallery's items, grouped by day, in the chosen view mode, with pull to refresh.
 * Taps, long presses, drag-to-select and drag-to-move are handled at grid level
 * (see [gallerySelectGestures]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryGrid(
    uiState: GalleryUiState,
    isOfflineMode: Boolean,
    onOpen: (key: String) -> Unit,
    onToggleSelection: (GalleryItem) -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    onReorderStart: () -> Unit,
    onMove: (fromKey: String, toKey: String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewMode = uiState.viewMode
    val isMasonry = viewMode == GalleryViewMode.MASONRY
    val lazyGridState = rememberLazyGridState()
    val staggeredState = rememberLazyStaggeredGridState()
    val gridState = remember(isMasonry) { GalleryGridState(isMasonry, lazyGridState, staggeredState) }
    val columns = viewMode.columns
    val spacing = if (columns >= 3) 4.dp else 8.dp

    val drag = remember { ReorderDragState() }
    val dragSelectState = remember { DragSelectState() }
    val haptics = LocalHapticFeedback.current
    val edgeThreshold = with(LocalDensity.current) { 64.dp.toPx() }
    val items by rememberUpdatedState(uiState.items)
    val selection by rememberUpdatedState(uiState.selectedItems)
    val isSelectionMode by rememberUpdatedState(uiState.isSelectionMode)
    val canReorder by rememberUpdatedState(uiState.canReorder)
    val open by rememberUpdatedState(onOpen)
    val toggleSelection by rememberUpdatedState(onToggleSelection)
    val changeSelection by rememberUpdatedState(onSelectionChange)
    val reorderStart by rememberUpdatedState(onReorderStart)
    val move by rememberUpdatedState(onMove)

    // Remembered so the gesture coroutine is not restarted on every recomposition
    val selectGestures = remember(gridState) {
        fun keyAt(pos: Offset) = drag.keyAt(pos, gridState.visibleKeys())
        Modifier.gallerySelectGestures(
            state = dragSelectState,
            haptics = haptics,
            edgeThreshold = edgeThreshold,
            keyAt = ::keyAt,
            keys = { items.map { it.key } },
            selection = { selection },
            isSelectionMode = { isSelectionMode },
            canReorder = { canReorder },
            onTap = { key ->
                val item = items.firstOrNull { it.key == key }
                if (item != null) {
                    if (isSelectionMode) toggleSelection(item) else open(key)
                }
            },
            onSelectionChange = { changeSelection(it) },
            onReorderStart = { key, pos ->
                // Dragging switches to the custom order (day headers go away)
                reorderStart()
                drag.start(key, pos)
            },
            onReorderMove = move@{ pos ->
                drag.moveTo(pos)
                val from = drag.draggingKey ?: return@move
                val target = keyAt(pos) ?: return@move
                if (target == from) return@move
                // Moving the first visible item would make the grid follow it; keep the scroll position
                val first = gridState.visibleKeys().firstOrNull()
                if (first == from || first == target) gridState.keepScrollPosition()
                move(from, target)
            },
            onReorderEnd = { drag.end() }
        )
    }

    // Auto-scroll while drag-selecting or moving an item near the top/bottom edge
    LaunchedEffect(dragSelectState.autoScrollSpeed) {
        val speed = dragSelectState.autoScrollSpeed
        if (speed != 0f) {
            while (true) {
                gridState.scrollBy(speed * 0.3f)
                dragSelectState.onAutoScrolled()
                delay(16)
            }
        }
    }

    // Start at the top when switching album, view mode or sort order
    LaunchedEffect(uiState.section, uiState.selectedAlbumId, viewMode, uiState.sortOrder) {
        gridState.scrollToTop()
    }

    GalleryPrefetch(uiState.items, gridState, columns)

    // Items split into days (one group without a header when sorted by name/type)
    val dateGroups = remember(uiState.items, uiState.sortOrder) {
        groupByDay(uiState.items, byDate = uiState.sortOrder.byDate)
    }
    val showPlaceholder = uiState.items.isEmpty()
    val emptyMessage = when {
        uiState.section == GallerySection.TRASH -> R.string.msg_trash_empty
        uiState.selectedAlbum != null -> R.string.msg_album_empty
        else -> R.string.msg_gallery_empty
    }

    // One grid cell; [placement] animates moves (not for the item being dragged)
    @Composable
    fun Cell(item: GalleryItem, placement: Modifier) {
        GalleryItemCard(
            item = item,
            isSelected = item.key in uiState.selectedItems,
            isOfflineMode = isOfflineMode,
            square = viewMode.square,
            modifier = drag.cellModifier(item.key, placement, draggedAlpha = 0.9f)
        )
    }

    @Composable
    fun Header(group: DateGroup) {
        DateGroupHeader(group, uiState.selectedItems, onSelectionChange)
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize()
    ) {
        // Always use a lazy grid for consistent nested scroll behavior with pull-to-refresh
        NoOverscrollContainer(modifier = Modifier.fillMaxSize()) {
            if (isMasonry) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(columns),
                    state = staggeredState,
                    contentPadding = PaddingValues(spacing),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalItemSpacing = spacing,
                    modifier = Modifier.fillMaxSize().then(selectGestures)
                ) {
                    if (showPlaceholder) {
                        item(span = StaggeredGridItemSpan.FullLine) { GalleryPlaceholder(uiState.isLoading, emptyMessage) }
                    } else {
                        dateGroups.forEach { group ->
                            item(key = "header_${group.day}", span = StaggeredGridItemSpan.FullLine) { Header(group) }
                            staggeredItems(group.items, key = { it.key }) { item -> Cell(item, Modifier.animateItem()) }
                        }
                    }
                }
            } else {
                // Regular grid for the square/single modes (stable layout)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = lazyGridState,
                    contentPadding = PaddingValues(spacing),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                    modifier = Modifier.fillMaxSize().then(selectGestures)
                ) {
                    if (showPlaceholder) {
                        item(span = { GridItemSpan(maxLineSpan) }) { GalleryPlaceholder(uiState.isLoading, emptyMessage) }
                    } else {
                        dateGroups.forEach { group ->
                            item(key = "header_${group.day}", span = { GridItemSpan(maxLineSpan) }) { Header(group) }
                            gridItems(group.items, key = { it.key }) { item -> Cell(item, Modifier.animateItem()) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The grid in use: a regular grid for the square/single modes, a staggered one for masonry.
 */
@Stable
private class GalleryGridState(
    val masonry: Boolean,
    val grid: LazyGridState,
    val staggered: LazyStaggeredGridState
) {
    val firstVisibleIndex: Int
        get() = if (masonry) staggered.firstVisibleItemIndex else grid.firstVisibleItemIndex

    val visibleCount: Int
        get() = if (masonry) staggered.layoutInfo.visibleItemsInfo.size else grid.layoutInfo.visibleItemsInfo.size

    fun visibleKeys(): List<Any> =
        if (masonry) staggered.layoutInfo.visibleItemsInfo.map { it.key }
        else grid.layoutInfo.visibleItemsInfo.map { it.key }

    suspend fun scrollBy(pixels: Float) {
        if (masonry) staggered.scrollBy(pixels) else grid.scrollBy(pixels)
    }

    suspend fun scrollToTop() {
        if (masonry) staggered.scrollToItem(0) else grid.scrollToItem(0)
    }

    /** Stay where we are when the first visible item moves (otherwise the grid follows it). */
    fun keepScrollPosition() {
        if (masonry) staggered.requestScrollToItem(staggered.firstVisibleItemIndex, staggered.firstVisibleItemScrollOffset)
        else grid.requestScrollToItem(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset)
    }
}

/**
 * Keep thumbnails around the visible items loaded: cache priorities follow the scroll
 * position, and the first screen is prefetched when the items change.
 */
@Composable
private fun GalleryPrefetch(items: List<GalleryItem>, gridState: GalleryGridState, columns: Int) {
    val firstVisibleIndex = gridState.firstVisibleIndex
    val visibleCount = gridState.visibleCount
    val prefetchItems = remember(items) { items.map { it.toPrefetchItem() } }

    // Only update when Gallery is the active view (not when MediaViewer is animating closed)
    LaunchedEffect(firstVisibleIndex, visibleCount) {
        if (prefetchItems.isNotEmpty() && MediaCache.isActiveView(ActiveView.GALLERY)) {
            // Debounce scroll updates to avoid excessive calls during fast scrolling
            delay(100)
            MediaCache.updateGalleryPosition(
                firstVisibleIndex = firstVisibleIndex,
                visibleItemCount = visibleCount,
                allItems = prefetchItems,
                columnsInGrid = columns
            )
        }
    }

    // Prefetch when items become available or change (e.g., after manual refresh)
    val itemsKey = remember(items) { if (items.isEmpty()) "" else "${items.size}_${items.first().promptId}" }
    LaunchedEffect(itemsKey) {
        if (items.isNotEmpty()) {
            // From the current scroll position instead of always starting from 0
            val start = firstVisibleIndex.coerceIn(0, items.size)
            val end = (start + 24).coerceAtMost(items.size)
            MediaCache.initialPrefetch(prefetchItems.subList(start, end))
        }
    }
}

private fun GalleryItem.toPrefetchItem() = MediaCache.PrefetchItem(
    key = toCacheKey(),
    isVideo = isVideo,
    subfolder = subfolder,
    type = type
)

/** Spinner while loading, else the empty message; spans the whole grid width. */
@Composable
private fun GalleryPlaceholder(isLoading: Boolean, emptyMessage: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    text = stringResource(emptyMessage),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Items generated on the same day. [day] = epoch day, or null for no header. */
private class DateGroup(val day: Long?, val items: List<GalleryItem>)

private const val UNKNOWN_DAY = Long.MIN_VALUE

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

/**
 * Day header ("Today", "Yesterday", or the date) with a select-all / deselect button
 * for that day's items. Nothing is shown for a group without a day.
 */
@Composable
private fun DateGroupHeader(
    group: DateGroup,
    selected: Set<String>,
    onSelectionChange: (Set<String>) -> Unit
) {
    val day = group.day ?: return
    val label = when (day) {
        UNKNOWN_DAY -> stringResource(R.string.gallery_date_unknown)
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
    val keys = group.items.map { it.key }
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

/**
 * Gallery item card with lazy bitmap loading.
 * Bitmaps are loaded from MediaCache on-demand.
 */
@Composable
private fun GalleryItemCard(
    item: GalleryItem,
    isSelected: Boolean,
    isOfflineMode: Boolean,
    square: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val (bitmap, isLoading) = rememberGalleryThumbnail(item, context)

    // Square crop, or the media's original aspect ratio (remembered once known)
    val aspect = if (square) 1f else (
        bitmap?.let { if (it.height > 0) it.width.toFloat() / it.height else null }
            ?: GalleryThumbnailCache.aspectRatio(item.key)
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
            when {
                bitmap != null -> {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(
                            if (item.isVideo) R.string.content_description_gallery_video_thumbnail
                            else R.string.content_description_gallery_thumbnail
                        ),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                isLoading -> {
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
                    // Not available: CloudOff in offline mode, otherwise the media type
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                isOfflineMode -> Icons.Default.CloudOff
                                item.isVideo -> Icons.Default.PlayArrow
                                else -> Icons.Default.Image
                            },
                            contentDescription = if (isOfflineMode) stringResource(R.string.content_description_not_cached) else null,
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
