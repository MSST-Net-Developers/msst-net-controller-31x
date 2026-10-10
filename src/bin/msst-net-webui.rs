//! msst-net-webui — HTTPS WebUI for the MSST-Net Controller.
//!
//! First run sets the access password (from `PASSWORD_FILE` /
//! `/run/secrets/admin_password` / `ADMIN_PASSWORD`, else interactively).
//! Listens on `0.0.0.0:<port>` from config.toml, or `WEBUI_BIND`.

use std::net::SocketAddr;
use std::sync::{Arc, RwLock};

use axum_server::tls_rustls::RustlsConfig;
use msst_net_controller::ipc::client::{IpcClient, IpcClientConfig};
use msst_net_controller::state::StateStore;
use msst_net_controller::webui::auth::{self, SessionStore};
use msst_net_controller::webui::config::{Config, config_dir};
use msst_net_controller::webui::{WebState, build_router};

fn fail(msg: impl std::fmt::Display) -> ! {
    eprintln!("错误: {msg}");
    std::process::exit(1);
}

fn self_signed_cert() -> Result<(Vec<u8>, Vec<u8>), String> {
    let sans = vec!["localhost".to_string(), "127.0.0.1".to_string(), "::1".to_string()];
    let cert = rcgen::generate_simple_self_signed(sans).map_err(|e| format!("cert gen: {e}"))?;
    let der = cert.serialize_der().map_err(|e| format!("cert serialize: {e}"))?;
    Ok((der, cert.serialize_private_key_der()))
}

#[tokio::main]
async fn main() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    let dir = config_dir();
    std::fs::create_dir_all(&dir).unwrap_or_else(|e| fail(format!("无法创建 {}: {e}", dir.display())));
    let config_path = dir.join("config.toml");
    let passwd_path = dir.join("passwd");

    if !passwd_path.exists() {
        match auth::env_password() {
            Ok(Some(pw)) => {
                let hash = auth::hash_password(&pw).unwrap_or_else(|e| fail(e));
                auth::write_passwd(&passwd_path, &hash).unwrap_or_else(|e| fail(e));
                tracing::info!("管理密码已自动配置");
            }
            Ok(None) => auth::interactive_setup(&passwd_path).unwrap_or_else(|e| fail(e)),
            Err(e) => fail(e),
        }
    }

    let cfg = Config::load_or_create(&config_path).unwrap_or_else(|e| fail(e));
    let bind: SocketAddr = match std::env::var("WEBUI_BIND") {
        Ok(s) => s.parse().unwrap_or_else(|e| fail(format!("WEBUI_BIND 格式无效 ({e})"))),
        Err(_) => SocketAddr::from(([0, 0, 0, 0], cfg.port)),
    };

    let (cert, key) = self_signed_cert().unwrap_or_else(|e| fail(e));
    let tls = RustlsConfig::from_der(vec![cert], key).await.unwrap_or_else(|e| fail(format!("TLS: {e}")));

    let client = IpcClient::new(IpcClientConfig::default(), StateStore::new());
    let state = WebState {
        client,
        config: Arc::new(RwLock::new(cfg)),
        config_path: Arc::new(config_path),
        passwd_path: Arc::new(passwd_path),
        sessions: Arc::new(SessionStore::new()),
    };

    tracing::info!("msst-net-webui listening on https://{bind}");
    axum_server::bind_rustls(bind, tls)
        .serve(build_router(state).into_make_service_with_connect_info::<SocketAddr>())
        .await
        .unwrap_or_else(|e| fail(e));
}
