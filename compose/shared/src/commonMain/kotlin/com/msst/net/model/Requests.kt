package com.msst.net.model

// Builders for `msst_ipc_protocol::IpcRequest` (serde: tag "type", content
// "params", snake_case).  Field names must match the protocol crate
// (msst-net-core-31x/crates/msst-ipc-protocol/src/message.rs).

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** An `IpcRequest` as JSON: `{"type": …, "params": {…}}`. */
typealias IpcRequest = JsonObject

enum class AuthDecision(val wire: String) {
    AllowOnce("allow_once"),
    AlwaysAllow("always_allow"),
    DenyOnce("deny_once"),
    AlwaysDeny("always_deny"),
}

/** JSON value for a Kotlin value (null, String, Number, Boolean, list, map or JsonElement). */
fun jsonOf(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Iterable<*> -> JsonArray(v.map(::jsonOf))
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to jsonOf(x) })
    else -> error("cannot encode ${v::class.simpleName} as JSON")
}

/** A JSON object; `null` values are kept as explicit nulls. */
fun obj(vararg kv: Pair<String, Any?>): JsonObject = JsonObject(kv.associate { (k, v) -> k to jsonOf(v) })

object Req {
    private fun q(type: String, vararg params: Pair<String, Any?>): IpcRequest = buildJsonObject {
        put("type", JsonPrimitive(type))
        if (params.isNotEmpty()) put("params", obj(*params))
    }

    // Queries
    fun health() = q("health")
    fun getAccessLog(limit: Int? = null) = q("get_access_log", "limit" to limit)
    fun getNetSettings() = q("get_net_settings")
    fun getEndpoints() = q("get_endpoints")
    fun getTrafficStats() = q("get_traffic_stats")
    fun getPathView() = q("get_path_view")
    fun getLogs(limit: Int? = null) = q("get_logs", "limit" to limit)
    fun getDnsDomains() = q("get_dns_domains")
    fun getDnsRecords(domain: String) = q("get_dns_records", "domain" to domain)
    fun getRelays() = q("get_relays")
    fun getServices() = q("get_services")

    // Commands
    /** [relay]: an official relay's address or a `msst-relay://` link (0.4). */
    fun createRoom(roomName: String, password: String?, relay: String? = null, relayPassword: String? = null) =
        q("create_room", "room_name" to roomName, "password" to password, "relay" to relay, "relay_password" to relayPassword)
    fun joinRoom(roomId: String, password: String?) = q("join_room", "room_id" to roomId, "password" to password)
    fun leaveRoom(roomId: String) = q("leave_room", "room_id" to roomId)
    fun createAclRule(rule: JsonObject) = q("create_acl_rule", "rule" to rule)
    fun updateAclRule(ruleId: String, patch: JsonObject) = q("update_acl_rule", "rule_id" to ruleId, "patch" to patch)
    fun deleteAclRule(ruleId: String) = q("delete_acl_rule", "rule_id" to ruleId)
    fun createAclGroup(name: String, kind: String, memberIds: List<String>) =
        q("create_acl_group", "group" to obj("name" to name, "kind" to kind, "member_ids" to memberIds))
    fun updateAclGroup(groupId: String, name: String, memberIds: List<String>) =
        q("update_acl_group", "group_id" to groupId, "patch" to obj("name" to name, "member_ids" to memberIds))
    fun deleteAclGroup(groupId: String) = q("delete_acl_group", "group_id" to groupId)
    fun authDecision(requestId: String, decision: AuthDecision) =
        q("auth_decision", "request_id" to requestId, "decision" to decision.wire)
    fun updateDisplayName(user: String?, device: String?) = q("update_display_name", "user" to user, "device" to device)
    fun updateHandle(user: String?, device: String?) = q("update_handle", "user" to user, "device" to device)
    fun setNetSettings(settings: JsonObject) = q("set_net_settings", "settings" to settings)
    fun setPingEnabled(enabled: Boolean) = q("set_ping_enabled", "enabled" to enabled)
    fun refreshEndpoint() = q("refresh_endpoint")
    fun reRegister() = q("re_register")
    fun authStart() = q("auth_start")
    fun authPoll(sessionId: String) = q("auth_poll", "session_id" to sessionId)
    fun dnsRegisterDomain(namespace: String, reason: String) = q("dns_register_domain", "namespace" to namespace, "reason" to reason)
    fun dnsCreateRecord(domain: String, record: JsonObject) = q("dns_create_record", "domain" to domain, "record" to record)
    fun dnsUpdateRecord(domain: String, recordId: String, patch: JsonObject) =
        q("dns_update_record", "domain" to domain, "record_id" to recordId, "patch" to patch)
    fun dnsDeleteRecord(domain: String, recordId: String) = q("dns_delete_record", "domain" to domain, "record_id" to recordId)
    fun startDiagPing(deviceId: String) = q("start_diag_ping", "device_id" to deviceId)
    fun getDiagPingResult(jobId: String) = q("get_diag_ping_result", "job_id" to jobId)
    fun clearLogs() = q("clear_logs")
}

/** Same TTL Core uses for "allow once" decisions (inbound.rs ONCE_RULE_TTL_SECS). */
const val ONCE_TTL_SECS = 600L

/** A `create_acl_rule` body for one device and port (access-log quick actions). */
fun quickDeviceRule(deviceId: String, deviceName: String, proto: String, port: Int, action: String, expiresAt: Long?): JsonObject =
    obj(
        "action" to action,
        "direction" to "inbound",
        "enabled" to true,
        "expires_at" to expiresAt,
        "principals" to listOf(obj("kind" to "device", "id" to deviceId)),
        "protocol_ports" to listOf(obj("proto" to proto, "port" to port.toString())),
        "comment" to "访问记录操作：${if (action == "allow") "允许" else "拒绝"} $deviceName $proto/$port",
    )
