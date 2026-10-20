//! JNI bridge for the Kotlin Multiplatform Controller (`compose/`).
//!
//! Two independent halves, matching the 3.1.x Core/Controller split:
//!
//! - **Controller** (`com.msst.net.jni.MsstNative`, Android and desktop):
//!   the same `IpcClient` as every other front end, connected to Core's IPC
//!   endpoint.  The UI reads the view and sends `IpcRequest`s; it never
//!   talks to Core internals directly.
//! - **Core lifecycle** (`com.msst.net.jni.MsstCore`, Android only, feature
//!   `embedded-core`; called by `MsstVpnService`): start/stop the embedded
//!   Core (`msst_net_core::embedded::EmbeddedCore`), hand it the VpnService
//!   TUN fd, report network changes.  Core serves IPC on a Unix socket in
//!   the app's private directory and protects every socket it opens with
//!   `VpnService.protect` (works under "block connections without VPN").
//!
//! Every entry point returns JSON:
//! `{"ok":true,...}` or `{"ok":false,"error":{category,code,message,detail?}}`.

use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::jstring;
use jni::{JNIEnv, JavaVM};
use msst_ipc_protocol::IpcRequest;
use msst_net_controller::error::UiError;
use msst_net_controller::ipc::client::{Connector, IpcClient, IpcClientConfig, default_connector};
use msst_net_controller::ipc::transport;
use msst_net_controller::notify::{UiNotice, spawn_notifier};
use msst_net_controller::state::StateStore;
use serde_json::{Value, json};

// ── Pure bridge logic (host-testable) ────────────────────────────────────────

fn ok(v: Value) -> String {
    let mut o = json!({ "ok": true });
    if let (Some(map), Value::Object(extra)) = (o.as_object_mut(), v) {
        map.extend(extra);
    }
    o.to_string()
}

fn err(e: UiError) -> String {
    json!({ "ok": false, "error": e }).to_string()
}

fn err_msg(category: &'static str, code: &str, message: impl Into<String>) -> String {
    err(UiError { category, code: code.into(), message: message.into(), detail: None })
}

/// The Controller half: one IPC client and the runtime it lives on.
pub struct Controller {
    runtime: tokio::runtime::Runtime,
    client: IpcClient,
    notifier: Mutex<Option<tokio::task::JoinHandle<()>>>,
}

impl Controller {
    /// Create a client connecting to Core's IPC socket at `socket_path`, or
    /// to the platform's default endpoint (`MSST_IPC_PATH` or the system
    /// socket / named pipe) when `None`.
    pub fn new(socket_path: Option<PathBuf>) -> std::io::Result<Self> {
        let runtime = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build()?;
        let connector: Connector = match socket_path {
            Some(p) => {
                let path = p.to_string_lossy().into_owned();
                Arc::new(move || {
                    let path = path.clone();
                    Box::pin(async move { transport::connect_to(&path).await })
                })
            }
            None => default_connector(),
        };
        Ok(Self::with_connector(runtime, connector))
    }

    pub fn with_connector(runtime: tokio::runtime::Runtime, connector: Connector) -> Self {
        let client = runtime.block_on(async {
            IpcClient::with_connector(IpcClientConfig::default(), StateStore::new(), connector)
        });
        Controller { runtime, client, notifier: Mutex::new(None) }
    }

    /// `{"ok":true,"view":ControllerView}`
    pub fn view_json(&self) -> String {
        let view = self.runtime.block_on(self.client.view());
        match serde_json::to_value(view) {
            Ok(v) => ok(json!({ "view": v })),
            Err(e) => err_msg("codec", "codec", e.to_string()),
        }
    }

    /// Send an `IpcRequest` given as JSON: `{"ok":true,"data":...}`.
    pub fn request_json(&self, request: &str) -> String {
        let req: IpcRequest = match serde_json::from_str(request) {
            Ok(r) => r,
            Err(e) => return err_msg("request", "invalid_request", format!("无效的请求: {e}")),
        };
        match self.runtime.block_on(self.client.command(req)) {
            Ok(v) => ok(json!({ "data": v })),
            Err(e) => err(UiError::from(e)),
        }
    }

    pub fn clear_rules_json(&self) -> String {
        match self.runtime.block_on(msst_net_controller::ops::clear_acl_rules(&self.client)) {
            Ok(v) => ok(json!({ "data": v })),
            Err(e) => err(UiError::from(e)),
        }
    }

    /// Reconnect now (or resync if connected): `{"ok":true,"outcome":…}`.
    pub fn retry_json(&self) -> String {
        let outcome = self.runtime.block_on(self.client.retry_now());
        ok(json!({ "outcome": outcome }))
    }

    /// Deliver every `UiNotice` (as JSON) to `sink`, replacing any previous sink.
    pub fn set_listener(&self, sink: impl Fn(String) + Send + 'static) {
        let handle = {
            let _guard = self.runtime.enter();
            spawn_notifier(&self.client, move |n: UiNotice| {
                if let Ok(s) = serde_json::to_string(&n) {
                    sink(s);
                }
            })
        };
        if let Some(old) = self.notifier.lock().unwrap().replace(handle) {
            old.abort();
        }
    }
}

// ── Global state ─────────────────────────────────────────────────────────────

static CONTROLLER: OnceLock<Controller> = OnceLock::new();
static JVM: OnceLock<JavaVM> = OnceLock::new();
static LISTENER: Mutex<Option<GlobalRef>> = Mutex::new(None);

fn string_arg(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s).map(Into::into).map_err(|e| format!("invalid string argument: {e}"))
}

fn to_jstring(env: &mut JNIEnv, s: String) -> jstring {
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Run `f`, converting panics into an error result.
fn guarded(env: &mut JNIEnv, f: impl FnOnce(&mut JNIEnv) -> String) -> jstring {
    let out = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| f(env))) {
        Ok(s) => s,
        Err(p) => {
            let msg = p
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| p.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown panic".into());
            err_msg("internal", "panic", format!("内部错误: {msg}"))
        }
    };
    to_jstring(env, out)
}

fn controller() -> Result<&'static Controller, String> {
    CONTROLLER.get().ok_or_else(|| err_msg("internal", "not_initialized", "控制器尚未初始化"))
}

fn remember_vm(env: &JNIEnv) {
    if let Ok(vm) = env.get_java_vm() {
        let _ = JVM.set(vm);
    }
}

// ── Controller (UI) ──────────────────────────────────────────────────────────

/// Create the Controller (once per process).  `socket_path` is Core's
/// socket, or `null` for the platform default endpoint.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_msst_net_jni_MsstNative_nativeInitController(
    mut env: JNIEnv,
    _class: JClass,
    socket_path: JString,
) -> jstring {
    remember_vm(&env);
    guarded(&mut env, |env| {
        let path = if socket_path.is_null() {
            None
        } else {
            match string_arg(env, &socket_path) {
                Ok(p) => Some(PathBuf::from(p)),
                Err(e) => return err_msg("request", "invalid_request", e),
            }
        };
        if CONTROLLER.get().is_none() {
            match Controller::new(path) {
                Ok(c) => {
                    let _ = CONTROLLER.set(c);
                }
                Err(e) => return err_msg("internal", "runtime", e.to_string()),
            }
        }
        ok(json!({}))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_msst_net_jni_MsstNative_nativeGetView(mut env: JNIEnv, _class: JClass) -> jstring {
    guarded(&mut env, |_| controller().map(|c| c.view_json()).unwrap_or_else(|e| e))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_msst_net_jni_MsstNative_nativeRequest(
    mut env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    guarded(&mut env, |env| {
        let req = match string_arg(env, &request) {
            Ok(r) => r,
            Err(e) => return err_msg("request", "invalid_request", e),
        };
        controller().map(|c| c.request_json(&req)).unwrap_or_else(|e| e)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_msst_net_jni_MsstNative_nativeClearRules(mut env: JNIEnv, _class: JClass) -> jstring {
    guarded(&mut env, |_| controller().map(|c| c.clear_rules_json()).unwrap_or_else(|e| e))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_msst_net_jni_MsstNative_nativeRetry(mut env: JNIEnv, _class: JClass) -> jstring {
    guarded(&mut env, |_| controller().map(|c| c.retry_json()).unwrap_or_else(|e| e))
}

/// Register `listener.onNotice(String)` for change notifications.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_msst_net_jni_MsstNative_nativeSetListener(
    mut env: JNIEnv,
    _class: JClass,
    listener: JObject,
) -> jstring {
    remember_vm(&env);
    guarded(&mut env, |env| {
        let c = match controller() {
            Ok(c) => c,
            Err(e) => return e,
        };
        let global = match env.new_global_ref(listener) {
            Ok(g) => g,
            Err(e) => return err_msg("internal", "jni", e.to_string()),
        };
        *LISTENER.lock().unwrap() = Some(global);
        c.set_listener(|notice| {
            let Some(vm) = JVM.get() else { return };
            let Some(listener) = LISTENER.lock().unwrap().clone() else { return };
            let Ok(mut env) = vm.attach_current_thread_as_daemon() else { return };
            if let Ok(s) = env.new_string(notice) {
                let _ = env.call_method(listener.as_obj(), "onNotice", "(Ljava/lang/String;)V", &[JValue::Object(&s)]);
                if env.exception_check().unwrap_or(false) {
                    let _ = env.exception_clear();
                }
            }
        });
        ok(json!({}))
    })
}

// ── Desktop notifications ────────────────────────────────────────────────────

#[cfg(target_os = "linux")]
mod notify_linux;

/// Show a native desktop notification; `on_activate` (a `Runnable`, may be
/// null) runs when the user clicks it.  `{"ok":true}`, or an error with
/// code `unsupported` where this library has no native notifications (the
/// caller then uses its own fallback).
#[cfg(not(target_os = "android"))]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_msst_net_jni_DesktopNotifications_nativeShow(
    mut env: JNIEnv,
    _class: JClass,
    title: JString,
    body: JString,
    on_activate: JObject,
) -> jstring {
    remember_vm(&env);
    guarded(&mut env, |env| {
        let (title, body) = match (string_arg(env, &title), string_arg(env, &body)) {
            (Ok(t), Ok(b)) => (t, b),
            (Err(e), _) | (_, Err(e)) => return err_msg("request", "invalid_request", e),
        };
        #[cfg(target_os = "linux")]
        {
            let callback = if on_activate.is_null() {
                None
            } else {
                match env.new_global_ref(on_activate) {
                    Ok(g) => Some(g),
                    Err(e) => return err_msg("internal", "jni", e.to_string()),
                }
            };
            let activate = callback.map(|runnable| -> Box<dyn FnOnce() + Send> {
                Box::new(move || {
                    let Some(vm) = JVM.get() else { return };
                    let Ok(mut env) = vm.attach_current_thread_as_daemon() else { return };
                    let _ = env.call_method(runnable.as_obj(), "run", "()V", &[]);
                    if env.exception_check().unwrap_or(false) {
                        let _ = env.exception_clear();
                    }
                })
            });
            match notify_linux::show(&title, &body, activate) {
                Ok(()) => ok(json!({})),
                Err(e) => err_msg("platform", "notification_failed", e),
            }
        }
        #[cfg(not(target_os = "linux"))]
        {
            let _ = (title, body, on_activate);
            err_msg("platform", "unsupported", "no native notifications on this platform")
        }
    })
}

// ── Core lifecycle (Android VpnService) ──────────────────────────────────────

#[cfg(feature = "embedded-core")]
mod core {
    use super::*;
    use jni::sys::jint;
    use msst_net_core::embedded::EmbeddedCore;

    static CORE: Mutex<Option<EmbeddedCore>> = Mutex::new(None);
    /// The running `VpnService`, for Core's socket protection.  Core installs
    /// its protector once per process; the service instance can change, so
    /// the protector reads this slot on every call.
    pub(crate) static VPN_SERVICE: Mutex<Option<GlobalRef>> = Mutex::new(None);

    /// `VpnService.protect(fd)` on the current service (Core's `netsock`
    /// protector).  Without a service the socket is refused, so Core's
    /// traffic never silently enters the VPN.  Runs on Core threads; must
    /// not call into Core.
    pub(crate) fn protect_fd(fd: i32) -> bool {
        let Some(service) = VPN_SERVICE.lock().unwrap().clone() else { return false };
        let Some(vm) = JVM.get() else { return false };
        let Ok(mut env) = vm.attach_current_thread_as_daemon() else { return false };
        let protected = env.call_method(service.as_obj(), "protect", "(I)Z", &[JValue::Int(fd)]).and_then(|v| v.z());
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_clear();
        }
        let protected = protected.unwrap_or(false);
        if !protected {
            tracing::warn!(fd, "VpnService.protect failed");
        }
        protected
    }

    /// Start Core (no-op if already running).  `{"ok":true,"ipv4","ipv6"}`
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_msst_net_jni_MsstCore_nativeStartCore(
        mut env: JNIEnv,
        _class: JClass,
        data_dir: JString,
        socket_path: JString,
        service: JObject,
    ) -> jstring {
        remember_vm(&env);
        guarded(&mut env, |env| {
            let (data_dir, socket) = match (string_arg(env, &data_dir), string_arg(env, &socket_path)) {
                (Ok(d), Ok(s)) => (d, s),
                (Err(e), _) | (_, Err(e)) => return err_msg("request", "invalid_request", e),
            };
            match env.new_global_ref(service) {
                Ok(g) => *VPN_SERVICE.lock().unwrap() = Some(g),
                Err(e) => return err_msg("internal", "jni", e.to_string()),
            }
            let mut slot = CORE.lock().unwrap();
            if slot.is_none() {
                let protect: msst_net_core::netsock::Protector = Arc::new(protect_fd);
                match EmbeddedCore::start(std::path::Path::new(&data_dir), std::path::Path::new(&socket), None, Some(protect)) {
                    Ok(core) => *slot = Some(core),
                    Err(e) => return err_msg("core", "core_start_failed", format!("客户端核心启动失败: {e:#}")),
                }
            }
            let (ipv4, ipv6) = slot.as_ref().unwrap().virtual_ips();
            ok(json!({ "ipv4": ipv4, "ipv6": ipv6 }))
        })
    }

    /// Hand Core the VpnService TUN fd (ownership passes to Core).
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_msst_net_jni_MsstCore_nativeAttachTun(mut env: JNIEnv, _class: JClass, fd: jint) -> jstring {
        guarded(&mut env, |_| match CORE.lock().unwrap().as_mut() {
            Some(core) => match core.attach_tun_fd(fd) {
                Ok(()) => ok(json!({})),
                Err(e) => err_msg("core", "tun_attach_failed", format!("无法接管 VPN 接口: {e:#}")),
            },
            None => err_msg("core", "not_running", "客户端核心未运行"),
        })
    }

    /// Stop using the TUN (VPN paused); Core keeps running.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_msst_net_jni_MsstCore_nativeDetachTun(mut env: JNIEnv, _class: JClass) -> jstring {
        guarded(&mut env, |_| {
            if let Some(core) = CORE.lock().unwrap().as_mut() {
                core.detach_tun();
            }
            ok(json!({}))
        })
    }

    /// The device's network changed.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_msst_net_jni_MsstCore_nativeNetworkChanged(mut env: JNIEnv, _class: JClass) -> jstring {
        guarded(&mut env, |_| {
            if let Some(core) = CORE.lock().unwrap().as_ref() {
                core.network_changed();
            }
            ok(json!({}))
        })
    }

    /// Shut Core down and flush its state.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_msst_net_jni_MsstCore_nativeStopCore(mut env: JNIEnv, _class: JClass) -> jstring {
        guarded(&mut env, |_| {
            if let Some(core) = CORE.lock().unwrap().take() {
                core.stop();
            }
            VPN_SERVICE.lock().unwrap().take();
            ok(json!({}))
        })
    }
}

// ── Tests (host) ─────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use msst_ipc_protocol::IpcError;
    use msst_net_controller::ipc::mock::{FakeCore, default_responses};

    fn controller(core: &FakeCore) -> Controller {
        let rt = tokio::runtime::Builder::new_multi_thread().worker_threads(1).enable_all().build().unwrap();
        Controller::with_connector(rt, core.connector())
    }

    fn wait_connected(c: &Controller) {
        for _ in 0..200 {
            let v: Value = serde_json::from_str(&c.view_json()).unwrap();
            if v["view"]["connection"] == "Connected" && v["view"]["state"]["sync"]["state"] == "synced" {
                return;
            }
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
        panic!("not connected");
    }

    #[test]
    fn view_and_requests_round_trip() {
        let core = FakeCore::new(|req| match req {
            IpcRequest::DeleteAclRule { .. } => Err(IpcError { code: "not_found".into(), message: "Rule 'x' not found".into() }),
            other => default_responses(other),
        });
        let c = controller(&core);
        wait_connected(&c);

        let v: Value = serde_json::from_str(&c.request_json(r#"{"type":"health"}"#)).unwrap();
        assert_eq!(v["ok"], true);
        assert_eq!(v["data"]["status"], "ok");

        let v: Value = serde_json::from_str(&c.request_json(r#"{"type":"delete_acl_rule","params":{"rule_id":"x"}}"#)).unwrap();
        assert_eq!(v["ok"], false);
        assert_eq!(v["error"]["code"], "not_found");

        let v: Value = serde_json::from_str(&c.request_json("not json")).unwrap();
        assert_eq!(v["error"]["code"], "invalid_request");
    }

    #[test]
    fn retry_reports_outcome() {
        let core = FakeCore::new(default_responses);
        let c = controller(&core);
        wait_connected(&c);
        let v: Value = serde_json::from_str(&c.retry_json()).unwrap();
        assert_eq!(v["ok"], true);
        assert_eq!(v["outcome"], "resyncing");
    }

    #[cfg(feature = "embedded-core")]
    #[test]
    fn protect_without_service_refuses_the_socket() {
        assert!(core::VPN_SERVICE.lock().unwrap().is_none());
        assert!(!core::protect_fd(3));
    }

    #[test]
    fn listener_receives_notices() {
        let core = FakeCore::new(default_responses);
        let c = controller(&core);
        wait_connected(&c);
        let (tx, rx) = std::sync::mpsc::channel();
        c.set_listener(move |s| {
            let _ = tx.send(s);
        });
        std::thread::sleep(std::time::Duration::from_millis(50));
        core.push_event(msst_ipc_protocol::IpcEvent::AccessRequestCreated {
            request_id: "a1".into(),
            src_device_id: "device-00000001".into(),
            src_user_id: None,
            src_device_display_name: Some("Bob".into()),
            src_user_display_name: None,
            proto: "tcp".into(),
            dst_port: 25565,
            service_name: None,
        });
        let got: Vec<String> = (0..2).filter_map(|_| rx.recv_timeout(std::time::Duration::from_secs(2)).ok()).collect();
        assert!(got.iter().any(|s| s.contains("\"access_request\"") && s.contains("Bob")), "{got:?}");
        assert!(got.iter().any(|s| s.contains("\"changed\"")), "{got:?}");
    }
}
