//! End-to-end tests: IpcClient ↔ FakeCore over the real wire protocol
//! (handshake + framing), with Core 3.1.x-shaped responses.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use msst_ipc_protocol::{AuthDecision, IpcError, IpcEvent, IpcRequest};
use msst_net_controller::error::ControllerError;
use msst_net_controller::ipc::client::{IpcClient, IpcClientConfig, RetryOutcome};
use msst_net_controller::ipc::mock::{FakeCore, default_responses};
use msst_net_controller::state::{ConnectionStatus, StateStore, SyncState};
use serde_json::json;

fn fast_config() -> IpcClientConfig {
    IpcClientConfig {
        reconnect_base_ms: 20,
        reconnect_max_ms: 100,
        incompatible_retry_ms: 50,
        request_timeout: Duration::from_secs(2),
        poll_interval: Duration::from_secs(60),
    }
}

async fn wait_for<F, Fut>(what: &str, mut cond: F)
where
    F: FnMut() -> Fut,
    Fut: std::future::Future<Output = bool>,
{
    for _ in 0..300 {
        if cond().await {
            return;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    panic!("timed out waiting for: {what}");
}

fn start(core: &FakeCore) -> IpcClient {
    IpcClient::with_connector(fast_config(), StateStore::new(), core.connector())
}

async fn synced(client: &IpcClient) {
    let c = client.clone();
    wait_for("sync", move || {
        let c = c.clone();
        async move { c.state_store().snapshot().await.sync == SyncState::Synced }
    })
    .await;
}

#[tokio::test]
async fn connect_syncs_every_resource_from_core() {
    let core = FakeCore::new(|req| match req {
        IpcRequest::Health => Ok(json!({ "status": "ok", "device_id": "device-aabbccdd", "is_authenticated": true, "ping_enabled": true })),
        IpcRequest::GetSystemState => Ok(json!({
            "room_id": "space-1", "room_name": "game", "relay": "1.2.3.4:3478",
            "peers": [{ "device_id": "device-00000001", "ipv4": "172.22.0.1", "ipv6": "fd22::1", "hostname": null, "display_name": "Bob" }],
            "device_id": "device-aabbccdd", "user_id": null, "joined_rooms": {}, "rule_count": 1, "generation": 3
        })),
        IpcRequest::GetNodes => Ok(json!({ "nodes": [{ "device_id": "device-00000001", "ipv4": "172.22.0.1", "ipv6": "fd22::1", "is_authenticated": false }] })),
        IpcRequest::GetPathView => Ok(json!({ "peers": [{ "device_id": "device-00000001", "chosen": "relay", "candidates": [] }] })),
        IpcRequest::GetAclRules => Ok(json!({ "rules": [{ "id": "r1", "action": "allow", "enabled": true }] })),
        IpcRequest::GetAclGroups => Ok(json!({ "groups": [{ "id": "g1", "name": "friends", "kind": "user", "member_ids": [] }] })),
        IpcRequest::GetPendingAuth => Ok(json!({ "requests": [] })),
        other => default_responses(other),
    });
    let client = start(&core);
    synced(&client).await;

    let view = client.view().await;
    assert_eq!(view.connection, ConnectionStatus::Connected);
    let s = view.state;
    assert_eq!(s.health.unwrap().device_id.as_deref(), Some("device-aabbccdd"));
    assert_eq!(s.room.room_id.as_deref(), Some("space-1"));
    assert_eq!(s.nodes["device-00000001"].path_mode.as_deref(), Some("relay"));
    assert_eq!(s.acl_rules.len(), 1);
    assert_eq!(s.acl_groups.len(), 1);
    client.shutdown();
}

#[tokio::test]
async fn log_events_during_sync_do_not_break_it() {
    let core = FakeCore::new(default_responses);
    // Core streams log lines from the moment the handshake completes.
    let pusher = core.clone();
    let spam = tokio::spawn(async move {
        loop {
            pusher.push_event(IpcEvent::Diagnostic { level: "info".into(), target: "t".into(), message: "x".into(), timestamp_ms: 0 });
            tokio::time::sleep(Duration::from_millis(1)).await;
        }
    });
    let client = start(&core);
    synced(&client).await;
    spam.abort();
    client.shutdown();
}

#[tokio::test]
async fn acl_event_triggers_requery() {
    let rules = Arc::new(Mutex::new(json!({ "rules": [] })));
    let r = rules.clone();
    let core = FakeCore::new(move |req| match req {
        IpcRequest::GetAclRules => Ok(r.lock().unwrap().clone()),
        other => default_responses(other),
    });
    let client = start(&core);
    synced(&client).await;

    *rules.lock().unwrap() = json!({ "rules": [{ "id": "new-rule", "enabled": true }] });
    core.push_event(IpcEvent::AclChanged { rule_id: "new-rule".into(), action: "created".into() });

    let c = client.clone();
    wait_for("rule appears", move || {
        let c = c.clone();
        async move { c.state_store().snapshot().await.acl_rules.iter().any(|r| r.id == "new-rule") }
    })
    .await;
    client.shutdown();
}

#[tokio::test]
async fn core_restart_resyncs() {
    let room = Arc::new(Mutex::new(json!({ "room_id": null, "peers": [] })));
    let r = room.clone();
    let core = FakeCore::new(move |req| match req {
        IpcRequest::GetSystemState => Ok(r.lock().unwrap().clone()),
        other => default_responses(other),
    });
    let client = start(&core);
    synced(&client).await;

    // Core restarts with different state; no events were seen for it.
    *room.lock().unwrap() = json!({ "room_id": "space-9", "room_name": "after", "peers": [] });
    core.drop_connections();

    let c = client.clone();
    wait_for("resync after restart", move || {
        let c = c.clone();
        async move {
            let s = c.state_store().snapshot().await;
            s.sync == SyncState::Synced && s.room.room_id.as_deref() == Some("space-9")
        }
    })
    .await;
    assert!(client.connection_diagnostics().await.disconnections >= 1);
    client.shutdown();
}

#[tokio::test]
async fn commands_are_not_replayed_after_reconnect() {
    let core = FakeCore::new(default_responses);
    let client = start(&core);
    synced(&client).await;

    core.set_running(false);
    let c = client.clone();
    wait_for("disconnected", move || {
        let c = c.clone();
        async move { c.connection_status().await != ConnectionStatus::Connected }
    })
    .await;

    let r = client.command(IpcRequest::DeleteAclRule { rule_id: "r1".into() }).await;
    assert!(matches!(r, Err(ControllerError::NotConnected { .. })), "got {r:?}");

    core.clear_requests();
    core.set_running(true);
    synced(&client).await;
    assert!(
        !core.requests().iter().any(|q| matches!(q, IpcRequest::DeleteAclRule { .. })),
        "a command issued while disconnected must never reach Core later"
    );
    client.shutdown();
}

#[tokio::test]
async fn incompatible_core_is_reported() {
    let core = FakeCore::new(default_responses);
    core.set_version(msst_ipc_protocol::version::make_version(9, 0, 0));
    let client = start(&core);
    let c = client.clone();
    wait_for("incompatible", move || {
        let c = c.clone();
        async move { c.connection_status().await == ConnectionStatus::Incompatible }
    })
    .await;
    let diag = client.connection_diagnostics().await;
    assert!(diag.last_error.unwrap().contains("9.0.0"));

    // Core upgraded → the client recovers without a restart.
    core.set_version(msst_ipc_protocol::PROTOCOL_VERSION);
    synced(&client).await;
    client.shutdown();
}

#[tokio::test]
async fn auth_decision_requeries_pending_requests() {
    let pending = Arc::new(Mutex::new(json!({ "requests": [] })));
    let p = pending.clone();
    let core = FakeCore::new(move |req| match req {
        IpcRequest::GetPendingAuth => Ok(p.lock().unwrap().clone()),
        IpcRequest::AuthDecision { request_id, .. } if request_id == "gone" => {
            Err(IpcError { code: "not_found".into(), message: "Auth request 'gone' not found or already resolved".into() })
        }
        IpcRequest::AuthDecision { .. } => {
            *p.lock().unwrap() = json!({ "requests": [] });
            Ok(json!({ "ok": true }))
        }
        other => default_responses(other),
    });
    let client = start(&core);
    synced(&client).await;

    *pending.lock().unwrap() = json!({ "requests": [{
        "request_id": "a1", "src_device_id": "device-00000001", "proto": "tcp", "dst_port": 25565, "expires_in_secs": 30
    }]});
    core.push_event(IpcEvent::AccessRequestCreated {
        request_id: "a1".into(),
        src_device_id: "device-00000001".into(),
        src_user_id: None,
        src_device_display_name: None,
        src_user_display_name: None,
        proto: "tcp".into(),
        dst_port: 25565,
        service_name: None,
    });
    let c = client.clone();
    wait_for("deadline from Core", move || {
        let c = c.clone();
        async move { c.state_store().snapshot().await.pending_auth.first().is_some_and(|r| r.expires_at_ms.is_some()) }
    })
    .await;

    // Already-resolved request: Core's error is surfaced, not success.
    let r = client.command(IpcRequest::AuthDecision { request_id: "gone".into(), decision: AuthDecision::DenyOnce }).await;
    assert!(matches!(r, Err(ControllerError::CoreError { ref code, .. }) if code == "not_found"));

    client.command(IpcRequest::AuthDecision { request_id: "a1".into(), decision: AuthDecision::AllowOnce }).await.unwrap();
    let c = client.clone();
    wait_for("pending cleared", move || {
        let c = c.clone();
        async move { c.state_store().snapshot().await.pending_auth.is_empty() }
    })
    .await;
    client.shutdown();
}

#[tokio::test]
async fn get_services_rejected_for_old_core() {
    let core = FakeCore::new(default_responses);
    core.set_version(msst_ipc_protocol::version::make_version(0, 1, 0));
    let client = start(&core);
    synced(&client).await;
    let r = client.query(IpcRequest::GetServices).await;
    assert!(matches!(r, Err(ControllerError::Unsupported(_))), "got {r:?}");
    client.shutdown();
}

/// Controller started before Core (real Unix socket, default backoff): once
/// Core starts listening, the Controller connects within a few seconds — it
/// must not need a restart or sit out a long backoff (the old 30 s cap left
/// users waiting long enough to assume it never reconnects).
#[cfg(unix)]
#[tokio::test(flavor = "multi_thread")]
async fn core_started_after_controller_is_picked_up_promptly() {
    use msst_net_controller::ipc::client::Connector;
    use msst_net_controller::ipc::transport;

    let dir = std::env::temp_dir().join(format!("msst-late-core-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let sock = dir.join("ipc.sock").to_string_lossy().into_owned();
    let path = sock.clone();
    let connector: Connector = Arc::new(move || {
        let path = path.clone();
        Box::pin(async move { transport::connect_first(&[path]).await })
    });
    let client = IpcClient::with_connector(IpcClientConfig::default(), StateStore::new(), connector);

    // Core starts 8 s later: an uncapped 1/2/4/8 s backoff would only retry
    // at 15 s; the capped one retries every 3 s by then.
    tokio::time::sleep(Duration::from_secs(8)).await;
    assert!(matches!(client.connection_status().await, ConnectionStatus::Reconnecting { .. }));

    std::fs::create_dir_all(&dir).unwrap();
    let listener = tokio::net::UnixListener::bind(&sock).unwrap();
    let core = FakeCore::new(default_responses);
    let c = core.clone();
    let accept = tokio::spawn(async move {
        while let Ok((stream, _)) = listener.accept().await {
            c.serve_stream(stream);
        }
    });

    let started = std::time::Instant::now();
    while client.connection_status().await != ConnectionStatus::Connected {
        assert!(started.elapsed() < Duration::from_millis(4500), "not connected {:?} after Core started", started.elapsed());
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    synced(&client).await;

    client.shutdown();
    accept.abort();
    let _ = std::fs::remove_dir_all(&dir);
}

/// The status-bar retry: while Core is down it cuts the backoff wait short;
/// while connected it keeps the session and re-queries every resource.
#[tokio::test]
async fn retry_now_skips_backoff_or_resyncs() {
    let core = FakeCore::new(default_responses);
    core.set_running(false);
    // A backoff far longer than the test waits.
    let cfg = IpcClientConfig { reconnect_base_ms: 60_000, reconnect_max_ms: 60_000, ..fast_config() };
    let client = IpcClient::with_connector(cfg, StateStore::new(), core.connector());
    let c = client.clone();
    wait_for("first attempt fails", move || {
        let c = c.clone();
        async move { matches!(c.connection_status().await, ConnectionStatus::Reconnecting { .. }) }
    })
    .await;

    core.set_running(true);
    assert_eq!(client.retry_now().await, RetryOutcome::Reconnecting);
    synced(&client).await;

    core.clear_requests();
    assert_eq!(client.retry_now().await, RetryOutcome::Resyncing);
    let k = core.clone();
    wait_for("every resource re-queried", move || {
        let k = k.clone();
        async move {
            let r = k.requests();
            r.iter().any(|q| matches!(q, IpcRequest::GetAclRules)) && r.iter().any(|q| matches!(q, IpcRequest::Health))
        }
    })
    .await;
    synced(&client).await;
    assert_eq!(client.connection_diagnostics().await.disconnections, 0, "a connected retry must not drop the session");
    client.shutdown();
}
