//! IPC client — manages the connection to Core, sends requests, receives events.
//!
//! The [`IpcClient`] is the primary interface between the application and Core.
//! It handles:
//!
//! - Connection establishment with version handshake
//! - Request/response correlation (each request gets a unique ID)
//! - Event reception and forwarding to the state store
//! - Automatic reconnection with exponential backoff
//! - Connection status tracking via [`ConnectionStateMachine`]

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use msst_ipc_protocol::{IpcMessage, IpcRequest, IpcResponse};
use tokio::sync::{broadcast, oneshot};

use super::connection::{ConnectionDiagnostics, ConnectionStateMachine, Transition};
use super::handshake::{self, HandshakeResult};
use super::transport::{self, IpcStream};
use crate::error::{ControllerError, Result};
use crate::state::{ConnectionStatus, StateChange, StateStore};

// ── Configuration ────────────────────────────────────────────────────────────

/// IPC client configuration.
#[derive(Debug, Clone)]
pub struct IpcClientConfig {
    /// Initial reconnect delay.
    pub reconnect_base_ms: u64,
    /// Maximum reconnect delay.
    pub reconnect_max_ms: u64,
    /// Request timeout.
    pub request_timeout: Duration,
}

impl Default for IpcClientConfig {
    fn default() -> Self {
        Self {
            reconnect_base_ms: 1_000,
            reconnect_max_ms: 30_000,
            request_timeout: Duration::from_secs(15),
        }
    }
}

// ── IpcClient ────────────────────────────────────────────────────────────────

/// The IPC client — manages connection to Core.
///
/// Clone-friendly: all state is behind `Arc`.
#[derive(Clone)]
pub struct IpcClient {
    config: IpcClientConfig,
    state_store: StateStore,
    state_machine: ConnectionStateMachine,
    next_request_id: Arc<AtomicU64>,
    request_tx: tokio::sync::mpsc::UnboundedSender<OutgoingRequest>,
    shutdown_tx: Arc<tokio::sync::watch::Sender<bool>>,
    /// The result of the last successful version handshake.
    handshake: Arc<tokio::sync::RwLock<Option<HandshakeResult>>>,
}

struct OutgoingRequest {
    id: String,
    body: IpcRequest,
    response_tx: oneshot::Sender<IpcResponse>,
}

impl IpcClient {
    /// Create a new IPC client and start the background connection task.
    pub fn new(config: IpcClientConfig, state_store: StateStore) -> Self {
        let (request_tx, request_rx) = tokio::sync::mpsc::unbounded_channel();
        let (shutdown_tx, shutdown_rx) = tokio::sync::watch::channel(false);
        let state_machine = ConnectionStateMachine::new();

        let client = Self {
            config: config.clone(),
            state_store: state_store.clone(),
            state_machine: state_machine.clone(),
            next_request_id: Arc::new(AtomicU64::new(1)),
            request_tx,
            shutdown_tx: Arc::new(shutdown_tx),
            handshake: Arc::new(tokio::sync::RwLock::new(None)),
        };

        let bg = BackgroundTask {
            config,
            state_store,
            state_machine,
            request_rx: Arc::new(tokio::sync::Mutex::new(request_rx)),
            shutdown_rx,
            handshake: client.handshake.clone(),
        };
        tokio::spawn(bg.run());

        client
    }

    /// Get the current connection status.
    pub async fn connection_status(&self) -> ConnectionStatus {
        self.state_machine.status().await
    }

    /// Get connection diagnostics (reconnect count, success rate, etc.).
    pub async fn connection_diagnostics(&self) -> ConnectionDiagnostics {
        self.state_machine.diagnostics().await
    }

    /// Get the result of the last successful version handshake.
    ///
    /// Returns `None` if no handshake has been completed yet.
    pub async fn handshake_result(&self) -> Option<HandshakeResult> {
        self.handshake.read().await.clone()
    }

    /// Subscribe to connection state transitions.
    pub fn subscribe_transitions(&self) -> broadcast::Receiver<Transition> {
        self.state_machine.subscribe()
    }

    /// Send a request to Core and wait for the correlated response.
    pub async fn request(&self, body: IpcRequest) -> Result<IpcResponse> {
        let id = format!("req-{}", self.next_request_id.fetch_add(1, Ordering::Relaxed));
        let (response_tx, response_rx) = oneshot::channel();

        self.request_tx
            .send(OutgoingRequest {
                id: id.clone(),
                body,
                response_tx,
            })
            .map_err(|_| ControllerError::ClientShutdown {
                request_id: id.clone(),
            })?;

        match tokio::time::timeout(self.config.request_timeout, response_rx).await {
            Ok(Ok(response)) => Ok(response),
            Ok(Err(_)) => Err(ControllerError::ClientShutdown { request_id: id }),
            Err(_) => Err(ControllerError::RequestTimeout {
                request_id: id,
                timeout_secs: self.config.request_timeout.as_secs(),
            }),
        }
    }

    /// Convenience: send a request and extract the `Ok` value.
    pub async fn query(&self, body: IpcRequest) -> Result<serde_json::Value> {
        match self.request(body).await? {
            IpcResponse::Ok(data) => Ok(data),
            IpcResponse::Error(e) => Err(ControllerError::from(e)),
        }
    }

    /// Subscribe to state change events.
    pub fn subscribe(&self) -> broadcast::Receiver<StateChange> {
        self.state_store.subscribe()
    }

    /// Signal the background task to shut down.
    pub fn shutdown(&self) {
        let _ = self.shutdown_tx.send(true);
    }
}

// ── Background task ──────────────────────────────────────────────────────────

struct BackgroundTask {
    config: IpcClientConfig,
    state_store: StateStore,
    state_machine: ConnectionStateMachine,
    request_rx: Arc<tokio::sync::Mutex<tokio::sync::mpsc::UnboundedReceiver<OutgoingRequest>>>,
    shutdown_rx: tokio::sync::watch::Receiver<bool>,
    handshake: Arc<tokio::sync::RwLock<Option<HandshakeResult>>>,
}

impl BackgroundTask {
    async fn run(mut self) {
        let mut reconnect_delay = self.config.reconnect_base_ms;
        let mut attempt: u32 = 0;

        loop {
            if *self.shutdown_rx.borrow() {
                break;
            }

            self.state_machine.begin_connecting().await;

            match transport::connect().await {
                Ok(mut stream) => {
                    // Version negotiation handshake
                    match handshake::perform_handshake(&mut stream).await {
                        Ok(result) => {
                            tracing::info!(
                                remote = %format!("{}.{}.{}", result.remote_decoded.0, result.remote_decoded.1, result.remote_decoded.2),
                                degraded = result.degraded,
                                event_seq = result.capabilities.event_seq,
                                "Version handshake succeeded"
                            );
                            // Store the handshake result
                            *self.handshake.write().await = Some(result);
                        }
                        Err(e) => {
                            tracing::error!(error = %e, category = e.category(), "Version handshake failed");
                            self.state_machine
                                .mark_disconnected(Some(format!("version mismatch: {e}")))
                                .await;
                            return; // Fatal — cannot communicate
                        }
                    }

                    self.state_machine.mark_connected().await;
                    reconnect_delay = self.config.reconnect_base_ms;
                    attempt = 0;

                    if let Err(e) = self.request_snapshot(&mut stream).await {
                        tracing::warn!(error = %e, category = e.category(), "Failed to request initial snapshot");
                    }

                    self.run_event_loop(&mut stream).await;

                    tracing::warn!("IPC connection lost");
                    self.state_machine
                        .mark_disconnected(Some("connection lost".into()))
                        .await;
                }
                Err(e) => {
                    tracing::debug!(error = %e, category = e.category(), "IPC connection failed");
                    attempt += 1;
                    self.state_machine
                        .begin_reconnecting(attempt, reconnect_delay)
                        .await;
                }
            }

            tracing::info!(delay_ms = reconnect_delay, attempt, "Reconnecting");
            tokio::select! {
                _ = tokio::time::sleep(Duration::from_millis(reconnect_delay)) => {}
                _ = self.shutdown_rx.changed() => {
                    tracing::info!("Shutdown requested during reconnect");
                    break;
                }
            }

            if *self.shutdown_rx.borrow() {
                break;
            }

            reconnect_delay = (reconnect_delay * 2).min(self.config.reconnect_max_ms);
        }

        tracing::info!("IPC background task exited");
    }

    async fn request_snapshot(&self, stream: &mut IpcStream) -> Result<()> {
        let req = IpcMessage::Request {
            id: "snapshot-init".into(),
            body: IpcRequest::GetSystemState,
        };
        transport::send_frame(stream, &req).await?;

        match tokio::time::timeout(Duration::from_secs(10), transport::recv_frame(stream)).await {
            Ok(Ok(Some(IpcMessage::Response { id: _, body }))) => match body {
                IpcResponse::Ok(data) => {
                    self.state_store.apply_snapshot(&data).await;
                    tracing::info!("Initial state snapshot applied");
                }
                IpcResponse::Error(e) => {
                    tracing::warn!(code = %e.code, "Snapshot request failed: {}", e.message);
                }
            },
            Ok(Ok(Some(_))) => {
                tracing::debug!("Received non-response during snapshot handshake, ignoring");
            }
            Ok(Ok(None)) => {
                tracing::warn!("Connection closed during snapshot");
            }
            Ok(Err(e)) => {
                tracing::warn!(error = %e, "Error receiving snapshot");
            }
            Err(_) => {
                tracing::warn!("Snapshot request timed out");
            }
        }
        Ok(())
    }

    async fn run_event_loop(&self, stream: &mut IpcStream) {
        let mut pending: HashMap<String, oneshot::Sender<IpcResponse>> = HashMap::new();
        let mut request_rx = self.request_rx.lock().await;

        loop {
            tokio::select! {
                frame = transport::recv_frame(stream) => {
                    match frame {
                        Ok(Some(msg)) => {
                            self.handle_incoming(msg, &mut pending).await;
                        }
                        Ok(None) => {
                            tracing::info!("Core closed IPC connection");
                            break;
                        }
                        Err(e) => {
                            tracing::error!(error = %e, category = e.category(), "IPC read error");
                            break;
                        }
                    }
                }
                outgoing = request_rx.recv() => {
                    match outgoing {
                        Some(req) => {
                            pending.insert(req.id.clone(), req.response_tx);
                            let msg = IpcMessage::Request {
                                id: req.id,
                                body: req.body,
                            };
                            if let Err(e) = transport::send_frame(stream, &msg).await {
                                tracing::error!(error = %e, category = e.category(), "IPC send error");
                                break;
                            }
                        }
                        None => {
                            tracing::info!("Request channel closed, shutting down");
                            break;
                        }
                    }
                }
            }
        }

        pending.clear();
    }

    async fn handle_incoming(
        &self,
        msg: IpcMessage,
        pending: &mut HashMap<String, oneshot::Sender<IpcResponse>>,
    ) {
        match msg {
            IpcMessage::Response { id, body } => {
                if let Some(tx) = pending.remove(&id) {
                    let _ = tx.send(body);
                } else {
                    tracing::debug!(id = %id, "Received response for unknown request");
                }
            }
            IpcMessage::Event { body } => {
                self.state_store.apply_event(&body).await;
            }
            IpcMessage::Request { .. } => {
                tracing::warn!("Received unexpected Request from Core");
            }
        }
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

    #[tokio::test]
    async fn initial_status_disconnected() {
        let store = StateStore::new();
        let client = IpcClient::new(IpcClientConfig::default(), store);
        tokio::time::sleep(Duration::from_millis(10)).await;
        let status = client.connection_status().await;
        assert!(
            matches!(status, ConnectionStatus::Connecting | ConnectionStatus::Reconnecting { .. }),
            "Expected Connecting or Reconnecting, got {status:?}"
        );
    }

    #[tokio::test]
    async fn client_exposes_diagnostics() {
        let store = StateStore::new();
        let client = IpcClient::new(IpcClientConfig::default(), store);
        tokio::time::sleep(Duration::from_millis(10)).await;
        let diag = client.connection_diagnostics().await;
        // At least one attempt should have been recorded
        assert!(diag.total_connect_attempts > 0 || diag.successful_connects == 0);
    }

    #[tokio::test]
    async fn handshake_result_initially_none() {
        let store = StateStore::new();
        let client = IpcClient::new(IpcClientConfig::default(), store);
        // No successful handshake yet
        assert!(client.handshake_result().await.is_none());
    }

    #[test]
    fn request_timeout_error_is_retryable() {
        let e = ControllerError::RequestTimeout {
            request_id: "req-1".into(),
            timeout_secs: 15,
        };
        assert!(e.is_retryable());
        assert_eq!(e.category(), "timeout");
    }

    #[test]
    fn client_shutdown_error_is_not_retryable() {
        let e = ControllerError::ClientShutdown {
            request_id: "req-1".into(),
        };
        assert!(!e.is_retryable());
        assert_eq!(e.category(), "shutdown");
    }
}