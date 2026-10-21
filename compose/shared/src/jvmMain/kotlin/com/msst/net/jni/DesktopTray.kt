package com.msst.net.jni

import com.msst.net.model.ControllerJson
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Native tray icon through `libmsst_jni` (Linux: a StatusNotifierItem,
 * menu included, over D-Bus).  Other platforms report `unsupported`;
 * callers fall back to the JVM tray.
 */
object DesktopTray {
    init {
        loadNativeLibrary()
    }

    /** Called on a native thread. */
    interface Listener {
        /** The icon was clicked. */
        fun onActivate()

        /** Menu entry [index] was chosen. */
        fun onMenuItem(index: Int)
    }

    /** `{"ok":true}` or `{"ok":false,"error":…}`.  [iconArgb]: [iconSize]²
     *  ARGB32 pixels, big endian; an empty string in [menu] is a separator. */
    @JvmStatic external fun nativeStart(title: String, iconSize: Int, iconArgb: ByteArray, menu: Array<String>, listener: Listener): String

    /** Show the icon; false if there is no native tray (the reason is logged). */
    fun start(title: String, iconSize: Int, iconArgb: ByteArray, menu: List<String>, listener: Listener): Boolean {
        val failure = try {
            val r = nativeStart(title, iconSize, iconArgb, menu.toTypedArray(), listener)
            if (ControllerJson.parseToJsonElement(r).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true) return true
            r
        } catch (e: LinkageError) { // library missing, or one built before nativeStart existed
            e.toString()
        }
        System.err.println("MSST-Net: native tray unavailable, using the JVM tray: $failure")
        return false
    }
}
