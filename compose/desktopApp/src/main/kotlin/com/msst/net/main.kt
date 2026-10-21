package com.msst.net

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.msst.net.state.ControllerStore
import com.msst.net.ui.ControllerApp
import com.msst.net.ui.WindowChrome
import com.msst.net.ui.WindowControls
import com.msst.net.ui.components.logoPainter
import com.msst.net.ui.theme.MsstTheme
import java.awt.Dimension
import javax.swing.SwingUtilities

fun main() = application {
    val trayState = rememberTrayState()
    // The library may be missing or built for another platform: say so
    // instead of crashing.
    val platform = remember { runCatching { DesktopPlatform(trayState) } }
    val scope = rememberCoroutineScope()

    Tray(logoPainter(), state = trayState, tooltip = "MSST-Net 控制器") {
        Item("退出", onClick = ::exitApplication)
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "MSST-Net 控制器",
        icon = logoPainter(),
        // The Controller's top bar stands in for the system title bar.
        undecorated = true,
        state = rememberWindowState(width = 900.dp, height = 640.dp),
    ) {
        val chrome = remember(window) { DesktopWindowChrome(this, ::exitApplication) }
        // Narrow enough for the compact layout, which starts below 600 dp.
        LaunchedEffect(window) { window.minimumSize = Dimension(400, 480) }
        // Clicking an access-request notification shows this window.
        DisposableEffect(window) {
            val p = platform.getOrNull()
            p?.activateWindow = {
                SwingUtilities.invokeLater {
                    window.isMinimized = false
                    window.isVisible = true
                    window.toFront()
                    window.requestFocus()
                }
            }
            onDispose { p?.activateWindow = {} }
        }
        Box(Modifier.fillMaxSize()) {
            platform.fold(
                onSuccess = { p ->
                    val store = remember { ControllerStore(p.backend, scope, p.notifier) }
                    ControllerApp(p, store, chrome)
                },
                onFailure = { e ->
                    MsstTheme {
                        Column(Modifier.fillMaxSize()) {
                            chrome.DragArea(Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.End) { WindowControls(chrome) }
                            }
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("无法加载控制器组件（msst_jni），请重新安装 MSST-Net 控制器。")
                                Text(e.toString())
                            }
                        }
                    }
                },
            )
            ResizeHandles()
        }
    }
}

/** Window moving, minimizing and closing for the undecorated window. */
private class DesktopWindowChrome(private val scope: FrameWindowScope, private val onClose: () -> Unit) : WindowChrome {
    @Composable
    override fun DragArea(modifier: Modifier, content: @Composable () -> Unit) {
        scope.WindowDraggableArea(modifier) { content() }
    }

    override fun minimize() {
        scope.window.isMinimized = true
    }

    override fun close() = onClose()
}
