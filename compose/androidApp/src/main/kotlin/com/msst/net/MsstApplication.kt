package com.msst.net

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.msst.net.state.ControllerStore
import kotlinx.coroutines.MainScope

/**
 * Owns the process-wide Controller: one IPC client and one store, alive as
 * long as the process (so access-request notifications arrive even while no
 * Activity is shown, and navigating never reconnects).
 */
class MsstApplication : Application() {

    companion object {
        const val AUTH_CHANNEL_ID = "msst_auth"
    }

    lateinit var platform: AndroidPlatform
        private set
    lateinit var store: ControllerStore
        private set

    override fun onCreate() {
        super.onCreate()
        createAuthChannel()
        // Same process as MsstVpnService, so the socket is the one Core serves.
        platform = AndroidPlatform(this)
        store = ControllerStore(platform.backend, MainScope(), platform.notifier)
        store.start()
    }

    private fun createAuthChannel() {
        val ch = NotificationChannel(AUTH_CHANNEL_ID, "MSST-Net 访问请求", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "网络访问交互式授权通知" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }
}
