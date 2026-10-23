//! `msst-net-cli` — the command-line Controller.
//!
//! Same features as the WebUI (`compose/webApp` via `msst-net-webui`), for
//! a terminal on the device running Core: every command connects through
//! the shared [`IpcClient`], waits until the view is synced from Core, runs
//! and exits.  Like the WebUI it does not take part in interactive
//! authorization (access requests are answered in the desktop or mobile
//! Controller, or time out in Core).
//!
//! Output is meant for people; `--json` prints Core's data instead.  Core
//! remains the authority: rule, room and DNS results are what Core
//! returned, and identities (device and user IDs, mnemonics) are passed to
//! Core as entered.  Short ID prefixes are only accepted for objects listed
//! by Core (rules, groups, records, access-log entries) and must be unique.

pub mod present;

use std::io::Write;
use std::time::Duration;

use clap::{Args, Parser, Subcommand, ValueEnum};
use msst_ipc_protocol::IpcRequest;
use serde_json::{Value, json};

use crate::error::{ControllerError, UiError};
use crate::ipc::client::{ControllerView, IpcClient};
use crate::notify::{UiNotice, spawn_notifier};
use crate::state::{ConnectionStatus, Resource, SyncState};
use present::*;

// ── Command line ─────────────────────────────────────────────────────────────

/// MSST-Net 命令行控制器：查看客户端核心状态，管理房间、防火墙规则、DNS 与网络设置。
#[derive(Parser, Debug)]
#[command(name = "msst-net-cli", version)]
pub struct Cli {
    /// 以 JSON 输出客户端核心返回的数据
    #[arg(long, global = true)]
    pub json: bool,

    /// 跳过确认提示（删除、清空、覆盖全部端口的规则等）
    #[arg(short = 'y', long, global = true)]
    pub yes: bool,

    /// 等待连接并同步客户端核心状态的最长时间（秒）
    #[arg(long, global = true, default_value_t = 10, value_name = "秒")]
    pub timeout: u64,

    /// 客户端核心的 IPC 路径（默认自动查找）
    #[arg(long = "ipc-path", env = "MSST_IPC_PATH", global = true, value_name = "路径")]
    pub ipc_path: Option<String>,

    #[command(subcommand)]
    pub command: Command,
}

#[derive(Subcommand, Debug)]
pub enum Command {
    /// 总览：连接状态、本机身份、房间
    Status,
    /// 持续显示状态变化（Ctrl-C 退出）
    Watch,
    /// 已知节点及其路径
    Nodes,
    /// 房间模式
    #[command(subcommand)]
    Room(RoomCmd),
    /// 防火墙规则（入站）
    #[command(subcommand)]
    Rules(RuleCmd),
    /// 权限组
    #[command(subcommand)]
    Groups(GroupCmd),
    /// 可用于规则的服务模板
    Services,
    /// 访问记录及快捷操作
    #[command(subcommand)]
    Access(AccessCmd),
    /// 查看或修改本机设备名与助记符
    Identity(IdentityArgs),
    /// MSST-Net 接入认证（在浏览器中完成）
    Login,
    /// 向协调服务器重新注册本设备
    Reregister,
    /// 网络设置
    #[command(subcommand)]
    Settings(SettingsCmd),
    /// 中转服务器、登记与游戏发现计数
    Relays,
    /// 路径视图（每个节点的直连候选、加密状态）
    Paths,
    /// 每个节点的流量计数
    Traffic,
    /// 本机公网端点
    Endpoints {
        /// 先请求客户端核心重新探测端点
        #[arg(long)]
        refresh: bool,
    },
    /// 经实际路径 ping 一个节点
    DiagPing {
        /// 目标设备 ID
        device_id: String,
    },
    /// 客户端核心日志
    Logs {
        /// 显示的条数
        #[arg(short = 'n', long, default_value_t = 200)]
        limit: u32,
        /// 清空日志缓冲区
        #[arg(long)]
        clear: bool,
    },
    /// 自定义 DNS（命名空间与记录）
    #[command(subcommand)]
    Dns(DnsCmd),
    /// 发送任意 IPC 请求（诊断用），如 '{"type":"get_paths"}'
    Raw {
        /// `IpcRequest` JSON：{"type": …, "params": {…}}
        request: String,
    },
}

#[derive(Subcommand, Debug)]
pub enum RoomCmd {
    /// 当前房间与成员
    Show,
    /// 创建房间并加入
    Create {
        /// 房间名
        name: String,
        /// 为房间设置密码；只写 --password 则在终端中输入（不回显，也不会留在命令历史里）
        #[arg(long, num_args = 0..=1, value_name = "密码")]
        password: Option<Option<String>>,
        /// 房间中转服务器：官方中转服务器地址（见 `relays`）或自托管的 msst-relay:// 连接串
        #[arg(long)]
        relay: Option<String>,
        /// 自托管中转服务器的接入密码；只写 --relay-password 则在终端中输入
        #[arg(long, num_args = 0..=1, value_name = "密码")]
        relay_password: Option<Option<String>>,
    },
    /// 加入房间
    Join {
        /// 房间号
        room_id: String,
        /// 房间密码；只写 --password 则在终端中输入。房间要求密码而未提供时也会在终端中询问
        #[arg(long, num_args = 0..=1, value_name = "密码")]
        password: Option<Option<String>>,
    },
    /// 退出当前房间
    Leave,
}

#[derive(Subcommand, Debug)]
pub enum RuleCmd {
    /// 列出规则
    List {
        /// 显示来源与端口详情
        #[arg(short, long)]
        verbose: bool,
    },
    /// 添加规则：一种来源 + 一种目标
    Add(RuleAddArgs),
    /// 启用规则
    Enable { rule_id: String },
    /// 停用规则
    Disable { rule_id: String },
    /// 修改规则备注（其他内容请删除后重建）
    Comment { rule_id: String, comment: String },
    /// 删除规则
    Delete { rule_id: String },
    /// 删除全部规则
    Clear,
}

#[derive(Args, Debug, Default)]
pub struct RuleAddArgs {
    /// 来源设备（设备 ID 或助记符，可重复）
    #[arg(long = "device", value_name = "ID")]
    pub devices: Vec<String>,
    /// 来源用户（用户 ID 或助记符，可重复）
    #[arg(long = "user", value_name = "ID")]
    pub users: Vec<String>,
    /// 来源为权限组（见 `groups list`）
    #[arg(long, value_name = "组 ID")]
    pub group: Option<String>,
    /// 来源为当前所有房间成员
    #[arg(long)]
    pub room_members: bool,
    /// 目标服务（见 `services`，可重复）
    #[arg(long = "service", value_name = "服务 ID")]
    pub services: Vec<String>,
    /// 目标协议与端口，如 tcp/25565、udp/1000-2000、tcp+udp/*；只写端口时为 tcp+udp（可重复）
    #[arg(long = "port", value_name = "协议/端口")]
    pub ports: Vec<String>,
    /// 目标为所有协议及端口
    #[arg(long)]
    pub all_ports: bool,
    /// 拒绝访问（默认允许）
    #[arg(long)]
    pub deny: bool,
    /// 备注
    #[arg(long)]
    pub comment: Option<String>,
}

#[derive(Subcommand, Debug)]
pub enum GroupCmd {
    /// 列出权限组
    List,
    /// 新建权限组
    Add {
        name: String,
        /// 成员类型
        #[arg(long, value_enum)]
        kind: GroupKind,
        /// 成员（设备或用户 ID / 助记符，可重复）
        #[arg(long = "member", value_name = "ID")]
        members: Vec<String>,
    },
    /// 修改权限组（给出 --member 时替换全部成员）
    Edit {
        group_id: String,
        #[arg(long)]
        name: Option<String>,
        #[arg(long = "member", value_name = "ID")]
        members: Vec<String>,
    },
    /// 删除权限组（已用它创建的规则不受影响）
    Delete { group_id: String },
}

#[derive(ValueEnum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum GroupKind {
    Device,
    User,
}

#[derive(Subcommand, Debug)]
pub enum AccessCmd {
    /// 最近的访问记录
    List {
        #[arg(short = 'n', long, default_value_t = 50)]
        limit: u32,
    },
    /// 为该记录的设备与端口添加 10 分钟内有效的允许规则
    AllowOnce { entry_id: String },
    /// 为该记录的设备与端口添加永久允许规则
    Allow { entry_id: String },
    /// 为该记录的设备与端口添加永久拒绝规则
    Deny { entry_id: String },
    /// 删除允许了该访问的永久规则
    Revoke { entry_id: String },
}

#[derive(Args, Debug)]
pub struct IdentityArgs {
    /// 新的设备名（空字符串恢复默认名称）
    #[arg(long)]
    pub name: Option<String>,
    /// 新的设备助记符：小写字母、数字和横杠（空字符串恢复为设备 ID）
    #[arg(long)]
    pub handle: Option<String>,
}

#[derive(Subcommand, Debug)]
pub enum SettingsCmd {
    /// 显示网络设置
    Show,
    /// 修改一项设置
    Set {
        #[arg(value_enum)]
        key: SettingKey,
        /// 值：on/off、数字、地址，或 auto 恢复自动
        value: String,
    },
}

#[derive(ValueEnum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum SettingKey {
    /// 禁用 P2P 直连（on/off）
    DisableP2p,
    /// 锁定当前路径（on/off）
    LockPath,
    /// 首选中转服务器（地址或 auto）
    PreferredRelay,
    /// MTU，1280–1500 或 auto（重启客户端核心后生效）
    Mtu,
    /// 中转保活间隔，5–30 秒或 auto
    Keepalive,
    /// Linux DNS 配置模式（prefer-split / iptables-only）
    DnsMode,
    /// Linux：绕过其他 VPN 的全局隧道（on/off）
    BypassOtherVpns,
    /// Linux：阻止其他 VPN 经 MSST-Net 传输（on/off）
    BlockOtherVpnsOverMsst,
    /// 响应其他设备的 ping（on/off）
    PingReply,
}

#[derive(Subcommand, Debug)]
pub enum DnsCmd {
    /// 我的命名空间
    Domains,
    /// 申请命名空间（需管理员审核）
    Register {
        namespace: String,
        /// 申请理由
        #[arg(long)]
        reason: String,
    },
    /// 命名空间下的记录
    Records { namespace: String },
    /// 添加记录
    Add {
        namespace: String,
        #[arg(long = "type", value_enum)]
        record_type: DnsType,
        /// 子域名（默认 @）
        #[arg(long)]
        name: Option<String>,
        #[command(flatten)]
        body: DnsBody,
    },
    /// 修改记录内容或 TTL
    Update {
        namespace: String,
        record_id: String,
        #[command(flatten)]
        body: DnsBody,
    },
    /// 删除记录
    Delete { namespace: String, record_id: String },
}

#[derive(ValueEnum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum DnsType {
    Cname,
    Txt,
    Srv,
}

impl DnsType {
    fn wire(self) -> &'static str {
        match self {
            Self::Cname => "CNAME",
            Self::Txt => "TXT",
            Self::Srv => "SRV",
        }
    }
}

#[derive(Args, Debug, Default, Clone)]
pub struct DnsBody {
    /// CNAME 目标（ezPlay 主机名）或 TXT 文本
    #[arg(long)]
    pub content: Option<String>,
    /// SRV 优先级
    #[arg(long)]
    pub priority: Option<String>,
    /// SRV 权重
    #[arg(long)]
    pub weight: Option<String>,
    /// SRV 端口
    #[arg(long)]
    pub port: Option<String>,
    /// SRV 目标主机（ezPlay 主机名）
    #[arg(long)]
    pub target: Option<String>,
    /// TTL（秒，60–86400）
    #[arg(long)]
    pub ttl: Option<String>,
}

// ── Errors ───────────────────────────────────────────────────────────────────

#[derive(Debug)]
pub enum CliError {
    /// A request failed (Core's error, timeout, lost connection, …).
    Controller(ControllerError),
    /// Core could not be reached within `--timeout`.
    NotConnected { status: String, last_error: Option<String> },
    /// The command line asks for something invalid (checked before sending).
    Invalid(String),
    /// The user did not confirm.
    Cancelled,
}

impl From<ControllerError> for CliError {
    fn from(e: ControllerError) -> Self {
        Self::Controller(e)
    }
}

impl CliError {
    /// Process exit status: 1 failed, 2 invalid input, 3 Core unreachable.
    pub fn exit_code(&self) -> i32 {
        match self {
            Self::Invalid(_) => 2,
            Self::NotConnected { .. }
            | Self::Controller(
                ControllerError::NotConnected { .. }
                | ControllerError::ConnectionFailed { .. }
                | ControllerError::ConnectionLost
                | ControllerError::VersionMismatch { .. },
            ) => 3,
            _ => 1,
        }
    }

    /// The error as presented to users (message + optional detail).
    pub fn to_ui(&self) -> UiError {
        match self {
            Self::Controller(e) => UiError::from(e.clone()),
            Self::NotConnected { status, last_error } => UiError {
                category: "connection",
                code: "not_connected".into(),
                message: format!("未连接到 MSST-Net 客户端核心（{status}）。请确认客户端核心正在运行。"),
                detail: last_error.clone(),
            },
            Self::Invalid(m) => UiError { category: "request", code: "invalid_input".into(), message: m.clone(), detail: None },
            Self::Cancelled => UiError { category: "request", code: "cancelled".into(), message: "已取消。".into(), detail: None },
        }
    }
}

type Result<T> = std::result::Result<T, CliError>;

fn invalid<T>(m: impl Into<String>) -> Result<T> {
    Err(CliError::Invalid(m.into()))
}

/// A Core error with a message adapted to this front end.
fn core_code(e: &CliError) -> Option<&str> {
    match e {
        CliError::Controller(ControllerError::CoreError { code, .. }) => Some(code),
        _ => None,
    }
}

// ── Execution context ────────────────────────────────────────────────────────

/// Asks the user to confirm `prompt`: `Some(answer)`, or `None` when nobody
/// can be asked (not a terminal).
pub type Confirm<'a> = &'a mut dyn FnMut(&str) -> Option<bool>;

/// Asks the user for a secret (without echo): `Some(text)`, or `None` when
/// nobody can be asked (not a terminal).
pub type AskSecret<'a> = &'a mut dyn FnMut(&str) -> Option<String>;

pub struct Ctx<'a> {
    pub client: &'a IpcClient,
    pub out: &'a mut dyn Write,
    pub json: bool,
    pub yes: bool,
    pub timeout: Duration,
    pub confirm: Confirm<'a>,
    pub secret: AskSecret<'a>,
}

macro_rules! say {
    ($ctx:expr) => {{ let _ = writeln!($ctx.out); }};
    ($ctx:expr, $($arg:tt)*) => {{ let _ = writeln!($ctx.out, $($arg)*); }};
}

impl Ctx<'_> {
    fn print_json(&mut self, v: &Value) {
        say!(self, "{}", serde_json::to_string_pretty(v).unwrap_or_default());
    }

    /// Ask before a destructive or wide-reaching action.
    fn confirm(&mut self, prompt: &str) -> Result<()> {
        if self.yes {
            return Ok(());
        }
        match (self.confirm)(prompt) {
            Some(true) => Ok(()),
            Some(false) => Err(CliError::Cancelled),
            None => invalid(format!("{prompt}\n此操作需要确认，请加 --yes 运行。")),
        }
    }

    /// A password given as `--flag <value>`, asked for when given as a bare
    /// `--flag`; `None` when not given (or empty).
    fn password(&mut self, given: &Option<Option<String>>, prompt: &str, flag: &str) -> Result<Option<String>> {
        let value = match given {
            None => return Ok(None),
            Some(Some(v)) => v.clone(),
            Some(None) => self.ask_secret(prompt, flag)?,
        };
        Ok(Some(value.trim().to_string()).filter(|v| !v.is_empty()))
    }

    /// Ask for a secret Core requires; an empty answer is refused.
    fn ask_secret(&mut self, prompt: &str, flag: &str) -> Result<String> {
        match (self.secret)(prompt) {
            Some(v) if !v.trim().is_empty() => Ok(v.trim().to_string()),
            Some(_) => invalid("未输入密码。"),
            None => invalid(format!("无法在终端中输入密码，请用 {flag} <密码> 提供。")),
        }
    }

    async fn query(&self, req: IpcRequest) -> Result<Value> {
        Ok(self.client.query(req).await?)
    }

    async fn command(&self, req: IpcRequest) -> Result<Value> {
        Ok(self.client.command(req).await?)
    }

    /// Print Core's result as JSON, or `text` for people.
    fn done(&mut self, result: &Value, text: impl std::fmt::Display) {
        if self.json {
            self.print_json(result);
        } else {
            say!(self, "{text}");
        }
    }
}

/// Wait until the client is connected and the view was synced from Core
/// (or the sync finished with failures, which the banner reports).  A
/// connected but still syncing view is returned after the timeout.
pub async fn wait_ready(client: &IpcClient, timeout: Duration) -> Result<ControllerView> {
    let deadline = tokio::time::Instant::now() + timeout;
    loop {
        let v = client.view().await;
        let connected = v.connection == ConnectionStatus::Connected;
        if connected && matches!(v.state.sync, SyncState::Synced | SyncState::Failed { .. }) {
            return Ok(v);
        }
        if v.connection == ConnectionStatus::Incompatible {
            return Err(ControllerError::VersionMismatch {
                remote: v.core_protocol.clone().unwrap_or_else(|| "未知".into()),
                local: v.controller_protocol.clone(),
            }
            .into());
        }
        if tokio::time::Instant::now() >= deadline {
            if connected {
                return Ok(v);
            }
            return Err(CliError::NotConnected {
                status: connection_label(&v.connection),
                last_error: v.diagnostics.last_error.clone(),
            });
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
}

/// Run one command.
pub async fn run(ctx: &mut Ctx<'_>, command: &Command) -> Result<()> {
    let view = match wait_ready(ctx.client, ctx.timeout).await {
        Ok(v) => v,
        Err(e @ (CliError::NotConnected { .. } | CliError::Controller(ControllerError::VersionMismatch { .. })))
            if matches!(command, Command::Status) =>
        {
            // Status still shows what is known about the connection.
            let v = ctx.client.view().await;
            status(ctx, &v);
            return Err(e);
        }
        Err(e) => return Err(e),
    };
    match command {
        Command::Status => {
            status(ctx, &view);
            Ok(())
        }
        Command::Watch => watch(ctx).await,
        Command::Nodes => {
            nodes(ctx, &view);
            Ok(())
        }
        Command::Room(c) => room(ctx, &view, c).await,
        Command::Rules(c) => rules(ctx, &view, c).await,
        Command::Groups(c) => groups(ctx, &view, c).await,
        Command::Services => services(ctx).await,
        Command::Access(c) => access(ctx, &view, c).await,
        Command::Identity(a) => identity(ctx, &view, a).await,
        Command::Login => login(ctx, &view).await,
        Command::Reregister => {
            let r = ctx.command(IpcRequest::ReRegister).await?;
            ctx.done(&r, "客户端核心已向协调服务器重新注册本设备。");
            Ok(())
        }
        Command::Settings(c) => settings(ctx, &view, c).await,
        Command::Relays => relays(ctx).await,
        Command::Paths => paths(ctx).await,
        Command::Traffic => traffic(ctx).await,
        Command::Endpoints { refresh } => endpoints(ctx, *refresh).await,
        Command::DiagPing { device_id } => diag_ping(ctx, &view, device_id).await,
        Command::Logs { limit, clear } => logs(ctx, *limit, *clear).await,
        Command::Dns(c) => dns(ctx, c).await,
        Command::Raw { request } => raw(ctx, request).await,
    }
}

// ── Status ───────────────────────────────────────────────────────────────────

fn status(ctx: &mut Ctx<'_>, v: &ControllerView) {
    if ctx.json {
        ctx.print_json(&serde_json::to_value(v).unwrap_or_default());
        return;
    }
    say!(ctx, "客户端核心：{}", core_status_label(v));
    say!(
        ctx,
        "连接：{}（协议版本：控制器 {} / 客户端核心 {}）",
        connection_label(&v.connection),
        v.controller_protocol,
        v.core_protocol.as_deref().unwrap_or("—")
    );
    if let Some(b) = core_banner(v) {
        let mark = match b.kind {
            BannerKind::Info => "提示",
            BannerKind::Warn => "注意",
            BannerKind::Error => "错误",
        };
        say!(ctx, "[{mark}] {}", b.text);
        if let Some(d) = b.detail {
            say!(ctx, "       {d}");
        }
    }
    if v.connection != ConnectionStatus::Connected {
        if v.diagnostics.successful_connects == 0
            && let Some(e) = &v.diagnostics.last_error
        {
            say!(ctx, "最近错误：{e}");
        }
        return;
    }
    let s = &v.state;
    if let Some(h) = &s.health {
        say!(ctx);
        let name = h.device_display_name.as_deref().unwrap_or("（未设置设备名）");
        say!(ctx, "本机设备：{name}");
        let dev = device_mnemonic(s);
        if !dev.is_empty() {
            say!(ctx, "  设备标识：{dev}");
        }
        let user = user_mnemonic(s);
        if !user.is_empty() {
            say!(ctx, "  用户标识：{user}{}", h.user_display_name.as_deref().map(|n| format!("（{n}）")).unwrap_or_default());
        }
        say!(
            ctx,
            "  ezPlay 主机名：{}",
            h.device_hostname.as_deref().unwrap_or("将在首次成功连接协调服务器后自动分配")
        );
        let auth = match h.coordinator_credential.as_deref() {
            Some("guest") => "访客身份（仅可使用房间模式）".to_string(),
            _ if h.is_authenticated => "已完成接入认证".to_string(),
            _ => "未认证".to_string(),
        };
        say!(ctx, "  接入认证：{auth}");
        say!(ctx, "  协调服务器：{}", tri_state(h.ws_connected, "已连接", "未连接"));
        say!(ctx, "  虚拟网卡：{}", tri_state(h.tun_ready, "就绪", "未就绪"));
        if let Some(r) = &h.relay {
            say!(ctx, "  当前中转：{r}");
        }
        say!(ctx, "  响应 ping：{}", tri_state(h.ping_enabled, "开启", "关闭"));
    }
    say!(ctx);
    say!(ctx, "节点：{} 个    防火墙规则：{} 条", s.nodes.len(), s.acl_rules.len());
    room_lines(ctx, v);
}

fn room_lines(ctx: &mut Ctx<'_>, v: &ControllerView) {
    let room = &v.state.room;
    match room.room_id.as_deref().filter(|id| !id.is_empty()) {
        None => {
            say!(ctx, "房间：未加入房间");
        }
        Some(id) => {
            say!(ctx, "房间：{}（房间号 {id}）  {} 位成员", room.room_name.as_deref().unwrap_or("未命名"), room.peers.len());
            if let Some(l) = room_relay_label(room.room_relay.as_ref()) {
                say!(ctx, "  {l}");
            }
        }
    }
    for w in &room.network_warnings {
        say!(ctx, "[注意] {}", network_warning_label(w));
    }
}

/// One line describing the view, for `watch`.
fn status_line(v: &ControllerView) -> String {
    let mut s = format!("{} · {}", connection_label(&v.connection), core_status_label(v));
    if v.connection == ConnectionStatus::Connected {
        let st = &v.state;
        s += &format!(" · 节点 {} · 规则 {}", st.nodes.len(), st.acl_rules.len());
        match st.room.room_id.as_deref().filter(|id| !id.is_empty()) {
            Some(_) => {
                s += &format!(
                    " · 房间 {}（{} 位成员）",
                    st.room.room_name.as_deref().unwrap_or("未命名"),
                    st.room.peers.len()
                )
            }
            None => s += " · 未加入房间",
        }
    }
    if let Some(b) = core_banner(v) {
        s += &format!(" · {}", b.text);
    }
    s
}

async fn watch(ctx: &mut Ctx<'_>) -> Result<()> {
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
    let notifier = spawn_notifier(ctx.client, move |n| {
        if matches!(n, UiNotice::Changed) {
            let _ = tx.send(());
        }
    });
    let mut last = String::new();
    let mut tick = tokio::time::interval(Duration::from_secs(5));
    loop {
        let v = ctx.client.view().await;
        if ctx.json {
            let line = serde_json::to_string(&v).unwrap_or_default();
            if line != last {
                say!(ctx, "{line}");
                last = line;
            }
        } else {
            let line = status_line(&v);
            if line != last {
                let now = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis() as i64)
                    .unwrap_or(0);
                say!(ctx, "{}  {line}", format_time_ms(now));
                last = line;
            }
        }
        let _ = ctx.out.flush();
        tokio::select! {
            n = rx.recv() => if n.is_none() { break },
            // Connection status changes without a notice while retrying.
            _ = tick.tick() => {}
            _ = tokio::signal::ctrl_c() => break,
        }
    }
    notifier.abort();
    Ok(())
}

// ── Nodes ────────────────────────────────────────────────────────────────────

fn nodes(ctx: &mut Ctx<'_>, v: &ControllerView) {
    let s = &v.state;
    if ctx.json {
        ctx.print_json(&serde_json::to_value(&s.nodes).unwrap_or_default());
        return;
    }
    if s.nodes.is_empty() {
        say!(ctx, "暂无已知节点。");
        return;
    }
    for n in s.nodes.values() {
        let name = n.display_name.as_deref().or(n.hostname.as_deref()).unwrap_or(&n.device_id);
        say!(ctx, "{name}");
        say!(ctx, "  设备 ID：{}", n.device_id);
        let mnemonic = match (&n.device_handle, &n.user_handle) {
            (Some(d), Some(u)) => Some(format!("{d}.{u}")),
            (Some(d), None) => Some(d.clone()),
            _ => None,
        };
        if let Some(m) = mnemonic {
            say!(ctx, "  助记符：{m}");
        }
        if let Some(h) = n.hostname.as_deref().filter(|h| Some(*h) != n.display_name.as_deref()) {
            say!(ctx, "  主机名：{h}");
        }
        let addrs: Vec<&str> = [n.ipv4.as_deref(), n.ipv6.as_deref()].into_iter().flatten().filter(|a| !a.is_empty()).collect();
        if !addrs.is_empty() {
            say!(ctx, "  地址：{}", addrs.join("  "));
        }
        say!(ctx, "  路径：{}", path_with_reason(n.path_mode.as_deref(), n.path_reason.as_deref()).unwrap_or_else(|| "未知".into()));
    }
}

// ── Room ─────────────────────────────────────────────────────────────────────

/// Room commands that need the coordinator: refused early with the reason
/// Core reported, like the graphical Controller does.
fn require_coordinator(v: &ControllerView, what: &str) -> Result<()> {
    if coordinator_unreachable(v) {
        invalid(format!("无法连接协调服务器，暂时无法{what}。"))
    } else if !core_ready(v) {
        invalid(format!("客户端核心未就绪，暂时无法{what}。"))
    } else {
        Ok(())
    }
}

async fn room(ctx: &mut Ctx<'_>, v: &ControllerView, cmd: &RoomCmd) -> Result<()> {
    match cmd {
        RoomCmd::Show => {
            if ctx.json {
                ctx.print_json(&serde_json::to_value(&v.state.room).unwrap_or_default());
                return Ok(());
            }
            room_lines(ctx, v);
            if v.state.room.room_id.as_deref().is_none_or(str::is_empty) {
                return Ok(());
            }
            if v.state.room.peers.is_empty() {
                say!(ctx, "  暂无成员信息");
            }
            let self_id = v.state.health.as_ref().and_then(|h| h.device_id.clone());
            for p in &v.state.room.peers {
                let me = self_id.as_deref() == Some(p.device_id.as_str());
                let name = p.display_name.as_deref().or(p.hostname.as_deref()).unwrap_or(&p.device_id);
                say!(ctx, "  {name}{}", if me { "（本机）" } else { "" });
                say!(ctx, "    设备 ID：{}", p.device_id);
                if let Some(ip) = p.ipv4.as_deref().filter(|s| !s.is_empty()) {
                    say!(ctx, "    地址：{ip}");
                }
                if !me {
                    let n = v.state.nodes.get(&p.device_id);
                    let path = n.and_then(|n| path_with_reason(n.path_mode.as_deref(), n.path_reason.as_deref()));
                    say!(ctx, "    路径：{}", path.unwrap_or_else(|| "未知".into()));
                }
                if let Some(note) = discovery_note(p) {
                    say!(ctx, "    {note}");
                }
            }
            Ok(())
        }
        RoomCmd::Create { name, password, relay, relay_password } => {
            require_coordinator(v, "创建或加入房间")?;
            let name = name.trim();
            if name.is_empty() {
                return invalid("请输入房间名。");
            }
            let relay = relay.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_string);
            let mut password = ctx.password(password, "房间密码：", "--password")?;
            let mut relay_password = ctx.password(relay_password, "中转服务器接入密码：", "--relay-password")?;
            say_progress(ctx, "正在创建房间…");
            // Core says which password is missing; ask once for each.
            let r = loop {
                let req = IpcRequest::CreateRoom {
                    room_name: name.to_string(),
                    password: password.clone(),
                    relay: relay.clone(),
                    relay_password: relay_password.clone(),
                };
                match ctx.command(req).await {
                    Ok(r) => break r,
                    Err(e) if core_code(&e) == Some("relay_password_required") && relay_password.is_none() => {
                        relay_password =
                            Some(ctx.ask_secret("该中转服务器设有接入密码，请输入中转服务器接入密码：", "--relay-password")?);
                    }
                    // On CreateRoom this comes from the relay, not from a room.
                    Err(e) if core_code(&e) == Some("room_password_required") && password.is_none() => {
                        password = Some(ctx.ask_secret("所选中转服务器要求房间设置密码，请为房间设置密码：", "--password")?);
                    }
                    Err(e) => return Err(e),
                }
                say_progress(ctx, "正在重新创建房间…");
            };
            let id = r.get("room_id").and_then(Value::as_str).unwrap_or("");
            ctx.done(&r, format!("已创建并加入房间 {name}（房间号 {id}）。把房间号告诉朋友即可加入。"));
            Ok(())
        }
        RoomCmd::Join { room_id, password } => {
            require_coordinator(v, "创建或加入房间")?;
            let room_id = room_id.trim();
            if room_id.is_empty() {
                return invalid("请输入房间号。");
            }
            let mut password = ctx.password(password, "房间密码：", "--password")?;
            say_progress(ctx, "正在加入房间…");
            let r = loop {
                let req = IpcRequest::JoinRoom { room_id: room_id.to_string(), password: password.clone() };
                match ctx.command(req).await {
                    Ok(r) => break r,
                    Err(e) if core_code(&e) == Some("room_password_required") && password.is_none() => {
                        let prompt = format!("房间 {room_id} 设有密码，请输入房间创建者提供的密码：");
                        password = Some(ctx.ask_secret(&prompt, "--password")?);
                    }
                    Err(e) => return Err(e),
                }
                say_progress(ctx, "正在重新加入房间…");
            };
            let name = r.get("room_name").and_then(Value::as_str).unwrap_or(room_id);
            ctx.done(&r, format!("已加入房间 {name}（房间号 {room_id}）。"));
            Ok(())
        }
        RoomCmd::Leave => {
            let Some(id) = v.state.room.room_id.clone().filter(|id| !id.is_empty()) else {
                return invalid("当前不在房间中。");
            };
            let r = ctx.command(IpcRequest::LeaveRoom { room_id: id.clone() }).await?;
            ctx.done(&r, format!("已退出房间 {id}。"));
            Ok(())
        }
    }
}

/// Progress for people (stderr, so `--json` output stays clean).
fn say_progress(ctx: &Ctx<'_>, text: &str) {
    if !ctx.json {
        eprintln!("{text}");
    }
}

// ── Rules ────────────────────────────────────────────────────────────────────

/// Resolve `input` to one of `ids`: the ID itself or a unique prefix.
pub fn resolve_id<'a>(input: &str, ids: impl IntoIterator<Item = &'a str>, what: &str) -> Result<String> {
    let input = input.trim();
    let ids: Vec<&str> = ids.into_iter().collect();
    if let Some(id) = ids.iter().find(|id| **id == input) {
        return Ok(id.to_string());
    }
    let matches: Vec<&&str> = ids.iter().filter(|id| !input.is_empty() && id.starts_with(input)).collect();
    match matches.as_slice() {
        [one] => Ok(one.to_string()),
        [] => invalid(format!("找不到{what} {input}。")),
        _ => invalid(format!("{what} {input} 对应多个对象，请输入更长的 ID。")),
    }
}

fn short(id: &str) -> String {
    id.chars().take(8).collect()
}

/// A `--port` value as (proto, port); a bare port means `tcp+udp`.
pub fn parse_port_item(s: &str) -> Result<(String, String)> {
    let s = s.trim();
    let (proto, port) = match s.split_once('/') {
        Some((p, port)) => (p.trim().to_lowercase(), port.trim().to_string()),
        None => ("tcp+udp".to_string(), s.to_string()),
    };
    if !matches!(proto.as_str(), "tcp" | "udp" | "tcp+udp") {
        return invalid(format!("协议无效：{proto}（可填 tcp、udp 或 tcp+udp）"));
    }
    if !valid_port_expr(&port) {
        return invalid(format!("端口格式无效：{port}（可填 *、80 或 80-443，范围 1-65535）"));
    }
    Ok((proto, port))
}

/// The `create_acl_rule` body for `rules add` (the rule wizard's request,
/// Core's 3.0.x-compatible `RuleRequest`), and what needs confirming.
/// Identities are passed as entered: Core resolves mnemonics and rejects
/// unknown ones.
pub fn build_rule(a: &RuleAddArgs, group_id: Option<String>) -> Result<(Value, Option<String>)> {
    let sources = [!a.devices.is_empty(), !a.users.is_empty(), group_id.is_some(), a.room_members];
    match sources.iter().filter(|b| **b).count() {
        0 => return invalid("请指定来源：--device、--user、--group 或 --room-members 之一。"),
        1 => {}
        _ => return invalid("一条规则只能有一种来源：--device、--user、--group 与 --room-members 不能同时使用。"),
    }
    let targets = [!a.services.is_empty(), !a.ports.is_empty(), a.all_ports];
    match targets.iter().filter(|b| **b).count() {
        0 => return invalid("请指定目标：--service、--port 或 --all-ports 之一。"),
        1 => {}
        _ => return invalid("一条规则只能有一种目标：--service、--port 与 --all-ports 不能同时使用。"),
    }

    let mut rule = serde_json::Map::new();
    rule.insert("action".into(), json!(if a.deny { "deny" } else { "allow" }));
    rule.insert("direction".into(), json!("inbound"));
    rule.insert("enabled".into(), json!(true));
    rule.insert("expires_at".into(), Value::Null);

    let principals = |kind: &str, ids: &[String]| -> Result<Value> {
        let mut seen = Vec::<&str>::new();
        for id in ids {
            let id = id.trim();
            if id.is_empty() {
                return invalid("来源不能为空。");
            }
            if !seen.contains(&id) {
                seen.push(id);
            }
        }
        Ok(Value::Array(seen.into_iter().map(|id| json!({ "kind": kind, "id": id })).collect()))
    };
    if let Some(g) = group_id {
        rule.insert("group_id".into(), json!(g));
        rule.insert("principals".into(), json!([]));
    } else if a.room_members {
        rule.insert("principals".into(), json!([{ "kind": "space", "id": "*" }]));
    } else if !a.devices.is_empty() {
        rule.insert("principals".into(), principals("device", &a.devices)?);
    } else {
        rule.insert("principals".into(), principals("user", &a.users)?);
    }

    let mut risk = None;
    if a.all_ports {
        rule.insert("protocol_ports".into(), json!([{ "proto": "tcp+udp", "port": "*" }]));
        risk = Some("所有协议及端口".to_string());
    } else if !a.ports.is_empty() {
        let mut items = Vec::<(String, String)>::new();
        for p in &a.ports {
            let item = parse_port_item(p)?;
            if covers_all_ports(&item.1) && risk.is_none() {
                let proto = if item.0 == "tcp+udp" { "TCP 和 UDP".to_string() } else { item.0.to_uppercase() };
                risk = Some(format!("{proto} 的全部端口"));
            }
            if !items.contains(&item) {
                items.push(item);
            }
        }
        rule.insert(
            "protocol_ports".into(),
            Value::Array(items.into_iter().map(|(proto, port)| json!({ "proto": proto, "port": port })).collect()),
        );
    } else {
        let mut ids = Vec::<String>::new();
        for s in &a.services {
            let s = s.trim().to_string();
            if !s.is_empty() && !ids.contains(&s) {
                ids.push(s);
            }
        }
        rule.insert("services".into(), json!(ids));
    }
    if let Some(c) = a.comment.as_deref().map(str::trim).filter(|c| !c.is_empty()) {
        rule.insert("comment".into(), json!(c));
    }
    Ok((Value::Object(rule), risk))
}

async fn rules(ctx: &mut Ctx<'_>, v: &ControllerView, cmd: &RuleCmd) -> Result<()> {
    let rules = &v.state.acl_rules;
    let rule_id = |input: &str| resolve_id(input, rules.iter().map(|r| r.id.as_str()), "规则");
    match cmd {
        RuleCmd::List { verbose } => {
            if ctx.json {
                ctx.print_json(&serde_json::to_value(rules).unwrap_or_default());
                return Ok(());
            }
            if rules.is_empty() {
                say!(ctx, "暂无防火墙规则。未匹配任何规则的入站访问会被拒绝。");
                return Ok(());
            }
            let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
            for r in rules {
                let rv = RuleView::from_rule(r);
                let mut flags = Vec::new();
                if !rv.enabled {
                    flags.push("已停用".to_string());
                }
                if let Some(t) = rv.expires_at {
                    flags.push(if t <= now { "已过期".into() } else { format!("{} 到期", format_time_ms(t * 1000)) });
                }
                if rv.pending_migration {
                    flags.push("含未解析的助记符".into());
                }
                let flags = if flags.is_empty() { String::new() } else { format!("  [{}]", flags.join("，")) };
                let action = if rv.action == "allow" { "允许" } else { "拒绝" };
                say!(ctx, "{}  {action}  {}{flags}", short(&rv.id), rv.summary());
                if let Some(c) = &rv.comment {
                    say!(ctx, "          备注：{c}");
                }
                if *verbose {
                    say!(ctx, "          ID：{}", rv.id);
                    say!(ctx, "          来源：{}", rv.sources());
                    say!(ctx, "          端口：{}", rv.ports_text());
                }
            }
            Ok(())
        }
        RuleCmd::Add(a) => {
            let group_id = match &a.group {
                Some(g) => Some(resolve_id(g, v.state.acl_groups.iter().map(|g| g.id.as_str()), "权限组")?),
                None => None,
            };
            let (rule, risk) = build_rule(a, group_id)?;
            if let Some(what) = risk {
                let verb = if a.deny { "拒绝" } else { "允许" };
                ctx.confirm(&format!("此规则将{verb}来源访问本机{what}。确定添加？"))?;
            }
            let r = ctx.command(IpcRequest::CreateAclRule { rule }).await?;
            let id = r.pointer("/rule/id").or_else(|| r.get("id")).and_then(Value::as_str).map(short).unwrap_or_default();
            ctx.done(&r, format!("已添加规则 {id}。"));
            Ok(())
        }
        RuleCmd::Enable { rule_id: id } | RuleCmd::Disable { rule_id: id } => {
            let enabled = matches!(cmd, RuleCmd::Enable { .. });
            let id = rule_id(id)?;
            let r = ctx.command(IpcRequest::UpdateAclRule { rule_id: id.clone(), patch: json!({ "enabled": enabled }) }).await?;
            ctx.done(&r, format!("规则 {} 已{}。", short(&id), if enabled { "启用" } else { "停用" }));
            Ok(())
        }
        RuleCmd::Comment { rule_id: id, comment } => {
            let id = rule_id(id)?;
            let patch = json!({ "comment": comment.trim() });
            let r = ctx.command(IpcRequest::UpdateAclRule { rule_id: id.clone(), patch }).await?;
            ctx.done(&r, format!("规则 {} 的备注已更新。", short(&id)));
            Ok(())
        }
        RuleCmd::Delete { rule_id: id } => {
            let id = rule_id(id)?;
            let summary = rules.iter().find(|r| r.id == id).map(|r| RuleView::from_rule(r).summary()).unwrap_or_default();
            ctx.confirm(&format!("确定删除规则 {}（{summary}）？", short(&id)))?;
            let r = ctx.command(IpcRequest::DeleteAclRule { rule_id: id.clone() }).await?;
            ctx.done(&r, format!("已删除规则 {}。", short(&id)));
            Ok(())
        }
        RuleCmd::Clear => {
            ctx.confirm(&format!("确定清空全部 {} 条规则？此操作不可恢复。", rules.len()))?;
            let r = crate::ops::clear_acl_rules(ctx.client).await?;
            if ctx.json {
                ctx.print_json(&r);
            } else {
                say!(ctx, "已删除 {} 条规则。", r["deleted"].as_u64().unwrap_or(0));
                for f in r["failed"].as_array().into_iter().flatten() {
                    say!(ctx, "  规则 {} 删除失败：{}", f["rule_id"].as_str().unwrap_or(""), f["message"].as_str().unwrap_or(""));
                }
            }
            if r["failed"].as_array().is_some_and(|f| !f.is_empty()) {
                return Err(CliError::Controller(ControllerError::State("部分规则删除失败。".into())));
            }
            Ok(())
        }
    }
}

async fn groups(ctx: &mut Ctx<'_>, v: &ControllerView, cmd: &GroupCmd) -> Result<()> {
    let groups = &v.state.acl_groups;
    let group_id = |input: &str| resolve_id(input, groups.iter().map(|g| g.id.as_str()), "权限组");
    let members = |ids: &[String]| -> Vec<String> {
        let mut out = Vec::<String>::new();
        for id in ids.iter().map(|s| s.trim()).filter(|s| !s.is_empty()) {
            if !out.iter().any(|o| o == id) {
                out.push(id.to_string());
            }
        }
        out
    };
    match cmd {
        GroupCmd::List => {
            if ctx.json {
                ctx.print_json(&serde_json::to_value(groups).unwrap_or_default());
                return Ok(());
            }
            if groups.is_empty() {
                say!(ctx, "暂无权限组。");
            }
            for g in groups {
                let kind = if g.kind == "user" { "用户" } else { "设备" };
                say!(ctx, "{}  {}（{kind}，{} 个成员）", short(&g.id), g.name, g.member_ids.len());
                for m in &g.member_ids {
                    let name = device_name(&v.state, m);
                    say!(ctx, "          {}", if name != *m { format!("{name} ({m})") } else { m.clone() });
                }
            }
            Ok(())
        }
        GroupCmd::Add { name, kind, members: m } => {
            let name = name.trim();
            if name.is_empty() {
                return invalid("请输入权限组名称。");
            }
            let kind = if *kind == GroupKind::User { "user" } else { "device" };
            let group = json!({ "name": name, "kind": kind, "member_ids": members(m) });
            let r = ctx.command(IpcRequest::CreateAclGroup { group }).await?;
            ctx.done(&r, format!("已创建权限组 {name}。"));
            Ok(())
        }
        GroupCmd::Edit { group_id: id, name, members: m } => {
            let id = group_id(id)?;
            let current = groups.iter().find(|g| g.id == id).expect("resolved from this list");
            let name = name.as_deref().map(str::trim).unwrap_or(current.name.as_str()).to_string();
            if name.is_empty() {
                return invalid("请输入权限组名称。");
            }
            let member_ids = if m.is_empty() { current.member_ids.clone() } else { members(m) };
            let patch = json!({ "name": name, "member_ids": member_ids });
            let r = ctx.command(IpcRequest::UpdateAclGroup { group_id: id, patch }).await?;
            ctx.done(&r, format!("已更新权限组 {name}。"));
            Ok(())
        }
        GroupCmd::Delete { group_id: id } => {
            let id = group_id(id)?;
            let name = groups.iter().find(|g| g.id == id).map(|g| g.name.clone()).unwrap_or_default();
            ctx.confirm(&format!("确定删除权限组「{name}」？已用它创建的规则不受影响。"))?;
            let r = ctx.command(IpcRequest::DeleteAclGroup { group_id: id }).await?;
            ctx.done(&r, format!("已删除权限组 {name}。"));
            Ok(())
        }
    }
}

async fn services(ctx: &mut Ctx<'_>) -> Result<()> {
    let r = ctx.query(IpcRequest::GetServices).await?;
    if ctx.json {
        ctx.print_json(&r);
        return Ok(());
    }
    for cat in r["categories"].as_array().into_iter().flatten() {
        say!(ctx, "{}", cat["label"].as_str().unwrap_or(""));
        for s in cat["services"].as_array().into_iter().flatten() {
            let ports: Vec<&str> = s["ports"].as_array().into_iter().flatten().filter_map(Value::as_str).collect();
            say!(ctx, "  {:<20} {}  {}", s["id"].as_str().unwrap_or(""), s["name"].as_str().unwrap_or(""), ports.join(", "));
        }
    }
    Ok(())
}

// ── Access log ───────────────────────────────────────────────────────────────

/// Same TTL Core uses for "allow once" decisions.
pub const ONCE_TTL_SECS: i64 = 600;

/// A `create_acl_rule` body for one device and port (access-log actions).
pub fn quick_device_rule(device_id: &str, device_name: &str, proto: &str, port: u64, action: &str, expires_at: Option<i64>) -> Value {
    json!({
        "action": action,
        "direction": "inbound",
        "enabled": true,
        "expires_at": expires_at,
        "principals": [{ "kind": "device", "id": device_id }],
        "protocol_ports": [{ "proto": proto, "port": port.to_string() }],
        "comment": format!("访问记录操作：{} {device_name} {proto}/{port}", if action == "allow" { "允许" } else { "拒绝" }),
    })
}

async fn access(ctx: &mut Ctx<'_>, v: &ControllerView, cmd: &AccessCmd) -> Result<()> {
    let limit = match cmd {
        AccessCmd::List { limit } => *limit,
        _ => 200,
    };
    let log = ctx.query(IpcRequest::GetAccessLog { limit: Some(limit) }).await?;
    let entries: Vec<Value> = log["entries"].as_array().cloned().unwrap_or_default();
    let s = &v.state;

    let AccessCmd::List { .. } = cmd else {
        let input = match cmd {
            AccessCmd::AllowOnce { entry_id } | AccessCmd::Allow { entry_id } | AccessCmd::Deny { entry_id } | AccessCmd::Revoke { entry_id } => entry_id,
            AccessCmd::List { .. } => unreachable!(),
        };
        let id = resolve_id(input, entries.iter().filter_map(|e| e["id"].as_str()), "访问记录")?;
        let e = entries.iter().find(|e| e["id"] == id.as_str()).expect("resolved from this list");
        let device = e["src_device_id"].as_str().unwrap_or("");
        let proto = e["proto"].as_str().unwrap_or("");
        let port = e["dst_port"].as_u64().unwrap_or(0);
        let allowed = e["decision"] == "allow";
        // The rule Core reports as deciding this access, if it is permanent.
        let permanent = |action: &str| -> Option<String> {
            let rid = e["rule_id"].as_str()?;
            let r = s.acl_rules.iter().find(|r| r.id == rid)?;
            let rv = RuleView::from_rule(r);
            (rv.action == action && rv.expires_at.is_none()).then(|| rid.to_string())
        };
        let name = device_name(s, device);
        let rule = |action: &str, expires_at: Option<i64>| quick_device_rule(device, &name, proto, port, action, expires_at);
        let (req, text) = match cmd {
            AccessCmd::Revoke { .. } => {
                let Some(rid) = permanent("allow").filter(|_| allowed) else {
                    return invalid("这条访问不是由永久允许规则放行的，没有可撤销的授权。");
                };
                ctx.confirm(&format!("将删除规则 {}，{name} 将不能再访问 {proto}/{port}。确定撤销？", short(&rid)))?;
                (IpcRequest::DeleteAclRule { rule_id: rid }, "已撤销授权。".to_string())
            }
            AccessCmd::AllowOnce { .. } if allowed => return invalid("这条访问已被允许。"),
            AccessCmd::AllowOnce { .. } => {
                let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
                (
                    IpcRequest::CreateAclRule { rule: rule("allow", Some(now + ONCE_TTL_SECS)) },
                    format!("已允许 {name} 在 10 分钟内访问 {proto}/{port}。"),
                )
            }
            AccessCmd::Allow { .. } if permanent("allow").is_some() => return invalid("已有永久允许规则。"),
            AccessCmd::Allow { .. } => {
                (IpcRequest::CreateAclRule { rule: rule("allow", None) }, format!("已始终允许 {name} 访问 {proto}/{port}。"))
            }
            AccessCmd::Deny { .. } if allowed => return invalid("这条访问已被允许；如需禁止，请撤销授权或添加拒绝规则。"),
            AccessCmd::Deny { .. } if permanent("deny").is_some() => return invalid("已有永久拒绝规则。"),
            AccessCmd::Deny { .. } => {
                (IpcRequest::CreateAclRule { rule: rule("deny", None) }, format!("已始终拒绝 {name} 访问 {proto}/{port}。"))
            }
            AccessCmd::List { .. } => unreachable!(),
        };
        let r = ctx.command(req).await?;
        ctx.done(&r, text);
        return Ok(());
    };

    if ctx.json {
        ctx.print_json(&log);
        return Ok(());
    }
    if entries.is_empty() {
        say!(ctx, "暂无访问记录。");
        return Ok(());
    }
    for e in &entries {
        let name = device_name(s, e["src_device_id"].as_str().unwrap_or(""));
        let target = match e["service_name"].as_str() {
            Some(svc) => svc.to_string(),
            None => format!("{}/{}", e["proto"].as_str().unwrap_or(""), e["dst_port"].as_u64().unwrap_or(0)),
        };
        let count = e["count"].as_u64().unwrap_or(1);
        say!(
            ctx,
            "{}  {}{}  {name} 访问 {target}：{}（{}）",
            short(e["id"].as_str().unwrap_or("")),
            format_time_ms(e["timestamp_ms"].as_i64().unwrap_or(0)),
            if count > 1 { format!(" ×{count}") } else { String::new() },
            decision_label(e["decision"].as_str().unwrap_or("")),
            access_reason_label(&e["reason"]),
        );
    }
    say!(ctx);
    say!(ctx, "快捷操作：access allow-once|allow|deny|revoke <记录 ID>");
    Ok(())
}

// ── Identity and login ───────────────────────────────────────────────────────

async fn identity(ctx: &mut Ctx<'_>, v: &ControllerView, a: &IdentityArgs) -> Result<()> {
    let h = v.state.health.clone().unwrap_or_default();
    if a.name.is_none() && a.handle.is_none() {
        if ctx.json {
            ctx.print_json(&serde_json::to_value(&h).unwrap_or_default());
            return Ok(());
        }
        say!(ctx, "设备名：{}", h.device_display_name.as_deref().unwrap_or("（未设置）"));
        say!(ctx, "设备助记符：{}", h.device_handle.as_deref().unwrap_or("（未设置，使用设备 ID）"));
        say!(ctx, "设备 ID：{}", h.device_id.as_deref().unwrap_or("未知"));
        say!(ctx, "用户：{}", h.user_display_name.as_deref().or(h.user_handle.as_deref()).or(h.user_id.as_deref()).unwrap_or("未知"));
        return Ok(());
    }
    // Validate both before sending either.
    let name = a.name.as_deref().map(str::trim);
    let handle = a.handle.as_deref().map(str::trim);
    if let Some(p) = name.and_then(display_name_problem) {
        return invalid(p);
    }
    if let Some(hd) = handle.filter(|h| !h.is_empty() && !valid_handle(h)) {
        return invalid(format!("助记符只能包含小写字母、数字和横杠：{hd}"));
    }
    let mut results = Vec::new();
    if let Some(n) = name.filter(|n| *n != h.device_display_name.as_deref().unwrap_or("")) {
        results.push(ctx.command(IpcRequest::UpdateDisplayName { user: None, device: Some(n.to_string()) }).await?);
        say_progress(ctx, "设备名已更新。");
    }
    if let Some(hd) = handle.filter(|hd| *hd != h.device_handle.as_deref().unwrap_or("")) {
        results.push(ctx.command(IpcRequest::UpdateHandle { user: None, device: Some(hd.to_string()) }).await?);
        say_progress(ctx, "设备助记符已更新。");
    }
    if results.is_empty() {
        say_progress(ctx, "没有需要修改的内容。");
    }
    if ctx.json {
        ctx.print_json(&Value::Array(results));
    }
    Ok(())
}

async fn login(ctx: &mut Ctx<'_>, v: &ControllerView) -> Result<()> {
    require_coordinator(v, "发起接入认证")?;
    let started = ctx.query(IpcRequest::AuthStart).await?;
    let session_id = started["session_id"]
        .as_str()
        .ok_or_else(|| CliError::Controller(ControllerError::Codec("AuthStart: response has no session_id".into())))?
        .to_string();
    let uri = started["verification_uri"].as_str().unwrap_or("");
    let code = started["user_code"].as_str().unwrap_or("");
    say!(ctx, "请在浏览器中打开：{uri}");
    if !code.is_empty() {
        say!(ctx, "并输入代码：{code}");
    }
    say!(ctx, "等待完成认证…（Ctrl-C 取消）");
    let _ = ctx.out.flush();

    let mut interval = started["interval"].as_u64().unwrap_or(5).max(1);
    loop {
        tokio::time::sleep(Duration::from_secs(interval)).await;
        let p = ctx.query(IpcRequest::AuthPoll { session_id: session_id.clone() }).await?;
        let status = p["status"].as_str();
        let waiting = p["pending"].as_bool() == Some(true) || matches!(status, Some("authorization_pending" | "slow_down"));
        if !waiting {
            break;
        }
        if status == Some("slow_down") {
            interval += 5;
        }
    }

    // A finished poll means Core stored a result, not that it is valid:
    // success only once Core's Health reports a valid authentication.
    say!(ctx, "认证页面已完成，正在等待客户端核心确认…");
    for _ in 0..30 {
        ctx.client.refresh(vec![Resource::Health]);
        tokio::time::sleep(Duration::from_millis(500)).await;
        let h = ctx.client.view().await.state.health;
        if h.is_some_and(|h| h.is_authenticated) {
            say!(ctx, "接入认证已完成。");
            return Ok(());
        }
    }
    Err(CliError::Controller(ControllerError::State(
        "认证页面已完成，但客户端核心尚未确认认证结果。请稍后运行 `msst-net-cli status` 查看，或重试。".into(),
    )))
}

// ── Settings ─────────────────────────────────────────────────────────────────

fn parse_bool(s: &str) -> Result<bool> {
    match s.trim().to_lowercase().as_str() {
        "on" | "true" | "yes" | "1" | "开" | "开启" => Ok(true),
        "off" | "false" | "no" | "0" | "关" | "关闭" => Ok(false),
        other => invalid(format!("无效的开关值：{other}（可填 on 或 off）")),
    }
}

fn parse_override(s: &str, range: std::ops::RangeInclusive<i64>, problem: &str) -> Result<Value> {
    let s = s.trim();
    if s.eq_ignore_ascii_case("auto") {
        return Ok(Value::Null);
    }
    match s.parse::<i64>() {
        Ok(n) if range.contains(&n) => Ok(json!(n)),
        _ => invalid(problem.to_string()),
    }
}

/// The request for `settings set` (`SetNetSettings` with one field, or
/// `SetPingEnabled`).
pub fn settings_request(key: SettingKey, value: &str) -> Result<IpcRequest> {
    let (field, v) = match key {
        SettingKey::PingReply => return Ok(IpcRequest::SetPingEnabled { enabled: parse_bool(value)? }),
        SettingKey::DisableP2p => ("disable_p2p", json!(parse_bool(value)?)),
        SettingKey::LockPath => ("lock_path", json!(parse_bool(value)?)),
        SettingKey::BypassOtherVpns => ("bypass_other_vpns", json!(parse_bool(value)?)),
        SettingKey::BlockOtherVpnsOverMsst => ("block_other_vpns_over_msst", json!(parse_bool(value)?)),
        SettingKey::PreferredRelay => {
            let v = value.trim();
            if v.is_empty() {
                return invalid("请填写中转服务器地址，或 auto 自动选择。");
            }
            ("preferred_relay", if v.eq_ignore_ascii_case("auto") { Value::Null } else { json!(v) })
        }
        SettingKey::Mtu => ("mtu_override", parse_override(value, 1280..=1500, "MTU 应为 1280–1500 之间的整数，或 auto")?),
        SettingKey::Keepalive => ("keepalive_secs", parse_override(value, 5..=30, "保活间隔应为 5–30 秒之间的整数，或 auto")?),
        SettingKey::DnsMode => match value.trim() {
            "prefer-split" | "prefer_split" => ("linux_dns_mode", json!("prefer_split")),
            "iptables-only" | "iptables_only" => ("linux_dns_mode", json!("iptables_only")),
            other => return invalid(format!("无效的 DNS 配置模式：{other}（可填 prefer-split 或 iptables-only）")),
        },
    };
    let mut settings = serde_json::Map::new();
    settings.insert(field.into(), v);
    Ok(IpcRequest::SetNetSettings { settings: Value::Object(settings) })
}

fn print_settings(ctx: &mut Ctx<'_>, s: &Value, ping: Option<bool>) {
    let on = |k: &str| if s[k].as_bool() == Some(true) { "开启" } else { "关闭" };
    say!(ctx, "禁用 P2P 直连（disable-p2p）：{}", on("disable_p2p"));
    say!(ctx, "锁定当前路径（lock-path）：{}", on("lock_path"));
    say!(ctx, "首选中转服务器（preferred-relay）：{}", s["preferred_relay"].as_str().unwrap_or("自动选择最优"));
    say!(ctx, "MTU（mtu，重启客户端核心后生效）：{}", s["mtu_override"].as_i64().map(|n| n.to_string()).unwrap_or("自动".into()));
    say!(ctx, "中转保活间隔（keepalive）：{}", s["keepalive_secs"].as_i64().map(|n| format!("{n} 秒")).unwrap_or("自动 (25 s)".into()));
    say!(ctx, "响应 ping（ping-reply）：{}", tri_state(ping, "开启", "关闭"));
    if s.get("linux_dns_mode").is_some_and(|m| !m.is_null()) || s.get("bypass_other_vpns").is_some() {
        say!(ctx, "Linux：");
        let mode = if s["linux_dns_mode"] == "iptables_only" { "仅使用 DNS 拦截" } else { "优先使用 DNS 分流" };
        say!(ctx, "  DNS 配置模式（dns-mode）：{mode}");
        if let Some(b) = s["bypass_other_vpns"].as_bool() {
            say!(ctx, "  绕过其他 VPN 的全局隧道（bypass-other-vpns）：{}", if b { "开启" } else { "关闭" });
        }
        if let Some(b) = s["block_other_vpns_over_msst"].as_bool() {
            say!(ctx, "  阻止其他 VPN 经 MSST-Net 传输（block-other-vpns-over-msst）：{}", if b { "开启" } else { "关闭" });
        }
    }
}

async fn settings(ctx: &mut Ctx<'_>, v: &ControllerView, cmd: &SettingsCmd) -> Result<()> {
    let ping = v.state.health.as_ref().and_then(|h| h.ping_enabled);
    match cmd {
        SettingsCmd::Show => {
            let s = ctx.query(IpcRequest::GetNetSettings).await?;
            if ctx.json {
                ctx.print_json(&s);
            } else {
                print_settings(ctx, &s, ping);
            }
            Ok(())
        }
        SettingsCmd::Set { key, value } => {
            let req = settings_request(*key, value)?;
            let is_ping = matches!(req, IpcRequest::SetPingEnabled { .. });
            let r = ctx.command(req).await?;
            if ctx.json {
                ctx.print_json(&r);
            } else if is_ping {
                say!(ctx, "已更新。");
            } else {
                // Show what Core stored.
                say!(ctx, "已更新，客户端核心当前的设置：");
                print_settings(ctx, &r, ping);
            }
            Ok(())
        }
    }
}

// ── Diagnostics ──────────────────────────────────────────────────────────────

async fn relays(ctx: &mut Ctx<'_>) -> Result<()> {
    let r = ctx.query(IpcRequest::GetRelays).await?;
    if ctx.json {
        ctx.print_json(&r);
        return Ok(());
    }
    let current = r["current"].as_str();
    say!(ctx, "官方中转服务器：");
    for relay in r["relays"].as_array().into_iter().flatten() {
        let addr = relay["addr"].as_str().unwrap_or("");
        let rtt = r["rtt_ms"][addr].as_f64().map(|v| format!("  {} ms", one_decimal(v))).unwrap_or_default();
        say!(ctx, "  {addr}{rtt}{}", if current == Some(addr) { "（当前）" } else { "" });
    }
    if let Some(reg) = r.get("registration").filter(|v| !v.is_null()) {
        let mode = if reg["mode"] == "sreg1" { "已认证登记（sreg1）" } else { "旧式登记（未认证）" };
        say!(ctx, "中转登记（当前中转）：{mode}  已发送 {}", reg["tx_sreg"].as_u64().unwrap_or(0));
        match reg["last_relay_path_ok_at"].as_i64() {
            Some(t) => say!(ctx, "  中转路径最近一次验证通过：{}", format_time_ms(t * 1000)),
            None => say!(ctx, "  中转路径尚未验证通过"),
        }
    }
    if let Some(rr) = r.get("room_relay").filter(|v| !v.is_null()) {
        let relay = crate::state::RoomRelay {
            address: rr["address"].as_str().unwrap_or("").into(),
            self_hosted: rr["self_hosted"].as_bool().unwrap_or(false),
            state: rr["state"].as_str().unwrap_or("").into(),
        };
        say!(ctx, "房间中转服务器：{}", room_relay_label(Some(&relay)).unwrap_or_default());
        say!(ctx, "  解析地址 {}  已发送登记 {}", rr["addr"].as_str().unwrap_or("-"), rr["tx_sreg"].as_u64().unwrap_or(0));
    }
    if let Some(d) = r.get("discovery").filter(|v| !v.is_null()) {
        say!(ctx, "游戏发现（经 WireGuard 端到端发送）：{}", discovery_stats_line(d));
    }
    Ok(())
}

async fn paths(ctx: &mut Ctx<'_>) -> Result<()> {
    let r = ctx.query(IpcRequest::GetPathView).await?;
    if ctx.json {
        ctx.print_json(&r);
        return Ok(());
    }
    let peers = r["peers"].as_array().cloned().unwrap_or_default();
    if peers.is_empty() {
        say!(ctx, "暂无路径数据。");
    }
    for e in &peers {
        let id = e["device_id"].as_str().unwrap_or("");
        match e["display_name"].as_str() {
            Some(n) => say!(ctx, "{n} ({id})"),
            None => say!(ctx, "{id}"),
        }
        say!(ctx, "  选中：{}", e["chosen"].as_str().unwrap_or(""));
        if let Some(r) = path_reason_label(e["reason"].as_str()) {
            say!(ctx, "  最近一次切换原因：{r}");
        }
        if let Some(enc) = encryption_label(e["encryption"].as_str()) {
            let at = e["last_handshake_at"].as_i64().map(|t| format!("（握手于 {}）", format_time_ms(t * 1000))).unwrap_or_default();
            say!(ctx, "  加密：{enc}{at}");
        }
        let failures = e["probe_failures"].as_u64().unwrap_or(0);
        if failures > 0 {
            let until = e["next_probe_at"].as_i64().map(|t| format!("，{} 前不再探测", format_time_ms(t * 1000))).unwrap_or_default();
            say!(ctx, "  直连探测已连续失败 {failures} 轮{until}");
        }
        for c in e["candidates"].as_array().into_iter().flatten() {
            let rtt = c["rtt_ms"].as_f64().map(|v| format!("{} ms", one_decimal(v))).unwrap_or("-".into());
            let loss = c["loss_rate"].as_f64().map(|l| format!("loss {}%", (l * 100.0).round() as i64)).unwrap_or_default();
            say!(ctx, "    {}  {rtt}  {loss}  [{}]", c["addr"].as_str().unwrap_or(""), c["status"].as_str().unwrap_or(""));
        }
    }
    Ok(())
}

async fn traffic(ctx: &mut Ctx<'_>) -> Result<()> {
    let r = ctx.query(IpcRequest::GetTrafficStats).await?;
    if ctx.json {
        ctx.print_json(&r);
        return Ok(());
    }
    let peers = r["peers"].as_array().cloned().unwrap_or_default();
    if peers.is_empty() {
        say!(ctx, "暂无流量数据。");
    }
    say!(ctx, "（路径切换时计数自动重置）");
    for t in &peers {
        let name = t["display_name"].as_str().or(t["device_id"].as_str()).unwrap_or("");
        say!(
            ctx,
            "{name}  ↑ {}  ↓ {}  丢弃 {}",
            format_bytes(t["tx"].as_u64().unwrap_or(0)),
            format_bytes(t["rx"].as_u64().unwrap_or(0)),
            t["drop"].as_u64().unwrap_or(0)
        );
    }
    Ok(())
}

async fn endpoints(ctx: &mut Ctx<'_>, refresh: bool) -> Result<()> {
    if refresh {
        ctx.command(IpcRequest::RefreshEndpoint).await?;
        // Detection runs in Core asynchronously; re-read shortly after.
        say_progress(ctx, "已请求重新探测，稍候读取结果…");
        tokio::time::sleep(Duration::from_secs(3)).await;
    }
    let r = ctx.query(IpcRequest::GetEndpoints).await?;
    if ctx.json {
        ctx.print_json(&r);
        return Ok(());
    }
    let current: Vec<&str> = r["current"].as_array().into_iter().flatten().filter_map(Value::as_str).collect();
    say!(ctx, "当前端点：");
    if current.is_empty() {
        say!(ctx, "  （未探测到）");
    }
    for c in current {
        say!(ctx, "  {c}");
    }
    let history = r["history"].as_array().cloned().unwrap_or_default();
    if !history.is_empty() {
        say!(ctx, "历史端点：");
        for h in &history {
            let eps: Vec<&str> = h["endpoints"].as_array().into_iter().flatten().filter_map(Value::as_str).collect();
            // Core sends `reported_at` (Unix seconds).
            let at = h["reported_at"].as_i64().or_else(|| h["timestamp"].as_i64());
            let at = at.map(|t| format_time_ms(t * 1000)).unwrap_or_else(|| "时间未知".into());
            say!(ctx, "  {at}  {}", eps.join(", "));
        }
    }
    if refresh {
        say!(ctx, "（探测可能仍在进行，稍后可再次运行 `endpoints` 查看）");
    }
    Ok(())
}

async fn diag_ping(ctx: &mut Ctx<'_>, v: &ControllerView, device_id: &str) -> Result<()> {
    let device_id = device_id.trim();
    if device_id.is_empty() {
        return invalid("请输入目标设备 ID。");
    }
    let job = ctx.query(IpcRequest::StartDiagPing { device_id: device_id.to_string() }).await?;
    let job_id = job["job_id"]
        .as_str()
        .ok_or_else(|| CliError::Controller(ControllerError::Codec("StartDiagPing: response has no job_id".into())))?
        .to_string();
    if !ctx.json {
        say!(ctx, "Ping {}：", device_name(&v.state, device_id));
    }
    let mut shown = 0;
    let deadline = tokio::time::Instant::now() + Duration::from_secs(120);
    let last = loop {
        tokio::time::sleep(Duration::from_millis(500)).await;
        let p = ctx.query(IpcRequest::GetDiagPingResult { job_id: job_id.clone() }).await?;
        let results = p["results"].as_array().cloned().unwrap_or_default();
        if !ctx.json {
            for (i, r) in results.iter().enumerate().skip(shown) {
                say!(ctx, "  #{}: {}", i + 1, r.as_f64().map(|v| format!("{} ms", one_decimal(v))).unwrap_or("超时".into()));
            }
            let _ = ctx.out.flush();
        }
        shown = shown.max(results.len());
        if p["done"].as_bool() == Some(true) {
            break p;
        }
        if tokio::time::Instant::now() >= deadline {
            return Err(ControllerError::RequestTimeout { request_id: job_id, timeout_secs: 120 }.into());
        }
    };
    if ctx.json {
        ctx.print_json(&last);
        return Ok(());
    }
    let results: Vec<Option<f64>> = last["results"].as_array().into_iter().flatten().map(Value::as_f64).collect();
    let rtts: Vec<f64> = results.iter().flatten().copied().collect();
    if let Some(p) = diag_path_label(last["path"].as_str()) {
        say!(ctx, "路径：{p}");
    }
    let avg = (!rtts.is_empty()).then(|| format!(" / 平均 RTT {} ms", one_decimal(rtts.iter().sum::<f64>() / rtts.len() as f64)));
    say!(ctx, "发送 {} / 收到 {} / 丢失 {}{}", results.len(), rtts.len(), results.len() - rtts.len(), avg.unwrap_or_default());
    Ok(())
}

async fn logs(ctx: &mut Ctx<'_>, limit: u32, clear: bool) -> Result<()> {
    if clear {
        let r = ctx.command(IpcRequest::ClearLogs).await?;
        ctx.done(&r, "已清空日志。");
        return Ok(());
    }
    let r = ctx.query(IpcRequest::GetLogs { limit: Some(limit) }).await?;
    if ctx.json {
        ctx.print_json(&r);
        return Ok(());
    }
    for e in r["entries"].as_array().into_iter().flatten() {
        say!(
            ctx,
            "{}  {:<5} {}  {}",
            format_time_ms(e["timestamp_ms"].as_i64().unwrap_or(0)),
            e["level"].as_str().unwrap_or(""),
            e["target"].as_str().unwrap_or(""),
            e["message"].as_str().unwrap_or("")
        );
    }
    Ok(())
}

// ── DNS ──────────────────────────────────────────────────────────────────────

/// Record content and TTL from the form fields (the DNS screen's checks).
pub fn dns_record_body(record_type: DnsType, b: &DnsBody) -> Result<(String, i64)> {
    let field = |o: &Option<String>| o.as_deref().map(str::trim).unwrap_or("").to_string();
    let content = if record_type == DnsType::Srv {
        let (priority, weight, port, target) = (field(&b.priority), field(&b.weight), field(&b.port), field(&b.target));
        if target.is_empty() {
            return invalid("请填写记录内容（SRV 需要 --target）。");
        }
        let in_range = |s: &str, r: std::ops::RangeInclusive<u32>| s.parse::<u32>().is_ok_and(|n| r.contains(&n));
        if !in_range(&priority, 0..=65535) || !in_range(&weight, 0..=65535) {
            return invalid("优先级和权重应为 0–65535 之间的整数。");
        }
        if !in_range(&port, 1..=65535) {
            return invalid("端口应为 1–65535 之间的整数。");
        }
        format!("{priority} {weight} {port} {target}")
    } else {
        field(&b.content)
    };
    if content.is_empty() {
        return invalid("请填写记录内容（--content）。");
    }
    match field(&b.ttl).parse::<i64>() {
        Ok(ttl) if (60..=86400).contains(&ttl) => Ok((content, ttl)),
        _ => invalid("TTL 应为 60–86400 之间的整数。"),
    }
}

async fn dns(ctx: &mut Ctx<'_>, cmd: &DnsCmd) -> Result<()> {
    match cmd {
        DnsCmd::Domains => {
            let r = ctx.query(IpcRequest::GetDnsDomains).await?;
            if ctx.json {
                ctx.print_json(&r);
                return Ok(());
            }
            let list = r["namespaces"].as_array().cloned().unwrap_or_default();
            if list.is_empty() {
                say!(ctx, "暂无命名空间。可用 `dns register <命名空间> --reason <理由>` 申请。");
            }
            for n in &list {
                let status = match n["status"].as_str().unwrap_or("") {
                    "pending" => "待审核".to_string(),
                    "approved" => "已批准".to_string(),
                    "rejected" => format!("已拒绝{}", n["reject_reason"].as_str().map(|r| format!("：{r}")).unwrap_or_default()),
                    other => other.to_string(),
                };
                let paused = if n["paused"].as_bool() == Some(true) { "（已暂停）" } else { "" };
                say!(ctx, "*.{}.{MSST_ZONE}  {status}{paused}", n["namespace"].as_str().unwrap_or(""));
            }
            Ok(())
        }
        DnsCmd::Register { namespace, reason } => {
            let Some(name) = normalize_namespace(namespace) else {
                return invalid("命名空间只能包含小写字母、数字和连字符，不能以连字符开头或结尾，最长 63 个字符。");
            };
            if RESERVED_NAMESPACES.contains(&name.as_str()) {
                return invalid(format!("“{name}”是保留名称，请换一个。"));
            }
            if reason.trim().is_empty() {
                return invalid("请填写申请理由。");
            }
            let r = ctx.command(IpcRequest::DnsRegisterDomain { namespace: name, reason: reason.trim().to_string() }).await?;
            ctx.done(&r, "申请已提交，等待管理员审核。每人最多同时拥有 5 个命名空间（待审核+已审批合计）。");
            Ok(())
        }
        DnsCmd::Records { namespace } => {
            let r = ctx.query(IpcRequest::GetDnsRecords { domain: namespace.trim().to_string() }).await?;
            if ctx.json {
                ctx.print_json(&r);
                return Ok(());
            }
            let list = r["records"].as_array().cloned().unwrap_or_default();
            if list.is_empty() {
                say!(ctx, "暂无记录。");
            }
            for rec in &list {
                say!(
                    ctx,
                    "{}  {:<5} {}.{}.{MSST_ZONE}  {}  TTL {}",
                    short(rec["id"].as_str().unwrap_or("")),
                    rec["record_type"].as_str().unwrap_or(""),
                    rec["name"].as_str().unwrap_or(""),
                    namespace.trim(),
                    rec["content"].as_str().unwrap_or(""),
                    rec["ttl"].as_i64().unwrap_or(300)
                );
            }
            Ok(())
        }
        DnsCmd::Add { namespace, record_type, name, body } => {
            let mut b = body.clone();
            if b.ttl.is_none() {
                b.ttl = Some("300".into());
            }
            if *record_type == DnsType::Srv {
                b.priority.get_or_insert("10".into());
                b.weight.get_or_insert("20".into());
                b.port.get_or_insert("443".into());
            }
            let (content, ttl) = dns_record_body(*record_type, &b)?;
            let name = name.as_deref().map(str::trim).filter(|n| !n.is_empty()).unwrap_or("@");
            let record = json!({ "record_type": record_type.wire(), "name": name, "content": content, "ttl": ttl });
            let r = ctx.command(IpcRequest::DnsCreateRecord { domain: namespace.trim().to_string(), record }).await?;
            ctx.done(&r, "已添加。");
            Ok(())
        }
        DnsCmd::Update { namespace, record_id, body } => {
            let ns = namespace.trim().to_string();
            let list = ctx.query(IpcRequest::GetDnsRecords { domain: ns.clone() }).await?;
            let records = list["records"].as_array().cloned().unwrap_or_default();
            let id = resolve_id(record_id, records.iter().filter_map(|r| r["id"].as_str()), "记录")?;
            let rec = records.iter().find(|r| r["id"] == id.as_str()).expect("resolved from this list");
            let t = match rec["record_type"].as_str() {
                Some("SRV") => DnsType::Srv,
                Some("TXT") => DnsType::Txt,
                _ => DnsType::Cname,
            };
            // Start from the record as Core has it; apply what was given.
            let content = rec["content"].as_str().unwrap_or("");
            let srv: Vec<&str> = content.split(' ').collect();
            let keep = |given: &Option<String>, current: Option<&str>| given.clone().or(current.map(str::to_string));
            let b = DnsBody {
                content: keep(&body.content, Some(content)),
                priority: keep(&body.priority, srv.first().copied()),
                weight: keep(&body.weight, srv.get(1).copied()),
                port: keep(&body.port, srv.get(2).copied()),
                target: keep(&body.target, srv.get(3).copied()),
                ttl: keep(&body.ttl, Some(&rec["ttl"].as_i64().unwrap_or(300).to_string())),
            };
            let (content, ttl) = dns_record_body(t, &b)?;
            let patch = json!({ "content": content, "ttl": ttl });
            let r = ctx.command(IpcRequest::DnsUpdateRecord { domain: ns, record_id: id, patch }).await?;
            ctx.done(&r, "已更新。");
            Ok(())
        }
        DnsCmd::Delete { namespace, record_id } => {
            let ns = namespace.trim().to_string();
            let list = ctx.query(IpcRequest::GetDnsRecords { domain: ns.clone() }).await?;
            let records = list["records"].as_array().cloned().unwrap_or_default();
            let id = resolve_id(record_id, records.iter().filter_map(|r| r["id"].as_str()), "记录")?;
            let rec = records.iter().find(|r| r["id"] == id.as_str()).expect("resolved from this list");
            ctx.confirm(&format!(
                "确定删除记录 {} {}.{ns}？",
                rec["record_type"].as_str().unwrap_or(""),
                rec["name"].as_str().unwrap_or("")
            ))?;
            let r = ctx.command(IpcRequest::DnsDeleteRecord { domain: ns, record_id: id }).await?;
            ctx.done(&r, "已删除。");
            Ok(())
        }
    }
}

// ── Raw requests ─────────────────────────────────────────────────────────────

async fn raw(ctx: &mut Ctx<'_>, request: &str) -> Result<()> {
    let req: IpcRequest = serde_json::from_str(request).map_err(|e| CliError::Invalid(format!("无效的请求: {e}")))?;
    if matches!(req, IpcRequest::AuthDecision { .. }) {
        return invalid("命令行控制器不支持交互式授权，请在本机的桌面或移动控制器中处理访问请求。");
    }
    let r = ctx.command(req).await?;
    ctx.print_json(&r);
    Ok(())
}

#[cfg(test)]
mod tests;
