//! Connection state machine — formal state transitions with diagnostics.
//!
//! The [`ConnectionStateMachine`] wraps [`ConnectionStatus`] and enforces
//! valid transitions.  Every transition is recorded with a timestamp for
//! diagnostic purposes.  The UI layer can subscribe to transition events
//! via [`ConnectionStateMachine::subscribe`].

use std::collections::VecDeque;
use std::sync::Arc;
use std::time::Instant;

use tokio::sync::{broadcast, RwLock};

use crate::state::ConnectionStatus;

// ── Transition record ────────────────────────────────────────────────────────

/// A single state transition with timing information.
#[derive(Debug, Clone)]
pub struct Transition {
    pub from: ConnectionStatus,
    pub to: ConnectionStatus,
    pub at: Instant,
    pub reason: Option<String>,
}

// ── Diagnostics ──────────────────────────────────────────────────────────────

/// Cumulative connection diagnostics (reset on successful connect).
#[derive(Debug, Clone, Default, serde::Serialize)]
pub struct ConnectionDiagnostics {
    /// Total number of connection attempts since startup.
    pub total_connect_attempts: u64,
    /// Number of successful connections.
    pub successful_connects: u64,
    /// Number of disconnections (connection lost after being connected).
    pub disconnections: u64,
    /// Number of version handshake failures.
    pub version_mismatches: u64,
    /// Timestamp of the last successful connection (None if never connected).
    #[serde(skip)]
    pub last_connected_at: Option<Instant>,
    /// Timestamp of the last disconnection.
    #[serde(skip)]
    pub last_disconnected_at: Option<Instant>,
    /// Duration of the last connection (from connect to disconnect).
    pub last_connection_duration_ms: Option<u64>,
    /// The most recent connection failure, for display (e.g. "Core not
    /// running", "protocol version mismatch").  Cleared on successful connect.
    pub last_error: Option<String>,
    /// Recent transition history (most recent last, bounded to 64 entries).
    #[serde(skip)]
    pub recent_transitions: VecDeque<Transition>,
}

impl ConnectionDiagnostics {
    /// Returns `true` if the connection has been stable for at least `duration`.
    pub fn is_stable_for(&self, duration: std::time::Duration) -> bool {
        match self.last_connected_at {
            Some(connected_at) => connected_at.elapsed() >= duration,
            None => false,
        }
    }

    /// Returns the connection success rate as a fraction in [0.0, 1.0].
    pub fn success_rate(&self) -> f64 {
        if self.total_connect_attempts == 0 {
            return 0.0;
        }
        self.successful_connects as f64 / self.total_connect_attempts as f64
    }
}

// ── State machine ────────────────────────────────────────────────────────────

/// Observable connection state machine with transition validation.
///
/// Clone-friendly: all state is behind `Arc`.
#[derive(Clone)]
pub struct ConnectionStateMachine {
    inner: Arc<RwLock<Inner>>,
    transition_tx: broadcast::Sender<Transition>,
}

struct Inner {
    status: ConnectionStatus,
    diagnostics: ConnectionDiagnostics,
}

/// Maximum number of transitions to keep in history.
const MAX_TRANSITION_HISTORY: usize = 64;

impl ConnectionStateMachine {
    /// Create a new state machine in the `Disconnected` state.
    pub fn new() -> Self {
        let (transition_tx, _) = broadcast::channel(64);
        Self {
            inner: Arc::new(RwLock::new(Inner {
                status: ConnectionStatus::Disconnected,
                diagnostics: ConnectionDiagnostics::default(),
            })),
            transition_tx,
        }
    }

    /// Get the current connection status.
    pub async fn status(&self) -> ConnectionStatus {
        self.inner.read().await.status
    }

    /// Get a snapshot of the connection diagnostics.
    pub async fn diagnostics(&self) -> ConnectionDiagnostics {
        self.inner.read().await.diagnostics.clone()
    }

    /// Subscribe to state transitions.
    pub fn subscribe(&self) -> broadcast::Receiver<Transition> {
        self.transition_tx.subscribe()
    }

    // ── Transition methods ───────────────────────────────────────────────────

    /// Transition to `Connecting`.
    ///
    /// Valid from: `Disconnected`, `Reconnecting`
    pub async fn begin_connecting(&self) -> bool {
        self.transition(ConnectionStatus::Connecting, None).await
    }

    /// Transition to `Connected`.
    ///
    /// Valid from: `Connecting`
    pub async fn mark_connected(&self) -> bool {
        let mut inner = self.inner.write().await;
        if !is_valid_transition(&inner.status, &ConnectionStatus::Connected) {
            tracing::warn!(
                from = %inner.status,
                "Invalid transition to Connected"
            );
            return false;
        }

        let from = inner.status;
        let now = Instant::now();

        inner.diagnostics.total_connect_attempts += 1;
        inner.diagnostics.successful_connects += 1;
        inner.diagnostics.last_connected_at = Some(now);
        inner.diagnostics.last_error = None;

        inner.status = ConnectionStatus::Connected;

        let trans = Transition {
            from,
            to: ConnectionStatus::Connected,
            at: now,
            reason: None,
        };
        record_transition(&mut inner.diagnostics, trans.clone());
        drop(inner);

        let _ = self.transition_tx.send(trans);
        true
    }

    /// Transition to `Disconnected`.
    ///
    /// Valid from: any state.
    pub async fn mark_disconnected(&self, reason: Option<String>) -> bool {
        let mut inner = self.inner.write().await;
        let from = inner.status;
        let now = Instant::now();

        // Track disconnection metrics if we were previously connected
        if matches!(from, ConnectionStatus::Connected) {
            inner.diagnostics.disconnections += 1;
            inner.diagnostics.last_disconnected_at = Some(now);
            if let Some(connected_at) = inner.diagnostics.last_connected_at {
                inner.diagnostics.last_connection_duration_ms =
                    Some(now.duration_since(connected_at).as_millis() as u64);
            }
        }

        // Track version mismatch
        if reason.as_deref().is_some_and(|r| r.contains("version")) {
            inner.diagnostics.version_mismatches += 1;
        }

        inner.status = ConnectionStatus::Disconnected;

        let trans = Transition {
            from,
            to: ConnectionStatus::Disconnected,
            at: now,
            reason,
        };
        record_transition(&mut inner.diagnostics, trans.clone());
        drop(inner);

        let _ = self.transition_tx.send(trans);
        true
    }

    /// Transition to `Reconnecting`.
    ///
    /// Valid from: `Connecting`, `Connected`
    /// Record a connection failure reason (shown in diagnostics).
    pub async fn set_last_error(&self, error: impl Into<String>) {
        self.inner.write().await.diagnostics.last_error = Some(error.into());
    }

    /// Core speaks an incompatible protocol major version.
    pub async fn mark_incompatible(&self, reason: String) -> bool {
        {
            let mut inner = self.inner.write().await;
            inner.diagnostics.total_connect_attempts += 1;
            inner.diagnostics.version_mismatches += 1;
            inner.diagnostics.last_error = Some(reason.clone());
        }
        self.transition(ConnectionStatus::Incompatible, Some(reason)).await
    }

    pub async fn begin_reconnecting(&self, attempt: u32, next_retry_ms: u64) -> bool {
        let mut inner = self.inner.write().await;
        let target = ConnectionStatus::Reconnecting {
            attempt,
            next_retry_ms,
        };
        if !is_valid_transition(&inner.status, &target) {
            tracing::warn!(
                from = %inner.status,
                "Invalid transition to Reconnecting"
            );
            return false;
        }

        let from = inner.status;
        let now = Instant::now();

        inner.diagnostics.total_connect_attempts += 1;

        inner.status = target;

        let trans = Transition {
            from,
            to: ConnectionStatus::Reconnecting {
                attempt,
                next_retry_ms,
            },
            at: now,
            reason: None,
        };
        record_transition(&mut inner.diagnostics, trans.clone());
        drop(inner);

        let _ = self.transition_tx.send(trans);
        true
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    async fn transition(&self, to: ConnectionStatus, reason: Option<String>) -> bool {
        let mut inner = self.inner.write().await;
        if !is_valid_transition(&inner.status, &to) {
            tracing::warn!(from = %inner.status, to = %to, "Invalid transition");
            return false;
        }

        let from = inner.status;
        let now = Instant::now();

        inner.status = to;

        let trans = Transition {
            from,
            to,
            at: now,
            reason,
        };
        record_transition(&mut inner.diagnostics, trans.clone());
        drop(inner);

        let _ = self.transition_tx.send(trans);
        true
    }
}

impl Default for ConnectionStateMachine {
    fn default() -> Self {
        Self::new()
    }
}

// ── Transition validation ────────────────────────────────────────────────────

/// Returns `true` if the transition from `from` to `to` is valid.
fn is_valid_transition(from: &ConnectionStatus, to: &ConnectionStatus) -> bool {
    use ConnectionStatus::*;
    matches!(
        (from, to),
        // Initial connection
        (Disconnected, Connecting)
        // Reconnection attempt
        | (Reconnecting { .. }, Connecting)
        // Successful connection
        | (Connecting, Connected)
        // Connection lost → reconnecting
        | (Connected, Reconnecting { .. })
        // Connection failed → reconnecting
        | (Connecting, Reconnecting { .. })
        // Any state → disconnected (always valid)
        | (_, Disconnected)
        // Same state (reconnecting with updated attempt count)
        | (Reconnecting { .. }, Reconnecting { .. })
        // Version handshake rejected; retried slowly
        | (Connecting, Incompatible)
        | (Incompatible, Connecting)
    )
}

fn record_transition(diagnostics: &mut ConnectionDiagnostics, trans: Transition) {
    diagnostics.recent_transitions.push_back(trans);
    if diagnostics.recent_transitions.len() > MAX_TRANSITION_HISTORY {
        diagnostics.recent_transitions.pop_front();
    }
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn initial_state_disconnected() {
        let sm = ConnectionStateMachine::new();
        assert_eq!(sm.status().await, ConnectionStatus::Disconnected);
    }

    #[tokio::test]
    async fn valid_connect_cycle() {
        let sm = ConnectionStateMachine::new();

        assert!(sm.begin_connecting().await);
        assert_eq!(sm.status().await, ConnectionStatus::Connecting);

        assert!(sm.mark_connected().await);
        assert_eq!(sm.status().await, ConnectionStatus::Connected);

        assert!(sm.mark_disconnected(Some("connection lost".into())).await);
        assert_eq!(sm.status().await, ConnectionStatus::Disconnected);
    }

    #[tokio::test]
    async fn valid_reconnect_cycle() {
        let sm = ConnectionStateMachine::new();

        // First attempt
        assert!(sm.begin_connecting().await);
        assert!(sm.begin_reconnecting(1, 1000).await);

        let status = sm.status().await;
        assert!(matches!(
            status,
            ConnectionStatus::Reconnecting {
                attempt: 1,
                next_retry_ms: 1000
            }
        ));

        // Second attempt
        assert!(sm.begin_connecting().await);
        assert!(sm.mark_connected().await);
        assert_eq!(sm.status().await, ConnectionStatus::Connected);
    }

    #[tokio::test]
    async fn invalid_transitions_rejected() {
        let sm = ConnectionStateMachine::new();

        // Can't go directly from Disconnected to Connected
        assert!(!sm.mark_connected().await);
        assert_eq!(sm.status().await, ConnectionStatus::Disconnected);

        // Can't go directly from Disconnected to Reconnecting
        assert!(!sm.begin_reconnecting(1, 1000).await);
        assert_eq!(sm.status().await, ConnectionStatus::Disconnected);
    }

    #[tokio::test]
    async fn disconnect_from_any_state() {
        let sm = ConnectionStateMachine::new();

        // From Connecting
        sm.begin_connecting().await;
        assert!(sm.mark_disconnected(None).await);
        assert_eq!(sm.status().await, ConnectionStatus::Disconnected);

        // From Connected
        sm.begin_connecting().await;
        sm.mark_connected().await;
        assert!(sm.mark_disconnected(None).await);
        assert_eq!(sm.status().await, ConnectionStatus::Disconnected);

        // From Reconnecting
        sm.begin_connecting().await;
        sm.begin_reconnecting(1, 1000).await;
        assert!(sm.mark_disconnected(None).await);
        assert_eq!(sm.status().await, ConnectionStatus::Disconnected);
    }

    #[tokio::test]
    async fn diagnostics_track_counts() {
        let sm = ConnectionStateMachine::new();

        // Two connection cycles
        sm.begin_connecting().await;
        sm.mark_connected().await;
        sm.mark_disconnected(None).await;

        sm.begin_connecting().await;
        sm.begin_reconnecting(1, 1000).await;
        sm.begin_connecting().await;
        sm.mark_connected().await;

        let diag = sm.diagnostics().await;
        assert_eq!(diag.successful_connects, 2);
        assert_eq!(diag.disconnections, 1);
        assert_eq!(diag.total_connect_attempts, 3);
        assert!(diag.success_rate() > 0.6);
        assert!(diag.last_connected_at.is_some());
        assert!(diag.last_disconnected_at.is_some());
    }

    #[tokio::test]
    async fn diagnostics_track_version_mismatch() {
        let sm = ConnectionStateMachine::new();

        sm.begin_connecting().await;
        sm.mark_disconnected(Some("version mismatch: Core 1.0.0".into()))
            .await;

        let diag = sm.diagnostics().await;
        assert_eq!(diag.version_mismatches, 1);
    }

    #[tokio::test]
    async fn transition_history_is_bounded() {
        let sm = ConnectionStateMachine::new();

        // Generate more transitions than the limit
        for _ in 0..100 {
            sm.begin_connecting().await;
            sm.mark_disconnected(None).await;
        }

        let diag = sm.diagnostics().await;
        assert_eq!(diag.recent_transitions.len(), MAX_TRANSITION_HISTORY);
    }

    #[tokio::test]
    async fn subscription_delivers_transitions() {
        let sm = ConnectionStateMachine::new();
        let mut rx = sm.subscribe();

        sm.begin_connecting().await;

        let trans = tokio::time::timeout(std::time::Duration::from_millis(100), rx.recv())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(trans.from, ConnectionStatus::Disconnected);
        assert_eq!(trans.to, ConnectionStatus::Connecting);
    }

    #[tokio::test]
    async fn stability_check() {
        let sm = ConnectionStateMachine::new();
        sm.begin_connecting().await;
        sm.mark_connected().await;

        // Not stable for 1 second immediately
        let diag = sm.diagnostics().await;
        assert!(!diag.is_stable_for(std::time::Duration::from_secs(1)));

        // But "stable" for 0 seconds
        assert!(diag.is_stable_for(std::time::Duration::ZERO));
    }
}