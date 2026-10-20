package com.msst.net.jni

import com.msst.net.model.ControllerJson
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Native desktop notifications through `libmsst_jni` (Linux: the
 * freedesktop notification service).  Other platforms report
 * `unsupported`; callers fall back to the tray notification.
 */
object DesktopNotifications {
    init {
        loadNativeLibrary()
    }

    /** `{"ok":true}` or `{"ok":false,"error":…}`.  Blocks briefly (D-Bus);
     *  [onActivate] runs on a native thread when the notification is clicked. */
    @JvmStatic external fun nativeShow(title: String, body: String, onActivate: Runnable?): String

    /** Show a notification; false if it could not be shown natively. */
    fun show(title: String, body: String, onActivate: (() -> Unit)?): Boolean = try {
        val r = nativeShow(title, body, onActivate?.let { f -> Runnable { f() } })
        ControllerJson.parseToJsonElement(r).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true
    } catch (e: LinkageError) { // library or symbol missing
        false
    }
}
