//! MSST-Net Controller — Tauri application shell.
//!
//! Owns the single `IpcClient` for the process and bridges it to the
//! webview: commands from `msst_net_controller::tauri_commands`, a
//! `controller-changed` event when the view must be re-read, and a system
//! notification for each new interactive access request (as in 3.0.x).

use msst_net_controller::ipc::client::{IpcClient, IpcClientConfig};
use msst_net_controller::notify::{UiNotice, spawn_notifier};
use msst_net_controller::state::StateStore;
use msst_net_controller::tauri_commands::{self, ControllerState};
use tauri::{AppHandle, Emitter, Manager};

/// Save exported log text via a native save dialog (3.0.x `export_logs`).
/// Returns `false` if the user cancelled.
#[tauri::command]
async fn export_logs(content: String) -> Result<bool, String> {
    let path = tokio::task::spawn_blocking(|| rfd::FileDialog::new().set_file_name("msst-net-logs.txt").save_file())
        .await
        .map_err(|e| e.to_string())?;
    match path {
        Some(p) => std::fs::write(&p, content).map(|_| true).map_err(|e| format!("无法写入 {}: {e}", p.display())),
        None => Ok(false),
    }
}

fn notify_access_request(app: &AppHandle, notice: &UiNotice) {
    use tauri_plugin_notification::NotificationExt;
    if let UiNotice::AccessRequest { src_device_id, src_device_display_name, proto, dst_port, service_name, .. } = notice {
        let who = src_device_display_name.as_deref().unwrap_or(src_device_id);
        let what = match service_name {
            Some(s) => format!("{s}（{proto}:{dst_port}）"),
            None => format!("{proto}:{dst_port}"),
        };
        let _ = app
            .notification()
            .builder()
            .title("访问请求")
            .body(format!("{who} 想访问您的 {what} 服务，请在应用中处理"))
            .show();
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_notification::init())
        .setup(|app| {
            // IpcClient spawns tasks, so create it on Tauri's tokio runtime.
            let client = tauri::async_runtime::block_on(async {
                IpcClient::new(IpcClientConfig::default(), StateStore::new())
            });
            let handle = app.handle().clone();
            tauri::async_runtime::block_on(async {
                spawn_notifier(&client, move |notice| match notice {
                    UiNotice::Changed => {
                        let _ = handle.emit("controller-changed", ());
                    }
                    n @ UiNotice::AccessRequest { .. } => notify_access_request(&handle, &n),
                })
            });
            app.manage(ControllerState { client });
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::Destroyed = event {
                window.state::<ControllerState>().client.shutdown();
            }
        })
        .invoke_handler(tauri::generate_handler![
            tauri_commands::get_view,
            tauri_commands::ipc_request,
            tauri_commands::clear_acl_rules,
            export_logs,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
