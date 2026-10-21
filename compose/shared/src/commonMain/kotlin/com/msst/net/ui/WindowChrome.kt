package com.msst.net.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.msst.net.ui.components.MIcon
import com.msst.net.ui.theme.Msst

/**
 * A desktop window drawn without the system title bar: the top bar moves
 * the window and ends with [WindowControls].  Absent on Android and the web.
 */
interface WindowChrome {
    /** Lays out [content] so that dragging it moves the window. */
    @Composable
    fun DragArea(modifier: Modifier, content: @Composable () -> Unit)
    fun minimize()
    fun close()
}

val LocalWindowChrome = staticCompositionLocalOf<WindowChrome?> { null }

/** Minimize and close, flush with the top-right corner; fills the bar's height. */
@Composable
fun WindowControls(chrome: WindowChrome) {
    val c = Msst.colors
    Row(Modifier.fillMaxHeight()) {
        WindowButton(MdiIcons.WindowMinimize, "最小化", c.bgHover, c.textPrimary, chrome::minimize)
        WindowButton(MdiIcons.Close, "关闭", c.errorDot, c.textInverse, chrome::close)
    }
}

@Composable
private fun WindowButton(icon: ImageVector, label: String, hoverBackground: Color, hoverTint: Color, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier.fillMaxHeight().width(46.dp)
            .background(if (hovered) hoverBackground else Color.Transparent)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MIcon(icon, 16.dp, if (hovered) hoverTint else Msst.colors.textTertiary, label)
    }
}
