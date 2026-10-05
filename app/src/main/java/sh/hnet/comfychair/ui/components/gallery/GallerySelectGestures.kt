package sh.hnet.comfychair.ui.components.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.max
import kotlin.math.min

/**
 * Shared state for drag-to-select in the gallery grid.
 * [autoScrollSpeed] is non-zero while the finger is near the top/bottom edge during a
 * selection drag; the grid scrolls by it and calls [onAutoScrolled] to extend the range.
 */
class DragSelectState {
    var autoScrollSpeed by mutableFloatStateOf(0f)
    internal var anchorIndex = -1
    internal var baseSelection: Set<String> = emptySet()
    internal var lastPosition: Offset? = null
    internal var update: ((Offset) -> Unit)? = null
    /** True while an item is being moved by drag and drop */
    internal var reordering = false

    fun onAutoScrolled() {
        val pos = lastPosition ?: return
        update?.invoke(pos)
    }
}

/**
 * Gallery grid gestures:
 * - Tap an item: [onTap]
 * - Outside selection mode, hold an item and drag it: move it ([onReorderStart],
 *   [onReorderMove], [onReorderEnd]); hold and release without moving: select it
 * - In selection mode (or where items can't be moved), long-press an item to select it
 *   (or unselect it if already selected); long-press and drag selects every item between
 *   the first and the current one
 *
 * @param keyAt Returns the item key under a position in the grid, or null
 * @param keys Current item keys in display order
 * @param selection Current selection
 * @param isSelectionMode Whether selection mode is on
 * @param canReorder Whether items can be moved in the current view
 */
fun Modifier.gallerySelectGestures(
    state: DragSelectState,
    haptics: HapticFeedback,
    edgeThreshold: Float,
    keyAt: (Offset) -> String?,
    keys: () -> List<String>,
    selection: () -> Set<String>,
    isSelectionMode: () -> Boolean,
    canReorder: () -> Boolean,
    onTap: (String) -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    onReorderStart: (key: String, position: Offset) -> Unit,
    onReorderMove: (Offset) -> Unit,
    onReorderEnd: () -> Unit
): Modifier = pointerInput(Unit) {
    fun extendTo(position: Offset) {
        if (state.anchorIndex < 0) return
        state.lastPosition = position
        val key = keyAt(position) ?: return
        val all = keys()
        val index = all.indexOf(key)
        if (index < 0) return
        val range = all.subList(min(state.anchorIndex, index), max(state.anchorIndex, index) + 1)
        onSelectionChange(state.baseSelection + range)
    }
    state.update = { position -> if (state.reordering) onReorderMove(position) else extendTo(position) }

    fun autoScrollSpeedAt(y: Float): Float = when {
        y > size.height - edgeThreshold -> y - (size.height - edgeThreshold)
        y < edgeThreshold -> -(edgeThreshold - y)
        else -> 0f
    }

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)

        // Tap (up before the long-press timeout) or cancel (scrolling consumed the pointer)
        var up: PointerInputChange? = null
        val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            up = waitForUpOrCancellation()
            true
        }
        if (released == true) {
            up?.let { change ->
                keyAt(down.position)?.let { key ->
                    change.consume()
                    onTap(key)
                }
            }
            return@awaitEachGesture
        }

        // Long press
        val key = keyAt(down.position) ?: return@awaitEachGesture
        val index = keys().indexOf(key)
        if (index < 0) return@awaitEachGesture
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val current = selection()

        if (!isSelectionMode() && canReorder()) {
            // Hold: drag to move the item, or release in place to select it
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        if (!state.reordering) onSelectionChange(current + key)
                        break
                    }
                    change.consume()
                    if (!state.reordering &&
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    ) {
                        state.reordering = true
                        onReorderStart(key, down.position)
                    }
                    if (state.reordering) {
                        state.lastPosition = change.position
                        state.autoScrollSpeed = autoScrollSpeedAt(change.position.y)
                        onReorderMove(change.position)
                    }
                }
            } finally {
                if (state.reordering) {
                    state.reordering = false
                    onReorderEnd()
                }
                state.lastPosition = null
                state.autoScrollSpeed = 0f
            }
            return@awaitEachGesture
        }

        if (key in current) {
            // Long-press on a selected item unselects it (no range drag)
            onSelectionChange(current - key)
            state.anchorIndex = -1
        } else {
            state.baseSelection = current
            state.anchorIndex = index
            onSelectionChange(current + key)
        }

        // Drag: consume in the Initial pass so the grid does not scroll by itself
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    change.consume()
                    break
                }
                change.consume()
                if (state.anchorIndex >= 0) {
                    state.autoScrollSpeed = autoScrollSpeedAt(change.position.y)
                    extendTo(change.position)
                }
            }
        } finally {
            state.anchorIndex = -1
            state.lastPosition = null
            state.autoScrollSpeed = 0f
        }
    }
}
