# MSST-Net Controller 3.1.x — Implementation Plan

## Executive Summary

Rebuild the Controller as a layered Rust application that communicates with Core exclusively through the shared `msst-ipc-protocol` crate. The first phase delivers the IPC transport, state management, and application scaffold — everything needed before any GUI work begins.

## Guiding Principles

1. **Core is authoritative.** Controller never infers state it did not receive from Core.
2. **One protocol, one crate.** Both Core and Controller depend on `msst-ipc-protocol` for message types. No duplicated definitions.
3. **Layered architecture.** Transport → IPC Client → State Store → Presentation. Each layer is testable in isolation.
4. **Explicit state machines.** Connection lifecycle and UI states are represented as enums, never as scattered booleans.
5. **Test everything.** Every layer has unit tests. Integration tests use mock transports.

---

## Phase 1 — P0: IPC Infrastructure & State Foundation

**Goal:** A running Controller binary that connects to Core, performs the version handshake, receives events, and maintains an authoritative local state view. No GUI yet.

### Milestone M1: Project Skeleton & Protocol Integration

- [x] T1: Write PLAN.md
- [x] T2: Configure `Cargo.toml` with `msst-ipc-protocol` dependency (path dep to Core workspace)
- [x] T3: Implement IPC transport layer (framing, version handshake) — 241 lines, 2 tests
- [x] T4: Define domain state types — 137 lines, 6 domain types
- [x] T5: Implement state store with event application — 454 lines, 7 tests
- [x] T6: Implement IPC client with reconnection — 358 lines, 2 tests
- [x] T7: Application scaffold with lifecycle — basic Ctrl+C handler

### Milestone M1.5: P0 Hardening & Integration

- [x] T8: Wire app lifecycle — connect IPC client on startup, subscribe to events, log state changes
- [x] T9: Clean compiler warnings (removed dead `PendingRequest`, suppressed mock module warnings)
- [x] T10: Add integration test — 9 tests covering snapshot, events, auth lifecycle, ACL, node lifecycle, coordinator connection, subscription, mock transport
- [x] T11: Add CLI argument parsing (--ipc-path, --log-level) via clap
- [x] T12: Add graceful shutdown — IPC client shutdown signal, interruptible reconnect sleep, App calls shutdown on exit

### Architecture

```
src/
├── main.rs              — Entry point, tokio runtime, signal handling
├── ipc/
│   ├── mod.rs           — Module re-exports
│   ├── transport.rs     — Length-prefixed framing, version handshake
│   ├── client.rs        — IPC client: send requests, receive events, reconnect
│   └── mock.rs          — Mock transport for testing
├── state/
│   ├── mod.rs           — Module re-exports
│   ├── types.rs         — Domain state types (ConnectionState, HealthState, etc.)
│   └── store.rs         — StateStore: applies events, exposes read snapshots
└── app.rs               — Application lifecycle: startup, run, shutdown
```

### Task Details

#### T2: Cargo.toml Configuration

- Add `msst-ipc-protocol` as path dependency to `../msst-net-core-31x/crates/msst-ipc-protocol`
- Add runtime deps: `tokio`, `serde`, `serde_json`, `tracing`, `tracing-subscriber`, `anyhow`, `thiserror`
- Add dev deps: `tokio-test` or similar

#### T3: IPC Transport Layer (`src/ipc/transport.rs`)

Responsibilities:
- Read/write length-prefixed frames (4-byte BE u32 + payload)
- Perform version handshake on connect (read Core's version u32, check compatibility)
- Platform abstraction: Unix socket (cfg(unix)) / Named pipe (cfg(windows))
- No reconnection logic here — that belongs in the client layer

Key types:
```rust
pub trait IpcTransport: Send + Sync {
    async fn send(&mut self, msg: &IpcMessage) -> Result<()>;
    async fn recv(&mut self) -> Result<IpcMessage>;
    fn is_connected(&self) -> bool;
    async fn close(&mut self);
}
```

#### T4: Domain State Types (`src/state/types.rs`)

Types derived from Core's event model:
- `ConnectionStatus` enum: Disconnected / Connecting / Connected / Reconnecting
- `HealthState`: status, identity fields, auth state
- `NodeInfo`: device_id, ipv4, ipv6, hostname, display_name, path_mode
- `RoomState`: room_id, room_name, relay, peers
- `AclRule` / `AclGroup`: mirrors Core's models
- `PendingAuthRequest`: request_id, source info, service, expiry

#### T5: State Store (`src/state/store.rs`)

- `StateStore` holds all domain state behind `Arc<RwLock<_>>`
- `apply_event(&self, event: &IpcEvent)` — processes push events
- `apply_snapshot(&self, snapshot: serde_json::Value)` — applies full state from `GetSystemState`
- `snapshot(&self) -> SystemState` — returns current view for UI consumption
- Thread-safe, no interior mutability hazards

#### T6: IPC Client (`src/ipc/client.rs`)

- Owns the transport and state store
- Manages connection lifecycle with exponential backoff reconnection
- On connect: version handshake → `GetSystemState` snapshot → event loop
- On disconnect: mark Reconnecting, attempt reconnect with backoff (1s → 2s → max 3s; Core is local, so retries are cheap)
- `send_request(&self, req: IpcRequest) -> Result<IpcResponse>` with request ID correlation and timeout
- `subscribe_events(&self) -> broadcast::Receiver<IpcEvent>` for UI layer

#### T7: Application Scaffold (`src/app.rs` + `src/main.rs`)

- Parse CLI args (socket path override, log level)
- Initialize tracing
- Create StateStore
- Create IpcClient
- Run event loop until shutdown signal
- Graceful shutdown: close transport, flush state

---

## Phase 2 — P1: Reliability & Diagnostics ✅

- [x] Structured error types (`src/error.rs`) — `ControllerError` enum with 12 variants, `is_retryable()`/`is_fatal()`/`category()` methods, `anyhow` fully removed
- [x] Connection status state machine (`src/ipc/connection.rs`) — `ConnectionStateMachine` with transition validation, `ConnectionDiagnostics` (reconnect count, success rate, stability), transition history (bounded 64), broadcast subscription
- [x] Event sequence tracker (`src/ipc/sequence.rs`) — `SequenceTracker` with gap detection, resync request emission, graceful degradation (works with/without seq), diagnostics
- [x] Protocol change proposal (`PROTOCOL_CHANGES.md`) — `seq` field on `IpcEvent`, `GetEventsSince` request, backward-compatible design

### Phase 2 Test Count: 55 (46 unit + 9 integration)

## Phase 3 — Protocol Hardening ✅

- [x] Bidirectional version handshake (`src/ipc/handshake.rs`) — sends Controller version, reads Core version, returns `HandshakeResult` with capabilities
- [x] Capability negotiation (`Capabilities` struct) — feature flags derived from remote version (event_seq, events_since, extended_auth)
- [x] Degraded mode detection — logs warning when operating with older Core, disables features requiring newer minor version
- [x] Core version sending (Core `src/ipc/mod.rs`) — both Unix and TCP handlers now send `PROTOCOL_VERSION` as first 4 bytes on connection
- [x] Client integration — `IpcClient` stores `HandshakeResult`, exposes `handshake_result()` for capability queries

### Phase 3 Test Count: 59 (50 unit + 9 integration)

## Phase 4 — UI Integration Layer ✅

### Tauri Integration (`src/tauri_commands.rs`, feature = "tauri")

- [x] `ControllerState` wrapper for Tauri's `.manage()` — holds `IpcClient` + `StateStore`
- [x] Connection management commands: `get_connection_status`, `get_connection_diagnostics`
- [x] State query commands: `get_system_state`, `get_health`, `get_nodes`, `get_room`, `get_acl_rules`, `get_acl_groups`, `get_pending_auth`
- [x] Room commands: `create_room`, `join_room`, `leave_room`
- [x] ACL commands: `create_acl_rule`, `update_acl_rule`, `delete_acl_rule`
- [x] Auth commands: `submit_auth_decision` (with `AuthDecision` enum mapping)
- [x] Network/diagnostics commands: `get_net_settings`, `set_net_settings`, `get_path_view`, `get_traffic_stats`, `get_endpoints`, `refresh_endpoint`, `get_relays`, `set_ping_enabled`, `start_diag_ping`, `get_diag_ping_result`
- [x] Identity commands: `update_display_name`, `update_handle`, `re_register`
- [x] Log commands: `get_logs`, `clear_logs`
- [x] All commands follow AGENTS.md §5.2: request accepted ≠ operation completed

### WebUI Backend (`src/webui.rs`, feature = "webui")

- [x] Axum HTTP server with REST API
- [x] State query endpoints: `/api/state`, `/api/health`, `/api/nodes`, `/api/room`, `/api/acl/rules`, `/api/acl/groups`, `/api/auth/pending`, `/api/connection`, `/api/diagnostics`
- [x] Command endpoints: room create/join/leave, ACL CRUD, auth decision, net settings, identity, logs
- [x] WebSocket endpoint `/ws/events` — streams `StateChange` events as JSON to browser
- [x] Structured HTTP error mapping (`ControllerError` → appropriate HTTP status codes)
- [x] `build_router()` and `serve()` functions for easy integration
- [x] 8 route tests covering GET endpoints and 404 handling

### Architecture

```text
src/
├── lib.rs               — Feature-gated module exports
├── tauri_commands.rs     — #[tauri::command] functions (feature = "tauri")
├── webui.rs              — Axum server + routes (feature = "webui")
├── app.rs               — Core application lifecycle
├── error.rs             — Structured error types
├── ipc/
│   ├── client.rs        — IPC client with request/response
│   ├── connection.rs    — Connection state machine
│   ├── handshake.rs     — Version negotiation
│   ├── sequence.rs      — Event sequence tracking
│   ├── transport.rs     — Length-prefixed framing
│   └── mock.rs          — Test transport
└── state/
    ├── types.rs         — Domain state types
    └── store.rs         — Event-driven state store

tests/
├── ipc_integration.rs   — 9 IPC flow tests
└── webui_routes.rs      — 8 HTTP route tests (feature = "webui")
```

### Phase 4 Test Count: 67 (50 unit + 9 integration + 8 WebUI)

## Phase 5 — Frontend & Tauri App Shell ✅

### SvelteKit Frontend (`frontend/`)

- [x] Project scaffolding — `package.json`, `svelte.config.js`, `vite.config.js`, `tsconfig.json`, `app.html`
- [x] TypeScript types (`src/lib/types/index.ts`) — mirrors Rust domain models: `SystemState`, `HealthState`, `NodeInfo`, `RoomState`, `AclRule`, `AclGroup`, `PendingAuthRequest`, `ConnectionStatus`, `WsEvent`
- [x] API client (`src/lib/api/client.ts`) — `fetch()`-based REST client + WebSocket connection with auto-reconnect, Tauri `invoke` detection
- [x] Reactive stores (`src/lib/stores/index.ts`) — Svelte 5 runes-based stores: `createConnectionStore`, `createStateStore`, `createAuthStore`, WebSocket bridge
- [x] StatusIndicator component — connection status dot + label
- [x] AuthDialog component — interactive auth request dialog with countdown timer, allow/deny actions
- [x] Main page (`+page.svelte`) — 5 tabs: 首页 (dashboard), 节点 (node list), 房间 (room management), 防御 (ACL rules), 设置 (identity)

### Tauri App Shell (`frontend/src-tauri/`)

- [x] `tauri.conf.json` — window config, build commands, security
- [x] `Cargo.toml` — depends on `msst-net-controller` with `features = ["tauri"]`
- [x] `lib.rs` — creates `ControllerState`, registers all 31 Tauri commands via `generate_handler!`
- [x] `main.rs` — entry point
- [x] Placeholder icons (32x32, 128x128, 256x256 RGBA PNG)

### Frontend Pages

| Tab | Content | API Endpoints |
|-----|---------|---------------|
| 首页 | Health, node count, room, rules summary | `/api/state` |
| 节点 | Node cards with device_id, hostname, ipv4, path_mode | `/api/nodes` |
| 房间 | Create/Join forms, peer list, leave button | `/api/room`, `/api/room/create`, `/api/room/join`, `/api/room/leave` |
| 防御 | ACL rules table with toggle/delete | `/api/acl/rules` |
| 设置 | Device ID, user ID, hostname display | `/api/health` |
| 授权弹窗 | Overlay dialog with countdown, 4 decision buttons | `/api/auth/pending`, `/api/auth/decision` |

### Architecture

```text
frontend/
├── package.json, svelte.config.js, vite.config.js, tsconfig.json
├── src/
│   ├── app.html
│   ├── lib/
│   │   ├── api/client.ts      — REST + WebSocket client
│   │   ├── types/index.ts     — TypeScript domain types
│   │   ├── stores/index.ts    — Svelte 5 rune stores
│   │   └── components/
│   │       ├── StatusIndicator.svelte
│   │       └── AuthDialog.svelte
│   └── routes/
│       ├── +layout.ts         — SSR disabled, prerender enabled
│       └── +page.svelte       — Main 5-tab UI
└── src-tauri/
    ├── tauri.conf.json
    ├── Cargo.toml
    └── src/
        ├── lib.rs             — Tauri command registration
        └── main.rs            — Entry point
```

### Phase 5 Test Count: 67 (unchanged — frontend has no Rust tests)

### Full Project Stats

| Metric | Value |
|--------|-------|
| Rust source files | 18 (src/) + 2 (tests/) + 2 (tauri backend) |
| Rust lines | 4,265 (library) + ~110 (tauri backend) |
| Svelte/TS files | 6 |
| Test count | 67 (50 unit + 9 integration + 8 WebUI) |
| Feature flags | default, tauri, webui |

### IPC Protocol (from `msst-ipc-protocol` v0.1.0)

- **Wire format:** 4-byte BE length prefix + JSON `IpcMessage` frame
- **Version handshake:** Core sends `PROTOCOL_VERSION` u32 as first 4 bytes on connect
- **Compatibility:** Same major version required; minor differences are forward-compatible
- **Message types:** Request (Controller→Core), Response (Core→Controller), Event (Core→Controller push)
- **Request correlation:** Each request has a unique `id`; response echoes the same `id`

### Affected Components When Protocol Changes

| Change | Core Impact | Controller Impact | Action |
|--------|------------|-------------------|--------|
| New IpcRequest variant | Add handler | Add method in client | Minor bump |
| New IpcEvent variant | Emit event | Handle in state store | Minor bump |
| Wire format change | Update framing | Update transport | Major bump |
| Field removal | Migration needed | Update types | Major bump |

### Testing Strategy

| Layer | Method | Dependencies |
|-------|--------|-------------|
| Transport | Unit tests with mock streams | None (in-memory) |
| State Store | Unit tests with synthetic events | None |
| IPC Client | Integration tests with mock transport | Mock transport |
| Application | Manual + smoke tests | Running Core |