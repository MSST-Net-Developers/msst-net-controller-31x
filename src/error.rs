//! Structured error types for the Controller.
//!
//! Every fallible operation returns a [`ControllerError`] variant instead of
//! `anyhow::Result`.  This makes error handling explicit and allows the UI
//! layer to present actionable feedback (e.g. "Core is not running" vs
//! "Protocol version mismatch").

// ── Main error enum ──────────────────────────────────────────────────────────

/// Top-level error type for all Controller operations.
#[derive(Debug, Clone, thiserror::Error)]
pub enum ControllerError {
    // ── IPC transport errors ─────────────────────────────────────────────────
    /// Could not connect to Core's IPC endpoint (socket/pipe not found or refused).
    #[error("cannot connect to Core IPC at {path}: {reason}")]
    ConnectionFailed { path: String, reason: String },

    /// The version handshake detected an incompatible Core.
    #[error("protocol version mismatch: Core {remote}, Controller {local}")]
    VersionMismatch { remote: String, local: String },

    /// The IPC connection was lost mid-operation.
    #[error("IPC connection lost")]
    ConnectionLost,

    /// The IPC connection is not currently established.
    #[error("not connected to Core (status: {status:?})")]
    NotConnected { status: crate::state::ConnectionStatus },

    // ── Frame-level errors ───────────────────────────────────────────────────
    /// A frame exceeded the maximum allowed size.
    #[error("IPC frame too large: {size} bytes (max {max})")]
    FrameTooLarge { size: usize, max: usize },

    /// Failed to serialize or deserialize a frame.
    #[error("IPC frame codec error: {0}")]
    Codec(String),

    // ── Request/response errors ──────────────────────────────────────────────
    /// A request to Core timed out.
    #[error("request {request_id} timed out after {timeout_secs}s")]
    RequestTimeout { request_id: String, timeout_secs: u64 },

    /// The response channel was dropped (background task exited).
    #[error("request {request_id} failed: IPC client is shutting down")]
    ClientShutdown { request_id: String },

    /// Core returned a structured error.
    #[error("Core error [{code}]: {message}")]
    CoreError { code: String, message: String },

    // ── State errors ─────────────────────────────────────────────────────────
    /// The state store received an event that could not be applied.
    #[error("state error: {0}")]
    State(String),

    // ── Application errors ───────────────────────────────────────────────────
    /// Shutdown was requested.
    #[error("shutdown requested")]
    Shutdown,

    /// An I/O error occurred.
    #[error("I/O error: {0}")]
    Io(String),
}

// ── Conversions ──────────────────────────────────────────────────────────────

impl From<std::io::Error> for ControllerError {
    fn from(e: std::io::Error) -> Self {
        ControllerError::Io(e.to_string())
    }
}

impl From<serde_json::Error> for ControllerError {
    fn from(e: serde_json::Error) -> Self {
        ControllerError::Codec(e.to_string())
    }
}

/// Convert from `msst_ipc_protocol::IpcError` (Core's structured error).
impl From<msst_ipc_protocol::IpcError> for ControllerError {
    fn from(e: msst_ipc_protocol::IpcError) -> Self {
        ControllerError::CoreError {
            code: e.code,
            message: e.message,
        }
    }
}

// ── Result alias ─────────────────────────────────────────────────────────────

/// Convenience alias for Controller operations.
pub type Result<T> = std::result::Result<T, ControllerError>;

// ── Diagnostic helpers ───────────────────────────────────────────────────────

impl ControllerError {
    /// Returns `true` if this error is transient and the operation should be retried.
    pub fn is_retryable(&self) -> bool {
        matches!(
            self,
            Self::ConnectionLost
                | Self::ConnectionFailed { .. }
                | Self::RequestTimeout { .. }
                | Self::Io(_)
        )
    }

    /// Returns `true` if this error indicates a fundamental incompatibility
    /// that cannot be resolved by retrying.
    pub fn is_fatal(&self) -> bool {
        matches!(self, Self::VersionMismatch { .. })
    }

    /// Returns a short machine-readable category for logging/metrics.
    pub fn category(&self) -> &'static str {
        match self {
            Self::ConnectionFailed { .. } => "connection",
            Self::VersionMismatch { .. } => "version",
            Self::ConnectionLost => "connection",
            Self::NotConnected { .. } => "connection",
            Self::FrameTooLarge { .. } => "frame",
            Self::Codec(_) => "codec",
            Self::RequestTimeout { .. } => "timeout",
            Self::ClientShutdown { .. } => "shutdown",
            Self::CoreError { .. } => "core",
            Self::State(_) => "state",
            Self::Shutdown => "shutdown",
            Self::Io(_) => "io",
        }
    }
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn retryable_errors() {
        assert!(ControllerError::ConnectionLost.is_retryable());
        assert!(ControllerError::ConnectionFailed {
            path: "/run/msst-net/ipc.sock".into(),
            reason: "No such file".into(),
        }
        .is_retryable());
        assert!(ControllerError::RequestTimeout {
            request_id: "req-1".into(),
            timeout_secs: 15,
        }
        .is_retryable());
        assert!(ControllerError::Io("broken pipe".into()).is_retryable());
    }

    #[test]
    fn non_retryable_errors() {
        assert!(!ControllerError::VersionMismatch {
            remote: "1.0.0".into(),
            local: "0.1.0".into(),
        }
        .is_retryable());
        assert!(!ControllerError::CoreError {
            code: "not_found".into(),
            message: "Peer not found".into(),
        }
        .is_retryable());
    }

    #[test]
    fn fatal_errors() {
        assert!(ControllerError::VersionMismatch {
            remote: "1.0.0".into(),
            local: "0.1.0".into(),
        }
        .is_fatal());
        assert!(!ControllerError::ConnectionLost.is_fatal());
    }

    #[test]
    fn error_categories() {
        assert_eq!(ControllerError::ConnectionLost.category(), "connection");
        assert_eq!(
            ControllerError::RequestTimeout {
                request_id: "r".into(),
                timeout_secs: 1,
            }
            .category(),
            "timeout"
        );
        assert_eq!(
            ControllerError::CoreError {
                code: "x".into(),
                message: "y".into(),
            }
            .category(),
            "core"
        );
    }

    #[test]
    fn error_display() {
        let e = ControllerError::VersionMismatch {
            remote: "1.0.0".into(),
            local: "0.1.0".into(),
        };
        let msg = e.to_string();
        assert!(msg.contains("1.0.0"));
        assert!(msg.contains("0.1.0"));
    }

    #[test]
    fn from_io_error() {
        let io = std::io::Error::new(std::io::ErrorKind::ConnectionRefused, "refused");
        let e: ControllerError = io.into();
        assert!(matches!(e, ControllerError::Io(_)));
    }

    #[test]
    fn from_core_error() {
        let core_err = msst_ipc_protocol::IpcError {
            code: "not_found".into(),
            message: "Peer not found".into(),
        };
        let e: ControllerError = core_err.into();
        match &e {
            ControllerError::CoreError { code, message } => {
                assert_eq!(code, "not_found");
                assert_eq!(message, "Peer not found");
            }
            _ => panic!("Expected CoreError"),
        }
    }
}