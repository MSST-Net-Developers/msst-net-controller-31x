<script lang="ts">
  // Create a firewall rule (3.0.x three-step wizard).  The request uses
  // Core's 3.0.x-compatible `RuleRequest` format; Core validates it and
  // resolves mnemonics to stable IDs (unresolvable ones are rejected).
  import { onMount, untrack } from 'svelte';
  import Modal from '$lib/components/Modal.svelte';
  import { ipc, errorMessage } from '$lib/api/client';
  import { R } from '$lib/api/requests';
  import { controller } from '$lib/controller.svelte';
  import { validPortExpr } from '$lib/util';
  import type { FirewallRule, ServiceCategory } from '$lib/types';

  let { onclose, from = null }: { onclose: () => void; from?: FirewallRule | null } = $props();

  let step = $state<1 | 2 | 3>(1);
  let srcMode = $state<'group' | 'manual' | 'space'>('group');
  let selectedGroupId = $state('');
  let srcKind = $state<'user' | 'device'>('device');
  let srcInput = $state('');
  let srcItems = $state<{ kind: string; id: string }[]>([]);
  let targetMode = $state<'simple' | 'advanced'>('simple');
  let selectedServices = $state<string[]>([]);
  let allServices = $state(false);
  let advProto = $state<'tcp' | 'udp' | 'tcp+udp'>('tcp+udp');
  let advPort = $state('');
  let advList = $state<{ proto: string; port: string }[]>([]);
  let action = $state<'allow' | 'deny'>('allow');
  let comment = $state('');
  let error = $state('');
  let busy = $state(false);

  let categories = $state<ServiceCategory[]>([]);
  let servicesError = $state('');

  const groups = $derived(controller.state?.acl_groups ?? []);
  const nodes = $derived(Object.values(controller.state?.nodes ?? {}));

  // Prefill from an existing rule ("复制为新规则"), once when opened.
  const initial = untrack(() => from);
  if (initial) {
    const pg = initial.permission_group;
    if (pg.kind === 'space') srcMode = 'space';
    else {
      srcMode = 'manual';
      srcKind = pg.kind === 'devices' ? 'device' : 'user';
      srcItems = pg.ids.map((id) => ({ kind: srcKind, id }));
    }
    targetMode = 'advanced';
    advList = initial.protocol_ports.map((pp) => ({
      proto: pp.proto,
      port: pp.start === pp.end ? String(pp.start) : pp.start === 1 && pp.end === 65535 ? '*' : `${pp.start}-${pp.end}`,
    }));
    action = initial.action;
    comment = initial.comment ?? '';
  }

  onMount(async () => {
    if (!controller.view?.capabilities.get_services) {
      servicesError = '当前客户端核心版本不提供服务列表，请使用「协议/端口」方式。';
      return;
    }
    try {
      categories = (await ipc<{ categories: ServiceCategory[] }>(R.getServices())).categories ?? [];
    } catch (e) {
      servicesError = `服务列表加载失败：${errorMessage(e)}`;
    }
  });

  // Display name of an entered ID or mnemonic, from Core's node list.
  const lookup = $derived.by(() => {
    const v = srcInput.trim();
    if (!v) return '';
    const n = nodes.find((p) =>
      srcKind === 'device' ? p.device_id === v || p.device_handle === v : p.user_id === v || p.user_handle === v,
    );
    return n ? (n.display_name ?? (srcKind === 'device' ? n.device_id : (n.user_id ?? ''))) : '';
  });

  function addSrc() {
    const v = srcInput.trim();
    if (!v) return;
    if (!srcItems.some((i) => i.id === v)) srcItems = [...srcItems, { kind: srcKind, id: v }];
    srcInput = '';
  }

  function addPort() {
    const p = advPort.trim();
    if (!p) return;
    if (!validPortExpr(p)) {
      error = `端口格式无效：${p}（可填 *、80 或 80-443，范围 1-65535）`;
      return;
    }
    error = '';
    if (!advList.some((x) => x.proto === advProto && x.port === p)) advList = [...advList, { proto: advProto, port: p }];
    advPort = '';
  }

  function next() {
    error = '';
    if (step === 1) {
      if (srcMode === 'group' && !selectedGroupId) return void (error = '请选择一个权限组');
      if (srcMode === 'manual' && srcItems.length === 0) return void (error = '请至少添加一个来源');
    } else if (step === 2) {
      if (targetMode === 'simple' && !allServices && selectedServices.length === 0) return void (error = '请选择至少一个服务类型');
      if (targetMode === 'advanced' && advList.length === 0) return void (error = '请添加至少一条协议+端口规则');
    }
    step = (step + 1) as 2 | 3;
  }

  async function submit() {
    error = '';
    const req: Record<string, unknown> = { action, direction: 'inbound', enabled: true, expires_at: null };
    if (srcMode === 'group') {
      req.group_id = selectedGroupId;
      req.principals = [];
    } else if (srcMode === 'space') {
      req.principals = [{ kind: 'space', id: '*' }];
    } else {
      req.principals = srcItems.map((i) => ({ kind: i.kind, id: i.id }));
    }
    if (targetMode === 'simple') {
      if (allServices) req.protocol_ports = [{ proto: 'tcp+udp', port: '*' }];
      else req.services = selectedServices;
    } else {
      req.protocol_ports = advList;
    }
    if (comment.trim()) req.comment = comment.trim();
    busy = true;
    try {
      await ipc(R.createAclRule(req));
      onclose();
    } catch (e) {
      error = errorMessage(e);
    } finally {
      busy = false;
    }
  }
</script>

<Modal {onclose} wide busy={busy}>
  <div class="wizard-header">
    <h2 class="modal-title">{from ? '复制为新规则' : '新建防火墙规则'}</h2>
    <div class="wizard-steps">
      <div class="wstep" class:wstep-active={step === 1} class:wstep-done={step > 1}>
        <span class="wstep-num">{step > 1 ? '✓' : '1'}</span><span class="wstep-label">来源匹配</span>
      </div>
      <div class="wstep-line" class:wstep-line-done={step > 1}></div>
      <div class="wstep" class:wstep-active={step === 2} class:wstep-done={step > 2}>
        <span class="wstep-num">{step > 2 ? '✓' : '2'}</span><span class="wstep-label">目标匹配</span>
      </div>
      <div class="wstep-line" class:wstep-line-done={step > 2}></div>
      <div class="wstep" class:wstep-active={step === 3}>
        <span class="wstep-num">3</span><span class="wstep-label">操作与备注</span>
      </div>
    </div>
  </div>

  {#if step === 1}
    <div class="wizard-body">
      <p class="step-desc">选择此规则匹配的来源</p>
      <div class="src-mode-cards">
        <label class="src-card" class:src-card-active={srcMode === 'group'}>
          <input type="radio" bind:group={srcMode} value="group" />
          <div class="src-card-body"><span class="src-card-title">使用命名权限组</span><span class="src-card-desc">选择一个预定义的权限组（推荐）</span></div>
        </label>
        <label class="src-card" class:src-card-active={srcMode === 'manual'}>
          <input type="radio" bind:group={srcMode} value="manual" />
          <div class="src-card-body"><span class="src-card-title">手动指定来源</span><span class="src-card-desc">输入设备或用户的助记符或 ID</span></div>
        </label>
        <label class="src-card" class:src-card-active={srcMode === 'space'}>
          <input type="radio" bind:group={srcMode} value="space" />
          <div class="src-card-body"><span class="src-card-title">用于房间模式</span><span class="src-card-desc">匹配当前所有房间成员</span></div>
        </label>
      </div>

      {#if srcMode === 'group'}
        <label class="field" style="margin-top:12px">
          <span>权限组</span>
          <select bind:value={selectedGroupId} class="select-input">
            <option value="">— 选择权限组 —</option>
            {#each groups as g (g.id)}<option value={g.id}>{g.name}（{g.kind === 'device' ? '设备组' : '用户组'}，{g.member_ids.length} 个成员）</option>{/each}
          </select>
        </label>
        {#if groups.length === 0}<p class="step-hint">暂无权限组，请先在「权限组管理」中创建</p>{/if}
        <p class="inline-note">规则创建时会复制权限组的当前成员；之后修改权限组不会改变此规则。</p>
      {:else if srcMode === 'space'}
        <p class="step-hint" style="margin-top:12px">规则将自动匹配当前加入的所有房间成员，无需手动指定。</p>
      {:else}
        <div style="margin-top:12px">
          <label class="field">
            <span>来源类型（列表中所有来源必须相同）</span>
            <select bind:value={srcKind} class="select-input" onchange={() => (srcItems = [])}>
              <option value="device">设备</option>
              <option value="user">用户</option>
            </select>
          </label>
          <div class="input-row" style="margin-top:8px">
            <input type="text" bind:value={srcInput} placeholder="输入助记符或 ID" class="input-grow"
              onkeydown={(e) => e.key === 'Enter' && addSrc()} />
            <button class="mini-btn" onclick={addSrc}>添加</button>
          </div>
          {#if lookup}<p class="lookup-hint">→ {lookup}</p>{/if}
          {#if srcItems.length > 0}
            <div class="tag-list" style="margin-top:6px">
              {#each srcItems as item (item.id)}
                <span class="tag">{item.kind === 'device' ? '设备' : '用户'}:{item.id}
                  <button class="tag-del" onclick={() => (srcItems = srcItems.filter((i) => i.id !== item.id))}>×</button></span>
              {/each}
            </div>
          {/if}
          <p class="inline-note">助记符会在创建时由客户端核心解析为固定 ID；无法解析的助记符将被拒绝。</p>
        </div>
      {/if}
    </div>
  {:else if step === 2}
    <div class="wizard-body">
      <p class="step-desc">选择此规则匹配的目标服务或端口</p>
      <div class="src-mode-cards">
        <label class="src-card" class:src-card-active={targetMode === 'simple'}>
          <input type="radio" bind:group={targetMode} value="simple" />
          <div class="src-card-body"><span class="src-card-title">服务（简单）</span><span class="src-card-desc">从常用服务列表中选择</span></div>
        </label>
        <label class="src-card" class:src-card-active={targetMode === 'advanced'}>
          <input type="radio" bind:group={targetMode} value="advanced" />
          <div class="src-card-body"><span class="src-card-title">协议/端口（进阶）</span><span class="src-card-desc">手动指定协议和端口</span></div>
        </label>
      </div>

      {#if targetMode === 'simple'}
        <div class="field" style="margin-top:12px">
          <span>服务类型（可多选）</span>
          {#if servicesError}<p class="inline-error">{servicesError}</p>{/if}
          <div class="services-scroll">
            <label class="svc-item svc-all" class:svc-checked={allServices}>
              <input type="checkbox" checked={allServices}
                onchange={(e) => { allServices = (e.target as HTMLInputElement).checked; if (allServices) selectedServices = []; }} />
              <span>所有服务</span><small>tcp+udp / *</small>
            </label>
            {#each categories as cat (cat.id)}
              <div class="svc-category-label">{cat.label}</div>
              <div class="services-grid">
                {#each cat.services as svc (svc.id)}
                  <label class="svc-item" class:svc-checked={selectedServices.includes(svc.id)} class:svc-disabled={allServices}>
                    <input type="checkbox" disabled={allServices} checked={selectedServices.includes(svc.id)}
                      onchange={(e) => {
                        selectedServices = (e.target as HTMLInputElement).checked
                          ? [...selectedServices, svc.id]
                          : selectedServices.filter((s) => s !== svc.id);
                      }} />
                    <span>{svc.name}</span><small>{svc.ports.join(', ')}</small>
                  </label>
                {/each}
              </div>
            {/each}
          </div>
        </div>
      {:else}
        <div style="margin-top:12px">
          <div class="form-row">
            <label class="field" style="flex:0 0 110px"><span>协议</span>
              <select bind:value={advProto} class="select-input">
                <option value="tcp+udp">TCP+UDP</option>
                <option value="tcp">TCP</option>
                <option value="udp">UDP</option>
              </select>
            </label>
            <label class="field" style="flex:1"><span>端口（* / 80 / 80-443）</span>
              <div class="input-row">
                <input type="text" bind:value={advPort} placeholder="如：25565" class="input-grow" onkeydown={(e) => e.key === 'Enter' && addPort()} />
                <button class="mini-btn" onclick={addPort}>添加</button>
              </div>
            </label>
          </div>
          {#if advList.length > 0}
            <div class="tag-list" style="margin-top:6px">
              {#each advList as pp (pp.proto + pp.port)}
                <span class="tag">{pp.proto}/{pp.port}
                  <button class="tag-del" onclick={() => (advList = advList.filter((x) => !(x.proto === pp.proto && x.port === pp.port)))}>×</button></span>
              {/each}
            </div>
          {/if}
        </div>
      {/if}
    </div>
  {:else}
    <div class="wizard-body">
      <p class="step-desc">设置规则的操作和可选备注</p>
      <div class="src-mode-cards">
        <label class="src-card src-card-allow" class:src-card-active={action === 'allow'}>
          <input type="radio" bind:group={action} value="allow" />
          <div class="src-card-body"><span class="src-card-title src-card-title-allow">允许</span><span class="src-card-desc">放行匹配此规则的流量</span></div>
        </label>
        <label class="src-card src-card-deny" class:src-card-active={action === 'deny'}>
          <input type="radio" bind:group={action} value="deny" />
          <div class="src-card-body"><span class="src-card-title src-card-title-deny">拒绝</span><span class="src-card-desc">拦截匹配此规则的流量</span></div>
        </label>
      </div>
      <label class="field" style="margin-top:16px">
        <span>备注（可选）</span>
        <input type="text" bind:value={comment} placeholder="规则用途说明，如：允许朋友访问游戏服务器" class="select-input" />
      </label>
    </div>
  {/if}

  {#if error}<p class="modal-error">{error}</p>{/if}

  <div class="modal-actions">
    {#if step > 1}
      <button class="modal-btn cancel" onclick={() => { error = ''; step = (step - 1) as 1 | 2; }} disabled={busy}>上一步</button>
    {:else}
      <button class="modal-btn cancel" onclick={onclose}>取消</button>
    {/if}
    {#if step < 3}
      <button class="modal-btn confirm" onclick={next}>下一步</button>
    {:else}
      <button class="modal-btn confirm" onclick={submit} disabled={busy || !controller.connected}>{busy ? '创建中…' : '创建'}</button>
    {/if}
  </div>
</Modal>
