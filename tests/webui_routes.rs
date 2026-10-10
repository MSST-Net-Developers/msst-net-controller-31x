//! WebUI security model and API routes, against a FakeCore.

#![cfg(feature = "webui")]

use std::net::SocketAddr;
use std::sync::{Arc, RwLock};

use axum::body::Body;
use axum::extract::connect_info::MockConnectInfo;
use axum::http::{Request, StatusCode, header};
use msst_ipc_protocol::{IpcError, IpcRequest};
use msst_net_controller::ipc::client::{IpcClient, IpcClientConfig};
use msst_net_controller::ipc::mock::{FakeCore, default_responses};
use msst_net_controller::state::StateStore;
use msst_net_controller::webui::auth::{self, SessionStore};
use msst_net_controller::webui::config::Config;
use msst_net_controller::webui::{WebState, build_router};
use serde_json::{Value, json};
use tower::ServiceExt;

struct Env {
    state: WebState,
    _dir: std::path::PathBuf,
}

fn env(name: &str) -> Env {
    let dir = std::env::temp_dir().join(format!("msst-webui-test-{name}-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let passwd = dir.join("passwd");
    auth::write_passwd(&passwd, &auth::hash_password("secret123").unwrap()).unwrap();
    let core = FakeCore::new(|req| match req {
        IpcRequest::DeleteAclRule { .. } => Err(IpcError { code: "not_found".into(), message: "Rule 'x' not found".into() }),
        other => default_responses(other),
    });
    let client = IpcClient::with_connector(IpcClientConfig::default(), StateStore::new(), core.connector());
    let state = WebState {
        client,
        config: Arc::new(RwLock::new(Config { allowed_cidrs: vec!["127.0.0.0/8".into()], ..Config::default() })),
        config_path: Arc::new(dir.join("config.toml")),
        passwd_path: Arc::new(passwd),
        sessions: Arc::new(SessionStore::new()),
    };
    Env { state, _dir: dir }
}

async fn call(state: &WebState, from: [u8; 4], req: Request<Body>) -> (StatusCode, axum::http::HeaderMap, Value) {
    let app = build_router(state.clone()).layer(MockConnectInfo(SocketAddr::from((from, 50000))));
    let resp = app.oneshot(req).await.unwrap();
    let status = resp.status();
    let headers = resp.headers().clone();
    let bytes = axum::body::to_bytes(resp.into_body(), 1 << 20).await.unwrap();
    (status, headers, serde_json::from_slice(&bytes).unwrap_or(Value::Null))
}

fn post(uri: &str, body: Value, cookie: Option<&str>) -> Request<Body> {
    let mut b = Request::post(uri).header(header::CONTENT_TYPE, "application/json");
    if let Some(c) = cookie {
        b = b.header(header::COOKIE, c);
    }
    b.body(Body::from(body.to_string())).unwrap()
}

fn get(uri: &str, cookie: Option<&str>) -> Request<Body> {
    let mut b = Request::get(uri);
    if let Some(c) = cookie {
        b = b.header(header::COOKIE, c);
    }
    b.body(Body::empty()).unwrap()
}

async fn login(state: &WebState) -> String {
    let (status, headers, _) = call(state, [127, 0, 0, 1], post("/api/login", json!({ "password": "secret123" }), None)).await;
    assert_eq!(status, StatusCode::OK);
    let set = headers.get(header::SET_COOKIE).unwrap().to_str().unwrap().to_string();
    assert!(set.contains("HttpOnly") && set.contains("Secure"));
    set.split(';').next().unwrap().to_string()
}

#[tokio::test]
async fn ip_outside_allowlist_is_forbidden() {
    let e = env("ip");
    let (status, _, _) = call(&e.state, [8, 8, 8, 8], post("/api/login", json!({ "password": "secret123" }), None)).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn api_requires_session() {
    let e = env("session");
    let (status, _, _) = call(&e.state, [127, 0, 0, 1], get("/api/view", None)).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let (status, _, _) = call(&e.state, [127, 0, 0, 1], get("/api/view", Some("msst_session=forged"))).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn wrong_password_rejected() {
    let e = env("pw");
    let (status, headers, _) = call(&e.state, [127, 0, 0, 1], post("/api/login", json!({ "password": "nope" }), None)).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert!(headers.get(header::SET_COOKIE).is_none());
}

#[tokio::test]
async fn login_view_ipc_logout() {
    let e = env("flow");
    let cookie = login(&e.state).await;

    let (status, _, view) = call(&e.state, [127, 0, 0, 1], get("/api/view", Some(&cookie))).await;
    assert_eq!(status, StatusCode::OK);
    assert!(view.get("connection").is_some() && view.get("state").is_some());

    // Wait for the client to connect to the fake Core.
    for _ in 0..200 {
        if e.state.client.connection_status().await == msst_net_controller::state::ConnectionStatus::Connected {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    }
    let (status, _, v) = call(&e.state, [127, 0, 0, 1], post("/api/ipc", json!({ "type": "health" }), Some(&cookie))).await;
    assert_eq!(status, StatusCode::OK, "{v}");
    assert_eq!(v["status"], "ok");

    // Core's rejection is surfaced with its code and message.
    let body = json!({ "type": "delete_acl_rule", "params": { "rule_id": "x" } });
    let (status, _, v) = call(&e.state, [127, 0, 0, 1], post("/api/ipc", body, Some(&cookie))).await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(v["code"], "not_found");
    assert_eq!(v["message"], "Rule 'x' not found");

    let (status, _, v) = call(&e.state, [127, 0, 0, 1], post("/api/ipc", json!({ "type": "nope" }), Some(&cookie))).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(v["code"], "invalid_request");

    let (status, _, _) = call(&e.state, [127, 0, 0, 1], post("/api/logout", json!({}), Some(&cookie))).await;
    assert_eq!(status, StatusCode::OK);
    let (status, _, _) = call(&e.state, [127, 0, 0, 1], get("/api/view", Some(&cookie))).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    e.state.client.shutdown();
}

#[tokio::test]
async fn config_validation() {
    let e = env("config");
    let cookie = login(&e.state).await;
    let put = |body: Value| {
        Request::put("/api/config")
            .header(header::CONTENT_TYPE, "application/json")
            .header(header::COOKIE, cookie.clone())
            .body(Body::from(body.to_string()))
            .unwrap()
    };
    let (status, _, _) = call(&e.state, [127, 0, 0, 1], put(json!({ "allowed_cidrs": ["not-a-cidr"] }))).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    let (status, _, _) = call(&e.state, [127, 0, 0, 1], put(json!({ "allowed_cidrs": [] }))).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    let (status, _, _) = call(&e.state, [127, 0, 0, 1], put(json!({ "allowed_cidrs": ["127.0.0.0/8", "10.0.0.0/8"] }))).await;
    assert_eq!(status, StatusCode::OK);
    assert!(std::fs::read_to_string(&*e.state.config_path).unwrap().contains("10.0.0.0/8"));
}

#[tokio::test]
async fn cross_origin_websocket_rejected() {
    let e = env("ws");
    let cookie = login(&e.state).await;
    let req = Request::get("/ws/events")
        .header(header::COOKIE, cookie)
        .header(header::HOST, "192.168.1.2:18080")
        .header(header::ORIGIN, "https://evil.example")
        .header(header::CONNECTION, "upgrade")
        .header(header::UPGRADE, "websocket")
        .header("sec-websocket-version", "13")
        .header("sec-websocket-key", "dGhlIHNhbXBsZSBub25jZQ==")
        .body(Body::empty())
        .unwrap();
    let (status, _, _) = call(&e.state, [127, 0, 0, 1], req).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
}
