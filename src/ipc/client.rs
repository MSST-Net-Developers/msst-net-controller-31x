//! IPC client — manages the connection to Core, sends requests, keeps the
//! [`StateStore`] in sync.
//!
//! ## Connection lifecycle
//!
//! ```text
//! connect → version handshake → session:
//!     reader task      frames → session loop
//!     session loop     responses → waiting callers, events → StateStore,
//!                      outgoing requests → Core, periodic refresh ticks
//!     refresher task   (re)queries resources one at a time
//! session ends → status Disconnected, sync NotSynced → backoff → reconnect
//! ```
//!
//! Core has no combined snapshot request and does not replay missed events,
//! so after every (re)connect the refresher re-queries every [`Resource`]
//! (AGENTS.md §5.4).  Core also disconnects clients that fall behind on
//! events, which lands here as a normal reconnect + resync.
//!
//! ## Requests never outlive their session
//!
//! A request is only accepted while a session exists and is bound to that
//! session's channel.  When the session ends, queued and in-flight requests
//! fail with [`ControllerError::ConnectionLost`]; they are never replayed on
//! the next connection (a command the user gave up on must not run later).
//! A request that timed out may still have been executed by Core — callers
//! must not report it as failed-and-not-applied.

use std::collections::{BTreeSet, HashMap};
use std::future::Future;
use std::pin::Pin;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use msst_ipc_protocol::{IpcError, IpcMessage, IpcRequest, IpcResponse};
use serde::Serialize;
use tokio::sync::{broadcast, mpsc, oneshot, watch, RwLock};

use super::connection::{ConnectionDiagnostics, ConnectionStateMachine, Transition};
use super::handshake::{self, Capabilities, HandshakeResult};
use super::transport::{self, Frame, IpcStream};
use crate::error::{ControllerError, Result};
use crate::state::{ConnectionStatus, Resource, ResourceData, StateChange, StateStore, SyncState, SystemState};

// ── Configuration ────────────────────────────────────────────────────────────

/// IPC client configuration.
#[derive(Debug, Clone)]
pub struct IpcClientConfig {
    /// Initial reconnect delay.
    pub reconnect_base_ms: u64,
    /// Maximum reconnect delay.
    pub reconnect_max_ms: u64,
    /// Retry delay after a protocol version mismatch (Core may be upgraded).
    pub incompatible_retry_ms: u64,
    /// Request timeout.
    pub request_timeout: Duration,
    /// Re-query interval for state Core reports no events for (health,
    /// room membership, node list, pending requests resolved elsewhere).
    pub poll_interval: Duration,
}

impl Default for IpcClientConfig {
    fn default() -> Self {
        Self {
            reconnect_base_ms: 1_000,
            reconnect_max_ms: 30_000,
            incompatible_retry_ms: 60_000,
            request_timeout: Duration::from_secs(15),
            poll_interval: Duration::from_secs(10),
        }
    }
}

/// Opens a new stream to Core.  The default connects to the platform IPC
/// endpoint; tests inject an in-memory Core.
pub type Connector = Arc<dyn Fn() -> Pin<Box<dyn Future<Output = Result<IpcStream>> + Send>> + Send + Sync>;

/// The default connector: the platform IPC endpoint ([`transport::connect`]).
pub fn default_connector() -> Connector {
    Arc::new(|| Box::pin(transport::connect()))
}

// ── View for the UI ──────────────────────────────────────────────────────────

/// Everything the UI needs to render the main view, in one read.
#[derive(Debug, Clone, Serialize)]
pub struct ControllerView {
    pub connection: ConnectionStatus,
    pub diagnostics: ConnectionDiagnostics,
    /// Core's protocol version from the last handshake ("major.minor.patch").
    pub core_protocol: Option<String>,
    pub controller_protocol: String,
    pub capabilities: Capabilities,
    pub state: SystemState,
}

// ── IpcClient ────────────────────────────────────────────────────────────────

struct Outgoing {
    id: String,
    body: IpcRequest,
    response_tx: oneshot::Sender<IpcResponse>,
}

enum RefreshMsg {
    /// Re-query everything (after connect).
    Sync,
    Resources(Vec<Resource>),
}

struct Inner {
    config: IpcClientConfig,
    store: StateStore,
    state_machine: ConnectionStateMachine,
    next_request_id: AtomicU64,
    /// Request channel of the current session; `None` while not connected.
    session: Mutex<Option<mpsc::UnboundedSender<Outgoing>>>,
    shutdown_tx: watch::Sender<bool>,
    handshake: RwLock<Option<HandshakeResult>>,
    refresh_tx: mpsc::UnboundedSender<RefreshMsg>,
}

/// The IPC client — owns the connection to Core.
///
/// Clone-friendly: all state is behind `Arc`.  Create it once per process;
/// screens share it (AGENTS.md §6).
#[derive(Clone)]
pub struct IpcClient {
    inner: Arc<Inner>,
}

impl IpcClient {
    /// Create a client connecting to the platform IPC endpoint and start its
    /// background tasks.  Must be called inside a tokio runtime.
    pub fn new(config: IpcClientConfig, store: StateStore) -> Self {
        Self::with_connector(config, store, default_connector())
    }

    /// Create a client with a custom connector (tests, embedded Core).
    pub fn with_connector(config: IpcClientConfig, store: StateStore, connector: Connector) -> Self {
        let (refresh_tx, refresh_rx) = mpsc::unbounded_channel();
        let (shutdown_tx, _) = watch::channel(false);
        let client = Self {
            inner: Arc::new(Inner {
                config,
                store,
                state_machine: ConnectionStateMachine::new(),
                next_request_id: AtomicU64::new(1),
                session: Mutex::new(None),
                shutdown_tx,
                handshake: RwLock::new(None),
                refresh_tx,
            }),
        };
        tokio::spawn(client.clone().connection_loop(connector));
        tokio::spawn(client.clone().refresher(refresh_rx));
        client
    }

    pub fn state_store(&self) -> &StateStore {
        &self.inner.store
    }

    /// Get the current connection status.
    pub async fn connection_status(&self) -> ConnectionStatus {
        self.inner.state_machine.status().await
    }

    /// Get connection diagnostics (reconnect count, last error, etc.).
    pub async fn connection_diagnostics(&self) -> ConnectionDiagnostics {
        self.inner.state_machine.diagnostics().await
    }

    /// Result of the last successful version handshake.
    pub async fn handshake_result(&self) -> Option<HandshakeResult> {
        self.inner.handshake.read().await.clone()
    }

    /// Connection status, diagnostics, protocol info and domain state.
    pub async fn view(&self) -> ControllerView {
        let hs = self.handshake_result().await;
        let fmt = |v: (u32, u32, u32)| format!("{}.{}.{}", v.0, v.1, v.2);
        ControllerView {
            connection: self.connection_status().await,
            diagnostics: self.connection_diagnostics().await,
            core_protocol: hs.as_ref().map(|h| fmt(h.remote_decoded)),
            controller_protocol: fmt(msst_ipc_protocol::version::decode_version(msst_ipc_protocol::PROTOCOL_VERSION)),
            capabilities: hs.map(|h| h.capabilities).unwrap_or_default(),
            state: self.inner.store.snapshot().await,
        }
    }

    /// Subscribe to connection state transitions.
    pub fn subscribe_transitions(&self) -> broadcast::Receiver<Transition> {
        self.inner.state_machine.subscribe()
    }

    /// Subscribe to state change events.
    pub fn subscribe(&self) -> broadcast::Receiver<StateChange> {
        self.inner.store.subscribe()
    }

    /// Send a request to Core and wait for the correlated response.
    pub async fn request(&self, body: IpcRequest) -> Result<IpcResponse> {
        if let Some(min_minor) = required_minor(&body)
            && let Some(hs) = self.handshake_result().await
                && hs.remote_decoded.1 < min_minor {
                    return Err(ControllerError::Unsupported(format!(
                        "Core 的 IPC 协议版本 {}.{} 不支持此操作（需要 0.{}），请升级 Core",
                        hs.remote_decoded.0, hs.remote_decoded.1, min_minor
                    )));
                }

        let id = format!("req-{}", self.inner.next_request_id.fetch_add(1, Ordering::Relaxed));
        let (response_tx, response_rx) = oneshot::channel();
        let sent = {
            let session = self.inner.session.lock().unwrap();
            match session.as_ref() {
                Some(tx) => tx.send(Outgoing { id: id.clone(), body, response_tx }).is_ok(),
                None => false,
            }
        };
        if !sent {
            return Err(ControllerError::NotConnected { status: self.connection_status().await });
        }

        match tokio::time::timeout(self.inner.config.request_timeout, response_rx).await {
            Ok(Ok(response)) => Ok(response),
            Ok(Err(_)) => Err(ControllerError::ConnectionLost),
            Err(_) => Err(ControllerError::RequestTimeout {
                request_id: id,
                timeout_secs: self.inner.config.request_timeout.as_secs(),
            }),
        }
    }

    /// Send a request and extract the `Ok` value.
    pub async fn query(&self, body: IpcRequest) -> Result<serde_json::Value> {
        match self.request(body).await? {
            IpcResponse::Ok(data) => Ok(data),
            IpcResponse::Error(e) => Err(ControllerError::from(e)),
        }
    }

    /// Send a request on behalf of the user.  On success, the parts of the
    /// local view the request may have changed are re-queried from Core
    /// (Core does not emit events for every change, see
    /// `docs/PROTOCOL_PROPOSALS_3.1.md` P2).  The returned value is Core's
    /// response: for commands it means *accepted*, not *completed*.
    pub async fn command(&self, body: IpcRequest) -> Result<serde_json::Value> {
        let affected = affected_resources(&body);
        let result = self.query(body).await;
        if result.is_ok() && !affected.is_empty() {
            self.refresh(affected);
        }
        result
    }

    /// Schedule a re-query of `resources`.
    pub fn refresh(&self, resources: Vec<Resource>) {
        let _ = self.inner.refresh_tx.send(RefreshMsg::Resources(resources));
    }

    /// Stop the background tasks.
    pub fn shutdown(&self) {
        let _ = self.inner.shutdown_tx.send(true);
    }

    fn is_shutdown(&self) -> bool {
        *self.inner.shutdown_tx.borrow()
    }

    // ── Connection loop ──────────────────────────────────────────────────────

    async fn connection_loop(self, connector: Connector) {
        let cfg = self.inner.config.clone();
        let sm = &self.inner.state_machine;
        let mut shutdown = self.inner.shutdown_tx.subscribe();
        let mut delay = cfg.reconnect_base_ms;
        let mut attempt: u32 = 0;

        while !self.is_shutdown() {
            sm.begin_connecting().await;
            let next_delay = match connector().await {
                Err(e) => {
                    tracing::debug!(error = %e, "IPC connection failed");
                    attempt += 1;
                    sm.set_last_error(e.to_string()).await;
                    sm.begin_reconnecting(attempt, delay).await;
                    None
                }
                Ok(mut stream) => match handshake::perform_handshake(&mut stream).await {
                    Err(e @ ControllerError::VersionMismatch { .. }) => {
                        tracing::error!(error = %e, "Core protocol version is incompatible");
                        *self.inner.handshake.write().await = None;
                        sm.mark_incompatible(e.to_string()).await;
                        Some(cfg.incompatible_retry_ms)
                    }
                    Err(e) => {
                        tracing::warn!(error = %e, "Version handshake failed");
                        attempt += 1;
                        sm.set_last_error(e.to_string()).await;
                        sm.begin_reconnecting(attempt, delay).await;
                        None
                    }
                    Ok(hs) => {
                        tracing::info!(remote = ?hs.remote_decoded, degraded = hs.degraded, "Connected to Core");
                        *self.inner.handshake.write().await = Some(hs);
                        sm.mark_connected().await;
                        attempt = 0;
                        delay = cfg.reconnect_base_ms;
                        let reason = self.run_session(stream).await;
                        tracing::warn!(%reason, "IPC session ended");
                        sm.mark_disconnected(Some(reason)).await;
                        self.inner.store.set_sync(SyncState::NotSynced).await;
                        // Reconnect promptly after a lost session.
                        Some(cfg.reconnect_base_ms)
                    }
                },
            };

            let wait = next_delay.unwrap_or(delay);
            tokio::select! {
                _ = tokio::time::sleep(Duration::from_millis(wait)) => {}
                _ = shutdown.changed() => break,
            }
            if next_delay.is_none() {
                delay = (delay * 2).min(cfg.reconnect_max_ms);
            }
        }
        tracing::info!("IPC connection loop exited");
    }

    /// Serve one connected session until it ends; returns the reason.
    async fn run_session(&self, stream: IpcStream) -> String {
        let (mut reader, mut writer) = tokio::io::split(stream);

        // Reading a frame is not cancel-safe, so it runs in its own task.
        let (frame_tx, mut frame_rx) = mpsc::unbounded_channel();
        let read_task = tokio::spawn(async move {
            loop {
                let r = transport::recv_frame(&mut reader).await;
                let end = !matches!(r, Ok(Some(_)));
                if frame_tx.send(r).is_err() || end {
                    break;
                }
            }
        });

        let (out_tx, mut out_rx) = mpsc::unbounded_channel::<Outgoing>();
        *self.inner.session.lock().unwrap() = Some(out_tx);
        let _ = self.inner.refresh_tx.send(RefreshMsg::Sync);

        let mut pending: HashMap<String, oneshot::Sender<IpcResponse>> = HashMap::new();
        let mut shutdown = self.inner.shutdown_tx.subscribe();
        let mut poll = tokio::time::interval(self.inner.config.poll_interval);
        poll.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        poll.tick().await;

        let reason = loop {
            tokio::select! {
                frame = frame_rx.recv() => match frame {
                    Some(Ok(Some(frame))) => self.handle_frame(frame, &mut pending).await,
                    Some(Ok(None)) | None => break "Core closed the connection".to_string(),
                    Some(Err(e)) => break format!("IPC read error: {e}"),
                },
                out = out_rx.recv() => {
                    let Some(out) = out else { break "request channel closed".to_string() };
                    pending.retain(|_, tx| !tx.is_closed()); // callers that timed out
                    let msg = IpcMessage::Request { id: out.id.clone(), body: out.body };
                    pending.insert(out.id, out.response_tx);
                    if let Err(e) = transport::send_frame(&mut writer, &msg).await {
                        break format!("IPC write error: {e}");
                    }
                }
                _ = poll.tick() => {
                    let _ = self.inner.refresh_tx.send(RefreshMsg::Resources(vec![
                        Resource::Health, Resource::Room, Resource::Nodes, Resource::PendingAuth,
                    ]));
                }
                _ = shutdown.changed() => break "shutdown".to_string(),
            }
        };

        // Fail queued requests (dropping them drops their response senders).
        *self.inner.session.lock().unwrap() = None;
        drop(out_rx);
        drop(pending);
        read_task.abort();
        reason
    }

    async fn handle_frame(&self, frame: Frame, pending: &mut HashMap<String, oneshot::Sender<IpcResponse>>) {
        match frame {
            Frame::Message(IpcMessage::Response { id, body }) => match pending.remove(&id) {
                Some(tx) => {
                    let _ = tx.send(body);
                }
                None => tracing::debug!(%id, "response for unknown or timed-out request"),
            },
            Frame::Message(IpcMessage::Event { body }) => {
                let refresh = self.inner.store.apply_event(&body).await;
                if !refresh.is_empty() {
                    let _ = self.inner.refresh_tx.send(RefreshMsg::Resources(refresh));
                }
            }
            Frame::Message(IpcMessage::Request { .. }) => tracing::warn!("ignoring unexpected Request from Core"),
            Frame::Undecodable { kind, id, error } => {
                if kind.as_deref() == Some("response")
                    && let Some(tx) = id.and_then(|id| pending.remove(&id)) {
                        let _ = tx.send(IpcResponse::Error(IpcError {
                            code: "malformed_response".into(),
                            message: format!("无法解析 Core 的响应: {error}"),
                        }));
                        return;
                    }
                // Typically an event type added in a newer protocol minor.
                tracing::warn!(?kind, %error, "ignoring undecodable frame from Core");
            }
        }
    }

    // ── Refresher ────────────────────────────────────────────────────────────

    async fn refresher(self, mut rx: mpsc::UnboundedReceiver<RefreshMsg>) {
        while let Some(first) = rx.recv().await {
            // Coalesce everything queued so far.
            let mut sync = false;
            let mut set = BTreeSet::new();
            let mut absorb = |m: RefreshMsg| match m {
                RefreshMsg::Sync => sync = true,
                RefreshMsg::Resources(rs) => set.extend(rs),
            };
            absorb(first);
            while let Ok(m) = rx.try_recv() {
                absorb(m);
            }
            if sync {
                self.inner.store.set_sync(SyncState::Syncing).await;
                set.extend(Resource::ALL);
            }

            let mut errors = Vec::new();
            for r in set {
                if let Err(e) = self.refresh_one(r).await {
                    tracing::warn!(resource = ?r, error = %e, "failed to query Core");
                    errors.push(format!("{}: {e}", resource_label(r)));
                }
            }

            if sync {
                let connected = self.inner.session.lock().unwrap().is_some();
                let next = if !connected {
                    SyncState::NotSynced
                } else if errors.is_empty() {
                    SyncState::Synced
                } else {
                    SyncState::Failed { message: errors.join("; ") }
                };
                self.inner.store.set_sync(next).await;
            }
            if self.is_shutdown() {
                break;
            }
        }
    }

    /// Query one resource and apply it; re-query (bounded) if an event made
    /// the result stale while it was in flight.
    async fn refresh_one(&self, r: Resource) -> Result<()> {
        for _ in 0..3 {
            let epoch = self.inner.store.epoch(r).await;
            let data = self.fetch(r).await?;
            if self.inner.store.apply_resource(epoch, data).await {
                return Ok(());
            }
        }
        Ok(()) // Still changing; the triggering events scheduled another refresh.
    }

    async fn fetch(&self, r: Resource) -> Result<ResourceData> {
        match r {
            Resource::Health => ResourceData::health(&self.query(IpcRequest::Health).await?),
            Resource::Room => ResourceData::room(&self.query(IpcRequest::GetSystemState).await?),
            Resource::Nodes => {
                let nodes = self.query(IpcRequest::GetNodes).await?;
                let paths = self.query(IpcRequest::GetPathView).await.ok();
                ResourceData::nodes(&nodes, paths.as_ref())
            }
            Resource::AclRules => ResourceData::acl_rules(&self.query(IpcRequest::GetAclRules).await?),
            Resource::AclGroups => ResourceData::acl_groups(&self.query(IpcRequest::GetAclGroups).await?),
            Resource::PendingAuth => {
                let v = self.query(IpcRequest::GetPendingAuth).await?;
                ResourceData::pending_auth(&v, now_ms())
            }
        }
    }
}

fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

fn resource_label(r: Resource) -> &'static str {
    match r {
        Resource::Health => "状态",
        Resource::Room => "房间",
        Resource::Nodes => "节点",
        Resource::AclRules => "防火墙规则",
        Resource::AclGroups => "权限组",
        Resource::PendingAuth => "访问请求",
    }
}

/// Minimum protocol minor version a request needs (only for requests added
/// after 0.1).
fn required_minor(req: &IpcRequest) -> Option<u32> {
    match req {
        IpcRequest::GetServices => Some(2),
        _ => None,
    }
}

/// Parts of the local view a successful request may have changed.
pub fn affected_resources(req: &IpcRequest) -> Vec<Resource> {
    use Resource::*;
    match req {
        IpcRequest::CreateRoom { .. } | IpcRequest::JoinRoom { .. } | IpcRequest::LeaveRoom { .. } => {
            vec![Room, Nodes, Health]
        }
        IpcRequest::CreateAclRule { .. } | IpcRequest::UpdateAclRule { .. } | IpcRequest::DeleteAclRule { .. } => {
            vec![AclRules, Health]
        }
        IpcRequest::CreateAclGroup { .. } | IpcRequest::UpdateAclGroup { .. } | IpcRequest::DeleteAclGroup { .. } => {
            vec![AclGroups]
        }
        IpcRequest::AuthDecision { .. } => vec![PendingAuth, AclRules, Health],
        IpcRequest::UpdateDisplayName { .. }
        | IpcRequest::UpdateHandle { .. }
        | IpcRequest::ReRegister
        | IpcRequest::AuthPoll { .. }
        | IpcRequest::AuthRefresh
        | IpcRequest::SetPingEnabled { .. } => vec![Health],
        _ => vec![],
    }
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_config() {
        let cfg = IpcClientConfig::default();
        assert_eq!(cfg.reconnect_base_ms, 1_000);
        assert_eq!(cfg.reconnect_max_ms, 30_000);
    }

    #[test]
    fn commands_map_to_affected_resources() {
        assert!(affected_resources(&IpcRequest::AuthDecision {
            request_id: "a".into(),
            decision: msst_ipc_protocol::AuthDecision::AllowOnce
        })
        .contains(&Resource::PendingAuth));
        assert!(affected_resources(&IpcRequest::GetLogs { limit: None }).is_empty());
    }

    #[tokio::test]
    async fn request_without_session_fails_fast() {
        let connector: Connector = Arc::new(|| {
            Box::pin(async { Err(ControllerError::ConnectionFailed { path: "x".into(), reason: "down".into() }) })
        });
        let client = IpcClient::with_connector(IpcClientConfig::default(), StateStore::new(), connector);
        let r = tokio::time::timeout(Duration::from_secs(1), client.request(IpcRequest::Health)).await.unwrap();
        assert!(matches!(r, Err(ControllerError::NotConnected { .. })));
        client.shutdown();
    }
}
