package com.msst.net

import com.msst.net.jni.DesktopTray
import java.awt.AWTException
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * The tray icon: clicking it shows the window; its menu offers the same and
 * 退出.  Linux: a native StatusNotifierItem ([DesktopTray]), since KDE Plasma
 * and GNOME only proxy the JVM's XEmbed tray, without its menu or clicks.
 * Elsewhere, or when that is unavailable: the JVM tray.
 */
class AppTray(private val onQuit: () -> Unit) {
    /** Brings the main window to the front; set by the window. */
    @Volatile var showWindow: () -> Unit = {}

    private val entries = listOf<Pair<String, (() -> Unit)?>>(
        "显示主窗口" to { showWindow() },
        "" to null, // separator
        "退出" to { SwingUtilities.invokeLater(onQuit) },
    )
    private var awtIcon: TrayIcon? = null

    fun start() {
        val image = AppTray::class.java.getResourceAsStream("/tray-icon.png")?.use(ImageIO::read)
        if (image == null) {
            System.err.println("MSST-Net: tray icon resource missing")
            return
        }
        if (isLinux && startNative(image)) return
        awtIcon = startAwt(image)
    }

    /** A notification from the JVM tray; false if it is not the one in use. */
    fun notify(title: String, body: String): Boolean {
        val icon = awtIcon ?: return false
        icon.displayMessage(title, body, TrayIcon.MessageType.INFO)
        return true
    }

    fun dispose() {
        awtIcon?.let { SystemTray.getSystemTray().remove(it) }
        awtIcon = null
    }

    private fun startNative(image: BufferedImage): Boolean {
        val size = image.width
        val argb = ByteArray(size * size * 4)
        for (y in 0 until size) for (x in 0 until size) {
            val p = image.getRGB(x, y)
            val i = (y * size + x) * 4
            argb[i] = (p ushr 24).toByte()
            argb[i + 1] = (p ushr 16).toByte()
            argb[i + 2] = (p ushr 8).toByte()
            argb[i + 3] = p.toByte()
        }
        val listener = object : DesktopTray.Listener {
            override fun onActivate() = showWindow()
            override fun onMenuItem(index: Int) {
                entries.getOrNull(index)?.second?.invoke()
            }
        }
        return runCatching { DesktopTray.start(TITLE, size, argb, entries.map { it.first }, listener) }
            .getOrElse { e -> System.err.println("MSST-Net: native tray unavailable: $e"); false }
    }

    private fun startAwt(image: BufferedImage): TrayIcon? {
        if (!SystemTray.isSupported()) {
            System.err.println("MSST-Net: no system tray on this desktop")
            return null
        }
        val menu = PopupMenu()
        entries.forEach { (label, action) ->
            if (action == null) menu.addSeparator()
            else menu.add(MenuItem(label).apply { addActionListener { action() } })
        }
        val icon = TrayIcon(image, TITLE, menu).apply {
            isImageAutoSize = true
            // Windows: a double click, or a click on a notification.
            addActionListener { showWindow() }
            // A single click as well; macOS opens the menu on click instead.
            if (!isMac) addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) showWindow()
                }
            })
        }
        return try {
            SystemTray.getSystemTray().add(icon)
            icon
        } catch (e: AWTException) {
            System.err.println("MSST-Net: cannot add the tray icon: $e")
            null
        }
    }

    private companion object {
        const val TITLE = "MSST-Net 控制器"
        val os = System.getProperty("os.name").orEmpty()
        val isLinux = os.startsWith("Linux")
        val isMac = os.startsWith("Mac")
    }
}
