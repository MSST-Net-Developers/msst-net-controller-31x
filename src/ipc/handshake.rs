//! Version negotiation — handshake protocol and capability exchange.
//!
//! ## Handshake Protocol
//!
//! After the transport connection is established (Unix socket or named pipe),
//! Core and Controller perform a version handshake before any `IpcMessage`
//! frames are exchanged:
//!
//! ```text
//! Core  → Controller:  4 bytes  PROTOCOL_VERSION (u32 BE)
//! Controller → Core:  4 bytes  PROTOCOL_VERSION (u32 BE)
//! ```
//!
//! Both sides send their version simultaneously (no wait-for-reply).  After
//! sending, each side reads the other's version and decides:
//!
//! 1. **Compatible** (same major): proceed to normal frame I/O.
//! 2. **Degraded** (local minor > remote minor): proceed, but disable
//!    features that require the newer minor version.
//! 3. **Incompatible** (different major): close the connection.
//!
//! ## Design Decisions
//!
//! - The handshake uses raw 4-byte frames, not JSON `IpcMessage`, to avoid
//!   chicken-and-egg problems with version-dependent serialization.
//! - Both sides send first, then read.  This avoids a deadlock where each
//!   side waits for the other to send.
//! - The handshake has a 5-second timeout to prevent hung connections.

use crate::error::{ControllerError, Result};
use crate::ipc::transport::IpcStream;

// ── Handshake result ─────────────────────────────────────────────────────────

/// The outcome of a version negotiation handshake.
#[derive(Debug, Clone)]
pub struct HandshakeResult {
    /// The remote side's protocol version.
    pub remote_version: u32,
    /// Decoded remote version (major, minor, patch).
    pub remote_decoded: (u32, u32, u32),
    /// Whether the remote version is fully compatible (same major).
    pub compatible: bool,
    /// Whether to operate in degraded mode (local has newer features).
    pub degraded: bool,
    /// Features available based on the negotiated version.
    pub capabilities: Capabilities,
}

/// Feature flags derived from the negotiated protocol version.
///
/// Each field corresponds to a minor version addition.  When the remote
/// side is on an older minor version, the corresponding feature is disabled.
#[derive(Debug, Clone, Default)]
pub struct Capabilities {
    /// Whether the remote side supports event sequence numbers (v0.2+).
    pub event_seq: bool,
    /// Whether the remote side supports `GetEventsSince` (v0.2+).
    pub events_since: bool,
    /// Whether the remote side supports extended auth decision types (v0.3+).
    pub extended_auth: bool,
}

impl Capabilities {
    /// Derive capabilities from a remote protocol version.
    pub fn from_version(remote: (u32, u32, u32)) -> Self {
        let (_, minor, _) = remote;
        Self {
            event_seq: minor >= 2,
            events_since: minor >= 2,
            extended_auth: minor >= 3,
        }
    }
}

// ── Handshake execution ──────────────────────────────────────────────────────

/// Perform the version handshake from the Controller side.
///
/// Sends our version, reads Core's version, and returns the negotiated result.
///
/// # Errors
///
/// Returns `ControllerError::VersionMismatch` if the major versions differ.
/// Returns `ControllerError::Io` if the handshake times out or the connection drops.
pub async fn perform_handshake(stream: &mut IpcStream) -> Result<HandshakeResult> {
    use msst_ipc_protocol::PROTOCOL_VERSION;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let local_version = PROTOCOL_VERSION;
    let local_bytes = local_version.to_be_bytes();

    // Send our version first, then read theirs.
    // We use write_all + read_exact directly on the stream rather than
    // going through send_frame/recv_frame, since the handshake uses raw
    // 4-byte version packets, not JSON IpcMessage frames.
    let write_result = match stream {
        IpcStream::Unix(s) => s.write_all(&local_bytes).await,
        #[cfg(windows)]
        IpcStream::Pipe(s) => s.write_all(&local_bytes).await,
    };
    write_result.map_err(|e| ControllerError::Io(e.to_string()))?;

    // Read Core's version with a timeout.
    let mut remote_bytes = [0u8; 4];
    let read_result = match stream {
        IpcStream::Unix(s) => {
            tokio::time::timeout(
                std::time::Duration::from_secs(5),
                s.read_exact(&mut remote_bytes),
            )
            .await
        }
        #[cfg(windows)]
        IpcStream::Pipe(s) => {
            tokio::time::timeout(
                std::time::Duration::from_secs(5),
                s.read_exact(&mut remote_bytes),
            )
            .await
        }
    };

    match read_result {
        Ok(Ok(_)) => {}
        Ok(Err(e)) => return Err(ControllerError::Io(e.to_string())),
        Err(_) => return Err(ControllerError::Io("version handshake timed out".into())),
    }

    let remote_version = u32::from_be_bytes(remote_bytes);
    let remote_decoded = msst_ipc_protocol::version::decode_version(remote_version);
    let local_decoded = msst_ipc_protocol::version::decode_version(local_version);

    tracing::info!(
        local = %format!("{}.{}.{}", local_decoded.0, local_decoded.1, local_decoded.2),
        remote = %format!("{}.{}.{}", remote_decoded.0, remote_decoded.1, remote_decoded.2),
        "Version handshake"
    );

    let compatible = msst_ipc_protocol::version::is_compatible(remote_version);
    let degraded = compatible && remote_decoded.1 < local_decoded.1;

    if !compatible {
        return Err(ControllerError::VersionMismatch {
            remote: format!("{}.{}.{}", remote_decoded.0, remote_decoded.1, remote_decoded.2),
            local: format!("{}.{}.{}", local_decoded.0, local_decoded.1, local_decoded.2),
        });
    }

    let capabilities = Capabilities::from_version(remote_decoded);

    if degraded {
        tracing::warn!(
            remote_minor = remote_decoded.1,
            local_minor = local_decoded.1,
            "Operating in degraded mode — some features unavailable"
        );
    }

    Ok(HandshakeResult {
        remote_version,
        remote_decoded,
        compatible,
        degraded,
        capabilities,
    })
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn capabilities_from_version() {
        // v0.1.0 — no extra features
        let caps = Capabilities::from_version((0, 1, 0));
        assert!(!caps.event_seq);
        assert!(!caps.events_since);
        assert!(!caps.extended_auth);

        // v0.2.0 — event_seq + events_since
        let caps = Capabilities::from_version((0, 2, 0));
        assert!(caps.event_seq);
        assert!(caps.events_since);
        assert!(!caps.extended_auth);

        // v0.3.0 — all features
        let caps = Capabilities::from_version((0, 3, 0));
        assert!(caps.event_seq);
        assert!(caps.events_since);
        assert!(caps.extended_auth);

        // v0.2.5 — patch doesn't matter
        let caps = Capabilities::from_version((0, 2, 5));
        assert!(caps.event_seq);
        assert!(caps.events_since);
        assert!(!caps.extended_auth);
    }

    #[test]
    fn capabilities_from_future_version() {
        // v0.99.99 — all known features enabled
        let caps = Capabilities::from_version((0, 99, 99));
        assert!(caps.event_seq);
        assert!(caps.events_since);
        assert!(caps.extended_auth);
    }

    #[test]
    fn handshake_result_fields() {
        // Simulate a compatible, non-degraded result
        let result = HandshakeResult {
            remote_version: msst_ipc_protocol::version::make_version(0, 1, 0),
            remote_decoded: (0, 1, 0),
            compatible: true,
            degraded: false,
            capabilities: Capabilities::from_version((0, 1, 0)),
        };
        assert!(result.compatible);
        assert!(!result.degraded);
        assert!(!result.capabilities.event_seq);

        // Simulate a degraded result (remote is older minor)
        let result = HandshakeResult {
            remote_version: msst_ipc_protocol::version::make_version(0, 1, 0),
            remote_decoded: (0, 1, 0),
            compatible: true,
            degraded: true,
            capabilities: Capabilities::from_version((0, 1, 0)),
        };
        assert!(result.compatible);
        assert!(result.degraded);
    }
}