//! Change notifications for UI front ends.
//!
//! Front ends do not mirror individual events; they re-read the whole view
//! ([`crate::ipc::client::IpcClient::view`]) when told something changed.
//! Bursts (e.g. a resync touching every resource) are coalesced so a UI
//! re-reads once per burst, not once per event.

use std::time::Duration;

use msst_ipc_protocol::IpcEvent;
use tokio::sync::broadcast::error::RecvError;
use tokio::task::JoinHandle;

use crate::ipc::client::IpcClient;
use crate::state::StateChange;

const DEBOUNCE: Duration = Duration::from_millis(100);

/// What a front end is told.
#[derive(Debug, Clone, serde::Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum UiNotice {
    /// The view changed; re-read it.
    Changed,
    /// Core reported a new interactive access request (for a system
    /// notification).  Names are as supplied by Core and may be absent.
    AccessRequest {
        request_id: String,
        src_device_id: String,
        src_device_display_name: Option<String>,
        proto: String,
        dst_port: u16,
        service_name: Option<String>,
    },
}

/// Forward changes of `client` to `sink` until the client shuts down or the
/// returned task is aborted.
pub fn spawn_notifier(client: &IpcClient, sink: impl Fn(UiNotice) + Send + 'static) -> JoinHandle<()> {
    let mut changes = client.subscribe();
    let mut transitions = client.subscribe_transitions();
    tokio::spawn(async move {
        loop {
            // Wait for the first change of a burst.
            let first = tokio::select! {
                c = changes.recv() => match c {
                    Ok(c) => Some(c),
                    Err(RecvError::Lagged(_)) => None,
                    Err(RecvError::Closed) => break,
                },
                t = transitions.recv() => match t {
                    Ok(_) | Err(RecvError::Lagged(_)) => None,
                    Err(RecvError::Closed) => break,
                },
            };
            if let Some(StateChange::EventApplied(IpcEvent::AccessRequestCreated {
                request_id,
                src_device_id,
                src_device_display_name,
                proto,
                dst_port,
                service_name,
                ..
            })) = first
            {
                sink(UiNotice::AccessRequest {
                    request_id,
                    src_device_id,
                    src_device_display_name,
                    proto,
                    dst_port,
                    service_name,
                });
            }
            // Coalesce the rest of the burst.
            tokio::time::sleep(DEBOUNCE).await;
            loop {
                match changes.try_recv() {
                    Ok(StateChange::EventApplied(IpcEvent::AccessRequestCreated {
                        request_id,
                        src_device_id,
                        src_device_display_name,
                        proto,
                        dst_port,
                        service_name,
                        ..
                    })) => sink(UiNotice::AccessRequest {
                        request_id,
                        src_device_id,
                        src_device_display_name,
                        proto,
                        dst_port,
                        service_name,
                    }),
                    Ok(_) | Err(tokio::sync::broadcast::error::TryRecvError::Lagged(_)) => {}
                    Err(_) => break,
                }
            }
            while transitions.try_recv().is_ok() {}
            sink(UiNotice::Changed);
        }
    })
}
