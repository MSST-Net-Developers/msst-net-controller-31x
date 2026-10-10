# MSST-Net Controller 3.1.x — structure, build, and changes from 3.0.x

## Structure

```text
src/                 Controller library (shared by every front end)
  ipc/               transport, handshake, IpcClient (reconnect + resync), FakeCore for tests
  state/             StateStore: the view of Core state, built only from Core queries/events
  notify.rs          debounced change notices for UIs
  ops.rs             multi-request operations (clear rules)
  tauri_commands.rs  Tauri bridge: get_view / ipc_request / clear_acl_rules
  webui/             WebUI server (3.0.x security model)
  bin/msst-net-webui.rs
frontend/            SvelteKit UI for desktop (Tauri) and WebUI (browser)
  src-tauri/         Tauri shell: notifications, log export, window
android/             Android app (Compose) + rust/ JNI glue (embedded Core + this library)
docs/PROTOCOL_PROPOSALS_3.1.md   issues found in Core / protocol (not changed here)
```

Every front end uses the same three operations: read the Controller view,
send an `IpcRequest` from the shared `msst-ipc-protocol` crate, and get
notified when the view changed.  Core is the only source of networking
state.

## Build

| Target | Command |
|---|---|
| Library + tests | `cargo test --features webui` |
| Frontend | `cd frontend && pnpm install && pnpm run check && pnpm run build` |
| Desktop app | `cd frontend && pnpm tauri build` |
| WebUI | build the frontend, then `cargo build --release --features webui --bin msst-net-webui` (the frontend is embedded) |
| Android | `android/rust/build.sh` (needs cargo-ndk + NDK), then `cd android && ./gradlew assembleRelease` |

The Android glue and the desktop crate depend on the Core repo by path
(`../msst-net-core-31x`), as before.

### WebUI

Same as 3.0.x: config in `~/.config/msst-net-webui/` (existing `passwd` and
`config.toml` are reused), password from `PASSWORD_FILE` /
`/run/secrets/admin_password` / `ADMIN_PASSWORD` or set interactively on first
run, self-signed TLS per run, CIDR allowlist on every request, `WEBUI_BIND`
overrides the listen address.  New: the IPC socket path can be overridden
with `MSST_IPC_PATH`, cross-site WebSocket connections are rejected, and
failed logins are delayed.

### Android

Core runs inside the app (`EmbeddedCore`) with its data in the app's
`filesDir` — the 3.0.x location, so the device identity survives the
upgrade.  The UI talks to it over IPC (`filesDir/ipc.sock`).

## Behaviour changes from 3.0.x (deliberate)

| 3.0.x | 3.1.x | Why |
|---|---|---|
| Access-request dialog auto-sent "deny once" when its local 60 s timer ended (creating a 10-minute deny rule) | The dialog shows Core's remaining time; Core's own timeout applies | The Controller must not make ACL decisions (AGENTS.md §10) |
| Room view built locally after join/leave; cached in localStorage | Room view only from Core's room state | Core is authoritative |
| Ping toggle changed locally, sent 3 s later | Toggle shows Core's value; disabled while the request runs | No optimistic state |
| Rule "edit" accepted sources/ports that Core silently ignored | Edit changes only comment; "copy as new rule" for the rest | No fake controls (P6) |
| Android "允许一次" created a permanent allow rule | Creates a rule expiring in 10 minutes (Core's once TTL) | Matches the label |
| Android "已有永久规则" computed by matching ports/principals locally | Uses the `rule_id` Core reports in the access log | No local ACL evaluation (§9.3) |
| Android "撤销授权" only showed a hint | Deletes the deciding rule | No fake controls |
| Android OIDC treated any user ID as authenticated | Uses Core's `is_authenticated` | Anonymous users also have IDs |
| Display names auto-generated and saved on startup | Not written automatically; shown as Core has them | No hidden writes to Core |
| WebUI had no interactive authorization | WebUI shows access requests too | Same UI everywhere |
| Android notifications only while the UI was open | Posted from the Application (also with always-on VPN) | Reliability |

## Verification status

- Rust: unit + integration tests (FakeCore speaks the real handshake and
  framing), WebUI route/security tests, JNI glue tests on the host.
- End to end against a real (sandboxed) 3.1.x Core: sync, commands,
  rejections, rule clearing, Core restart → automatic resync, WebUI login and
  every tab rendered in headless Chromium.
- Android: APK builds for arm64-v8a / armeabi-v7a / x86_64, lint clean.
  **Not run on a device or emulator** (no system image installed here):
  VpnService start/sleep/wake, TUN hand-over and notifications are unverified.
- Desktop Tauri app: compiles; not launched in this environment.
