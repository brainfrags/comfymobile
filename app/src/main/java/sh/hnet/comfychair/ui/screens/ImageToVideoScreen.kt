package sh.hnet.comfychair.ui.screens

import sh.hnet.comfychair.ui.components.generate.ratioOf
import sh.hnet.comfychair.ui.components.generate.WorkflowSplitDropdown
import sh.hnet.comfychair.ui.components.generate.SourceResultToggle
import sh.hnet.comfychair.ui.components.generate.SmallActionIcon
import sh.hnet.comfychair.ui.components.generate.PromptCardContent
import sh.hnet.comfychair.ui.components.generate.ProgressPill
import sh.hnet.comfychair.ui.components.generate.PreviewPlaceholder
import sh.hnet.comfychair.ui.components.generate.MetaActionsRow
import sh.hnet.comfychair.ui.components.generate.GenerationScreenLayout
import sh.hnet.comfychair.ui.components.generate.GenerateWithGalleryRow
import sh.hnet.comfychair.ui.components.generate.AlbumDropdown
import sh.hnet.comfychair.repository.AlbumRepository
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowEditorActivity
import sh.hnet.comfychair.model.ScreenType
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.ui.components.AppMenuDropdown
import sh.hnet.comfychair.ui.theme.Dimensions
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.GenerationButton
import sh.hnet.comfychair.ui.components.GenerationProgressBar
import sh.hnet.comfychair.ui.components.PromptLibraryDialog
import sh.hnet.comfychair.ui.components.PromptPresetDialog
import sh.hnet.comfychair.ui.components.shared.PromptPresetDropdown
import sh.hnet.comfychair.ui.components.shared.rememberSpellCheckVisualTransformation
import sh.hnet.comfychair.ui.components.config.ConfigBottomSheetContent
import sh.hnet.comfychair.ui.components.config.UnifiedCallbacks
import sh.hnet.comfychair.ui.components.config.toBottomSheetConfig
import sh.hnet.comfychair.ui.components.VideoPlayer
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.viewmodel.ContentType
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoEvent
import sh.hnet.comfychair.viewmodel.ImageToVideoViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoViewMode
import sh.hnet.comfychair.viewmodel.PromptPresetEvent
import sh.hnet.comfychair.viewmodel.PromptPresetViewModel
import sh.hnet.comfychair.viewmodel.MediaViewerItem
import sh.hnet.comfychair.ui.components.generate.WorkflowChip
import sh.hnet.comfychair.ui.components.generate.RecentResultsStrip
import sh.hnet.comfychair.ui.components.generate.ModeMenuButton

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImageToVideoScreen(
    generationViewModel: GenerationViewModel,
    imageToVideoViewModel: ImageToVideoViewModel,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit,
    presetViewModel: PromptPresetViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // State and effects
    // Collect state
    val generationState by generationViewModel.generationState.collectAsState()
    val uiState by imageToVideoViewModel.uiState.collectAsState()
    val presetUiState by presetViewModel.uiState.collectAsState()
    val queueState by JobRegistry.queueState.collectAsState()
    val isConnecting by ConnectionManager.isConnecting.collectAsState()

    // Check if THIS screen owns the currently executing job (for progress bar)
    val isThisScreenExecuting = queueState.executingOwnerId == ImageToVideoViewModel.OWNER_ID

    // Check offline mode
    val isOfflineMode = remember { AppSettings.isOfflineMode(context) }
    var spellCheckEnabled by remember { mutableStateOf(AppSettings.isPromptSpellCheckEnabled(context)) }
    var promptExpandEnabled by remember { mutableStateOf(AppSettings.isPromptExpandEnabled(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                spellCheckEnabled = AppSettings.isPromptSpellCheckEnabled(context)
                promptExpandEnabled = AppSettings.isPromptExpandEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val positivePromptTransformation = rememberSpellCheckVisualTransformation(
        text = uiState.positivePrompt,
        enabled = spellCheckEnabled
    )
    val imeHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    var prevImeHeight by remember { mutableStateOf(imeHeight) }
    SideEffect { prevImeHeight = imeHeight }
    var promptFocused by remember { mutableStateOf(false) }
    val expandPrompt = promptFocused && imeHeight > 0 && imeHeight >= prevImeHeight

    var showOptionsSheet by remember { mutableStateOf(false) }

    // Track screen visibility for video playback control
    var isScreenVisible by remember { mutableStateOf(true) }

    // Video URI from ViewModel state
    val videoUri = uiState.currentVideoUri

    val configSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            imageToVideoViewModel.onSourceImageChange(context, it)
            imageToVideoViewModel.onViewModeChange(ImageToVideoViewMode.SOURCE)
        }
    }

    // Observe lifecycle to control video playback during navigation transitions
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            isScreenVisible = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Initialize ViewModel
    LaunchedEffect(Unit) {
        generationViewModel.getClient()?.let { client ->
            imageToVideoViewModel.initialize(context, client)
        }
        presetViewModel.initialize(context, ScreenType.IMAGE_TO_VIDEO)

        // Check if there's a pending generation that may have completed while we were away
        if (generationViewModel.generationState.value.isGenerating &&
            generationViewModel.generationState.value.ownerId == ImageToVideoViewModel.OWNER_ID) {
            generationViewModel.checkServerForCompletion { _, _ -> }
        }
    }

    // Refresh presets when screen resumes (catches external changes from Media Viewer)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                presetViewModel.refreshPresets()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Event handling
    LaunchedEffect(Unit) {
        imageToVideoViewModel.events.collect { event ->
            when (event) {
                is ImageToVideoEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is ImageToVideoEvent.ShowToastMessage -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Preset event handling
    LaunchedEffect(Unit) {
        presetViewModel.events.collect { event ->
            when (event) {
                is PromptPresetEvent.PresetApplied -> {
                    imageToVideoViewModel.onPositivePromptChange(event.prompt)
                }
                is PromptPresetEvent.ShowToast -> {
                    Toast.makeText(context, context.getString(event.messageResId), Toast.LENGTH_SHORT).show()
                }
                is PromptPresetEvent.MaxFavoritesReached -> {
                    Toast.makeText(context, context.getString(R.string.prompt_preset_max_favorites), Toast.LENGTH_SHORT).show()
                }
                is PromptPresetEvent.ResetPrompt -> {
                    imageToVideoViewModel.resetPromptToDefault()
                }
            }
        }
    }

    // Register event handler when screen is active
    DisposableEffect(Unit) {
        imageToVideoViewModel.startListening(generationViewModel)
        onDispose {
            imageToVideoViewModel.stopListening(generationViewModel)
        }
    }

    // Handle when a NEW job starts executing for this screen
    // Using both executingPromptId and executingOwnerId as keys handles the race condition
    // where execution_start arrives before job registration (owner becomes known later)
    LaunchedEffect(queueState.executingPromptId, queueState.executingOwnerId) {
        val promptId = queueState.executingPromptId
        if (queueState.executingOwnerId == ImageToVideoViewModel.OWNER_ID && promptId != null) {
            imageToVideoViewModel.clearPreviewForExecution(promptId)
            imageToVideoViewModel.onViewModeChange(ImageToVideoViewMode.PREVIEW)
            imageToVideoViewModel.startListening(generationViewModel)
        }
    }

    // ---------- Pieces ----------

    // Album new videos go into (shared with the other modes and the gallery)
    LaunchedEffect(Unit) { AlbumRepository.ensureLoaded(context) }
    val albums by AlbumRepository.albums.collectAsState()
    val currentAlbumId by AlbumRepository.targetAlbumId.collectAsState()
    val currentAlbum = albums.firstOrNull { it.id == currentAlbumId }

    val showingSource = uiState.viewMode == ImageToVideoViewMode.SOURCE
    val progressVisible = isThisScreenExecuting && generationState.maxProgress > 0 && generationState.progress > 0
    val batchLabel = if (queueState.batchTotal > 0) "${queueState.completedInBatch}/${queueState.batchTotal}" else null

    fun generate(front: Boolean) {
        scope.launch {
            val workflowJson = imageToVideoViewModel.prepareWorkflow()
            if (workflowJson == null) {
                Toast.makeText(context, context.getString(R.string.error_failed_load_workflow), Toast.LENGTH_SHORT).show()
                return@launch
            }
            generationViewModel.startGeneration(
                workflowJson,
                ImageToVideoViewModel.OWNER_ID,
                ContentType.VIDEO,
                front = front
            ) { success, promptId, errorMessage ->
                if (success && promptId != null) AlbumRepository.addPromptToCurrent(promptId)
                if (!success) {
                    Toast.makeText(
                        context,
                        errorMessage ?: context.getString(R.string.error_generation_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // Preview card follows the source image, else the video size
    val previewRatio = (if (showingSource) uiState.sourceImage?.let { it.width.toFloat() / it.height } else null)
        ?: ratioOf(uiState.width, uiState.height)
        ?: (16f / 9f)
    val videoInfo = "${uiState.width}×${uiState.height} · ${uiState.length}f · ${uiState.fps}fps"

    // ---------- Layout ----------

    GenerationScreenLayout(
        expandPrompt = expandPrompt,
        previewRatio = previewRatio,
        onPreviewClick = when {
            showingSource && uiState.sourceImage != null -> {
                // Source image is user-provided, no ComfyUI metadata
                { context.startActivity(MediaViewerActivity.createSingleImageIntent(context, uiState.sourceImage!!)) }
            }
            !showingSource && videoUri != null -> {
                {
                    context.startActivity(
                        MediaViewerActivity.createSingleVideoIntent(
                            context = context,
                            videoUri = videoUri,
                            hostname = generationViewModel.getHostname(),
                            port = generationViewModel.getPort()
                        )
                    )
                }
            }
            else -> null
        },
        progress = { m, translucent ->
            ProgressPill(
                generationState.progress, generationState.maxProgress, m,
                translucent = translucent, active = progressVisible, label = batchLabel
            )
        },
        workflowDropdown = { m ->
            WorkflowSplitDropdown(
                workflows = uiState.availableWorkflows.map { it.name },
                selected = uiState.selectedWorkflow,
                onSelect = imageToVideoViewModel::onWorkflowChange,
                onSettings = { showOptionsSheet = true },
                settingsDescription = stringResource(R.string.button_options),
                modifier = m
            )
        },
        albumDropdown = {
            AlbumDropdown(albums = albums, selectedId = currentAlbumId, onSelect = { AlbumRepository.selectTarget(it) })
        },
        serverMenu = {
            AppMenuDropdown(onSettings = onNavigateToSettings, onLogout = onLogout)
        },
        headerActions = {
            // Pick source image
            IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = stringResource(R.string.button_upload_source_image))
            }
        },
        previewContent = {
            if (showingSource) {
                val source = uiState.sourceImage
                if (source != null) {
                    Image(
                        bitmap = source.asImageBitmap(),
                        contentDescription = stringResource(R.string.tab_source_image),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    PreviewPlaceholder(stringResource(R.string.msg_no_source_image)) {
                        imagePickerLauncher.launch("image/*")
                    }
                }
            } else {
                when {
                    // Live preview during generation
                    uiState.previewBitmap != null -> Image(
                        bitmap = uiState.previewBitmap!!.asImageBitmap(),
                        contentDescription = stringResource(R.string.content_description_preview),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    videoUri != null -> VideoPlayer(
                        videoUri = videoUri,
                        modifier = Modifier.fillMaxSize(),
                        isActive = isScreenVisible
                    )
                }
            }
        },
        belowPreview = {
            SourceResultToggle(
                showingSource = showingSource,
                onShowSource = { imageToVideoViewModel.onViewModeChange(ImageToVideoViewMode.SOURCE) },
                onShowResult = { imageToVideoViewModel.onViewModeChange(ImageToVideoViewMode.PREVIEW) }
            )
            MetaActionsRow(info = videoInfo) {
                SmallActionIcon(Icons.Default.Save, stringResource(R.string.button_save_to_gallery), videoUri != null) {
                    videoUri?.let { uri ->
                        scope.launch { VideoUtils.saveVideoToGallery(context, uri, VideoUtils.GalleryPrefix.IMAGE_TO_VIDEO) }
                    }
                }
                SmallActionIcon(Icons.Default.Share, stringResource(R.string.button_share), videoUri != null) {
                    videoUri?.let { VideoUtils.shareVideo(context, it) }
                }
            }
            RecentResultsStrip(
                selectedKey = null,
                onSelect = { item ->
                    context.startActivity(
                        MediaViewerActivity.createPreviewIntent(
                            context = context,
                            hostname = generationViewModel.getHostname(),
                            port = generationViewModel.getPort(),
                            bitmap = null,
                            filename = item.filename,
                            subfolder = item.subfolder,
                            type = item.type
                        )
                    )
                },
                showGalleryButton = false,
                album = currentAlbum
            )
        },
        prompt = { fill ->
            PromptCardContent(
                value = uiState.positivePrompt,
                onValueChange = {
                    imageToVideoViewModel.onPositivePromptChange(it)
                    presetViewModel.clearActivePreset()
                },
                fill = fill,
                onFocusChanged = { promptFocused = it },
                autoCorrect = spellCheckEnabled,
                visualTransformation = positivePromptTransformation,
                presetButton = {
                    PromptPresetDropdown(
                        favorites = presetUiState.favorites,
                        activePresetId = presetUiState.activePresetId,
                        currentPromptIsEmpty = uiState.positivePrompt.isEmpty(),
                        onPresetSelected = { presetViewModel.onPresetSelected(it) },
                        onOpenLibrary = { presetViewModel.showLibrary() },
                        onSaveCurrentPrompt = { presetViewModel.showSaveDialog(uiState.positivePrompt) },
                        onResetPrompt = { presetViewModel.resetPrompt() }
                    )
                }
            )
        },
        generateRow = {
            GenerateWithGalleryRow {
                GenerationButton(
                    queueSize = queueState.totalQueueSize,
                    isExecuting = queueState.isExecuting,
                    isEnabled = imageToVideoViewModel.hasValidConfiguration() && uiState.positivePrompt.isNotBlank(),
                    isOfflineMode = isOfflineMode,
                    isUploading = uiState.isUploading,
                    isFetching = uiState.isFetching,
                    isConnecting = isConnecting,
                    onGenerate = { generate(front = false) },
                    onCancelCurrent = { generationViewModel.cancelGeneration { } },
                    onAddToFrontOfQueue = { generate(front = true) },
                    onClearQueue = {
                        generationViewModel.getClient()?.clearQueue { success ->
                            val messageRes = if (success) R.string.msg_queue_cleared_success else R.string.error_queue_clear
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    )

    // Options bottom sheet
    if (showOptionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOptionsSheet = false },
            sheetState = configSheetState,
            contentWindowInsets = { WindowInsets.safeDrawing }
        ) {
            val callbacks = remember(imageToVideoViewModel) {
                UnifiedCallbacks(
                    onWorkflowChange = imageToVideoViewModel::onWorkflowChange,
                    onViewWorkflow = {
                        val workflowId = uiState.availableWorkflows
                            .find { it.name == uiState.selectedWorkflow }?.id
                        if (workflowId != null) {
                            context.startActivity(
                                WorkflowEditorActivity.createIntent(context, workflowId)
                            )
                        }
                    },
                    onNegativePromptChange = imageToVideoViewModel::onNegativePromptChange,
                    // Single-model patterns
                    onCheckpointChange = imageToVideoViewModel::onCheckpointChange,
                    onUnetChange = imageToVideoViewModel::onUnetChange,
                    onMandatoryLoraChange = imageToVideoViewModel::onMandatoryLoraChange,
                    // Dual-model patterns
                    onHighnoiseUnetChange = imageToVideoViewModel::onHighnoiseUnetChange,
                    onLownoiseUnetChange = imageToVideoViewModel::onLownoiseUnetChange,
                    onHighnoiseLoraChange = imageToVideoViewModel::onHighnoiseLoraChange,
                    onLownoiseLoraChange = imageToVideoViewModel::onLownoiseLoraChange,
                    onVaeChange = imageToVideoViewModel::onVaeChange,
                    onClipChange = imageToVideoViewModel::onClipChange,
                    onClip1Change = imageToVideoViewModel::onClip1Change,
                    onClip2Change = imageToVideoViewModel::onClip2Change,
                    onClip3Change = imageToVideoViewModel::onClip3Change,
                    onClip4Change = imageToVideoViewModel::onClip4Change,
                    onTextEncoderChange = imageToVideoViewModel::onTextEncoderChange,
                    onLatentUpscaleModelChange = imageToVideoViewModel::onLatentUpscaleModelChange,
                    onWidthChange = imageToVideoViewModel::onWidthChange,
                    onHeightChange = imageToVideoViewModel::onHeightChange,
                    onMegapixelsChange = imageToVideoViewModel::onMegapixelsChange,
                    onLengthChange = imageToVideoViewModel::onLengthChange,
                    onFpsChange = imageToVideoViewModel::onFpsChange,
                    onStepsChange = imageToVideoViewModel::onStepsChange,
                    onCfgChange = imageToVideoViewModel::onCfgChange,
                    onSamplerChange = imageToVideoViewModel::onSamplerChange,
                    onSchedulerChange = imageToVideoViewModel::onSchedulerChange,
                    onRandomSeedToggle = imageToVideoViewModel::onRandomSeedToggle,
                    onSeedChange = imageToVideoViewModel::onSeedChange,
                    onRandomizeSeed = imageToVideoViewModel::onRandomizeSeed,
                    onDenoiseChange = imageToVideoViewModel::onDenoiseChange,
                    onBatchSizeChange = imageToVideoViewModel::onBatchSizeChange,
                    onUpscaleMethodChange = imageToVideoViewModel::onUpscaleMethodChange,
                    onScaleByChange = imageToVideoViewModel::onScaleByChange,
                    onStopAtClipLayerChange = imageToVideoViewModel::onStopAtClipLayerChange,
                    // Primary LoRA chain (for single-model workflows like LTX 2.0)
                    onAddLora = imageToVideoViewModel::onAddLora,
                    onRemoveLora = imageToVideoViewModel::onRemoveLora,
                    onLoraNameChange = imageToVideoViewModel::onLoraNameChange,
                    onLoraStrengthChange = imageToVideoViewModel::onLoraStrengthChange,
                    // Dual-model LoRA chains
                    onAddHighnoiseLora = imageToVideoViewModel::onAddHighnoiseLora,
                    onRemoveHighnoiseLora = imageToVideoViewModel::onRemoveHighnoiseLora,
                    onHighnoiseLoraNameChange = imageToVideoViewModel::onHighnoiseLoraChainNameChange,
                    onHighnoiseLoraStrengthChange = imageToVideoViewModel::onHighnoiseLoraChainStrengthChange,
                    onAddLownoiseLora = imageToVideoViewModel::onAddLownoiseLora,
                    onRemoveLownoiseLora = imageToVideoViewModel::onRemoveLownoiseLora,
                    onLownoiseLoraNameChange = imageToVideoViewModel::onLownoiseLoraChainNameChange,
                    onLownoiseLoraStrengthChange = imageToVideoViewModel::onLownoiseLoraChainStrengthChange
                )
            }
            val bottomSheetConfig = remember(uiState, callbacks) {
                uiState.toBottomSheetConfig(callbacks)
            }
            ConfigBottomSheetContent(
                config = bottomSheetConfig,
                workflowName = uiState.selectedWorkflow,
                spellCheckEnabled = spellCheckEnabled
            )
        }
    }

    // Prompt Library Dialog
    if (presetUiState.showLibrarySideSheet) {
        PromptLibraryDialog(
            presets = presetViewModel.getFilteredPresets(),
            availableTags = presetUiState.availableTags,
            searchQuery = presetUiState.searchQuery,
            selectedTags = presetUiState.selectedTags,
            filterFavoritesOnly = presetUiState.filterFavoritesOnly,
            activePresetId = presetUiState.activePresetId,
            onSearchQueryChange = { presetViewModel.onSearchQueryChange(it) },
            onTagToggle = { presetViewModel.onTagToggle(it) },
            onToggleFavoritesFilter = { presetViewModel.onToggleFavoritesFilter() },
            onPresetSelected = { presetViewModel.onPresetSelected(it) },
            onToggleFavorite = { presetViewModel.onToggleFavorite(it) },
            onEditPreset = { presetViewModel.showEditDialog(it) },
            onDuplicatePreset = { presetViewModel.onDuplicatePreset(it) },
            onDeletePreset = { presetViewModel.onDeletePreset(it) },
            onDismiss = { presetViewModel.dismissLibrary() }
        )
    }

    // Prompt Preset Dialog
    if (presetUiState.showSaveDialog) {
        PromptPresetDialog(
            editingPreset = presetUiState.editingPreset,
            currentPrompt = presetUiState.currentPromptForSave,
            existingTags = presetUiState.availableTags,
            isNameTaken = { name, excludeId -> presetViewModel.isNameTaken(name, excludeId) },
            onDismiss = { presetViewModel.dismissSaveDialog() },
            onSave = { name, prompt, tags -> presetViewModel.onSavePreset(name, prompt, tags) }
        )
    }
}
