//! Tauri command bridge.
//!
//! The frontend talks to Core through three commands:
//!
//! - `get_view` — connection status, diagnostics and the state store, read
//!   in one call.  Re-read whenever the shell emits `controller-changed`.
//! - `ipc_request` — any [`IpcRequest`] from the shared protocol crate,
//!   sent as-is (`{ "type": "...", "params": {...} }`).  A successful result
//!   means Core *accepted* the request (AGENTS.md §5.2); the affected state
//!   is re-queried from Core afterwards.
//! - `clear_acl_rules` — composite operation, see [`crate::ops`].
//!
//! Errors are returned as [`UiError`] (`{ category, code, message }`).

use msst_ipc_protocol::IpcRequest;

use crate::error::UiError;
use crate::ipc::client::{ControllerView, IpcClient};

/// Tauri-managed application state.  Insert via `.manage()`.
pub struct ControllerState {
    pub client: IpcClient,
}

#[tauri::command]
pub async fn get_view(state: tauri::State<'_, ControllerState>) -> Result<ControllerView, UiError> {
    Ok(state.client.view().await)
}

#[tauri::command]
pub async fn ipc_request(state: tauri::State<'_, ControllerState>, request: IpcRequest) -> Result<serde_json::Value, UiError> {
    state.client.command(request).await.map_err(UiError::from)
}

#[tauri::command]
pub async fn clear_acl_rules(state: tauri::State<'_, ControllerState>) -> Result<serde_json::Value, UiError> {
    crate::ops::clear_acl_rules(&state.client).await.map_err(UiError::from)
}
