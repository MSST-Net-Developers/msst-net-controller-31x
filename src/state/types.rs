//! Domain state types.
//!
//! These types represent the Controller's view of Core's authoritative state.
//! They are populated exclusively from IPC query responses and events — never
//! inferred.  Field names follow Core's actual response payloads (see the
//! `from_core_*` tests in `store.rs`, which use Core's response shapes).

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fmt;

// ── Connection lifecycle ─────────────────────────────────────────────────────

/// Controller's IPC connection status.
///
/// This represents the *communication channel* to Core, not the network state.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
pub enum ConnectionStatus {
    /// No connection attempt has been made yet, or the connection was lost.
    #[default]
    Disconnected,
    /// Actively establishing connection (version handshake in progress).
    Connecting,
    /// Connected and receiving events.
    Connected,
    /// Connection failed; retrying with exponential backoff.
    Reconnecting { attempt: u32, next_retry_ms: u64 },
    /// Core speaks an incompatible protocol major version.  Retried slowly in
    /// case Core is upgraded; details are in the connection diagnostics.
    Incompatible,
}

impl fmt::Display for ConnectionStatus {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Disconnected => write!(f, "Disconnected"),
            Self::Connecting => write!(f, "Connecting"),
            Self::Connected => write!(f, "Connected"),
            Self::Reconnecting { attempt, next_retry_ms } => {
                write!(f, "Reconnecting (attempt {attempt}, next retry in {next_retry_ms}ms)")
            }
            Self::Incompatible => write!(f, "Incompatible"),
        }
    }
}

/// Whether the local view has been (re)built from Core since the last connect.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(tag = "state", rename_all = "snake_case")]
pub enum SyncState {
    /// Not connected since startup, or the connection was lost: the data
    /// shown may be stale.
    #[default]
    NotSynced,
    /// Initial queries in progress.
    Syncing,
    /// The view was rebuilt from Core and is being kept current by events.
    Synced,
    /// Some initial queries failed; the affected sections may be missing.
    Failed { message: String },
}

// ── Health ───────────────────────────────────────────────────────────────────

/// Core's `Health` response.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct HealthState {
    pub status: String,
    /// Advances on every Controller-visible change; restarts from 0 when
    /// Core restarts (protocol docs, P4).
    pub generation: Option<u64>,
    pub device_id: Option<String>,
    pub user_id: Option<String>,
    pub device_handle: Option<String>,
    pub user_handle: Option<String>,
    pub user_display_name: Option<String>,
    pub device_display_name: Option<String>,
    pub device_hostname: Option<String>,
    /// Whether Core holds a valid (unexpired) OIDC access token.
    pub is_authenticated: bool,
    pub auth_expires_at: Option<i64>,
    /// Whether Core's coordinator WebSocket is connected.
    pub ws_connected: Option<bool>,
    pub coordinator_state: Option<String>,
    pub tun_ready: Option<bool>,
    pub relay: Option<String>,
    pub peer_count: Option<u64>,
    pub rule_count: Option<u64>,
    pub ping_enabled: Option<bool>,
    /// Core needs MSST-Net access authentication (protocol 0.3; `None` from
    /// older Cores).
    pub access_auth_required: Option<bool>,
    /// This connection's permission tier, `"viewer"` (queries only) or
    /// `"admin"` (protocol 0.4; `None` from older Cores, which allow
    /// everything).  Kept as text so an unknown future role does not make
    /// the whole Health response undecodable.
    pub ipc_role: Option<String>,
    /// Credential of Core's coordinator connection (protocol 0.4): `"oidc"`,
    /// `"guest"` (no valid MSST login: room mode only) or `"none"`.
    pub coordinator_credential: Option<String>,
}

impl HealthState {
    /// Core reported this connection as read-only (`ipc_role = "viewer"`).
    pub fn is_viewer(&self) -> bool {
        self.ipc_role.as_deref() == Some("viewer")
    }
}

// ── Nodes ────────────────────────────────────────────────────────────────────

/// A known peer node (Core's `GetNodes` entry).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct NodeInfo {
    pub device_id: String,
    #[serde(default)]
    pub ipv4: Option<String>,
    #[serde(default)]
    pub ipv6: Option<String>,
    #[serde(default)]
    pub hostname: Option<String>,
    #[serde(default)]
    pub display_name: Option<String>,
    #[serde(default)]
    pub user_id: Option<String>,
    #[serde(default)]
    pub user_handle: Option<String>,
    #[serde(default)]
    pub device_handle: Option<String>,
    #[serde(default)]
    pub is_authenticated: bool,
    /// Path mode as last reported by Core (`GetPathView` `chosen` or a
    /// `PathChanged` event).  `None` = Core has not reported one.
    #[serde(default)]
    pub path_mode: Option<String>,
}

// ── Room ─────────────────────────────────────────────────────────────────────

/// Current room state (Core's `GetSystemState` room view).
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct RoomState {
    pub room_id: Option<String>,
    pub room_name: Option<String>,
    pub relay: Option<String>,
    pub peers: Vec<RoomPeer>,
    /// The relay the room creator chose for room traffic (protocol 0.4);
    /// `None` = not specified (members use their own relays).
    pub room_relay: Option<RoomRelay>,
    /// Host routing problems Core found (`GetSystemState` only, not in
    /// `RoomStateChanged`; empty from older Cores and where Core doesn't
    /// check).  Not room state, but it arrives with the same response.
    pub network_warnings: Vec<NetworkWarning>,
}

/// Something on Core's host that keeps MSST-Net traffic off the MSST-Net
/// interface
/// (another VPN, a Docker network, …), as Core reports it.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct NetworkWarning {
    /// `route_conflict` (another interface's `route` lies inside the MSST-Net
    /// `range`) or `route_hijacked` (traffic to `range` leaves through
    /// `interface`).  Kept as text: an unknown kind is still shown.
    pub kind: String,
    pub range: String,
    pub interface: String,
    pub route: Option<String>,
}

/// A room's own relay, as Core reports it.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct RoomRelay {
    pub address: String,
    pub self_hosted: bool,
    /// `probing`, `ok` (room traffic uses it) or `down` (members fall back
    /// to their own relays).
    pub state: String,
}

/// A peer in the current room (including this device).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RoomPeer {
    pub device_id: String,
    #[serde(default)]
    pub ipv4: Option<String>,
    #[serde(default)]
    pub ipv6: Option<String>,
    #[serde(default)]
    pub hostname: Option<String>,
    #[serde(default)]
    pub display_name: Option<String>,
}

// ── ACL ──────────────────────────────────────────────────────────────────────

/// A firewall rule as returned by Core.
///
/// Only the identifier is interpreted by the Controller; every other field
/// (`permission_group`, `protocol_ports`, `action`, `principal_mnemonics`,
/// `pending_migration`, …) is carried through unchanged for display, so the
/// Controller never re-interprets ACL semantics.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AclRule {
    pub id: String,
    #[serde(flatten)]
    pub fields: serde_json::Map<String, serde_json::Value>,
}

/// A permission group as returned by Core.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AclGroup {
    pub id: String,
    pub name: String,
    /// `"user"` or `"device"`.
    pub kind: String,
    /// IDs (or not-yet-migrated mnemonics) as stored by Core.
    #[serde(default)]
    pub member_ids: Vec<String>,
}

// ── Interactive auth ─────────────────────────────────────────────────────────

/// A pending interactive authorization request.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PendingAuthRequest {
    pub request_id: String,
    pub src_device_id: String,
    #[serde(default)]
    pub src_user_id: Option<String>,
    #[serde(default)]
    pub src_device_display_name: Option<String>,
    #[serde(default)]
    pub src_user_display_name: Option<String>,
    pub proto: String,
    pub dst_port: u16,
    #[serde(default)]
    pub service_name: Option<String>,
    /// Remaining time as reported by Core's `GetPendingAuth`.
    #[serde(default)]
    pub expires_in_secs: Option<i64>,
    /// Local wall-clock deadline (ms since epoch) derived from
    /// `expires_in_secs` when the list was received.  `None` until Core has
    /// reported the remaining time.
    #[serde(default)]
    pub expires_at_ms: Option<u64>,
}

// ── Background operations ────────────────────────────────────────────────────

/// A command Core runs as a background operation (protocol 0.4, `async`),
/// as last reported by Core (`OperationProgress` / `OperationCompleted` /
/// `GetOperation`).
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct OperationState {
    /// The request's `type`, e.g. `"create_room"`.  `None` until known
    /// (progress events do not carry it).
    pub request_type: Option<String>,
    /// Last reported step, e.g. `"relay_selected"`.
    pub stage: Option<String>,
    /// What the synchronous request would have returned; `None` while the
    /// operation is running.
    pub result: Option<msst_ipc_protocol::IpcResponse>,
}

// ── System state snapshot ────────────────────────────────────────────────────

/// The Controller's complete view of Core state.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct SystemState {
    pub sync: SyncState,
    pub health: Option<HealthState>,
    pub nodes: BTreeMap<String, NodeInfo>,
    pub room: RoomState,
    pub acl_rules: Vec<AclRule>,
    pub acl_groups: Vec<AclGroup>,
    pub pending_auth: Vec<PendingAuthRequest>,
    /// Core needs MSST-Net access authentication.  Source of truth is
    /// `Health.access_auth_required` (protocol 0.3); with older Cores it is
    /// set by the `AccessAuthRequired` event and cleared once `Health`
    /// reports a valid authentication.
    pub access_auth_required: bool,
    /// Recent background operations by ID (bounded; cleared on reconnect,
    /// when progress may have been missed).
    pub operations: BTreeMap<String, OperationState>,
}

/// Sections of [`SystemState`] that can be re-queried from Core individually.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Resource {
    Health,
    Room,
    Nodes,
    AclRules,
    AclGroups,
    PendingAuth,
}

impl Resource {
    pub const ALL: [Resource; 6] = [
        Resource::Health,
        Resource::Room,
        Resource::Nodes,
        Resource::AclRules,
        Resource::AclGroups,
        Resource::PendingAuth,
    ];
}
