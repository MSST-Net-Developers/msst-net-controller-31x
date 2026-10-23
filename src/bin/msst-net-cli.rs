//! msst-net-cli — the command-line MSST-Net Controller (see
//! `msst_net_controller::cli`).
//!
//! Exit status: 0 success, 1 the operation failed, 2 invalid input or
//! confirmation required, 3 Core not reachable or incompatible.

use std::io::{BufRead, IsTerminal, Write};
use std::sync::Arc;
use std::time::Duration;

use clap::Parser;
use msst_net_controller::cli::{self, Cli, CliError, Ctx};
use msst_net_controller::ipc::client::{Connector, IpcClient, IpcClientConfig};
use msst_net_controller::ipc::transport;
use msst_net_controller::state::StateStore;

/// Ask on the terminal; `None` when stdin is not one.
fn ask(prompt: &str) -> Option<bool> {
    if !std::io::stdin().is_terminal() {
        return None;
    }
    eprint!("{prompt} [y/N] ");
    let _ = std::io::stderr().flush();
    let mut line = String::new();
    std::io::stdin().lock().read_line(&mut line).ok()?;
    Some(matches!(line.trim().to_lowercase().as_str(), "y" | "yes" | "是"))
}

/// Ask for a password without echo; `None` when stdin is not a terminal.
fn ask_secret(prompt: &str) -> Option<String> {
    if !std::io::stdin().is_terminal() {
        return None;
    }
    rpassword::prompt_password(prompt).ok()
}

#[tokio::main]
async fn main() {
    let args = Cli::parse();

    // Library diagnostics only on request (RUST_LOG); the output is for people.
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("off")),
        )
        .with_writer(std::io::stderr)
        .init();

    let config = IpcClientConfig::default();
    let client = match args.ipc_path.clone() {
        Some(path) => {
            let connector: Connector = Arc::new(move || {
                let path = path.clone();
                Box::pin(async move { transport::connect_to(&path).await })
            });
            IpcClient::with_connector(config, StateStore::new(), connector)
        }
        None => IpcClient::new(config, StateStore::new()),
    };

    let mut stdout = std::io::stdout();
    let mut confirm = ask;
    let mut secret = ask_secret;
    let mut ctx = Ctx {
        client: &client,
        out: &mut stdout,
        json: args.json,
        yes: args.yes,
        timeout: Duration::from_secs(args.timeout),
        confirm: &mut confirm,
        secret: &mut secret,
    };
    let result = cli::run(&mut ctx, &args.command).await;
    let _ = std::io::stdout().flush();
    client.shutdown();

    if let Err(e) = result {
        report(&e, args.json);
        std::process::exit(e.exit_code());
    }
}

fn report(e: &CliError, json: bool) {
    let ui = e.to_ui();
    if json {
        eprintln!("{}", serde_json::json!({ "error": ui }));
        return;
    }
    if matches!(e, CliError::Cancelled) {
        eprintln!("{}", ui.message);
        return;
    }
    eprintln!("错误：{}", ui.message);
    if let Some(d) = ui.detail {
        eprintln!("详情：{d}");
    }
}
