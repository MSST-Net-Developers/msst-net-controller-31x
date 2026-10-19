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
//! ## Protocol 0.4
//!
//! - Events carry `seq` (1, 2, 3 … per Core run).  A duplicate is dropped;
//!   a gap means lost events and triggers a full resync.  Responses carry
//!   `event_seq`, which the [`StateStore`] uses to tell stale query results
//!   from fresh ones.
//! - Responses may arrive out of order; they are matched strictly by `id`.
//! - `CreateRoom` / `JoinRoom` run as background operations (`async`):
//!   Core accepts at once and reports progress and the result as events.
//!   [`IpcClient::command`] waits for the result, so callers see the same
//!   outcome as a synchronous request, without the request timeout; if the
//!   completion event is missed (reconnect), `GetOperation` recovers it.
//! - Log lines (`Diagnostic` events) are switched off with `SetLogStream`;
//!   the log is read on demand with `GetLogs`.
//! - Commands may be refused with `permission_denied` when Core gives this
//!   connection the `viewer` role (`Health.ipc_role`).
//! - A Core that rejects our version answers id `"handshake"` with
//!   `incompatible_version` before closing; that ends the session as
//!   [`ConnectionStatus::Incompatible`].
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

use msst_ipc_protocol::views::{OperationAccepted, OperationView};
use msst_ipc_protocol::{IpcError, IpcMessage, IpcRequest, IpcResponse};
use serde::Serialize;
use tokio::sync::{broadcast, mpsc, oneshot, watch, Notify, RwLock};

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
    /// Maximum reconnect delay.  Kept short: Core is a local socket/pipe, so
    /// a failed attempt is nearly free, and a Core started after the
    /// Controller should be picked up within seconds, not after a long
    /// backoff the user mistakes for "never reconnects".
    pub reconnect_max_ms: u64,
    /// Retry delay after a protocol version mismatch (Core may be upgraded).
    pub incompatible_retry_ms: u64,
    /// Request timeout.
    pub request_timeout: Duration,
    /// Re-query interval for state Core reports no events for (health,
    /// room membership, node list, pending requests resolved elsewhere).
    pub poll_interval: Duration,
    /// How long [`IpcClient::command`] waits for a background operation's
    /// result before reporting that it may still be running.
    pub operation_timeout: Duration,
    /// While waiting for an operation, how often its state is re-queried
    /// with `GetOperation` in case the completion event was missed.
    pub operation_poll_interval: Duration,
}

impl Default for IpcClientConfig {
    fn default() -> Self {
        Self {
            reconnect_base_ms: 1_000,
            reconnect_max_ms: 3_000,
            incompatible_retry_ms: 60_000,
            request_timeout: Duration::from_secs(15),
            poll_interval: Duration::from_secs(10),
            operation_timeout: Duration::from_secs(120),
            operation_poll_interval: Duration::from_secs(3),
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

/// What [`IpcClient::retry_now`] did.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum RetryOutcome {
    /// Not connected: a connection attempt starts now instead of after the
    /// remaining backoff.
    Reconnecting,
    /// Connected: every resource is being re-queried from Core.
    Resyncing,
}

// ── IpcClient ────────────────────────────────────────────────────────────────

/// A response and its `event_seq` (protocol 0.4), or why none arrived.
type Reply = Result<(IpcResponse, Option<u64>)>;

struct Outgoing {
    id: String,
    body: IpcRequest,
    run_async: bool,
    response_tx: oneshot::Sender<Reply>,
}

/// Why a session ended.
enum SessionEnd {
    /// Connection lost or closed; reconnect.
    Lost(String),
    /// Core rejected our protocol version after the handshake.
    Incompatible(String),
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
    /// Cuts the current backoff wait short (user asked to retry now).
    retry_now: Notify,
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
                retry_now: Notify::new(),
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
        self.send(body, false).await.map(|(r, _)| r)
    }

    /// Send a request (as a background operation if `run_async`) and wait
    /// for the response and its `event_seq`.
    async fn send(&self, body: IpcRequest, run_async: bool) -> Result<(IpcResponse, Option<u64>)> {
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
                Some(tx) => tx.send(Outgoing { id: id.clone(), body, run_async, response_tx }).is_ok(),
                None => false,
            }
        };
        if !sent {
            return Err(ControllerError::NotConnected { status: self.connection_status().await });
        }

        match tokio::time::timeout(self.inner.config.request_timeout, response_rx).await {
            Ok(Ok(reply)) => reply,
            Ok(Err(_)) => Err(ControllerError::ConnectionLost),
            Err(_) => Err(ControllerError::RequestTimeout {
                request_id: id,
                timeout_secs: self.inner.config.request_timeout.as_secs(),
            }),
        }
    }

    /// Send a request and extract the `Ok` value.
    pub async fn query(&self, body: IpcRequest) -> Result<serde_json::Value> {
        self.query_at(body).await.map(|(v, _)| v)
    }

    /// [`Self::query`] plus the response's `event_seq` (protocol 0.4).
    async fn query_at(&self, body: IpcRequest) -> Result<(serde_json::Value, Option<u64>)> {
        match self.send(body, false).await? {
            (IpcResponse::Ok(data), at) => Ok((data, at)),
            (IpcResponse::Error(e), _) => Err(ControllerError::from(e)),
        }
    }

    /// Send a request on behalf of the user.  On success, the parts of the
    /// local view the request may have changed are re-queried from Core
    /// (Core does not emit events for every change, see
    /// `docs/PROTOCOL_PROPOSALS_3.1.md` P2).  The returned value is Core's
    /// response: for commands it means *accepted*, not *completed*.
    ///
    /// Slow commands ([`prefers_async`]) run as background operations when
    /// Core supports them; the result is then the operation's result.
    pub async fn command(&self, body: IpcRequest) -> Result<serde_json::Value> {
        let affected = affected_resources(&body);
        let run_async =
            prefers_async(&body) && self.handshake_result().await.is_some_and(|h| h.capabilities.operations);
        let result = if run_async { self.run_operation(body).await } else { self.query(body).await };
        if result.is_ok() && !affected.is_empty() {
            self.refresh(affected);
        }
        result
    }

    /// Run `body` as a background operation (protocol 0.4) and wait for its
    /// result.  Core owns the operation: it finishes even if this client
    /// disconnects or stops waiting.
    async fn run_operation(&self, body: IpcRequest) -> Result<serde_json::Value> {
        let request_type =
            serde_json::to_value(&body).ok().and_then(|v| v.get("type").and_then(|t| t.as_str()).map(str::to_string));
        // Subscribe first: the completion may be applied before we look.
        let mut changes = self.inner.store.subscribe();
        let accepted: OperationAccepted = match self.send(body, true).await? {
            (IpcResponse::Ok(v), _) => serde_json::from_value(v)
                .map_err(|e| ControllerError::Codec(format!("async request: unexpected response: {e}")))?,
            (IpcResponse::Error(e), _) => return Err(e.into()),
        };
        let id = accepted.operation_id;
        tracing::debug!(operation = %id, ?request_type, "background operation started");
        if let Some(t) = &request_type {
            self.inner.store.track_operation(&id, t).await;
        }

        let cfg = &self.inner.config;
        let deadline = tokio::time::Instant::now() + cfg.operation_timeout;
        let mut poll = tokio::time::interval(cfg.operation_poll_interval);
        poll.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        poll.tick().await;
        loop {
            if let Some(result) = self.inner.store.operation_result(&id).await {
                return match result {
                    IpcResponse::Ok(v) => Ok(v),
                    IpcResponse::Error(e) => Err(e.into()),
                };
            }
            tokio::select! {
                c = changes.recv() => if let Err(broadcast::error::RecvError::Closed) = c {
                    return Err(ControllerError::Shutdown);
                },
                // Fallback for a completion event lost to a reconnect.
                _ = poll.tick() => match self.query(IpcRequest::GetOperation { operation_id: id.clone() }).await {
                    Ok(v) => match serde_json::from_value::<OperationView>(v) {
                        Ok(view) => self.inner.store.record_operation(&view).await,
                        Err(e) => tracing::warn!(operation = %id, error = %e, "unexpected GetOperation response"),
                    },
                    Err(ControllerError::CoreError { code, .. }) if code == msst_ipc_protocol::errors::NOT_FOUND => {
                        return Err(ControllerError::OperationLost { operation_id: id });
                    }
                    Err(e) => tracing::debug!(operation = %id, error = %e, "GetOperation failed; still waiting"),
                },
                _ = tokio::time::sleep_until(deadline) => {
                    return Err(ControllerError::RequestTimeout { request_id: id, timeout_secs: cfg.operation_timeout.as_secs() });
                }
            }
        }
    }

    /// Schedule a re-query of `resources`.
    pub fn refresh(&self, resources: Vec<Resource>) {
        let _ = self.inner.refresh_tx.send(RefreshMsg::Resources(resources));
    }

    /// User-requested retry.  While not connected, the next connection
    /// attempt starts immediately (and the backoff restarts from its base);
    /// while connected, the live session is kept and all state is re-queried
    /// from Core instead.  Either way, the outcome arrives through the usual
    /// status and state updates.
    pub async fn retry_now(&self) -> RetryOutcome {
        if self.connection_status().await == ConnectionStatus::Connected {
            let _ = self.inner.refresh_tx.send(RefreshMsg::Sync);
            RetryOutcome::Resyncing
        } else {
            // Stores a permit if the loop is mid-attempt, so the wait after
            // that attempt is skipped rather than the request being lost.
            self.inner.retry_now.notify_one();
            RetryOutcome::Reconnecting
        }
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
                        self.inner.store.begin_session(hs.remote_decoded.1).await;
                        *self.inner.handshake.write().await = Some(hs);
                        sm.mark_connected().await;
                        attempt = 0;
                        delay = cfg.reconnect_base_ms;
                        let end = self.run_session(stream).await;
                        self.inner.store.set_sync(SyncState::NotSynced).await;
                        match end {
                            SessionEnd::Lost(reason) => {
                                tracing::warn!(%reason, "IPC session ended");
                                sm.mark_disconnected(Some(reason)).await;
                                // Reconnect promptly after a lost session.
                                Some(cfg.reconnect_base_ms)
                            }
                            SessionEnd::Incompatible(reason) => {
                                tracing::error!(%reason, "Core rejected the Controller's protocol version");
                                sm.mark_incompatible(reason).await;
                                Some(cfg.incompatible_retry_ms)
                            }
                        }
                    }
                },
            };

            let wait = next_delay.unwrap_or(delay);
            tokio::select! {
                _ = tokio::time::sleep(Duration::from_millis(wait)) => {
                    if next_delay.is_none() {
                        delay = (delay * 2).min(cfg.reconnect_max_ms);
                    }
                }
                _ = self.inner.retry_now.notified() => {
                    tracing::info!("Reconnect requested, retrying now");
                    delay = cfg.reconnect_base_ms;
                }
                _ = shutdown.changed() => break,
            }
        }
        tracing::info!("IPC connection loop exited");
    }

    /// Serve one connected session until it ends; returns why.
    async fn run_session(&self, stream: IpcStream) -> SessionEnd {
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

        let mut pending: HashMap<String, oneshot::Sender<Reply>> = HashMap::new();
        // `seq` of the last event received (protocol 0.4).
        let mut last_seq: Option<u64> = None;
        let mut shutdown = self.inner.shutdown_tx.subscribe();
        let mut poll = tokio::time::interval(self.inner.config.poll_interval);
        poll.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        poll.tick().await;

        let reason = loop {
            tokio::select! {
                frame = frame_rx.recv() => match frame {
                    Some(Ok(Some(frame))) => {
                        if let Some(end) = self.handle_frame(frame, &mut pending, &mut last_seq).await {
                            break end;
                        }
                    }
                    Some(Ok(None)) | None => break SessionEnd::Lost("Core closed the connection".to_string()),
                    Some(Err(e)) => break SessionEnd::Lost(format!("IPC read error: {e}")),
                },
                out = out_rx.recv() => {
                    let Some(out) = out else { break SessionEnd::Lost("request channel closed".to_string()) };
                    pending.retain(|_, tx| !tx.is_closed()); // callers that timed out
                    let msg = IpcMessage::Request { id: out.id.clone(), body: out.body, r#async: out.run_async };
                    match transport::send_frame(&mut writer, &msg).await {
                        Ok(()) => {
                            pending.insert(out.id, out.response_tx);
                        }
                        // Nothing was written; only this request fails.
                        Err(e @ ControllerError::FrameTooLarge { .. }) => {
                            let _ = out.response_tx.send(Err(e));
                        }
                        Err(e) => break SessionEnd::Lost(format!("IPC write error: {e}")),
                    }
                }
                _ = poll.tick() => {
                    // Protocol 0.3 Cores send events for every view change
                    // (and disconnect lagging clients, forcing a resync), so
                    // only time-dependent Health is polled.  Older Cores miss
                    // events for rooms, nodes and resolved requests.
                    let full = self.handshake_result().await.is_some_and(|h| h.capabilities.full_events);
                    let resources = if full {
                        vec![Resource::Health]
                    } else {
                        vec![Resource::Health, Resource::Room, Resource::Nodes, Resource::PendingAuth]
                    };
                    let _ = self.inner.refresh_tx.send(RefreshMsg::Resources(resources));
                }
                _ = shutdown.changed() => break SessionEnd::Lost("shutdown".to_string()),
            }
        };

        // Fail queued requests (dropping them drops their response senders).
        *self.inner.session.lock().unwrap() = None;
        drop(out_rx);
        drop(pending);
        read_task.abort();
        reason
    }

    /// Handle one frame; `Some` ends the session.
    async fn handle_frame(
        &self,
        frame: Frame,
        pending: &mut HashMap<String, oneshot::Sender<Reply>>,
        last_seq: &mut Option<u64>,
    ) -> Option<SessionEnd> {
        match frame {
            Frame::Message(IpcMessage::Response { id, body, event_seq }) => match pending.remove(&id) {
                Some(tx) => {
                    let _ = tx.send(Ok((body, event_seq)));
                }
                None if id == "handshake" => {
                    if let IpcResponse::Error(e) = &body
                        && e.code == msst_ipc_protocol::errors::INCOMPATIBLE_VERSION
                    {
                        return Some(SessionEnd::Incompatible(e.message.clone()));
                    }
                    tracing::warn!(?body, "unexpected handshake response from Core");
                }
                None => tracing::debug!(%id, "response for unknown or timed-out request"),
            },
            Frame::Message(IpcMessage::Event { body, seq }) => {
                let mut resync = false;
                if let Some(seq) = seq {
                    match *last_seq {
                        Some(last) if seq <= last => {
                            tracing::debug!(seq, last, "ignoring duplicate event");
                            return None;
                        }
                        Some(last) if seq > last + 1 => {
                            tracing::warn!(seq, last, "events were lost; resyncing from Core");
                            resync = true;
                        }
                        _ => {}
                    }
                    *last_seq = Some(seq);
                }
                let refresh = self.inner.store.apply_event_at(&body, seq).await;
                if resync {
                    let _ = self.inner.refresh_tx.send(RefreshMsg::Sync);
                } else if !refresh.is_empty() {
                    let _ = self.inner.refresh_tx.send(RefreshMsg::Resources(refresh));
                }
            }
            Frame::Message(IpcMessage::Request { .. }) => tracing::warn!("ignoring unexpected Request from Core"),
            Frame::Undecodable { kind, id, error } => {
                if kind.as_deref() == Some("response")
                    && let Some(tx) = id.and_then(|id| pending.remove(&id)) {
                        let _ = tx.send(Ok((
                            IpcResponse::Error(IpcError {
                                code: "malformed_response".into(),
                                message: format!("无法解析 Core 的响应: {error}"),
                            }),
                            None,
                        )));
                        return None;
                    }
                // Typically an event type added in a newer protocol minor.
                tracing::warn!(?kind, %error, "ignoring undecodable frame from Core");
            }
        }
        None
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
                // Nothing here consumes streamed log lines; logs are read
                // with GetLogs.  Per connection, so repeated on every sync.
                if self.handshake_result().await.is_some_and(|h| h.capabilities.log_stream)
                    && let Err(e) = self.query(IpcRequest::SetLogStream { enabled: false }).await
                {
                    tracing::debug!(error = %e, "SetLogStream failed");
                }
            }

            let mut errors = Vec::new();
            // A full rebuild takes several queries; Core's `generation`
            // (read via Health first and last) tells whether Core changed
            // in between — if so, query again (bounded).
            for _attempt in 0..3 {
                errors.clear();
                for &r in &set {
                    if let Err(e) = self.refresh_one(r).await {
                        tracing::warn!(resource = ?r, error = %e, "failed to query Core");
                        errors.push(format!("{}: {e}", resource_label(r)));
                    }
                }
                if !sync || !errors.is_empty() {
                    break;
                }
                let before = self.generation().await;
                if let Err(e) = self.refresh_one(Resource::Health).await {
                    errors.push(format!("{}: {e}", resource_label(Resource::Health)));
                    break;
                }
                let after = self.generation().await;
                if before.is_none() || before == after {
                    break;
                }
                tracing::debug!(?before, ?after, "Core changed during resync; querying again");
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

    /// Core's `generation` as last read through Health.
    async fn generation(&self) -> Option<u64> {
        self.inner.store.snapshot().await.health.and_then(|h| h.generation)
    }

    /// Query one resource and apply it; re-query (bounded) if an event made
    /// the result stale while it was in flight.
    async fn refresh_one(&self, r: Resource) -> Result<()> {
        for _ in 0..3 {
            let epoch = self.inner.store.epoch(r).await;
            let (data, event_seq) = self.fetch(r).await?;
            if self.inner.store.apply_resource_at(epoch, data, event_seq).await {
                return Ok(());
            }
        }
        Ok(()) // Still changing; the triggering events scheduled another refresh.
    }

    /// Query one resource; also returns the response's `event_seq`.
    async fn fetch(&self, r: Resource) -> Result<(ResourceData, Option<u64>)> {
        let one = |(v, at): (serde_json::Value, Option<u64>), parse: fn(&serde_json::Value) -> Result<ResourceData>| {
            parse(&v).map(|d| (d, at))
        };
        match r {
            Resource::Health => one(self.query_at(IpcRequest::Health).await?, ResourceData::health),
            Resource::Room => one(self.query_at(IpcRequest::GetSystemState).await?, ResourceData::room),
            Resource::Nodes => {
                let (nodes, nodes_at) = self.query_at(IpcRequest::GetNodes).await?;
                let paths = self.query_at(IpcRequest::GetPathView).await.ok();
                // Two snapshots: only as fresh as the older one.
                let at = match &paths {
                    Some((_, paths_at)) => nodes_at.zip(*paths_at).map(|(a, b)| a.min(b)),
                    None => nodes_at,
                };
                Ok((ResourceData::nodes(&nodes, paths.as_ref().map(|(v, _)| v))?, at))
            }
            Resource::AclRules => one(self.query_at(IpcRequest::GetAclRules).await?, ResourceData::acl_rules),
            Resource::AclGroups => one(self.query_at(IpcRequest::GetAclGroups).await?, ResourceData::acl_groups),
            Resource::PendingAuth => {
                let (v, at) = self.query_at(IpcRequest::GetPendingAuth).await?;
                Ok((ResourceData::pending_auth(&v, now_ms())?, at))
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
        IpcRequest::ClearAclRules => Some(3),
        IpcRequest::GetOperation { .. } | IpcRequest::SetLogStream { .. } => Some(4),
        _ => None,
    }
}

/// Commands slow enough to run as background operations when Core
/// supports them (they wait for the coordinator and a relay).
pub fn prefers_async(req: &IpcRequest) -> bool {
    matches!(req, IpcRequest::CreateRoom { .. } | IpcRequest::JoinRoom { .. })
}

/// Parts of the local view a successful request may have changed.
pub fn affected_resources(req: &IpcRequest) -> Vec<Resource> {
    use Resource::*;
    match req {
        IpcRequest::CreateRoom { .. } | IpcRequest::JoinRoom { .. } | IpcRequest::LeaveRoom { .. } => {
            vec![Room, Nodes, Health]
        }
        IpcRequest::CreateAclRule { .. }
        | IpcRequest::UpdateAclRule { .. }
        | IpcRequest::DeleteAclRule { .. }
        | IpcRequest::ClearAclRules => {
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
        assert_eq!(cfg.reconnect_max_ms, 3_000);
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
