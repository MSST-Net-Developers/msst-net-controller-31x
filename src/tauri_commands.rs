//! Tauri command bridge — exposes IPC client and state store to the frontend.
//!
//! Every `#[tauri::command]` function is a thin wrapper that:
//! 1. Reads the `IpcClient` and `StateStore` from Tauri's managed state
//! 2. Delegates to the appropriate IPC client method
//! 3. Returns a serializable result (or a string error for Tauri's `invoke`)
//!
//! ## Design Rules (from AGENTS.md §3)
//!
//! - Commands are **not** final outcomes. A successful `create_room` means the
//!   request was accepted, not that the room exists yet.
//! - The UI must distinguish request-submitted / accepted / in-progress /
//!   completed / failed.
//! - Domain state comes from `StateStore`, never from local booleans.

use std::sync::Arc;

use msst_ipc_protocol::{AuthDecision, IpcRequest};

use tokio::sync::RwLock;

use crate::ipc::client::IpcClient;
use crate::state::{ConnectionStatus, StateStore, SystemState};

// ── Managed state wrapper ────────────────────────────────────────────────────

/// Tauri-managed application state.
///
/// Insert this into the Tauri app via `.manage()` before building.
pub struct ControllerState {
    pub ipc_client: IpcClient,
    pub state_store: StateStore,
}

// ── Connection management ────────────────────────────────────────────────────

/// Get the current IPC connection status.
#[tauri::command]
pub async fn get_connection_status(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<ConnectionStatus, String> {
    let st = state.read().await;
    Ok(st.ipc_client.connection_status().await)
}

/// Get connection diagnostics (reconnect count, success rate, etc.).
#[tauri::command]
pub async fn get_connection_diagnostics(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    let diag = st.ipc_client.connection_diagnostics().await;
    serde_json::to_value(&diag).map_err(|e| e.to_string())
}

// ── State queries ────────────────────────────────────────────────────────────

/// Get the full system state snapshot.
#[tauri::command]
pub async fn get_system_state(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<SystemState, String> {
    let st = state.read().await;
    Ok(st.state_store.snapshot().await)
}

/// Get the health state.
#[tauri::command]
pub async fn get_health(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<crate::state::HealthState, String> {
    let st = state.read().await;
    let snap = st.state_store.snapshot().await;
    Ok(snap.health)
}

/// Get all known nodes.
#[tauri::command]
pub async fn get_nodes(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<Vec<crate::state::NodeInfo>, String> {
    let st = state.read().await;
    let snap = st.state_store.snapshot().await;
    Ok(snap.nodes.into_values().collect())
}

/// Get the current room state.
#[tauri::command]
pub async fn get_room(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<crate::state::RoomState, String> {
    let st = state.read().await;
    let snap = st.state_store.snapshot().await;
    Ok(snap.room)
}

/// Get all ACL rules.
#[tauri::command]
pub async fn get_acl_rules(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<Vec<crate::state::AclRule>, String> {
    let st = state.read().await;
    let snap = st.state_store.snapshot().await;
    Ok(snap.acl_rules)
}

/// Get all ACL groups.
#[tauri::command]
pub async fn get_acl_groups(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<Vec<crate::state::AclGroup>, String> {
    let st = state.read().await;
    let snap = st.state_store.snapshot().await;
    Ok(snap.acl_groups)
}

/// Create a permission group.
#[tauri::command]
pub async fn create_acl_group(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
    group: serde_json::Value,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::CreateAclGroup { group })
        .await
        .map_err(|e| e.to_string())
}

/// Update a permission group.
#[tauri::command]
pub async fn update_acl_group(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
    group_id: String,
    patch: serde_json::Value,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::UpdateAclGroup { group_id, patch })
        .await
        .map_err(|e| e.to_string())
}

/// Delete a permission group.
#[tauri::command]
pub async fn delete_acl_group(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
    group_id: String,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::DeleteAclGroup { group_id })
        .await
        .map_err(|e| e.to_string())
}

/// Get pending auth requests.
#[tauri::command]
pub async fn get_pending_auth(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<Vec<crate::state::PendingAuthRequest>, String> {
    let st = state.read().await;
    let snap = st.state_store.snapshot().await;
    Ok(snap.pending_auth)
}

/// Get access log entries.
#[tauri::command]
pub async fn list_access_log(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::GetAccessLog { limit: None })
        .await
        .map_err(|e| e.to_string())
}

// ── Room commands ────────────────────────────────────────────────────────────

/// Create a room. Returns the room info if accepted by Core.
#[tauri::command]
pub async fn create_room(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
    room_name: String,
    password: Option<String>,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::CreateRoom { room_name, password })
        .await
        .map_err(|e| e.to_string())
}

/// Join an existing room.
#[tauri::command]
pub async fn join_room(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
    room_id: String,
    password: Option<String>,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::JoinRoom { room_id, password })
        .await
        .map_err(|e| e.to_string())
}

/// Leave the current room.
#[tauri::command]
pub async fn leave_room(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
    room_id: String,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::LeaveRoom { room_id })
        .await
        .map_err(|e| e.to_string())
}

// ── ACL commands ─────────────────────────────────────────────────────────────

/// Create a firewall rule.
#[tauri::command]
pub async fn create_acl_rule(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
    rule: serde_json::Value,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::CreateAclRule { rule })
        .await
        .map_err(|e| e.to_string())
}

/// Update a firewall rule.
#[tauri::command]
pub async fn update_acl_rule(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
    rule_id: String,
    patch: serde_json::Value,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::UpdateAclRule { rule_id, patch })
        .await
        .map_err(|e| e.to_string())
}

/// Delete a firewall rule.
#[tauri::command]
pub async fn delete_acl_rule(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
    rule_id: String,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::DeleteAclRule { rule_id })
        .await
        .map_err(|e| e.to_string())
}

/// Clear all firewall rules.
///
/// Iterates through all rules and deletes each one, since the IPC protocol
/// does not have a batch-clear request.
#[tauri::command]
pub async fn clear_acl_rules(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<(), String> {
    let st = state.read().await;
    let snap = st.state_store.snapshot().await;
    for rule in &snap.acl_rules {
        let _ = st
            .ipc_client
            .query(IpcRequest::DeleteAclRule {
                rule_id: rule.id.clone(),
            })
            .await;
    }
    Ok(())
}

// ── Auth commands ────────────────────────────────────────────────────────────

/// Submit an auth decision for a pending interactive auth request.
#[tauri::command]
pub async fn submit_auth_decision(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
    request_id: String,
    decision: String,
) -> Result<serde_json::Value, String> {
    let dec = match decision.as_str() {
        "allow_once" => AuthDecision::AllowOnce,
        "always_allow" => AuthDecision::AlwaysAllow,
        "deny_once" => AuthDecision::DenyOnce,
        "always_deny" => AuthDecision::AlwaysDeny,
        _ => return Err(format!("invalid decision: {decision}")),
    };
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::AuthDecision {
            request_id,
            decision: dec,
        })
        .await
        .map_err(|e| e.to_string())
}

// ── Network / diagnostics ────────────────────────────────────────────────────

/// Get network settings.
#[tauri::command]
pub async fn get_net_settings(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::GetNetSettings)
        .await
        .map_err(|e| e.to_string())
}

/// Set network settings.
#[tauri::command]
pub async fn set_net_settings(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
    settings: serde_json::Value,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::SetNetSettings { settings })
        .await
        .map_err(|e| e.to_string())
}

/// Get path view.
#[tauri::command]
pub async fn get_path_view(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::GetPathView)
        .await
        .map_err(|e| e.to_string())
}

/// Get traffic stats.
#[tauri::command]
pub async fn get_traffic_stats(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::GetTrafficStats)
        .await
        .map_err(|e| e.to_string())
}

/// Get endpoints.
#[tauri::command]
pub async fn get_endpoints(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::GetEndpoints)
        .await
        .map_err(|e| e.to_string())
}

/// Refresh endpoint.
#[tauri::command]
pub async fn refresh_endpoint(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::RefreshEndpoint)
        .await
        .map_err(|e| e.to_string())
}

/// Get relays.
#[tauri::command]
pub async fn get_relays(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::GetRelays)
        .await
        .map_err(|e| e.to_string())
}

/// Set ping enabled.
#[tauri::command]
pub async fn set_ping_enabled(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
    enabled: bool,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::SetPingEnabled { enabled })
        .await
        .map_err(|e| e.to_string())
}

/// Start diagnostic ping.
#[tauri::command]
pub async fn start_diag_ping(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
    device_id: String,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::StartDiagPing { device_id })
        .await
        .map_err(|e| e.to_string())
}

/// Get diagnostic ping result.
#[tauri::command]
pub async fn get_diag_ping_result(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
    job_id: String,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::GetDiagPingResult { job_id })
        .await
        .map_err(|e| e.to_string())
}

// ── Identity ─────────────────────────────────────────────────────────────────

/// Update display name.
#[tauri::command]
pub async fn update_display_name(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
    user: Option<String>,
    device: Option<String>,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::UpdateDisplayName { user, device })
        .await
        .map_err(|e| e.to_string())
}

/// Update handle.
#[tauri::command]
pub async fn update_handle(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
    user: Option<String>,
    device: Option<String>,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::UpdateHandle { user, device })
        .await
        .map_err(|e| e.to_string())
}

/// Re-register device with coordinator.
#[tauri::command]
pub async fn re_register(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::ReRegister)
        .await
        .map_err(|e| e.to_string())
}

// ── Logs ─────────────────────────────────────────────────────────────────────

/// Get logs.
#[tauri::command]
pub async fn get_logs(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::GetLogs { limit: None })
        .await
        .map_err(|e| e.to_string())
}

/// Clear logs.
#[tauri::command]
pub async fn clear_logs(
    state: tauri::State<'_, Arc<RwLock<ControllerState>>>,
) -> Result<serde_json::Value, String> {
    let st = state.read().await;
    st.ipc_client
        .query(IpcRequest::ClearLogs)
        .await
        .map_err(|e| e.to_string())
}