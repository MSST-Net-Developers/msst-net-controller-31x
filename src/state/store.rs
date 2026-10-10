//! State store — the Controller's view of Core state.
//!
//! The [`StateStore`] is updated **only** by:
//!
//! 1. Query results from Core ([`StateStore::apply_resource`]), one
//!    [`Resource`] at a time — Core has no single combined snapshot request,
//!    so the IPC client rebuilds the view by querying every resource after
//!    each (re)connect.
//! 2. Push events ([`StateStore::apply_event`]).
//!
//! ## Stale query results
//!
//! Events and query results can interleave: an event may arrive while a
//! query for the same resource is in flight, and the (older) query result
//! would then overwrite the newer event.  Every resource therefore has an
//! epoch that is bumped by each event touching it; a query result is only
//! applied if the epoch is unchanged since the query was sent
//! ([`StateStore::epoch`] → query → [`StateStore::apply_resource`]).
//! A rejected result is re-queried by the caller.
//!
//! The UI layer reads state through [`StateStore::snapshot`] and listens to
//! [`StateStore::subscribe`]; it never mutates the store.

use std::collections::{BTreeMap, HashMap};
use std::sync::Arc;

use msst_ipc_protocol::IpcEvent;
use serde_json::Value;
use tokio::sync::{RwLock, broadcast};

use super::types::*;
use crate::error::{ControllerError, Result};

// ── Change notifications ─────────────────────────────────────────────────────

/// Describes a state change for subscribers.
#[derive(Debug, Clone)]
pub enum StateChange {
    /// A resource was replaced by a fresh query result.
    ResourceRefreshed(Resource),
    /// A push event was applied.
    EventApplied(IpcEvent),
    /// The sync state changed.
    SyncChanged(SyncState),
}

// ── Parsed query results ─────────────────────────────────────────────────────

/// A freshly queried resource, parsed from Core's response payload(s).
#[derive(Debug, Clone)]
pub enum ResourceData {
    Health(Box<HealthState>),
    Room(RoomState),
    /// `GetNodes` plus the `chosen` path of each peer from `GetPathView`
    /// (absent if the path view could not be queried).
    Nodes { nodes: Vec<NodeInfo>, paths: Option<HashMap<String, String>> },
    AclRules(Vec<AclRule>),
    AclGroups(Vec<AclGroup>),
    PendingAuth(Vec<PendingAuthRequest>),
}

impl ResourceData {
    pub fn resource(&self) -> Resource {
        match self {
            Self::Health(_) => Resource::Health,
            Self::Room(_) => Resource::Room,
            Self::Nodes { .. } => Resource::Nodes,
            Self::AclRules(_) => Resource::AclRules,
            Self::AclGroups(_) => Resource::AclGroups,
            Self::PendingAuth(_) => Resource::PendingAuth,
        }
    }

    /// Parse Core's `Health` response.
    pub fn health(v: &Value) -> Result<Self> {
        Ok(Self::Health(Box::new(decode("Health", v.clone())?)))
    }

    /// Parse Core's `GetSystemState` response (room view).
    pub fn room(v: &Value) -> Result<Self> {
        Ok(Self::Room(decode("GetSystemState", v.clone())?))
    }

    /// Parse Core's `GetNodes` response and (optionally) `GetPathView`.
    pub fn nodes(nodes: &Value, path_view: Option<&Value>) -> Result<Self> {
        let nodes: Vec<NodeInfo> = decode("GetNodes", list(nodes, "nodes", "GetNodes")?)?;
        let paths = path_view.map(|pv| {
            pv.get("peers")
                .and_then(Value::as_array)
                .map(|peers| {
                    peers
                        .iter()
                        .filter_map(|p| Some((p.get("device_id")?.as_str()?.to_string(), p.get("chosen")?.as_str()?.to_string())))
                        .collect()
                })
                .unwrap_or_default()
        });
        Ok(Self::Nodes { nodes, paths })
    }

    /// Parse Core's `GetAclRules` response.
    pub fn acl_rules(v: &Value) -> Result<Self> {
        Ok(Self::AclRules(decode("GetAclRules", list(v, "rules", "GetAclRules")?)?))
    }

    /// Parse Core's `GetAclGroups` response.
    pub fn acl_groups(v: &Value) -> Result<Self> {
        Ok(Self::AclGroups(decode("GetAclGroups", list(v, "groups", "GetAclGroups")?)?))
    }

    /// Parse Core's `GetPendingAuth` response; `now_ms` anchors the
    /// relative `expires_in_secs` to a local deadline.
    pub fn pending_auth(v: &Value, now_ms: u64) -> Result<Self> {
        let mut reqs: Vec<PendingAuthRequest> = decode("GetPendingAuth", list(v, "requests", "GetPendingAuth")?)?;
        for r in &mut reqs {
            r.expires_at_ms = r.expires_in_secs.map(|s| now_ms + (s.max(0) as u64) * 1000);
        }
        Ok(Self::PendingAuth(reqs))
    }
}

fn list(v: &Value, key: &str, what: &str) -> Result<Value> {
    v.get(key)
        .filter(|x| x.is_array())
        .cloned()
        .ok_or_else(|| ControllerError::Codec(format!("{what}: response has no `{key}` array")))
}

fn decode<T: serde::de::DeserializeOwned>(what: &str, v: Value) -> Result<T> {
    serde_json::from_value(v).map_err(|e| ControllerError::Codec(format!("{what}: unexpected response: {e}")))
}

// ── StateStore ───────────────────────────────────────────────────────────────

struct Inner {
    state: SystemState,
    epochs: HashMap<Resource, u64>,
    /// Core speaks protocol >= 0.3: `RoomStateChanged` / `NodeStateChanged`
    /// carry full state and can be applied directly.
    full_events: bool,
}

/// Thread-safe state store with change subscription.
#[derive(Clone)]
pub struct StateStore {
    inner: Arc<RwLock<Inner>>,
    change_tx: broadcast::Sender<StateChange>,
}

impl StateStore {
    /// Create a new empty state store.
    pub fn new() -> Self {
        let (change_tx, _) = broadcast::channel(256);
        Self {
            inner: Arc::new(RwLock::new(Inner { state: SystemState::default(), epochs: HashMap::new(), full_events: false })),
            change_tx,
        }
    }

    /// Record the connected Core's protocol minor version (after each
    /// handshake); decides how partial events are handled.
    pub async fn set_protocol_minor(&self, minor: u32) {
        self.inner.write().await.full_events = minor >= 3;
    }

    /// The current epoch of `resource`; pass it to [`Self::apply_resource`].
    pub async fn epoch(&self, resource: Resource) -> u64 {
        self.inner.read().await.epochs.get(&resource).copied().unwrap_or(0)
    }

    /// Replace a resource with a query result, unless an event touched the
    /// resource after `epoch` was read.  Returns `false` if the result was
    /// discarded as stale (the caller should query again).
    pub async fn apply_resource(&self, epoch: u64, data: ResourceData) -> bool {
        let resource = data.resource();
        let mut inner = self.inner.write().await;
        if inner.epochs.get(&resource).copied().unwrap_or(0) != epoch {
            return false;
        }
        let st = &mut inner.state;
        match data {
            ResourceData::Health(h) => {
                st.access_auth_required = match h.access_auth_required {
                    Some(flag) => flag, // protocol 0.3: authoritative
                    None => st.access_auth_required && !h.is_authenticated,
                };
                st.health = Some(*h);
            }
            ResourceData::Room(r) => st.room = r,
            ResourceData::Nodes { nodes, paths } => {
                let old = std::mem::take(&mut st.nodes);
                st.nodes = nodes
                    .into_iter()
                    .map(|mut n| {
                        n.path_mode = match &paths {
                            Some(p) => p.get(&n.device_id).cloned(),
                            // Path view unavailable: keep the last reported mode.
                            None => old.get(&n.device_id).and_then(|o| o.path_mode.clone()),
                        };
                        (n.device_id.clone(), n)
                    })
                    .collect::<BTreeMap<_, _>>();
            }
            ResourceData::AclRules(r) => st.acl_rules = r,
            ResourceData::AclGroups(g) => st.acl_groups = g,
            ResourceData::PendingAuth(p) => st.pending_auth = p,
        }
        drop(inner);
        let _ = self.change_tx.send(StateChange::ResourceRefreshed(resource));
        true
    }

    /// Set the sync state.
    pub async fn set_sync(&self, sync: SyncState) {
        let mut inner = self.inner.write().await;
        if inner.state.sync == sync {
            return;
        }
        inner.state.sync = sync.clone();
        drop(inner);
        let _ = self.change_tx.send(StateChange::SyncChanged(sync));
    }

    /// Apply a push event.  Returns the resources that must be re-queried
    /// because the event does not carry the full new state.
    pub async fn apply_event(&self, event: &IpcEvent) -> Vec<Resource> {
        // Log lines are not state; they are fetched on demand (GetLogs).
        if matches!(event, IpcEvent::Diagnostic { .. }) {
            return Vec::new();
        }

        let mut inner = self.inner.write().await;
        let full = inner.full_events;
        let (touched, refresh) = apply_event_to(&mut inner.state, event, full);
        for r in touched {
            *inner.epochs.entry(r).or_insert(0) += 1;
        }
        drop(inner);
        let _ = self.change_tx.send(StateChange::EventApplied(event.clone()));
        refresh
    }

    /// Get a copy of the current state.
    pub async fn snapshot(&self) -> SystemState {
        self.inner.read().await.state.clone()
    }

    /// Subscribe to state change notifications.
    pub fn subscribe(&self) -> broadcast::Receiver<StateChange> {
        self.change_tx.subscribe()
    }
}

impl Default for StateStore {
    fn default() -> Self {
        Self::new()
    }
}

/// `NodeStateChanged.change` (protocol 0.3): the peer's full record.
#[derive(serde::Deserialize)]
struct NodeRecord {
    #[serde(default)]
    ipv4: Option<String>,
    #[serde(default)]
    ipv6: Option<String>,
    #[serde(default)]
    hostname: Option<String>,
    #[serde(default)]
    display_name: Option<String>,
    #[serde(default)]
    user_id: Option<String>,
    #[serde(default)]
    user_handle: Option<String>,
    #[serde(default)]
    device_handle: Option<String>,
    #[serde(default)]
    is_authenticated: bool,
}

/// Apply `event` to `st`.  Returns (resources touched → epoch bump,
/// resources to re-query).
fn apply_event_to(st: &mut SystemState, event: &IpcEvent, full_events: bool) -> (Vec<Resource>, Vec<Resource>) {
    use Resource::*;
    match event {
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
            if !st.pending_auth.iter().any(|r| r.request_id == *request_id) {
                st.pending_auth.push(PendingAuthRequest {
                    request_id: request_id.clone(),
                    src_device_id: src_device_id.clone(),
                    src_user_id: src_user_id.clone(),
                    src_device_display_name: src_device_display_name.clone(),
                    src_user_display_name: src_user_display_name.clone(),
                    proto: proto.clone(),
                    dst_port: *dst_port,
                    service_name: service_name.clone(),
                    expires_in_secs: None,
                    expires_at_ms: None,
                });
            }
            // The event carries no deadline; Core's list does.
            (vec![PendingAuth], vec![PendingAuth])
        }
        IpcEvent::AccessRequestTimeout { request_id } => {
            st.pending_auth.retain(|r| r.request_id != *request_id);
            (vec![PendingAuth], vec![])
        }
        IpcEvent::AccessAuthRequired { .. } => {
            st.access_auth_required = true;
            (vec![], vec![Health])
        }
        IpcEvent::AccessRequestResolved { request_id, .. } => {
            // The rule it created arrives as AclChanged.
            st.pending_auth.retain(|r| r.request_id != *request_id);
            (vec![PendingAuth], vec![])
        }
        IpcEvent::NodeAdded { device_id, ipv4, ipv6, hostname, display_name } => {
            let node = st.nodes.entry(device_id.clone()).or_insert_with(|| NodeInfo {
                device_id: device_id.clone(),
                ipv4: None,
                ipv6: None,
                hostname: None,
                display_name: None,
                user_id: None,
                user_handle: None,
                device_handle: None,
                is_authenticated: false,
                path_mode: None,
            });
            node.ipv4 = Some(ipv4.clone());
            node.ipv6 = Some(ipv6.clone());
            node.hostname = hostname.clone();
            node.display_name = display_name.clone();
            // User/identity fields are only in GetNodes.
            (vec![Nodes], vec![Nodes])
        }
        IpcEvent::NodeRemoved { device_id } => {
            st.nodes.remove(device_id);
            (vec![Nodes], vec![])
        }
        IpcEvent::NodeStateChanged { device_id, change } => {
            // Protocol 0.3: `change` is the peer's full current record.
            match (full_events, serde_json::from_value::<NodeRecord>(change.clone()), st.nodes.get_mut(device_id)) {
                (true, Ok(r), Some(node)) => {
                    node.ipv4 = r.ipv4;
                    node.ipv6 = r.ipv6;
                    node.hostname = r.hostname;
                    node.display_name = r.display_name;
                    node.user_id = r.user_id;
                    node.user_handle = r.user_handle;
                    node.device_handle = r.device_handle;
                    node.is_authenticated = r.is_authenticated;
                    (vec![Nodes], vec![])
                }
                _ => (vec![], vec![Nodes]),
            }
        }
        IpcEvent::PathChanged { device_id, new_mode, .. } => {
            if let Some(node) = st.nodes.get_mut(device_id) {
                node.path_mode = Some(new_mode.clone());
            }
            (vec![Nodes], vec![])
        }
        IpcEvent::AclChanged { rule_id, action } => {
            if action == "deleted" {
                st.acl_rules.retain(|r| r.id != *rule_id);
            }
            // Created/updated events don't carry the rule.
            (vec![AclRules], vec![AclRules, Health])
        }
        IpcEvent::AclGroupChanged { group_id, action } => {
            if action == "deleted" {
                st.acl_groups.retain(|g| g.id != *group_id);
            }
            (vec![AclGroups], vec![AclGroups])
        }
        IpcEvent::RoomStateChanged { room_id, room_name, peers, relay } if full_events => {
            // Protocol 0.3: same content as GetSystemState's room view.
            match peers.iter().map(|p| serde_json::from_value::<RoomPeer>(p.clone())).collect::<std::result::Result<Vec<_>, _>>() {
                Ok(peers) => {
                    st.room = RoomState { room_id: room_id.clone(), room_name: room_name.clone(), relay: relay.clone(), peers };
                    (vec![Room], vec![])
                }
                Err(_) => (vec![], vec![Room, Nodes]),
            }
        }
        IpcEvent::RoomStateChanged { .. } => {
            // Older Cores: the peer list lacks addresses and this device and
            // relay is unset; re-query Core's room view.
            (vec![], vec![Room, Nodes])
        }
        IpcEvent::IdentityChanged {
            device_id,
            user_id,
            device_handle,
            user_handle,
            device_display_name,
            user_display_name,
            device_hostname,
            is_authenticated,
        } => match st.health.as_mut() {
            Some(h) => {
                h.device_id = Some(device_id.clone());
                h.user_id = Some(user_id.clone());
                h.device_handle = device_handle.clone();
                h.user_handle = user_handle.clone();
                h.device_display_name = device_display_name.clone();
                h.user_display_name = user_display_name.clone();
                h.device_hostname = device_hostname.clone();
                h.is_authenticated = *is_authenticated;
                if *is_authenticated {
                    st.access_auth_required = false;
                }
                (vec![Health], vec![])
            }
            None => (vec![], vec![Health]),
        },
        IpcEvent::SettingsChanged { scope, settings } => {
            // Network settings are not part of the view (queried on demand;
            // UIs re-read them on change notices).
            if scope == "ping" {
                if let (Some(h), Some(on)) = (st.health.as_mut(), settings.get("ping_enabled").and_then(Value::as_bool)) {
                    h.ping_enabled = Some(on);
                    return (vec![Health], vec![]);
                }
                return (vec![], vec![Health]);
            }
            (vec![], vec![])
        }
        IpcEvent::CoordinatorConnectionChanged { connected } => {
            if let Some(h) = st.health.as_mut() {
                h.ws_connected = Some(*connected);
            }
            (vec![Health], vec![Health])
        }
        IpcEvent::Diagnostic { .. } => (vec![], vec![]),
    }
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    // Payloads below mirror Core 3.1.x `src/ipc/handler.rs`, `rooms::room_view`,
    // `acl::list_rules`/`rule_view`, `acl::list_groups`,
    // `inbound::pending_requests` and `diagnostics::path_view`.

    fn core_health() -> Value {
        json!({
            "status": "ok", "generation": 7,
            "device_id": "device-aabbccdd", "user_id": "usr-11223344",
            "device_handle": "pc", "user_handle": "alice",
            "user_display_name": "Alice", "device_display_name": "Alice PC",
            "device_hostname": "pc.alice.ezplay.msst.net",
            "is_authenticated": true, "auth_expires_at": 1_900_000_000i64,
            "ws_connected": true, "coordinator_state": "Connected", "tun_ready": true,
            "relay": "1.2.3.4:3478", "peer_count": 1, "rule_count": 1, "ping_enabled": false,
            "local_device_id": "device-aabbccdd", "local_user_id": "usr-11223344"
        })
    }

    fn core_rules() -> Value {
        json!({ "rules": [{
            "id": "r1",
            "permission_group": { "kind": "devices", "ids": ["device-00000001"] },
            "protocol_ports": [{ "proto": "tcp", "start": 25565, "end": 25565 }],
            "action": "allow", "direction": "inbound", "expires_at": null,
            "enabled": true, "comment": "mc",
            "principal_mnemonics": {}, "pending_migration": false
        }]})
    }

    #[tokio::test]
    async fn from_core_responses() {
        let store = StateStore::new();
        assert!(store.apply_resource(0, ResourceData::health(&core_health()).unwrap()).await);
        let room = json!({
            "room_id": "space-1", "room_name": "game", "relay": "1.2.3.4:3478",
            "peers": [{ "device_id": "device-00000001", "ipv4": "172.22.0.1", "ipv6": "fd22::1",
                        "hostname": null, "display_name": "Bob" }],
            "device_id": "device-aabbccdd", "user_id": "usr-11223344",
            "joined_rooms": { "space-1": "game" }, "rule_count": 1, "generation": 7
        });
        assert!(store.apply_resource(0, ResourceData::room(&room).unwrap()).await);
        let nodes = json!({ "nodes": [{
            "device_id": "device-00000001", "ipv4": "172.22.0.1", "ipv6": "fd22::1",
            "hostname": null, "display_name": "Bob", "user_id": null,
            "user_handle": null, "device_handle": null, "is_authenticated": false
        }]});
        let paths = json!({ "peers": [{ "device_id": "device-00000001", "display_name": "Bob",
                                        "chosen": "relay", "candidates": [] }]});
        assert!(store.apply_resource(0, ResourceData::nodes(&nodes, Some(&paths)).unwrap()).await);
        assert!(store.apply_resource(0, ResourceData::acl_rules(&core_rules()).unwrap()).await);
        let groups = json!({ "groups": [{ "id": "g1", "name": "friends", "kind": "user", "member_ids": ["usr-1"] }]});
        assert!(store.apply_resource(0, ResourceData::acl_groups(&groups).unwrap()).await);
        let pending = json!({ "requests": [{
            "request_id": "a1", "src_device_id": "device-00000001", "src_user_id": null,
            "src_device_display_name": "Bob", "src_user_display_name": null,
            "proto": "tcp", "dst_port": 25565, "service_name": "Minecraft Java", "expires_in_secs": 30
        }]});
        assert!(store.apply_resource(0, ResourceData::pending_auth(&pending, 1_000).unwrap()).await);

        let s = store.snapshot().await;
        let h = s.health.unwrap();
        assert!(h.is_authenticated);
        assert_eq!(h.ping_enabled, Some(false));
        assert_eq!(s.room.room_id.as_deref(), Some("space-1"));
        assert_eq!(s.room.peers[0].ipv4.as_deref(), Some("172.22.0.1"));
        assert_eq!(s.nodes["device-00000001"].path_mode.as_deref(), Some("relay"));
        assert_eq!(s.acl_rules[0].id, "r1");
        // Unknown rule fields are carried through unchanged.
        assert_eq!(s.acl_rules[0].fields["permission_group"]["kind"], "devices");
        assert_eq!(s.acl_groups[0].member_ids, vec!["usr-1"]);
        assert_eq!(s.pending_auth[0].expires_at_ms, Some(31_000));
    }

    #[test]
    fn malformed_response_is_an_error() {
        assert!(ResourceData::acl_rules(&json!({ "nope": [] })).is_err());
        assert!(ResourceData::acl_rules(&json!({ "rules": [{ "no_id": 1 }] })).is_err());
        assert!(ResourceData::nodes(&json!({ "nodes": [{}] }), None).is_err());
    }

    #[tokio::test]
    async fn stale_query_result_is_rejected() {
        let store = StateStore::new();
        let epoch = store.epoch(Resource::PendingAuth).await;
        // Event arrives while the query is in flight.
        store.apply_event(&IpcEvent::AccessRequestTimeout { request_id: "a1".into() }).await;
        let stale = ResourceData::pending_auth(
            &json!({ "requests": [{ "request_id": "a1", "src_device_id": "d", "proto": "tcp", "dst_port": 1 }] }),
            0,
        )
        .unwrap();
        assert!(!store.apply_resource(epoch, stale).await);
        assert!(store.snapshot().await.pending_auth.is_empty());
    }

    #[tokio::test]
    async fn access_request_dedup_and_requery() {
        let store = StateStore::new();
        let ev = IpcEvent::AccessRequestCreated {
            request_id: "a1".into(),
            src_device_id: "d".into(),
            src_user_id: None,
            src_device_display_name: None,
            src_user_display_name: None,
            proto: "tcp".into(),
            dst_port: 25565,
            service_name: None,
        };
        assert_eq!(store.apply_event(&ev).await, vec![Resource::PendingAuth]);
        store.apply_event(&ev).await;
        let s = store.snapshot().await;
        assert_eq!(s.pending_auth.len(), 1);
        assert_eq!(s.pending_auth[0].expires_at_ms, None, "no invented deadline");
    }

    #[tokio::test]
    async fn acl_events_trigger_requery() {
        let store = StateStore::new();
        store.apply_resource(0, ResourceData::acl_rules(&core_rules()).unwrap()).await;
        let refresh = store.apply_event(&IpcEvent::AclChanged { rule_id: "r1".into(), action: "updated".into() }).await;
        assert!(refresh.contains(&Resource::AclRules));
        store.apply_event(&IpcEvent::AclChanged { rule_id: "r1".into(), action: "deleted".into() }).await;
        assert!(store.snapshot().await.acl_rules.is_empty());
    }

    #[tokio::test]
    async fn node_lifecycle_and_path() {
        let store = StateStore::new();
        store
            .apply_event(&IpcEvent::NodeAdded {
                device_id: "d1".into(),
                ipv4: "172.22.0.2".into(),
                ipv6: "fd22::2".into(),
                hostname: None,
                display_name: Some("D1".into()),
            })
            .await;
        store.apply_event(&IpcEvent::PathChanged { device_id: "d1".into(), old_mode: "relay".into(), new_mode: "direct".into() }).await;
        assert_eq!(store.snapshot().await.nodes["d1"].path_mode.as_deref(), Some("direct"));
        store.apply_event(&IpcEvent::NodeRemoved { device_id: "d1".into() }).await;
        assert!(store.snapshot().await.nodes.is_empty());
    }

    #[tokio::test]
    async fn auth_required_cleared_by_authenticated_health() {
        let store = StateStore::new();
        store.apply_event(&IpcEvent::AccessAuthRequired { coordinator_url: "https://c".into() }).await;
        assert!(store.snapshot().await.access_auth_required);
        let epoch = store.epoch(Resource::Health).await;
        store.apply_resource(epoch, ResourceData::health(&core_health()).unwrap()).await;
        assert!(!store.snapshot().await.access_auth_required);
    }

    #[tokio::test]
    async fn diagnostic_events_do_not_notify() {
        let store = StateStore::new();
        let mut rx = store.subscribe();
        store
            .apply_event(&IpcEvent::Diagnostic { level: "info".into(), target: "t".into(), message: "m".into(), timestamp_ms: 0 })
            .await;
        assert!(rx.try_recv().is_err());
    }
}
