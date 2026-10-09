//! MSST-Net Controller — entry point.

use clap::Parser;
use msst_net_controller::app;
use msst_net_controller::error::ControllerError;

/// MSST-Net Controller — the unprivileged GUI/CLI client.
#[derive(Parser, Debug)]
#[command(name = "msst-net-controller", version, about)]
struct Cli {
    /// Override the IPC socket/pipe path (default: auto-detect).
    #[arg(long = "ipc-path", env = "MSST_IPC_PATH")]
    ipc_path: Option<String>,

    /// Log level filter (e.g. "info", "debug", "msst_net_controller=trace").
    #[arg(long, env = "RUST_LOG", default_value = "info")]
    log_level: String,
}

#[tokio::main]
async fn main() {
    let cli = Cli::parse();

    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new(&cli.log_level)),
        )
        .init();

    // SAFETY: single-threaded at this point, before tokio runtime starts.
    if let Some(ref path) = cli.ipc_path {
        unsafe { std::env::set_var("MSST_IPC_PATH", path) };
    }

    tracing::info!(version = env!("CARGO_PKG_VERSION"), "Starting Controller");

    let app = app::App::new();
    if let Err(e) = app.run().await {
        match &e {
            ControllerError::VersionMismatch { remote, local } => {
                tracing::error!(
                    remote = %remote,
                    local = %local,
                    category = e.category(),
                    "Fatal: IPC protocol version mismatch — cannot communicate with Core"
                );
                eprintln!("Error: {e}");
                eprintln!("Update Controller or Core so both use the same protocol major version.");
                std::process::exit(1);
            }
            ControllerError::Shutdown => {
                // Normal exit
            }
            _ => {
                tracing::error!(error = %e, category = e.category(), "Controller exited with error");
                eprintln!("Error: {e}");
                std::process::exit(1);
            }
        }
    }
}