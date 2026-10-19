package com.msst.net.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.msst.net.MainActivity
import com.msst.net.jni.MsstCore
import com.msst.net.platform.VpnControl.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/**
 * MSST-Net VPN service: runs the embedded Core and gives it the TUN.
 *
 * Core itself is the authority for networking state; this service only
 * reports its own lifecycle ([state]) — whether Core was started and the
 * VPN interface is attached.  The UI reads everything else from Core over
 * IPC.
 */
class MsstVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.msst.net.START_VPN"
        const val ACTION_STOP = "com.msst.net.STOP_VPN"
        const val ACTION_SLEEP = "com.msst.net.SLEEP_VPN"
        const val ACTION_WAKE = "com.msst.net.WAKE_VPN"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "msst_vpn"
        private const val TAG = "MsstVpnService"
        private const val NETWORK_CHANGE_DEBOUNCE_MS = 1_000L

        private val _state = MutableStateFlow<State>(State.Stopped)
        val state: StateFlow<State> = _state.asStateFlow()

        /** Core's IPC socket, in the app's private directory. */
        fun socketPath(context: Context): String = File(context.filesDir, "ipc.sock").absolutePath

        /** Core's data directory (identity, rules, settings) — the 3.0.x location. */
        fun dataDir(context: Context): String = context.filesDir.absolutePath
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private var ipv4 = ""
    private var ipv6 = ""
    private var networkChangeJob: Job? = null

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkChanged()
        override fun onLost(network: Network) = onNetworkChanged()
    }

    private fun onNetworkChanged() {
        networkChangeJob?.cancel()
        networkChangeJob = scope.launch {
            delay(NETWORK_CHANGE_DEBOUNCE_MS)
            Log.i(TAG, "Network changed — notifying Core")
            MsstCore.nativeNetworkChanged()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, null -> {
                // Foreground immediately (required within seconds of startForegroundService).
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, buildNotification("正在启动…"))
                scope.launch { lifecycle.withLock { start() } }
            }
            ACTION_STOP -> scope.launch { lifecycle.withLock { stop() } }
            ACTION_SLEEP -> scope.launch { lifecycle.withLock { sleep() } }
            ACTION_WAKE -> scope.launch { lifecycle.withLock { wake() } }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        scope.launch { lifecycle.withLock { stop() } }
        super.onRevoke()
    }

    override fun onDestroy() {
        // Synchronous: the process may be torn down right after.
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        if (_state.value != State.Stopped) MsstCore.nativeStopCore()
        _state.value = State.Stopped
        scope.cancel()
        super.onDestroy()
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    private fun start() {
        if (_state.value == State.Running || _state.value == State.Sleeping) return
        _state.value = State.Starting

        val res = JSONObject(MsstCore.nativeStartCore(dataDir(this), socketPath(this), this))
        if (!res.optBoolean("ok")) return fail(errorText(res))
        ipv4 = res.getString("ipv4")
        ipv6 = res.getString("ipv6")

        val err = attachNewTun()
        if (err != null) {
            MsstCore.nativeStopCore()
            return fail(err)
        }
        _state.value = State.Running
        notifyStatus("已连接")
        runCatching {
            val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        }
        Log.i(TAG, "VPN started: ipv4=$ipv4 ipv6=$ipv6")
    }

    private fun stop() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        MsstCore.nativeStopCore()
        _state.value = State.Stopped
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    /** Pause the VPN interface; Core (and its coordinator connection) keeps running. */
    private fun sleep() {
        if (_state.value != State.Running) return
        MsstCore.nativeDetachTun()
        _state.value = State.Sleeping
        notifyStatus("已休眠")
    }

    private fun wake() {
        if (_state.value != State.Sleeping) return
        val err = attachNewTun()
        if (err != null) {
            Log.e(TAG, "wake failed: $err")
            notifyStatus("恢复失败：$err")
            return
        }
        _state.value = State.Running
        notifyStatus("已连接")
    }

    /** Establish a VPN interface for the MSST ranges and hand it to Core. */
    private fun attachNewTun(): String? {
        val pfd: ParcelFileDescriptor = Builder()
            .setSession("MSST-Net")
            .addAddress(ipv4, 16)
            .addAddress(ipv6, 64)
            .addRoute("172.22.0.0", 16)
            .addRoute("fd22:2222:2222:2222::", 64)
            // Core answers DNS arriving through the TUN (embedded.rs).
            .addDnsServer("172.22.0.1")
            .addDnsServer("fd22:2222:2222:2222::1")
            .setMtu(1420)
            .setBlocking(false)
            .setConfigureIntent(activityIntent())
            .establish()
            ?: return "无法建立 VPN 接口（VPN 权限可能已被撤销）"
        // Ownership of the fd passes to Core.
        val res = JSONObject(MsstCore.nativeAttachTun(pfd.detachFd()))
        return if (res.optBoolean("ok")) null else errorText(res)
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        _state.value = State.Failed(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun errorText(res: JSONObject): String =
        res.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() } ?: "未知错误"

    // ── Notification ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "MSST-Net VPN", NotificationManager.IMPORTANCE_LOW).apply {
            description = "MSST-Net 内网互联 VPN 服务"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notifyStatus(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(statusText: String): Notification {
        val sleeping = _state.value == State.Sleeping
        val action = Intent(this, MsstVpnService::class.java).apply { action = if (sleeping) ACTION_WAKE else ACTION_SLEEP }
        val actionIntent = PendingIntent.getService(
            this, if (sleeping) 1 else 0, action,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MSST-Net")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .apply {
                if (_state.value == State.Running || sleeping) {
                    addAction(Notification.Action.Builder(null, if (sleeping) "恢复" else "休眠", actionIntent).build())
                }
                setContentIntent(activityIntent())
            }
            .build()
    }

    private fun activityIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
