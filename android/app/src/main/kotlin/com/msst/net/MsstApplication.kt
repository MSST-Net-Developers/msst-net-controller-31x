package com.msst.net

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.msst.net.controller.Controller
import com.msst.net.controller.Notice
import com.msst.net.jni.MsstNative

/** Creates the process-wide Controller and posts access-request notifications. */
class MsstApplication : Application() {

    companion object {
        const val AUTH_CHANNEL_ID = "msst_auth"
    }

    override fun onCreate() {
        super.onCreate()
        createAuthChannel()
        // Same process as MsstVpnService, so the socket is the one Core serves.
        Controller.init(MsstNative.socketPath(this)) { postAccessRequest(it) }
    }

    private fun createAuthChannel() {
        val ch = NotificationChannel(AUTH_CHANNEL_ID, "MSST-Net 访问请求", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "网络访问交互式授权通知" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }

    /** Notification with decision actions (3.0.x behaviour).  Names are as
     *  supplied by Core; Core enforces the decision and the timeout. */
    private fun postAccessRequest(req: Notice.AccessRequest) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val who = req.srcDeviceDisplayName ?: req.srcDeviceId
        val what = req.serviceName?.let { "${it}（${req.proto.uppercase()}/${req.dstPort}）" } ?: "${req.proto.uppercase()}/${req.dstPort}"
        val text = "设备 $who 想访问您的 $what 服务，请处理"
        val notifId = req.requestId.hashCode()

        fun action(decision: String, slot: Int): PendingIntent {
            val intent = Intent(this, AuthActionReceiver::class.java).apply {
                action = AuthActionReceiver.ACTION_AUTH_DECISION
                putExtra(AuthActionReceiver.EXTRA_REQUEST_ID, req.requestId)
                putExtra(AuthActionReceiver.EXTRA_DECISION, decision)
                putExtra(AuthActionReceiver.EXTRA_NOTIF_ID, notifId)
            }
            return PendingIntent.getBroadcast(
                this, notifId * 4 + slot, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, notifId, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notif = NotificationCompat.Builder(this, AUTH_CHANNEL_ID)
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
