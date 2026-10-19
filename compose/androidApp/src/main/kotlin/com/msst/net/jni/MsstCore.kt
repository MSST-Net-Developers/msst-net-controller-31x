package com.msst.net.jni

import android.net.VpnService

/**
 * JNI bridge to the embedded Core's lifecycle (compose/rust, feature
 * `embedded-core`), used only by [com.msst.net.MsstVpnService].  The UI
 * never calls these; it talks to Core over IPC through [MsstNative].
 * Every function returns `{"ok":true,…}` or `{"ok":false,"error":{…}}`.
 */
object MsstCore {
    init {
        System.loadLibrary("msst_jni")
    }

    /**
     * Start Core (no-op if running). `{"ok":true,"ipv4":…,"ipv6":…}`
     *
     * Core calls [VpnService.protect] on [service] (from its own threads) for
     * every socket it opens, so its traffic bypasses the VPN — also with
     * "block connections without VPN".
     */
    @JvmStatic external fun nativeStartCore(dataDir: String, socketPath: String, service: VpnService): String

    /** Hand Core the TUN fd from `ParcelFileDescriptor.detachFd()`. */
    @JvmStatic external fun nativeAttachTun(fd: Int): String

    /** Stop using the TUN (sleep); Core keeps running. */
    @JvmStatic external fun nativeDetachTun(): String

    @JvmStatic external fun nativeNetworkChanged(): String

    @JvmStatic external fun nativeStopCore(): String
}
