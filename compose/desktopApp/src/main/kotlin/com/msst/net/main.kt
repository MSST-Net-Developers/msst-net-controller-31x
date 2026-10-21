package com.msst.net

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.msst.net.state.ControllerStore
import com.msst.net.ui.ControllerApp
import com.msst.net.ui.components.logoPainter
import com.msst.net.ui.theme.MsstTheme
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
        state = rememberWindowState(width = 900.dp, height = 640.dp),
    ) {
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
        platform.fold(
            onSuccess = { p ->
                val store = remember { ControllerStore(p.backend, scope, p.notifier) }
                ControllerApp(p, store)
            },
            onFailure = { e ->
                MsstTheme {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("无法加载控制器组件（msst_jni），请重新安装 MSST-Net 控制器。")
                        Text(e.toString())
                    }
                }
            },
        )
    }
}
