package io.github.christiantwu.longhand.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Where a long list's handle sits and where dragging it goes, apart from Compose so it can be tested. */
object FastScroll {
    /** A list gets a handle once it's at least this many screens long: shorter ones are quicker to flick through. */
    const val SCREENS = 4

    /** The list is long enough for a handle: [total] items, about [visible] of them on screen. */
    fun wanted(visible: Int, total: Int): Boolean = visible > 0 && total >= visible * SCREENS

    /**
     * How far down the list is, 0 at the top to 1 at the end: item [first] is the first on screen, [scrolled] of its
     * height (0 to 1) already above the top. Counted in items, as their heights aren't known until they're shown.
     */
    fun fraction(first: Int, scrolled: Float, visible: Int, total: Int): Float {
        val range = total - visible
        if (range <= 0) return 0f
        return ((first + scrolled.coerceIn(0f, 1f)) / range).coerceIn(0f, 1f)
    }

    /** The item to put first on screen with the handle at [fraction]; at 1, the last item, so the list ends up at its end. */
    fun index(fraction: Float, visible: Int, total: Int): Int {
        if (total <= 0) return 0
        if (fraction >= 1f) return total - 1
        val range = (total - visible).coerceAtLeast(0)
        return (fraction.coerceAtLeast(0f) * range).roundToInt().coerceIn(0, total - 1)
    }
}

/** How long the handle stays after the list stops moving. */
private const val LINGER_MS = 1_500L
private val HandleHeight = 48.dp

/**
 * A handle on the edge of a long list to drag through it: shown while [state]'s list moves and a moment after, only on
 * lists [FastScroll.wanted] takes. While it's dragged, a bubble beside it says where the list is: [label] for the key
 * of the first item on screen (null for no bubble). Place it over the list, at its top end corner.
 *
 * Left out of what TalkBack reads, which has its own gestures to scroll and can't drag.
 */
@Composable
fun FastScroller(state: LazyListState, label: (key: Any?) -> String?, modifier: Modifier = Modifier) {
    val wanted by remember(state) {
        derivedStateOf { FastScroll.wanted(state.layoutInfo.visibleItemsInfo.size, state.layoutInfo.totalItemsCount) }
    }
    var dragging by remember { mutableStateOf(false) }
    // Where the finger has the handle. The list follows it, in whole items, so the handle doesn't follow the list back.
    var dragged by remember { mutableFloatStateOf(0f) }
    var awake by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.isScrollInProgress, dragging) {
        if (state.isScrollInProgress || dragging) awake = true else {
            delay(LINGER_MS)
            awake = false
        }
    }
    fun position(): Float {
        val info = state.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull() ?: return 0f
        val scrolled = if (first.size > 0) state.firstVisibleItemScrollOffset.toFloat() / first.size else 0f
        return FastScroll.fraction(first.index, scrolled, info.visibleItemsInfo.size, info.totalItemsCount)
    }
    AnimatedVisibility(wanted && awake, modifier.clearAndSetSemantics {}, enter = fadeIn(), exit = fadeOut()) {
        BoxWithConstraints(Modifier.fillMaxHeight().padding(vertical = 8.dp), contentAlignment = Alignment.TopEnd) {
            val track = (constraints.maxHeight - with(LocalDensity.current) { HandleHeight.toPx() }).coerceAtLeast(1f)
            val c = MaterialTheme.colorScheme
            Row(
                Modifier.offset { IntOffset(0, ((if (dragging) dragged else position()) * track).roundToInt()) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dragging) {
                    val key by remember(state) { derivedStateOf { state.layoutInfo.visibleItemsInfo.firstOrNull()?.key } }
                    label(key)?.let { text ->
                        Box(Modifier.clip(CircleShape).background(c.inverseSurface).padding(horizontal = 14.dp, vertical = 8.dp)) {
                            MonoLabel(text, color = c.inverseOnSurface)
                        }
                    }
                }
                // A 32dp strip to take hold of, wider than the pill drawn in it; a drag along the screen's edge is
                // the handle's, not the system's back gesture.
                // Each step of the drag is handled as it arrives, in order: where it starts is read before the first move.
                // (Modifier.draggable runs its start callback in a coroutine of its own, which a fling still being
                // stopped can hold up past the first moves.)
                val trackNow by rememberUpdatedState(track)
                Box(
                    Modifier.size(32.dp, HandleHeight).systemGestureExclusion().pointerInput(state) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragged = position()
                                dragging = true
                                // A fling still under way would carry on moving the list under the finger.
                                scope.launch { state.stopScroll() }
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, delta ->
                            change.consume()
                            dragged = (dragged + delta / trackNow).coerceIn(0f, 1f)
                            val info = state.layoutInfo
                            state.requestScrollToItem(FastScroll.index(dragged, info.visibleItemsInfo.size, info.totalItemsCount))
                        }
                    },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        Modifier.padding(end = 4.dp).size(6.dp, HandleHeight).clip(CircleShape)
                            .background(if (dragging) c.primary else c.onSurfaceVariant.copy(alpha = 0.7f)),
                    )
                }
            }
        }
    }
}
