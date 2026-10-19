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
//! With protocol 0.4 Cores the check is exact instead: events carry `seq`
//! and responses `event_seq` (the last event Core had emitted when it read
//! the state).  A result is stale only if an event *newer* than its
//! `event_seq` already touched the resource; events at or below it are
//! already reflected, so they neither invalidate the result nor trigger a
//! re-query.  Events are still applied (idempotently): one just above
//! `event_seq` may or may not be reflected.  `seq` restarts with every Core
//! run, so the bookkeeping is reset per session ([`StateStore::begin_session`]).
//!
//! The UI layer reads state through [`StateStore::snapshot`] and listens to
//! [`StateStore::subscribe`]; it never mutates the store.

use std::collections::{BTreeMap, HashMap, VecDeque};
use std::sync::Arc;

use msst_ipc_protocol::views::{OperationStatus, OperationView};
use msst_ipc_protocol::{IpcEvent, IpcResponse};
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
    /// A background operation was started or queried (not via an event).
    OperationChanged(String),
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

/// Operations kept in the view (running ones are evicted last).
const MAX_OPERATIONS: usize = 32;

#[derive(Default)]
struct Inner {
    state: SystemState,
    epochs: HashMap<Resource, u64>,
    /// Core speaks protocol >= 0.3: `RoomStateChanged` / `NodeStateChanged`
    /// carry full state and can be applied directly.
    full_events: bool,
    /// Highest event `seq` that touched each resource (this session).
    event_seqs: HashMap<Resource, u64>,
    /// `event_seq` of the query result each resource was last replaced with
    /// (this session).
    snapshot_seqs: HashMap<Resource, u64>,
    /// IDs in `state.operations`, oldest first.
    op_order: VecDeque<String>,
}

impl Inner {
    fn operation_mut(&mut self, id: &str) -> &mut OperationState {
        if !self.state.operations.contains_key(id) {
            self.op_order.push_back(id.to_string());
            while self.op_order.len() > MAX_OPERATIONS {
                let ops = &self.state.operations;
                let victim = self.op_order.iter().position(|i| ops.get(i).is_some_and(|o| o.result.is_some())).unwrap_or(0);
                if let Some(old) = self.op_order.remove(victim) {
                    self.state.operations.remove(&old);
                }
            }
        }
        self.state.operations.entry(id.to_string()).or_default()
    }
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
            inner: Arc::new(RwLock::new(Inner::default())),
            change_tx,
        }
    }

    /// Record the connected Core's protocol minor version (after each
    /// handshake); decides how partial events are handled.
    pub async fn set_protocol_minor(&self, minor: u32) {
        self.inner.write().await.full_events = minor >= 3;
    }

    /// Start of a new IPC session with a Core speaking protocol minor
    /// `minor`.  Forgets the previous session's event positions (`seq`
    /// restarts with Core) and its operations (their events may have been
    /// missed; callers waiting for one re-query it with `GetOperation`).
    pub async fn begin_session(&self, minor: u32) {
        let mut inner = self.inner.write().await;
        inner.full_events = minor >= 3;
        inner.event_seqs.clear();
        inner.snapshot_seqs.clear();
        inner.op_order.clear();
        inner.state.operations.clear();
    }

    /// The current epoch of `resource`; pass it to [`Self::apply_resource`].
    pub async fn epoch(&self, resource: Resource) -> u64 {
        self.inner.read().await.epochs.get(&resource).copied().unwrap_or(0)
    }

    /// Replace a resource with a query result, unless an event touched the
    /// resource after `epoch` was read.  Returns `false` if the result was
    /// discarded as stale (the caller should query again).
    pub async fn apply_resource(&self, epoch: u64, data: ResourceData) -> bool {
        self.apply_resource_at(epoch, data, None).await
    }

    /// [`Self::apply_resource`] for a result whose response carried
    /// `event_seq` (protocol 0.4): stale only if an event newer than
    /// `event_seq` touched the resource, or a newer result was applied.
    /// Without `event_seq`, falls back to the epoch check.
    pub async fn apply_resource_at(&self, epoch: u64, data: ResourceData, event_seq: Option<u64>) -> bool {
        let resource = data.resource();
        let mut inner = self.inner.write().await;
        let fresh = match event_seq {
            Some(at) => {
                inner.event_seqs.get(&resource).is_none_or(|&s| s <= at)
                    && inner.snapshot_seqs.get(&resource).is_none_or(|&s| s <= at)
            }
            None => inner.epochs.get(&resource).copied().unwrap_or(0) == epoch,
        };
        if !fresh {
            return false;
        }
        if let Some(at) = event_seq {
            inner.snapshot_seqs.insert(resource, at);
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
        self.apply_event_at(event, None).await
    }

    /// [`Self::apply_event`] for an event with `seq` (protocol 0.4).  The
    /// caller has already dropped duplicates and detected gaps; here `seq`
    /// only decides staleness and skips re-queries of resources whose last
    /// query result already reflects the event.
    pub async fn apply_event_at(&self, event: &IpcEvent, seq: Option<u64>) -> Vec<Resource> {
        // Log lines are not state; they are fetched on demand (GetLogs).
        if matches!(event, IpcEvent::Diagnostic { .. }) {
            return Vec::new();
        }

        let mut inner = self.inner.write().await;
        let refresh = match event {
            IpcEvent::OperationProgress { operation_id, stage } => {
                inner.operation_mut(operation_id).stage = Some(stage.clone());
                Vec::new()
            }
            IpcEvent::OperationCompleted { operation_id, request_type, result } => {
                let op = inner.operation_mut(operation_id);
                op.request_type = Some(request_type.clone());
                op.result = Some(result.clone());
                Vec::new()
            }
            _ => {
                let full = inner.full_events;
                let (touched, mut refresh) = apply_event_to(&mut inner.state, event, full);
                for r in touched {
                    *inner.epochs.entry(r).or_insert(0) += 1;
                    if let Some(s) = seq {
                        let e = inner.event_seqs.entry(r).or_insert(0);
                        *e = (*e).max(s);
                    }
                }
                if let Some(s) = seq {
                    refresh.retain(|r| inner.snapshot_seqs.get(r).is_none_or(|&snap| snap < s));
                }
                refresh
            }
        };
        drop(inner);
        let _ = self.change_tx.send(StateChange::EventApplied(event.clone()));
        refresh
    }

    /// Note that this client started operation `id` for a `request_type`
    /// request (progress events do not name the request type).
    pub async fn track_operation(&self, id: &str, request_type: &str) {
        let mut inner = self.inner.write().await;
        let op = inner.operation_mut(id);
        op.request_type.get_or_insert_with(|| request_type.to_string());
        drop(inner);
        let _ = self.change_tx.send(StateChange::OperationChanged(id.to_string()));
    }

    /// Record an operation's state as queried with `GetOperation`.
    pub async fn record_operation(&self, view: &OperationView) {
        let mut inner = self.inner.write().await;
        let op = inner.operation_mut(&view.operation_id);
        op.request_type = Some(view.request_type.clone());
        if view.stage.is_some() {
            op.stage = view.stage.clone();
        }
        if view.status == OperationStatus::Completed && view.result.is_some() {
            op.result = view.result.clone();
        }
        drop(inner);
        let _ = self.change_tx.send(StateChange::OperationChanged(view.operation_id.clone()));
    }

    /// The result of operation `id`, once Core reported it complete.
    pub async fn operation_result(&self, id: &str) -> Option<IpcResponse> {
        self.inner.read().await.state.operations.get(id).and_then(|o| o.result.clone())
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
        // Handled by the store itself (not part of the queried resources).
        IpcEvent::OperationProgress { .. } | IpcEvent::OperationCompleted { .. } | IpcEvent::Diagnostic { .. } => {
            (vec![], vec![])
        }
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
    async fn event_seq_decides_staleness_and_requery() {
        let store = StateStore::new();
        store.begin_session(4).await;
        let epoch = store.epoch(Resource::AclRules).await;
        // Event 5 arrives while a query is in flight.
        let refresh = store.apply_event_at(&IpcEvent::AclChanged { rule_id: "r1".into(), action: "updated".into() }, Some(5)).await;
        assert!(refresh.contains(&Resource::AclRules));
        // Read before event 5: stale.
        assert!(!store.apply_resource_at(epoch, ResourceData::acl_rules(&core_rules()).unwrap(), Some(4)).await);
        // Read after it: fresh, although the epoch moved.
        assert!(store.apply_resource_at(epoch, ResourceData::acl_rules(&core_rules()).unwrap(), Some(5)).await);
        // An older result must not replace a newer one.
        assert!(!store.apply_resource_at(epoch + 1, ResourceData::acl_rules(&json!({ "rules": [] })).unwrap(), Some(4)).await);
        assert_eq!(store.snapshot().await.acl_rules.len(), 1);

        // Already reflected by the snapshot at 5: applied, but not re-queried.
        let ev = IpcEvent::AclChanged { rule_id: "r1".into(), action: "updated".into() };
        assert!(!store.apply_event_at(&ev, Some(5)).await.contains(&Resource::AclRules));
        assert!(store.apply_event_at(&ev, Some(6)).await.contains(&Resource::AclRules));

        // A new session (Core may have restarted) forgets the positions.
        store.begin_session(4).await;
        assert!(store.apply_resource_at(0, ResourceData::acl_rules(&core_rules()).unwrap(), Some(1)).await);
    }

    #[tokio::test]
    async fn operations_follow_events_and_stay_bounded() {
        let store = StateStore::new();
        store.apply_event_at(&IpcEvent::OperationProgress { operation_id: "op-1".into(), stage: "relay_selected".into() }, Some(1)).await;
        store.track_operation("op-1", "create_room").await;
        let op = store.snapshot().await.operations["op-1"].clone();
        assert_eq!((op.request_type.as_deref(), op.stage.as_deref()), (Some("create_room"), Some("relay_selected")));
        assert!(store.operation_result("op-1").await.is_none());

        let result = msst_ipc_protocol::IpcResponse::Ok(json!({ "room_id": "space-1" }));
        store
            .apply_event_at(&IpcEvent::OperationCompleted { operation_id: "op-1".into(), request_type: "create_room".into(), result }, Some(2))
            .await;
        assert!(store.operation_result("op-1").await.is_some());

        // Still running ones survive eviction.
        store.track_operation("op-running", "join_room").await;
        for i in 0..100 {
            let id = format!("op-x{i}");
            let result = msst_ipc_protocol::IpcResponse::Ok(json!({}));
            store.apply_event(&IpcEvent::OperationCompleted { operation_id: id, request_type: "join_room".into(), result }).await;
        }
        let ops = store.snapshot().await.operations;
        assert_eq!(ops.len(), MAX_OPERATIONS);
        assert!(ops.contains_key("op-running") && !ops.contains_key("op-1"));
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
