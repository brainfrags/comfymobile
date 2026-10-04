package sh.hnet.comfychair.ui.components.generate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.R

/**
 * Shared layout of the generation screens (same design as Text to Image).
 *
 * Phone: mode button + extra actions + menu / workflow + album / preview card (progress pill
 * on top) / [belowPreview] / prompt card / [settingsRow] / [generateRow].
 * While the keyboard is open only the prompt (filling the space) and generate stay.
 *
 * Wide: left card with progress pill + mode tabs, preview and [belowPreview]; right column
 * with workflow + album + actions + menu, prompt card, [settingsRow] and [generateRow].
 *
 * @param progress Loading pill; gets a modifier and whether it is drawn over the image
 * @param prompt Prompt card content; gets whether it should fill the card's height
 */
@Composable
fun GenerationScreenLayout(
    expandPrompt: Boolean,
    previewRatio: Float,
    onPreviewClick: (() -> Unit)?,
    progress: @Composable (Modifier, Boolean) -> Unit,
    workflowDropdown: @Composable (Modifier) -> Unit,
    albumDropdown: @Composable () -> Unit,
    serverMenu: @Composable () -> Unit,
    previewContent: @Composable BoxScope.() -> Unit,
    prompt: @Composable ColumnScope.(fill: Boolean) -> Unit,
    generateRow: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    headerActions: @Composable RowScope.() -> Unit = {},
    belowPreview: @Composable ColumnScope.() -> Unit = {},
    settingsRow: @Composable ColumnScope.() -> Unit = {}
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp

        if (!isWide) {
            // ===== Phone (folded) =====
            Column(Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp)
                ) {
                    ModeMenuButton()
                    Spacer(Modifier.weight(1f))
                    headerActions()
                    serverMenu()
                }
                // Workflow (takes all spare width) and album
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)
                ) {
                    workflowDropdown(Modifier.weight(1f))
                    albumDropdown()
                }

                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!expandPrompt) {
                        // Preview card; its shape follows the image / selected resolution
                        FitAspectBox(
                            ratio = previewRatio,
                            modifier = Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .clickable(enabled = onPreviewClick != null) { onPreviewClick?.invoke() },
                                contentAlignment = Alignment.Center
                            ) {
                                previewContent()
                                progress(Modifier.align(Alignment.TopStart).padding(10.dp), true)
                            }
                        }
                        belowPreview()
                    }

                    GenCard(if (expandPrompt) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth()) {
                        Column(
                            (if (expandPrompt) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).padding(4.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            prompt(expandPrompt)
                        }
                    }

                    if (!expandPrompt) settingsRow()
                    generateRow()
                }
            }
        } else {
            // ===== Wide (unfolded) =====
            Row(
                Modifier.fillMaxSize().padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                GenCard(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(14.dp)
                        ) {
                            progress(Modifier, false)
                            Spacer(Modifier.width(12.dp))
                            ModeTabs(Modifier.weight(1f))
                        }
                        HorizontalDivider()
                        FitAspectBox(
                            ratio = previewRatio,
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp).padding(top = 14.dp),
                            boxModifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .clickable(enabled = onPreviewClick != null) { onPreviewClick?.invoke() }
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                previewContent()
                            }
                        }
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            belowPreview()
                        }
                    }
                }

                Column(
                    Modifier.weight(0.85f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        workflowDropdown(Modifier.weight(1f))
                        albumDropdown()
                        headerActions()
                        serverMenu()
                    }
                    GenCard(Modifier.fillMaxWidth().weight(1f)) {
                        Column(Modifier.fillMaxSize().padding(4.dp), verticalArrangement = Arrangement.Center) {
                            prompt(true)
                        }
                    }
                    if (!expandPrompt) settingsRow()
                    generateRow()
                }
            }
        }
    }
}

/**
 * Prompt field without its own outline (the card is the frame), with the preset (bookmark)
 * button and clear button in a row under it. Use inside the prompt card's column.
 */
@Composable
fun ColumnScope.PromptCardContent(
    value: String,
    onValueChange: (String) -> Unit,
    fill: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    autoCorrect: Boolean,
    visualTransformation: VisualTransformation,
    presetButton: @Composable () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(stringResource(R.string.hint_prompt)) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent
        ),
        modifier = (if (fill) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth())
            .onFocusChanged { onFocusChanged(it.isFocused) },
        minLines = 3,
        maxLines = if (fill) Int.MAX_VALUE else 4,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = autoCorrect),
        visualTransformation = visualTransformation
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        presetButton()
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { onValueChange("") }, enabled = value.isNotEmpty()) {
            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.content_description_clear))
        }
    }
}

/** Generate button row with the gallery shortcut on the right. */
@Composable
fun GenerateWithGalleryRow(generateButton: @Composable RowScope.() -> Unit) {
    val nav = LocalMainNav.current
    Row(
        Modifier.fillMaxWidth().height(56.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        generateButton()
        OutlinedIconButton(
            onClick = { nav?.onOpenGallery?.invoke() },
            enabled = nav != null,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(Icons.Default.GridView, contentDescription = stringResource(R.string.nav_gallery))
        }
    }
}

/** Info text on the left, small action icons on the right (under the preview). */
@Composable
fun MetaActionsRow(info: String, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            info,
            fontFamily = FontFamily.Monospace, fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
}

/** "Source / Result" toggle for the image-input modes. */
@Composable
fun SourceResultToggle(showingSource: Boolean, onShowSource: () -> Unit, onShowResult: () -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().height(40.dp)) {
        SegmentedButton(
            selected = showingSource,
            onClick = onShowSource,
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
        ) {
            Text(stringResource(R.string.tab_source_image))
        }
        SegmentedButton(
            selected = !showingSource,
            onClick = onShowResult,
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
        ) {
            Text(stringResource(R.string.tab_preview))
        }
    }
}

/** Placeholder text shown in an empty preview card. */
@Composable
fun PreviewPlaceholder(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(12.dp)
    )
}
