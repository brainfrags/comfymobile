package sh.hnet.comfychair.ui.components.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.gallery.GalleryItem
import sh.hnet.comfychair.repository.AlbumRepository
import sh.hnet.comfychair.storage.GalleryAlbum
import sh.hnet.comfychair.ui.components.rememberGalleryThumbnail

/**
 * Album list: cover, name and item count per album, plus a "new album" tile.
 * Hold an album for its menu (rename, show/hide, delete); hold and drag it to move it.
 */
@Composable
fun AlbumGrid(
    albums: List<GalleryAlbum>,
    counts: Map<String, Int>,
    covers: Map<String, GalleryItem>,
    hiddenIds: Set<String>,
    onOpen: (String) -> Unit,
    onNewAlbum: () -> Unit,
    onReorderStart: () -> Unit,
    onMove: (fromId: String, toId: String) -> Unit,
    onRename: (GalleryAlbum) -> Unit,
    onToggleHidden: (GalleryAlbum) -> Unit,
    onDelete: (GalleryAlbum) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val gridState = rememberLazyGridState()
    val drag = remember { ReorderDragState() }
    val reorderStart by rememberUpdatedState(onReorderStart)
    val move by rememberUpdatedState(onMove)
    // Album whose menu is open (held and released without dragging)
    var menuAlbumId by remember { mutableStateOf<String?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                fun albumAt(pos: Offset) =
                    drag.keyAt(pos, gridState.layoutInfo.visibleItemsInfo.map { it.key })
                // Held album, where it was held and how far the finger moved since
                var heldId: String? = null
                var heldAt = Offset.Zero
                var moved = Offset.Zero
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        heldId = albumAt(pos) ?: return@detectDragGesturesAfterLongPress
                        heldAt = pos
                        moved = Offset.Zero
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { change, amount ->
                        val held = heldId ?: return@detectDragGesturesAfterLongPress
                        change.consume()
                        moved += amount
                        // Dragging starts once the finger really moves; until then it's a hold (menu)
                        if (drag.draggingKey == null) {
                            if (moved.getDistance() < viewConfiguration.touchSlop) return@detectDragGesturesAfterLongPress
                            reorderStart()
                            drag.start(held, heldAt)
                        }
                        val from = drag.draggingKey ?: return@detectDragGesturesAfterLongPress
                        drag.moveTo(heldAt + moved)
                        val target = albumAt(drag.fingerPosition)
                        if (target != null && target != from) {
                            // Keep the scroll position when the first visible album moves
                            val first = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.key
                            if (first == from || first == target) {
                                gridState.requestScrollToItem(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                            }
                            move(from, target)
                        }
                    },
                    onDragEnd = {
                        if (drag.draggingKey == null) menuAlbumId = heldId
                        heldId = null
                        drag.end()
                    },
                    onDragCancel = {
                        heldId = null
                        drag.end()
                    }
                )
            }
    ) {
        items(albums, key = { it.id }) { album ->
            val isHidden = album.id in hiddenIds
            Box(modifier = drag.cellModifier(album.id, Modifier.animateItem())) {
                AlbumTile(
                    album = album,
                    count = counts[album.id] ?: 0,
                    cover = covers[album.id],
                    isHidden = isHidden,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onOpen(album.id) }
                )
                DropdownMenu(expanded = menuAlbumId == album.id, onDismissRequest = { menuAlbumId = null }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.workflow_menu_rename)) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menuAlbumId = null; onRename(album) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (isHidden) R.string.gallery_unhide_album else R.string.gallery_hide_album)) },
                        leadingIcon = { Icon(if (isHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = null) },
                        onClick = { menuAlbumId = null; onToggleHidden(album) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.button_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuAlbumId = null; onDelete(album) }
                    )
                }
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
private fun AlbumTile(
    album: GalleryAlbum,
    count: Int,
    cover: GalleryItem?,
    isHidden: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                // Hidden albums are only listed while hidden albums are shown; dimmed then
                .alpha(if (isHidden) 0.5f else 1f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
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
            if (isHidden) {
                Icon(
                    Icons.Default.VisibilityOff,
                    contentDescription = stringResource(R.string.gallery_hidden_album),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
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
            stringResource(R.string.gallery_album_item_count, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp)
        )
    }
}
