//! Version negotiation — the raw 4-byte handshake before any JSON frame.
//!
//! ```text
//! Core       → Controller:  4 bytes  PROTOCOL_VERSION (u32 BE)
//! Controller → Core:        4 bytes  PROTOCOL_VERSION (u32 BE)
//! ```
//!
//! Both sides send first, then read (no deadlock).  Compatibility is decided
//! by `msst_ipc_protocol::version::is_compatible` (same major).  Within a
//! major version, minor versions are additive: a feature is only used when
//! the remote minor version is known to include it (see [`Capabilities`]).

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use crate::error::{ControllerError, Result};

const HANDSHAKE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);

/// The outcome of a version negotiation handshake.
#[derive(Debug, Clone, serde::Serialize)]
pub struct HandshakeResult {
    /// The remote side's protocol version.
    pub remote_version: u32,
    /// Decoded remote version (major, minor, patch).
    pub remote_decoded: (u32, u32, u32),
    /// Whether the remote side is on an older minor version than this build.
    pub degraded: bool,
    /// Features available based on the negotiated version.
    pub capabilities: Capabilities,
}

/// Requests that only exist from a given protocol minor version on.
///
/// Only versions actually published in `msst-ipc-protocol` are listed here;
/// proposed-but-unapproved features (e.g. event sequence numbers) are not.
#[derive(Debug, Clone, Default, serde::Serialize)]
pub struct Capabilities {
    /// `GetServices` (protocol 0.2).
    pub get_services: bool,
    /// `ClearAclRules` (protocol 0.3).
    pub clear_acl_rules: bool,
    /// Events cover every view change and `RoomStateChanged` /
    /// `NodeStateChanged` carry full state (protocol 0.3).
    pub full_events: bool,
    /// Events carry `seq`, responses `event_seq`; `Health` reports
    /// `ipc_role` (protocol 0.4).
    pub event_seq: bool,
    /// Commands can run as background operations (`async`,
    /// `GetOperation`, `OperationProgress` / `OperationCompleted`; 0.4).
    pub operations: bool,
    /// `SetLogStream` (protocol 0.4).
    pub log_stream: bool,
}

impl Capabilities {
    /// Derive capabilities from a remote protocol version.
    pub fn from_version(remote: (u32, u32, u32)) -> Self {
        let (_, minor, _) = remote;
        Self {
            get_services: minor >= 2,
            clear_acl_rules: minor >= 3,
            full_events: minor >= 3,
            event_seq: minor >= 4,
            operations: minor >= 4,
            log_stream: minor >= 4,
        }
    }
}

/// Perform the version handshake from the Controller side.
///
/// # Errors
///
/// `VersionMismatch` if the major versions differ; `Io` on timeout or I/O failure.
pub async fn perform_handshake<S: AsyncRead + AsyncWrite + Unpin + ?Sized>(stream: &mut S) -> Result<HandshakeResult> {
    use msst_ipc_protocol::PROTOCOL_VERSION;
    use msst_ipc_protocol::version::{decode_version, is_compatible};

    stream.write_all(&PROTOCOL_VERSION.to_be_bytes()).await?;
    stream.flush().await?;

    let mut remote_bytes = [0u8; 4];
    match tokio::time::timeout(HANDSHAKE_TIMEOUT, stream.read_exact(&mut remote_bytes)).await {
        Ok(Ok(_)) => {}
        Ok(Err(e)) => return Err(ControllerError::Io(format!("version handshake failed: {e}"))),
        Err(_) => return Err(ControllerError::Io("version handshake timed out".into())),
    }

    let remote_version = u32::from_be_bytes(remote_bytes);
    let remote_decoded = decode_version(remote_version);
    let local_decoded = decode_version(PROTOCOL_VERSION);
    let fmt = |v: (u32, u32, u32)| format!("{}.{}.{}", v.0, v.1, v.2);

    if !is_compatible(remote_version) {
        return Err(ControllerError::VersionMismatch { remote: fmt(remote_decoded), local: fmt(local_decoded) });
    }
    let degraded = remote_decoded.1 < local_decoded.1;
    if degraded {
        tracing::warn!(remote = %fmt(remote_decoded), local = %fmt(local_decoded), "Core uses an older protocol minor version; newer requests are unavailable");
    }
    Ok(HandshakeResult { remote_version, remote_decoded, degraded, capabilities: Capabilities::from_version(remote_decoded) })
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use msst_ipc_protocol::PROTOCOL_VERSION;
    use msst_ipc_protocol::version::make_version;

    #[test]
    fn capabilities_follow_published_versions() {
        assert!(!Capabilities::from_version((0, 1, 0)).get_services);
        assert!(Capabilities::from_version((0, 2, 0)).get_services);
        assert!(Capabilities::from_version((0, 9, 0)).get_services);
        let v2 = Capabilities::from_version((0, 2, 0));
        assert!(!v2.clear_acl_rules && !v2.full_events);
        let v3 = Capabilities::from_version((0, 3, 0));
        assert!(v3.clear_acl_rules && v3.full_events);
        assert!(!v3.event_seq && !v3.operations && !v3.log_stream);
        let v4 = Capabilities::from_version((0, 4, 0));
        assert!(v4.event_seq && v4.operations && v4.log_stream);
    }

    async fn run(core_version: u32) -> (Result<HandshakeResult>, u32) {
        let (mut ctrl, mut core) = tokio::io::duplex(64);
        let core_side = tokio::spawn(async move {
            core.write_all(&core_version.to_be_bytes()).await.unwrap();
            let mut b = [0u8; 4];
            core.read_exact(&mut b).await.unwrap();
            u32::from_be_bytes(b)
        });
        let r = perform_handshake(&mut ctrl).await;
        (r, core_side.await.unwrap())
    }

    #[tokio::test]
    async fn compatible_handshake_sends_our_version() {
        let (r, sent) = run(PROTOCOL_VERSION).await;
        assert_eq!(sent, PROTOCOL_VERSION);
        let r = r.unwrap();
        assert!(!r.degraded);
    }

    #[tokio::test]
    async fn older_minor_is_degraded() {
        let (r, _) = run(make_version(0, 1, 0)).await;
        let r = r.unwrap();
        assert!(r.degraded);
        assert!(!r.capabilities.get_services);
    }

    #[tokio::test]
    async fn other_major_is_rejected() {
        let (r, _) = run(make_version(1, 0, 0)).await;
        assert!(matches!(r, Err(ControllerError::VersionMismatch { .. })));
    }
}
