package com.msst.net

import com.msst.net.jni.DesktopNotifications
import com.msst.net.jni.JniBackend
import com.msst.net.model.UiNotice
import com.msst.net.platform.AccessRequestNotifier
import com.msst.net.platform.ControllerBackend
import com.msst.net.platform.ControllerPlatform
import com.msst.net.platform.ControllerVariant
import com.msst.net.platform.LocalPreferences
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
import java.util.Properties
import javax.swing.JFileChooser

/**
 * Desktop Controller: the Controller library over JNI, connected to the
 * system Core service's IPC endpoint (`MSST_IPC_PATH` or the platform
 * default).  Core runs as its own privileged service; this process stays
 * unprivileged.
 */
class DesktopPlatform(private val tray: AppTray) : ControllerPlatform {
    override val variant = ControllerVariant.Desktop
    override val backend: ControllerBackend = JniBackend(socketPath = null)

    override val preferences: LocalPreferences = DesktopPreferences()

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
            val shown = nativeNotifications && DesktopNotifications.show(title, body) { tray.showWindow() }
            if (!shown) tray.notify(title, body)
        }
    }
}

/**
 * UI preferences in the per-user configuration directory
 * (`%APPDATA%\MSST-Net`, `~/Library/Application Support/MSST-Net`, or
 * `$XDG_CONFIG_HOME/msst-net`).  Failures to read or write are ignored: the
 * preference then lasts until restart.
 */
private class DesktopPreferences : LocalPreferences {
    private val file: File = run {
        val os = System.getProperty("os.name").orEmpty()
        val home = System.getProperty("user.home").orEmpty()
        when {
            os.startsWith("Windows") -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "MSST-Net")
            os.startsWith("Mac") -> File(home, "Library/Application Support/MSST-Net")
            else -> File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config", "msst-net")
        }.resolve("controller-ui.properties")
    }
    private val props = Properties().apply { runCatching { file.inputStream().use { load(it) } } }

    @Synchronized
    override fun get(key: String): String? = props.getProperty(key)

    @Synchronized
    override fun set(key: String, value: String) {
        props.setProperty(key, value)
        runCatching {
            file.parentFile.mkdirs()
            file.outputStream().use { props.store(it, "MSST-Net Controller UI preferences") }
        }
    }
}
