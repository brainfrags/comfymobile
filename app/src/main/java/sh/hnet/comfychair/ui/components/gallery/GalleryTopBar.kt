package sh.hnet.comfychair.ui.components.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewComfy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.AppMenuDropdown
import sh.hnet.comfychair.viewmodel.AlbumSortOrder
import sh.hnet.comfychair.viewmodel.GallerySection
import sh.hnet.comfychair.viewmodel.GallerySortOrder
import sh.hnet.comfychair.viewmodel.GalleryUiState
import sh.hnet.comfychair.viewmodel.GalleryViewMode
import sh.hnet.comfychair.viewmodel.GalleryViewModel

/** Dialogs the top bar opens (they live in the screen). */
class GalleryTopBarDialogs(
    val onNewAlbum: () -> Unit,
    val onAddToAlbum: () -> Unit,
    val onEditAlbum: () -> Unit,
    val onDeleteForever: () -> Unit,
    val onEmptyTrash: () -> Unit
)

/**
 * Gallery app bar. Its title and actions depend on what is shown:
 * selection mode, an open album, the album list, the trash, or Photos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryTopBar(
    uiState: GalleryUiState,
    viewModel: GalleryViewModel,
    dialogs: GalleryTopBarDialogs,
    onSlideshow: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit
) {
    TopAppBar(
        title = {
            when {
                uiState.isSelectionMode -> Text(stringResource(R.string.gallery_selected_count, uiState.selectedItems.size))
                uiState.isInAlbum -> Text(uiState.selectedAlbum!!.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Photos / Albums / Trash tabs at the start of the app bar
                else -> GalleryTabs(selected = uiState.section, onSelect = viewModel::setSection)
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
        navigationIcon = {
            if (uiState.isSelectionMode) {
                IconButton(onClick = viewModel::clearSelection) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.button_cancel_selection))
                }
            } else if (uiState.isInAlbum) {
                IconButton(onClick = { viewModel.selectAlbum(null) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
                }
            }
        },
        actions = {
            when {
                uiState.isSelectionMode -> SelectionActions(uiState, viewModel, dialogs)
                uiState.section == GallerySection.TRASH -> {
                    IconButton(onClick = dialogs.onEmptyTrash, enabled = uiState.items.isNotEmpty()) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.gallery_empty_trash))
                    }
                    SelectButton(viewModel, enabled = uiState.items.isNotEmpty())
                }
                uiState.isAlbumList -> {
                    AlbumListMenu(uiState, viewModel)
                    IconButton(onClick = dialogs.onNewAlbum) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = stringResource(R.string.gallery_new_album))
                    }
                }
                else -> {
                    // In an album, its own sort order gets a button of its own
                    if (uiState.isInAlbum) ItemSortMenu(uiState.sortOrder, viewModel::setSortOrder)
                    ViewMenu(uiState, viewModel, onSlideshow)
                    if (uiState.isInAlbum) {
                        IconButton(onClick = dialogs.onEditAlbum) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.gallery_edit_album))
                        }
                    }
                    SelectButton(viewModel)
                }
            }
            if (!uiState.isSelectionMode) {
                AppMenuDropdown(onSettings = onNavigateToSettings, onLogout = onLogout)
            }
        }
    )
}

@Composable
private fun SelectButton(viewModel: GalleryViewModel, enabled: Boolean = true) {
    IconButton(onClick = viewModel::enterSelectionMode, enabled = enabled) {
        Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.button_gallery_select))
    }
}

/** Actions on the selected items. */
@Composable
private fun SelectionActions(uiState: GalleryUiState, viewModel: GalleryViewModel, dialogs: GalleryTopBarDialogs) {
    val context = LocalContext.current
    // Select all / deselect all (the shown items)
    val allSelected = uiState.items.isNotEmpty() && uiState.items.all { it.key in uiState.selectedItems }
    IconButton(onClick = { if (allSelected) viewModel.clearSelection() else viewModel.selectAll() }) {
        Icon(
            if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
            contentDescription = stringResource(if (allSelected) R.string.gallery_deselect_all else R.string.gallery_select_all)
        )
    }
    if (uiState.section == GallerySection.TRASH) {
        // Trash: restore or delete for good
        IconButton(onClick = viewModel::restoreSelected) {
            Icon(Icons.Default.RestoreFromTrash, contentDescription = stringResource(R.string.gallery_restore))
        }
        IconButton(onClick = dialogs.onDeleteForever, enabled = uiState.selectedItems.isNotEmpty()) {
            Icon(
                Icons.Default.DeleteForever,
                contentDescription = stringResource(R.string.gallery_delete_forever),
                tint = MaterialTheme.colorScheme.error
            )
        }
        return
    }
    IconButton(onClick = viewModel::deleteSelected) {
        Icon(
            Icons.Default.Delete,
            contentDescription = stringResource(R.string.button_delete_history_item),
            tint = MaterialTheme.colorScheme.error
        )
    }
    IconButton(onClick = dialogs.onAddToAlbum) {
        Icon(Icons.Default.LibraryAdd, contentDescription = stringResource(R.string.gallery_add_to_album))
    }
    val album = uiState.selectedAlbum
    if (uiState.isInAlbum && album != null) {
        if (uiState.selectedItems.size == 1) {
            IconButton(onClick = { viewModel.setSelectedAsCover(album.id) }) {
                Icon(Icons.Default.Wallpaper, contentDescription = stringResource(R.string.gallery_set_cover))
            }
        }
        IconButton(onClick = { viewModel.removeSelectedFromAlbum(album.id) }) {
            Icon(Icons.Default.RemoveCircleOutline, contentDescription = stringResource(R.string.gallery_remove_from_album))
        }
    }
    IconButton(onClick = { viewModel.saveSelectedToGallery(context) }) {
        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.button_save_image))
    }
    IconButton(onClick = { viewModel.shareSelected(context) }) {
        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.button_share))
    }
}

/** Item sort orders with their labels */
private val ITEM_SORT_ORDERS = listOf(
    GallerySortOrder.NEWEST to R.string.gallery_sort_newest,
    GallerySortOrder.OLDEST to R.string.gallery_sort_oldest,
    GallerySortOrder.NAME to R.string.gallery_sort_name,
    GallerySortOrder.TYPE to R.string.gallery_sort_type,
    GallerySortOrder.CUSTOM to R.string.gallery_sort_custom
)

/** Order of the items in an open album. */
@Composable
private fun ItemSortMenu(selected: GallerySortOrder, onSelect: (GallerySortOrder) -> Unit) {
    Box {
        var expanded by remember { mutableStateOf(false) }
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.gallery_sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ITEM_SORT_ORDERS.forEach { (order, labelRes) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = {
                        onSelect(order)
                        expanded = false
                    },
                    trailingIcon = { if (selected == order) Icon(Icons.Default.Check, contentDescription = null) }
                )
            }
        }
    }
}

/**
 * One menu for slideshow, duplicate cleanup, view mode and (outside albums) sort order
 * (keeps room for the tabs).
 */
@Composable
private fun ViewMenu(uiState: GalleryUiState, viewModel: GalleryViewModel, onSlideshow: () -> Unit) {
    Box {
        var expanded by remember { mutableStateOf(false) }
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.gallery_view_mode))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.gallery_slideshow)) },
                onClick = {
                    expanded = false
                    onSlideshow()
                },
                enabled = uiState.items.isNotEmpty(),
                leadingIcon = { Icon(Icons.Default.Slideshow, contentDescription = null) }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.gallery_clean_duplicates)) },
                onClick = {
                    expanded = false
                    viewModel.findDuplicates()
                },
                leadingIcon = { Icon(Icons.Default.CleaningServices, contentDescription = null) }
            )
            HorizontalDivider()
            GalleryViewMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(stringResource(mode.label)) },
                    onClick = {
                        viewModel.setViewMode(mode)
                        expanded = false
                    },
                    leadingIcon = { Icon(mode.icon, contentDescription = null) },
                    trailingIcon = { if (mode == uiState.viewMode) Icon(Icons.Default.Check, contentDescription = null) }
                )
            }
            if (uiState.isInAlbum) return@DropdownMenu
            HorizontalDivider()
            ITEM_SORT_ORDERS.forEach { (order, labelRes) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = {
                        viewModel.setSortOrder(order)
                        expanded = false
                    },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) },
                    trailingIcon = { if (uiState.sortOrder == order) Icon(Icons.Default.Check, contentDescription = null) }
                )
            }
        }
    }
}

/** Order of the album list, and whether hidden albums are shown. */
@Composable
private fun AlbumListMenu(uiState: GalleryUiState, viewModel: GalleryViewModel) {
    Box {
        var expanded by remember { mutableStateOf(false) }
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.gallery_sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                AlbumSortOrder.NAME to R.string.album_sort_name,
                AlbumSortOrder.RECENT to R.string.album_sort_recent,
                AlbumSortOrder.COUNT to R.string.album_sort_count,
                AlbumSortOrder.CUSTOM to R.string.gallery_sort_custom
            ).forEach { (order, labelRes) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = {
                        viewModel.setAlbumSortOrder(order)
                        expanded = false
                    },
                    trailingIcon = { if (uiState.albumSortOrder == order) Icon(Icons.Default.Check, contentDescription = null) }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.gallery_show_hidden_albums)) },
                onClick = {
                    viewModel.setShowHiddenAlbums(!uiState.showHiddenAlbums)
                    expanded = false
                },
                leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null) },
                trailingIcon = { if (uiState.showHiddenAlbums) Icon(Icons.Default.Check, contentDescription = null) }
            )
        }
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

private val GalleryViewMode.icon: ImageVector
    get() = when (this) {
        GalleryViewMode.GRID_2 -> Icons.Default.GridView
        GalleryViewMode.GRID_3 -> Icons.Default.Apps
        GalleryViewMode.GRID_4 -> Icons.Default.ViewComfy
        GalleryViewMode.MASONRY -> Icons.Default.Dashboard
        GalleryViewMode.SINGLE -> Icons.Default.ViewAgenda
    }

private val GalleryViewMode.label: Int
    get() = when (this) {
        GalleryViewMode.GRID_2 -> R.string.gallery_view_grid_2
        GalleryViewMode.GRID_3 -> R.string.gallery_view_grid_3
        GalleryViewMode.GRID_4 -> R.string.gallery_view_grid_4
        GalleryViewMode.MASONRY -> R.string.gallery_view_masonry
        GalleryViewMode.SINGLE -> R.string.gallery_view_single
    }
