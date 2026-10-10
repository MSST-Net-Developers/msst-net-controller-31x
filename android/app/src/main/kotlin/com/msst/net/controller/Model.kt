package com.msst.net.controller

import org.json.JSONArray
import org.json.JSONObject

// Kotlin mirror of the Controller view (Rust `ControllerView`) and of the
// Core payloads the screens use.  Parsed from JSON; never inferred.

enum class CoreStatus { CHECKING, OK, WARNING, ERROR }

data class Health(
    val status: String,
    val deviceId: String?,
    val userId: String?,
    val deviceHandle: String?,
    val userHandle: String?,
    val userDisplayName: String?,
    val deviceDisplayName: String?,
    val deviceHostname: String?,
    val isAuthenticated: Boolean,
    val authExpiresAt: Long?,
    val wsConnected: Boolean?,
    val tunReady: Boolean?,
    val relay: String?,
    val pingEnabled: Boolean?,
)

data class NodeInfo(
    val deviceId: String,
    val ipv4: String?,
    val displayName: String?,
    val hostname: String?,
    val deviceHandle: String?,
    val userId: String?,
    val userHandle: String?,
    val pathMode: String?,
)

data class Peer(
    val deviceId: String,
    val ipv4: String?,
    val ipv6: String?,
    val displayName: String?,
    val hostname: String?,
)

data class RoomState(
    val roomId: String? = null,
    val roomName: String? = null,
    val relay: String? = null,
    val peers: List<Peer> = emptyList(),
) {
    val inRoom: Boolean get() = roomId != null
}

data class FirewallRule(
    val id: String,
    val enabled: Boolean,
    val action: String,
    /** "devices" | "users" | "space" */
    val kind: String,
    val principals: List<String>,
    /** ID → mnemonic the rule was created with (display only). */
    val mnemonics: Map<String, String>,
    /** e.g. "tcp/25565", "tcp/80-443"; a full range is shown as "udp/(asterisk)". */
    val ports: List<String>,
    val comment: String?,
    val expiresAt: Long?,
    val pendingMigration: Boolean,
)

data class PermissionGroup(val id: String, val name: String, val kind: String, val memberIds: List<String>)

data class PendingAuthRequest(
    val requestId: String,
    val srcDeviceId: String,
    val srcUserId: String?,
    val srcUserDisplayName: String?,
    val srcDeviceDisplayName: String?,
    val proto: String,
    val dstPort: Int,
    val serviceName: String?,
    val expiresInSecs: Long?,
    /** Local deadline from Core's remaining time; null until Core reported it. */
    val expiresAtMs: Long?,
)

sealed class Connection {
    object Connected : Connection()
    object Connecting : Connection()
    object Disconnected : Connection()
    object Incompatible : Connection()
    data class Reconnecting(val attempt: Int) : Connection()
}

data class ControllerView(
    val connection: Connection,
    val everConnected: Boolean,
    val lastError: String?,
    val disconnections: Long,
    val coreProtocol: String?,
    val controllerProtocol: String,
    val supportsServices: Boolean,
    /** "not_synced" | "syncing" | "synced" | "failed" */
    val sync: String,
    val syncMessage: String?,
    val health: Health?,
    val nodes: Map<String, NodeInfo>,
    val room: RoomState,
    val rules: List<FirewallRule>,
    val groups: List<PermissionGroup>,
    val pendingAuth: List<PendingAuthRequest>,
    val accessAuthRequired: Boolean,
) {
    val connected: Boolean get() = connection == Connection.Connected

    val status: CoreStatus
        get() = when {
            connection == Connection.Incompatible -> CoreStatus.ERROR
            connection == Connection.Connecting -> CoreStatus.CHECKING
            !connected -> CoreStatus.ERROR
            health == null -> if (sync == "failed") CoreStatus.WARNING else CoreStatus.CHECKING
            health.status == "ok" -> CoreStatus.OK
            else -> CoreStatus.WARNING
        }

    val coreReady: Boolean get() = status == CoreStatus.OK
}

// ── Parsing ──────────────────────────────────────────────────────────────────

/** `optString` that maps JSON null / "" to null. */
internal fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

internal fun JSONObject.longOrNull(key: String): Long? = if (isNull(key) || !has(key)) null else optLong(key)

internal fun JSONObject.boolOrNull(key: String): Boolean? = if (isNull(key) || !has(key)) null else optBoolean(key)

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }

internal inline fun <T> JSONArray?.objects(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).map { f(getJSONObject(it)) }

fun formatPorts(arr: JSONArray?): List<String> = arr.objects { pp ->
    val proto = pp.optString("proto", "tcp")
    val start = pp.optInt("start")
    val end = pp.optInt("end")
    when {
        start == 1 && end == 65535 -> "$proto/*"
        start == end -> "$proto/$start"
        else -> "$proto/$start-$end"
    }
}

fun parseRule(r: JSONObject): FirewallRule {
    val pg = r.optJSONObject("permission_group")
    val mn = r.optJSONObject("principal_mnemonics")
    return FirewallRule(
        id = r.getString("id"),
        enabled = r.optBoolean("enabled", true),
        action = r.optString("action", "allow"),
        kind = pg?.optString("kind") ?: "",
        principals = pg?.optJSONArray("ids").strings(),
        mnemonics = mn?.keys()?.asSequence()?.associateWith { mn.getString(it) } ?: emptyMap(),
        ports = formatPorts(r.optJSONArray("protocol_ports")),
        comment = r.str("comment"),
        expiresAt = r.longOrNull("expires_at"),
        pendingMigration = r.optBoolean("pending_migration", false),
    )
}

fun parseView(v: JSONObject): ControllerView {
    val conn = when (val c = v.opt("connection")) {
        "Connected" -> Connection.Connected
        "Connecting" -> Connection.Connecting
        "Incompatible" -> Connection.Incompatible
        is JSONObject -> Connection.Reconnecting(c.optJSONObject("Reconnecting")?.optInt("attempt") ?: 0)
        else -> Connection.Disconnected
    }
    val diag = v.optJSONObject("diagnostics") ?: JSONObject()
    val st = v.getJSONObject("state")
    val h = st.optJSONObject("health")
    val room = st.optJSONObject("room") ?: JSONObject()
    val nodes = st.optJSONObject("nodes") ?: JSONObject()
    return ControllerView(
        connection = conn,
        everConnected = diag.optLong("successful_connects") > 0,
        lastError = diag.str("last_error"),
        disconnections = diag.optLong("disconnections"),
        coreProtocol = v.str("core_protocol"),
        controllerProtocol = v.optString("controller_protocol"),
        supportsServices = v.optJSONObject("capabilities")?.optBoolean("get_services") ?: false,
        sync = st.optJSONObject("sync")?.optString("state") ?: "not_synced",
        syncMessage = st.optJSONObject("sync")?.str("message"),
        health = h?.let {
            Health(
                status = it.optString("status"),
                deviceId = it.str("device_id"),
                userId = it.str("user_id"),
                deviceHandle = it.str("device_handle"),
                userHandle = it.str("user_handle"),
                userDisplayName = it.str("user_display_name"),
                deviceDisplayName = it.str("device_display_name"),
                deviceHostname = it.str("device_hostname"),
                isAuthenticated = it.optBoolean("is_authenticated"),
                authExpiresAt = it.longOrNull("auth_expires_at"),
                wsConnected = it.boolOrNull("ws_connected"),
                tunReady = it.boolOrNull("tun_ready"),
                relay = it.str("relay"),
                pingEnabled = it.boolOrNull("ping_enabled"),
            )
        },
        nodes = nodes.keys().asSequence().associateWith { id ->
            val n = nodes.getJSONObject(id)
            NodeInfo(
                deviceId = id,
                ipv4 = n.str("ipv4"),
                displayName = n.str("display_name"),
                hostname = n.str("hostname"),
                deviceHandle = n.str("device_handle"),
                userId = n.str("user_id"),
                userHandle = n.str("user_handle"),
                pathMode = n.str("path_mode"),
            )
        },
        room = RoomState(
            roomId = room.str("room_id"),
            roomName = room.str("room_name"),
            relay = room.str("relay"),
            peers = room.optJSONArray("peers").objects {
                Peer(it.getString("device_id"), it.str("ipv4"), it.str("ipv6"), it.str("display_name"), it.str("hostname"))
            },
        ),
        rules = st.optJSONArray("acl_rules").objects(::parseRule),
        groups = st.optJSONArray("acl_groups").objects {
            PermissionGroup(it.getString("id"), it.optString("name"), it.optString("kind"), it.optJSONArray("member_ids").strings())
        },
        pendingAuth = st.optJSONArray("pending_auth").objects {
            PendingAuthRequest(
                requestId = it.getString("request_id"),
                srcDeviceId = it.getString("src_device_id"),
                srcUserId = it.str("src_user_id"),
                srcUserDisplayName = it.str("src_user_display_name"),
                srcDeviceDisplayName = it.str("src_device_display_name"),
                proto = it.optString("proto"),
                dstPort = it.optInt("dst_port"),
                serviceName = it.str("service_name"),
                expiresInSecs = it.longOrNull("expires_in_secs"),
                expiresAtMs = it.longOrNull("expires_at_ms"),
            )
        },
        accessAuthRequired = !st.isNull("access_auth_required"),
    )
}
