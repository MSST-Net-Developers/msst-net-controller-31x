package com.msst.net.jni

/**
 * JNI bridge to the Controller library (compose/rust, `libmsst_jni`).
 *
 * Every function returns JSON: `{"ok":true,...}` or
 * `{"ok":false,"error":{"category","code","message","detail"?}}`.
 * All calls block; call them off the main thread.
 */
object MsstNative {
    init {
        loadNativeLibrary()
    }

    /** Create the Controller (once per process) for Core's socket, or the
     *  platform's default endpoint when [socketPath] is null. */
    @JvmStatic external fun nativeInitController(socketPath: String?): String

    /** `{"ok":true,"view":ControllerView}` */
    @JvmStatic external fun nativeGetView(): String

    /** Send an IpcRequest (`{"type":…,"params":{…}}`): `{"ok":true,"data":…}` */
    @JvmStatic external fun nativeRequest(requestJson: String): String

    /** Delete all firewall rules: `{"ok":true,"data":{"deleted":n,"failed":[…]}}` */
    @JvmStatic external fun nativeClearRules(): String

    /** Reconnect now, or resync if connected: `{"ok":true,"outcome":…}` */
    @JvmStatic external fun nativeRetry(): String

    /** Receive change notifications (JSON `UiNotice`) on a background thread. */
    @JvmStatic external fun nativeSetListener(listener: NoticeListener): String
}

/** Called from a Rust thread with a `UiNotice` JSON string. */
fun interface NoticeListener {
    fun onNotice(noticeJson: String)
}

/** Load `libmsst_jni` (Android: from the APK; desktop: see the JVM actual). */
internal expect fun loadNativeLibrary()
