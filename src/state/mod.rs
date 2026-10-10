//! State management — domain types and the state store.

pub mod store;
pub mod types;

pub use store::{ResourceData, StateChange, StateStore};
pub use types::*;