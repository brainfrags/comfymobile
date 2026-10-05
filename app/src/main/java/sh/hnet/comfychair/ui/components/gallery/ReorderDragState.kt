package sh.hnet.comfychair.ui.components.gallery

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Hold-and-drag reordering in a lazy grid: the cell being moved follows the finger.
 * Positions are in grid coordinates.
 */
@Stable
class ReorderDragState {
    // Where each visible cell is drawn. Measured from the cells themselves, so content
    // padding and headers can't shift the hit test.
    private val bounds = HashMap<String, Rect>()

    var draggingKey by mutableStateOf<String?>(null)
        private set
    private var position by mutableStateOf(Offset.Zero)
    private var grabOffset by mutableStateOf(Offset.Zero)

    /** The key of the cell under [pos], among [visibleKeys]. */
    fun keyAt(pos: Offset, visibleKeys: List<Any>): String? =
        visibleKeys.firstOrNull { key -> (key as? String)?.let { bounds[it] }?.contains(pos) == true } as? String

    fun start(key: String, pos: Offset) {
        draggingKey = key
        position = pos
        grabOffset = pos - (bounds[key]?.topLeft ?: pos)
    }

    fun moveTo(pos: Offset) {
        position = pos
    }

    /** Where the finger is now */
    val fingerPosition: Offset
        get() = position

    fun end() {
        draggingKey = null
    }

    /**
     * Modifier for the cell of [key]: records where it is and lifts it while it is dragged.
     * [placement] (the item's animateItem) is used while it is not dragged.
     */
    fun cellModifier(key: String, placement: Modifier, draggedAlpha: Float = 1f): Modifier {
        val isDragging = key == draggingKey
        return Modifier
            .then(if (isDragging) Modifier.zIndex(1f) else placement)
            .onGloballyPositioned { bounds[key] = it.boundsInParent() }
            .graphicsLayer {
                if (isDragging) {
                    bounds[key]?.let { b ->
                        translationX = position.x - grabOffset.x - b.left
                        translationY = position.y - grabOffset.y - b.top
                    }
                    scaleX = 1.05f
                    scaleY = 1.05f
                    alpha = draggedAlpha
                    shadowElevation = 12.dp.toPx()
                }
            }
    }
}
