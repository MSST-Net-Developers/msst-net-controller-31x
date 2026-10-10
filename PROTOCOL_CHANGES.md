# Protocol Change Proposals

## 1. Version Negotiation Handshake — IMPLEMENTED

### Status: IMPLEMENTED (both Core and Controller)

### Background

The original v0.1.0 protocol specified that Core sends its `PROTOCOL_VERSION` as
the first 4 bytes on connection, but the implementation was incomplete:
- Core did not send version bytes
- Controller's `version_handshake()` read bytes that were never sent
- No capability negotiation existed

### Implemented Change

**Wire format (unchanged)**:
```text
Core  → Controller:  4 bytes  PROTOCOL_VERSION (u32 BE)
Controller → Core:  4 bytes  PROTOCOL_VERSION (u32 BE)
```

Both sides send first, then read. This avoids deadlock. A 5-second timeout
prevents hung connections.

**Compatibility strategy**:
1. Same major version → compatible, proceed
2. Same major, different minor → degraded mode (newer features disabled)
3. Different major → incompatible, connection closed

**Affected components**:
| Component | Change | Status |
|-----------|--------|--------|
| Core `src/ipc/mod.rs` | Send version bytes on Unix + TCP connect | ✅ Done |
| Controller `src/ipc/handshake.rs` | New module: bidirectional handshake + capabilities | ✅ Done |
| Controller `src/ipc/client.rs` | Use handshake module, store result | ✅ Done |
| `msst-ipc-protocol` | No change needed (uses existing `PROTOCOL_VERSION`) | N/A |

---

## 2. Event Sequence Numbers — PROPOSED

### Status: PROPOSED (requires Core team approval)

> **Superseded (3.1.0-alpha.1 Controller fixes):** protocol 0.2 was used for
> `GetServices`, so this proposal must target 0.3 — see
> `docs/PROTOCOL_PROPOSALS_3.1.md` P5.  The Controller no longer contains a
> `SequenceTracker` or assumes any seq capability; it resyncs every resource
> after each reconnect instead.  The "Already implemented" notes below are
> obsolete.

## Background

The current IPC protocol (v0.1.0) does not include sequence numbers on events.
This means the Controller cannot detect when events are lost due to:
- IPC connection drops (events sent while Controller is disconnected)
- Broadcast channel lag (subscriber falls behind)
- Transport errors (frame corruption, partial reads)

## Proposed Change

### 1. Add `seq` field to `IpcEvent`

**Location**: `msst-ipc-protocol/src/message.rs` — `IpcEvent` enum

**Change**: Add an optional `seq` field to the event envelope.

Currently, events are serialized as:
```json
{"kind": "event", "body": {"type": "node_added", "data": {...}}}
```

Proposed format:
```json
{"kind": "event", "seq": 42, "body": {"type": "node_added", "data": {...}}}
```

**Backward compatibility**: The `seq` field is added to `IpcMessage::Event` as
`Option<u64>` with `#[serde(default)]`. Older Controllers that don't know about
`seq` will ignore it (serde's default behavior for unknown fields). Newer
Controllers receiving events from older Cores will see `None` and fall back to
count-only mode.

### 2. Add `GetEventsSince` request type

**Location**: `msst-ipc-protocol/src/message.rs` — `IpcRequest` enum

**Change**: Add a new request variant:
```rust
GetEventsSince { seq: u64 },
```

**Response**: Returns all events with `seq >= requested_seq` that Core still
has in its buffer. If the buffer has been exhausted, returns an error with
code `"events_expired"`.

**Purpose**: Allows Controller to request missed events after reconnection
instead of doing a full snapshot.

### 3. Add `events_seq` field to `GetSystemState` response

**Location**: The snapshot response payload.

**Change**: Include the current event sequence number in the snapshot:
```json
{
  "health": {...},
  "nodes": [...],
  "events_seq": 1234
}
```

**Purpose**: After applying a snapshot, the Controller knows the baseline
sequence number and can detect gaps from that point forward.

## Affected Components

| Component | Impact | Action |
|-----------|--------|--------|
| `msst-ipc-protocol` | Add `seq` to `IpcMessage::Event`, add `GetEventsSince` request | Minor version bump (0.2.0) |
| Core IPC server | Emit `seq` on events, handle `GetEventsSince` | Implement event buffer + seq counter |
| Controller transport | No change (frame format unchanged) | None |
| Controller state store | Pass `seq` to `SequenceTracker` | Small change |
| Controller IPC client | Use `SequenceTracker`, request resync on gap | Already implemented (`ipc::sequence`) |
| Tauri / WebUI | No change (reads from StateStore) | None |

## Compatibility Requirements

- **Old Controller + New Core**: Works. Controller ignores `seq` field.
- **New Controller + Old Core**: Works. Controller sees `seq: None` and uses
  count-only mode (no gap detection).
- **Wire format**: No change. `seq` is an optional JSON field.

## Testing Plan

1. **Unit tests** (Controller side): Already implemented in `ipc::sequence`.
2. **Integration test**: Controller ↔ Core with mock transport, verify gap
   detection triggers resync.
3. **Contract test**: Serialize/deserialize `IpcEvent` with and without `seq`
   to verify backward compatibility.
4. **Manual test**: Start Controller, kill Core, restart Core, verify Controller
   detects the gap and requests resync.

## Deployment Order

1. Update `msst-ipc-protocol` crate (minor bump)
2. Core: add `seq` counter and `GetEventsSince` handler
3. Controller: already ready (graceful degradation built in)
4. Deploy Core first, then Controller (safe in any order due to Option<_>)