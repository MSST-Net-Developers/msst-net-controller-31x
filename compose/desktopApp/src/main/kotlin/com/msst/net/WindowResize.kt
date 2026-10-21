package com.msst.net

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import java.awt.Cursor
import java.awt.MouseInfo

// An undecorated window has no system frame to resize it by: thin handles
// along its edges and corners stand in for it.

private val EDGE = 5.dp
private val CORNER = 10.dp

private enum class Edge(
    val left: Boolean = false,
    val top: Boolean = false,
    val right: Boolean = false,
    val bottom: Boolean = false,
    val alignment: Alignment,
    val cursor: Int,
) {
    N(top = true, alignment = Alignment.TopCenter, cursor = Cursor.N_RESIZE_CURSOR),
    S(bottom = true, alignment = Alignment.BottomCenter, cursor = Cursor.S_RESIZE_CURSOR),
    W(left = true, alignment = Alignment.CenterStart, cursor = Cursor.W_RESIZE_CURSOR),
    E(right = true, alignment = Alignment.CenterEnd, cursor = Cursor.E_RESIZE_CURSOR),
    // Corners come last so they sit above the edges they overlap.
    NW(left = true, top = true, alignment = Alignment.TopStart, cursor = Cursor.NW_RESIZE_CURSOR),
    NE(right = true, top = true, alignment = Alignment.TopEnd, cursor = Cursor.NE_RESIZE_CURSOR),
    SW(left = true, bottom = true, alignment = Alignment.BottomStart, cursor = Cursor.SW_RESIZE_CURSOR),
    SE(right = true, bottom = true, alignment = Alignment.BottomEnd, cursor = Cursor.SE_RESIZE_CURSOR),
}

/** Place after the window's content so the handles receive the pointer first. */
@Composable
fun FrameWindowScope.ResizeHandles() {
    Box(Modifier.fillMaxSize()) {
        Edge.entries.forEach { Handle(it, window) }
    }
}

@Composable
private fun BoxScope.Handle(edge: Edge, window: ComposeWindow) {
    val shape = when {
        (edge.left || edge.right) && (edge.top || edge.bottom) -> Modifier.size(CORNER)
        edge.top || edge.bottom -> Modifier.fillMaxWidth().height(EDGE)
        else -> Modifier.fillMaxHeight().width(EDGE)
    }
    Box(
        Modifier.align(edge.alignment).then(shape)
            .pointerHoverIcon(PointerIcon(Cursor(edge.cursor)))
            .pointerInput(edge) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    // Screen coordinates: the handle itself moves while the
                    // left or top edge is dragged.
                    val origin = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
                    val start = window.bounds
                    val min = window.minimumSize
                    do {
                        val event = awaitPointerEvent()
                        val p = MouseInfo.getPointerInfo()?.location ?: break
                        val dx = p.x - origin.x
                        val dy = p.y - origin.y
                        var x = start.x
                        var y = start.y
                        var w = start.width
                        var h = start.height
                        if (edge.left) { w = maxOf(start.width - dx, min.width); x = start.x + start.width - w }
                        if (edge.right) w = maxOf(start.width + dx, min.width)
                        if (edge.top) { h = maxOf(start.height - dy, min.height); y = start.y + start.height - h }
                        if (edge.bottom) h = maxOf(start.height + dy, min.height)
                        window.setBounds(x, y, w, h)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
    )
}
