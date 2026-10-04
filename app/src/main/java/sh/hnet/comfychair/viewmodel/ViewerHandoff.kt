package sh.hnet.comfychair.viewmodel

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hands a request from the media viewer (its own activity) to the generation screens.
 * The viewer posts a request and returns to MainContainerActivity, which navigates to
 * the right screen; that screen applies the request and clears it.
 */
object ViewerHandoff {
    sealed class Request {
        /** Put a picture's prompt back into Text to Image */
        data class ReusePrompt(val positive: String, val negative: String?) : Request()
        /** Use a picture as the Image to Image source */
        data class EditImage(val bitmap: Bitmap) : Request()
    }

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    fun post(request: Request) {
        _pending.value = request
    }

    /** Clear [request] once applied (no-op if a newer request replaced it) */
    fun consume(request: Request) {
        _pending.compareAndSet(request, null)
    }
}
