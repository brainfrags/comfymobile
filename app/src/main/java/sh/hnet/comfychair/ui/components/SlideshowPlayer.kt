package sh.hnet.comfychair.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.viewmodel.MediaViewerItem

/** Cross-fade between slides */
private const val FADE_DURATION_MS = 900

/**
 * Ken Burns style motions, cycled per slide.
 * Scale starts/ends at 1.0 or a little above so the whole picture is shown at one end of the
 * motion (ContentScale.Fit, nothing is cut off at rest). Pan values are fractions of the size.
 */
private enum class SlideMotion(
    val fromScale: Float, val toScale: Float,
    val fromX: Float, val toX: Float,
    val fromY: Float, val toY: Float
) {
    ZOOM_IN(1.0f, 1.12f, 0f, 0f, 0f, 0f),
    PAN_LEFT(1.1f, 1.1f, 0.035f, -0.035f, 0f, 0f),
    ZOOM_OUT(1.12f, 1.0f, 0f, 0f, 0f, 0f),
    PAN_RIGHT(1.1f, 1.1f, -0.035f, 0.035f, 0.01f, -0.01f)
}

/**
 * Fullscreen slideshow: cross-fades between items with a slow zoom/pan on each one.
 * Plays from [startIndex] towards the start of the list (newest items in the gallery),
 * wrapping around to the end.
 *
 * @param slideDurationMs How long each slide stays on screen (the zoom/pan runs over this time)
 * @param onStop Called with the index being shown when the user taps to stop
 */
@Composable
fun SlideshowPlayer(
    items: List<MediaViewerItem>,
    startIndex: Int,
    slideDurationMs: Int,
    onStop: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, items.size - 1)) }
    // Number of slides shown so far; picks the motion and keys the cross-fade
    var slideCount by remember { mutableIntStateOf(0) }
    val currentIndex by rememberUpdatedState(index)

    // Previous item, wrapping to the end of the list
    fun nextIndex(i: Int) = if (i - 1 >= 0) i - 1 else items.size - 1

    // Warm the cache for the upcoming slide so it is ready when the fade starts
    val upcoming = items[nextIndex(index)]
    rememberLazyBitmap(
        cacheKey = MediaCacheKey(upcoming.promptId, upcoming.filename),
        isVideo = upcoming.isVideo,
        subfolder = upcoming.subfolder,
        type = upcoming.type
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onStop(currentIndex) })
            }
    ) {
        AnimatedContent(
            targetState = slideCount to index,
            transitionSpec = {
                fadeIn(tween(FADE_DURATION_MS)) togetherWith fadeOut(tween(FADE_DURATION_MS))
            },
            label = "slideshow"
        ) { (count, i) ->
            val item = items[i]
            val (bitmap, isLoading) = rememberLazyBitmap(
                cacheKey = MediaCacheKey(item.promptId, item.filename),
                isVideo = item.isVideo,
                subfolder = item.subfolder,
                type = item.type
            )
            val motion = SlideMotion.entries[count % SlideMotion.entries.size]
            val progress = remember { Animatable(0f) }
            LaunchedEffect(bitmap != null) {
                if (bitmap != null) {
                    progress.animateTo(
                        1f,
                        tween(slideDurationMs + FADE_DURATION_MS, easing = LinearEasing)
                    )
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val p = progress.value
                                val s = motion.fromScale + (motion.toScale - motion.fromScale) * p
                                scaleX = s
                                scaleY = s
                                translationX = size.width * (motion.fromX + (motion.toX - motion.fromX) * p)
                                translationY = size.height * (motion.fromY + (motion.toY - motion.fromY) * p)
                            }
                    )
                } else if (isLoading) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }

    // Advance once the current slide has had its time on screen
    val shown = items[index]
    val (shownBitmap, shownLoading) = rememberLazyBitmap(
        cacheKey = MediaCacheKey(shown.promptId, shown.filename),
        isVideo = shown.isVideo,
        subfolder = shown.subfolder,
        type = shown.type
    )
    LaunchedEffect(slideCount, shownBitmap != null, shownLoading) {
        // Wait for the picture (skip it if it failed to load)
        if (shownBitmap == null && shownLoading) return@LaunchedEffect
        delay(if (shownBitmap != null) slideDurationMs.toLong() else 500L)
        index = nextIndex(index)
        slideCount++
    }
}
