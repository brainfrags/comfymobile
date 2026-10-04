package sh.hnet.comfychair.ui.navigation

import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.LocalSettingsEmbedded
import sh.hnet.comfychair.ui.components.SettingsMenuDropdown
import sh.hnet.comfychair.ui.components.generate.Brand
import sh.hnet.comfychair.ui.components.generate.PillChip
import sh.hnet.comfychair.ui.screens.AboutSettingsScreen
import sh.hnet.comfychair.ui.screens.ApplicationSettingsScreen
import sh.hnet.comfychair.ui.screens.ServerSettingsScreen
import sh.hnet.comfychair.ui.screens.WorkflowsSettingsScreen
import sh.hnet.comfychair.viewmodel.SettingsViewModel
import sh.hnet.comfychair.viewmodel.WorkflowManagementViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsNavHost(
    settingsViewModel: SettingsViewModel,
    workflowManagementViewModel: WorkflowManagementViewModel,
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit
) {
    // One page instead of four tabs: every settings screen is shown as a section.
    // Switches and primary buttons use the brand lime.
    val base = MaterialTheme.colorScheme
    MaterialTheme(colorScheme = base.copy(primary = Brand.Lime, onPrimary = Brand.LimeInk)) {
    CompositionLocalProvider(LocalSettingsEmbedded provides true) {
        val scope = rememberCoroutineScope()
        val scrollState = rememberScrollState()
        val offsets = remember { mutableStateMapOf<String, Int>() }

        val server: @Composable () -> Unit = {
            ServerSettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = onNavigateToGeneration,
                onNavigateToGeneration = onNavigateToGeneration,
                onLogout = onLogout
            )
        }
        val workflows: @Composable () -> Unit = {
            WorkflowsSettingsScreen(
                viewModel = workflowManagementViewModel,
                onNavigateToGeneration = onNavigateToGeneration,
                onLogout = onLogout
            )
        }
        val application: @Composable () -> Unit = {
            ApplicationSettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = onNavigateToGeneration,
                onNavigateToGeneration = onNavigateToGeneration,
                onLogout = onLogout
            )
        }
        val about: @Composable () -> Unit = {
            AboutSettingsScreen(onNavigateToGeneration = onNavigateToGeneration, onLogout = onLogout)
        }

        val sectionTitles = listOf(
            "server" to stringResource(R.string.nav_server_settings),
            "workflows" to stringResource(R.string.nav_workflows_settings),
            "app" to stringResource(R.string.nav_application_settings),
            "about" to stringResource(R.string.nav_about_settings)
        )

        fun jumpTo(key: String) {
            offsets[key]?.let { y -> scope.launch { scrollState.animateScrollTo(y) } }
        }

        @Composable
        fun Section(key: String, content: @Composable () -> Unit) {
            Box(Modifier.fillMaxWidth().onGloballyPositioned { offsets[key] = it.positionInParent().y.toInt() }) {
                content()
            }
        }

        // Keep clear of the status bar and navigation bar
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical))) {
            TopAppBar(
                title = { Text(stringResource(R.string.action_settings)) },
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = {
                    IconButton(onClick = onNavigateToGeneration) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.menu_generation))
                    }
                },
                actions = { SettingsMenuDropdown(onGeneration = onNavigateToGeneration, onLogout = onLogout) }
            )

            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth < 600.dp) {
                    // ===== Phone: one scrolling page with jump chips =====
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            sectionTitles.forEach { (key, title) ->
                                PillChip(title, onClick = { jumpTo(key) })
                            }
                        }
                        Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(bottom = 32.dp)) {
                            Section("server", server)
                            Section("workflows", workflows)
                            Section("app", application)
                            Section("about", about)
                        }
                    }
                } else {
                    // ===== Wide: server + index fixed on the left, sections scroll on the right =====
                    Row(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
                        ) {
                            server()
                            Spacer(Modifier.height(16.dp))
                            Column(
                                Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                sectionTitles.drop(1).forEach { (key, title) ->
                                    Text(
                                        title,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { jumpTo(key) }
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }
                        VerticalDivider()
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scrollState).padding(bottom = 32.dp)) {
                            Section("workflows", workflows)
                            Section("app", application)
                            Section("about", about)
                        }
                    }
                }
            }
        }
    }
    }
}
