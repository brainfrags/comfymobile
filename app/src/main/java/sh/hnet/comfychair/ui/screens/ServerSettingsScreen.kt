package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.SettingsScreenScaffold
import sh.hnet.comfychair.viewmodel.GpuInfo
import sh.hnet.comfychair.viewmodel.SettingsEvent
import sh.hnet.comfychair.viewmodel.SettingsViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.ServerStorage
import sh.hnet.comfychair.ui.components.generate.Brand

@Composable
fun ServerSettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.serverSettingsState.collectAsState()

    // State and effects
    // Load system stats on first composition and start auto-refresh
    LaunchedEffect(Unit) {
        viewModel.loadSystemStats()
    }

    // Start/stop auto-refresh when screen is shown/hidden
    DisposableEffect(Unit) {
        viewModel.startResourceAutoRefresh()
        onDispose {
            viewModel.stopResourceAutoRefresh()
        }
    }

    // Event handling (on the one-page settings screen, the application section shows toasts)
    val embedded = sh.hnet.comfychair.ui.components.LocalSettingsEmbedded.current
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ShowToast -> {
                    if (!embedded) Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is SettingsEvent.RefreshNeeded -> {
                    // Handled by SettingsContainerActivity
                }
                is SettingsEvent.ShowRestoreDialog -> {
                    // Handled by ApplicationSettingsScreen
                }
                is SettingsEvent.NavigateToLogin -> {
                    // Handled by SettingsContainerActivity
                }
            }
        }
    }

    // UI composition
    SettingsScreenScaffold(
        title = stringResource(R.string.title_server_settings),
        onNavigateToGeneration = onNavigateToGeneration,
        onLogout = onLogout
    ) {
        val serverName = remember(uiState.hostname) {
            ConnectionManager.currentServerId?.let { ServerStorage(context).getServer(it)?.name }
        }
        val stats = uiState.systemStats
        val shape = RoundedCornerShape(18.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    Brush.linearGradient(listOf(Color(0xFF182232), MaterialTheme.colorScheme.surfaceContainer))
                )
                .border(1.dp, Color(0xFF25324A), shape)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Name, address, status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(serverName ?: uiState.hostname, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    Text(
                        "${uiState.hostname}:${uiState.port}",
                        fontFamily = FontFamily.Monospace, fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val ok = stats != null
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background((if (ok) Brand.Ok else MaterialTheme.colorScheme.error).copy(alpha = .14f))
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (ok) Brand.Ok else MaterialTheme.colorScheme.error))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(if (ok) R.string.status_connected else R.string.status_not_connected),
                        fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                        color = if (ok) Brand.Ok else MaterialTheme.colorScheme.error
                    )
                }
            }

            if (uiState.isLoadingStats && stats == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).size(24.dp))
            }

            // GPU / RAM meters side by side
            if (stats != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    stats.gpus.forEachIndexed { i, gpu ->
                        if (gpu.vramTotalGB > 0) {
                            UsageMeter(
                                label = if (stats.gpus.size > 1) "GPU $i" else "GPU",
                                used = gpu.vramTotalGB - gpu.vramFreeGB,
                                total = gpu.vramTotalGB,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (stats.ramTotalGB > 0) {
                        UsageMeter("RAM", stats.ramTotalGB - stats.ramFreeGB, stats.ramTotalGB, Modifier.weight(1f))
                    }
                }
                Text(
                    listOfNotNull(
                        stats.gpus.firstOrNull()?.name,
                        "ComfyUI ${stats.comfyuiVersion}",
                        "PyTorch ${stats.pytorchVersion.substringBefore('+')}",
                        "Python ${stats.pythonVersion.substringBefore(' ')}"
                    ).joinToString("  ·  "),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Actions
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onLogout, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text(stringResource(R.string.button_change_server), fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { viewModel.refreshServerData() },
                    enabled = !uiState.isRefreshingModels,
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    if (uiState.isRefreshingModels) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(stringResource(R.string.button_refresh_models), fontSize = 12.sp)
                }
                TextButton(
                    onClick = { viewModel.clearHistory() },
                    enabled = !uiState.isClearingHistory,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(stringResource(R.string.button_clear_history), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** Label, used/total and a bar; turns orange above 80 %. */
@Composable
private fun UsageMeter(label: String, used: Double, total: Double, modifier: Modifier = Modifier) {
    val ratio = (used / total).toFloat().coerceIn(0f, 1f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("%.1f / %.0f GB".format(used, total), fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
        }
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)),
            color = if (ratio > 0.8f) Color(0xFFFFB547) else Brand.Blue,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            drawStopIndicator = {}
        )
    }
}

@Composable
private fun GpuUsageCard(
    gpu: GpuInfo,
    index: Int,
    showIndex: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            val title = if (showIndex) {
                "${stringResource(R.string.title_gpu_usage)} $index"
            } else {
                stringResource(R.string.title_gpu_usage)
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = gpu.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (gpu.vramTotalGB > 0) {
                Spacer(modifier = Modifier.height(12.dp))

                val vramUsedGB = gpu.vramTotalGB - gpu.vramFreeGB
                val vramProgress = (vramUsedGB / gpu.vramTotalGB).toFloat().coerceIn(0f, 1f)

                LinearProgressIndicator(
                    progress = { vramProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(
                        R.string.resource_usage_format,
                        vramUsedGB,
                        gpu.vramTotalGB
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
