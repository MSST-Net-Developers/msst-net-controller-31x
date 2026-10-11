//! Fake Core for tests.
//!
//! Speaks the real wire protocol (version handshake + length-prefixed JSON
//! frames) over in-memory duplex streams, so the [`super::client::IpcClient`]
//! can be tested end to end without a running Core or platform sockets.
//!
//! ```ignore
//! let core = FakeCore::new(|req| match req { IpcRequest::Health => Ok(json!({...})), _ => Ok(json!({})) });
//! let client = IpcClient::with_connector(cfg, store, core.connector());
//! core.push_event(IpcEvent::CoordinatorConnectionChanged { connected: true });
//! ```

use std::sync::{Arc, Mutex};

use msst_ipc_protocol::{IpcError, IpcEvent, IpcMessage, IpcRequest, IpcResponse, PROTOCOL_VERSION};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::broadcast;

use super::client::Connector;
use super::transport::{self, Frame, IpcStream};
use crate::error::ControllerError;

type Handler = dyn Fn(&IpcRequest) -> Result<serde_json::Value, IpcError> + Send + Sync;

/// A scriptable in-process Core.
#[derive(Clone)]
pub struct FakeCore {
    handler: Arc<Mutex<Arc<Handler>>>,
    events: broadcast::Sender<IpcEvent>,
    /// Every request received, in order.
    requests: Arc<Mutex<Vec<IpcRequest>>>,
    /// Protocol version sent in the handshake.
    version: Arc<Mutex<u32>>,
    /// When false, connection attempts fail as if Core were not running.
    running: Arc<Mutex<bool>>,
    /// Kill switch for live connections.
    kill: broadcast::Sender<()>,
}

impl FakeCore {
    pub fn new(handler: impl Fn(&IpcRequest) -> Result<serde_json::Value, IpcError> + Send + Sync + 'static) -> Self {
        Self {
            handler: Arc::new(Mutex::new(Arc::new(handler))),
            events: broadcast::channel(256).0,
            requests: Arc::default(),
            version: Arc::new(Mutex::new(PROTOCOL_VERSION)),
            running: Arc::new(Mutex::new(true)),
            kill: broadcast::channel(4).0,
        }
    }

    /// Replace the request handler.
    pub fn set_handler(&self, handler: impl Fn(&IpcRequest) -> Result<serde_json::Value, IpcError> + Send + Sync + 'static) {
        *self.handler.lock().unwrap() = Arc::new(handler);
    }

    pub fn set_version(&self, v: u32) {
        *self.version.lock().unwrap() = v;
    }

    /// Simulate Core stopping (drops live connections, refuses new ones) or starting.
    pub fn set_running(&self, running: bool) {
        *self.running.lock().unwrap() = running;
        if !running {
            let _ = self.kill.send(());
        }
    }

    /// Drop all live connections (Core restart / lagging-client disconnect).
    pub fn drop_connections(&self) {
        let _ = self.kill.send(());
    }

    /// Push an event to every connected Controller.
    pub fn push_event(&self, event: IpcEvent) {
        let _ = self.events.send(event);
    }

    pub fn requests(&self) -> Vec<IpcRequest> {
        self.requests.lock().unwrap().clone()
    }

    pub fn clear_requests(&self) {
        self.requests.lock().unwrap().clear();
    }

    /// A connector for `IpcClient::with_connector`.
    pub fn connector(&self) -> Connector {
        let core = self.clone();
        Arc::new(move || {
            let core = core.clone();
            Box::pin(async move { core.accept() })
        })
    }

    fn accept(&self) -> crate::error::Result<IpcStream> {
        if !*self.running.lock().unwrap() {
            return Err(ControllerError::ConnectionFailed { path: "fake".into(), reason: "Core not running".into() });
        }
        let (client, server) = tokio::io::duplex(256 * 1024);
        tokio::spawn(self.clone().serve(server));
        Ok(Box::new(client))
    }

    /// Serve one Controller connection accepted on a real transport (e.g. a
    /// Unix socket), for tests that exercise the platform connector.
    pub fn serve_stream(&self, stream: impl transport::AsyncStream + 'static) {
        tokio::spawn(self.clone().serve(stream));
    }

    async fn serve(self, stream: impl transport::AsyncStream + 'static) {
        let (mut reader, mut writer) = tokio::io::split(stream);
        let version = *self.version.lock().unwrap();
        if writer.write_all(&version.to_be_bytes()).await.is_err() {
            return;
        }
        let mut remote = [0u8; 4];
        if reader.read_exact(&mut remote).await.is_err() {
            return;
        }
        if !msst_ipc_protocol::version::is_compatible(version) {
            return; // Core closes on mismatch too.
        }
        let mut events = self.events.subscribe();
        let mut kill = self.kill.subscribe();
        let (frames_tx, mut frames_rx) = tokio::sync::mpsc::unbounded_channel();
        let read_task = tokio::spawn(async move {
            while let Ok(Some(f)) = transport::recv_frame(&mut reader).await {
                if frames_tx.send(f).is_err() {
                    break;
                }
            }
        });
        loop {
            tokio::select! {
                f = frames_rx.recv() => match f {
                    Some(Frame::Message(IpcMessage::Request { id, body })) => {
                        self.requests.lock().unwrap().push(body.clone());
                        let handler = self.handler.lock().unwrap().clone();
                        let resp = match handler(&body) {
                            Ok(v) => IpcResponse::Ok(v),
                            Err(e) => IpcResponse::Error(e),
                        };
                        if transport::send_frame(&mut writer, &IpcMessage::Response { id, body: resp }).await.is_err() {
                            break;
                        }
                    }
                    Some(_) => {}
                    None => break,
                },
                ev = events.recv() => match ev {
                    Ok(body) => {
                        if transport::send_frame(&mut writer, &IpcMessage::Event { body }).await.is_err() {
                            break;
                        }
                    }
                    Err(_) => break,
                },
                _ = kill.recv() => break,
            }
        }
        read_task.abort();
    }
}

/// Core-shaped responses for every query the Controller issues on sync.
pub fn default_responses(req: &IpcRequest) -> Result<serde_json::Value, IpcError> {
    use serde_json::json;
    Ok(match req {
        IpcRequest::Health => json!({ "status": "ok", "is_authenticated": false, "ws_connected": true, "ping_enabled": true }),
        IpcRequest::GetSystemState => json!({ "room_id": null, "room_name": null, "relay": null, "peers": [] }),
        IpcRequest::GetNodes => json!({ "nodes": [] }),
        IpcRequest::GetPathView => json!({ "peers": [] }),
        IpcRequest::GetAclRules => json!({ "rules": [] }),
        IpcRequest::GetAclGroups => json!({ "groups": [] }),
        IpcRequest::GetPendingAuth => json!({ "requests": [] }),
        _ => json!({ "ok": true }),
    })
}
