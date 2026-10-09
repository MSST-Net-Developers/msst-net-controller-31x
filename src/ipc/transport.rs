//! IPC transport layer — length-prefixed framing and version handshake.
//!
//! This module handles the raw byte-level communication with Core:
//! - 4-byte big-endian length prefix framing
//! - Protocol version handshake on connection establishment
//! - Platform-specific socket/pipe connections
//!
//! The transport is intentionally dumb — it knows nothing about request/response
//! semantics or reconnection.  That logic lives in [`super::client`].

use msst_ipc_protocol::{IpcMessage, PROTOCOL_VERSION};

use crate::error::{ControllerError, Result};

// ── Platform-specific connection types ───────────────────────────────────────

/// A platform-specific async byte stream connected to Core's IPC endpoint.
pub enum IpcStream {
    #[cfg(unix)]
    Unix(tokio::net::UnixStream),
    #[cfg(windows)]
    Pipe(tokio::net::windows::named_pipe::NamedPipeClient),
}

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

/// Connect to Core's IPC endpoint.
///
/// Returns a connected [`IpcStream`] ready for the version handshake.
pub async fn connect() -> Result<IpcStream> {
    let path = resolve_ipc_path();
    tracing::info!(path = %path, "Connecting to Core IPC");
    connect_platform(&path).await
}

#[cfg(unix)]
async fn connect_platform(path: &str) -> Result<IpcStream> {
    tokio::net::UnixStream::connect(path)
        .await
        .map(IpcStream::Unix)
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
        .map(IpcStream::Pipe)
        .map_err(|e| ControllerError::ConnectionFailed {
            path: path.to_string(),
            reason: e.to_string(),
        })
}

// ── Framed I/O ───────────────────────────────────────────────────────────────

/// Perform the version handshake on a newly connected stream.
///
/// Reads the 4-byte version u32 from Core and checks compatibility.
///
/// **Deprecated**: Use [`super::handshake::perform_handshake`] instead,
/// which supports bidirectional negotiation and capability detection.
#[deprecated(note = "Use ipc::handshake::perform_handshake instead")]
pub async fn version_handshake(stream: &mut IpcStream) -> Result<()> {
    let mut version_buf = [0u8; 4];
    read_exact(stream, &mut version_buf).await?;

    let remote_version = u32::from_be_bytes(version_buf);
    let (maj, min, pat) = msst_ipc_protocol::version::decode_version(remote_version);
    tracing::info!(version = %format!("{maj}.{min}.{pat}"), "Core protocol version");

    if !msst_ipc_protocol::version::is_compatible(remote_version) {
        let (local_maj, local_min, local_pat) =
            msst_ipc_protocol::version::decode_version(PROTOCOL_VERSION);
        return Err(ControllerError::VersionMismatch {
            remote: format!("{maj}.{min}.{pat}"),
            local: format!("{local_maj}.{local_min}.{local_pat}"),
        });
    }

    Ok(())
}

/// Send a framed IPC message (4-byte BE length + JSON payload).
pub async fn send_frame(stream: &mut IpcStream, msg: &IpcMessage) -> Result<()> {
    let payload = serde_json::to_vec(msg)?;
    let len = payload.len() as u32;
    write_all(stream, &len.to_be_bytes()).await?;
    write_all(stream, &payload).await?;
    Ok(())
}

/// Receive a framed IPC message. Returns `None` on clean EOF.
pub async fn recv_frame(stream: &mut IpcStream) -> Result<Option<IpcMessage>> {
    let mut len_buf = [0u8; 4];
    match read_exact(stream, &mut len_buf).await {
        Ok(()) => {}
        Err(ControllerError::Io(ref msg)) if msg.contains("UnexpectedEof") || msg.contains("connection closed") => {
            return Ok(None);
        }
        Err(e) => return Err(e),
    }

    let len = u32::from_be_bytes(len_buf) as usize;
    if len > 16 * 1024 * 1024 {
        return Err(ControllerError::FrameTooLarge {
            size: len,
            max: 16 * 1024 * 1024,
        });
    }

    let mut payload = vec![0u8; len];
    read_exact(stream, &mut payload).await?;

    let msg: IpcMessage = serde_json::from_slice(&payload)
        .map_err(|e| ControllerError::Codec(e.to_string()))?;

    Ok(Some(msg))
}

// ── Low-level I/O helpers ────────────────────────────────────────────────────

async fn read_exact(stream: &mut IpcStream, buf: &mut [u8]) -> Result<()> {
    match stream {
        #[cfg(unix)]
        IpcStream::Unix(s) => {
            tokio::io::AsyncReadExt::read_exact(s, buf).await?;
        }
        #[cfg(windows)]
        IpcStream::Pipe(s) => {
            tokio::io::AsyncReadExt::read_exact(s, buf).await?;
        }
    }
    Ok(())
}

async fn write_all(stream: &mut IpcStream, buf: &[u8]) -> Result<()> {
    match stream {
        #[cfg(unix)]
        IpcStream::Unix(s) => {
            tokio::io::AsyncWriteExt::write_all(s, buf).await?;
        }
        #[cfg(windows)]
        IpcStream::Pipe(s) => {
            tokio::io::AsyncWriteExt::write_all(s, buf).await?;
        }
    }
    Ok(())
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolve_ipc_path_returns_non_empty() {
        let path = resolve_ipc_path();
        assert!(!path.is_empty());
    }

    #[test]
    fn version_compatibility_check() {
        assert!(msst_ipc_protocol::version::is_compatible(
            msst_ipc_protocol::version::make_version(0, 1, 0)
        ));
        assert!(msst_ipc_protocol::version::is_compatible(
            msst_ipc_protocol::version::make_version(0, 99, 99)
        ));
        assert!(!msst_ipc_protocol::version::is_compatible(
            msst_ipc_protocol::version::make_version(1, 0, 0)
        ));
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
        let e = ControllerError::VersionMismatch {
            remote: "1.0.0".into(),
            local: "0.1.0".into(),
        };
        assert!(e.is_fatal());
        assert!(!e.is_retryable());
    }

    #[test]
    fn frame_too_large_is_not_retryable() {
        let e = ControllerError::FrameTooLarge {
            size: 20_000_000,
            max: 16_777_216,
        };
        assert!(!e.is_retryable());
        assert!(!e.is_fatal());
    }
}