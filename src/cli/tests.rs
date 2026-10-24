use super::*;
use crate::ipc::client::IpcClientConfig;
use crate::ipc::mock::{FakeCore, default_responses};
use crate::state::StateStore;
use msst_ipc_protocol::IpcError;

/// Run `args` (without the program name) against `core`; returns the
/// result, stdout, and the requests Core received after the initial sync.
async fn run_cli(core: &FakeCore, args: &[&str], answer: Option<bool>) -> (Result<()>, String, Vec<IpcRequest>) {
    run_cli_with(core, args, answer, None).await
}

/// [`run_cli`] with the answer to password prompts (`None`: no terminal).
async fn run_cli_with(
    core: &FakeCore,
    args: &[&str],
    answer: Option<bool>,
    secret: Option<&str>,
) -> (Result<()>, String, Vec<IpcRequest>) {
    let cli = Cli::try_parse_from(std::iter::once("msst-net-cli").chain(args.iter().copied())).unwrap();
    let client = IpcClient::with_connector(IpcClientConfig::default(), StateStore::new(), core.connector());
    let mut out = Vec::new();
    let mut confirm = |_: &str| answer;
    let mut ask = |_: &str| secret.map(str::to_string);
    // Only the requests the command sent, not the initial sync.
    wait_ready(&client, Duration::from_secs(5)).await.ok();
    core.clear_requests();
    let mut ctx = Ctx {
        client: &client,
        out: &mut out,
        json: cli.json,
        yes: cli.yes,
        timeout: Duration::from_millis(cli.timeout * 1000),
        confirm: &mut confirm,
        secret: &mut ask,
    };
    let r = run(&mut ctx, &cli.command).await;
    client.shutdown();
    let sent = core.requests().into_iter().filter(|r| !is_sync_query(r)).collect();
    (r, String::from_utf8(out).unwrap(), sent)
}

/// Queries the client sends by itself (sync, refresh after commands).
fn is_sync_query(r: &IpcRequest) -> bool {
    matches!(
        r,
        IpcRequest::Health
            | IpcRequest::GetSystemState
            | IpcRequest::GetNodes
            | IpcRequest::GetPathView
            | IpcRequest::GetAclRules
            | IpcRequest::GetAclGroups
            | IpcRequest::GetPendingAuth
            | IpcRequest::SetLogStream { .. }
    )
}

fn rules_core() -> FakeCore {
    FakeCore::new(|req| match req {
        IpcRequest::GetAclRules => Ok(json!({ "rules": [
            { "id": "aaaa1111-0000", "permission_group": { "kind": "devices", "ids": ["d1"] },
              "protocol_ports": [{ "proto": "tcp", "start": 25565, "end": 25565 }], "action": "allow", "enabled": true },
            { "id": "aaaa2222-0000", "permission_group": { "kind": "space", "ids": [] },
              "protocol_ports": [], "action": "deny", "enabled": true },
        ] })),
        IpcRequest::CreateAclRule { rule } => Ok(json!({ "rule": { "id": "new-rule-id", "echo": rule } })),
        other => default_responses(other),
    })
}

fn add_args(f: impl FnOnce(&mut RuleAddArgs)) -> RuleAddArgs {
    let mut a = RuleAddArgs::default();
    f(&mut a);
    a
}

// ── Pure helpers ─────────────────────────────────────────────────────────────

#[test]
fn rule_body_matches_the_wizard() {
    let (rule, risk) = build_rule(
        &add_args(|a| {
            a.devices = vec!["pc.alice".into(), "pc.alice".into(), "d2".into()];
            a.ports = vec!["tcp/25565".into(), "19132".into()];
            a.comment = Some("  Minecraft ".into());
        }),
        None,
    )
    .unwrap();
    assert_eq!(
        rule,
        json!({
            "action": "allow", "direction": "inbound", "enabled": true, "expires_at": null,
            "principals": [{ "kind": "device", "id": "pc.alice" }, { "kind": "device", "id": "d2" }],
            "protocol_ports": [{ "proto": "tcp", "port": "25565" }, { "proto": "tcp+udp", "port": "19132" }],
            "comment": "Minecraft",
        })
    );
    assert_eq!(risk, None);

    let (rule, _) = build_rule(&add_args(|a| a.services = vec!["minecraft_java".into()]), Some("g1".into())).unwrap();
    assert_eq!(rule["group_id"], "g1");
    assert_eq!(rule["principals"], json!([]));
    assert_eq!(rule["services"], json!(["minecraft_java"]));

    let (rule, risk) = build_rule(&add_args(|a| { a.room_members = true; a.all_ports = true; a.deny = true; }), None).unwrap();
    assert_eq!(rule["principals"], json!([{ "kind": "space", "id": "*" }]));
    assert_eq!(rule["protocol_ports"], json!([{ "proto": "tcp+udp", "port": "*" }]));
    assert_eq!(rule["action"], "deny");
    assert_eq!(risk.as_deref(), Some("所有协议及端口"));

    let (_, risk) = build_rule(&add_args(|a| { a.users = vec!["u".into()]; a.ports = vec!["udp/1-65535".into()]; }), None).unwrap();
    assert_eq!(risk.as_deref(), Some("UDP 的全部端口"));
}

#[test]
fn rule_body_rejects_invalid_input() {
    let err = |a: RuleAddArgs| match build_rule(&a, None) {
        Err(CliError::Invalid(m)) => m,
        other => panic!("{other:?}"),
    };
    assert!(err(add_args(|a| a.ports = vec!["80".into()])).contains("来源"));
    assert!(err(add_args(|a| a.devices = vec!["d".into()])).contains("目标"));
    assert!(err(add_args(|a| { a.devices = vec!["d".into()]; a.users = vec!["u".into()]; a.all_ports = true; })).contains("一种来源"));
    assert!(err(add_args(|a| { a.devices = vec!["d".into()]; a.all_ports = true; a.ports = vec!["80".into()]; })).contains("一种目标"));
    assert!(err(add_args(|a| { a.devices = vec!["d".into()]; a.ports = vec!["tcp/0".into()]; })).contains("端口格式无效"));
    assert!(err(add_args(|a| { a.devices = vec!["d".into()]; a.ports = vec!["icmp/1".into()]; })).contains("协议无效"));
    assert!(err(add_args(|a| { a.devices = vec![" ".into()]; a.all_ports = true; })).contains("来源不能为空"));
}

#[test]
fn id_prefixes_must_be_unique() {
    let ids = ["aaaa1111", "aaaa2222", "bbbb"];
    assert_eq!(resolve_id("bb", ids, "规则").unwrap(), "bbbb");
    assert_eq!(resolve_id("aaaa1111", ids, "规则").unwrap(), "aaaa1111");
    assert!(matches!(resolve_id("aaaa", ids, "规则"), Err(CliError::Invalid(m)) if m.contains("多个")));
    assert!(matches!(resolve_id("c", ids, "规则"), Err(CliError::Invalid(m)) if m.contains("找不到")));
    assert!(matches!(resolve_id("", ids, "规则"), Err(CliError::Invalid(_))));
}

#[test]
fn settings_map_to_core_fields() {
    let set = |k, v: &str| serde_json::to_value(settings_request(k, v).unwrap()).unwrap();
    assert_eq!(set(SettingKey::DisableP2p, "on"), json!({ "type": "set_net_settings", "params": { "settings": { "disable_p2p": true } } }));
    assert_eq!(set(SettingKey::Mtu, "auto")["params"]["settings"], json!({ "mtu_override": null }));
    assert_eq!(set(SettingKey::Keepalive, "15")["params"]["settings"], json!({ "keepalive_secs": 15 }));
    assert_eq!(set(SettingKey::DnsMode, "iptables-only")["params"]["settings"], json!({ "linux_dns_mode": "iptables_only" }));
    assert_eq!(set(SettingKey::PreferredRelay, "AUTO")["params"]["settings"], json!({ "preferred_relay": null }));
    assert_eq!(set(SettingKey::PingReply, "off"), json!({ "type": "set_ping_enabled", "params": { "enabled": false } }));
    assert!(settings_request(SettingKey::Mtu, "1600").is_err());
    assert!(settings_request(SettingKey::Keepalive, "4").is_err());
    assert!(settings_request(SettingKey::LockPath, "maybe").is_err());
}

#[test]
fn dns_record_checks() {
    let body = |f: fn(&mut DnsBody)| {
        let mut b = DnsBody { ttl: Some("300".into()), ..Default::default() };
        f(&mut b);
        b
    };
    assert_eq!(dns_record_body(DnsType::Cname, &body(|b| b.content = Some(" a.b ".into()))).unwrap(), ("a.b".into(), 300));
    let srv = body(|b| {
        b.priority = Some("10".into());
        b.weight = Some("20".into());
        b.port = Some("25565".into());
        b.target = Some("pc.alice.ezplay.net.msst2031.cn".into());
    });
    assert_eq!(dns_record_body(DnsType::Srv, &srv).unwrap().0, "10 20 25565 pc.alice.ezplay.net.msst2031.cn");
    assert!(dns_record_body(DnsType::Txt, &body(|_| {})).is_err());
    assert!(dns_record_body(DnsType::Cname, &body(|b| { b.content = Some("x".into()); b.ttl = Some("59".into()); })).is_err());
    assert!(dns_record_body(DnsType::Srv, &body(|b| { b.priority = Some("1".into()); b.weight = Some("1".into()); b.port = Some("0".into()); b.target = Some("t".into()); })).is_err());
}

// ── Against a fake Core ──────────────────────────────────────────────────────

#[tokio::test]
async fn rules_add_sends_the_rule() {
    let core = rules_core();
    let (r, out, sent) = run_cli(&core, &["rules", "add", "--device", "pc.alice", "--port", "tcp/25565"], None).await;
    r.unwrap();
    assert!(out.contains("已添加规则 new-rule"), "{out}");
    let [IpcRequest::CreateAclRule { rule }] = sent.as_slice() else { panic!("{sent:?}") };
    assert_eq!(rule["principals"], json!([{ "kind": "device", "id": "pc.alice" }]));
}

#[tokio::test]
async fn all_ports_rule_needs_confirmation() {
    let core = rules_core();
    let (r, _, sent) = run_cli(&core, &["rules", "add", "--room-members", "--all-ports"], None).await;
    assert!(matches!(r, Err(CliError::Invalid(m)) if m.contains("--yes")));
    assert!(sent.is_empty());

    let (r, _, sent) = run_cli(&core, &["rules", "add", "--room-members", "--all-ports"], Some(false)).await;
    assert!(matches!(r, Err(CliError::Cancelled)));
    assert!(sent.is_empty());

    let (r, _, sent) = run_cli(&core, &["rules", "add", "--room-members", "--all-ports"], Some(true)).await;
    r.unwrap();
    assert_eq!(sent.len(), 1);
}

fn edit_args(f: impl FnOnce(&mut RuleEditArgs)) -> RuleEditArgs {
    let mut a = RuleEditArgs::default();
    f(&mut a);
    a
}

#[test]
fn edit_patch_has_only_what_was_given() {
    let (patch, risk, full) = build_patch(&edit_args(|a| a.comment = Some("  ".into())), None).unwrap();
    assert_eq!(patch, json!({ "comment": "" }), "an empty comment clears it");
    assert_eq!((risk, full), (None, false), "comment only: works with older Cores");

    let (patch, _, full) = build_patch(
        &edit_args(|a| {
            a.users = vec!["bob".into(), "bob".into()];
            a.services = vec!["ssh".into()];
            a.action = Some(RuleAction::Deny);
        }),
        None,
    )
    .unwrap();
    assert_eq!(patch, json!({ "principals": [{ "kind": "user", "id": "bob" }], "services": ["ssh"], "action": "deny" }));
    assert!(full);

    // A group source is `group_id` alone: Core rejects it with `principals`.
    let (patch, _, _) = build_patch(&edit_args(|_| {}), Some("g1".into())).unwrap();
    assert_eq!(patch, json!({ "group_id": "g1" }));

    let (patch, risk, _) = build_patch(&edit_args(|a| a.all_ports = true), None).unwrap();
    assert_eq!(patch, json!({ "protocol_ports": [{ "proto": "tcp+udp", "port": "*" }] }));
    assert_eq!(risk.as_deref(), Some("所有协议及端口"));

    let err = |a: RuleEditArgs| match build_patch(&a, None) {
        Err(CliError::Invalid(m)) => m,
        other => panic!("{other:?}"),
    };
    assert!(err(edit_args(|_| {})).contains("请指定要修改的内容"));
    assert!(err(edit_args(|a| { a.devices = vec!["d".into()]; a.room_members = true; })).contains("一种来源"));
    assert!(err(edit_args(|a| { a.services = vec!["ssh".into()]; a.ports = vec!["22".into()]; })).contains("一种目标"));
    assert!(err(edit_args(|a| a.ports = vec!["icmp/1".into()])).contains("协议无效"));
}

fn edit_core() -> FakeCore {
    FakeCore::new(|req| match req {
        IpcRequest::GetAclRules => Ok(json!({ "rules": [
            { "id": "bbbb1111-0000", "permission_group": { "kind": "devices", "ids": ["d1"] },
              "protocol_ports": [{ "proto": "tcp", "start": 25565, "end": 25565 }], "action": "deny", "enabled": true },
            { "id": "bbbb2222-0000", "permission_group": { "kind": "devices", "ids": ["d1"] },
              "protocol_ports": [{ "proto": "tcp", "start": 22, "end": 22 }], "action": "allow", "enabled": true,
              "expires_at": 4_000_000_000_i64 },
        ] })),
        IpcRequest::GetAclGroups => Ok(json!({ "groups": [{ "id": "gggg1111", "name": "friends", "kind": "user", "member_ids": [] }] })),
        IpcRequest::UpdateAclRule { rule_id, .. } => Ok(json!({ "rule": { "id": rule_id } })),
        other => default_responses(other),
    })
}

#[tokio::test]
async fn rules_edit_updates_a_permanent_rule() {
    let core = edit_core();
    let (r, out, sent) = run_cli(&core, &["rules", "edit", "bbbb1", "--group", "gggg", "--service", "ssh", "--action", "allow"], None).await;
    r.unwrap();
    assert!(out.contains("规则 bbbb1111 已更新"), "{out}");
    let [IpcRequest::UpdateAclRule { rule_id, patch }] = sent.as_slice() else { panic!("{sent:?}") };
    assert_eq!(rule_id, "bbbb1111-0000");
    assert_eq!(patch, &json!({ "group_id": "gggg1111", "services": ["ssh"], "action": "allow" }));

    // All ports: confirmed with the action the rule will have (deny: kept).
    let (r, _, sent) = run_cli(&core, &["rules", "edit", "bbbb1", "--all-ports"], None).await;
    assert!(matches!(r, Err(CliError::Invalid(m)) if m.contains("--yes")));
    assert!(sent.is_empty());
}

#[tokio::test]
async fn temporary_rules_cannot_be_edited() {
    let core = edit_core();
    let (r, _, sent) = run_cli(&core, &["rules", "edit", "bbbb2", "--comment", "x"], None).await;
    assert!(matches!(r, Err(CliError::Invalid(m)) if m.contains("临时规则")));
    assert!(sent.is_empty());
    let (r, out, _) = run_cli(&core, &["rules", "list"], None).await;
    r.unwrap();
    assert!(out.contains("临时，"), "{out}");
}

#[tokio::test]
async fn rules_delete_resolves_prefix_and_confirms() {
    let core = rules_core();
    let (r, _, sent) = run_cli(&core, &["rules", "delete", "aaaa"], Some(true)).await;
    assert!(matches!(r, Err(CliError::Invalid(m)) if m.contains("多个")));
    assert!(sent.is_empty());

    let (r, out, sent) = run_cli(&core, &["-y", "rules", "delete", "aaaa2"], None).await;
    r.unwrap();
    assert!(out.contains("已删除规则 aaaa2222"), "{out}");
    assert!(matches!(sent.as_slice(), [IpcRequest::DeleteAclRule { rule_id }] if rule_id == "aaaa2222-0000"));
}

#[tokio::test]
async fn rules_list_describes_rules() {
    let core = rules_core();
    let (r, out, _) = run_cli(&core, &["rules", "list", "-v"], None).await;
    r.unwrap();
    assert!(out.contains("aaaa1111  允许  1 台设备可以访问 1 组端口"), "{out}");
    assert!(out.contains("端口：tcp/25565"), "{out}");
    assert!(out.contains("房间模式：当前所有房间成员"), "{out}");
}

#[tokio::test]
async fn core_rejection_is_reported() {
    let core = FakeCore::new(|req| match req {
        IpcRequest::JoinRoom { .. } => Err(IpcError { code: "room_password_required".into(), message: "password".into() }),
        IpcRequest::SetNetSettings { .. } => Err(IpcError { code: "persist_failed".into(), message: "disk full".into() }),
        other => default_responses(other),
    });
    // No terminal to ask on: say how to pass it.
    let (r, _, _) = run_cli(&core, &["room", "join", "space-9"], None).await;
    assert!(matches!(&r, Err(CliError::Invalid(m)) if m.contains("--password")), "{r:?}");

    let (r, _, _) = run_cli(&core, &["settings", "set", "lock-path", "on"], None).await;
    let e = r.unwrap_err();
    assert_eq!(e.exit_code(), 1);
    let ui = e.to_ui();
    assert_eq!(ui.code, "persist_failed");
    assert_eq!(ui.detail.as_deref(), Some("disk full"));
}

#[tokio::test]
async fn interactive_authorization_is_not_offered() {
    let core = rules_core();
    let raw = r#"{"type":"auth_decision","params":{"request_id":"r","decision":"allow_once"}}"#;
    let (r, _, sent) = run_cli(&core, &["raw", raw], None).await;
    assert!(matches!(r, Err(CliError::Invalid(m)) if m.contains("交互式授权")));
    assert!(sent.is_empty());

    let (r, out, _) = run_cli(&core, &["raw", r#"{"type":"get_paths"}"#], None).await;
    r.unwrap();
    assert!(out.contains("\"ok\": true"), "{out}");
}

#[tokio::test]
async fn access_log_quick_actions() {
    let core = FakeCore::new(|req| match req {
        IpcRequest::GetAccessLog { .. } => Ok(json!({ "entries": [
            { "id": "e-denied", "src_device_id": "d1", "proto": "tcp", "dst_port": 25565, "decision": "deny",
              "reason": { "type": "default_deny" }, "timestamp_ms": 0 },
            { "id": "e-allowed", "src_device_id": "d1", "proto": "tcp", "dst_port": 80, "decision": "allow",
              "reason": { "type": "matched_rule", "rule_id": "r-perm" }, "rule_id": "r-perm", "timestamp_ms": 0 },
        ] })),
        IpcRequest::GetAclRules => Ok(json!({ "rules": [
            { "id": "r-perm", "permission_group": { "kind": "devices", "ids": ["d1"] }, "protocol_ports": [], "action": "allow", "expires_at": null },
        ] })),
        other => default_responses(other),
    });
    let (r, out, _) = run_cli(&core, &["access", "list"], None).await;
    r.unwrap();
    assert!(out.contains("d1 访问 tcp/25565：拒绝（无匹配规则（默认拒绝））"), "{out}");

    let (r, _, sent) = run_cli(&core, &["access", "allow-once", "e-d"], None).await;
    r.unwrap();
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs() as i64;
    let rule = sent.iter().find_map(|r| match r {
        IpcRequest::CreateAclRule { rule } => Some(rule.clone()),
        _ => None,
    });
    let rule = rule.expect("rule created");
    assert!((rule["expires_at"].as_i64().unwrap() - (now + ONCE_TTL_SECS)).abs() < 5);
    assert_eq!(rule["protocol_ports"], json!([{ "proto": "tcp", "port": "25565" }]));

    // Allowed by a permanent rule: no second allow rule, revoking deletes it.
    let (r, _, _) = run_cli(&core, &["access", "allow", "e-a"], None).await;
    assert!(matches!(r, Err(CliError::Invalid(m)) if m.contains("已有永久允许规则")));
    let (r, _, sent) = run_cli(&core, &["-y", "access", "revoke", "e-a"], None).await;
    r.unwrap();
    assert!(sent.iter().any(|r| matches!(r, IpcRequest::DeleteAclRule { rule_id } if rule_id == "r-perm")));
}

#[tokio::test]
async fn status_without_core() {
    let core = FakeCore::new(default_responses);
    core.set_running(false);
    let (r, out, _) = run_cli(&core, &["--timeout", "1", "status"], None).await;
    let e = r.unwrap_err();
    assert_eq!(e.exit_code(), 3);
    assert!(out.contains("客户端核心可能没有启动"), "{out}");
}

#[tokio::test]
async fn status_shows_core_state() {
    let core = FakeCore::new(|req| match req {
        IpcRequest::Health => Ok(json!({ "status": "ok", "ws_connected": false, "device_display_name": "Alice PC" })),
        IpcRequest::GetSystemState => Ok(json!({ "room_id": "space-1", "room_name": "MC", "peers": [{ "device_id": "d1" }] })),
        other => default_responses(other),
    });
    let (r, out, _) = run_cli(&core, &["status"], None).await;
    r.unwrap();
    assert!(out.contains("客户端核心无法连接协调服务器"), "{out}");
    assert!(out.contains("本机设备：Alice PC"), "{out}");
    assert!(out.contains("房间：MC（房间号 space-1）  1 位成员"), "{out}");

    // Room commands need the coordinator: refused before sending.
    let (r, _, sent) = run_cli(&core, &["room", "create", "x"], None).await;
    assert!(matches!(r, Err(CliError::Invalid(m)) if m.contains("协调服务器")));
    assert!(sent.is_empty());
}

#[tokio::test]
async fn room_passwords_are_asked_for() {
    let core = FakeCore::new(|req| match req {
        IpcRequest::JoinRoom { password: None, .. } => Err(IpcError { code: "room_password_required".into(), message: "pw".into() }),
        IpcRequest::JoinRoom { room_id, .. } => Ok(json!({ "room_id": room_id, "room_name": "MC" })),
        IpcRequest::CreateRoom { relay_password: None, .. } => {
            Err(IpcError { code: "relay_password_required".into(), message: "pw".into() })
        }
        IpcRequest::CreateRoom { .. } => Ok(json!({ "room_id": "space-2" })),
        other => default_responses(other),
    });
    // Core asks for the room password: asked once, then sent.
    let (r, out, sent) = run_cli_with(&core, &["room", "join", "space-1"], None, Some(" secret ")).await;
    r.unwrap();
    assert!(out.contains("已加入房间 MC"), "{out}");
    let joins: Vec<_> = sent.iter().filter_map(|r| match r { IpcRequest::JoinRoom { password, .. } => Some(password.clone()), _ => None }).collect();
    assert_eq!(joins, vec![None, Some("secret".to_string())]);

    // A bare --password asks before sending.
    let (r, _, sent) = run_cli_with(&core, &["room", "join", "space-1", "--password"], None, Some("pw2")).await;
    r.unwrap();
    assert!(matches!(sent.as_slice(), [IpcRequest::JoinRoom { password: Some(p), .. }] if p == "pw2"), "{sent:?}");

    // An empty answer is not sent.
    let (r, _, sent) = run_cli_with(&core, &["room", "join", "space-1", "--password"], None, Some("  ")).await;
    assert!(matches!(r, Err(CliError::Invalid(_))));
    assert!(sent.is_empty());

    // Creating with a relay that needs a password.
    let (r, out, sent) =
        run_cli_with(&core, &["room", "create", "x", "--relay", "msst-relay://h:1#k"], None, Some("relaypw")).await;
    r.unwrap();
    assert!(out.contains("space-2"), "{out}");
    assert!(matches!(sent.last(), Some(IpcRequest::CreateRoom { relay_password: Some(p), .. }) if p == "relaypw"));
}
