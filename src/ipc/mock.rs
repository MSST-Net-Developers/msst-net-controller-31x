//! Mock IPC transport for testing.
//!
//! Provides an in-memory channel-based transport that can be driven by tests
//! without requiring a real Core process or platform sockets.

use crate::error::{ControllerError, Result};
use msst_ipc_protocol::IpcMessage;
use tokio::sync::mpsc;

/// A mock IPC endpoint backed by in-memory channels.
///
/// Create a pair with [`mock_pair`], then use one end as the "Controller side"
/// and drive the other from the test.
pub struct MockTransport {
    tx: mpsc::UnboundedSender<IpcMessage>,
    rx: mpsc::UnboundedReceiver<IpcMessage>,
}

impl MockTransport {
    /// Send a message from this side.
    pub async fn send(&mut self, msg: &IpcMessage) -> Result<()> {
        self.tx.send(msg.clone()).map_err(|e| ControllerError::Io(e.to_string()))
    }

    /// Receive a message on this side. Returns `None` if the other side closed.
    pub async fn recv(&mut self) -> Option<IpcMessage> {
        self.rx.recv().await
    }

    /// Try to receive without blocking.
    pub fn try_recv(&mut self) -> Option<IpcMessage> {
        self.rx.try_recv().ok()
    }
}

/// Create a connected pair of mock transports.
///
/// Messages sent on one end are received on the other.
pub fn mock_pair() -> (MockTransport, MockTransport) {
    let (tx_a, rx_b) = mpsc::unbounded_channel();
    let (tx_b, rx_a) = mpsc::unbounded_channel();
    (
        MockTransport { tx: tx_a, rx: rx_a },
        MockTransport { tx: tx_b, rx: rx_b },
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use msst_ipc_protocol::{IpcEvent, IpcMessage, IpcRequest, IpcResponse};

    #[tokio::test]
    async fn mock_pair_roundtrip() {
        let (mut controller, mut core) = mock_pair();

        let req = IpcMessage::Request {
            id: "test-1".into(),
            body: IpcRequest::Health,
        };
        controller.send(&req).await.unwrap();

        let received = core.recv().await.unwrap();
        match received {
            IpcMessage::Request { id, body } => {
                assert_eq!(id, "test-1");
                assert!(matches!(body, IpcRequest::Health));
            }
            _ => panic!("Expected Request"),
        }

        let resp = IpcMessage::Response {
            id: "test-1".into(),
            body: IpcResponse::Ok(serde_json::json!({"status": "ok"})),
        };
        core.send(&resp).await.unwrap();

        let received = controller.recv().await.unwrap();
        match received {
            IpcMessage::Response { id, body } => {
                assert_eq!(id, "test-1");
                assert!(matches!(body, IpcResponse::Ok(_)));
            }
            _ => panic!("Expected Response"),
        }
    }

    #[tokio::test]
    async fn mock_pair_event() {
        let (mut controller, mut core) = mock_pair();

        let event = IpcMessage::Event {
            body: IpcEvent::NodeAdded {
                device_id: "device-deadbeef".into(),
                ipv4: "172.22.1.2".into(),
                ipv6: "fd22::1".into(),
                hostname: Some("test.ezplay.msst.net".into()),
                display_name: Some("Test PC".into()),
            },
        };
        core.send(&event).await.unwrap();

        let received = controller.recv().await.unwrap();
        assert!(matches!(received, IpcMessage::Event { body: IpcEvent::NodeAdded { .. } }));
    }

    #[tokio::test]
    async fn mock_pair_close() {
        let (mut controller, core) = mock_pair();
        drop(core);
        assert!(controller.recv().await.is_none());
    }
}