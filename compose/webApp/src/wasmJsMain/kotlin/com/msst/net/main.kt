package com.msst.net

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.msst.net.state.ControllerStore
import com.msst.net.ui.ControllerApp

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val platform = WebPlatform()
    ComposeViewport {
        val scope = rememberCoroutineScope()
        // WebUI does not handle interactive authorization: no notifier.
        val store = remember { ControllerStore(platform.backend, scope) }
        ControllerApp(platform, store)
    }
}
