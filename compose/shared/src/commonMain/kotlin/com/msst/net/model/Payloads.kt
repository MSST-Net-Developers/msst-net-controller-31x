package com.msst.net.model

// Core response payloads queried on demand (not kept in the view).  Shapes
// follow msst-net-core-31x (src/ipc/handler.rs and the modules it calls).

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON settings shared by every backend: tolerant of unknown fields. */
val ControllerJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable
data class NetSettings(
    @SerialName("disable_p2p") val disableP2p: Boolean = false,
    @SerialName("lock_path") val lockPath: Boolean = false,
    @SerialName("preferred_relay") val preferredRelay: String? = null,
    @SerialName("mtu_override") val mtuOverride: Int? = null,
    @SerialName("keepalive_secs") val keepaliveSecs: Int? = null,
    @SerialName("linux_dns_mode") val linuxDnsMode: String? = null,
    /** Linux: Core's own traffic bypasses other VPNs' full tunnels; null
     *  from Cores without the setting. */
    @SerialName("bypass_other_vpns") val bypassOtherVpns: Boolean? = null,
    /** Linux: other VPNs (Tailscale) may not use MSST as their path; null
     *  from Cores without the setting. */
    @SerialName("block_other_vpns_over_msst") val blockOtherVpnsOverMsst: Boolean? = null,
)

@Serializable
data class PathCandidate(
    val addr: String,
    @SerialName("rtt_ms") val rttMs: Double? = null,
    @SerialName("loss_rate") val lossRate: Double? = null,
    val status: String = "",
)

@Serializable
data class PathViewEntry(
    @SerialName("device_id") val deviceId: String,
    @SerialName("display_name") val displayName: String? = null,
    /** `relay`, `probing` or `direct:<ip:port>`. */
    val chosen: String = "",
    val candidates: List<PathCandidate> = emptyList(),
    /** WireGuard session (0.4): `no_key`, `unknown`, `handshaking`,
     *  `encrypted` or `failed`; null from older Cores. */
    val encryption: String? = null,
    /** Unix seconds, while a session exists. */
    @SerialName("last_handshake_at") val lastHandshakeAt: Long? = null,
    /** Failed probe rounds; no probing before [nextProbeAt] (Unix seconds). */
    @SerialName("probe_failures") val probeFailures: Int = 0,
    @SerialName("next_probe_at") val nextProbeAt: Long? = null,
)

@Serializable
data class PathView(val peers: List<PathViewEntry> = emptyList())

@Serializable
data class TrafficEntry(
    @SerialName("device_id") val deviceId: String,
    @SerialName("display_name") val displayName: String? = null,
    val tx: Long = 0,
    val rx: Long = 0,
    val drop: Long = 0,
)

@Serializable
data class TrafficStats(val peers: List<TrafficEntry> = emptyList())

@Serializable
data class EndpointRecord(val endpoints: List<String> = emptyList(), val timestamp: Long = 0)

@Serializable
data class EndpointInfo(val current: List<String> = emptyList(), val history: List<EndpointRecord> = emptyList())

/** An official relay (`GET /relays`, passed through by Core). */
@Serializable
data class Relay(val addr: String, val features: List<String> = emptyList())

@Serializable
data class RelayList(
    val relays: List<Relay> = emptyList(),
    val current: String? = null,
    @SerialName("rtt_ms") val rttMs: Map<String, Double> = emptyMap(),
    @SerialName("secure_discovery") val secureDiscovery: SecureDiscovery? = null,
    /** Registration with the current relay (0.4). */
    val registration: RelayRegistration? = null,
    /** The room's own relay; null outside a room that has one (0.4). */
    @SerialName("room_relay") val roomRelay: RoomRelayDiag? = null,
)

@Serializable
data class RelayRegistration(
    /** `sreg1` (authenticated) or `legacy`. */
    val mode: String = "",
    @SerialName("tx_sreg") val txSreg: Long = 0,
    /** Unix seconds a relay-path probe last came back through our relay. */
    @SerialName("last_relay_path_ok_at") val lastRelayPathOkAt: Long? = null,
)

@Serializable
data class RoomRelayDiag(
    val address: String = "",
    @SerialName("self_hosted") val selfHosted: Boolean = false,
    val state: String = "",
    /** Resolved `IP:port`. */
    val addr: String? = null,
    @SerialName("tx_sreg") val txSreg: Long = 0,
)

/** Game discovery through the current relay (`GetRelays`).  Inactive when
 *  the relay has no public key. */
@Serializable
data class SecureDiscovery(
    val active: Boolean = false,
    @SerialName("rx_accepted") val rxAccepted: Long = 0,
    /** Dropped packets by reason (`unverified_sender`, `duplicate`, …). */
    val drop: Map<String, Long> = emptyMap(),
    /** Relays discovery goes to (`IP:port`; empty outside a room). */
    val relays: List<String> = emptyList(),
)

@Serializable
data class LogEntry(
    val level: String = "",
    val target: String = "",
    val message: String = "",
    @SerialName("timestamp_ms") val timestampMs: Long = 0,
)

@Serializable
data class LogList(val entries: List<LogEntry> = emptyList())

/** Why Core allowed or denied an access (`type` tagged). */
@Serializable
data class AccessReason(
    /** `matched_rule`, `interactive_auth`, `interactive_auth_timeout`,
     *  `interactive_auth_limit`, `default_deny`. */
    val type: String = "",
    @SerialName("rule_id") val ruleId: String? = null,
    val comment: String? = null,
)

@Serializable
data class AccessLogEntry(
    val id: String,
    @SerialName("src_device_id") val srcDeviceId: String = "",
    @SerialName("service_name") val serviceName: String? = null,
    val proto: String = "",
    @SerialName("dst_port") val dstPort: Int = 0,
    /** `allow`, `deny` or `silent_deny`. */
    val decision: String = "",
    val reason: AccessReason = AccessReason(),
    @SerialName("timestamp_ms") val timestampMs: Long = 0,
    @SerialName("rule_id") val ruleId: String? = null,
    val count: Long = 1,
)

@Serializable
data class AccessLog(val entries: List<AccessLogEntry> = emptyList())

@Serializable
data class ServiceItem(val id: String, val name: String, val ports: List<String> = emptyList())

@Serializable
data class ServiceCategory(val id: String, val label: String, val services: List<ServiceItem> = emptyList())

@Serializable
data class ServiceList(val categories: List<ServiceCategory> = emptyList())

@Serializable
data class AuthStartResult(
    @SerialName("session_id") val sessionId: String,
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_uri") val verificationUri: String = "",
    val interval: Int? = null,
)

@Serializable
data class AuthPollResult(val pending: Boolean? = null, val status: String? = null) {
    /** Still waiting for the user (OIDC device flow). */
    val waiting get() = pending == true || status == "authorization_pending" || status == "slow_down"
}

@Serializable
data class NamespaceRegistration(
    val namespace: String,
    @SerialName("user_id") val userId: String = "",
    val reason: String = "",
    /** `pending`, `approved` or `rejected`. */
    val status: String = "",
    @SerialName("reject_reason") val rejectReason: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    val paused: Boolean = false,
)

@Serializable
data class NamespaceList(val namespaces: List<NamespaceRegistration> = emptyList())

@Serializable
data class DnsRecord(
    val id: String,
    val namespace: String = "",
    /** `CNAME`, `TXT` or `SRV`. */
    @SerialName("record_type") val recordType: String,
    val name: String,
    val content: String,
    val ttl: Int = 300,
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class DnsRecordList(val records: List<DnsRecord> = emptyList())

@Serializable
data class ClearRulesFailure(@SerialName("rule_id") val ruleId: String, val message: String)

@Serializable
data class ClearRulesResult(val deleted: Int = 0, val failed: List<ClearRulesFailure> = emptyList())

@Serializable
data class DiagPingJob(@SerialName("job_id") val jobId: String)

@Serializable
data class DiagPingResult(val results: List<Double?> = emptyList(), val done: Boolean = false)
