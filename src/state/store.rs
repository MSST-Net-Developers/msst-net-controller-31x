//! State store — applies IPC events and exposes read snapshots.
//!
//! The [`StateStore`] is the single source of truth for the Controller's view
//! of Core state.  It is updated **only** by:
//!
//! 1. Applying a full snapshot from `GetSystemState`
//! 2. Applying individual [`IpcEvent`]s from the event stream
//!
//! The UI layer reads state through [`StateStore::snapshot`] or
//! [`StateStore::subscribe`] and never mutates it directly.

use std::sync::Arc;

use msst_ipc_protocol::IpcEvent;
use tokio::sync::{broadcast, RwLock};

use super::types::*;

// ── StateStore ───────────────────────────────────────────────────────────────

/// Thread-safe state store with event subscription.
#[derive(Clone)]
pub struct StateStore {
    inner: Arc<RwLock<SystemState>>,
    event_tx: broadcast::Sender<StateChange>,
}

/// Describes a state change for subscribers.
#[derive(Debug, Clone)]
pub enum StateChange {
    /// Full state replaced (from snapshot).
    SnapshotApplied,
    /// A single event was applied.
    EventApplied(IpcEvent),
}

impl StateStore {
    /// Create a new empty state store.
    pub fn new() -> Self {
        let (event_tx, _) = broadcast::channel(256);
        Self {
            inner: Arc::new(RwLock::new(SystemState::default())),
            event_tx,
        }
    }

    /// Apply a full system state snapshot (from `GetSystemState` response).
    ///
    /// Replaces all local state with the snapshot data.
    pub async fn apply_snapshot(&self, data: &serde_json::Value) {
        let mut state = self.inner.write().await;

        // Health
        if let Some(Ok(health)) = data.get("health").map(|h| serde_json::from_value(h.clone())) {
            state.health = health;
        }

        // Nodes
        if let Some(nodes) = data.get("nodes").and_then(|v| v.as_array()) {
            state.nodes.clear();
            for node in nodes {
                if let Ok(info) = serde_json::from_value::<NodeInfo>(node.clone()) {
                    state.nodes.insert(info.device_id.clone(), info);
                }
            }
        }

        // Room
        if let Some(Ok(r)) = data.get("room").map(|v| serde_json::from_value(v.clone())) {
            state.room = r;
        }

        // ACL rules
        if let Some(rules) = data.get("acl_rules").and_then(|v| v.as_array()) {
            state.acl_rules = rules
                .iter()
                .filter_map(|r| serde_json::from_value(r.clone()).ok())
                .collect();
        }

        // ACL groups
        if let Some(groups) = data.get("acl_groups").and_then(|v| v.as_array()) {
            state.acl_groups = groups
                .iter()
                .filter_map(|g| serde_json::from_value(g.clone()).ok())
                .collect();
        }

        // Pending auth
        if let Some(auth) = data.get("pending_auth").and_then(|v| v.as_array()) {
            state.pending_auth = auth
                .iter()
                .filter_map(|a| serde_json::from_value(a.clone()).ok())
                .collect();
        }

        drop(state);
        let _ = self.event_tx.send(StateChange::SnapshotApplied);
    }

    /// Apply a single IPC event to the state.
    pub async fn apply_event(&self, event: &IpcEvent) {
        let mut state = self.inner.write().await;

        match event {
            // ── Auth events ──────────────────────────────────────────────────
            IpcEvent::AccessRequestCreated {
                request_id,
                src_device_id,
                src_user_id,
                src_device_display_name,
                src_user_display_name,
                proto,
                dst_port,
                service_name,
            } => {
                // Deduplicate by request_id
                if !state.pending_auth.iter().any(|r| r.request_id == *request_id) {
                    state.pending_auth.push(PendingAuthRequest {
                        request_id: request_id.clone(),
                        src_device_id: src_device_id.clone(),
                        src_user_id: src_user_id.clone(),
                        src_device_display_name: src_device_display_name.clone(),
                        src_user_display_name: src_user_display_name.clone(),
                        proto: proto.clone(),
                        dst_port: *dst_port,
                        service_name: service_name.clone(),
                        expires_at: None,
                    });
                }
            }

            IpcEvent::AccessRequestTimeout { request_id } => {
                state.pending_auth.retain(|r| r.request_id != *request_id);
            }

            IpcEvent::AccessAuthRequired { .. } => {
                // Mark as not authenticated — the UI layer handles prompting.
                state.health.is_authenticated = false;
            }

            // ── Node lifecycle ───────────────────────────────────────────────
            IpcEvent::NodeAdded {
                device_id,
                ipv4,
                ipv6,
                hostname,
                display_name,
            } => {
                state.nodes.insert(
                    device_id.clone(),
                    NodeInfo {
                        device_id: device_id.clone(),
                        ipv4: ipv4.clone(),
                        ipv6: ipv6.clone(),
                        hostname: hostname.clone(),
                        display_name: display_name.clone(),
                        path_mode: None,
                    },
                );
            }

            IpcEvent::NodeRemoved { device_id } => {
                state.nodes.remove(device_id);
            }

            IpcEvent::NodeStateChanged { device_id, change } => {
                if let Some(node) = state.nodes.get_mut(device_id) {
                    // Apply partial changes
                    if let Some(dn) = change.get("display_name").and_then(|v| v.as_str()) {
                        node.display_name = Some(dn.to_string());
                    }
                    if let Some(hn) = change.get("hostname").and_then(|v| v.as_str()) {
                        node.hostname = Some(hn.to_string());
                    }
                    if let Some(pm) = change.get("path_mode").and_then(|v| v.as_str()) {
                        node.path_mode = Some(pm.to_string());
                    }
                }
            }

            // ── Path events ──────────────────────────────────────────────────
            IpcEvent::PathChanged {
                device_id,
                new_mode,
                ..
            } => {
                if let Some(node) = state.nodes.get_mut(device_id) {
                    node.path_mode = Some(new_mode.clone());
                }
            }

            // ── ACL events ───────────────────────────────────────────────────
            IpcEvent::AclChanged { rule_id, action } => {
                match action.as_str() {
                    "deleted" => {
                        state.acl_rules.retain(|r| r.id != *rule_id);
                    }
                    "created" | "updated" => {
                        // The event doesn't carry the full rule.
                        // A snapshot refresh or explicit GetAclRules is needed.
                        // For now, mark it as stale — the client layer should
                        // issue a follow-up GetAclRules query.
                    }
                    _ => {}
                }
            }

            IpcEvent::AclGroupChanged { group_id, action } => {
                match action.as_str() {
                    "deleted" => {
                        state.acl_groups.retain(|g| g.id != *group_id);
                    }
                    "created" | "updated" => {
                        // Same as AclChanged — need follow-up query.
                    }
                    _ => {}
                }
            }

            // ── Room events ──────────────────────────────────────────────────
            IpcEvent::RoomStateChanged {
                room_id,
                room_name,
                peers,
                relay,
            } => {
                state.room = RoomState {
                    room_id: room_id.clone(),
                    room_name: room_name.clone(),
                    relay: relay.clone(),
                    peers: peers
                        .iter()
                        .filter_map(|p| serde_json::from_value(p.clone()).ok())
                        .collect(),
                };
            }

            // ── Coordinator connection ───────────────────────────────────────
            IpcEvent::CoordinatorConnectionChanged { connected } => {
                state.health.ws_connected = Some(*connected);
            }

            // ── Diagnostic (log) — no state change ──────────────────────────
            IpcEvent::Diagnostic { .. } => {}
        }

        drop(state);
        let _ = self.event_tx.send(StateChange::EventApplied(event.clone()));
    }

    /// Get a snapshot of the current state.
    pub async fn snapshot(&self) -> SystemState {
        self.inner.read().await.clone()
    }

    /// Subscribe to state change notifications.
    pub fn subscribe(&self) -> broadcast::Receiver<StateChange> {
        self.event_tx.subscribe()
    }
}

impl Default for StateStore {
    fn default() -> Self {
        Self::new()
    }
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use msst_ipc_protocol::IpcEvent;

    #[tokio::test]
    async fn apply_event_node_added() {
        let store = StateStore::new();
        store
            .apply_event(&IpcEvent::NodeAdded {
                device_id: "device-deadbeef".into(),
                ipv4: "172.22.1.2".into(),
                ipv6: "fd22::1".into(),
                hostname: Some("test.ezplay.msst.net".into()),
                display_name: Some("Test PC".into()),
            })
            .await;

        let state = store.snapshot().await;
        assert_eq!(state.nodes.len(), 1);
        let node = state.nodes.get("device-deadbeef").unwrap();
        assert_eq!(node.ipv4, "172.22.1.2");
        assert_eq!(node.hostname.as_deref().unwrap(), "test.ezplay.msst.net");
    }

    #[tokio::test]
    async fn apply_event_node_removed() {
        let store = StateStore::new();
        store
            .apply_event(&IpcEvent::NodeAdded {
                device_id: "device-deadbeef".into(),
                ipv4: "172.22.1.2".into(),
                ipv6: "fd22::1".into(),
                hostname: None,
                display_name: None,
            })
            .await;
        store
            .apply_event(&IpcEvent::NodeRemoved {
                device_id: "device-deadbeef".into(),
            })
            .await;

        let state = store.snapshot().await;
        assert!(state.nodes.is_empty());
    }

    #[tokio::test]
    async fn apply_event_access_request() {
        let store = StateStore::new();
        store
            .apply_event(&IpcEvent::AccessRequestCreated {
                request_id: "req-001".into(),
                src_device_id: "device-aabb".into(),
                src_user_id: Some("usr-1122".into()),
                src_device_display_name: Some("Alice PC".into()),
                src_user_display_name: Some("Alice".into()),
                proto: "tcp".into(),
                dst_port: 25565,
                service_name: Some("minecraft-java".into()),
            })
            .await;

        let state = store.snapshot().await;
        assert_eq!(state.pending_auth.len(), 1);
        assert_eq!(state.pending_auth[0].request_id, "req-001");
    }

    #[tokio::test]
    async fn apply_event_access_request_dedup() {
        let store = StateStore::new();
        let event = IpcEvent::AccessRequestCreated {
            request_id: "req-001".into(),
            src_device_id: "device-aabb".into(),
            src_user_id: None,
            src_device_display_name: None,
            src_user_display_name: None,
            proto: "tcp".into(),
            dst_port: 25565,
            service_name: None,
        };
        store.apply_event(&event).await;
        store.apply_event(&event).await; // duplicate

        let state = store.snapshot().await;
        assert_eq!(state.pending_auth.len(), 1);
    }

    #[tokio::test]
    async fn apply_event_access_request_timeout() {
        let store = StateStore::new();
        store
            .apply_event(&IpcEvent::AccessRequestCreated {
                request_id: "req-001".into(),
                src_device_id: "device-aabb".into(),
                src_user_id: None,
                src_device_display_name: None,
                src_user_display_name: None,
                proto: "tcp".into(),
                dst_port: 25565,
                service_name: None,
            })
            .await;
        store
            .apply_event(&IpcEvent::AccessRequestTimeout {
                request_id: "req-001".into(),
            })
            .await;

        let state = store.snapshot().await;
        assert!(state.pending_auth.is_empty());
    }

    #[tokio::test]
    async fn apply_event_room_state_changed() {
        let store = StateStore::new();
        store
            .apply_event(&IpcEvent::RoomStateChanged {
                room_id: Some("room-abc".into()),
                room_name: Some("Test Room".into()),
                peers: vec![serde_json::json!({
                    "device_id": "device-deadbeef",
                    "hostname": "test.ezplay.msst.net",
                    "display_name": "Test PC"
                })],
                relay: Some("relay.example.com:3478".into()),
            })
            .await;

        let state = store.snapshot().await;
        assert_eq!(state.room.room_id.as_deref().unwrap(), "room-abc");
        assert_eq!(state.room.peers.len(), 1);
    }

    #[tokio::test]
    async fn apply_snapshot_replaces_state() {
        let store = StateStore::new();
        store
            .apply_event(&IpcEvent::NodeAdded {
                device_id: "old-node".into(),
                ipv4: "172.22.1.1".into(),
                ipv6: "fd22::1".into(),
                hostname: None,
                display_name: None,
            })
            .await;

        let snapshot = serde_json::json!({
            "health": {"status": "ok", "is_authenticated": true},
            "nodes": [
                {"device_id": "new-node", "ipv4": "172.22.1.2", "ipv6": "fd22::2"}
            ]
        });
        store.apply_snapshot(&snapshot).await;

        let state = store.snapshot().await;
        assert!(!state.nodes.contains_key("old-node"));
        assert!(state.nodes.contains_key("new-node"));
        assert_eq!(state.health.status, "ok");
    }

    #[tokio::test]
    async fn subscribe_receives_events() {
        let store = StateStore::new();
        let mut rx = store.subscribe();

        store
            .apply_event(&IpcEvent::NodeAdded {
                device_id: "device-test".into(),
                ipv4: "172.22.1.1".into(),
                ipv6: "fd22::1".into(),
                hostname: None,
                display_name: None,
            })
            .await;

        let change = rx.recv().await.unwrap();
        assert!(matches!(change, StateChange::EventApplied(IpcEvent::NodeAdded { .. })));
    }
}