//! Application lifecycle — startup, run, shutdown.
//!
//! The [`App`] struct owns all top-level components and coordinates their
//! lifecycle.  It is created once at startup and drives the main event loop
//! until a shutdown signal is received.

use crate::error::Result;
use crate::ipc::client::{IpcClient, IpcClientConfig};
use crate::state::{StateChange, StateStore};

/// The Controller application.
pub struct App {
    pub state_store: StateStore,
    pub ipc_client: IpcClient,
}

impl Default for App {
    fn default() -> Self {
        Self::new()
    }
}

impl App {
    /// Create and start the application.
    pub fn new() -> Self {
        let state_store = StateStore::new();
        let config = IpcClientConfig::default();
        let ipc_client = IpcClient::new(config, state_store.clone());

        Self {
            state_store,
            ipc_client,
        }
    }

    /// Run until shutdown signal (Ctrl+C / SIGTERM).
    ///
    /// Subscribes to state changes and logs them.  The IPC client's background
    /// task handles connection, reconnection, and event processing autonomously.
    pub async fn run(&self) -> Result<()> {
        tracing::info!("Controller started");

        let mut state_rx = self.ipc_client.subscribe();

        let state_log = tokio::spawn(async move {
            loop {
                match state_rx.recv().await {
                    Ok(StateChange::SyncChanged(sync)) => {
                        tracing::info!(?sync, "State sync changed");
                    }
                    Ok(StateChange::ResourceRefreshed(resource)) => {
                        tracing::debug!(?resource, "Resource refreshed from Core");
                    }
                    Ok(StateChange::EventApplied(event)) => {
                        tracing::debug!(?event, "IPC event applied");
                    }
                    Ok(StateChange::OperationChanged(id)) => {
                        tracing::debug!(%id, "Background operation updated");
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(n)) => {
                        tracing::warn!(n, "State change subscriber lagged, events dropped");
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => {
                        break;
                    }
                }
            }
        });

        let client = self.ipc_client.clone();
        let status_log = tokio::spawn(async move {
            let mut interval = tokio::time::interval(std::time::Duration::from_secs(30));
            interval.tick().await;
            loop {
                interval.tick().await;
                let status = client.connection_status().await;
                tracing::info!(%status, "Connection status");
            }
        });

        tokio::signal::ctrl_c().await
            .map_err(|e| crate::error::ControllerError::Io(e.to_string()))?;
        tracing::info!("Shutdown signal received");

        self.ipc_client.shutdown();

        state_log.abort();
        status_log.abort();

        Ok(())
    }
}