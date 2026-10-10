package com.msst.net.jni

import android.content.Context
import java.io.File

/**
 * JNI bridge to `android/rust` (libmsst_jni.so).
 *
 * Core lifecycle functions are used by [com.msst.net.platform.MsstVpnService];
 * Controller functions by the UI.  Every function returns JSON:
 * `{"ok":true,...}` or `{"ok":false,"error":{"category","code","message","detail"?}}`.
 * All calls block; call them off the main thread.
 */
object MsstNative {
    init {
        System.loadLibrary("msst_jni")
    }

    /** Core's IPC socket, in the app's private directory. */
    fun socketPath(context: Context): String = File(context.filesDir, "ipc.sock").absolutePath

    /** Core's data directory (identity, rules, settings) — the 3.0.x location. */
    fun dataDir(context: Context): String = context.filesDir.absolutePath

    // ── Core lifecycle (VpnService) ─────────────────────────────────────────

    /** Start Core (no-op if running). `{"ok":true,"ipv4":…,"ipv6":…}` */
    @JvmStatic external fun nativeStartCore(dataDir: String, socketPath: String): String

    /** Hand Core the TUN fd from `ParcelFileDescriptor.detachFd()`. */
    @JvmStatic external fun nativeAttachTun(fd: Int): String

    /** Stop using the TUN (sleep); Core keeps running. */
    @JvmStatic external fun nativeDetachTun(): String

    @JvmStatic external fun nativeNetworkChanged(): String

    @JvmStatic external fun nativeStopCore(): String

    // ── Controller (UI) ─────────────────────────────────────────────────────

    /** Create the Controller for Core's socket (once per process). */
    @JvmStatic external fun nativeInitController(socketPath: String): String

    /** `{"ok":true,"view":ControllerView}` */
    @JvmStatic external fun nativeGetView(): String

    /** Send an IpcRequest (`{"type":…,"params":{…}}`): `{"ok":true,"data":…}` */
    @JvmStatic external fun nativeRequest(requestJson: String): String

    /** Delete all firewall rules: `{"ok":true,"data":{"deleted":n,"failed":[…]}}` */
    @JvmStatic external fun nativeClearRules(): String

    /** Receive change notifications (JSON `UiNotice`) on a background thread. */
    @JvmStatic external fun nativeSetListener(listener: NoticeListener): String
}

/** Called from a Rust thread with a `UiNotice` JSON string. */
fun interface NoticeListener {
    fun onNotice(noticeJson: String)
}
