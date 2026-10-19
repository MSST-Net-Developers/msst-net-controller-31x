package com.msst.net

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.msst.net.model.AuthDecision
import com.msst.net.model.Req
import com.msst.net.platform.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Handles the decision buttons of an access-request notification. */
class AuthActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_AUTH_DECISION = "com.msst.net.AUTH_DECISION"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_DECISION = "decision"
        const val EXTRA_NOTIF_ID = "notif_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_AUTH_DECISION) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val decision = AuthDecision.entries.firstOrNull { it.wire == intent.getStringExtra(EXTRA_DECISION) } ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        if (notifId != -1) context.getSystemService(NotificationManager::class.java)?.cancel(notifId)

        val backend = (context.applicationContext as MsstApplication).platform.backend
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                backend.request(Req.authDecision(requestId, decision))
            } catch (e: ApiException) {
                // E.g. already timed out or decided elsewhere: tell the user.
                Log.w("AuthActionReceiver", "auth decision failed: ${e.code} ${e.detail ?: ""}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "处理访问请求失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
