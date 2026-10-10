<script lang="ts">
  import Icon from '$lib/icons';
  import { ipc, clearAclRules, errorMessage } from '$lib/api/client';
  import { R } from '$lib/api/requests';
  import { controller } from '$lib/controller.svelte';
  import { formatPorts, formatPrincipal } from '$lib/util';
  import GroupManagerDialog from '$lib/dialogs/GroupManagerDialog.svelte';
  import RuleWizard from '$lib/dialogs/RuleWizard.svelte';
  import RuleEditDialog from '$lib/dialogs/RuleEditDialog.svelte';
  import type { AccessDecision, AccessLogEntry, AccessReason, FirewallRule } from '$lib/types';

  // Same TTL Core uses for "allow once" decisions (inbound.rs ONCE_RULE_TTL_SECS).
  const ONCE_TTL_SECS = 600;

  const rules = $derived((controller.state?.acl_rules ?? []) as FirewallRule[]);
  const health = $derived(controller.health);
  const nodes = $derived(controller.state?.nodes ?? {});

  let sub = $state<'rules' | 'access-log'>('rules');
  let showGroups = $state(false);
  let wizard = $state<{ from: FirewallRule | null } | null>(null);
  let editing = $state<FirewallRule | null>(null);
  let error = $state('');

  // ── Ping ──
  let pingBusy = $state(false);
  async function setPing(enabled: boolean) {
    pingBusy = true;
    error = '';
    try {
      await ipc(R.setPingEnabled(enabled));
    } catch (e) {
      error = `Ping 设置失败：${errorMessage(e)}`;
    } finally {
      pingBusy = false;
    }
  }

  // ── Rules ──
  let busyRules = $state<Set<string>>(new Set());
  async function withRule(id: string, f: () => Promise<unknown>, what: string) {
    busyRules = new Set([...busyRules, id]);
    error = '';
    try {
      await f();
    } catch (e) {
      error = `${what}失败：${errorMessage(e)}`;
    } finally {
      const s = new Set(busyRules);
      s.delete(id);
      busyRules = s;
    }
  }

  function toggleRule(r: FirewallRule) {
    withRule(r.id, () => ipc(R.updateAclRule(r.id, { enabled: !r.enabled })), r.enabled ? '停用规则' : '启用规则');
  }

  function deleteRule(r: FirewallRule) {
    if (!confirm('确定删除这条规则？')) return;
    withRule(r.id, () => ipc(R.deleteAclRule(r.id)), '删除规则');
  }

  let clearing = $state(false);
  async function clearAll() {
    if (!confirm(`确定清空全部 ${rules.length} 条规则？此操作不可恢复。`)) return;
    clearing = true;
    error = '';
    try {
      const res = await clearAclRules();
      if (res.failed.length > 0) {
        error = `已删除 ${res.deleted} 条，${res.failed.length} 条删除失败：${res.failed.map((f) => f.message).join('；')}`;
      }
    } catch (e) {
      error = `清空失败：${errorMessage(e)}`;
    } finally {
      clearing = false;
    }
  }

  // ── Access log (queried on demand; not part of the view) ──
  let log = $state<AccessLogEntry[]>([]);
  let logLoading = $state(false);
  let logError = $state('');
  let logSeq = 0;

  async function loadLog() {
    const seq = ++logSeq;
    logLoading = true;
    logError = '';
    try {
      const d = await ipc<{ entries: AccessLogEntry[] }>(R.getAccessLog(200));
      if (seq === logSeq) log = d.entries ?? [];
    } catch (e) {
      if (seq === logSeq) logError = errorMessage(e);
    } finally {
      if (seq === logSeq) logLoading = false;
    }
  }

  const decisionLabel = (d: AccessDecision) => (d === 'allow' ? '允许' : d === 'deny' ? '拒绝' : '静默拒绝');
  function reasonLabel(r: AccessReason): string {
    switch (r.type) {
      case 'matched_rule':
        return r.comment ? `规则备注：${r.comment}` : `规则 ${r.rule_id.slice(0, 8)}`;
      case 'interactive_auth':
        return '交互式授权';
      case 'interactive_auth_timeout':
        return '交互式授权超时';
      case 'default_deny':
        return '无匹配规则（默认拒绝）';
      default:
        return '未知原因';
    }
  }
  const deviceName = (id: string) => nodes[id]?.display_name ?? nodes[id]?.hostname ?? id;

  function hasPermanentRule(e: AccessLogEntry, action: 'allow' | 'deny'): boolean {
    const r = e.rule_id ? rules.find((r) => r.id === e.rule_id) : undefined;
    return !!r && r.action === action && r.expires_at === null;
  }

  async function logAction(e: AccessLogEntry, f: () => Promise<unknown>, what: string) {
    logError = '';
    try {
      await f();
      await loadLog();
    } catch (err) {
      logError = `${what}失败：${errorMessage(err)}`;
    }
  }

  function ruleFor(e: AccessLogEntry, action: 'allow' | 'deny', expiresAt: number | null) {
    return ipc(
      R.createAclRule({
        action,
        direction: 'inbound',
        enabled: true,
        expires_at: expiresAt,
        principals: [{ kind: 'device', id: e.src_device_id }],
        protocol_ports: [{ proto: e.proto, port: String(e.dst_port) }],
        comment: `访问记录操作：${action === 'allow' ? '允许' : '拒绝'} ${deviceName(e.src_device_id)} ${e.proto}/${e.dst_port}`,
      }),
    );
  }
</script>

<div class="defense-page">
  <div class="defense-toolbar">
    <label class="ping-toggle" title={health?.ping_enabled == null ? '状态未知' : ''}>
      <span>Ping (ICMP){health?.ping_enabled == null ? '（未知）' : ''}</span>
      <input type="checkbox" class="toggle-input" checked={health?.ping_enabled ?? false}
        disabled={pingBusy || health?.ping_enabled == null}
        onchange={(e) => setPing((e.target as HTMLInputElement).checked)} />
      <span class="toggle-knob"></span>
    </label>

    <button class="tool-btn" onclick={() => (showGroups = true)}>👥 权限组管理</button>
    <button class="tool-btn" class:active={sub === 'access-log'}
      onclick={() => { sub = sub === 'access-log' ? 'rules' : 'access-log'; if (sub === 'access-log') loadLog(); }}>
      📋 访问记录
    </button>

    <div class="toolbar-spacer"></div>

    {#if sub === 'rules'}
      <button class="tool-btn primary" onclick={() => (wizard = { from: null })} disabled={!controller.connected}>＋ 新建规则</button>
      <button class="tool-btn danger" onclick={clearAll} disabled={rules.length === 0 || clearing || !controller.connected}>
        <Icon icon="mdi:trash-can-outline" width={14} height={14} /> {clearing ? '清空中…' : '清空'}
      </button>
    {:else}
      <span class="access-log-count">{log.length} 条记录</span>
      <button class="tool-btn" onclick={loadLog} disabled={logLoading}>
        {#if logLoading}加载中…{:else}<Icon icon="mdi:refresh" width={14} height={14} /> 刷新{/if}
      </button>
    {/if}
  </div>

  {#if error}<p class="inline-error" style="margin:0 var(--space-md)">{error}</p>{/if}

  {#if sub === 'rules'}
    <div class="rules-container">
      {#if controller.state?.sync.state !== 'synced' && rules.length === 0}
        <div class="rules-empty">{controller.connected ? '正在读取规则…' : '未连接客户端核心，无法读取规则'}</div>
      {:else if rules.length === 0}
        <div class="rules-empty">暂无防火墙规则（未匹配的入站访问默认拒绝）</div>
      {:else}
        <table class="rules-table">
          <thead>
            <tr><th>启用</th><th>来源</th><th>协议/端口</th><th>动作</th><th>备注</th><th>操作</th></tr>
          </thead>
          <tbody>
            {#each rules as rule (rule.id)}
              <tr class:disabled={!rule.enabled}>
                <td><input type="checkbox" checked={rule.enabled} disabled={busyRules.has(rule.id) || !controller.connected}
                  onchange={(e) => { (e.target as HTMLInputElement).checked = rule.enabled; toggleRule(rule); }} /></td>
                <td class="td-principal">
                  {formatPrincipal(rule.permission_group, rule.principal_mnemonics)}
                  {#if rule.pending_migration}<div class="inline-note">含尚未解析的助记符</div>{/if}
                </td>
                <td class="td-ports">{formatPorts(rule.protocol_ports)}</td>
                <td>
                  <span class="action-badge" class:allow={rule.action === 'allow'} class:deny={rule.action === 'deny'}>
                    {rule.action === 'allow' ? '允许' : '拒绝'}
                  </span>
                  {#if rule.expires_at}<div class="inline-note">至 {new Date(rule.expires_at * 1000).toLocaleTimeString()}</div>{/if}
                </td>
                <td class="td-comment">{rule.comment ?? '—'}</td>
                <td class="td-actions">
                  <button class="icon-btn" onclick={() => (editing = rule)} title="编辑"><Icon icon="mdi:pencil" width={13} height={13} /></button>
                  <button class="icon-btn danger" onclick={() => deleteRule(rule)} title="删除" disabled={busyRules.has(rule.id) || !controller.connected}>
                    <Icon icon="mdi:trash-can-outline" width={13} height={13} />
                  </button>
                </td>
              </tr>
            {/each}
          </tbody>
        </table>
      {/if}
    </div>
  {:else}
    <div class="access-log-container">
      {#if logError}<div class="rules-empty" style="color:var(--error)">{logError}</div>{/if}
      {#if log.length === 0 && !logError}
        <div class="rules-empty">{logLoading ? '加载中…' : '暂无访问记录'}</div>
      {/if}
      {#each log as entry (entry.id)}
        <div class="al-card" class:al-allow={entry.decision === 'allow'} class:al-deny={entry.decision !== 'allow'}>
          <div class="al-header">
            <span class="al-device">{deviceName(entry.src_device_id)}</span>
            <span class="al-time">{new Date(entry.timestamp_ms).toLocaleString()}{entry.count > 1 ? ` ×${entry.count}` : ''}</span>
          </div>
          <div class="al-body">
            在 <strong>{new Date(entry.timestamp_ms).toLocaleTimeString()}</strong> 时尝试访问
            <span class="al-service">{entry.service_name ?? `${entry.proto}/${entry.dst_port}`}</span>
          </div>
          <div class="al-verdict">
            已因 <span class="al-reason">{reasonLabel(entry.reason)}</span> 被
            <span class="al-decision-badge" class:al-badge-allow={entry.decision === 'allow'} class:al-badge-deny={entry.decision !== 'allow'}>
              {decisionLabel(entry.decision)}
            </span>
          </div>
          <div class="al-actions">
            {#if entry.decision === 'allow'}
              {#if hasPermanentRule(entry, 'allow')}
                <button class="al-btn al-btn-warn" onclick={() => logAction(entry, () => ipc(R.deleteAclRule(entry.rule_id!)), '撤销授权')}>撤销授权</button>
              {:else}
                <button class="al-btn al-btn-allow" onclick={() => logAction(entry, () => ruleFor(entry, 'allow', null), '添加规则')}>始终允许</button>
              {/if}
            {:else}
              <button class="al-btn al-btn-allow"
                onclick={() => logAction(entry, () => ruleFor(entry, 'allow', Math.floor(Date.now() / 1000) + ONCE_TTL_SECS), '添加规则')}>允许一次</button>
              <button class="al-btn al-btn-allow" onclick={() => logAction(entry, () => ruleFor(entry, 'allow', null), '添加规则')}>始终允许</button>
              {#if !hasPermanentRule(entry, 'deny')}
                <button class="al-btn al-btn-deny" onclick={() => logAction(entry, () => ruleFor(entry, 'deny', null), '添加规则')}>始终拒绝</button>
              {/if}
            {/if}
          </div>
        </div>
      {/each}
    </div>
  {/if}
</div>

{#if showGroups}<GroupManagerDialog onclose={() => (showGroups = false)} />{/if}
{#if wizard}<RuleWizard from={wizard.from} onclose={() => (wizard = null)} />{/if}
{#if editing}
  <RuleEditDialog rule={editing} onclose={() => (editing = null)}
    oncopy={(r) => { editing = null; wizard = { from: r }; }} />
{/if}
