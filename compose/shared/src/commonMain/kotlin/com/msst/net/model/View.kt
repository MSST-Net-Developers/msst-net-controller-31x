package com.msst.net.model

// Types of the Controller view (Rust `ControllerView`, read through
// `ControllerBackend.view()`).  Field names follow the Rust serde output;
// unknown fields are ignored so a newer Controller library or Core does not
// make the view undecodable.

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// ── Connection ───────────────────────────────────────────────────────────────

/** Controller ↔ Core IPC channel status (not the network state). */
@Serializable(with = ConnectionStatusSerializer::class)
sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus
    data object Connecting : ConnectionStatus
    data object Connected : ConnectionStatus
    data class Reconnecting(val attempt: Long, val nextRetryMs: Long) : ConnectionStatus
    /** Core speaks an incompatible protocol version. */
    data object Incompatible : ConnectionStatus
    /** A status this Controller does not know (newer library). */
    data class Unknown(val raw: String) : ConnectionStatus
}

/** serde's externally tagged enum: `"Connected"` or `{"Reconnecting":{…}}`. */
internal object ConnectionStatusSerializer : KSerializer<ConnectionStatus> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ConnectionStatus")

    override fun deserialize(decoder: Decoder): ConnectionStatus {
        val e = (decoder as JsonDecoder).decodeJsonElement()
        if (e is JsonPrimitive) {
            return when (val s = e.content) {
                "Disconnected" -> ConnectionStatus.Disconnected
                "Connecting" -> ConnectionStatus.Connecting
                "Connected" -> ConnectionStatus.Connected
                "Incompatible" -> ConnectionStatus.Incompatible
                else -> ConnectionStatus.Unknown(s)
            }
        }
        val r = (e as? JsonObject)?.get("Reconnecting") as? JsonObject
            ?: return ConnectionStatus.Unknown(e.toString())
        return ConnectionStatus.Reconnecting(
            attempt = r["attempt"]?.jsonPrimitive?.longOrNull ?: 0,
            nextRetryMs = r["next_retry_ms"]?.jsonPrimitive?.longOrNull ?: 0,
        )
    }

    override fun serialize(encoder: Encoder, value: ConnectionStatus) {
        val e: JsonElement = when (value) {
            ConnectionStatus.Disconnected -> JsonPrimitive("Disconnected")
            ConnectionStatus.Connecting -> JsonPrimitive("Connecting")
            ConnectionStatus.Connected -> JsonPrimitive("Connected")
            ConnectionStatus.Incompatible -> JsonPrimitive("Incompatible")
            is ConnectionStatus.Unknown -> JsonPrimitive(value.raw)
            is ConnectionStatus.Reconnecting -> buildJsonObject {
                put("Reconnecting", buildJsonObject {
                    put("attempt", value.attempt)
                    put("next_retry_ms", value.nextRetryMs)
                })
            }
        }
        (encoder as JsonEncoder).encodeJsonElement(e)
    }
}

/** Whether the view was (re)built from Core since the last connect. */
@Serializable
data class SyncState(
    /** `not_synced`, `syncing`, `synced` or `failed`. */
    val state: String = "not_synced",
    val message: String? = null,
) {
    val synced get() = state == "synced"
    val syncing get() = state == "syncing"
    val failed get() = state == "failed"
}

@Serializable
data class ConnectionDiagnostics(
    @SerialName("total_connect_attempts") val totalConnectAttempts: Long = 0,
    @SerialName("successful_connects") val successfulConnects: Long = 0,
    val disconnections: Long = 0,
    @SerialName("version_mismatches") val versionMismatches: Long = 0,
    @SerialName("last_connection_duration_ms") val lastConnectionDurationMs: Long? = null,
    @SerialName("last_error") val lastError: String? = null,
)

/** What the connected Core supports (from the protocol version). */
@Serializable
data class Capabilities(
    @SerialName("get_services") val getServices: Boolean = false,
    @SerialName("clear_acl_rules") val clearAclRules: Boolean = false,
    @SerialName("full_events") val fullEvents: Boolean = false,
    @SerialName("event_seq") val eventSeq: Boolean = false,
    val operations: Boolean = false,
    @SerialName("log_stream") val logStream: Boolean = false,
)

// ── Core state ───────────────────────────────────────────────────────────────

/** Core's `Health` response. */
@Serializable
data class Health(
    val status: String = "",
    val generation: Long? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("device_handle") val deviceHandle: String? = null,
    @SerialName("user_handle") val userHandle: String? = null,
    @SerialName("user_display_name") val userDisplayName: String? = null,
    @SerialName("device_display_name") val deviceDisplayName: String? = null,
    @SerialName("device_hostname") val deviceHostname: String? = null,
    @SerialName("is_authenticated") val isAuthenticated: Boolean = false,
    @SerialName("auth_expires_at") val authExpiresAt: Long? = null,
    @SerialName("ws_connected") val wsConnected: Boolean? = null,
    @SerialName("coordinator_state") val coordinatorState: String? = null,
    @SerialName("tun_ready") val tunReady: Boolean? = null,
    val relay: String? = null,
    @SerialName("peer_count") val peerCount: Long? = null,
    @SerialName("rule_count") val ruleCount: Long? = null,
    @SerialName("ping_enabled") val pingEnabled: Boolean? = null,
    /** Protocol 0.3; null from older Cores. */
    @SerialName("access_auth_required") val accessAuthRequired: Boolean? = null,
    /** This connection's permission tier (protocol 0.4; null from older Cores). */
    @SerialName("ipc_role") val ipcRole: String? = null,
    /** Credential of Core's coordinator connection (protocol 0.4): `oidc`,
     *  `guest` (no valid MSST login: room mode only) or `none`. */
    @SerialName("coordinator_credential") val coordinatorCredential: String? = null,
) {
    /** Core reported this connection as read-only. */
    val isViewer get() = ipcRole == "viewer"
}

@Serializable
data class NodeInfo(
    @SerialName("device_id") val deviceId: String,
    val ipv4: String? = null,
    val ipv6: String? = null,
    val hostname: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("user_handle") val userHandle: String? = null,
    @SerialName("device_handle") val deviceHandle: String? = null,
    @SerialName("is_authenticated") val isAuthenticated: Boolean = false,
    /** Path mode as last reported by Core; null = not reported. */
    @SerialName("path_mode") val pathMode: String? = null,
    /** Why the path mode last changed (e.g. `no_pong`); null = not reported. */
    @SerialName("path_reason") val pathReason: String? = null,
)

@Serializable
data class RoomPeer(
    @SerialName("device_id") val deviceId: String,
    val ipv4: String? = null,
    val ipv6: String? = null,
    val hostname: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    /** Game discovery with this member (Core 3.1.6+): `ok`, `unsupported`
     *  (an older Core: none either way) or `unknown`; null for this device
     *  and from older Cores. */
    val discovery: String? = null,
)

@Serializable
data class RoomState(
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("room_name") val roomName: String? = null,
    val relay: String? = null,
    val peers: List<RoomPeer> = emptyList(),
    /** The relay the room creator chose (protocol 0.4); null = not specified. */
    @SerialName("room_relay") val roomRelay: RoomRelay? = null,
    /** Host routing problems Core found (from `GetSystemState`; empty from
     *  older Cores).  Not room state, but delivered with it. */
    @SerialName("network_warnings") val networkWarnings: List<NetworkWarning> = emptyList(),
) {
    val inRoom get() = !roomId.isNullOrEmpty()
}

/** Something on Core's host that keeps MSST-Net traffic off the MSST-Net interface
 *  (another VPN, a Docker network, …). */
@Serializable
data class NetworkWarning(
    /** `route_conflict` or `route_hijacked`; an unknown kind is still shown. */
    val kind: String = "",
    /** The MSST-Net range concerned. */
    val range: String = "",
    /** The other interface. */
    val `interface`: String = "",
    /** The conflicting route (`route_conflict`). */
    val route: String? = null,
)

/** A room's own relay as Core reports it. */
@Serializable
data class RoomRelay(
    val address: String = "",
    @SerialName("self_hosted") val selfHosted: Boolean = false,
    /** `probing`, `ok` (room traffic uses it) or `down` (members fall back
     *  to their own relays). */
    val state: String = "",
)

@Serializable
data class ProtocolPort(val proto: String, val start: Int, val end: Int)

/** Rule sources: `devices`/`users` with IDs, or `space` (room members). */
@Serializable
data class PermissionGroup(val kind: String, val ids: List<String> = emptyList())

/** A firewall rule as returned by Core; carried through for display only. */
@Serializable
data class FirewallRule(
    val id: String,
    @SerialName("permission_group") val permissionGroup: PermissionGroup = PermissionGroup("devices"),
    @SerialName("protocol_ports") val protocolPorts: List<ProtocolPort> = emptyList(),
    val action: String = "",
    val direction: String = "inbound",
    @SerialName("expires_at") val expiresAt: Long? = null,
    val enabled: Boolean = true,
    val comment: String? = null,
    /** ID → mnemonic the rule was created with (display only). */
    @SerialName("principal_mnemonics") val principalMnemonics: Map<String, String> = emptyMap(),
    /** The rule still references mnemonics Core could not resolve yet. */
    @SerialName("pending_migration") val pendingMigration: Boolean = false,
)

/** A permission group (`kind` is `user` or `device`). */
@Serializable
data class AclGroup(
    val id: String,
    val name: String,
    val kind: String,
    @SerialName("member_ids") val memberIds: List<String> = emptyList(),
)

@Serializable
data class PendingAuthRequest(
    @SerialName("request_id") val requestId: String,
    @SerialName("src_device_id") val srcDeviceId: String,
    @SerialName("src_user_id") val srcUserId: String? = null,
    @SerialName("src_device_display_name") val srcDeviceDisplayName: String? = null,
    @SerialName("src_user_display_name") val srcUserDisplayName: String? = null,
    val proto: String,
    @SerialName("dst_port") val dstPort: Int,
    @SerialName("service_name") val serviceName: String? = null,
    @SerialName("expires_in_secs") val expiresInSecs: Long? = null,
    /** Local deadline derived from Core's `expires_in_secs`; null until known. */
    @SerialName("expires_at_ms") val expiresAtMs: Long? = null,
)

/** A background operation (protocol 0.4) as last reported by Core. */
@Serializable
data class OperationState(
    @SerialName("request_type") val requestType: String? = null,
    val stage: String? = null,
    /** `{"status":"ok"|"error","data":…}` once finished. */
    val result: JsonObject? = null,
)

@Serializable
data class SystemState(
    val sync: SyncState = SyncState(),
    val health: Health? = null,
    val nodes: Map<String, NodeInfo> = emptyMap(),
    val room: RoomState = RoomState(),
    @SerialName("acl_rules") val aclRules: List<FirewallRule> = emptyList(),
    @SerialName("acl_groups") val aclGroups: List<AclGroup> = emptyList(),
    @SerialName("pending_auth") val pendingAuth: List<PendingAuthRequest> = emptyList(),
    @SerialName("access_auth_required") val accessAuthRequired: Boolean = false,
    val operations: Map<String, OperationState> = emptyMap(),
)

@Serializable
data class ControllerView(
    val connection: ConnectionStatus = ConnectionStatus.Disconnected,
    val diagnostics: ConnectionDiagnostics = ConnectionDiagnostics(),
    @SerialName("core_protocol") val coreProtocol: String? = null,
    @SerialName("controller_protocol") val controllerProtocol: String = "",
    val capabilities: Capabilities = Capabilities(),
    val state: SystemState = SystemState(),
) {
    val connected get() = connection == ConnectionStatus.Connected
}

// ── Notices ──────────────────────────────────────────────────────────────────

/** A change notification (Rust `UiNotice`). */
sealed interface UiNotice {
    /** The view changed; re-read it. */
    data object Changed : UiNotice

    /** Core reported a new interactive access request.  Names are as
     *  supplied by Core and may be absent. */
    data class AccessRequest(
        val requestId: String,
        val srcDeviceId: String,
        val srcDeviceDisplayName: String?,
        val proto: String,
        val dstPort: Int,
        val serviceName: String?,
    ) : UiNotice

    companion object {
        /** Parse a `UiNotice` JSON message; null for unknown types. */
        fun parse(json: String): UiNotice? {
            val o = runCatching { ControllerJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            return when (s("type")) {
                "changed" -> Changed
                "access_request" -> AccessRequest(
                    requestId = s("request_id") ?: return null,
                    srcDeviceId = s("src_device_id") ?: return null,
                    srcDeviceDisplayName = s("src_device_display_name"),
                    proto = s("proto") ?: "",
                    dstPort = s("dst_port")?.toIntOrNull() ?: 0,
                    serviceName = s("service_name"),
                )
                else -> null
            }
        }
    }
}

/** What a user-requested retry did. */
enum class RetryOutcome { Reconnecting, Resyncing }
