package com.msst.net

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.msst.net.jni.JniBackend
import com.msst.net.model.UiNotice
import com.msst.net.platform.AccessRequestNotifier
import com.msst.net.platform.ControllerBackend
import com.msst.net.platform.ControllerPlatform
import com.msst.net.platform.ControllerVariant
import com.msst.net.platform.PlatformServices
import com.msst.net.platform.MsstVpnService
import com.msst.net.platform.VpnControl

/**
 * Android Controller: Core runs embedded in [MsstVpnService] and serves IPC
 * on a socket in the app's private directory; the UI reaches it through the
 * Controller library over JNI like every other Controller.
 */
class AndroidPlatform(private val context: Context) : ControllerPlatform {
    override val variant = ControllerVariant.Mobile
    override val backend: ControllerBackend = JniBackend(MsstVpnService.socketPath(context))
    override val vpn = AndroidVpnControl(context)

    override val services = object : PlatformServices {
        override fun openUrl(url: String) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        override suspend fun copyText(text: String): Boolean = runCatching {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("MSST-Net", text))
        }.isSuccess

        /** Mobile: hand the text to the share sheet (save to files, send, …). */
        override suspend fun saveText(suggestedName: String, content: String): Boolean {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, suggestedName)
                .putExtra(Intent.EXTRA_TEXT, content)
            context.startActivity(Intent.createChooser(send, "导出日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
    }

    /** Notification with decision actions (3.0.x behaviour).  Names are as
     *  supplied by Core; Core enforces the decision and the timeout. */
    override val notifier = AccessRequestNotifier { req: UiNotice.AccessRequest ->
        val nm = context.getSystemService(NotificationManager::class.java) ?: return@AccessRequestNotifier
        val who = req.srcDeviceDisplayName ?: req.srcDeviceId
        val what = req.serviceName?.let { "$it（${req.proto.uppercase()}/${req.dstPort}）" } ?: "${req.proto.uppercase()}/${req.dstPort}"
        val text = "设备 $who 想访问您的 $what 服务，请处理"
        val notifId = req.requestId.hashCode()

        fun action(decision: String, slot: Int): PendingIntent {
            val intent = Intent(context, AuthActionReceiver::class.java).apply {
                action = AuthActionReceiver.ACTION_AUTH_DECISION
                putExtra(AuthActionReceiver.EXTRA_REQUEST_ID, req.requestId)
                putExtra(AuthActionReceiver.EXTRA_DECISION, decision)
                putExtra(AuthActionReceiver.EXTRA_NOTIF_ID, notifId)
            }
            return PendingIntent.getBroadcast(context, notifId * 4 + slot, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, notifId, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notif = NotificationCompat.Builder(context, MsstApplication.AUTH_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("访问请求")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setContentIntent(open)
            .addAction(0, "允许", action("allow_once", 0))
            .addAction(0, "始终允许", action("always_allow", 1))
            .addAction(0, "拒绝", action("deny_once", 2))
            .build()
        runCatching { nm.notify(notifId, notif) } // POST_NOTIFICATIONS may be denied
    }
}

/** Starts and controls [MsstVpnService].  Starting needs the VPN permission,
 *  which only an Activity can request: [MainActivity] installs [requestPermission]. */
class AndroidVpnControl(private val context: Context) : VpnControl {
    override val state = MsstVpnService.state

    /** Asks for the VPN permission and starts the service once granted. */
    var requestPermission: (() -> Unit)? = null

    override fun start() {
        val ask = requestPermission
        if (android.net.VpnService.prepare(context) != null && ask != null) ask() else startService()
    }

    /** Start without asking (permission already granted). */
    fun startService() {
        context.startForegroundService(intent(MsstVpnService.ACTION_START))
    }

    override fun stop() {
        context.startService(intent(MsstVpnService.ACTION_STOP))
    }

    override fun sleep() {
        context.startService(intent(MsstVpnService.ACTION_SLEEP))
    }

    override fun wake() {
        context.startService(intent(MsstVpnService.ACTION_WAKE))
    }

    private fun intent(action: String) = Intent(context, MsstVpnService::class.java).setAction(action)
}
