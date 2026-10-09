//! WebUI backend tests — verify HTTP routes and WebSocket handling.

#![cfg(feature = "webui")]

use axum::body::Body;
use axum::http::{Request, StatusCode};
use tower::ServiceExt; // for `oneshot`

use msst_net_controller::ipc::client::{IpcClient, IpcClientConfig};
use msst_net_controller::state::StateStore;
use msst_net_controller::webui::{build_router, WebState};

fn make_state() -> WebState {
    let state_store = StateStore::new();
    let ipc_client = IpcClient::new(IpcClientConfig::default(), state_store.clone());
    WebState { ipc_client, state_store }
}

#[tokio::test]
async fn get_state_returns_ok() {
    let state = make_state();
    let app = build_router(state);

    let resp = app
        .oneshot(
            Request::builder()
                .uri("/api/state")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(resp.status(), StatusCode::OK);
}

#[tokio::test]
async fn get_health_returns_ok() {
    let state = make_state();
    let app = build_router(state);

    let resp = app
        .oneshot(
            Request::builder()
                .uri("/api/health")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(resp.status(), StatusCode::OK);
}

#[tokio::test]
async fn get_nodes_returns_ok() {
    let state = make_state();
    let app = build_router(state);

    let resp = app
        .oneshot(
            Request::builder()
                .uri("/api/nodes")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(resp.status(), StatusCode::OK);
}

#[tokio::test]
async fn get_room_returns_ok() {
    let state = make_state();
    let app = build_router(state);

    let resp = app
        .oneshot(
            Request::builder()
                .uri("/api/room")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(resp.status(), StatusCode::OK);
}

#[tokio::test]
async fn get_acl_rules_returns_ok() {
    let state = make_state();
    let app = build_router(state);

    let resp = app
        .oneshot(
            Request::builder()
                .uri("/api/acl/rules")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(resp.status(), StatusCode::OK);
}

#[tokio::test]
async fn get_connection_returns_ok() {
    let state = make_state();
    let app = build_router(state);

    let resp = app
        .oneshot(
            Request::builder()
                .uri("/api/connection")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(resp.status(), StatusCode::OK);
}

#[tokio::test]
async fn get_diagnostics_returns_ok() {
    let state = make_state();
    let app = build_router(state);

    let resp = app
        .oneshot(
            Request::builder()
                .uri("/api/diagnostics")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(resp.status(), StatusCode::OK);
}

#[tokio::test]
async fn unknown_route_returns_404() {
    let state = make_state();
    let app = build_router(state);

    let resp = app
        .oneshot(
            Request::builder()
                .uri("/api/nonexistent")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(resp.status(), StatusCode::NOT_FOUND);
}