//! WebUI backend — serves the Controller frontend (the Compose web build,
//! `compose/webApp`) over HTTPS for remote / headless management, with the
//! 3.0.x security model:
//!
//! - every request: source IP must be in `allowed_cidrs` (config.toml)
//! - everything except login and static files: valid session cookie
//!   (`msst_session`, HttpOnly, Secure, SameSite=Lax, 24 h)
//! - password: argon2 hash in `passwd`, set on first run
//! - TLS: fresh self-signed certificate per run (see the binary)
//!
//! API (same surface as the Tauri commands):
//!
//! | Route | Purpose |
//! |---|---|
//! | `POST /api/login`, `POST /api/logout`, `GET /api/session` | session |
//! | `GET /api/info` | WebUI version and protocol version |
//! | `GET/PUT /api/config` | allowed CIDRs |
//! | `GET /api/view` | connection status, diagnostics, Core state |
//! | `POST /api/ipc` | any `IpcRequest` (`{ "type", "params" }`) except `auth_decision` |
//! | `POST /api/acl/clear` | delete all firewall rules |
//! | `GET /ws/events` | `UiNotice::Changed` messages (re-read `/api/view`) |
//!
//! The WebUI does not take part in interactive authorization: access
//! requests are answered on the device itself (desktop or mobile
//! Controller), or time out in Core.

pub mod auth;
pub mod config;

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::{Arc, RwLock};

use axum::body::Body;
use axum::extract::ws::{Message as WsMsg, WebSocket, WebSocketUpgrade};
use axum::extract::{ConnectInfo, Request, State};
use axum::handler::Handler;
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use msst_ipc_protocol::IpcRequest;
use serde::Deserialize;
use serde_json::json;
use tower_http::compression::CompressionLayer;

use crate::error::{ControllerError, UiError};
use crate::ipc::client::IpcClient;
use crate::notify::{UiNotice, spawn_notifier};
use auth::SessionStore;
use config::Config;

const COOKIE: &str = "msst_session";

/// Shared state of the WebUI server.
#[derive(Clone)]
pub struct WebState {
    pub client: IpcClient,
    pub config: Arc<RwLock<Config>>,
    pub config_path: Arc<PathBuf>,
    pub passwd_path: Arc<PathBuf>,
    pub sessions: Arc<SessionStore>,
}

/// Build the router.  Requires `ConnectInfo<SocketAddr>` (use
/// `into_make_service_with_connect_info`).
pub fn build_router(state: WebState) -> Router {
    let protected = Router::new()
        .route("/api/session", get(|| async { Json(json!({ "authenticated": true })) }))
        .route("/api/logout", post(logout))
        .route("/api/info", get(info))
        .route("/api/config", get(get_config).put(update_config))
        .route("/api/view", get(view))
        .route("/api/ipc", post(ipc))
        .route("/api/acl/clear", post(clear_rules))
        .route("/api/reconnect", post(reconnect))
        .route("/ws/events", get(ws_events))
        .layer(middleware::from_fn_with_state(state.clone(), auth_middleware));

    // The frontend is ~13 MB of wasm; gzip brings a first load to ~5 MB.
    let public = Router::new()
        .route("/api/login", post(login))
        .fallback(static_handler.layer(CompressionLayer::new()));

    Router::new()
        .merge(protected)
        .merge(public)
        .layer(middleware::from_fn_with_state(state.clone(), ip_filter_middleware))
        .with_state(state)
}

// ── Middleware ───────────────────────────────────────────────────────────────

async fn ip_filter_middleware(
    State(state): State<WebState>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    req: Request,
    next: Next,
) -> Response {
    let allowed = state.config.read().unwrap().parsed_cidrs();
    if allowed.iter().any(|net| net.contains(addr.ip())) {
        next.run(req).await
    } else {
        (StatusCode::FORBIDDEN, "Forbidden: your IP is not in the allowed list\n").into_response()
    }
}

async fn auth_middleware(State(state): State<WebState>, req: Request, next: Next) -> Response {
    if session_cookie(req.headers()).is_some_and(|t| state.sessions.validate(t)) {
        next.run(req).await
    } else {
        (StatusCode::UNAUTHORIZED, [("X-Webui-Unauthorized", "1")], Json(json!({ "error": "unauthorized" }))).into_response()
    }
}

fn session_cookie(headers: &HeaderMap) -> Option<&str> {
    let cookie = headers.get(header::COOKIE)?.to_str().ok()?;
    cookie.split(';').find_map(|part| part.trim().strip_prefix("msst_session="))
}

// ── Session routes ───────────────────────────────────────────────────────────

#[derive(Deserialize)]
struct LoginBody {
    password: String,
}

async fn login(State(state): State<WebState>, Json(body): Json<LoginBody>) -> Response {
    let hash = match auth::read_passwd(&state.passwd_path) {
        Ok(h) => h,
        Err(e) => {
            tracing::error!("cannot read WebUI password file: {e}");
            return (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": "server error" }))).into_response();
        }
    };
    let ok = tokio::task::spawn_blocking(move || auth::verify_password(&body.password, &hash)).await.unwrap_or(false);
    if !ok {
        // Slow down online guessing.
        tokio::time::sleep(std::time::Duration::from_millis(500)).await;
        return (StatusCode::UNAUTHORIZED, Json(json!({ "error": "invalid password" }))).into_response();
    }
    let token = state.sessions.create();
    let cookie = format!("{COOKIE}={token}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age={}", auth::SESSION_TTL.as_secs());
    (StatusCode::OK, [(header::SET_COOKIE, HeaderValue::from_str(&cookie).unwrap())], Json(json!({ "ok": true }))).into_response()
}

async fn logout(State(state): State<WebState>, headers: HeaderMap) -> Response {
    if let Some(token) = session_cookie(&headers) {
        state.sessions.remove(token);
    }
    let clear = format!("{COOKIE}=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0");
    (StatusCode::OK, [(header::SET_COOKIE, HeaderValue::from_str(&clear).unwrap())], Json(json!({ "ok": true }))).into_response()
}

async fn info(State(state): State<WebState>) -> Json<serde_json::Value> {
    let hs = state.client.handshake_result().await;
    Json(json!({
        "version": env!("CARGO_PKG_VERSION"),
        "codename": crate::CODENAME,
        "core_protocol": hs.map(|h| format!("{}.{}.{}", h.remote_decoded.0, h.remote_decoded.1, h.remote_decoded.2)),
    }))
}

// ── Config ───────────────────────────────────────────────────────────────────

async fn get_config(State(state): State<WebState>) -> Json<serde_json::Value> {
    let cfg = state.config.read().unwrap();
    Json(json!({ "port": cfg.port, "allowed_cidrs": cfg.allowed_cidrs }))
}

#[derive(Deserialize)]
struct UpdateConfigBody {
    allowed_cidrs: Vec<String>,
}

async fn update_config(State(state): State<WebState>, Json(body): Json<UpdateConfigBody>) -> Response {
    let bad = |m: String| (StatusCode::BAD_REQUEST, Json(json!({ "error": m }))).into_response();
    for cidr in &body.allowed_cidrs {
        if cidr.parse::<ipnetwork::IpNetwork>().is_err() {
            return bad(format!("无效的 CIDR: {cidr}"));
        }
    }
    if body.allowed_cidrs.is_empty() {
        return bad("允许列表不能为空，否则将无法再访问 WebUI".into());
    }
    let mut cfg = state.config.write().unwrap();
    let mut next = cfg.clone();
    next.allowed_cidrs = body.allowed_cidrs;
    if let Err(e) = next.save(&state.config_path) {
        return (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": format!("保存失败: {e}") }))).into_response();
    }
    *cfg = next;
    Json(json!({ "ok": true })).into_response()
}

// ── Controller API ───────────────────────────────────────────────────────────

fn error_response(e: ControllerError) -> Response {
    let status = match &e {
        ControllerError::ConnectionFailed { .. } | ControllerError::NotConnected { .. } | ControllerError::ConnectionLost => {
            StatusCode::SERVICE_UNAVAILABLE
        }
        ControllerError::VersionMismatch { .. } | ControllerError::Unsupported(_) => StatusCode::NOT_IMPLEMENTED,
        ControllerError::RequestTimeout { .. } => StatusCode::GATEWAY_TIMEOUT,
        // Core rejected the request: report Core's error as-is.
        ControllerError::CoreError { .. } => StatusCode::UNPROCESSABLE_ENTITY,
        _ => StatusCode::BAD_GATEWAY,
    };
    (status, Json(UiError::from(e))).into_response()
}

async fn view(State(state): State<WebState>) -> Response {
    Json(state.client.view().await).into_response()
}

async fn ipc(State(state): State<WebState>, body: axum::body::Bytes) -> Response {
    let request: IpcRequest = match serde_json::from_slice(&body) {
        Ok(r) => r,
        Err(e) => {
            let err = UiError { category: "request", code: "invalid_request".into(), message: format!("无效的请求: {e}"), detail: None };
            return (StatusCode::BAD_REQUEST, Json(err)).into_response();
        }
    };
    if matches!(request, IpcRequest::AuthDecision { .. }) {
        let err = UiError {
            category: "request",
            code: "unsupported".into(),
            message: "WebUI 不支持交互式授权，请在本机的控制器中处理访问请求。".into(),
            detail: None,
        };
        return (StatusCode::FORBIDDEN, Json(err)).into_response();
    }
    match state.client.command(request).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => error_response(e),
    }
}

async fn clear_rules(State(state): State<WebState>) -> Response {
    match crate::ops::clear_acl_rules(&state.client).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => error_response(e),
    }
}

async fn reconnect(State(state): State<WebState>) -> Response {
    Json(json!({ "outcome": state.client.retry_now().await })).into_response()
}

// ── Events ───────────────────────────────────────────────────────────────────

async fn ws_events(
    State(state): State<WebState>,
    headers: HeaderMap,
    ws: Result<WebSocketUpgrade, axum::extract::ws::rejection::WebSocketUpgradeRejection>,
) -> Response {
    // Reject cross-site WebSocket connections.
    if let Some(origin) = headers.get(header::ORIGIN).and_then(|o| o.to_str().ok()) {
        let host = headers.get(header::HOST).and_then(|h| h.to_str().ok()).unwrap_or("");
        let origin_host = origin.split("://").nth(1).unwrap_or("");
        if origin_host != host {
            return (StatusCode::FORBIDDEN, "cross-origin WebSocket rejected\n").into_response();
        }
    }
    match ws {
        Ok(ws) => ws.on_upgrade(move |socket| forward_notices(socket, state.client)),
        Err(rejection) => rejection.into_response(),
    }
}

async fn forward_notices(mut socket: WebSocket, client: IpcClient) {
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
    // Access-request notices are for interactive authorization, which the
    // WebUI does not handle.
    let notifier = spawn_notifier(&client, move |n| {
        if matches!(n, UiNotice::Changed) {
            let _ = tx.send(n);
        }
    });
    loop {
        tokio::select! {
            n = rx.recv() => {
                let Some(n) = n else { break };
                let text = serde_json::to_string(&n).unwrap_or_default();
                if socket.send(WsMsg::Text(text.into())).await.is_err() {
                    break;
                }
            }
            m = socket.recv() => match m {
                Some(Ok(WsMsg::Close(_))) | None | Some(Err(_)) => break,
                _ => {}
            }
        }
    }
    notifier.abort();
}

// ── Static frontend ──────────────────────────────────────────────────────────

#[derive(rust_embed::RustEmbed)]
#[folder = "compose/webApp/build/dist/wasmJs/productionExecutable"]
#[exclude = "*.map"] // source maps: debugging only
struct Frontend;

async fn static_handler(req: Request) -> Response {
    let path = req.uri().path().trim_start_matches('/');
    let (path, data) = match Frontend::get(path) {
        Some(f) if !path.is_empty() => (path.to_string(), f.data),
        _ => match Frontend::get("index.html") {
            Some(f) => ("index.html".to_string(), f.data),
            None => return (StatusCode::NOT_FOUND, "frontend not built\n").into_response(),
        },
    };
    let mime = mime_guess::from_path(&path).first_or_octet_stream().to_string();
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, mime)
        .body(Body::from(data.into_owned()))
        .unwrap()
}
