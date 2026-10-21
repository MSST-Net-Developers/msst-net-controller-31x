//! msst-net-webui — HTTPS WebUI for the MSST-Net Controller.
//!
//! First run sets the access password (from `PASSWORD_FILE` /
//! `/run/secrets/admin_password` / `ADMIN_PASSWORD`, else interactively on
//! a terminal).  Listens on `0.0.0.0:<port>` from config.toml, or
//! `WEBUI_BIND`.  Files live in `MSST_WEBUI_CONFIG_DIR` (default
//! `~/.config/msst-net-webui`).  Stops gracefully on SIGTERM / Ctrl-C.
//!
//! `msst-net-webui healthcheck` checks a running WebUI on this host (exit
//! status 0 when it answers), for container health checks.

use std::io::IsTerminal;
use std::net::SocketAddr;
use std::sync::{Arc, RwLock};
use std::time::Duration;

use axum_server::Handle;
use axum_server::tls_rustls::RustlsConfig;
use msst_net_controller::ipc::client::{IpcClient, IpcClientConfig};
use msst_net_controller::state::StateStore;
use msst_net_controller::webui::auth::{self, SessionStore};
use msst_net_controller::webui::config::{Config, config_dir};
use msst_net_controller::webui::tls::{self, CertSource};
use msst_net_controller::webui::{WebState, build_router};

fn fail(msg: impl std::fmt::Display) -> ! {
    eprintln!("错误: {msg}");
    std::process::exit(1);
}

/// `WEBUI_BIND`, else all interfaces on the configured port.
fn bind_addr(cfg: &Config) -> SocketAddr {
    match std::env::var("WEBUI_BIND") {
        Ok(s) => s.parse().unwrap_or_else(|e| fail(format!("WEBUI_BIND 格式无效 ({e})"))),
        Err(_) => SocketAddr::from(([0, 0, 0, 0], cfg.port)),
    }
}

/// `healthcheck`: no config is written and nothing is logged on success.
fn healthcheck() -> ! {
    let path = config_dir().join("config.toml");
    let cfg = if path.exists() { Config::load(&path).unwrap_or_else(|e| fail(e)) } else { Config::default() };
    match tls::healthcheck(tls::local_addr(bind_addr(&cfg))) {
        Ok(()) => std::process::exit(0),
        Err(e) => fail(format!("WebUI 无响应: {e}")),
    }
}

/// Stop accepting connections on SIGTERM (`docker stop`) or Ctrl-C, and give
/// open requests a few seconds to finish.  As a container's PID 1 the
/// process would otherwise ignore SIGTERM and be killed after the timeout.
async fn shutdown_on_signal(handle: Handle) {
    #[cfg(unix)]
    let term = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut s) => {
                s.recv().await;
            }
            Err(e) => {
                tracing::warn!("无法监听 SIGTERM: {e}");
                std::future::pending::<()>().await
            }
        }
    };
    #[cfg(not(unix))]
    let term = std::future::pending::<()>();
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {}
        _ = term => {}
    }
    tracing::info!("正在停止 msst-net-webui…");
    handle.graceful_shutdown(Some(Duration::from_secs(5)));
}

#[tokio::main]
async fn main() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    if std::env::args().nth(1).as_deref() == Some("healthcheck") {
        healthcheck();
    }
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
            Ok(None) if std::io::stdin().is_terminal() => auth::interactive_setup(&passwd_path).unwrap_or_else(|e| fail(e)),
            Ok(None) => fail(
                "尚未设置访问密码。请通过环境变量 ADMIN_PASSWORD 或密码文件 PASSWORD_FILE\
                 （默认 /run/secrets/admin_password）提供，或在终端中运行以交互设置。",
            ),
            Err(e) => fail(e),
        }
    }

    let cfg = Config::load_or_create(&config_path).unwrap_or_else(|e| fail(e));
    let bind = bind_addr(&cfg);

    let cert_source = CertSource::from_env(&dir).unwrap_or_else(|e| fail(e));
    let (cert, key) = cert_source.ensure().unwrap_or_else(|e| fail(e));
    let tls = RustlsConfig::from_pem_file(cert, key).await.unwrap_or_else(|e| {
        fail(format!("无法加载 TLS 证书 {} / {}: {e}", cert.display(), key.display()))
    });

    let client = IpcClient::new(IpcClientConfig::default(), StateStore::new());
    let state = WebState {
        client,
        config: Arc::new(RwLock::new(cfg)),
        config_path: Arc::new(config_path),
        passwd_path: Arc::new(passwd_path),
        sessions: Arc::new(SessionStore::new()),
    };

    let handle = Handle::new();
    tokio::spawn(shutdown_on_signal(handle.clone()));
    tracing::info!("msst-net-webui listening on https://{bind}");
    axum_server::bind_rustls(bind, tls)
        .handle(handle)
        .serve(build_router(state).into_make_service_with_connect_info::<SocketAddr>())
        .await
        .unwrap_or_else(|e| fail(e));
}
