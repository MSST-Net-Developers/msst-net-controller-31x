<script lang="ts">
  // .msst.net namespace management (3.0.x).  Core forwards these requests to
  // the coordinator with the user's token, so OIDC authentication is required.
  import { onMount } from 'svelte';
  import Modal from '$lib/components/Modal.svelte';
  import { ipc, errorMessage } from '$lib/api/client';
  import { R } from '$lib/api/requests';
  import { controller } from '$lib/controller.svelte';
  import type { DnsRecord, NamespaceRegistration } from '$lib/types';

  type Phase = 'idle' | 'submitting' | 'success' | 'error';

  const authenticated = $derived(controller.health?.is_authenticated ?? false);

  let namespaces = $state<NamespaceRegistration[]>([]);
  let loading = $state(false);
  let error = $state('');

  let showRegister = $state(false);
  let newNs = $state('');
  let newReason = $state('');
  let regPhase = $state<Phase>('idle');
  let regError = $state('');

  let active = $state<string | null>(null);
  let records = $state<DnsRecord[]>([]);
  let recordsLoading = $state(false);
  let recordsError = $state('');
  let recordsSeq = 0;

  let showRecord = $state(false);
  let editing = $state<DnsRecord | null>(null);
  let recType = $state<'CNAME' | 'TXT' | 'SRV'>('CNAME');
  let recName = $state('');
  let recContent = $state('');
  let srvPriority = $state(10);
  let srvWeight = $state(20);
  let srvPort = $state(443);
  let srvTarget = $state('');
  let recTTL = $state(300);
  let recPhase = $state<Phase>('idle');
  let recError = $state('');

  async function loadNamespaces() {
    if (!authenticated) return;
    loading = true;
    error = '';
    try {
      namespaces = (await ipc<{ namespaces: NamespaceRegistration[] }>(R.getDnsDomains())).namespaces ?? [];
    } catch (e) {
      error = errorMessage(e);
    } finally {
      loading = false;
    }
  }

  onMount(loadNamespaces);

  async function submitRegister() {
    if (!newNs.trim() || !newReason.trim() || regPhase === 'submitting') return;
    const ns = newNs.trim().toLowerCase().replace(/\.msst\.net$/i, '');
    if (!/^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/.test(ns)) {
      regPhase = 'error';
      regError = '命名空间只能包含小写字母、数字和连字符，且不能以连字符开头或结尾';
      return;
    }
    regPhase = 'submitting';
    regError = '';
    try {
      await ipc(R.dnsRegisterDomain(ns, newReason.trim()));
      regPhase = 'success';
      setTimeout(() => { showRegister = false; regPhase = 'idle'; loadNamespaces(); }, 800);
    } catch (e) {
      regPhase = 'error';
      regError = errorMessage(e);
    }
  }

  async function openRecords(ns: string) {
    if (active === ns) {
      active = null;
      return;
    }
    active = ns;
    await loadRecords(ns);
  }

  async function loadRecords(ns: string) {
    const seq = ++recordsSeq;
    records = [];
    recordsLoading = true;
    recordsError = '';
    try {
      const d = await ipc<{ records: DnsRecord[] }>(R.getDnsRecords(ns));
      if (seq === recordsSeq && active === ns) records = d.records ?? [];
    } catch (e) {
      if (seq === recordsSeq) recordsError = errorMessage(e);
    } finally {
      if (seq === recordsSeq) recordsLoading = false;
    }
  }

  function openAdd() {
    editing = null;
    recType = 'CNAME';
    recName = '';
    recContent = '';
    srvPriority = 10;
    srvWeight = 20;
    srvPort = 443;
    srvTarget = '';
    recTTL = 300;
    recPhase = 'idle';
    recError = '';
    showRecord = true;
  }

  function openEdit(r: DnsRecord) {
    editing = r;
    recType = r.record_type;
    recName = r.name;
    recTTL = r.ttl;
    recPhase = 'idle';
    recError = '';
    if (r.record_type === 'SRV') {
      const p = r.content.split(' ');
      srvPriority = parseInt(p[0]) || 10;
      srvWeight = parseInt(p[1]) || 20;
      srvPort = parseInt(p[2]) || 443;
      srvTarget = p[3] || '';
      recContent = '';
    } else {
      recContent = r.content;
    }
    showRecord = true;
  }

  async function submitRecord() {
    if (recPhase === 'submitting' || !active) return;
    const content = recType === 'SRV' ? `${srvPriority} ${srvWeight} ${srvPort} ${srvTarget.trim()}` : recContent.trim();
    if (!content || (recType === 'SRV' && !srvTarget.trim())) {
      recPhase = 'error';
      recError = '请填写记录内容';
      return;
    }
    recPhase = 'submitting';
    recError = '';
    const ns = active;
    try {
      if (editing) await ipc(R.dnsUpdateRecord(ns, editing.id, { content, ttl: recTTL }));
      else await ipc(R.dnsCreateRecord(ns, { record_type: recType, name: recName.trim() || '@', content, ttl: recTTL }));
      recPhase = 'success';
      setTimeout(() => { showRecord = false; recPhase = 'idle'; loadRecords(ns); }, 600);
    } catch (e) {
      recPhase = 'error';
      recError = errorMessage(e);
    }
  }

  async function deleteRecord(ns: string, r: DnsRecord) {
    if (!confirm('确定删除此记录？')) return;
    recordsError = '';
    try {
      await ipc(R.dnsDeleteRecord(ns, r.id));
      await loadRecords(ns);
    } catch (e) {
      recordsError = `删除失败：${errorMessage(e)}`;
    }
  }

  const statusLabel = (s: string) => (s === 'pending' ? '待审核' : s === 'approved' ? '已审批' : '已拒绝');
</script>

<div class="dns-page">
  <div class="dns-header">
    <div>
      <h2 class="dns-title">🌐 .msst.net 命名空间管理</h2>
      <p class="dns-subtitle">注册命名空间后，可在 <strong>*.&lt;namespace&gt;.msst.net</strong> 下添加 CNAME / TXT / SRV 记录。</p>
    </div>
    <button class="dns-register-btn" disabled={!authenticated || !controller.connected}
      onclick={() => { newNs = ''; newReason = ''; regPhase = 'idle'; regError = ''; showRegister = true; }}>＋ 申请命名空间</button>
  </div>

  {#if !authenticated}
    <div class="dns-empty">
      <p>域名管理需要先完成 MSST 接入认证</p>
      <p class="dns-empty-hint">请在首页右下角点击 👤 完成接入认证</p>
    </div>
  {:else if loading}
    <p class="dns-loading">加载中…</p>
  {:else if error}
    <p class="dns-error">{error} <button class="mini-btn" onclick={loadNamespaces}>重试</button></p>
  {:else if namespaces.length === 0}
    <div class="dns-empty">
      <p>您还没有命名空间</p>
      <p class="dns-empty-hint">点击「申请命名空间」开始注册（需审核）</p>
    </div>
  {:else}
    <div class="dns-domain-list">
      {#each namespaces as ns (ns.namespace)}
        <div class="dns-domain-card" class:dns-card-active={active === ns.namespace}>
          <div class="dns-domain-header">
            <div class="dns-domain-name">*.{ns.namespace}.msst.net</div>
            <span class="dns-badge dns-badge-{ns.status}">{statusLabel(ns.status)}</span>
          </div>
          {#if ns.reason}<p class="dns-reason">申请理由：{ns.reason}</p>{/if}
          {#if ns.status === 'rejected' && ns.reject_reason}<p class="dns-reject-reason">拒绝原因：{ns.reject_reason}</p>{/if}
          {#if ns.status === 'approved'}
            <button class="dns-manage-btn" onclick={() => openRecords(ns.namespace)}>{active === ns.namespace ? '▾ 管理记录' : '▸ 管理记录'}</button>
          {/if}
        </div>

        {#if active === ns.namespace && ns.status === 'approved'}
          <div class="dns-records-panel">
            <div class="dns-records-header">
              <span class="dns-records-title">DNS 记录 — *.{ns.namespace}.msst.net</span>
              <button class="dns-add-btn" onclick={openAdd}>＋ 添加记录</button>
            </div>
            {#if recordsError}<p class="dns-error">{recordsError}</p>{/if}
            {#if recordsLoading}
              <p class="dns-loading">加载中…</p>
            {:else if records.length === 0 && !recordsError}
              <p class="dns-records-empty">暂无记录，点击「添加记录」创建</p>
            {:else if records.length > 0}
              <table class="dns-records-table">
                <thead><tr><th>类型</th><th>名称</th><th>内容</th><th>TTL</th><th></th></tr></thead>
                <tbody>
                  {#each records as rec (rec.id)}
                    <tr>
                      <td><span class="dns-type-badge dns-type-{rec.record_type.toLowerCase()}">{rec.record_type}</span></td>
                      <td class="dns-rec-name">{rec.name === '@' ? ns.namespace + '.msst.net' : rec.name + '.' + ns.namespace + '.msst.net'}</td>
                      <td class="dns-rec-content">{rec.content}</td>
                      <td>{rec.ttl}</td>
                      <td>
                        <button class="dns-rec-btn dns-rec-edit" onclick={() => openEdit(rec)}>编辑</button>
                        <button class="dns-rec-btn dns-rec-del" onclick={() => deleteRecord(ns.namespace, rec)}>删除</button>
                      </td>
                    </tr>
                  {/each}
                </tbody>
              </table>
            {/if}
          </div>
        {/if}
      {/each}
    </div>
  {/if}
</div>

{#if showRegister}
  <Modal onclose={() => (showRegister = false)} busy={regPhase === 'submitting'} cls="dns-modal">
    <h2 class="modal-title">申请命名空间</h2>
    {#if regPhase === 'success'}
      <div class="modal-feedback success">✓ 申请已提交，等待管理员审核</div>
    {:else}
      <label class="field"><span>命名空间</span>
        <div class="dns-domain-input-row">
          <input type="text" bind:value={newNs} placeholder="如 abjust" disabled={regPhase === 'submitting'} />
          <span class="dns-suffix">.msst.net</span>
        </div>
      </label>
      <label class="field"><span>申请理由（必填）</span>
        <textarea bind:value={newReason} placeholder="请说明命名空间的用途和必要性" disabled={regPhase === 'submitting'} rows={3}></textarea>
      </label>
      <p class="dns-quota-hint">每人最多同时拥有 5 个命名空间（待审核+已审批合计）</p>
      {#if regError}<p class="modal-error">{regError}</p>{/if}
      <div class="modal-actions">
        <button class="modal-btn confirm" onclick={submitRegister} disabled={regPhase === 'submitting' || !newNs.trim() || !newReason.trim()}>
          {regPhase === 'submitting' ? '提交中…' : '提交申请'}
        </button>
        <button class="modal-btn cancel" onclick={() => (showRegister = false)} disabled={regPhase === 'submitting'}>取消</button>
      </div>
    {/if}
  </Modal>
{/if}

{#if showRecord && active}
  <Modal onclose={() => (showRecord = false)} busy={recPhase === 'submitting'} cls="dns-record-modal dns-modal">
    <h2 class="modal-title">{editing ? '编辑 DNS 记录' : '添加 DNS 记录'}</h2>
    <p class="dns-modal-domain">命名空间：*.{active}.msst.net</p>
    {#if recPhase === 'success'}
      <div class="modal-feedback success">✓ {editing ? '已更新' : '已添加'}</div>
    {:else}
      {#if !editing}
        <div class="field"><span>记录类型</span>
          <div class="dns-type-selector">
            {#each ['CNAME', 'TXT', 'SRV'] as const as t}
              <button class="dns-type-opt" class:dns-type-opt-active={recType === t} onclick={() => (recType = t)}>{t}</button>
            {/each}
          </div>
        </div>
        <label class="field"><span>子域名（如 myapp、api、www）</span>
          <input type="text" bind:value={recName} placeholder="如 myapp" disabled={recPhase === 'submitting'} /></label>
      {/if}
      {#if recType === 'CNAME'}
        <label class="field"><span>目标（ezPlay 主机名，如 device.user.ezplay.msst.net）</span>
          <input type="text" bind:value={recContent} placeholder="device.user.ezplay.msst.net" disabled={recPhase === 'submitting'} /></label>
      {:else if recType === 'TXT'}
        <label class="field"><span>文本内容</span>
          <input type="text" bind:value={recContent} placeholder="v=spf1 include:..." disabled={recPhase === 'submitting'} /></label>
      {:else}
        <div class="dns-srv-row">
          <label class="field dns-srv-field"><span>优先级</span><input type="number" bind:value={srvPriority} min={0} max={65535} disabled={recPhase === 'submitting'} /></label>
          <label class="field dns-srv-field"><span>权重</span><input type="number" bind:value={srvWeight} min={0} max={65535} disabled={recPhase === 'submitting'} /></label>
          <label class="field dns-srv-field"><span>端口</span><input type="number" bind:value={srvPort} min={1} max={65535} disabled={recPhase === 'submitting'} /></label>
        </div>
        <label class="field"><span>目标主机（ezPlay 主机名）</span>
          <input type="text" bind:value={srvTarget} placeholder="device.user.ezplay.msst.net" disabled={recPhase === 'submitting'} /></label>
      {/if}
      <label class="field"><span>TTL（秒，默认 300）</span>
        <input type="number" bind:value={recTTL} min={60} max={86400} disabled={recPhase === 'submitting'} /></label>
      {#if recError}<p class="modal-error">{recError}</p>{/if}
      <div class="modal-actions">
        <button class="modal-btn confirm" class:submitting={recPhase === 'submitting'} onclick={submitRecord} disabled={recPhase === 'submitting'}>
          {recPhase === 'submitting' ? '保存中…' : editing ? '保存' : '添加'}
        </button>
        <button class="modal-btn cancel" onclick={() => (showRecord = false)} disabled={recPhase === 'submitting'}>取消</button>
      </div>
    {/if}
  </Modal>
{/if}
