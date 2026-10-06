package sh.hnet.comfychair.viewmodel

import sh.hnet.comfychair.gallery.GalleryItem
import sh.hnet.comfychair.storage.GalleryAlbum
import sh.hnet.comfychair.storage.GalleryLibrary

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
    TYPE;

    /** Items grouped by day (sorted by date) */
    val byDate: Boolean
        get() = this == NEWEST || this == OLDEST

    fun apply(items: List<GalleryItem>): List<GalleryItem> = when (this) {
        NEWEST -> items
        OLDEST -> items.asReversed()
        NAME -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.filename })
        TYPE -> items.sortedBy { it.isVideo }
    }
}

/**
 * New dates that put the dragged item at [pos] in its place in a list sorted by date.
 *
 * @param times Dates of the list's items in list order, the dragged item already at [pos]
 * @param anchor Index of the item it was dropped next to; it takes that item's day
 * @param step How the date changes going down the list: -1 ms newest first, +1 oldest first
 * @return List index -> new date, for the items whose date changes. Neighbours with the same
 *   date (images of one batch) are pushed 1 ms apart so the order is exact.
 */
internal fun datesForMove(times: List<Long>, pos: Int, anchor: Int, step: Long): Map<Int, Long> {
    val zone = java.time.ZoneId.systemDefault()
    fun day(time: Long) = java.time.Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    val t = times.toMutableList()
    val above = t.getOrNull(pos - 1)?.takeIf { it > 0 }
    val below = t.getOrNull(pos + 1)?.takeIf { it > 0 }
    // Halfway between the new neighbours if they are on the same day, else just next to the anchor
    val mid = if (above != null && below != null && day(above) == day(below)) above + (below - above) / 2 else null
    t[pos] = when {
        mid != null && mid != above && mid != below -> mid
        anchor < pos -> t[anchor] + step
        else -> t[anchor] - step
    }
    var i = pos + 1
    while (i < t.size && t[i] > 0 && (t[i] - t[i - 1]) * step <= 0) { t[i] = t[i - 1] + step; i++ }
    i = pos - 1
    while (i >= 0 && t[i] > 0 && (t[i + 1] - t[i]) * step <= 0) { t[i] = t[i + 1] - step; i-- }
    return t.indices.filter { t[it] != times[it] }.associateWith { t[it] }
}

/** Order of the album list. */
enum class AlbumSortOrder {
    NAME,
    /** Album with the newest image first */
    RECENT,
    /** Most images first */
    COUNT,
    /** Order set by holding and dragging albums */
    CUSTOM;

    /**
     * @param albumItems Items of each album (album id -> items)
     * @param newestFirst Every gallery item, newest first
     * @param customOrder Album ids in the order set by dragging
     */
    fun apply(
        albums: List<GalleryAlbum>,
        albumItems: Map<String, List<GalleryItem>>,
        newestFirst: List<GalleryItem>,
        customOrder: List<String>
    ): List<GalleryAlbum> = when (this) {
        NAME -> albums.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        COUNT -> albums.sortedByDescending { albumItems[it.id]?.size ?: 0 }
        RECENT -> {
            // Position of each item in the gallery (newest first); empty albums go last
            val rank = HashMap<String, Int>(newestFirst.size * 2)
            newestFirst.forEachIndexed { i, item -> rank.putIfAbsent(item.key, i) }
            albums.sortedBy { a -> albumItems[a.id].orEmpty().minOfOrNull { rank[it.key] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE }
        }
        CUSTOM -> {
            val position = customOrder.withIndex().associate { it.value to it.index }
            // Albums not placed yet (new ones) go last, by name
            albums.sortedWith(
                compareBy<GalleryAlbum> { position[it.id] ?: Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
        }
    }
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
    val viewMode: GalleryViewMode = GalleryViewMode.GRID_2,
    /** Order of [items]: the open album's own order if it has one, else the gallery's */
    val sortOrder: GallerySortOrder = GallerySortOrder.NEWEST,
    val albumSortOrder: AlbumSortOrder = AlbumSortOrder.NAME,
    /** Albums in [albumSortOrder]; hidden ones only while [showHiddenAlbums] */
    val albums: List<GalleryAlbum> = emptyList(),
    /** IDs of the albums hidden from the album list */
    val hiddenAlbumIds: Set<String> = emptySet(),
    val showHiddenAlbums: Boolean = false,
    /** null = all items */
    val selectedAlbumId: String? = null,
    /** Number of existing gallery items in each album (albumId -> count) */
    val albumCounts: Map<String, Int> = emptyMap(),
    /** Cover item of each album (its first item in the current sort order) */
    val albumCovers: Map<String, GalleryItem> = emptyMap(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    /** [GalleryItem.key]s of the selected items */
    val selectedItems: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val trashCount: Int = 0,
    /** Root copies of images that are also in an album, waiting for the user to confirm deleting them */
    val rootDuplicates: List<Pair<String, String>>? = null,
    /** Files are being moved between album folders */
    val isMoving: Boolean = false,
    /** Move mode: a tap picks an image, arrows on it move it (no selecting) */
    val isMoveMode: Boolean = false,
    /** [GalleryItem.key] of the image picked in move mode */
    val movingKey: String? = null
) {
    val selectedAlbum: GalleryAlbum?
        get() = albums.firstOrNull { it.id == selectedAlbumId }

    /** An album is open (not the album list) */
    val isInAlbum: Boolean
        get() = section == GallerySection.ALBUMS && selectedAlbum != null

    /** The album list is shown */
    val isAlbumList: Boolean
        get() = section == GallerySection.ALBUMS && selectedAlbum == null

    /**
     * Items can be moved (move mode), which changes their date: only when sorted by date,
     * and not in the trash or on the album list.
     */
    val canReorder: Boolean
        get() = sortOrder.byDate &&
            (section == GallerySection.PHOTOS || (section == GallerySection.ALBUMS && selectedAlbumId != null))
}

/**
 * Events emitted by gallery operations
 */
sealed class GalleryEvent {
    data class ShowToast(val messageResId: Int) : GalleryEvent()
}

/** View settings and running operations of the gallery screen (not from the repositories). */
internal data class GalleryViewState(
    val viewMode: GalleryViewMode = GalleryViewMode.GRID_2,
    val sortOrder: GallerySortOrder = GallerySortOrder.NEWEST,
    val albumSortOrder: AlbumSortOrder = AlbumSortOrder.NAME,
    val section: GallerySection = GallerySection.PHOTOS,
    val showHiddenAlbums: Boolean = false,
    val selection: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val isMoving: Boolean = false,
    val rootDuplicates: List<Pair<String, String>>? = null,
    val isMoveMode: Boolean = false,
    val movingKey: String? = null
)

/** The gallery's data as the repositories hold it. */
internal class GallerySource(
    /** Items not in the trash, newest first */
    val items: List<GalleryItem>,
    val trashed: List<GalleryItem>,
    val library: GalleryLibrary,
    val albums: List<GalleryAlbum>,
    val currentAlbumId: String?,
    val isLoading: Boolean,
    val isRefreshing: Boolean
)

/** What the gallery screen shows for [source] with the view settings [view]. */
internal fun buildGalleryUiState(source: GallerySource, view: GalleryViewState): GalleryUiState {
    // Every album still keeps its items out of Photos, hidden or not
    val albums = source.albums
    val hidden = source.library.hiddenAlbums
    val shownAlbums = if (view.showHiddenAlbums) albums else albums.filter { it.id !in hidden }
    // Grid keys must be unique
    val unique = source.items.distinctBy { it.key }
    val items = view.sortOrder.apply(unique)
    val album = shownAlbums.firstOrNull { it.id == source.currentAlbumId }
    // An open album can have its own order
    val albumSort = album?.takeIf { view.section == GallerySection.ALBUMS }
        ?.let { a -> source.library.albumSorts[a.id]?.let { name -> GallerySortOrder.entries.firstOrNull { it.name == name } } }
    val sortOrder = albumSort ?: view.sortOrder
    fun GalleryAlbum.has(item: GalleryItem) = contains(item.promptId, item.key)
    // Photos shows only items not yet sorted into any album
    val unsorted = items.filter { item -> albums.none { it.has(item) } }
    val albumItems = albums.associate { a -> a.id to items.filter { a.has(it) } }
    val covers = source.library.covers
    return GalleryUiState(
        section = view.section,
        items = when {
            view.section == GallerySection.PHOTOS -> unsorted
            view.section == GallerySection.TRASH -> source.trashed.distinctBy { it.key }
            album != null -> albumSort?.apply(unique.filter { album.has(it) })
                ?: albumItems[album.id].orEmpty()
            else -> emptyList()
        },
        viewMode = view.viewMode,
        sortOrder = sortOrder,
        albumSortOrder = view.albumSortOrder,
        albums = view.albumSortOrder.apply(shownAlbums, albumItems, source.items, source.library.albumOrder),
        hiddenAlbumIds = hidden,
        showHiddenAlbums = view.showHiddenAlbums,
        selectedAlbumId = album?.id,
        albumCounts = albumItems.mapValues { it.value.size },
        // The chosen cover if it is still in the album, else the first item
        albumCovers = albumItems.mapNotNull { (id, list) ->
            val chosen = covers[id]?.let { libraryId -> list.firstOrNull { it.libraryId == libraryId } }
            (chosen ?: list.firstOrNull())?.let { id to it }
        }.toMap(),
        isLoading = source.isLoading,
        isRefreshing = source.isRefreshing, // Only show indicator for manual refresh
        selectedItems = view.selection,
        isSelectionMode = view.isSelectionMode,
        trashCount = source.trashed.size,
        rootDuplicates = view.rootDuplicates,
        isMoving = view.isMoving
    ).withMoveMode(view)
}

/** Move mode only where items can be moved; the picked image only while it is shown. */
private fun GalleryUiState.withMoveMode(view: GalleryViewState): GalleryUiState =
    if (!view.isMoveMode || !canReorder) this
    else copy(isMoveMode = true, movingKey = view.movingKey?.takeIf { key -> items.any { it.key == key } })
