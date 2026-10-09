//! WebUI backend — Axum HTTP server for the browser-based controller.
//!
//! Provides:
//! - REST API for state queries and commands (proxied to IPC client)
//! - WebSocket endpoint for real-time state change events
//! - CORS for local development
//!
//! ## Architecture
//!
//! ```text
//! Browser  ──HTTP/WS──▶  Axum (this module)  ──IPC──▶  Core
//! ```
//!
//! The Axum server is a thin proxy. It does not maintain its own state;
//! all domain data flows through `StateStore` and `IpcClient`.

use std::net::SocketAddr;

use axum::extract::{State as AxumState, WebSocketUpgrade};
use axum::http::StatusCode;
use axum::response::Response;
use axum::routing::{get, post};
use axum::{Json, Router};
use msst_ipc_protocol::{AuthDecision, IpcRequest};
use serde::Deserialize;
use tokio::sync::broadcast;

use crate::error::ControllerError;
use crate::ipc::client::IpcClient;
use crate::state::{StateChange, StateStore, SystemState};

// ── Shared state ─────────────────────────────────────────────────────────────

/// Shared application state for the Axum server.
#[derive(Clone)]
pub struct WebState {
    pub ipc_client: IpcClient,
    pub state_store: StateStore,
}

// ── Server builder ───────────────────────────────────────────────────────────

/// Build the Axum router with all routes.
pub fn build_router(state: WebState) -> Router {
    Router::new()
        // State queries
        .route("/api/state", get(get_system_state))
        .route("/api/health", get(get_health))
        .route("/api/nodes", get(get_nodes))
        .route("/api/room", get(get_room))
        .route("/api/acl/rules", get(get_acl_rules).post(create_acl_rule).delete(clear_acl_rules))
        .route("/api/acl/groups", get(get_acl_groups).post(create_acl_group))
        .route("/api/acl/groups/{group_id}", post(update_acl_group).delete(delete_acl_group))
        .route("/api/acl/access-log", get(get_access_log))
        .route("/api/auth/pending", get(get_pending_auth))
        .route("/api/connection", get(get_connection_status))
        .route("/api/diagnostics", get(get_connection_diagnostics))
        // Commands
        .route("/api/room/create", post(create_room))
        .route("/api/room/join", post(join_room))
        .route("/api/room/leave", post(leave_room))
        .route("/api/acl/rules/{rule_id}", post(update_acl_rule).delete(delete_acl_rule))
        .route("/api/auth/decision", post(submit_auth_decision))
        .route("/api/net/settings", get(get_net_settings).put(set_net_settings))
        .route("/api/net/path-view", get(get_path_view))
        .route("/api/net/traffic", get(get_traffic_stats))
        .route("/api/net/endpoints", get(get_endpoints))
        .route("/api/net/refresh-endpoint", post(refresh_endpoint))
        .route("/api/relays", get(get_relays))
        .route("/api/ping", get(get_ping).post(set_ping_enabled))
        .route("/api/net/ping-peer", post(start_diag_ping))
        .route("/api/net/ping-peer/{job_id}", get(get_diag_ping_result))
        .route("/api/identity/display-name", post(update_display_name))
        .route("/api/identity/handle", post(update_handle))
        .route("/api/re-register", post(re_register))
        .route("/api/logs", get(get_logs).delete(clear_logs))
        // WebSocket for real-time events
        .route("/ws/events", get(ws_events))
        .with_state(state)
}

/// Start the Axum server.
pub async fn serve(state: WebState, addr: SocketAddr) -> Result<(), ControllerError> {
    let app = build_router(state);

    tracing::info!(%addr, "WebUI server starting");

    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .map_err(|e| ControllerError::Io(e.to_string()))?;

    axum::serve(listener, app.into_make_service())
        .await
        .map_err(|e| ControllerError::Io(e.to_string()))
}

// ── State queries ────────────────────────────────────────────────────────────

async fn get_system_state(
    AxumState(st): AxumState<WebState>,
) -> Json<SystemState> {
    Json(st.state_store.snapshot().await)
}

async fn get_health(
    AxumState(st): AxumState<WebState>,
) -> Json<serde_json::Value> {
    let snap = st.state_store.snapshot().await;
    Json(serde_json::to_value(&snap.health).unwrap_or_default())
}

async fn get_nodes(
    AxumState(st): AxumState<WebState>,
) -> Json<serde_json::Value> {
    let snap = st.state_store.snapshot().await;
    let nodes: Vec<_> = snap.nodes.into_values().collect();
    Json(serde_json::to_value(&nodes).unwrap_or_default())
}

async fn get_room(
    AxumState(st): AxumState<WebState>,
) -> Json<serde_json::Value> {
    let snap = st.state_store.snapshot().await;
    Json(serde_json::to_value(&snap.room).unwrap_or_default())
}

async fn get_acl_rules(
    AxumState(st): AxumState<WebState>,
) -> Json<serde_json::Value> {
    let snap = st.state_store.snapshot().await;
    Json(serde_json::json!({ "rules": snap.acl_rules }))
}

async fn get_pending_auth(
    AxumState(st): AxumState<WebState>,
) -> Json<serde_json::Value> {
    let snap = st.state_store.snapshot().await;
    Json(serde_json::json!({ "requests": snap.pending_auth }))
}

async fn get_connection_status(
    AxumState(st): AxumState<WebState>,
) -> Json<serde_json::Value> {
    let status = st.ipc_client.connection_status().await;
    Json(serde_json::json!({ "status": status }))
}

async fn get_connection_diagnostics(
    AxumState(st): AxumState<WebState>,
) -> Json<serde_json::Value> {
    let diag = st.ipc_client.connection_diagnostics().await;
    Json(serde_json::to_value(&diag).unwrap_or_default())
}

// ── Commands ─────────────────────────────────────────────────────────────────

async fn proxy_query(
    client: &IpcClient,
    req: IpcRequest,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    match client.query(req).await {
        Ok(data) => Ok(Json(data)),
        Err(e) => Err(map_error(e)),
    }
}

fn map_error(e: ControllerError) -> (StatusCode, String) {
    let status = match &e {
        ControllerError::ConnectionFailed { .. } => StatusCode::SERVICE_UNAVAILABLE,
        ControllerError::VersionMismatch { .. } => StatusCode::HTTP_VERSION_NOT_SUPPORTED,
        ControllerError::ConnectionLost => StatusCode::SERVICE_UNAVAILABLE,
        ControllerError::NotConnected { .. } => StatusCode::SERVICE_UNAVAILABLE,
        ControllerError::RequestTimeout { .. } => StatusCode::GATEWAY_TIMEOUT,
        ControllerError::ClientShutdown { .. } => StatusCode::SERVICE_UNAVAILABLE,
        ControllerError::CoreError { code, .. } => match code.as_str() {
            "not_found" => StatusCode::NOT_FOUND,
            "not_implemented" => StatusCode::NOT_IMPLEMENTED,
            "invalid_rule" => StatusCode::BAD_REQUEST,
            _ => StatusCode::BAD_GATEWAY,
        },
        _ => StatusCode::INTERNAL_SERVER_ERROR,
    };
    (status, e.to_string())
}

#[derive(Deserialize)]
struct CreateRoomBody {
    room_name: String,
    password: Option<String>,
}

async fn create_room(
    AxumState(st): AxumState<WebState>,
    Json(body): Json<CreateRoomBody>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::CreateRoom {
        room_name: body.room_name,
        password: body.password,
    }).await
}

#[derive(Deserialize)]
struct JoinRoomBody {
    room_id: String,
    password: Option<String>,
}

async fn join_room(
    AxumState(st): AxumState<WebState>,
    Json(body): Json<JoinRoomBody>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::JoinRoom {
        room_id: body.room_id,
        password: body.password,
    }).await
}

#[derive(Deserialize)]
struct LeaveRoomBody {
    room_id: String,
}

async fn leave_room(
    AxumState(st): AxumState<WebState>,
    Json(body): Json<LeaveRoomBody>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::LeaveRoom {
        room_id: body.room_id,
    }).await
}

async fn create_acl_rule(
    AxumState(st): AxumState<WebState>,
    Json(rule): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::CreateAclRule { rule }).await
}

async fn update_acl_rule(
    AxumState(st): AxumState<WebState>,
    axum::extract::Path(rule_id): axum::extract::Path<String>,
    Json(patch): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::UpdateAclRule { rule_id, patch }).await
}

async fn delete_acl_rule(
    AxumState(st): AxumState<WebState>,
    axum::extract::Path(rule_id): axum::extract::Path<String>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::DeleteAclRule { rule_id }).await
}

async fn clear_acl_rules(
    AxumState(st): AxumState<WebState>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let snap = st.state_store.snapshot().await;
    for rule in &snap.acl_rules {
        let _ = st.ipc_client.query(IpcRequest::DeleteAclRule { rule_id: rule.id.clone() }).await;
    }
    Ok(Json(serde_json::json!({ "ok": true })))
}

async fn get_access_log(
    AxumState(st): AxumState<WebState>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::GetAccessLog { limit: None }).await
}

async fn get_acl_groups(
    AxumState(st): AxumState<WebState>,
) -> Json<serde_json::Value> {
    let snap = st.state_store.snapshot().await;
    Json(serde_json::json!({ "groups": snap.acl_groups }))
}

async fn create_acl_group(
    AxumState(st): AxumState<WebState>,
    Json(group): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::CreateAclGroup { group }).await
}

async fn update_acl_group(
    AxumState(st): AxumState<WebState>,
    axum::extract::Path(group_id): axum::extract::Path<String>,
    Json(patch): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::UpdateAclGroup { group_id, patch }).await
}

async fn delete_acl_group(
    AxumState(st): AxumState<WebState>,
    axum::extract::Path(group_id): axum::extract::Path<String>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::DeleteAclGroup { group_id }).await
}

#[derive(Deserialize)]
struct AuthDecisionBody {
    request_id: String,
    decision: String,
}

async fn submit_auth_decision(
    AxumState(st): AxumState<WebState>,
    Json(body): Json<AuthDecisionBody>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let dec = match body.decision.as_str() {
        "allow_once" => AuthDecision::AllowOnce,
        "always_allow" => AuthDecision::AlwaysAllow,
        "deny_once" => AuthDecision::DenyOnce,
        "always_deny" => AuthDecision::AlwaysDeny,
        _ => return Err((StatusCode::BAD_REQUEST, format!("invalid decision: {}", body.decision))),
    };
    proxy_query(&st.ipc_client, IpcRequest::AuthDecision {
        request_id: body.request_id,
        decision: dec,
    }).await
}

async fn get_net_settings(
    AxumState(st): AxumState<WebState>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::GetNetSettings).await
}

async fn set_net_settings(
    AxumState(st): AxumState<WebState>,
    Json(settings): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::SetNetSettings { settings }).await
}

async fn get_path_view(
    AxumState(st): AxumState<WebState>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::GetPathView).await
}

async fn get_traffic_stats(
    AxumState(st): AxumState<WebState>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::GetTrafficStats).await
}

async fn get_endpoints(
    AxumState(st): AxumState<WebState>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::GetEndpoints).await
}

async fn refresh_endpoint(
    AxumState(st): AxumState<WebState>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::RefreshEndpoint).await
}

async fn get_relays(
    AxumState(st): AxumState<WebState>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::GetRelays).await
}

#[derive(Deserialize)]
struct PingBody {
    enabled: bool,
}

async fn set_ping_enabled(
    AxumState(st): AxumState<WebState>,
    Json(body): Json<PingBody>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::SetPingEnabled { enabled: body.enabled }).await
}

async fn get_ping(
    AxumState(st): AxumState<WebState>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::GetNetSettings).await
}

#[derive(Deserialize)]
struct DiagPingBody {
    device_id: String,
}

async fn start_diag_ping(
    AxumState(st): AxumState<WebState>,
    Json(body): Json<DiagPingBody>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::StartDiagPing { device_id: body.device_id }).await
}

async fn get_diag_ping_result(
    AxumState(st): AxumState<WebState>,
    axum::extract::Path(job_id): axum::extract::Path<String>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::GetDiagPingResult { job_id }).await
}

#[derive(Deserialize)]
struct DisplayNameBody {
    user: Option<String>,
    device: Option<String>,
}

async fn update_display_name(
    AxumState(st): AxumState<WebState>,
    Json(body): Json<DisplayNameBody>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::UpdateDisplayName {
        user: body.user,
        device: body.device,
    }).await
}

#[derive(Deserialize)]
struct HandleBody {
    user: Option<String>,
    device: Option<String>,
}

async fn update_handle(
    AxumState(st): AxumState<WebState>,
    Json(body): Json<HandleBody>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::UpdateHandle {
        user: body.user,
        device: body.device,
    }).await
}

async fn re_register(
    AxumState(st): AxumState<WebState>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::ReRegister).await
}

async fn get_logs(
    AxumState(st): AxumState<WebState>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::GetLogs { limit: None }).await
}

async fn clear_logs(
    AxumState(st): AxumState<WebState>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    proxy_query(&st.ipc_client, IpcRequest::ClearLogs).await
}

// ── WebSocket events ─────────────────────────────────────────────────────────

/// WebSocket handler that streams state change events to the browser.
async fn ws_events(
    ws: WebSocketUpgrade,
    AxumState(st): AxumState<WebState>,
) -> Response {
    ws.on_upgrade(move |socket| handle_ws(socket, st))
}

async fn handle_ws(
    socket: axum::extract::ws::WebSocket,
    state: WebState,
) {
    use axum::extract::ws::Message as WsMsg;
    use futures_util::SinkExt;

    let mut rx = state.state_store.subscribe();
    let (mut sender, mut receiver) = socket.split();

    // Spawn a task that forwards state changes as JSON to the WebSocket.
    let send_task = tokio::spawn(async move {
        loop {
            match rx.recv().await {
                Ok(change) => {
                    let json = match &change {
                        StateChange::SnapshotApplied => {
                            serde_json::json!({ "type": "snapshot_applied" })
                        }
                        StateChange::EventApplied(event) => {
                            serde_json::json!({ "type": "event", "data": event })
                        }
                    };
                    let text = json.to_string();
                    if sender.send(WsMsg::Text(text.into())).await.is_err() {
                        break;
                    }
                }
                Err(broadcast::error::RecvError::Lagged(n)) => {
                    let msg = serde_json::json!({ "type": "lagged", "dropped": n }).to_string();
                    let _ = sender.send(WsMsg::Text(msg.into())).await;
                }
                Err(broadcast::error::RecvError::Closed) => break,
            }
        }
    });

    // Read from WebSocket (ignore client messages except Close).
    loop {
        match receiver.next().await {
            Some(Ok(WsMsg::Close(_))) | None => break,
            _ => {} // Ignore other messages
        }
    }

    send_task.abort();
}

// Import StreamExt for receiver.next()
use futures_util::StreamExt;