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

    /// Core no longer knows a background operation this client was waiting
    /// for (Core restarted, or the result expired): the outcome is unknown.
    #[error("operation {operation_id} is unknown to Core")]
    OperationLost { operation_id: String },

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
            Self::OperationLost { .. } => "operation",
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

/// User-facing text for a Core error code (`msst_ipc_protocol::errors`).
///
/// Core's `message` is diagnostic text; it is only appended for validation
/// codes, where it names the offending input.  Unknown codes (newer Cores)
/// get a generic message; the raw text stays available as `detail`.
fn core_error_message(code: &str, raw: &str) -> String {
    use msst_ipc_protocol::errors::*;
    let with_reason = |summary: &str| format!("{summary}：{raw}");
    match code {
        INVALID_REQUEST => "客户端核心无法识别此请求，控制器与核心版本可能不匹配。".into(),
        INVALID_RULE => with_reason("规则无效"),
        INVALID_PATCH | INVALID_GROUP => with_reason("内容无效"),
        INVALID_SETTINGS => with_reason("设置无效"),
        INVALID_HANDLE => "助记符只能包含小写字母、数字和横杠。".into(),
        INVALID_DISPLAY_NAME => "名称最多 64 个字符，且不能包含控制字符。".into(),
        NOT_FOUND => "找不到对应的对象，它可能已被删除或已处理。".into(),
        MNEMONIC_NOT_FOUND => with_reason("找不到该助记符对应的设备或用户"),
        NOT_INITIALIZED => "客户端核心尚未完成初始化，请稍后重试。".into(),
        NOT_AUTHENTICATED => "请先完成 MSST 接入认证。".into(),
        AUTH_FAILED => "认证失败，请重试。".into(),
        COORDINATOR_UNAVAILABLE => "无法连接协调服务器，请检查网络连接后重试。".into(),
        COORDINATOR_ERROR => "协调服务器返回了错误，请稍后重试。".into(),
        REGISTER_FAILED => "向协调服务器注册设备失败，请稍后重试。".into(),
        RELAY_UNAVAILABLE => "无法获取可用的中转服务器，请检查网络连接后重试。".into(),
        ROOM_CREATE_FAILED => "创建房间失败，请稍后重试。".into(),
        ROOM_REJECTED => "无法加入房间，请检查房间号和密码。".into(),
        ROOM_PASSWORD_REQUIRED => "该房间设有密码，请输入房间密码。".into(),
        ALREADY_IN_ROOM => "你已在一个房间中，请先退出当前房间。".into(),
        PERSIST_FAILED => "客户端核心无法保存配置（磁盘写入失败），更改未生效。".into(),
        PERMISSION_DENIED => "当前账户只有只读权限，不能进行更改。请让管理员把你加入 msst-net 用户组（Windows：MSST-Net Administrators），然后重新启动控制器。".into(),
        BUSY => "客户端核心正忙（同时进行的操作过多），请稍后重试。".into(),
        INCOMPATIBLE_VERSION => "客户端核心与控制器的协议版本不兼容，请将两者更新到同一版本。".into(),
        RESPONSE_TOO_LARGE => "要读取的数据超出了单次传输上限，请减少查询的条数后重试。".into(),
        _ => format!("客户端核心返回错误（{code}）。"),
    }
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
            Self::OperationLost { .. } => "核心服务已不再记录此操作（可能已重启），操作结果未知，请查看最新状态。".into(),
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

        let ui = UiError::from(ControllerError::CoreError { code: "something_new".into(), message: "raw".into() });
        assert!(ui.message.contains("something_new"));
        assert_eq!(ui.detail.as_deref(), Some("raw"));
    }

    #[test]
    fn every_documented_core_code_is_mapped() {
        for code in msst_ipc_protocol::errors::ALL {
            let msg = core_error_message(code, "RAW");
            assert!(!msg.contains(&format!("（{code}）")), "no user message for Core error code `{code}`");
        }
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