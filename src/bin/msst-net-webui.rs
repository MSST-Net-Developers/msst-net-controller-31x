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
//! `msst-net-webui --version` prints the version (installers use it to
//! check a download before switching to it).
//!
//! Windows: started by the Service Control Manager (service
//! `msst-net-webui`, LocalSystem — Core's pipe admits only SYSTEM,
//! Administrators and interactive users), it runs as a service: config in
//! `%ProgramData%\MSST-Net\webui` unless `MSST_WEBUI_CONFIG_DIR` says
//! otherwise, log in `webui.log` there.  Started by hand, it runs in the
//! console as before.

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
    // Also logged: a service has no console to show stderr.
    tracing::error!("{msg}");
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

/// SIGTERM (`docker stop`) or Ctrl-C.  As a container's PID 1 the process
/// would otherwise ignore SIGTERM and be killed after the timeout.
async fn stop_signal() {
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
}

fn init_logging(writer: Option<std::fs::File>) {
    let filter =
        tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info"));
    match writer {
        Some(f) => tracing_subscriber::fmt().with_env_filter(filter).with_ansi(false).with_writer(std::sync::Mutex::new(f)).init(),
        None => tracing_subscriber::fmt().with_env_filter(filter).init(),
    }
}

fn main() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    match std::env::args().nth(1).as_deref() {
        None => {}
        Some("healthcheck") => healthcheck(),
        Some("--version" | "-V") => {
            println!("msst-net-webui {}", env!("CARGO_PKG_VERSION"));
            return;
        }
        Some("--help" | "-h") => {
            println!(
                "msst-net-webui {} — MSST-Net WebUI\n\n\
                 用法：\n  msst-net-webui               启动 WebUI\n  \
                 msst-net-webui healthcheck   检查本机运行中的 WebUI\n  \
                 msst-net-webui --version     显示版本\n\n\
                 配置通过环境变量提供，见 docs/DOCKER.md。",
                env!("CARGO_PKG_VERSION")
            );
            return;
        }
        // Never start the server on an argument it doesn't understand.
        Some(other) => {
            eprintln!("错误: 未知参数 {other}（--help 查看用法）");
            std::process::exit(2);
        }
    }
    #[cfg(windows)]
    if windows_service_host::dispatch() {
        return;
    }
    init_logging(None);
    runtime().block_on(serve(stop_signal()));
}

fn runtime() -> tokio::runtime::Runtime {
    tokio::runtime::Builder::new_multi_thread().enable_all().build().unwrap_or_else(|e| fail(format!("无法启动运行时: {e}")))
}

/// Run the WebUI until `stop` completes; then stop accepting connections
/// and give open requests a few seconds to finish.
async fn serve(stop: impl std::future::Future<Output = ()> + Send + 'static) {
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
    let h = handle.clone();
    tokio::spawn(async move {
        stop.await;
        tracing::info!("正在停止 msst-net-webui…");
        h.graceful_shutdown(Some(Duration::from_secs(5)));
    });
    tracing::info!("msst-net-webui listening on https://{bind}");
    axum_server::bind_rustls(bind, tls)
        .handle(handle)
        .serve(build_router(state).into_make_service_with_connect_info::<SocketAddr>())
        .await
        .unwrap_or_else(|e| fail(e));
}

/// Windows service host (the pattern of Core's `windows_service_host.rs`).
///
/// The SCM requires `StartServiceCtrlDispatcher` within ~30 s of launch.
/// Started by hand there is no SCM and the call fails at once with
/// `ERROR_FAILED_SERVICE_CONTROLLER_CONNECT` (1063): then the WebUI runs in
/// the console.
#[cfg(windows)]
mod windows_service_host {
    use std::ffi::OsString;
    use std::path::PathBuf;
    use std::sync::{Arc, Mutex};
    use std::time::Duration;
    use windows_service::service::{
        ServiceControl, ServiceControlAccept, ServiceExitCode, ServiceState, ServiceStatus, ServiceType,
    };
    use windows_service::service_control_handler::{self, ServiceControlHandlerResult};
    use windows_service::{define_windows_service, service_dispatcher};

    pub const SERVICE_NAME: &str = "msst-net-webui";
    const ERROR_FAILED_SERVICE_CONTROLLER_CONNECT: i32 = 1063;
    /// Rotate the log beyond this size (one old copy is kept).
    const LOG_LIMIT: u64 = 5 * 1024 * 1024;

    /// Run as a service if started by the SCM.  `false`: not under the SCM.
    pub fn dispatch() -> bool {
        match service_dispatcher::start(SERVICE_NAME, ffi_service_main) {
            Ok(()) => true,
            Err(windows_service::Error::Winapi(ref e)) if e.raw_os_error() == Some(ERROR_FAILED_SERVICE_CONTROLLER_CONNECT) => false,
            Err(e) => {
                eprintln!("服务调度器错误: {e}");
                std::process::exit(1);
            }
        }
    }

    define_windows_service!(ffi_service_main, service_main);

    fn status(state: ServiceState, accept: ServiceControlAccept, exit: u32, wait: Duration) -> ServiceStatus {
        ServiceStatus {
            service_type: ServiceType::OWN_PROCESS,
            current_state: state,
            controls_accepted: accept,
            exit_code: ServiceExitCode::Win32(exit),
            checkpoint: 0,
            wait_hint: wait,
            process_id: None,
        }
    }

    /// `%ProgramData%\MSST-Net\webui` (the installer's location); the
    /// per-user default makes no sense for LocalSystem.
    fn service_config_dir() -> PathBuf {
        let base = std::env::var_os("ProgramData").map(PathBuf::from).unwrap_or_else(|| PathBuf::from(r"C:\ProgramData"));
        base.join("MSST-Net").join("webui")
    }

    fn open_log(dir: &std::path::Path) -> Option<std::fs::File> {
        std::fs::create_dir_all(dir).ok()?;
        let path = dir.join("webui.log");
        if std::fs::metadata(&path).is_ok_and(|m| m.len() > LOG_LIMIT) {
            let _ = std::fs::rename(&path, dir.join("webui.log.1"));
        }
        std::fs::OpenOptions::new().create(true).append(true).open(path).ok()
    }

    fn service_main(_args: Vec<OsString>) {
        if std::env::var_os("MSST_WEBUI_CONFIG_DIR").is_none_or(|d| d.is_empty()) {
            // SAFETY: before the runtime and the server exist; the only other
            // thread is the SCM's dispatcher, which doesn't read the environment.
            unsafe { std::env::set_var("MSST_WEBUI_CONFIG_DIR", service_config_dir()) };
        }
        let dir = msst_net_controller::webui::config::config_dir();
        super::init_logging(open_log(&dir));

        let (stop_tx, stop_rx) = tokio::sync::oneshot::channel::<()>();
        let stop_tx = Arc::new(Mutex::new(Some(stop_tx)));
        let handle = match service_control_handler::register(SERVICE_NAME, move |ctrl| match ctrl {
            ServiceControl::Stop | ServiceControl::Shutdown => {
                if let Some(tx) = stop_tx.lock().ok().and_then(|mut g| g.take()) {
                    let _ = tx.send(());
                }
                ServiceControlHandlerResult::NoError
            }
            ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
            _ => ServiceControlHandlerResult::NotImplemented,
        }) {
            Ok(h) => h,
            Err(e) => {
                tracing::error!("无法注册服务控制处理器: {e}");
                return;
            }
        };

        let _ = handle.set_service_status(status(ServiceState::StartPending, ServiceControlAccept::empty(), 0, Duration::from_secs(30)));
        let rt = super::runtime();
        let _ = handle.set_service_status(status(
            ServiceState::Running,
            ServiceControlAccept::STOP | ServiceControlAccept::SHUTDOWN,
            0,
            Duration::default(),
        ));
        tracing::info!("msst-net-webui {} 以 Windows 服务运行，配置目录 {}", env!("CARGO_PKG_VERSION"), dir.display());
        // Startup errors end the process (`fail`): the SCM then reports an
        // unexpected stop and applies the service's recovery actions.
        rt.block_on(super::serve(async {
            let _ = stop_rx.await;
            tracing::info!("service stop requested by the SCM");
        }));
        let _ = handle.set_service_status(status(ServiceState::Stopped, ServiceControlAccept::empty(), 0, Duration::default()));
    }
}
