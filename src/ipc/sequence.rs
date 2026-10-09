//! Event sequence tracking and gap detection.
//!
//! This module monitors the event stream for gaps that indicate lost events.
//! When a gap is detected, it signals that a state re-sync is needed.
//!
//! ## Design: Graceful Degradation
//!
//! The current IPC protocol (v0.1.0) does **not** include sequence numbers
//! on events.  This module works in two modes:
//!
//! - **Without seq** (current): Tracks event count only. No gap detection
//!   possible, but the counter is available for diagnostics.
//! - **With seq** (future): When Core adds a `seq` field to `IpcEvent`,
//!   this module will detect gaps and trigger re-sync automatically.
//!
//! The transition requires a protocol change in `msst-ipc-protocol` (see
//! `PROTOCOL_CHANGES.md`).  The Controller is forward-compatible: it will
//! parse `seq` if present and ignore it if absent.

use std::sync::Arc;

use tokio::sync::{broadcast, RwLock};

// ── Sequence tracker ─────────────────────────────────────────────────────────

/// Tracks event sequence numbers and detects gaps.
///
/// Clone-friendly: all state is behind `Arc`.
#[derive(Clone)]
pub struct SequenceTracker {
    inner: Arc<RwLock<Inner>>,
    resync_tx: broadcast::Sender<ResyncRequest>,
}

struct Inner {
    /// The last sequence number we've seen (None if no seq has been received yet).
    last_seq: Option<u64>,
    /// Total events processed (regardless of seq presence).
    events_processed: u64,
    /// Number of gaps detected.
    gaps_detected: u64,
    /// Number of re-syncs requested.
    resyncs_requested: u64,
}

/// A re-sync request emitted when a gap is detected.
#[derive(Debug, Clone)]
pub struct ResyncRequest {
    /// The expected next sequence number.
    pub expected_seq: u64,
    /// The actual sequence number received (which was higher than expected).
    pub received_seq: u64,
    /// Number of events estimated to have been lost.
    pub gap_size: u64,
}

/// Diagnostics snapshot from the sequence tracker.
#[derive(Debug, Clone)]
pub struct SequenceDiagnostics {
    pub last_seq: Option<u64>,
    pub events_processed: u64,
    pub gaps_detected: u64,
    pub resyncs_requested: u64,
}

impl SequenceTracker {
    /// Create a new sequence tracker.
    pub fn new() -> Self {
        let (resync_tx, _) = broadcast::channel(16);
        Self {
            inner: Arc::new(RwLock::new(Inner {
                last_seq: None,
                events_processed: 0,
                gaps_detected: 0,
                resyncs_requested: 0,
            })),
            resync_tx,
        }
    }

    /// Record an event, optionally with a sequence number.
    ///
    /// Returns `Some(ResyncRequest)` if a gap is detected, `None` otherwise.
    pub async fn record_event(&self, seq: Option<u64>) -> Option<ResyncRequest> {
        let mut inner = self.inner.write().await;
        inner.events_processed += 1;

        let Some(seq) = seq else {
            // No sequence number — protocol v0.1.0 behavior.
            // Just count the event, no gap detection possible.
            return None;
        };

        let resync = match inner.last_seq {
            None => {
                // First sequenced event — accept unconditionally.
                None
            }
            Some(last) if seq == last + 1 => {
                // Sequential — no gap.
                None
            }
            Some(last) if seq <= last => {
                // Out-of-order or duplicate — log but don't resync.
                tracing::debug!(
                    last_seq = last,
                    received_seq = seq,
                    "Out-of-order event (duplicate or reorder)"
                );
                None
            }
            Some(last) => {
                // Gap detected — events were lost.
                let gap_size = seq - last - 1;
                inner.gaps_detected += 1;
                tracing::warn!(
                    last_seq = last,
                    received_seq = seq,
                    gap_size,
                    "Event gap detected — events lost"
                );
                Some(ResyncRequest {
                    expected_seq: last + 1,
                    received_seq: seq,
                    gap_size,
                })
            }
        };

        inner.last_seq = Some(seq);

        if let Some(ref req) = resync {
            inner.resyncs_requested += 1;
            let _ = self.resync_tx.send(req.clone());
        }

        resync
    }

    /// Reset the tracker (e.g., after applying a fresh snapshot).
    pub async fn reset(&self) {
        let mut inner = self.inner.write().await;
        inner.last_seq = None;
        // Don't reset counters — they're cumulative diagnostics.
    }

    /// Get diagnostics.
    pub async fn diagnostics(&self) -> SequenceDiagnostics {
        let inner = self.inner.read().await;
        SequenceDiagnostics {
            last_seq: inner.last_seq,
            events_processed: inner.events_processed,
            gaps_detected: inner.gaps_detected,
            resyncs_requested: inner.resyncs_requested,
        }
    }

    /// Subscribe to re-sync requests.
    pub fn subscribe_resync(&self) -> broadcast::Receiver<ResyncRequest> {
        self.resync_tx.subscribe()
    }
}

impl Default for SequenceTracker {
    fn default() -> Self {
        Self::new()
    }
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn no_seq_mode_counts_events() {
        let tracker = SequenceTracker::new();

        // Events without seq (current protocol behavior)
        for _ in 0..10 {
            assert!(tracker.record_event(None).await.is_none());
        }

        let diag = tracker.diagnostics().await;
        assert_eq!(diag.events_processed, 10);
        assert_eq!(diag.gaps_detected, 0);
        assert!(diag.last_seq.is_none());
    }

    #[tokio::test]
    async fn sequential_events_no_gap() {
        let tracker = SequenceTracker::new();

        for seq in 1..=100 {
            assert!(tracker.record_event(Some(seq)).await.is_none());
        }

        let diag = tracker.diagnostics().await;
        assert_eq!(diag.events_processed, 100);
        assert_eq!(diag.gaps_detected, 0);
        assert_eq!(diag.last_seq, Some(100));
    }

    #[tokio::test]
    async fn gap_detection() {
        let tracker = SequenceTracker::new();

        // Send events 1-5
        for seq in 1..=5 {
            assert!(tracker.record_event(Some(seq)).await.is_none());
        }

        // Skip 6-9, receive 10
        let resync = tracker.record_event(Some(10)).await;
        assert!(resync.is_some());
        let req = resync.unwrap();
        assert_eq!(req.expected_seq, 6);
        assert_eq!(req.received_seq, 10);
        assert_eq!(req.gap_size, 4);

        let diag = tracker.diagnostics().await;
        assert_eq!(diag.gaps_detected, 1);
        assert_eq!(diag.resyncs_requested, 1);
    }

    #[tokio::test]
    async fn duplicate_event_ignored() {
        let tracker = SequenceTracker::new();

        tracker.record_event(Some(1)).await;
        tracker.record_event(Some(2)).await;
        // Duplicate of 2
        assert!(tracker.record_event(Some(2)).await.is_none());

        let diag = tracker.diagnostics().await;
        assert_eq!(diag.events_processed, 3);
        assert_eq!(diag.gaps_detected, 0);
    }

    #[tokio::test]
    async fn out_of_order_ignored() {
        let tracker = SequenceTracker::new();

        tracker.record_event(Some(1)).await;
        tracker.record_event(Some(3)).await; // gap
        // Receive 2 (out of order) — should not trigger another resync
        assert!(tracker.record_event(Some(2)).await.is_none());

        let diag = tracker.diagnostics().await;
        assert_eq!(diag.gaps_detected, 1); // Only the 1→3 gap
    }

    #[tokio::test]
    async fn reset_clears_seq_but_not_counters() {
        let tracker = SequenceTracker::new();

        for seq in 1..=10 {
            tracker.record_event(Some(seq)).await;
        }
        tracker.record_event(Some(20)).await; // gap

        tracker.reset().await;

        let diag = tracker.diagnostics().await;
        assert!(diag.last_seq.is_none());
        // Counters preserved
        assert_eq!(diag.events_processed, 11);
        assert_eq!(diag.gaps_detected, 1);

        // Can continue tracking from fresh
        assert!(tracker.record_event(Some(1)).await.is_none());
    }

    #[tokio::test]
    async fn resync_subscription() {
        let tracker = SequenceTracker::new();
        let mut rx = tracker.subscribe_resync();

        tracker.record_event(Some(1)).await;
        tracker.record_event(Some(2)).await;
        // Gap: skip 3-5
        tracker.record_event(Some(6)).await;

        let req = tokio::time::timeout(std::time::Duration::from_millis(100), rx.recv())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(req.expected_seq, 3);
        assert_eq!(req.received_seq, 6);
        assert_eq!(req.gap_size, 3);
    }

    #[tokio::test]
    async fn mixed_seq_and_no_seq() {
        let tracker = SequenceTracker::new();

        // Some events without seq (backward compat)
        tracker.record_event(None).await;
        tracker.record_event(None).await;

        // Then events with seq
        assert!(tracker.record_event(Some(1)).await.is_none());
        assert!(tracker.record_event(Some(2)).await.is_none());

        let diag = tracker.diagnostics().await;
        assert_eq!(diag.events_processed, 4);
        assert_eq!(diag.last_seq, Some(2));
    }
}