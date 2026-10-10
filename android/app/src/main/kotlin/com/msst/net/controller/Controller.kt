package com.msst.net.controller

import com.msst.net.jni.MsstNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A failed request: [message] is for users, [code] is Core's / the Controller's code. */
class ApiException(message: String, val code: String, val detail: String? = null) : Exception(message)

/** A change notification (Rust `UiNotice`). */
sealed class Notice {
    object Changed : Notice()
    data class AccessRequest(
        val requestId: String,
        val srcDeviceId: String,
        val srcDeviceDisplayName: String?,
        val proto: String,
        val dstPort: Int,
        val serviceName: String?,
    ) : Notice()
}

/**
 * The process-wide Controller (JNI `IpcClient`).  Initialised once by
 * [com.msst.net.MsstApplication]; screens use it through the ViewModel.
 */
object Controller {
    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 64)
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()

    fun init(socketPath: String, onAccessRequest: (Notice.AccessRequest) -> Unit) {
        MsstNative.nativeInitController(socketPath)
        MsstNative.nativeSetListener { json ->
            val n = runCatching { JSONObject(json) }.getOrNull() ?: return@nativeSetListener
            val notice = when (n.optString("type")) {
                "changed" -> Notice.Changed
                "access_request" -> Notice.AccessRequest(
                    requestId = n.getString("request_id"),
                    srcDeviceId = n.getString("src_device_id"),
                    srcDeviceDisplayName = n.str("src_device_display_name"),
                    proto = n.optString("proto"),
                    dstPort = n.optInt("dst_port"),
                    serviceName = n.str("service_name"),
                ).also(onAccessRequest)
                else -> return@nativeSetListener
            }
            _notices.tryEmit(notice)
        }
    }

    private fun unwrap(json: String): JSONObject {
        val o = JSONObject(json)
        if (o.optBoolean("ok")) return o
        val e = o.optJSONObject("error") ?: JSONObject()
        throw ApiException(e.optString("message", "未知错误"), e.optString("code", "unknown"), e.str("detail"))
    }

    suspend fun view(): ControllerView = withContext(Dispatchers.IO) {
        parseView(unwrap(MsstNative.nativeGetView()).getJSONObject("view"))
    }

    /** Send an IpcRequest; returns Core's response data.  For commands,
     *  success means Core accepted the request. */
    suspend fun request(req: JSONObject): Any? = withContext(Dispatchers.IO) {
        unwrap(MsstNative.nativeRequest(req.toString())).opt("data")
    }

    suspend fun requestObject(req: JSONObject): JSONObject = request(req) as? JSONObject ?: JSONObject()

    suspend fun clearRules(): JSONObject = withContext(Dispatchers.IO) {
        unwrap(MsstNative.nativeClearRules()).getJSONObject("data")
    }
}

/** Builders for `msst_ipc_protocol::IpcRequest` (tag "type", content "params"). */
object Req {
    private fun q(type: String, params: JSONObject? = null) =
        JSONObject().put("type", type).apply { if (params != null) put("params", params) }

    private fun p(vararg kv: Pair<String, Any?>) = JSONObject().apply {
        kv.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
    }

    fun health() = q("health")
    fun getAccessLog(limit: Int?) = q("get_access_log", p("limit" to limit))
    fun getNetSettings() = q("get_net_settings")
    fun getEndpoints() = q("get_endpoints")
    fun getTrafficStats() = q("get_traffic_stats")
    fun getPathView() = q("get_path_view")
    fun getLogs(limit: Int?) = q("get_logs", p("limit" to limit))
    fun getRelays() = q("get_relays")
    fun getServices() = q("get_services")

    fun createRoom(name: String, password: String?) = q("create_room", p("room_name" to name, "password" to password))
    fun joinRoom(id: String, password: String?) = q("join_room", p("room_id" to id, "password" to password))
    fun leaveRoom(id: String) = q("leave_room", p("room_id" to id))
    fun createAclRule(rule: JSONObject) = q("create_acl_rule", p("rule" to rule))
    fun updateAclRule(id: String, patch: JSONObject) = q("update_acl_rule", p("rule_id" to id, "patch" to patch))
    fun deleteAclRule(id: String) = q("delete_acl_rule", p("rule_id" to id))
    fun createAclGroup(name: String, kind: String, members: List<String>) =
        q("create_acl_group", p("group" to p("name" to name, "kind" to kind, "member_ids" to JSONArray(members))))
    fun updateAclGroup(id: String, name: String, members: List<String>) =
        q("update_acl_group", p("group_id" to id, "patch" to p("name" to name, "member_ids" to JSONArray(members))))
    fun deleteAclGroup(id: String) = q("delete_acl_group", p("group_id" to id))
    fun authDecision(requestId: String, decision: String) = q("auth_decision", p("request_id" to requestId, "decision" to decision))
    fun updateDisplayName(user: String?, device: String?) = q("update_display_name", p("user" to user, "device" to device))
    fun updateHandle(user: String?, device: String?) = q("update_handle", p("user" to user, "device" to device))
    fun setNetSettings(patch: JSONObject) = q("set_net_settings", p("settings" to patch))
    fun setPingEnabled(enabled: Boolean) = q("set_ping_enabled", p("enabled" to enabled))
    fun refreshEndpoint() = q("refresh_endpoint")
    fun reRegister() = q("re_register")
    fun authStart() = q("auth_start")
    fun authPoll(sessionId: String) = q("auth_poll", p("session_id" to sessionId))
    fun startDiagPing(deviceId: String) = q("start_diag_ping", p("device_id" to deviceId))
    fun getDiagPingResult(jobId: String) = q("get_diag_ping_result", p("job_id" to jobId))
    fun clearLogs() = q("clear_logs")
}
