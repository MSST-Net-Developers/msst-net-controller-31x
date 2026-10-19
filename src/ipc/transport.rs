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

/// Maximum accepted frame size.  Protocol 0.4 Cores never send frames above
/// [`CORE_MAX_FRAME_LEN`]; the extra room tolerates older Cores.
pub const MAX_FRAME_LEN: usize = 16 * 1024 * 1024;

/// Largest frame Core accepts (and, since protocol 0.4, sends).
pub const CORE_MAX_FRAME_LEN: usize = 1024 * 1024;

// ── Stream abstraction ───────────────────────────────────────────────────────

/// Any bidirectional byte stream that can carry the IPC protocol.
pub trait AsyncStream: AsyncRead + AsyncWrite + Send + Unpin {}
impl<T: AsyncRead + AsyncWrite + Send + Unpin> AsyncStream for T {}

/// A connected stream to Core's IPC endpoint (Unix socket, named pipe, or an
/// in-memory stream in tests).
pub type IpcStream = Box<dyn AsyncStream>;

// ── Connection ───────────────────────────────────────────────────────────────

/// Candidate IPC socket/pipe paths, in the order they are tried.
///
/// 1. `MSST_IPC_PATH` env var (explicit override; the only candidate)
/// 2. Platform defaults, matching where Core binds (Core's
///    `resolve_ipc_socket`: per-user directory when not root, system
///    directory when root):
///    - Linux: `$XDG_RUNTIME_DIR/msst-net/ipc.sock`, `/run/msst-net/ipc.sock`
///    - macOS: `$TMPDIR/msst-net/ipc.sock`, `/var/run/msst-net/ipc.sock`
///    - Windows: `\\.\pipe\msst-net-ipc`
///
/// The Controller cannot know whether Core runs as root, and a socket file
/// left behind by an earlier Core may exist with nobody listening, so the
/// paths are tried by connecting, not by checking that the file exists.
pub fn ipc_path_candidates() -> Vec<String> {
    if let Ok(p) = std::env::var("MSST_IPC_PATH") {
        return vec![p];
    }
    #[cfg(target_os = "linux")]
    {
        let mut v: Vec<String> = std::env::var("XDG_RUNTIME_DIR").map(|xdg| format!("{xdg}/msst-net/ipc.sock")).into_iter().collect();
        v.push("/run/msst-net/ipc.sock".to_string());
        v
    }
    #[cfg(target_os = "macos")]
    {
        let mut v: Vec<String> = std::env::var("TMPDIR")
            .map(|tmpdir| format!("{}/msst-net/ipc.sock", tmpdir.trim_end_matches('/')))
            .into_iter()
            .collect();
        v.push("/var/run/msst-net/ipc.sock".to_string());
        v
    }
    #[cfg(windows)]
    {
        vec![r"\\.\pipe\msst-net-ipc".to_string()]
    }
    #[cfg(not(any(target_os = "linux", target_os = "macos", windows)))]
    {
        vec!["/run/msst-net/ipc.sock".to_string()]
    }
}

/// Connect to Core's IPC endpoint: the first candidate path that accepts
/// a connection (see [`ipc_path_candidates`]).
pub async fn connect() -> Result<IpcStream> {
    connect_first(&ipc_path_candidates()).await
}

/// Connect to the first of `paths` that accepts a connection.  If none does,
/// the error lists every path and why it failed.
pub async fn connect_first(paths: &[String]) -> Result<IpcStream> {
    let mut failures = Vec::new();
    for path in paths {
        match connect_to(path).await {
            Ok(s) => return Ok(s),
            Err(ControllerError::ConnectionFailed { reason, .. }) => failures.push((path.as_str(), reason)),
            Err(e) => return Err(e),
        }
    }
    Err(match failures.as_slice() {
        [(path, reason)] => ControllerError::ConnectionFailed { path: path.to_string(), reason: reason.clone() },
        _ => ControllerError::ConnectionFailed {
            path: paths.join(", "),
            reason: failures.iter().map(|(p, r)| format!("{p}: {r}")).collect::<Vec<_>>().join("; "),
        },
    })
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
///
/// A message above [`CORE_MAX_FRAME_LEN`] fails with `FrameTooLarge` before
/// anything is written (Core would close the connection), so the stream
/// stays usable.
pub async fn send_frame<W: AsyncWrite + Unpin + ?Sized>(stream: &mut W, msg: &IpcMessage) -> Result<()> {
    let payload = serde_json::to_vec(msg)?;
    if payload.len() > CORE_MAX_FRAME_LEN {
        return Err(ControllerError::FrameTooLarge { size: payload.len(), max: CORE_MAX_FRAME_LEN });
    }
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
    fn ipc_path_candidates_non_empty() {
        let c = ipc_path_candidates();
        assert!(!c.is_empty() && c.iter().all(|p| !p.is_empty()));
    }

    /// A socket file left by an earlier Core (nobody listening) must not hide
    /// the Core listening on a later candidate path.
    #[cfg(unix)]
    #[tokio::test]
    async fn stale_socket_file_falls_through_to_live_core() {
        let dir = std::env::temp_dir().join(format!("msst-transport-stale-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let stale = dir.join("stale.sock");
        drop(std::os::unix::net::UnixListener::bind(&stale).unwrap()); // file stays, nobody listens
        let live = dir.join("live.sock");
        let listener = tokio::net::UnixListener::bind(&live).unwrap();
        let accept = tokio::spawn(async move { listener.accept().await.is_ok() });

        let paths = [stale.to_string_lossy().into_owned(), live.to_string_lossy().into_owned()];
        assert!(connect_first(&paths).await.is_ok());
        assert!(accept.await.unwrap());

        let missing = dir.join("missing.sock").to_string_lossy().into_owned();
        match connect_first(&[paths[0].clone(), missing.clone()]).await {
            Err(ControllerError::ConnectionFailed { path, reason }) => {
                assert!(path.contains(&paths[0]) && path.contains(&missing), "{path}");
                assert!(reason.contains(&paths[0]) && reason.contains(&missing), "{reason}");
            }
            Err(e) => panic!("unexpected error {e}"),
            Ok(_) => panic!("connected to nothing"),
        }
        let _ = std::fs::remove_dir_all(&dir);
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
        let msg = IpcMessage::Request { id: "r1".into(), body: IpcRequest::Health, r#async: false };
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
        let ev = IpcMessage::Event { body: IpcEvent::CoordinatorConnectionChanged { connected: true }, seq: Some(1) };
        send_frame(&mut a, &ev).await.unwrap();
        assert!(matches!(recv_frame(&mut b).await.unwrap(), Some(Frame::Message(IpcMessage::Event { .. }))));
    }

    #[tokio::test]
    async fn oversized_frame_rejected() {
        let (mut a, mut b) = tokio::io::duplex(64);
        a.write_all(&((MAX_FRAME_LEN as u32) + 1).to_be_bytes()).await.unwrap();
        assert!(matches!(recv_frame(&mut b).await, Err(ControllerError::FrameTooLarge { .. })));
    }

    #[tokio::test]
    async fn oversized_request_is_not_sent() {
        let (mut a, mut b) = tokio::io::duplex(4096);
        let rule = serde_json::json!({ "comment": "x".repeat(CORE_MAX_FRAME_LEN) });
        let big = IpcMessage::Request { id: "big".into(), body: IpcRequest::CreateAclRule { rule }, r#async: false };
        assert!(matches!(send_frame(&mut a, &big).await, Err(ControllerError::FrameTooLarge { .. })));
        // Nothing was written: the next frame is intact.
        let ok = IpcMessage::Request { id: "ok".into(), body: IpcRequest::Health, r#async: false };
        send_frame(&mut a, &ok).await.unwrap();
        match recv_frame(&mut b).await.unwrap() {
            Some(Frame::Message(IpcMessage::Request { id, .. })) => assert_eq!(id, "ok"),
            other => panic!("unexpected {other:?}"),
        }
    }
}
