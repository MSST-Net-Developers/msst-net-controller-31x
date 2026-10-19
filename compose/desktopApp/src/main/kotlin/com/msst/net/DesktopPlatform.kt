package com.msst.net

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import com.msst.net.jni.JniBackend
import com.msst.net.model.UiNotice
import com.msst.net.platform.AccessRequestNotifier
import com.msst.net.platform.ControllerBackend
import com.msst.net.platform.ControllerPlatform
import com.msst.net.platform.ControllerVariant
import com.msst.net.platform.PlatformServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import javax.swing.JFileChooser

/**
 * Desktop Controller: the Controller library over JNI, connected to the
 * system Core service's IPC endpoint (`MSST_IPC_PATH` or the platform
 * default).  Core runs as its own privileged service; this process stays
 * unprivileged.
 */
class DesktopPlatform(private val tray: TrayState) : ControllerPlatform {
    override val variant = ControllerVariant.Desktop
    override val backend: ControllerBackend = JniBackend(socketPath = null)

    override val services = object : PlatformServices {
        override fun openUrl(url: String) {
            runCatching { Desktop.getDesktop().browse(URI(url)) }
        }

        override suspend fun copyText(text: String): Boolean = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }.isSuccess

        override suspend fun saveText(suggestedName: String, content: String): Boolean {
            val file = withContext(Dispatchers.Main) {
                val chooser = JFileChooser().apply { selectedFile = File(suggestedName) }
                if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
            } ?: return false
            withContext(Dispatchers.IO) { file.writeText(content) }
            return true
        }
    }

    /** A system notification per new access request (as in 3.0.x); the
     *  decision is made in the app's dialog. */
    override val notifier = AccessRequestNotifier { req: UiNotice.AccessRequest ->
        val who = req.srcDeviceDisplayName ?: req.srcDeviceId
        val what = req.serviceName?.let { "$it（${req.proto}:${req.dstPort}）" } ?: "${req.proto}:${req.dstPort}"
        tray.sendNotification(Notification("访问请求", "$who 想访问您的 $what 服务，请在应用中处理", Notification.Type.Info))
    }
}
