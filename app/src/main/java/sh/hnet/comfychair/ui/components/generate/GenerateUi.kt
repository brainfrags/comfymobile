package sh.hnet.comfychair.ui.components.generate

import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.KeyboardArrowDown
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.ui.components.rememberGalleryThumbnail
import sh.hnet.comfychair.viewmodel.GalleryItem

/** Brand colors (from the CM logo). */
object Brand {
    val Lime = Color(0xFFD7F22A)
    val LimeInk = Color(0xFF1A1F05)
    val Blue = Color(0xFF1A8CFF)
    val BlueSoft = Color(0x291A8CFF)
    val BlueText = Color(0xFFCFE6FF)
    val Ok = Color(0xFF3DDC97)
    val NegRed = Color(0xFFFF8A8A)
}

/**
 * Navigation between the generation modes and to the gallery, provided by the
 * main container so each screen can show its own mode button and gallery button
 * (the bottom navigation bar is gone).
 */
data class MainNavActions(
    val currentRoute: String?,
    val onSelectMode: (MainRoute) -> Unit,
    val onOpenGallery: () -> Unit
)

val LocalMainNav = compositionLocalOf<MainNavActions?> { null }

private data class ModeEntry(val route: MainRoute, val short: String, val iconRes: Int, val labelRes: Int)

private val MODES = listOf(
    ModeEntry(MainRoute.TextToImage, "T2I", R.drawable.text_to_image_24px, R.string.nav_text_to_image),
    ModeEntry(MainRoute.ImageToImage, "I2I", R.drawable.image_to_image_24px, R.string.nav_image_to_image),
    ModeEntry(MainRoute.TextToVideo, "T2V", R.drawable.text_to_video_24px, R.string.nav_text_to_video),
    ModeEntry(MainRoute.ImageToVideo, "I2V", R.drawable.image_to_video_24px, R.string.nav_image_to_video)
)

/** Compact "✦ T2I ▼" button that switches generation mode. */
@Composable
fun ModeMenuButton(modifier: Modifier = Modifier) {
    val nav = LocalMainNav.current ?: return
    val current = MODES.firstOrNull { it.route.route == nav.currentRoute } ?: MODES.first()
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Brand.BlueSoft)
                .border(1.dp, Brand.Blue.copy(alpha = .35f), RoundedCornerShape(10.dp))
                .clickable { open = true }
                .padding(start = 10.dp, end = 4.dp)
        ) {
            Icon(painterResource(current.iconRes), null, tint = Brand.BlueText, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(current.short, color = Brand.BlueText, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            Icon(Icons.Default.ArrowDropDown, null, tint = Brand.BlueText)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MODES.forEach { m ->
                DropdownMenuItem(
                    text = { Text(stringResource(m.labelRes)) },
                    leadingIcon = { Icon(painterResource(m.iconRes), null) },
                    trailingIcon = { if (m == current) Icon(Icons.Default.Check, null) },
                    onClick = {
                        open = false
                        if (m != current) nav.onSelectMode(m.route)
                    }
                )
            }
        }
    }
}

/** Mode tabs for the wide (unfolded) layout. */
@Composable
fun ModeTabs(modifier: Modifier = Modifier) {
    val nav = LocalMainNav.current ?: return
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MODES.forEach { m ->
            val on = m.route.route == nav.currentRoute
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) Brand.BlueSoft else Color.Transparent)
                    .clickable(enabled = !on) { nav.onSelectMode(m.route) }
            ) {
                Icon(
                    painterResource(m.iconRes), null,
                    tint = if (on) Brand.BlueText else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    m.short, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = if (on) Brand.BlueText else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Small colored dot for connection status, meant to overlay the ⋮ menu. */
@Composable
fun StatusDot(connected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (connected) Brand.Ok else MaterialTheme.colorScheme.error)
    )
}

private val PillStart = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
private val PillEnd = RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
private val PillHeight = 40.dp

/**
 * Two-part outlined pill (like the resolution toggle): the workflow name with a chevron opens
 * the workflow list; the icon part on the right opens the workflow settings.
 */
@Composable
fun WorkflowSplitDropdown(
    workflows: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onSettings: () -> Unit,
    settingsDescription: String,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    val outline = MaterialTheme.colorScheme.outline
    Row(modifier.height(PillHeight), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PillHeight)
                    .clip(PillStart)
                    .border(1.dp, outline, PillStart)
                    .clickable(enabled = workflows.isNotEmpty()) { open = true }
                    .padding(start = 14.dp, end = 8.dp)
            ) {
                Text(
                    selected.ifEmpty { "—" },
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // Chevron at the right edge of the name part
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                workflows.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        trailingIcon = { if (name == selected) Icon(Icons.Default.Check, null) },
                        onClick = { open = false; onSelect(name) }
                    )
                }
            }
        }
        // Overlap the borders by 1dp so the divider line is single width
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset(x = (-1).dp)
                .height(PillHeight)
                .width(48.dp)
                .clip(PillEnd)
                .border(1.dp, outline, PillEnd)
                .clickable(onClick = onSettings)
        ) {
            Icon(Icons.Default.Tune, contentDescription = settingsDescription, modifier = Modifier.size(20.dp))
        }
    }
}

/** Outlined pill to pick the album new images go into (null = none). */
@Composable
fun AlbumDropdown(
    albums: List<sh.hnet.comfychair.storage.GalleryAlbum>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    val selected = albums.firstOrNull { it.id == selectedId }
    val shape = RoundedCornerShape(50)
    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(PillHeight)
                .clip(shape)
                .background(if (selected != null) Brand.BlueSoft else Color.Transparent)
                .border(1.dp, if (selected != null) Brand.Blue.copy(alpha = .5f) else MaterialTheme.colorScheme.outline, shape)
                .clickable { open = true }
                .padding(start = 12.dp, end = 8.dp)
        ) {
            Icon(
                Icons.Default.PhotoAlbum, null, modifier = Modifier.size(18.dp),
                tint = if (selected != null) Brand.BlueText else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                selected?.name ?: stringResource(R.string.label_no_album),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (selected != null) Brand.BlueText else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 110.dp)
            )
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Default.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.label_no_album)) },
                trailingIcon = { if (selected == null) Icon(Icons.Default.Check, null) },
                onClick = { open = false; onSelect(null) }
            )
            albums.forEach { album ->
                DropdownMenuItem(
                    text = { Text(album.name) },
                    trailingIcon = { if (album.id == selected?.id) Icon(Icons.Default.Check, null) },
                    onClick = { open = false; onSelect(album.id) }
                )
            }
        }
    }
}

/** Compact workflow selector chip ("name ▼"). */
@Composable
fun WorkflowChip(
    workflows: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .clickable(enabled = workflows.isNotEmpty()) { open = true }
                .height(32.dp)
                .padding(start = 10.dp, end = 2.dp)
        ) {
            Text(
                selected.ifEmpty { "—" },
                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp)
            )
            Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            workflows.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    trailingIcon = { if (name == selected) Icon(Icons.Default.Check, null) },
                    onClick = { open = false; onSelect(name) }
                )
            }
        }
    }
}

/** Small icon button (32dp) for compact action rows; dimmed when disabled. */
@Composable
fun SmallActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
    }
}

/** Small translucent action chip drawn over the preview image. */
@Composable
fun OverlayChip(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = Color.White.copy(alpha = if (enabled) 1f else .35f),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xB8080A0C))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/** "12/20" progress pill with a ring. */
@Composable
fun ProgressPill(
    progress: Int,
    max: Int,
    modifier: Modifier = Modifier,
    translucent: Boolean = true,
    active: Boolean = true,
    /** Text next to the ring instead of "progress/max" (the ring still shows progress/max) */
    label: String? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (translucent) Color(0xB8080A0C) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 5.dp, end = 12.dp, top = 5.dp, bottom = 5.dp)
    ) {
        CircularProgressIndicator(
            progress = { if (active && max > 0) progress.toFloat() / max else 0f },
            modifier = Modifier.size(22.dp),
            color = if (active) Brand.Lime else Color.Gray,
            trackColor = Color.White.copy(alpha = .15f),
            strokeWidth = 3.dp
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label ?: if (active) "$progress/$max" else "–/–",
            color = Color.White.copy(alpha = if (active || label != null) 1f else .35f), fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold, fontSize = 12.sp
        )
    }
}

/** One-line info (resolution · seed · …) drawn over the preview. */
@Composable
fun MetaLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = Color.White.copy(alpha = .85f),
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x8C080A0C))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

/**
 * Recent results (newest first, from the gallery), optionally with a gallery button at the end.
 * Cells are square and share the row width equally.
 */
@Composable
fun RecentResultsStrip(
    selectedKey: String?,
    onSelect: (GalleryItem) -> Unit,
    modifier: Modifier = Modifier,
    count: Int = 6,
    showGalleryButton: Boolean = true,
    /** Only show this album's items (null = all) */
    album: sh.hnet.comfychair.storage.GalleryAlbum? = null
) {
    val context = LocalContext.current
    val nav = LocalMainNav.current
    val items by GalleryRepository.getInstance().galleryItems.collectAsState()
    val recent = (if (album == null) items else items.filter { album.contains(it.promptId, it.toCacheKey().keyString) })
        .take(count)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until count) {
            val item = recent.getOrNull(i)
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                if (item != null) {
                    val key = item.toCacheKey().keyString
                    val (bmp, _) = rememberGalleryThumbnail(item, context)
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(), null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (key == selectedKey) Modifier.border(2.dp, Brand.Lime, RoundedCornerShape(10.dp))
                                else Modifier
                            )
                            .clickable { onSelect(item) }
                    )
                }
            }
        }
        // Gallery button
        if (showGalleryButton) Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .5f), RoundedCornerShape(10.dp))
                .clickable(enabled = nav != null) { nav?.onOpenGallery?.invoke() }
        ) {
            Icon(Icons.Default.GridView, null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.nav_gallery), fontSize = 9.5.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Rounded pill chip. */
@Composable
fun PillChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: String? = null,
    dashed: Boolean = false,
    accent: Boolean = false
) {
    val shape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(shape)
            .background(if (accent) Brand.BlueSoft else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                1.dp,
                if (accent) Brand.Blue.copy(alpha = .35f) else MaterialTheme.colorScheme.outlineVariant,
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        if (leading != null) {
            Text(leading, color = Brand.Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = when {
                accent -> Brand.BlueText
                dashed -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

/** Horizontally scrolling chip row. */
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** Parameter tile: label on top, value below (tap to edit). */
@Composable
fun ParamTile(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(label.uppercase(), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Dialog to edit a single numeric/text value. */
@Composable
fun EditValueDialog(
    title: String,
    initial: String,
    numeric: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, singleLine = true,
                keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }) { Text(stringResource(R.string.button_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } }
    )
}

/** Dialog to pick one option from a list. */
@Composable
fun PickOptionDialog(
    title: String,
    options: List<String>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                options.forEach { opt ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(opt) }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(opt, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        if (opt == selected) Icon(Icons.Default.Check, null, tint = Brand.Lime)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } }
    )
}

/** Dialog for the seed: random on/off, or a fixed number. */
@Composable
fun SeedDialog(
    randomSeed: Boolean,
    seed: String,
    onRandomToggle: () -> Unit,
    onSeedChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(seed) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.label_seed)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.label_random_seed), modifier = Modifier.weight(1f))
                    Switch(checked = randomSeed, onCheckedChange = { onRandomToggle() })
                }
                OutlinedTextField(
                    value = value, onValueChange = { value = it }, singleLine = true,
                    enabled = !randomSeed,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (!randomSeed) onSeedChange(value.trim()); onDismiss() }) {
                Text(stringResource(R.string.button_save))
            }
        }
    )
}

/** Batch-size tile next to the Generate button. Tap cycles 1 → 2 → 3 → 4 → 1. */
@Composable
fun BatchTile(batch: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val n = batch.toIntOrNull() ?: 1
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .clickable { onChange((if (n >= 4) 1 else n + 1).toString()) }
            .padding(horizontal = 12.dp)
    ) {
        Text("×$n", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text("BATCH", fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Card surface used for grouped content (prompt, left column on wide screens). */
@Composable
fun GenCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content
    )
}

/** Human-readable meta line for an image. */
fun metaText(bitmap: Bitmap?, seed: String?, extra: String? = null): String =
    listOfNotNull(
        bitmap?.let { "${it.width}×${it.height}" },
        seed?.takeIf { it.isNotBlank() }?.let { "seed $it" },
        extra
    ).joinToString(" · ")

/**
 * Centers a box with the given width/height ratio, as large as fits in the available space.
 * Used so the preview card follows the selected resolution (2:3, 1:1, 3:2, ...).
 */
@Composable
fun FitAspectBox(
    ratio: Float,
    modifier: Modifier = Modifier,
    boxModifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val r = ratio.takeIf { it.isFinite() && it > 0f } ?: 1f
        val w = maxWidth
        val h = maxHeight
        val (bw, bh) = if (w / h > r) (h * r) to h else w to (w / r)
        Box(boxModifier.size(bw, bh), content = content)
    }
}

/** Width/height ratio from text fields, or null if they aren't numbers. */
fun ratioOf(width: String, height: String): Float? {
    val w = width.toFloatOrNull() ?: return null
    val h = height.toFloatOrNull() ?: return null
    return if (w > 0 && h > 0) w / h else null
}
