# MSST-Net Controller 3.1.x — structure, build, and changes from 3.0.x

## Structure

```text
src/                 Controller library (IPC client, state sync, notices) — used by every variant
  ipc/               transport, handshake, IpcClient (reconnect + resync), FakeCore for tests
  state/             StateStore: the view of Core state, built only from Core queries/events
  notify.rs          debounced change notices for UIs
  ops.rs             multi-request operations (clear rules)
  webui/             WebUI server (3.0.x security model; serves compose/webApp)
  bin/msst-net-webui.rs
compose/             Kotlin Multiplatform / Compose Multiplatform Controller (one UI for all variants)
  shared/            common UI, presentation state, model; `jniMain` = JNI backend (Android + desktop)
  androidApp/        Android: Activity, VpnService (embedded Core), notifications
  desktopApp/        desktop (JVM): window, tray, notifications (Linux: freedesktop D-Bus via msst_jni)
  webApp/            WebUI frontend (wasm), talks to msst-net-webui over HTTPS
  rust/              JNI crate `msst_jni`: this library (+ EmbeddedCore with `--features embedded-core`)
docs/PROTOCOL_PROPOSALS_3.1.md   issues found in Core / protocol (not changed here)
```

### Variants

The shared UI talks to a `ControllerPlatform` (`compose/shared/.../platform/Platform.kt`):

| | Desktop | Mobile (Android) | WebUI |
|---|---|---|---|
| Reaches the library | JNI, Core's system IPC endpoint | JNI, `filesDir/ipc.sock` of the embedded Core | HTTPS API of `msst-net-webui` (which holds the IPC client) |
| Rule wizard / permission groups | yes | **no** (rules only from interactive authorization, access-log actions and room members) | yes |
| Rule for a room member (right click / long press → 添加防火墙规则; source fixed to its device ID) | yes | yes | yes |
| Interactive authorization | dialog + system notification (Linux: native D-Bus; Windows/macOS: tray) | dialog + notification with actions | **no** (server also rejects `auth_decision`) |
| Access password / IP allowlist | — | — | **required** (`WebSession`) |
| VPN service control | — | `VpnControl` | — |
| Per-app exclusion | — | `AppExclusion` (`addDisallowedApplication`; list in app preferences, not Core) | — |

Differences are declared in `ControllerFeatures` and optional services
(`vpn`, `appExclusion`, `session`, `notifier`), not by platform checks in screens.

Protocol: built against `msst-ipc-protocol` 0.4; Cores speaking 0.1–0.3
still work (features they lack are disabled with an explanation).  With a
0.4 Core the Controller checks event `seq` (a gap triggers a full resync),
uses response `event_seq` to discard stale query results, runs
`CreateRoom` / `JoinRoom` as background operations (progress shown in the
dialog; `GetOperation` recovers a result missed across a reconnect), turns
off streamed log lines (`SetLogStream`) and shows a read-only banner when
Core gives the connection the `viewer` role.

Every front end uses the same three operations: read the Controller view,
send an `IpcRequest` from the shared `msst-ipc-protocol` crate, and get
notified when the view changed.  Core is the only source of networking
state.

## Build

| Target | Command |
|---|---|
| Library + tests | `cargo test --features webui` |
| JNI crate tests | `cd compose/rust && cargo test` |
| Kotlin tests | `cd compose && ./gradlew :shared:jvmTest :shared:testAndroidHostTest` (wasm: `CHROME_BIN=… ./gradlew :shared:wasmJsTest`) |
| Desktop app | `compose/rust/build-desktop.sh`, then `cd compose && ./gradlew :desktopApp:run`; to ship, `createReleaseDistributable` / `packageReleaseDeb` / `packageReleaseRpm` / `packageReleaseMsi` / `packageReleaseDmg` (on each OS; ProGuard, rules in `desktopApp/proguard-rules.pro`; the MSI upgrade code is fixed so new MSIs upgrade in place) |
| WebUI | `cd compose && ./gradlew :webApp:wasmJsBrowserDistribution`, then `cargo build --release --features webui --bin msst-net-webui` (the web build is embedded) |
| Android | `compose/rust/build-android.sh` (needs cargo-ndk + NDK), then `cd compose && ./gradlew :androidApp:assembleRelease` (one APK per ABI; `bundleRelease` for an AAB) |

The JNI crate depends on the Core repo by path (`../msst-net-core-31x`), as
before.

GitHub Actions: `.github/workflows/ci.yml` runs the tests above on every
push; `release.yml` builds a `v<version>` tag into the release the
installer's deployment contract defines (`msst-net-installer-31x`
`docs/DEPLOYMENT_CONTRACT.md`: contract file names, signed
`manifest.json`, the `abjust/msstnet31x-webui` image), plus the signed
per-ABI Android APKs (not in the manifest: Android doesn't use the
installer).  It builds against Core's newest release unless
`MSST_CORE_REF` names another.  Its header lists the secrets and variables
it needs.  UI font: 寒蝉全圆体 (ChillRound, SIL OFL 1.1), bundled as Compose
resources.

### WebUI

Same as 3.0.x: config in `~/.config/msst-net-webui/` (existing `passwd` and
`config.toml` are reused), password from `PASSWORD_FILE` /
`/run/secrets/admin_password` / `ADMIN_PASSWORD` or set interactively on first
run, CIDR allowlist on every request, `WEBUI_BIND` overrides the listen
address.  New: the IPC socket path can be overridden with `MSST_IPC_PATH`
(or 3.0.x `CORE_SOCKET`), cross-site WebSocket connections are rejected,
failed logins are delayed, MSST-Net's virtual network is refused unless an
allowlist entry inside it admits it, the self-signed certificate is kept
(or `TLS_CERT_FILE` / `TLS_KEY_FILE` supply one), `MSST_WEBUI_CONFIG_DIR`
moves the config directory, SIGTERM stops it gracefully, and `GET /healthz`
/ `msst-net-webui healthcheck` serve container health checks, `--version`
prints the version (unknown arguments are refused instead of starting the
server).  Docker
deployment: [DOCKER.md](DOCKER.md).

On Windows the same binary runs as the service `msst-net-webui` when the
Service Control Manager starts it (otherwise in the console): LocalSystem
(Core's pipe admits only SYSTEM, Administrators and interactive users, so a
virtual service account would be refused), config in
`%ProgramData%\MSST-Net\webui` unless `MSST_WEBUI_CONFIG_DIR` is set (the
service's `Environment` registry value), log in `webui.log` there (rotated at
5 MB).  The installer registers the service and restricts that directory to
SYSTEM and Administrators.

### Android

Core runs inside the app (`EmbeddedCore`) with its data in the app's
`filesDir` — the 3.0.x location, so the device identity survives the
upgrade.  The UI talks to it over IPC (`filesDir/ipc.sock`).  Core protects
every socket it opens through `VpnService.protect` (passed in by the JNI
glue), so it keeps working with "always-on VPN + block connections without
VPN".

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
| WebUI had no interactive authorization | Still none (requests are answered on the device or time out in Core) | Product decision for 3.1 |
| Android notifications only while the UI was open | Posted from the Application (also with always-on VPN) | Reliability |

## Verification status

- Rust: unit + integration tests (FakeCore speaks the real handshake and
  framing), WebUI route/security tests, JNI glue tests on the host.
- Core contract vectors (`tests/core_contract.rs`, reads
  `../msst-net-core-31x/crates/msst-ipc-protocol/tests/vectors`).
- Protocol 0.4 behaviour is tested against FakeCore only (seq gaps and
  duplicates, async operations, missed completions, viewer role, handshake
  rejection); not yet run against a real 0.4 Core.
- End to end against a real (sandboxed) 3.1.x Core (protocol 0.2 and 0.3), with the former Svelte frontend: sync, commands,
  rejections, rule clearing, Core restart → automatic resync, WebUI login and
  every tab rendered in headless Chromium.
- Kotlin: model decoding (fixture from the real library output), request
  shapes, presentation rules, store (stale views dropped, notices), rule
  wizard, OIDC flow — on JVM, Android host and wasm (headless Chromium).
- Desktop app: launched against a local Core (connected, home screen
  rendered); other screens rendered offscreen. Window packaging
  (`packageDeb` / `packageMsi`) not tried.
- WebUI: `msst-net-webui` against a local Core: wasm served
  (`application/wasm`), login page rendered in headless Chromium, login /
  view / `auth_decision` rejection checked over the API. Logged-in screens
  were not exercised in a browser.
- Android: APK builds (debug, without native libraries in this run).
  **Not run on a device or emulator**: VpnService start/sleep/wake, TUN
  hand-over, socket protection under lockdown mode and notifications are
  unverified.
