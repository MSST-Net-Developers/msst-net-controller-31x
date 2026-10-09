//! Domain state types.
//!
//! These types represent the Controller's view of Core's authoritative state.
//! They are populated exclusively from IPC snapshots and events — never inferred.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fmt;

// ── Connection lifecycle ─────────────────────────────────────────────────────

/// Controller's IPC connection status.
///
/// This represents the *communication channel* to Core, not the network state.
/// Network state (node connected, path mode, etc.) is part of [`HealthState`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
pub enum ConnectionStatus {
    /// No connection attempt has been made yet.
    #[default]
    Disconnected,
    /// Actively establishing connection (version handshake in progress).
    Connecting,
    /// Connected and receiving events.
    Connected,
    /// Connection lost; attempting to reconnect with exponential backoff.
    Reconnecting { attempt: u32, next_retry_ms: u64 },
}

impl fmt::Display for ConnectionStatus {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Disconnected => write!(f, "Disconnected"),
            Self::Connecting => write!(f, "Connecting"),
            Self::Connected => write!(f, "Connected"),
            Self::Reconnecting {
                attempt,
                next_retry_ms,
            } => write!(
                f,
                "Reconnecting (attempt {attempt}, next retry in {next_retry_ms}ms)"
            ),
        }
    }
}

// ── Health ───────────────────────────────────────────────────────────────────

/// Health state from Core's `/health` endpoint or `Health` query response.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct HealthState {
    pub status: String,
    pub user_id: Option<String>,
    pub device_id: Option<String>,
    pub user_display_name: Option<String>,
    pub device_display_name: Option<String>,
    pub user_handle: Option<String>,
    pub device_handle: Option<String>,
    pub device_hostname: Option<String>,
    pub is_authenticated: bool,
    pub ws_connected: Option<bool>,
    pub tun_ready: Option<bool>,
    pub relay: Option<String>,
}

// ── Nodes ────────────────────────────────────────────────────────────────────

/// A known peer node.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NodeInfo {
    pub device_id: String,
    pub ipv4: String,
    pub ipv6: String,
    pub hostname: Option<String>,
    pub display_name: Option<String>,
    pub path_mode: Option<String>,
}

// ── Room ─────────────────────────────────────────────────────────────────────

/// Current room state.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct RoomState {
    pub room_id: Option<String>,
    pub room_name: Option<String>,
    pub relay: Option<String>,
    pub peers: Vec<RoomPeer>,
}

/// A peer in the current room.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RoomPeer {
    pub device_id: String,
    pub hostname: Option<String>,
    pub display_name: Option<String>,
}

// ── ACL ──────────────────────────────────────────────────────────────────────

/// A firewall/ACL rule as returned by Core.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AclRule {
    pub id: String,
    pub enabled: bool,
    pub action: String,
    pub direction: String,
    pub principals: Vec<serde_json::Value>,
    pub protocol_ports: Vec<serde_json::Value>,
    pub comment: Option<String>,
    pub expires_at: Option<u64>,
}

/// A permission group as returned by Core.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AclGroup {
    pub id: String,
    pub name: String,
    pub kind: String,
    pub member_ids: Vec<String>,
}

// ── Interactive auth ─────────────────────────────────────────────────────────

/// A pending interactive authorization request.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PendingAuthRequest {
    pub request_id: String,
    pub src_device_id: String,
    pub src_user_id: Option<String>,
    pub src_device_display_name: Option<String>,
    pub src_user_display_name: Option<String>,
    pub proto: String,
    pub dst_port: u16,
    pub service_name: Option<String>,
    pub expires_at: Option<u64>,
}

// ── System state snapshot ────────────────────────────────────────────────────

/// The complete system state as received from Core.
///
/// This is populated by `GetSystemState` response or assembled from events.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct SystemState {
    pub health: HealthState,
    pub nodes: HashMap<String, NodeInfo>,
    pub room: RoomState,
    pub acl_rules: Vec<AclRule>,
    pub acl_groups: Vec<AclGroup>,
    pub pending_auth: Vec<PendingAuthRequest>,
}