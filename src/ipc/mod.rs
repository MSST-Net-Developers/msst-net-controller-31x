//! IPC layer — transport, client, and testing utilities.

pub mod client;
pub mod connection;
pub mod handshake;
#[allow(dead_code)] // Test utilities used by integration tests
pub mod mock;
pub mod sequence;
pub mod transport;