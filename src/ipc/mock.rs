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
//!
//! With a protocol 0.4 version (the default) it also behaves like a 0.4
//! Core: events carry `seq`, responses `event_seq`, `Health` reports
//! `ipc_role`, commands above the role are refused, `async` commands run as
//! operations (`OperationCompleted`, `GetOperation`).

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use msst_ipc_protocol::access::RequestKind;
use msst_ipc_protocol::views::{OperationAccepted, OperationStatus, OperationView};
use msst_ipc_protocol::{IpcError, IpcEvent, IpcMessage, IpcRequest, IpcResponse, PROTOCOL_VERSION, Role};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::broadcast;

use super::client::Connector;
use super::transport::{self, Frame, IpcStream};
use crate::error::ControllerError;

type Handler = dyn Fn(&IpcRequest) -> Result<serde_json::Value, IpcError> + Send + Sync;

/// Per-run state that a real Core loses when it restarts.
#[derive(Default)]
struct RunState {
    /// Last event `seq` emitted.
    last_seq: u64,
    next_op: u64,
    /// Finished operations: ID → (request type, result).
    operations: HashMap<String, (String, IpcResponse)>,
}

/// A scriptable in-process Core.
#[derive(Clone)]
pub struct FakeCore {
    handler: Arc<Mutex<Arc<Handler>>>,
    events: broadcast::Sender<IpcMessage>,
    run: Arc<Mutex<RunState>>,
    /// Role given to every connection (protocol 0.4).
    role: Arc<Mutex<Role>>,
    /// Don't send `OperationCompleted` (as if the event were missed).
    hold_completions: Arc<Mutex<bool>>,
    /// Reject every Controller after the handshake with an
    /// `incompatible_version` frame (as a 0.4 Core of another major would).
    reject_handshake: Arc<Mutex<bool>>,
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
            run: Arc::default(),
            role: Arc::new(Mutex::new(Role::Admin)),
            hold_completions: Arc::default(),
            reject_handshake: Arc::default(),
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

    pub fn set_role(&self, role: Role) {
        *self.role.lock().unwrap() = role;
    }

    pub fn set_hold_completions(&self, hold: bool) {
        *self.hold_completions.lock().unwrap() = hold;
    }

    pub fn set_reject_handshake(&self, reject: bool) {
        *self.reject_handshake.lock().unwrap() = reject;
    }

    /// Simulate Core stopping (drops live connections, refuses new ones;
    /// `seq` and operations restart) or starting.
    pub fn set_running(&self, running: bool) {
        *self.running.lock().unwrap() = running;
        if !running {
            *self.run.lock().unwrap() = RunState::default();
            let _ = self.kill.send(());
        }
    }

    fn speaks_0_4(&self) -> bool {
        msst_ipc_protocol::version::decode_version(*self.version.lock().unwrap()).1 >= 4
    }

    /// Drop all live connections (Core restart / lagging-client disconnect).
    pub fn drop_connections(&self) {
        let _ = self.kill.send(());
    }

    /// Push an event to every connected Controller (with the next `seq`,
    /// except log lines, when speaking protocol 0.4).
    pub fn push_event(&self, event: IpcEvent) {
        let seq = (self.speaks_0_4() && !matches!(event, IpcEvent::Diagnostic { .. })).then(|| {
            let mut run = self.run.lock().unwrap();
            run.last_seq += 1;
            run.last_seq
        });
        let _ = self.events.send(IpcMessage::Event { body: event, seq });
    }

    /// Emit `n` events that never reach any Controller (a gap in `seq`).
    pub fn lose_events(&self, n: u64) {
        self.run.lock().unwrap().last_seq += n;
    }

    /// Send an event frame exactly as given (e.g. a duplicate `seq`).
    pub fn push_raw_event(&self, event: IpcEvent, seq: Option<u64>) {
        let _ = self.events.send(IpcMessage::Event { body: event, seq });
    }

    /// IDs of operations started with `async`, oldest first.
    pub fn operation_ids(&self) -> Vec<String> {
        let mut ids: Vec<String> = self.run.lock().unwrap().operations.keys().cloned().collect();
        ids.sort_by_key(|id| id.trim_start_matches("op-").parse::<u64>().unwrap_or(0));
        ids
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
        if *self.reject_handshake.lock().unwrap() {
            let why = IpcMessage::Response {
                id: "handshake".into(),
                body: IpcResponse::Error(IpcError { code: "incompatible_version".into(), message: "fake Core rejects".into() }),
                event_seq: None,
            };
            let _ = transport::send_frame(&mut writer, &why).await;
            return;
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
                    Some(Frame::Message(IpcMessage::Request { id, body, r#async })) => {
                        self.requests.lock().unwrap().push(body.clone());
                        let (resp, completed) = self.respond(&body, r#async);
                        let event_seq = self.speaks_0_4().then(|| self.run.lock().unwrap().last_seq);
                        if transport::send_frame(&mut writer, &IpcMessage::Response { id, body: resp, event_seq }).await.is_err() {
                            break;
                        }
                        // After the accepted response, like Core.
                        if let Some(ev) = completed {
                            self.push_event(ev);
                        }
                    }
                    Some(_) => {}
                    None => break,
                },
                ev = events.recv() => match ev {
                    Ok(msg) => {
                        if transport::send_frame(&mut writer, &msg).await.is_err() {
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

    /// Answer one request; for an `async` command also the
    /// `OperationCompleted` event to send after the response.
    fn respond(&self, body: &IpcRequest, run_async: bool) -> (IpcResponse, Option<IpcEvent>) {
        let v04 = self.speaks_0_4();
        let role = *self.role.lock().unwrap();
        if v04 && body.required_role() > role {
            let e = IpcError { code: "permission_denied".into(), message: format!("this connection is {}", role.as_str()) };
            return (IpcResponse::Error(e), None);
        }
        if v04 && let IpcRequest::GetOperation { operation_id } = body {
            let run = self.run.lock().unwrap();
            return match run.operations.get(operation_id) {
                Some((request_type, result)) => (
                    IpcResponse::ok(&OperationView {
                        operation_id: operation_id.clone(),
                        request_type: request_type.clone(),
                        status: OperationStatus::Completed,
                        stage: None,
                        result: Some(result.clone()),
                        started_at: 0,
                        finished_at: Some(0),
                    }),
                    None,
                ),
                None => (IpcResponse::Error(IpcError { code: "not_found".into(), message: "no operation".into() }), None),
            };
        }

        let handler = self.handler.lock().unwrap().clone();
        let mut result = match handler(body) {
            Ok(v) => IpcResponse::Ok(v),
            Err(e) => IpcResponse::Error(e),
        };
        if v04 && let (IpcRequest::Health, IpcResponse::Ok(serde_json::Value::Object(m))) = (body, &mut result) {
            m.entry("ipc_role").or_insert_with(|| role.as_str().into());
        }
        if !(v04 && run_async && body.kind() == RequestKind::Command) {
            return (result, None);
        }

        let request_type = serde_json::to_value(body).ok().and_then(|v| v["type"].as_str().map(str::to_string)).unwrap_or_default();
        let operation_id = {
            let mut run = self.run.lock().unwrap();
            run.next_op += 1;
            let id = format!("op-{}", run.next_op);
            run.operations.insert(id.clone(), (request_type.clone(), result.clone()));
            id
        };
        let completed = (!*self.hold_completions.lock().unwrap())
            .then(|| IpcEvent::OperationCompleted { operation_id: operation_id.clone(), request_type, result });
        (IpcResponse::ok(&OperationAccepted { operation_id }), completed)
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
        IpcRequest::SetLogStream { enabled } => json!({ "log_stream": enabled }),
        _ => json!({ "ok": true }),
    })
}
