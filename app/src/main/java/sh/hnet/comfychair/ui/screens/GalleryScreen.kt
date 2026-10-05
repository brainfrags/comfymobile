package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.ActiveView
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.GalleryAlbum
import sh.hnet.comfychair.ui.components.gallery.AddToAlbumDialog
import sh.hnet.comfychair.ui.components.gallery.AlbumGrid
import sh.hnet.comfychair.ui.components.gallery.AlbumNameDialog
import sh.hnet.comfychair.ui.components.gallery.ConfirmDeleteDialog
import sh.hnet.comfychair.ui.components.gallery.GalleryGrid
import sh.hnet.comfychair.ui.components.gallery.GalleryTopBar
import sh.hnet.comfychair.ui.components.gallery.GalleryTopBarDialogs
import sh.hnet.comfychair.viewmodel.ConnectionStatus
import sh.hnet.comfychair.viewmodel.GalleryEvent
import sh.hnet.comfychair.viewmodel.GallerySection
import sh.hnet.comfychair.viewmodel.GalleryViewModel
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.MediaViewerItem

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
    val isOfflineMode = remember { AppSettings.isOfflineMode(context) }

    LaunchedEffect(Unit) {
        galleryViewModel.initialize(context)
    }

    // Load gallery when connected
    LaunchedEffect(connectionStatus) {
        if (connectionStatus == ConnectionStatus.CONNECTED) galleryViewModel.loadGallery()
        galleryViewModel.reloadAlbums()
    }

    LaunchedEffect(Unit) {
        galleryViewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.ShowToast -> Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val mediaViewerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Deleting in the viewer only moves items to the trash (local), so nothing to reload
        MediaCache.setActiveView(ActiveView.GALLERY)
    }

    /**
     * Open the media viewer at [key] (null = the last item, where the slideshow starts).
     * The item is looked up by key in the latest list, so a refresh between the tap and
     * here can't open a different image.
     */
    fun launchMediaViewer(key: String?, startSlideshow: Boolean = false) {
        val state = galleryViewModel.uiState.value
        val items = state.items
        val index = if (key == null) items.lastIndex else items.indexOfFirst { it.key == key }
        if (index !in items.indices) return

        MediaCache.setActiveView(ActiveView.MEDIA_VIEWER)
        // Protect the items around the opened one in the cache
        MediaCache.updateNavigationPriorities(index, items.map { it.toCacheKey() })

        val intent = MediaViewerActivity.createGalleryIntent(
            context = context,
            hostname = ConnectionManager.hostname,
            port = ConnectionManager.port,
            items = items.map { MediaViewerItem(it.promptId, it.filename, it.subfolder, it.type, it.isVideo, it.index) },
            initialIndex = index,
            startSlideshow = startSlideshow,
            isTrash = state.section == GallerySection.TRASH
        )
        mediaViewerLauncher.launch(intent)
    }

    // Dialogs
    var showNewAlbumDialog by remember { mutableStateOf(false) }
    var showAddToAlbumDialog by remember { mutableStateOf(false) }
    var editingAlbum by remember { mutableStateOf<GalleryAlbum?>(null) }
    var showEmptyTrashDialog by remember { mutableStateOf(false) }
    var showDeleteForeverDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        GalleryTopBar(
            uiState = uiState,
            viewModel = galleryViewModel,
            dialogs = GalleryTopBarDialogs(
                onNewAlbum = { showNewAlbumDialog = true },
                onAddToAlbum = { showAddToAlbumDialog = true },
                onEditAlbum = { editingAlbum = uiState.selectedAlbum },
                onDeleteForever = { showDeleteForeverDialog = true },
                onEmptyTrash = { showEmptyTrashDialog = true }
            ),
            onSlideshow = { launchMediaViewer(null, startSlideshow = true) },
            onNavigateToSettings = onNavigateToSettings,
            onLogout = onLogout
        )

        // Files are being moved between album folders
        if (uiState.isMoving) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            HorizontalDivider()
        }

        // Back: leave selection mode, then the opened album
        BackHandler(enabled = uiState.isSelectionMode || uiState.isInAlbum) {
            if (uiState.isSelectionMode) galleryViewModel.clearSelection() else galleryViewModel.selectAlbum(null)
        }

        if (uiState.isAlbumList) {
            AlbumGrid(
                albums = uiState.albums,
                counts = uiState.albumCounts,
                covers = uiState.albumCovers,
                hiddenIds = uiState.hiddenAlbumIds,
                onOpen = galleryViewModel::selectAlbum,
                onNewAlbum = { showNewAlbumDialog = true },
                onReorderStart = galleryViewModel::beginAlbumReorder,
                onMove = galleryViewModel::moveAlbum
            )
        } else {
            GalleryGrid(
                uiState = uiState,
                isOfflineMode = isOfflineMode,
                onOpen = { key -> launchMediaViewer(key) },
                onToggleSelection = galleryViewModel::toggleSelection,
                onSelectionChange = galleryViewModel::setSelection,
                onReorderStart = galleryViewModel::beginReorder,
                onMove = galleryViewModel::moveItem,
                onRefresh = galleryViewModel::manualRefresh
            )
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
            },
            isHidden = album.id in uiState.hiddenAlbumIds,
            onToggleHidden = {
                galleryViewModel.setAlbumHidden(album.id, hidden = album.id !in uiState.hiddenAlbumIds)
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
            onConfirm = galleryViewModel::deleteDuplicates,
            onDismiss = galleryViewModel::dismissDuplicates
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
