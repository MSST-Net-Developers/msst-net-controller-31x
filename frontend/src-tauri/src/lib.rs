//! MSST-Net Controller — Tauri application shell.
//!
//! Bridges the Controller library to the Tauri frontend via `#[tauri::command]`
//! functions exposed through the `tauri_commands` module.

use std::sync::Arc;
use tokio::sync::RwLock;

use msst_net_controller::ipc::client::{IpcClient, IpcClientConfig};
use msst_net_controller::state::StateStore;
use msst_net_controller::tauri_commands::ControllerState;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::new("info"))
        .init();

    let state_store = StateStore::new();
    let ipc_client = IpcClient::new(IpcClientConfig::default(), state_store.clone());

    let controller_state = Arc::new(RwLock::new(ControllerState {
        ipc_client,
        state_store,
    }));

    tauri::Builder::default()
        .manage(controller_state)
        .invoke_handler(tauri::generate_handler![
            msst_net_controller::tauri_commands::get_connection_status,
            msst_net_controller::tauri_commands::get_connection_diagnostics,
            msst_net_controller::tauri_commands::get_system_state,
            msst_net_controller::tauri_commands::get_health,
            msst_net_controller::tauri_commands::get_nodes,
            msst_net_controller::tauri_commands::get_room,
            msst_net_controller::tauri_commands::get_acl_rules,
            msst_net_controller::tauri_commands::get_acl_groups,
            msst_net_controller::tauri_commands::create_acl_group,
            msst_net_controller::tauri_commands::update_acl_group,
            msst_net_controller::tauri_commands::delete_acl_group,
            msst_net_controller::tauri_commands::get_pending_auth,
            msst_net_controller::tauri_commands::create_room,
            msst_net_controller::tauri_commands::join_room,
            msst_net_controller::tauri_commands::leave_room,
            msst_net_controller::tauri_commands::create_acl_rule,
            msst_net_controller::tauri_commands::update_acl_rule,
            msst_net_controller::tauri_commands::delete_acl_rule,
            msst_net_controller::tauri_commands::submit_auth_decision,
            msst_net_controller::tauri_commands::get_net_settings,
            msst_net_controller::tauri_commands::set_net_settings,
            msst_net_controller::tauri_commands::get_path_view,
            msst_net_controller::tauri_commands::get_traffic_stats,
            msst_net_controller::tauri_commands::get_endpoints,
            msst_net_controller::tauri_commands::refresh_endpoint,
            msst_net_controller::tauri_commands::get_relays,
            msst_net_controller::tauri_commands::set_ping_enabled,
            msst_net_controller::tauri_commands::start_diag_ping,
            msst_net_controller::tauri_commands::get_diag_ping_result,
            msst_net_controller::tauri_commands::update_display_name,
            msst_net_controller::tauri_commands::update_handle,
            msst_net_controller::tauri_commands::re_register,
            msst_net_controller::tauri_commands::get_logs,
            msst_net_controller::tauri_commands::clear_logs,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}