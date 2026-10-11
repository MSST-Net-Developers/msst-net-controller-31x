//! MSST-Net Controller — library root.
//!
//! Re-exports the main modules so they can be used by integration tests
//! and by the binary entry point.

pub mod app;
pub mod error;
pub mod ipc;
pub mod notify;
pub mod ops;
pub mod state;

#[cfg(feature = "tauri")]
pub mod tauri_commands;

#[cfg(feature = "webui")]
pub mod webui;

pub use error::ControllerError;

/// Release codename, shared by every 3.1.x release (Tauri/WebUI and the
/// Android Controller use the same name).
pub const CODENAME: &str = "微分方程";