# IPC Protocol Proposals (Controller → Core team)

Baseline when written: `msst-ipc-protocol` 0.2.0.  Core answered in
`msst-net-core-31x/docs/PROTOCOL_PROPOSALS_3.1_RESPONSE.md` (protocol 0.3.0).

## Controller follow-up (protocol 0.3)

| # | Core | Controller |
|---|------|-----------|
| P1 | vectors | `tests/core_contract.rs` decodes every vector with the Controller's decoders and applies `events.json` to the store |
| P2 | events emitted | `RoomStateChanged` / `NodeStateChanged` applied directly; `AccessRequestResolved`, `IdentityChanged`, `SettingsChanged` handled; on 0.3 the periodic poll is Health only (older Cores keep the room/node/pending poll) |
| P3 | `ClearAclRules` | used when the Core speaks ≥ 0.3; one-by-one fallback kept |
| P4 | `generation` defined | full resync re-queried if `generation` moved during it |
| P5 | deferred | — |
| P6 | unknown patch fields rejected | shown as validation errors; edit dialog unchanged |
| P7 | MTU 1280–1500 | UI uses Core's range (desktop, WebUI, Android) |
| P8 | `EmbeddedCore::start(…, protect)` | JNI glue passes `VpnService.protect` on the running service (no service → socket refused); lockdown mode not yet tried on a device |
| P9 | fixed | `null` handled; `tun_ready` shown as reported |
| P10 | `Health.access_auth_required` | authoritative flag; event kept as fallback |
| P11 | fixed | embedded start errors are reported by the JNI glue |
| P12 | `errors::ALL` | every code mapped to a user message (test fails on a new unmapped code); Core text is `detail` |

Verified against a rebuilt 0.3.0 Core (sandboxed): negotiated 0.3.0,
atomic clear, event-driven ping / identity updates, MTU and patch validation
errors, Core restart → resync.

Core-side observations from 0.3 (fixed in Core a293c19):
- `UpdateDisplayName` / `UpdateHandle` replies return `null` for unset fields.
- The own device's IPv6 in room views and `virtual_ips` is canonical
  (`fd22:2222:2222:2222::1`).

---

Original proposals:

## P1. Contract test vectors (no wire change)

- Problem: response/event payloads are `serde_json::Value`; Controller drifted
  from Core's actual shapes (snapshot keys, rule `permission_group`, pending
  auth `expires_in_secs`).
- Proposal: add `crates/msst-ipc-protocol/tests/vectors/*.json`, one file per
  query response, generated from Core's handlers; Controller tests decode them.
- Affected: protocol crate (tests only), Core (generator test), Controller tests.
- Compatibility: none. Deployment: protocol crate → Core → Controller.

## P2. Missing events (minor bump, additive)

Defined but never emitted by Core: `NodeRemoved`, `NodeStateChanged`.
No event exists for: auth request resolved, room left / member joined/left,
identity (display name / handle) changed, ping / net settings changed.
`RoomStateChanged` is emitted with `relay: None` and peers without addresses.

- Proposal: emit the defined events; add `AccessRequestResolved { request_id }`,
  `IdentityChanged`, `SettingsChanged`; populate `RoomStateChanged` like
  `GetSystemState`.
- Interim Controller behaviour: re-query after commands and poll
  Health/Room/Nodes periodically while connected.
- Compatibility: older Controllers ignore unknown event types only if they
  tolerate undecodable frames (3.1.x Controller does after this fix).

## P3. `ClearAclRules` (minor bump)

3.0.x had `DELETE /firewall/rules`. Interim: Controller deletes one by one and
reports partial failures. Proposal: `ClearAclRules` returning deleted count.

## P4. `generation` semantics

`Health` and `GetSystemState` return `generation`, undocumented. Please define
whether it can be used to detect changes between the multiple queries the
Controller uses to rebuild its view.

## P5. Event sequence numbers (re-targets PROTOCOL_CHANGES.md §2)

The earlier proposal assumed version 0.2.0, which is already used by
`GetServices`. If approved it must target 0.3.0. The Controller no longer
assumes any seq capability.

## P6. Full rule update (minor bump, additive)

`UpdateAclRule` only changes `enabled`, `comment`, `expires_at` (same as
3.0.x `PUT /firewall/rules/:id`).  The 3.0.x edit wizard sent sources/ports
too and they were silently ignored.  3.1.x Controllers edit only what Core
supports and offer "copy as new rule".  Proposal: accept the full
`RuleRequest` in `UpdateAclRule` (atomic replace, same id), or reject unknown
patch fields with `invalid_patch` so clients cannot silently lose edits.

## P7. Validate network settings in Core

`SetNetSettings` stores `mtu_override` via `as u16` without range checks
(70000 wraps to 4464).  Controllers reject values outside 1–65535, but Core
should validate (and define the accepted MTU range) since it is the authority.

## P8. Socket protection for the embedded (Android) Core

3.0.x called `VpnService.protect(fd)` on Core's UDP socket.
`EmbeddedCore` (3.1.x) exposes no socket fds or protect callback.  The VPN
only routes the MSST ranges, so this works normally, but with Android's
"always-on VPN + block connections without VPN" Core loses its coordinator
and relay connectivity.  Proposal: `EmbeddedCore::start(..., protect: impl
Fn(RawFd) -> bool)` called for every socket Core opens (UDP data plane,
coordinator HTTP/WebSocket).

## P9. `Health` accuracy

- `tun_ready` is `true` when TUN creation failed and Core runs relay-only
  (observed: "TUN creation failed … running without TUN", `tun_ready: true`).
- Unset names/handles are returned as `""` instead of `null`.

## P10. Startup events and auth state

Events emitted before the IPC server starts are dropped ("IPC event dropped:
channel closed"), including `AccessAuthRequired`, and that state is not
queryable afterwards (only `is_authenticated`).  Proposal: expose
`access_auth_required` in `Health`, or start the IPC server before the
coordinator client.

## P11. IPC bind failure

If the IPC socket cannot be bound (e.g. path longer than `SUN_LEN`), Core
logs an error and keeps running without IPC — unmanageable and invisible to
users.  Proposal: treat it as a startup failure (exit non-zero / `start()`
error for the embedded Core).

## P12. User-facing error messages

Infrastructure errors carry raw transport text (`error sending request for
url (http://…)`).  Controllers now map known codes to user messages and keep
the raw text as `detail`; a documented list of error codes in the protocol
crate would keep that mapping complete.

## Observed during verification

The `target/debug/msst-net-core` binary in the Core repo reports protocol
0.1.0 while `PROTOCOL_VERSION` in source is 0.2.0 — the binary predates the
version bump.  Rebuild Core before deployment; the Controller handles 0.1
(GetServices disabled with an explanation) but desktop users would lose the
service list.
