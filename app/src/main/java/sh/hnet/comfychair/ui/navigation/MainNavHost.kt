package sh.hnet.comfychair.ui.navigation

import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.navigation.compose.currentBackStackEntryAsState
import sh.hnet.comfychair.ui.components.generate.LocalMainNav
import sh.hnet.comfychair.ui.components.generate.MainNavActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import sh.hnet.comfychair.viewmodel.ViewerHandoff
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.ui.components.MainNavigationBar
import sh.hnet.comfychair.ui.screens.TextToImageScreen
import sh.hnet.comfychair.ui.screens.ImageToImageScreen
import sh.hnet.comfychair.ui.screens.TextToVideoScreen
import sh.hnet.comfychair.ui.screens.ImageToVideoScreen
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.TextToImageViewModel
import sh.hnet.comfychair.viewmodel.ImageToImageViewModel
import sh.hnet.comfychair.viewmodel.TextToVideoViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoViewModel

/**
 * Main navigation host that contains all the generation screens.
 * Uses a Scaffold with bottom navigation bar.
 */
@Composable
fun MainNavHost(
    generationViewModel: GenerationViewModel,
    imageToImageViewModel: ImageToImageViewModel,
    imageToVideoViewModel: ImageToVideoViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onLogout: () -> Unit,
    startDestination: String = MainRoute.TextToImage.route,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val navActions = MainNavActions(
        currentRoute = backStackEntry?.destination?.route,
        onSelectMode = { route ->
            navController.navigate(route.route) {
                popUpTo(MainRoute.TextToImage.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
        onOpenGallery = onNavigateToGallery
    )
    // Requests from the media viewer: open the matching screen. Edit-image is applied here
    // (activity-scoped ViewModel); reuse-prompt is applied by the Text to Image screen.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ViewerHandoff.pending.collect { request ->
            when (request) {
                is ViewerHandoff.Request.ReusePrompt -> navActions.onSelectMode(MainRoute.TextToImage)
                is ViewerHandoff.Request.EditImage -> {
                    imageToImageViewModel.onSourceBitmapChange(context, request.bitmap)
                    ViewerHandoff.consume(request)
                    navActions.onSelectMode(MainRoute.ImageToImage)
                }
                null -> Unit
            }
        }
    }

    // No bottom bar: each screen shows a mode button and a gallery button itself
    CompositionLocalProvider(LocalMainNav provides navActions) {
    Scaffold(
        modifier = modifier.imePadding()
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(MainRoute.TextToImage.route) {
                val textToImageViewModel: TextToImageViewModel = viewModel()
                TextToImageScreen(
                    generationViewModel = generationViewModel,
                    textToImageViewModel = textToImageViewModel,
                    onNavigateToSettings = onNavigateToSettings,
                    onLogout = onLogout
                )
            }

            composable(MainRoute.ImageToImage.route) {
                ImageToImageScreen(
                    generationViewModel = generationViewModel,
                    imageToImageViewModel = imageToImageViewModel,
                    onNavigateToSettings = onNavigateToSettings,
                    onLogout = onLogout
                )
            }

            composable(MainRoute.TextToVideo.route) {
                val textToVideoViewModel: TextToVideoViewModel = viewModel()
                TextToVideoScreen(
                    generationViewModel = generationViewModel,
                    textToVideoViewModel = textToVideoViewModel,
                    onNavigateToSettings = onNavigateToSettings,
                    onLogout = onLogout
                )
            }

            composable(MainRoute.ImageToVideo.route) {
                ImageToVideoScreen(
                    generationViewModel = generationViewModel,
                    imageToVideoViewModel = imageToVideoViewModel,
                    onNavigateToSettings = onNavigateToSettings,
                    onLogout = onLogout
                )
            }
        }
    }
    }
}
