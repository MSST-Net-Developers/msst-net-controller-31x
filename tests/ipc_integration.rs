//! Integration tests for the full IPC flow.
//!
//! These tests exercise the complete path: state store event application,
//! snapshot management, mock transport request/response correlation,
//! and subscription delivery.

use std::time::Duration;

use msst_ipc_protocol::{IpcEvent, IpcMessage, IpcRequest, IpcResponse};
use msst_net_controller::ipc::mock;
use msst_net_controller::state::{StateChange, StateStore};

// ── Tests ────────────────────────────────────────────────────────────────────

#[tokio::test]
async fn full_flow_snapshot_then_events() {
    let store = StateStore::new();

    // 1. Apply a snapshot (as if received from GetSystemState)
    let snapshot = serde_json::json!({
        "health": {
            "status": "ok",
            "is_authenticated": true,
            "user_id": "usr-test",
            "device_id": "device-test"
        },
        "nodes": [
            {
                "device_id": "device-peer1",
                "ipv4": "172.22.1.2",
                "ipv6": "fd22::2",
                "hostname": "peer1.ezplay.msst.net",
                "display_name": "Peer One"
            }
        ],
        "room": {"room_id": null, "room_name": null, "relay": null, "peers": []},
        "acl_rules": [],
        "acl_groups": [],
        "pending_auth": []
    });
    store.apply_snapshot(&snapshot).await;

    let state = store.snapshot().await;
    assert_eq!(state.health.status, "ok");
    assert!(state.health.is_authenticated);
    assert_eq!(state.health.user_id.as_deref(), Some("usr-test"));
    assert_eq!(state.nodes.len(), 1);
    assert!(state.nodes.contains_key("device-peer1"));
    assert!(state.room.room_id.is_none());

    // 2. Apply incremental events (as if received from the event stream)
    store.apply_event(&IpcEvent::NodeAdded {
        device_id: "device-new".into(),
        ipv4: "172.22.1.3".into(),
        ipv6: "fd22::3".into(),
        hostname: Some("new.ezplay.msst.net".into()),
        display_name: Some("New Device".into()),
    }).await;

    store.apply_event(&IpcEvent::RoomStateChanged {
        room_id: Some("room-test".into()),
        room_name: Some("Test Room".into()),
        peers: vec![serde_json::json!({
            "device_id": "device-peer1",
            "hostname": "peer1.ezplay.msst.net",
            "display_name": "Peer One"
        })],
        relay: Some("relay.example.com:3478".into()),
    }).await;

    let state = store.snapshot().await;
    assert_eq!(state.nodes.len(), 2);
    assert!(state.nodes.contains_key("device-new"));
    assert_eq!(state.room.room_id.as_deref(), Some("room-test"));
    assert_eq!(state.room.peers.len(), 1);
    assert_eq!(state.room.relay.as_deref(), Some("relay.example.com:3478"));

    // 3. Verify health persisted through event application
    assert_eq!(state.health.status, "ok");
    assert!(state.health.is_authenticated);
}

#[tokio::test]
async fn state_store_auth_request_lifecycle() {
    let store = StateStore::new();

    // 1. Auth request arrives
    store.apply_event(&IpcEvent::AccessRequestCreated {
        request_id: "auth-001".into(),
        src_device_id: "device-alice".into(),
        src_user_id: Some("usr-alice".into()),
        src_device_display_name: Some("Alice's PC".into()),
        src_user_display_name: Some("Alice".into()),
        proto: "tcp".into(),
        dst_port: 25565,
        service_name: Some("minecraft-java".into()),
    }).await;

    let state = store.snapshot().await;
    assert_eq!(state.pending_auth.len(), 1);
    assert_eq!(state.pending_auth[0].request_id, "auth-001");
    assert_eq!(state.pending_auth[0].service_name.as_deref(), Some("minecraft-java"));

    // 2. Duplicate is ignored
    store.apply_event(&IpcEvent::AccessRequestCreated {
        request_id: "auth-001".into(),
        src_device_id: "device-alice".into(),
        src_user_id: None,
        src_device_display_name: None,
        src_user_display_name: None,
        proto: "tcp".into(),
        dst_port: 25565,
        service_name: None,
    }).await;

    let state = store.snapshot().await;
    assert_eq!(state.pending_auth.len(), 1);

    // 3. Second request arrives
    store.apply_event(&IpcEvent::AccessRequestCreated {
        request_id: "auth-002".into(),
        src_device_id: "device-bob".into(),
        src_user_id: Some("usr-bob".into()),
        src_device_display_name: None,
        src_user_display_name: Some("Bob".into()),
        proto: "udp".into(),
        dst_port: 19132,
        service_name: Some("minecraft-bedrock".into()),
    }).await;

    let state = store.snapshot().await;
    assert_eq!(state.pending_auth.len(), 2);

    // 4. First times out
    store.apply_event(&IpcEvent::AccessRequestTimeout {
        request_id: "auth-001".into(),
    }).await;

    let state = store.snapshot().await;
    assert_eq!(state.pending_auth.len(), 1);
    assert_eq!(state.pending_auth[0].request_id, "auth-002");

    // 5. Second times out
    store.apply_event(&IpcEvent::AccessRequestTimeout {
        request_id: "auth-002".into(),
    }).await;

    let state = store.snapshot().await;
    assert!(state.pending_auth.is_empty());
}

#[tokio::test]
async fn state_store_acl_changes() {
    let store = StateStore::new();

    // Start with rules and groups in the snapshot
    store.apply_snapshot(&serde_json::json!({
        "acl_rules": [
            {"id": "rule-1", "enabled": true, "action": "allow", "direction": "inbound",
             "principals": [], "protocol_ports": [], "comment": null, "expires_at": null}
        ],
        "acl_groups": [
            {"id": "group-1", "name": "Friends", "kind": "device", "member_ids": ["device-a"]}
        ]
    })).await;

    let state = store.snapshot().await;
    assert_eq!(state.acl_rules.len(), 1);
    assert_eq!(state.acl_groups.len(), 1);

    // Rule deleted via event
    store.apply_event(&IpcEvent::AclChanged {
        rule_id: "rule-1".into(),
        action: "deleted".into(),
    }).await;

    let state = store.snapshot().await;
    assert!(state.acl_rules.is_empty());

    // Group deleted via event
    store.apply_event(&IpcEvent::AclGroupChanged {
        group_id: "group-1".into(),
        action: "deleted".into(),
    }).await;

    let state = store.snapshot().await;
    assert!(state.acl_groups.is_empty());
}

#[tokio::test]
async fn state_store_node_lifecycle() {
    let store = StateStore::new();

    // Add nodes
    store.apply_event(&IpcEvent::NodeAdded {
        device_id: "device-1".into(),
        ipv4: "172.22.1.1".into(),
        ipv6: "fd22::1".into(),
        hostname: Some("node1.ezplay.msst.net".into()),
        display_name: Some("Node 1".into()),
    }).await;

    store.apply_event(&IpcEvent::NodeAdded {
        device_id: "device-2".into(),
        ipv4: "172.22.1.2".into(),
        ipv6: "fd22::2".into(),
        hostname: None,
        display_name: None,
    }).await;

    let state = store.snapshot().await;
    assert_eq!(state.nodes.len(), 2);

    // State change
    store.apply_event(&IpcEvent::NodeStateChanged {
        device_id: "device-2".into(),
        change: serde_json::json!({
            "display_name": "Updated Node 2",
            "hostname": "node2.ezplay.msst.net"
        }),
    }).await;

    let state = store.snapshot().await;
    let node2 = state.nodes.get("device-2").unwrap();
    assert_eq!(node2.display_name.as_deref(), Some("Updated Node 2"));
    assert_eq!(node2.hostname.as_deref(), Some("node2.ezplay.msst.net"));

    // Path change
    store.apply_event(&IpcEvent::PathChanged {
        device_id: "device-1".into(),
        old_mode: "relay".into(),
        new_mode: "direct".into(),
    }).await;

    let state = store.snapshot().await;
    let node1 = state.nodes.get("device-1").unwrap();
    assert_eq!(node1.path_mode.as_deref(), Some("direct"));

    // Remove one
    store.apply_event(&IpcEvent::NodeRemoved {
        device_id: "device-1".into(),
    }).await;

    let state = store.snapshot().await;
    assert_eq!(state.nodes.len(), 1);
    assert!(!state.nodes.contains_key("device-1"));
}

#[tokio::test]
async fn state_store_coordinator_connection() {
    let store = StateStore::new();

    // Initial snapshot with ws_connected = true
    store.apply_snapshot(&serde_json::json!({
        "health": {"status": "ok", "ws_connected": true}
    })).await;

    let state = store.snapshot().await;
    assert_eq!(state.health.ws_connected, Some(true));

    // Coordinator disconnects
    store.apply_event(&IpcEvent::CoordinatorConnectionChanged {
        connected: false,
    }).await;

    let state = store.snapshot().await;
    assert_eq!(state.health.ws_connected, Some(false));

    // Reconnects
    store.apply_event(&IpcEvent::CoordinatorConnectionChanged {
        connected: true,
    }).await;

    let state = store.snapshot().await;
    assert_eq!(state.health.ws_connected, Some(true));
}

#[tokio::test]
async fn subscription_delivers_all_changes() {
    let store = StateStore::new();
    let mut rx = store.subscribe();

    // Snapshot
    store.apply_snapshot(&serde_json::json!({
        "health": {"status": "ok"}
    })).await;

    let change: StateChange = tokio::time::timeout(Duration::from_millis(100), rx.recv())
        .await
        .unwrap()
        .unwrap();
    assert!(matches!(change, StateChange::SnapshotApplied));

    // Event
    store.apply_event(&IpcEvent::NodeAdded {
        device_id: "device-test".into(),
        ipv4: "172.22.1.1".into(),
        ipv6: "fd22::1".into(),
        hostname: None,
        display_name: None,
    }).await;

    let change: StateChange = tokio::time::timeout(Duration::from_millis(100), rx.recv())
        .await
        .unwrap()
        .unwrap();
    assert!(matches!(change, StateChange::EventApplied(IpcEvent::NodeAdded { .. })));
}

#[tokio::test]
async fn mock_transport_request_response_flow() {
    let (mut controller, mut core) = mock::mock_pair();

    // Controller sends a request
    let req = IpcMessage::Request {
        id: "req-flow-1".into(),
        body: IpcRequest::Health,
    };
    controller.send(&req).await.unwrap();

    // Core receives and responds
    let received: IpcMessage = core.recv().await.unwrap();
    match &received {
        IpcMessage::Request { id, body } => {
            assert_eq!(id, "req-flow-1");
            assert!(matches!(body, IpcRequest::Health));
        }
        _ => panic!("Expected Request"),
    }

    let resp = IpcMessage::Response {
        id: "req-flow-1".into(),
        body: IpcResponse::Ok(serde_json::json!({"status": "ok"})),
    };
    core.send(&resp).await.unwrap();

    // Controller receives the correlated response
    let received: IpcMessage = controller.recv().await.unwrap();
    match received {
        IpcMessage::Response { id, body } => {
            assert_eq!(id, "req-flow-1");
            match body {
                IpcResponse::Ok(val) => assert_eq!(val["status"], "ok"),
                _ => panic!("Expected Ok response"),
            }
        }
        _ => panic!("Expected Response"),
    }
}

#[tokio::test]
async fn mock_transport_multiple_events() {
    let (mut controller, mut core) = mock::mock_pair();

    // Core sends multiple events
    let events = vec![
        IpcEvent::NodeAdded {
            device_id: "device-1".into(),
            ipv4: "172.22.1.1".into(),
            ipv6: "fd22::1".into(),
            hostname: None,
            display_name: None,
        },
        IpcEvent::NodeAdded {
            device_id: "device-2".into(),
            ipv4: "172.22.1.2".into(),
            ipv6: "fd22::2".into(),
            hostname: None,
            display_name: None,
        },
        IpcEvent::CoordinatorConnectionChanged { connected: true },
    ];

    for event in &events {
        core.send(&IpcMessage::Event { body: event.clone() }).await.unwrap();
    }

    // Controller receives all events in order
    for _ in 0..3 {
        let msg: IpcMessage = controller.recv().await.unwrap();
        assert!(matches!(msg, IpcMessage::Event { .. }));
    }
}

#[tokio::test]
async fn snapshot_replaces_all_state_cleanly() {
    let store = StateStore::new();

    // Populate with initial data
    store.apply_event(&IpcEvent::NodeAdded {
        device_id: "old-node".into(),
        ipv4: "172.22.1.1".into(),
        ipv6: "fd22::1".into(),
        hostname: None,
        display_name: None,
    }).await;

    store.apply_event(&IpcEvent::AccessRequestCreated {
        request_id: "old-auth".into(),
        src_device_id: "device-x".into(),
        src_user_id: None,
        src_device_display_name: None,
        src_user_display_name: None,
        proto: "tcp".into(),
        dst_port: 80,
        service_name: None,
    }).await;

    let state = store.snapshot().await;
    assert_eq!(state.nodes.len(), 1);
    assert_eq!(state.pending_auth.len(), 1);

    // Apply a fresh snapshot — should replace everything
    store.apply_snapshot(&serde_json::json!({
        "health": {"status": "ok", "is_authenticated": true},
        "nodes": [
            {"device_id": "new-node", "ipv4": "172.22.2.1", "ipv6": "fd22::100"}
        ],
        "room": {"room_id": "room-new", "room_name": "New Room", "relay": null, "peers": []},
        "acl_rules": [],
        "acl_groups": [],
        "pending_auth": []
    })).await;

    let state = store.snapshot().await;
    assert!(!state.nodes.contains_key("old-node"));
    assert!(state.nodes.contains_key("new-node"));
    assert!(state.pending_auth.is_empty());
    assert_eq!(state.room.room_id.as_deref(), Some("room-new"));
    assert!(state.health.is_authenticated);
}