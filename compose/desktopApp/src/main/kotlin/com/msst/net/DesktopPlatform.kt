package com.msst.net

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import com.msst.net.jni.DesktopNotifications
import com.msst.net.jni.JniBackend
import com.msst.net.model.UiNotice
import com.msst.net.platform.AccessRequestNotifier
import com.msst.net.platform.ControllerBackend
import com.msst.net.platform.ControllerPlatform
import com.msst.net.platform.ControllerVariant
import com.msst.net.platform.PlatformServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    /** Brings the main window to the front; set by the window. */
    @Volatile var activateWindow: () -> Unit = {}

    private val notifyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Linux: the desktop's own notification service (the JVM tray draws
     *  its own balloon there).  Windows and macOS: the tray notification,
     *  which those systems already show natively. */
    private val nativeNotifications = System.getProperty("os.name").orEmpty().startsWith("Linux")

    /** A system notification per new access request (as in 3.0.x); the
     *  decision is made in the app's dialog. */
    override val notifier = AccessRequestNotifier { req: UiNotice.AccessRequest ->
        val who = req.srcDeviceDisplayName ?: req.srcDeviceId
        val what = req.serviceName?.let { "$it（${req.proto}:${req.dstPort}）" } ?: "${req.proto}:${req.dstPort}"
        val title = "访问请求"
        val body = "$who 想访问您的 $what 服务，请在应用中处理"
        notifyScope.launch {
            val shown = nativeNotifications && DesktopNotifications.show(title, body) { activateWindow() }
            if (!shown) tray.sendNotification(Notification(title, body, Notification.Type.Info))
        }
    }
}
