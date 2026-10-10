//! IPC transport layer — length-prefixed framing and platform connections.
//!
//! This module handles the raw byte-level communication with Core:
//! - 4-byte big-endian length prefix framing
//! - Platform-specific socket/pipe connections
//!
//! The transport is intentionally dumb — it knows nothing about request/response
//! semantics or reconnection.  That logic lives in [`super::client`].
//! The version handshake lives in [`super::handshake`].

use msst_ipc_protocol::IpcMessage;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use crate::error::{ControllerError, Result};

/// Maximum accepted frame size (Core itself rejects frames above 1 MiB).
pub const MAX_FRAME_LEN: usize = 16 * 1024 * 1024;

// ── Stream abstraction ───────────────────────────────────────────────────────

/// Any bidirectional byte stream that can carry the IPC protocol.
pub trait AsyncStream: AsyncRead + AsyncWrite + Send + Unpin {}
impl<T: AsyncRead + AsyncWrite + Send + Unpin> AsyncStream for T {}

/// A connected stream to Core's IPC endpoint (Unix socket, named pipe, or an
/// in-memory stream in tests).
pub type IpcStream = Box<dyn AsyncStream>;

// ── Connection ───────────────────────────────────────────────────────────────

/// Resolve the IPC socket/pipe path.
///
/// Priority:
/// 1. `MSST_IPC_PATH` env var (explicit override)
/// 2. Platform-specific defaults:
///    - Linux: `$XDG_RUNTIME_DIR/msst-net/ipc.sock` → `/run/msst-net/ipc.sock`
///    - macOS: `$TMPDIR/msst-net/ipc.sock` → `/var/run/msst-net/ipc.sock`
///    - Windows: `\\.\pipe\msst-net-ipc`
pub fn resolve_ipc_path() -> String {
    if let Ok(p) = std::env::var("MSST_IPC_PATH") {
        return p;
    }
    #[cfg(target_os = "linux")]
    {
        if let Ok(xdg) = std::env::var("XDG_RUNTIME_DIR") {
            let p = format!("{xdg}/msst-net/ipc.sock");
            if std::path::Path::new(&p).exists() {
                return p;
            }
        }
        "/run/msst-net/ipc.sock".to_string()
    }
    #[cfg(target_os = "macos")]
    {
        if let Ok(tmpdir) = std::env::var("TMPDIR") {
            let p = format!("{}/msst-net/ipc.sock", tmpdir.trim_end_matches('/'));
            if std::path::Path::new(&p).exists() {
                return p;
            }
        }
        "/var/run/msst-net/ipc.sock".to_string()
    }
    #[cfg(windows)]
    {
        r"\\.\pipe\msst-net-ipc".to_string()
    }
    #[cfg(not(any(target_os = "linux", target_os = "macos", windows)))]
    {
        "/run/msst-net/ipc.sock".to_string()
    }
}

/// Connect to Core's IPC endpoint at the default (or overridden) path.
pub async fn connect() -> Result<IpcStream> {
    connect_to(&resolve_ipc_path()).await
}

/// Connect to Core's IPC endpoint at `path`.
pub async fn connect_to(path: &str) -> Result<IpcStream> {
    tracing::debug!(path = %path, "Connecting to Core IPC");
    connect_platform(path).await
}

#[cfg(unix)]
async fn connect_platform(path: &str) -> Result<IpcStream> {
    tokio::net::UnixStream::connect(path)
        .await
        .map(|s| Box::new(s) as IpcStream)
        .map_err(|e| ControllerError::ConnectionFailed {
            path: path.to_string(),
            reason: e.to_string(),
        })
}

#[cfg(windows)]
async fn connect_platform(path: &str) -> Result<IpcStream> {
    use tokio::net::windows::named_pipe::ClientOptions;
    ClientOptions::new()
        .open(path)
        .map(|s| Box::new(s) as IpcStream)
        .map_err(|e| ControllerError::ConnectionFailed {
            path: path.to_string(),
            reason: e.to_string(),
        })
}

// ── Framed I/O ───────────────────────────────────────────────────────────────

/// Send a framed IPC message (4-byte BE length + JSON payload).
pub async fn send_frame<W: AsyncWrite + Unpin + ?Sized>(stream: &mut W, msg: &IpcMessage) -> Result<()> {
    let payload = serde_json::to_vec(msg)?;
    stream.write_all(&(payload.len() as u32).to_be_bytes()).await?;
    stream.write_all(&payload).await?;
    stream.flush().await?;
    Ok(())
}

/// Receive one raw frame. Returns `None` on clean EOF at a frame boundary.
async fn recv_raw<R: AsyncRead + Unpin + ?Sized>(stream: &mut R) -> Result<Option<Vec<u8>>> {
    let mut len_buf = [0u8; 4];
    match stream.read_exact(&mut len_buf).await {
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e.into()),
    }
    let len = u32::from_be_bytes(len_buf) as usize;
    if len > MAX_FRAME_LEN {
        return Err(ControllerError::FrameTooLarge { size: len, max: MAX_FRAME_LEN });
    }
    let mut payload = vec![0u8; len];
    stream.read_exact(&mut payload).await?;
    Ok(Some(payload))
}

/// A received frame: either a decoded message or a frame whose JSON this
/// build cannot decode (e.g. an event type added in a newer minor version).
#[derive(Debug)]
pub enum Frame {
    Message(IpcMessage),
    /// Undecodable payload; `id` is recovered when the frame is a response so
    /// the waiting request can fail instead of hanging until its timeout.
    Undecodable { kind: Option<String>, id: Option<String>, error: String },
}

/// Receive one framed IPC message. Returns `None` on clean EOF.
///
/// An undecodable frame is not a transport error: newer Cores may send event
/// types this build does not know (protocol minor versions are additive).
pub async fn recv_frame<R: AsyncRead + Unpin + ?Sized>(stream: &mut R) -> Result<Option<Frame>> {
    let Some(payload) = recv_raw(stream).await? else {
        return Ok(None);
    };
    match serde_json::from_slice::<IpcMessage>(&payload) {
        Ok(msg) => Ok(Some(Frame::Message(msg))),
        Err(e) => {
            let v: Option<serde_json::Value> = serde_json::from_slice(&payload).ok();
            let field = |k: &str| v.as_ref().and_then(|v| v.get(k)).and_then(|x| x.as_str()).map(str::to_string);
            Ok(Some(Frame::Undecodable { kind: field("kind"), id: field("id"), error: e.to_string() }))
        }
    }
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use msst_ipc_protocol::{IpcEvent, IpcRequest};

    #[test]
    fn resolve_ipc_path_returns_non_empty() {
        assert!(!resolve_ipc_path().is_empty());
    }

    #[test]
    fn connection_failed_is_retryable() {
        let e = ControllerError::ConnectionFailed {
            path: "/run/msst-net/ipc.sock".into(),
            reason: "No such file".into(),
        };
        assert!(e.is_retryable());
        assert!(!e.is_fatal());
    }

    #[test]
    fn version_mismatch_is_fatal() {
        let e = ControllerError::VersionMismatch { remote: "1.0.0".into(), local: "0.1.0".into() };
        assert!(e.is_fatal());
        assert!(!e.is_retryable());
    }

    #[tokio::test]
    async fn frame_round_trip_and_clean_eof() {
        let (mut a, mut b) = tokio::io::duplex(4096);
        let msg = IpcMessage::Request { id: "r1".into(), body: IpcRequest::Health };
        send_frame(&mut a, &msg).await.unwrap();
        drop(a);
        match recv_frame(&mut b).await.unwrap() {
            Some(Frame::Message(IpcMessage::Request { id, .. })) => assert_eq!(id, "r1"),
            other => panic!("unexpected {other:?}"),
        }
        assert!(recv_frame(&mut b).await.unwrap().is_none(), "EOF must be reported as None");
    }

    #[tokio::test]
    async fn unknown_event_type_is_undecodable_not_error() {
        let (mut a, mut b) = tokio::io::duplex(4096);
        let json = br#"{"kind":"event","body":{"type":"from_the_future","data":{}}}"#;
        a.write_all(&(json.len() as u32).to_be_bytes()).await.unwrap();
        a.write_all(json).await.unwrap();
        match recv_frame(&mut b).await.unwrap() {
            Some(Frame::Undecodable { kind, id, .. }) => {
                assert_eq!(kind.as_deref(), Some("event"));
                assert!(id.is_none());
            }
            other => panic!("unexpected {other:?}"),
        }
        // Stream stays usable.
        let ev = IpcMessage::Event { body: IpcEvent::CoordinatorConnectionChanged { connected: true } };
        send_frame(&mut a, &ev).await.unwrap();
        assert!(matches!(recv_frame(&mut b).await.unwrap(), Some(Frame::Message(IpcMessage::Event { .. }))));
    }

    #[tokio::test]
    async fn oversized_frame_rejected() {
        let (mut a, mut b) = tokio::io::duplex(64);
        a.write_all(&((MAX_FRAME_LEN as u32) + 1).to_be_bytes()).await.unwrap();
        assert!(matches!(recv_frame(&mut b).await, Err(ControllerError::FrameTooLarge { .. })));
    }
}
