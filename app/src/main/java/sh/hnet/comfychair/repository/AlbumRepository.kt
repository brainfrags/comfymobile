package sh.hnet.comfychair.repository

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.GalleryAlbum
import sh.hnet.comfychair.storage.GalleryAlbumStore
import sh.hnet.comfychair.viewmodel.GalleryItem

/**
 * Albums of the current server, and the album currently selected.
 * Shared by the gallery and the generation screens so their album selection stays in sync:
 * opening an album in the gallery selects it for generation, and new images generated while
 * an album is selected are added to it.
 *
 * Two kinds of albums:
 * - Folder albums: subfolders of ComfyUI's output folder. Their items are the files in the
 *   folder; adding items moves the files there (see [GalleryRepository.moveToFolder]).
 *   IDs start with [FOLDER_PREFIX].
 * - Older app-only albums (kept on the device), which just remember their members.
 */
object AlbumRepository {
    const val FOLDER_PREFIX = "folder:"

    fun isFolder(albumId: String) = albumId.startsWith(FOLDER_PREFIX)
    fun folderOf(albumId: String) = albumId.removePrefix(FOLDER_PREFIX)
    fun folderAlbumId(folder: String) = FOLDER_PREFIX + folder

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gallery = GalleryRepository.getInstance()

    // App-only albums (persisted in albums.json)
    private val _localAlbums = MutableStateFlow<List<GalleryAlbum>>(emptyList())

    /** All albums: app-only ones first, then one per output subfolder (A-Z). */
    val albums: StateFlow<List<GalleryAlbum>> = combine(
        _localAlbums,
        gallery.galleryItems,
        gallery.library,
        gallery.serverFolders
    ) { local, items, library, serverFolders ->
        local + folderAlbums(items, library.folders + serverFolders, library.pendingMoves)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Selected album ID, or null for none (the whole gallery) */
    private val _currentAlbumId = MutableStateFlow<String?>(null)
    val currentAlbumId: StateFlow<String?> = _currentAlbumId.asStateFlow()

    private var appContext: Context? = null
    private var serverId: String? = null

    private fun itemKey(item: GalleryItem) = "${item.promptId}_${item.filename}"

    /** One album per output subfolder (also empty ones in [extraFolders]), with its items as members. */
    private fun folderAlbums(
        items: List<GalleryItem>,
        extraFolders: Set<String>,
        pendingMoves: Map<String, String>
    ): List<GalleryAlbum> {
        val byFolder = items.filter { it.type == "output" && it.subfolder.isNotEmpty() }.groupBy { it.subfolder }
        val pendingByFolder = pendingMoves.entries.groupBy({ it.value }, { it.key })
        return (byFolder.keys + extraFolders.filter { it.isNotBlank() } + pendingByFolder.keys)
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .map { folder ->
                GalleryAlbum(
                    id = folderAlbumId(folder),
                    name = folder,
                    members = byFolder[folder].orEmpty().mapTo(HashSet()) { itemKey(it) },
                    // Generated while this album was selected and not moved into it yet
                    prompts = pendingByFolder[folder].orEmpty().toSet()
                )
            }
    }

    /** Load albums for the current server (no-op if already loaded). Safe to call often. */
    @Synchronized
    fun ensureLoaded(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val id = ConnectionManager.currentServerId ?: return
        if (id == serverId) return
        serverId = id
        val loaded = GalleryAlbumStore.load(ctx, id)
        _localAlbums.value = loaded
        // Folder albums are only known once the gallery has loaded, so keep a folder selection as is
        _currentAlbumId.value = AppSettings.getCurrentAlbumId(ctx, id)
            ?.takeIf { a -> isFolder(a) || loaded.any { it.id == a } }
    }

    fun select(albumId: String?) {
        val id = albumId?.takeIf { a -> isFolder(a) || _localAlbums.value.any { it.id == a } }
        _currentAlbumId.value = id
        val ctx = appContext ?: return
        val server = serverId ?: return
        AppSettings.setCurrentAlbumId(ctx, server, id)
    }

    /** Change the app-only albums (folder albums change by moving files). */
    fun update(transform: (List<GalleryAlbum>) -> List<GalleryAlbum>) {
        val albums = synchronized(this) {
            transform(_localAlbums.value).also { _localAlbums.value = it }
        }
        val current = _currentAlbumId.value
        if (current != null && !isFolder(current) && albums.none { it.id == current }) select(null)
        val ctx = appContext ?: return
        val server = serverId ?: return
        scope.launch(Dispatchers.IO) { GalleryAlbumStore.save(ctx, server, albums) }
    }

    /**
     * Put everything a prompt generates into the selected album (if any). For a folder album
     * the files are moved into its folder once the gallery sees them.
     */
    fun addPromptToCurrent(promptId: String) {
        val albumId = _currentAlbumId.value ?: return
        if (isFolder(albumId)) {
            gallery.queuePromptMove(promptId, folderOf(albumId))
            return
        }
        update { list -> list.map { if (it.id == albumId) it.copy(prompts = it.prompts + promptId) else it } }
    }
}
