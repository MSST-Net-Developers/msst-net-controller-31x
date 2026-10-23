//! How Core's state is described in the terminal.  A port of the shared
//! UI's presentation helpers (`compose/shared/.../state/Presentation.kt`)
//! so every Controller variant uses the same words.  Nothing here decides
//! networking state: it only describes what Core reported.

use serde_json::Value;

use crate::ipc::client::ControllerView;
use crate::state::{AclRule, ConnectionStatus, NetworkWarning, RoomPeer, RoomRelay, SyncState, SystemState};

// ── Connection and Core status ───────────────────────────────────────────────

/// Controller ↔ Core channel status in words.
pub fn connection_label(c: &ConnectionStatus) -> String {
    match c {
        ConnectionStatus::Connected => "已连接".into(),
        ConnectionStatus::Connecting => "连接中".into(),
        ConnectionStatus::Disconnected => "未连接".into(),
        ConnectionStatus::Incompatible => "版本不兼容".into(),
        ConnectionStatus::Reconnecting { attempt, .. } => format!("重连中（第 {attempt} 次）"),
    }
}

/// Overall status (the status bar of the graphical Controller).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CoreStatus {
    Ok,
    Warning,
    Error,
    Checking,
}

/// Core reports that its coordinator connection is down.  Unknown (older
/// Cores, Health not read yet) is not "down".
pub fn coordinator_unreachable(v: &ControllerView) -> bool {
    v.connection == ConnectionStatus::Connected && v.state.health.as_ref().and_then(|h| h.ws_connected) == Some(false)
}

/// Core itself is usable: connected and its Health is `ok`.
pub fn core_ready(v: &ControllerView) -> bool {
    v.connection == ConnectionStatus::Connected && v.state.health.as_ref().is_some_and(|h| h.status == "ok")
}

pub fn core_status(v: &ControllerView) -> CoreStatus {
    match v.connection {
        ConnectionStatus::Connected => match &v.state.health {
            None if matches!(v.state.sync, SyncState::Failed { .. }) => CoreStatus::Warning,
            None => CoreStatus::Checking,
            Some(h) if h.status != "ok" => CoreStatus::Warning,
            Some(_) if coordinator_unreachable(v) => CoreStatus::Warning,
            Some(_) => CoreStatus::Ok,
        },
        ConnectionStatus::Connecting => CoreStatus::Checking,
        _ => CoreStatus::Error,
    }
}

/// One-line explanation of [`core_status`].
pub fn core_status_label(v: &ControllerView) -> &'static str {
    let connected = v.connection == ConnectionStatus::Connected;
    match core_status(v) {
        CoreStatus::Ok => {
            if v.state.health.as_ref().and_then(|h| h.coordinator_credential.as_deref()) == Some("guest") {
                "客户端核心状态正常（访客身份：仅可使用房间模式）"
            } else {
                "客户端核心状态正常"
            }
        }
        CoreStatus::Warning if coordinator_unreachable(v) => "客户端核心无法连接协调服务器",
        CoreStatus::Warning if matches!(v.state.sync, SyncState::Failed { .. }) => "客户端核心部分状态读取失败",
        CoreStatus::Warning => "客户端核心状态异常",
        CoreStatus::Checking if connected => "正在读取客户端核心状态…",
        CoreStatus::Checking => "正在连接客户端核心…",
        CoreStatus::Error if v.connection == ConnectionStatus::Incompatible => "客户端核心版本不兼容",
        CoreStatus::Error => "客户端核心可能没有启动",
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BannerKind {
    Info,
    Warn,
    Error,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Banner {
    pub kind: BannerKind,
    pub text: String,
    pub detail: Option<String>,
}

/// Why Core data may be missing, stale or read-only; `None` when
/// everything is current.
pub fn core_banner(v: &ControllerView) -> Option<Banner> {
    let b = |kind, text: String, detail: Option<String>| Some(Banner { kind, text, detail });
    let d = &v.diagnostics;
    let s = &v.state;
    let connected = v.connection == ConnectionStatus::Connected;
    match v.connection {
        ConnectionStatus::Incompatible => {
            return b(
                BannerKind::Error,
                format!(
                    "客户端核心的协议版本（{}）与控制器（{}）不兼容，请将两者更新到同一版本",
                    v.core_protocol.as_deref().unwrap_or("未知"),
                    v.controller_protocol
                ),
                d.last_error.clone(),
            );
        }
        ConnectionStatus::Reconnecting { .. } => {
            return b(BannerKind::Error, "未连接到客户端核心，正在重试…以下内容可能已过期".into(), d.last_error.clone());
        }
        ConnectionStatus::Disconnected if d.successful_connects > 0 => {
            return b(BannerKind::Warn, "与客户端核心的连接已断开，正在重连…以下内容可能已过期".into(), d.last_error.clone());
        }
        _ => {}
    }
    if !connected {
        return None;
    }
    let authenticated = s.health.as_ref().is_some_and(|h| h.is_authenticated);
    match &s.sync {
        SyncState::Syncing | SyncState::NotSynced => b(BannerKind::Info, "正在从客户端核心读取最新状态…".into(), None),
        SyncState::Failed { message } => {
            b(BannerKind::Warn, "部分状态读取失败，相关内容可能缺失".into(), Some(message.clone()))
        }
        SyncState::Synced if s.access_auth_required && !authenticated && !coordinator_unreachable(v) => b(
            BannerKind::Warn,
            "MSST-Net 接入认证未完成或已过期，请运行 `msst-net-cli login` 完成接入认证".into(),
            None,
        ),
        SyncState::Synced if s.health.as_ref().is_some_and(|h| h.is_viewer()) => b(
            BannerKind::Info,
            "只读模式：当前账户可以查看状态，但不能进行更改。如需管理权限，请将该账户加入 msst-net 用户组\
             （Windows：MSST-Net Administrators）后重新运行"
                .into(),
            None,
        ),
        SyncState::Synced => None,
    }
}

/// Yes/no/unknown for an optional Core flag.
pub fn tri_state(b: Option<bool>, yes: &str, no: &str) -> String {
    match b {
        None => "未知".into(),
        Some(true) => yes.into(),
        Some(false) => no.into(),
    }
}

// ── Paths and encryption ─────────────────────────────────────────────────────

/// Path mode as reported by Core, in words; `None` when Core reported none.
pub fn path_label(mode: Option<&str>) -> Option<String> {
    let mode = mode?;
    Some(match mode {
        "relay" => "中转".into(),
        "probing" => "探测直连中".into(),
        m if m.starts_with("direct") => "直连".into(),
        m => m.into(),
    })
}

/// Why the path last changed, in words; unknown reasons are shown as sent.
pub fn path_reason_label(reason: Option<&str>) -> Option<String> {
    let r = reason?;
    Some(
        match r {
            "probe" => "发现直连候选，开始探测",
            "punch" => "协调服务器发起打洞",
            "upgrade" => "直连探测成功",
            "switch" => "换到更优的直连端点",
            "no_pong" => "直连无响应",
            "loss" => "直连丢包过多",
            "rtt_spike" => "直连延迟持续升高",
            "data_stalled" => "直连能 ping 通但数据不通",
            "probe_failed" => "所有直连候选均无响应",
            "p2p_disabled" => "已关闭 P2P",
            other => other,
        }
        .to_string(),
    )
}

/// [`path_label`] with the reason it changed, e.g. `中转（直连无响应）`.
pub fn path_with_reason(mode: Option<&str>, reason: Option<&str>) -> Option<String> {
    let label = path_label(mode)?;
    Some(match path_reason_label(reason) {
        Some(r) => format!("{label}（{r}）"),
        None => label,
    })
}

/// A WireGuard session state from `GetPathView`.
pub fn encryption_label(state: Option<&str>) -> Option<String> {
    Some(
        match state? {
            "no_key" => "无公钥（无法通信）",
            "unknown" => "空闲（有通信时自动建立）",
            "handshaking" => "握手中",
            "encrypted" => "已加密",
            "failed" => "握手失败",
            other => other,
        }
        .to_string(),
    )
}

/// How a diagnostic ping's `path` is shown; `None` when Core didn't say.
pub fn diag_path_label(path: Option<&str>) -> Option<String> {
    Some(
        match path? {
            "direct" => "P2P 直连",
            "relay" => "经中转（WireGuard 隧道内）",
            other => other,
        }
        .to_string(),
    )
}

// ── Room ─────────────────────────────────────────────────────────────────────

/// One line for a host routing problem Core reported.
pub fn network_warning_label(w: &NetworkWarning) -> String {
    match w.kind.as_str() {
        "route_conflict" => format!(
            "{} 的路由 {} 与 MSST-Net 网段 {} 重叠，MSST-Net 未接管该网段，可能无法与部分设备通信",
            w.interface,
            w.route.as_deref().unwrap_or(&w.range),
            w.range
        ),
        "route_hijacked" => {
            format!("发往 MSST-Net 网段 {} 的流量被路由到了 {}，可能无法与其他设备通信", w.range, w.interface)
        }
        kind => format!("网络配置问题（{kind}）：{} / {}", w.range, w.interface),
    }
}

/// The game discovery note of a room member; `None` when there is nothing to say.
pub fn discovery_note(peer: &RoomPeer) -> Option<&'static str> {
    (peer.discovery.as_deref() == Some("unsupported")).then_some("该成员版本过旧，无法互相发现游戏")
}

/// The room relay line; `None` when the room has none.
pub fn room_relay_label(r: Option<&RoomRelay>) -> Option<String> {
    let r = r?;
    let kind = if r.self_hosted { "此房间使用自托管中转服务器" } else { "此房间使用官方中转服务器" };
    let state = match r.state.as_str() {
        "ok" => "正常",
        "probing" => "检测中",
        "down" => "不可用，已回退到各自的中转服务器",
        _ => "状态未知",
    };
    Some(format!("{kind} {}（{state}）", r.address))
}

/// The game discovery counters (`GetRelays.discovery`) in one line.
pub fn discovery_stats_line(d: &Value) -> String {
    let n = |k: &str| d.get(k).and_then(Value::as_u64).unwrap_or(0);
    let mut s = format!("已发送 {}  已接收 {}", n("tx_messages"), n("rx_accepted"));
    if n("tx_skipped_unsupported") > 0 {
        s += &format!("  未发送（成员版本过旧）{}", n("tx_skipped_unsupported"));
    }
    if let Some(drop) = d.get("drop").and_then(Value::as_object) {
        for (reason, count) in drop {
            let count = count.as_u64().unwrap_or(0);
            if count == 0 {
                continue;
            }
            let label = match reason.as_str() {
                "not_member" => "发送方不是房间成员",
                "not_discovery" => "不是游戏发现广播",
                "rate_limited" => "超出速率限制",
                other => other,
            };
            s += &format!("  丢弃（{label}）{count}");
        }
    }
    s
}

/// Display name for a device ID from Core's node list.
pub fn device_name(state: &SystemState, id: &str) -> String {
    state
        .nodes
        .get(id)
        .and_then(|n| n.display_name.clone().or_else(|| n.hostname.clone()))
        .unwrap_or_else(|| id.to_string())
}

/// Device mnemonic shown for "大内网模式".
pub fn device_mnemonic(state: &SystemState) -> String {
    let Some(h) = &state.health else { return String::new() };
    match (&h.device_handle, &h.user_handle) {
        (Some(d), Some(u)) => format!("{d}.{u}"),
        (Some(d), None) => d.clone(),
        _ => h.device_id.clone().unwrap_or_default(),
    }
}

pub fn user_mnemonic(state: &SystemState) -> String {
    state.health.as_ref().and_then(|h| h.user_handle.clone().or_else(|| h.user_id.clone())).unwrap_or_default()
}

// ── Firewall rules ───────────────────────────────────────────────────────────

/// The principal ID Core matches against every device or user (`*`).
pub const ANY_PRINCIPAL: &str = "*";

/// The fields of a rule the CLI shows, read from Core's rule as is.
#[derive(Debug, Clone, PartialEq)]
pub struct RuleView {
    pub id: String,
    /// `devices`, `users` or `space`.
    pub source_kind: String,
    pub source_ids: Vec<String>,
    /// (proto, start, end)
    pub ports: Vec<(String, u64, u64)>,
    pub action: String,
    pub expires_at: Option<i64>,
    pub enabled: bool,
    pub comment: Option<String>,
    pub mnemonics: serde_json::Map<String, Value>,
    pub pending_migration: bool,
}

impl RuleView {
    pub fn from_rule(r: &AclRule) -> Self {
        let f = &r.fields;
        let pg = f.get("permission_group");
        let str_of = |v: Option<&Value>| v.and_then(Value::as_str).map(str::to_string);
        Self {
            id: r.id.clone(),
            source_kind: str_of(pg.and_then(|p| p.get("kind"))).unwrap_or_else(|| "devices".into()),
            source_ids: pg
                .and_then(|p| p.get("ids"))
                .and_then(Value::as_array)
                .map(|a| a.iter().filter_map(|v| v.as_str().map(str::to_string)).collect())
                .unwrap_or_default(),
            ports: f
                .get("protocol_ports")
                .and_then(Value::as_array)
                .map(|a| {
                    a.iter()
                        .map(|p| {
                            (
                                str_of(p.get("proto")).unwrap_or_default(),
                                p.get("start").and_then(Value::as_u64).unwrap_or(0),
                                p.get("end").and_then(Value::as_u64).unwrap_or(0),
                            )
                        })
                        .collect()
                })
                .unwrap_or_default(),
            action: str_of(f.get("action")).unwrap_or_default(),
            expires_at: f.get("expires_at").and_then(Value::as_i64),
            enabled: f.get("enabled").and_then(Value::as_bool).unwrap_or(true),
            comment: str_of(f.get("comment")),
            mnemonics: f.get("principal_mnemonics").and_then(Value::as_object).cloned().unwrap_or_default(),
            pending_migration: f.get("pending_migration").and_then(Value::as_bool).unwrap_or(false),
        }
    }

    /// "Who → can or cannot → which services" (AGENTS.md §9.1).
    pub fn summary(&self) -> String {
        let any = self.source_ids.iter().any(|id| id == ANY_PRINCIPAL);
        let who = match self.source_kind.as_str() {
            "space" => "当前房间成员".to_string(),
            "devices" if any => "所有设备".to_string(),
            "devices" => format!("{} 台设备", self.source_ids.len()),
            _ if any => "所有用户".to_string(),
            _ => format!("{} 位用户", self.source_ids.len()),
        };
        let verb = if self.action == "allow" { "可以访问" } else { "不能访问" };
        format!("{who}{verb} {} 组端口", self.ports.len())
    }

    /// Sources; IDs are shown with the mnemonic the rule was created with.
    pub fn sources(&self) -> String {
        if self.source_kind == "space" {
            return "房间模式：当前所有房间成员".into();
        }
        let devices = self.source_kind == "devices";
        let prefix = if devices { "设备" } else { "用户" };
        let ids: Vec<String> = self
            .source_ids
            .iter()
            .map(|id| match self.mnemonics.get(id).and_then(Value::as_str) {
                _ if id == ANY_PRINCIPAL => (if devices { "所有设备 (*)" } else { "所有用户 (*)" }).to_string(),
                Some(m) => format!("{m} ({id})"),
                None => id.clone(),
            })
            .collect();
        format!("{prefix}: {}", ids.join(", "))
    }

    pub fn ports_text(&self) -> String {
        if self.ports.is_empty() {
            return "任意".into();
        }
        self.ports.iter().map(|(p, s, e)| format!("{p}/{}", format_port(*s, *e))).collect::<Vec<_>>().join(", ")
    }
}

pub fn format_port(start: u64, end: u64) -> String {
    if start == end {
        start.to_string()
    } else if start == 1 && end == 65535 {
        "*".into()
    } else {
        format!("{start}-{end}")
    }
}

// ── Access log ───────────────────────────────────────────────────────────────

/// Why Core allowed or denied an access (`reason` of an access-log entry).
pub fn access_reason_label(reason: &Value) -> String {
    let get = |k: &str| reason.get(k).and_then(Value::as_str);
    match get("type").unwrap_or("") {
        "matched_rule" => match get("comment") {
            Some(c) => format!("规则备注：{c}"),
            None => format!("规则 {}", get("rule_id").unwrap_or("").chars().take(8).collect::<String>()),
        },
        "interactive_auth" => "交互式授权".into(),
        "interactive_auth_timeout" => "交互式授权超时".into(),
        "interactive_auth_limit" => "待处理的访问请求过多（未弹出提示）".into(),
        "default_deny" => "无匹配规则（默认拒绝）".into(),
        _ => "未知原因".into(),
    }
}

pub fn decision_label(d: &str) -> &'static str {
    match d {
        "allow" => "允许",
        "deny" => "拒绝",
        _ => "静默拒绝",
    }
}

// ── Validation (early feedback only; Core validates again) ──────────────────

/// A port expression accepted by Core: `*`, `80`, `80-443`.
pub fn valid_port_expr(s: &str) -> bool {
    if s == "*" {
        return true;
    }
    let (a, b) = match s.split_once('-') {
        Some((a, b)) => (a, b),
        None => (s, s),
    };
    let num = |x: &str| (!x.is_empty() && x.len() <= 5 && x.bytes().all(|c| c.is_ascii_digit())).then(|| x.parse::<u32>().ok()).flatten();
    match (num(a), num(b)) {
        (Some(a), Some(b)) => a >= 1 && b <= 65535 && a <= b,
        _ => false,
    }
}

/// A port expression that matches every port (`*`, `1-65535`).
pub fn covers_all_ports(expr: &str) -> bool {
    matches!(expr.trim(), "*" | "1-65535")
}

/// Mnemonic handle rule (Core `validate_handle`).
pub fn valid_handle(s: &str) -> bool {
    !s.is_empty() && s.bytes().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'-')
}

pub const MAX_DISPLAY_NAME_CHARS: usize = 64;

/// Why Core would reject `name` as a display name, or `None`.  An empty
/// name is acceptable: it clears the name.
pub fn display_name_problem(name: &str) -> Option<String> {
    let n = name.trim();
    if n.chars().count() > MAX_DISPLAY_NAME_CHARS {
        Some(format!("名称最多 {MAX_DISPLAY_NAME_CHARS} 个字符"))
    } else if n.chars().any(char::is_control) {
        Some("名称不能包含控制字符".into())
    } else {
        None
    }
}

/// MSST-Net's internal DNS zone.
pub const MSST_ZONE: &str = "net.msst2031.cn";

/// Namespace labels the coordinator refuses (its copy is authoritative;
/// this one only gives early feedback).
pub const RESERVED_NAMESPACES: &[&str] =
    &["srv", "ezplay", "serv", "msst", "msst-net", "official", "admin", "coord", "relay", "www", "api", "ns", "dns"];

/// A namespace label under [`MSST_ZONE`] (one label: a-z, 0-9, `-`), or `None`.
pub fn normalize_namespace(input: &str) -> Option<String> {
    let lower = input.trim().to_lowercase();
    let ns = lower.strip_suffix(&format!(".{MSST_ZONE}")).unwrap_or(&lower);
    let ok = !ns.is_empty()
        && ns.len() <= 63
        && ns.bytes().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'-')
        && !ns.starts_with('-')
        && !ns.ends_with('-');
    ok.then(|| ns.to_string())
}

// ── Numbers and times ────────────────────────────────────────────────────────

/// `x.y` with one decimal.
pub fn one_decimal(v: f64) -> String {
    format!("{v:.1}")
}

pub fn format_bytes(b: u64) -> String {
    const K: f64 = 1024.0;
    let f = b as f64;
    if f >= K * K * K {
        format!("{} GB", one_decimal(f / (K * K * K)))
    } else if f >= K * K {
        format!("{} MB", one_decimal(f / (K * K)))
    } else if f >= K {
        format!("{} KB", one_decimal(f / K))
    } else {
        format!("{b} B")
    }
}

/// Milliseconds since the Unix epoch as `YYYY-MM-DD HH:MM:SS` in the
/// system time zone.
pub fn format_time_ms(ms: i64) -> String {
    format_time_ms_in(ms, &chrono::Local)
}

/// [`format_time_ms`] in a given time zone.
pub fn format_time_ms_in<Tz: chrono::TimeZone>(ms: i64, tz: &Tz) -> String
where
    Tz::Offset: std::fmt::Display,
{
    match chrono::DateTime::from_timestamp_millis(ms) {
        Some(t) => t.with_timezone(tz).format("%Y-%m-%d %H:%M:%S").to_string(),
        None => "时间未知".into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn port_expressions() {
        for ok in ["*", "80", "1", "65535", "80-443", "1-65535"] {
            assert!(valid_port_expr(ok), "{ok}");
        }
        for bad in ["", "0", "65536", "443-80", "a", "80-", "-80", "123456", "8 0"] {
            assert!(!valid_port_expr(bad), "{bad}");
        }
        assert!(covers_all_ports(" 1-65535 "));
        assert!(!covers_all_ports("1-1024"));
    }

    #[test]
    fn namespaces_and_names() {
        assert_eq!(normalize_namespace(" Abjust.net.msst2031.cn "), Some("abjust".into()));
        assert_eq!(normalize_namespace("-a"), None);
        assert_eq!(normalize_namespace("a.b"), None);
        assert!(valid_handle("alice-laptop"));
        assert!(!valid_handle("Alice"));
        assert!(display_name_problem(&"x".repeat(65)).is_some());
        assert!(display_name_problem("a\u{7}").is_some());
        assert!(display_name_problem("").is_none());
    }

    #[test]
    fn rule_view_reads_core_rule() {
        let rule: AclRule = serde_json::from_value(json!({
            "id": "r1",
            "permission_group": { "kind": "devices", "ids": ["d1", "d2"] },
            "protocol_ports": [{ "proto": "tcp", "start": 25565, "end": 25565 }, { "proto": "udp", "start": 1, "end": 65535 }],
            "action": "allow",
            "enabled": false,
            "principal_mnemonics": { "d1": "pc.alice" },
        }))
        .unwrap();
        let v = RuleView::from_rule(&rule);
        assert_eq!(v.summary(), "2 台设备可以访问 2 组端口");
        assert_eq!(v.sources(), "设备: pc.alice (d1), d2");
        assert_eq!(v.ports_text(), "tcp/25565, udp/*");
        assert!(!v.enabled);

        let any: AclRule = serde_json::from_value(json!({
            "id": "r2", "permission_group": { "kind": "devices", "ids": ["*"] }, "action": "deny",
        }))
        .unwrap();
        let v = RuleView::from_rule(&any);
        assert_eq!(v.summary(), "所有设备不能访问 0 组端口");
        assert_eq!(v.sources(), "设备: 所有设备 (*)");
    }

    #[test]
    fn labels() {
        assert_eq!(path_with_reason(Some("relay"), Some("no_pong")).as_deref(), Some("中转（直连无响应）"));
        assert_eq!(path_with_reason(Some("direct:1.2.3.4:5"), None).as_deref(), Some("直连"));
        assert_eq!(path_with_reason(None, Some("probe")), None);
        assert_eq!(path_reason_label(Some("future")).as_deref(), Some("future"));
        assert_eq!(access_reason_label(&json!({ "type": "matched_rule", "rule_id": "0123456789" })), "规则 01234567");
        assert_eq!(
            discovery_stats_line(&json!({ "tx_messages": 3, "rx_accepted": 2, "drop": { "rate_limited": 1, "not_member": 0 } })),
            "已发送 3  已接收 2  丢弃（超出速率限制）1"
        );
    }

    #[test]
    fn numbers_and_times() {
        assert_eq!(format_bytes(512), "512 B");
        assert_eq!(format_bytes(1536), "1.5 KB");
        assert_eq!(format_bytes(3 * 1024 * 1024 * 1024), "3.0 GB");
        assert_eq!(format_time_ms_in(0, &chrono::Utc), "1970-01-01 00:00:00");
        assert_eq!(format_time_ms_in(1_709_210_096_000, &chrono::Utc), "2024-02-29 12:34:56");
        let cst = chrono::FixedOffset::east_opt(8 * 3600).unwrap();
        assert_eq!(format_time_ms_in(1_709_210_096_000, &cst), "2024-02-29 20:34:56");
    }
}
