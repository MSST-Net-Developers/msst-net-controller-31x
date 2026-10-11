<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { ipc, errorMessage, isTauri, webui } from '$lib/api/client';
  import { R } from '$lib/api/requests';
  import { controller } from '$lib/controller.svelte';
  import { formatBytes, validHandle } from '$lib/util';
  import LogDialog from '$lib/dialogs/LogDialog.svelte';
  import type { EndpointInfo, NetSettings, PathViewEntry, RelayList, TrafficEntry } from '$lib/types';
  import Icon from '$lib/icons';

  const health = $derived(controller.health);
  const ready = $derived(controller.coreReady);

  // ── Identity ──
  let deviceName = $state('');
  let deviceHandle = $state('');
  let identityLoadedFor = '';
  $effect(() => {
    // Fill the form from Core once per identity (don't overwrite edits).
    const h = health;
    if (h?.device_id && h.device_id !== identityLoadedFor) {
      identityLoadedFor = h.device_id;
      deviceName = h.device_display_name ?? '';
      deviceHandle = h.device_handle ?? '';
    }
  });
  let saveState = $state<'idle' | 'saving' | 'saved' | 'error'>('idle');
  let saveError = $state('');
  let saveTimer: ReturnType<typeof setTimeout>;

  async function saveIdentity() {
    saveError = '';
    const name = deviceName.trim();
    const handle = deviceHandle.trim();
    if (!name) return void ((saveState = 'error'), (saveError = '设备名不能为空'));
    if (handle && !validHandle(handle)) {
      saveState = 'error';
      saveError = '助记符只能包含小写字母、数字和横杠';
      return;
    }
    saveState = 'saving';
    try {
      if (name !== (health?.device_display_name ?? '')) await ipc(R.updateDisplayName(null, name));
      if (handle && handle !== (health?.device_handle ?? '')) await ipc(R.updateHandle(null, handle));
      saveState = 'saved';
      clearTimeout(saveTimer);
      saveTimer = setTimeout(() => (saveState = 'idle'), 2000);
    } catch (e) {
      saveState = 'error';
      saveError = errorMessage(e);
    }
  }

  // ── Diagnostics actions ──
  let showLogs = $state(false);
  let reRegisterState = $state<'idle' | 'busy' | 'done' | 'error'>('idle');
  let reRegisterError = $state('');
  async function reRegister() {
    reRegisterState = 'busy';
    reRegisterError = '';
    try {
      await ipc(R.reRegister());
      reRegisterState = 'done';
      setTimeout(() => (reRegisterState = 'idle'), 2000);
    } catch (e) {
      reRegisterState = 'error';
      reRegisterError = errorMessage(e);
    }
  }

  // ── Advanced network settings ──
  let advanced = $state(false);
  let openSection = $state<Record<string, boolean>>({});
  let settings = $state<NetSettings | null>(null);
  let settingsError = $state('');
  let settingsBusy = $state(false);
  let mtuMode = $state<'auto' | 'custom'>('auto');
  let mtuInput = $state('');
  let kaMode = $state<'auto' | 'custom'>('auto');
  let kaInput = $state('');

  function applySettingsView(s: NetSettings) {
    settings = s;
    mtuMode = s.mtu_override != null ? 'custom' : 'auto';
    mtuInput = s.mtu_override != null ? String(s.mtu_override) : '';
    kaMode = s.keepalive_secs != null ? 'custom' : 'auto';
    kaInput = s.keepalive_secs != null ? String(s.keepalive_secs) : '';
  }

  async function loadSettings() {
    settingsError = '';
    try {
      applySettingsView(await ipc<NetSettings>(R.getNetSettings()));
    } catch (e) {
      settingsError = `读取网络设置失败：${errorMessage(e)}`;
    }
  }

  /** Send one changed field; show what Core stored. */
  async function patch(p: Partial<NetSettings>) {
    settingsBusy = true;
    settingsError = '';
    try {
      applySettingsView(await ipc<NetSettings>(R.setNetSettings(p)));
    } catch (e) {
      settingsError = `保存失败：${errorMessage(e)}`;
      await loadSettings();
    } finally {
      settingsBusy = false;
    }
  }

  function applyMtu() {
    const v = Number(mtuInput);
    // Range defined by Core (protocol response P7); Core rejects others.
    if (!Number.isInteger(v) || v < 1280 || v > 1500) return void (settingsError = 'MTU 应为 1280–1500 之间的整数');
    patch({ mtu_override: v });
  }
  function applyKeepalive() {
    const v = Number(kaInput);
    if (!Number.isInteger(v) || v < 5 || v > 30) return void (settingsError = '保活间隔应为 5–30 秒之间的整数');
    patch({ keepalive_secs: v });
  }

  let pathView = $state<PathViewEntry[]>([]);
  let relays = $state<RelayList | null>(null);
  let relaysError = $state('');
  let traffic = $state<TrafficEntry[]>([]);
  let endpoints = $state<EndpointInfo | null>(null);
  let sectionError = $state<Record<string, string>>({});
  let pathTimer: ReturnType<typeof setInterval> | undefined;

  async function load<T>(key: string, req: Parameters<typeof ipc>[0], set: (v: T) => void) {
    try {
      set(await ipc<T>(req));
      sectionError = { ...sectionError, [key]: '' };
    } catch (e) {
      sectionError = { ...sectionError, [key]: errorMessage(e) };
    }
  }
  const loadPath = () => load<{ peers: PathViewEntry[] }>('path', R.getPathView(), (d) => (pathView = d.peers ?? []));
  const loadTraffic = () => load<{ peers: TrafficEntry[] }>('traffic', R.getTrafficStats(), (d) => (traffic = d.peers ?? []));
  const loadEndpoints = () => load<EndpointInfo>('endpoints', R.getEndpoints(), (d) => (endpoints = d));
  async function loadRelays() {
    relaysError = '';
    try {
      relays = await ipc<RelayList>(R.getRelays());
    } catch (e) {
      relaysError = errorMessage(e);
    }
  }

  // Settings changed elsewhere (another Controller, SettingsChanged): keep
  // the open panel current without touching text being edited.
  onMount(() =>
    controller.listen((n) => {
      if (n.type !== 'changed' || !advanced || settingsBusy) return;
      ipc<NetSettings>(R.getNetSettings())
        .then((s) => {
          if (!settingsBusy) settings = s;
        })
        .catch(() => {});
    }),
  );

  function toggleAdvanced() {
    advanced = !advanced;
    if (advanced) loadSettings();
  }

  function toggleSection(key: string) {
    const open = !openSection[key];
    openSection = { ...openSection, [key]: open };
    if (key === 'path') {
      clearInterval(pathTimer);
      if (open) {
        loadPath();
        loadRelays();
        pathTimer = setInterval(loadPath, 2000);
      }
    }
    if (open && key === 'trans') loadTraffic();
    if (open && key === 'ep') loadEndpoints();
  }

  let refreshEpState = $state('');
  async function refreshEndpoint() {
    refreshEpState = '已请求重新探测…';
    try {
      await ipc(R.refreshEndpoint());
      // Detection runs in Core asynchronously; re-read shortly after.
      setTimeout(loadEndpoints, 3000);
    } catch (e) {
      refreshEpState = `请求失败：${errorMessage(e)}`;
    }
  }

  // ── Diagnostic ping ──
  let pingTarget = $state('');
  let pingResults = $state<(number | null)[]>([]);
  let pingRunning = $state(false);
  let pingDone = $state(false);
  let pingError = $state('');
  let pingTimer: ReturnType<typeof setTimeout>;
  let pingJob = '';
  const peers = $derived(Object.values(controller.state?.nodes ?? {}));

  async function startPing() {
    if (!pingTarget.trim() || pingRunning) return;
    pingRunning = true;
    pingDone = false;
    pingResults = [];
    pingError = '';
    try {
      pingJob = (await ipc<{ job_id: string }>(R.startDiagPing(pingTarget.trim()))).job_id;
      pingTimer = setTimeout(pollPing, 500);
    } catch (e) {
      pingRunning = false;
      pingError = errorMessage(e);
    }
  }
  async function pollPing() {
    const job = pingJob;
    try {
      const r = await ipc<{ results: (number | null)[]; done: boolean }>(R.getDiagPingResult(job));
      if (job !== pingJob) return;
      pingResults = r.results ?? [];
      pingDone = r.done;
      pingRunning = !r.done;
      if (!r.done) pingTimer = setTimeout(pollPing, 500);
    } catch (e) {
      pingRunning = false;
      pingError = errorMessage(e);
    }
  }

  // ── WebUI remote access (browser only) ──
  let cidrs = $state<string[]>([]);
  let webPort = $state<number | null>(null);
  let cidrInput = $state('');
  let cidrState = $state<'idle' | 'saving' | 'saved' | 'error'>('idle');
  let cidrError = $state('');
  if (!isTauri) {
    webui.getConfig().then((c) => { cidrs = c.allowed_cidrs; webPort = c.port; }).catch((e) => (cidrError = errorMessage(e)));
  }
  async function saveCidrs() {
    cidrState = 'saving';
    cidrError = '';
    try {
      await webui.setConfig(cidrs);
      cidrState = 'saved';
      setTimeout(() => (cidrState = 'idle'), 2000);
    } catch (e) {
      cidrState = 'error';
      cidrError = errorMessage(e);
    }
  }

  // ── Controller ↔ Core diagnostics ──
  const conn = $derived(controller.view);
  function connLabel(): string {
    const c = conn?.connection;
    if (!c) return '未知';
    if (typeof c === 'object') return `重连中（第 ${c.Reconnecting.attempt} 次）`;
    return { Connected: '已连接', Connecting: '连接中', Disconnected: '未连接', Incompatible: '版本不兼容' }[c];
  }

  onDestroy(() => {
    clearInterval(pathTimer);
    clearTimeout(pingTimer);
    clearTimeout(saveTimer);
  });
</script>

<div class="settings-page">
  <div class="settings-inner">
    <div class="settings-card">
      <div class="settings-header">
        <h2 class="settings-section-title">设备标识符</h2>
        <p class="settings-hint">显示名将展示给房间内的其他成员；助记符只能包含小写字母、数字和横杠，用于在规则中引用</p>
      </div>
      <div class="divider-gradient"></div>
      {#if !ready}<p class="settings-hint is-warning"><Icon icon="mdi:alert-outline" width={14} height={14} />客户端核心未就绪，暂不可编辑</p>{/if}
      <div class="settings-fields">
        <label class="field"><span>设备名</span>
          <input type="text" bind:value={deviceName} placeholder="请输入设备名" disabled={!ready} /></label>
        <label class="field"><span>设备助记符</span>
          <input type="text" bind:value={deviceHandle} placeholder="如：alice-laptop" disabled={!ready} /></label>
      </div>
      {#if health}
        <p class="inline-note selectable">设备 ID：{health.device_id ?? '—'}　用户 ID：{health.user_id ?? '—'}</p>
      {/if}
      {#if saveError}<p class="inline-error">{saveError}</p>{/if}
      <div class="settings-actions">
        <button class="save-btn" class:saving={saveState === 'saving'} class:saved={saveState === 'saved'} class:save-error={saveState === 'error'}
          onclick={saveIdentity} disabled={saveState === 'saving' || !ready}>
          {saveState === 'saving' ? '保存中…' : saveState === 'saved' ? '✓ 已保存' : saveState === 'error' ? '保存失败，请重试' : '保存'}
        </button>
      </div>
    </div>

    {#if !isTauri}
      <div class="settings-card">
        <div class="settings-header">
          <h2 class="settings-section-title">远程管理</h2>
          <p class="settings-hint">允许访问 WebUI 的 IP 地址段（CIDR 格式）。监听端口：{webPort ?? '—'}</p>
        </div>
        <div class="divider-gradient"></div>
        <div class="cidr-list">
          {#each cidrs as c (c)}
            <div class="cidr-item"><span class="cidr-text">{c}</span>
              <button class="cidr-del" onclick={() => (cidrs = cidrs.filter((x) => x !== c))}>×</button></div>
          {/each}
        </div>
        <div class="cidr-add-row">
          <input class="cidr-input" type="text" bind:value={cidrInput} placeholder="如 192.168.1.0/24"
            onkeydown={(e) => { if (e.key === 'Enter' && cidrInput.trim()) { cidrs = [...new Set([...cidrs, cidrInput.trim()])]; cidrInput = ''; } }} />
          <button class="tool-btn" onclick={() => { if (cidrInput.trim()) { cidrs = [...new Set([...cidrs, cidrInput.trim()])]; cidrInput = ''; } }}>添加</button>
        </div>
        <p class="inline-note">移除当前所用的地址段后，本浏览器将无法再访问 WebUI。</p>
        {#if cidrError}<p class="inline-error">{cidrError}</p>{/if}
        <div class="settings-actions">
          <button class="save-btn" class:saving={cidrState === 'saving'} class:saved={cidrState === 'saved'} class:save-error={cidrState === 'error'}
            onclick={saveCidrs} disabled={cidrState === 'saving' || cidrs.length === 0}>
            {cidrState === 'saving' ? '保存中…' : cidrState === 'saved' ? '✓ 已保存' : cidrState === 'error' ? '保存失败' : '保存'}
          </button>
        </div>
      </div>
    {/if}

    <div class="settings-card">
      <div class="settings-header">
        <h2 class="settings-section-title">诊断与调试</h2>
        <p class="settings-hint">查看日志、重新注册及高级网络控制</p>
      </div>
      <div class="divider-gradient"></div>

      <div class="adv-mono-block">
        <div>控制器 ↔ 客户端核心：<strong>{connLabel()}</strong></div>
        <div>协议版本：控制器 {conn?.controller_protocol ?? '—'} / 客户端核心 {conn?.core_protocol ?? '—'}</div>
        <div>协调服务器：{health?.ws_connected == null ? '未知' : health.ws_connected ? '已连接' : '未连接'}　虚拟网卡：{health?.tun_ready == null ? '未知' : health.tun_ready ? '就绪' : '未就绪'}　中转：{health?.relay ?? '—'}</div>
        <div>累计断线 {conn?.diagnostics.disconnections ?? 0} 次</div>
        {#if conn?.diagnostics.last_error}<div>最近错误：{conn.diagnostics.last_error}</div>{/if}
      </div>

      <div class="settings-actions">
        <button class="save-btn outline-btn" onclick={reRegister} disabled={!ready || reRegisterState === 'busy'}>
          {reRegisterState === 'busy' ? '注册中…' : reRegisterState === 'done' ? '✓ 已重新注册' : '重新注册'}
        </button>
        <button class="save-btn" onclick={() => (showLogs = true)} disabled={!controller.connected}>查看日志</button>
      </div>
      {#if reRegisterError}<p class="inline-error">重新注册失败：{reRegisterError}</p>{/if}

      <div class="divider-gradient"></div>
      <div class="adv-toggle" role="button" tabindex="0" onclick={toggleAdvanced}
        onkeydown={(e) => (e.key === 'Enter' || e.key === ' ') && toggleAdvanced()}>
        <span class="adv-toggle-label">高级选项</span>
        <span class="adv-toggle-arrow"><Icon icon={advanced ? 'mdi:chevron-up' : 'mdi:chevron-down'} width={18} height={18} /></span>
      </div>

      {#if advanced}
        <div class="adv-sections">
          {#if settingsError}<p class="inline-error">{settingsError}</p>{/if}

          <div class="adv-section">
            <div class="adv-section-hdr" role="button" tabindex="0" onclick={() => toggleSection('path')}
              onkeydown={(e) => (e.key === 'Enter' || e.key === ' ') && toggleSection('path')}><span>路径选择</span><span class="adv-section-arrow"><Icon icon={openSection.path ? 'mdi:chevron-up' : 'mdi:chevron-down'} width={18} height={18} /></span></div>
            {#if openSection.path}
              <div class="adv-section-body">
                {#if settings}
                  <div class="adv-row">
                    <div class="adv-row-label"><span>禁用 P2P 直连</span><small>始终通过中转服务器转发流量</small></div>
                    <label class="ping-toggle">
                      <input type="checkbox" class="toggle-input" checked={settings.disable_p2p} disabled={settingsBusy}
                        onchange={(e) => patch({ disable_p2p: (e.target as HTMLInputElement).checked })} />
                      <span class="toggle-knob"></span>
                    </label>
                  </div>
                  <div class="adv-row">
                    <div class="adv-row-label"><span>锁定当前路径</span><small>禁止任何路径切换（中转↔直连均冻结）</small></div>
                    <label class="ping-toggle">
                      <input type="checkbox" class="toggle-input" checked={settings.lock_path} disabled={settingsBusy}
                        onchange={(e) => patch({ lock_path: (e.target as HTMLInputElement).checked })} />
                      <span class="toggle-knob"></span>
                    </label>
                  </div>
                  <div class="adv-field-group">
                    <span class="adv-field-label">中转服务器偏好（重新连接协调服务器后生效）</span>
                    {#if relaysError}
                      <small class="adv-hint is-error">中转列表读取失败：{relaysError}</small>
                    {:else if !relays}
                      <small class="adv-hint">（加载中…）</small>
                    {:else}
                      <div class="adv-radio-list">
                        <label class="adv-radio">
                          <input type="radio" name="relay" checked={settings.preferred_relay == null} disabled={settingsBusy}
                            onchange={() => patch({ preferred_relay: null })} />
                          <span>自动选择最优</span>
                        </label>
                        {#each relays.relays ?? [] as r (r.addr)}
                          <label class="adv-radio">
                            <input type="radio" name="relay" checked={settings.preferred_relay === r.addr} disabled={settingsBusy}
                              onchange={() => patch({ preferred_relay: r.addr })} />
                            <span class="mono">{r.addr}{relays.current === r.addr ? '（当前）' : ''}{relays.rtt_ms?.[r.addr] != null ? ` ${relays.rtt_ms[r.addr]} ms` : ''}</span>
                          </label>
                        {/each}
                      </div>
                    {/if}
                  </div>
                {/if}
                <div class="adv-field-group">
                  <span class="adv-field-label">路径视图（只读）</span>
                  {#if sectionError.path}<small class="adv-hint is-error">{sectionError.path}</small>{/if}
                  {#if pathView.length === 0}
                    <small class="adv-hint">暂无路径数据</small>
                  {:else}
                    {#each pathView as entry (entry.device_id)}
                      <div class="adv-mono-block">
                        <div class="mono adv-mono-id">{entry.display_name ? `${entry.display_name} (${entry.device_id})` : entry.device_id}</div>
                        <div>选中: <strong>{entry.chosen}</strong></div>
                        {#each entry.candidates as c}
                          <div class="mono adv-mono-row">
                            {c.addr}  {c.rtt_ms != null ? c.rtt_ms.toFixed(1) + ' ms' : '-'}  {c.loss_rate != null ? `loss ${(c.loss_rate * 100).toFixed(0)}%` : ''}  [{c.status}]
                          </div>
                        {/each}
                      </div>
                    {/each}
                  {/if}
                </div>
              </div>
            {/if}
          </div>

          <div class="adv-section">
            <div class="adv-section-hdr" role="button" tabindex="0" onclick={() => toggleSection('trans')}
              onkeydown={(e) => (e.key === 'Enter' || e.key === ' ') && toggleSection('trans')}><span>网络传输</span><span class="adv-section-arrow"><Icon icon={openSection.trans ? 'mdi:chevron-up' : 'mdi:chevron-down'} width={18} height={18} /></span></div>
            {#if openSection.trans}
              <div class="adv-section-body">
                {#if settings}
                  <div class="adv-field-group">
                    <span class="adv-field-label">MTU（重启客户端核心后生效）</span>
                    <div class="adv-radio-row">
                      <label class="adv-radio">
                        <input type="radio" name="mtu" value="auto" bind:group={mtuMode} disabled={settingsBusy}
                          onchange={() => patch({ mtu_override: null })} /><span>自动</span>
                      </label>
                      <label class="adv-radio"><input type="radio" name="mtu" value="custom" bind:group={mtuMode} disabled={settingsBusy} /><span>自定义</span></label>
                      {#if mtuMode === 'custom'}
                        <input type="text" class="adv-inline-input" bind:value={mtuInput} placeholder="1280–1500" />
                        <button class="mini-btn" onclick={applyMtu} disabled={settingsBusy}>应用</button>
                      {/if}
                    </div>
                  </div>
                  <div class="adv-field-group">
                    <span class="adv-field-label">DNS 配置模式（仅 Linux）</span>
                    <div class="adv-radio-list">
                      <label class="adv-radio">
                        <input type="radio" name="dns" checked={settings.linux_dns_mode !== 'iptables_only'} disabled={settingsBusy}
                          onchange={() => patch({ linux_dns_mode: 'prefer_split' })} />
                        <span>优先使用 DNS 分流（默认）</span>
                      </label>
                      <small class="adv-hint indent">优先通过 systemd-resolved 配置，不可用时自动切换为 DNS 拦截</small>
                      <label class="adv-radio">
                        <input type="radio" name="dns" checked={settings.linux_dns_mode === 'iptables_only'} disabled={settingsBusy}
                          onchange={() => patch({ linux_dns_mode: 'iptables_only' })} />
                        <span>仅使用 DNS 拦截</span>
                      </label>
                      <small class="adv-hint indent">仅使用 iptables 配置 DNS 拦截规则，跳过 systemd-resolved</small>
                    </div>
                  </div>
                {/if}
                <div class="adv-field-group">
                  <span class="adv-field-label">流量计数（路径切换时自动重置）<button class="mini-btn" onclick={loadTraffic}>刷新</button></span>
                  {#if sectionError.traffic}<small class="adv-hint is-error">{sectionError.traffic}</small>{/if}
                  {#if traffic.length === 0}
                    <small class="adv-hint">暂无流量数据</small>
                  {:else}
                    <table class="adv-table">
                      <thead><tr><th>设备</th><th>发送</th><th>接收</th><th>丢弃</th></tr></thead>
                      <tbody>
                        {#each traffic as t (t.device_id)}
                          <tr><td class="mono">{t.display_name ?? t.device_id}</td><td>{formatBytes(t.tx)}</td><td>{formatBytes(t.rx)}</td><td>{t.drop}</td></tr>
                        {/each}
                      </tbody>
                    </table>
                  {/if}
                </div>
              </div>
            {/if}
          </div>

          <div class="adv-section">
            <div class="adv-section-hdr" role="button" tabindex="0" onclick={() => toggleSection('ep')}
              onkeydown={(e) => (e.key === 'Enter' || e.key === ' ') && toggleSection('ep')}><span>穿透控制</span><span class="adv-section-arrow"><Icon icon={openSection.ep ? 'mdi:chevron-up' : 'mdi:chevron-down'} width={18} height={18} /></span></div>
            {#if openSection.ep}
              <div class="adv-section-body">
                {#if sectionError.endpoints}<small class="adv-hint is-error">{sectionError.endpoints}</small>{/if}
                <div class="adv-field-group">
                  <span class="adv-field-label">当前端点</span>
                  {#if !endpoints || endpoints.current.length === 0}
                    <small class="adv-hint">（未探测到）</small>
                  {:else}
                    {#each endpoints.current as ep}<div class="mono adv-mono-row">{ep}</div>{/each}
                  {/if}
                </div>
                {#if endpoints && endpoints.history.length > 0}
                  <div class="adv-field-group">
                    <span class="adv-field-label">历史端点</span>
                    {#each endpoints.history as rec}
                      <div class="mono adv-mono-row">{new Date(rec.timestamp * 1000).toLocaleTimeString()}  {rec.endpoints.join(', ')}</div>
                    {/each}
                  </div>
                {/if}
                {#if settings}
                  <div class="adv-field-group">
                    <span class="adv-field-label">保活间隔（P2P + 中转均生效）</span>
                    <div class="adv-radio-row">
                      <label class="adv-radio">
                        <input type="radio" name="ka" value="auto" bind:group={kaMode} disabled={settingsBusy}
                          onchange={() => patch({ keepalive_secs: null })} /><span>自动 (25 s)</span>
                      </label>
                      <label class="adv-radio"><input type="radio" name="ka" value="custom" bind:group={kaMode} disabled={settingsBusy} /><span>自定义</span></label>
                      {#if kaMode === 'custom'}
                        <input type="text" class="adv-inline-input" bind:value={kaInput} placeholder="5–30 秒" />
                        <button class="mini-btn" onclick={applyKeepalive} disabled={settingsBusy}>应用</button>
                      {/if}
                    </div>
                  </div>
                {/if}
                <div class="settings-actions">
                  {#if refreshEpState}<small class="adv-hint">{refreshEpState}</small>{/if}
                  <button class="save-btn outline-btn" onclick={refreshEndpoint} disabled={!ready}>强制刷新端点</button>
                </div>
              </div>
            {/if}
          </div>

          <div class="adv-section">
            <div class="adv-section-hdr" role="button" tabindex="0" onclick={() => toggleSection('diag')}
              onkeydown={(e) => (e.key === 'Enter' || e.key === ' ') && toggleSection('diag')}><span>其他诊断工具</span><span class="adv-section-arrow"><Icon icon={openSection.diag ? 'mdi:chevron-up' : 'mdi:chevron-down'} width={18} height={18} /></span></div>
            {#if openSection.diag}
              <div class="adv-section-body">
                <div class="adv-field-group">
                  <span class="adv-field-label">Ping 节点</span>
                  <div class="input-row">
                    <input type="text" class="input-grow" bind:value={pingTarget} list="ping-peers" placeholder="目标设备 ID" disabled={pingRunning} />
                    <datalist id="ping-peers">
                      {#each peers as p (p.device_id)}<option value={p.device_id}>{p.display_name ?? p.device_id}</option>{/each}
                    </datalist>
                    <button class="mini-btn" onclick={startPing} disabled={!pingTarget.trim() || pingRunning || !ready}>{pingRunning ? '进行中…' : '开始'}</button>
                  </div>
                  {#if pingError}<small class="adv-hint is-error">{pingError}</small>{/if}
                  {#if pingResults.length > 0}
                    {@const recv = pingResults.filter((r) => r != null).length}
                    {@const rtts = pingResults.filter((r): r is number => r != null)}
                    {@const avg = rtts.length > 0 ? rtts.reduce((a, b) => a + b, 0) / rtts.length : null}
                    <div class="adv-mono-block">
                      <div>发送 {pingResults.length} / 收到 {recv} / 丢失 {pingResults.length - recv}{avg != null ? ` / 平均 RTT ${avg.toFixed(1)} ms` : ''}</div>
                      <div class="mono adv-mono-row">{pingResults.map((r, i) => `#${i + 1}: ${r != null ? r.toFixed(1) + ' ms' : '超时'}`).join('  ')}</div>
                      {#if !pingDone}<div class="adv-hint is-progress">进行中…</div>{/if}
                    </div>
                  {/if}
                </div>
              </div>
            {/if}
          </div>
        </div>
      {/if}
    </div>
  </div>
</div>

{#if showLogs}<LogDialog onclose={() => (showLogs = false)} />{/if}
