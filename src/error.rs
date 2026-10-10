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

    /// The request is not supported by the connected Core's protocol version.
    #[error("{0}")]
    Unsupported(String),

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
            Self::Unsupported(_) => "unsupported",
            Self::State(_) => "state",
            Self::Shutdown => "shutdown",
            Self::Io(_) => "io",
        }
    }
}

// ── UI-facing error ──────────────────────────────────────────────────────────

/// An error as presented to the UI: a stable machine-readable category and
/// code, plus a message for ordinary users (AGENTS.md §7.4).
#[derive(Debug, Clone, serde::Serialize)]
pub struct UiError {
    pub category: &'static str,
    /// Core's error code for `core` errors, otherwise the category.
    pub code: String,
    pub message: String,
    /// Technical detail for diagnostics (e.g. Core's raw error text) when
    /// `message` is a rewritten summary.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
}

impl From<ControllerError> for UiError {
    fn from(e: ControllerError) -> Self {
        let message = e.user_message();
        let (code, detail) = match &e {
            ControllerError::CoreError { code, message: raw } => {
                (code.clone(), (raw != &message).then(|| raw.clone()))
            }
            other => (other.category().to_string(), None),
        };
        UiError { category: e.category(), code, message, detail }
    }
}

/// User-facing text for a Core error code.  Infrastructure failures get a
/// plain explanation (their raw text is transport detail); validation
/// failures keep Core's specific reason.
fn core_error_message(code: &str, raw: &str) -> String {
    let summary = match code {
        "coordinator_unavailable" => return "无法连接协调服务器，请检查网络连接后重试。".into(),
        "coordinator_error" => return "协调服务器返回了错误，请稍后重试。".into(),
        "relay_unavailable" => return "无法获取可用的中转服务器，请检查网络连接后重试。".into(),
        "persist_failed" => return "客户端核心无法保存配置（磁盘写入失败），更改未生效。".into(),
        "not_initialized" => return "客户端核心尚未完成初始化，请稍后重试。".into(),
        "register_failed" => return "向协调服务器注册设备失败，请稍后重试。".into(),
        "room_create_failed" => return "创建房间失败，请稍后重试。".into(),
        "auth_failed" => "认证失败",
        "invalid_rule" => "规则无效",
        "invalid_group" | "invalid_patch" => "内容无效",
        "invalid_handle" => "助记符无效",
        "invalid_settings" => "设置无效",
        "mnemonic_not_found" => "找不到该助记符",
        "room_rejected" => "无法加入房间",
        _ => return raw.to_string(),
    };
    format!("{summary}：{raw}")
}

impl ControllerError {
    /// A message for ordinary users: what failed and, where possible, what
    /// to do next.
    pub fn user_message(&self) -> String {
        match self {
            Self::ConnectionFailed { .. } | Self::NotConnected { .. } => {
                "未连接到 MSST-Net 核心服务。请确认核心服务正在运行，控制器会自动重连。".into()
            }
            Self::ConnectionLost => "与核心服务的连接已断开，操作结果未知。重连后请查看最新状态。".into(),
            Self::VersionMismatch { remote, local } => {
                format!("核心服务的协议版本（{remote}）与控制器（{local}）不兼容，请将两者更新到同一版本。")
            }
            Self::RequestTimeout { timeout_secs, .. } => {
                format!("核心服务在 {timeout_secs} 秒内未响应。操作可能仍在执行，请稍后查看最新状态。")
            }
            Self::CoreError { code, message } => core_error_message(code, message),
            Self::Unsupported(m) => m.clone(),
            Self::FrameTooLarge { .. } | Self::Codec(_) => "核心服务返回了无法识别的数据，请将日志提供给开发组。".into(),
            Self::ClientShutdown { .. } | Self::Shutdown => "控制器正在退出。".into(),
            Self::State(m) | Self::Io(m) => m.clone(),
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
    fn core_error_messages_for_users() {
        let ui = UiError::from(ControllerError::CoreError {
            code: "coordinator_unavailable".into(),
            message: "error sending request for url (http://127.0.0.1:9/relays)".into(),
        });
        assert_eq!(ui.code, "coordinator_unavailable");
        assert!(ui.message.starts_with("无法连接协调服务器"));
        assert!(ui.detail.unwrap().contains("127.0.0.1"));

        let ui = UiError::from(ControllerError::CoreError { code: "invalid_rule".into(), message: "Invalid port '99999'".into() });
        assert_eq!(ui.message, "规则无效：Invalid port '99999'");

        let ui = UiError::from(ControllerError::CoreError { code: "not_authenticated".into(), message: "请先完成 OIDC 认证".into() });
        assert_eq!(ui.message, "请先完成 OIDC 认证");
        assert!(ui.detail.is_none());
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