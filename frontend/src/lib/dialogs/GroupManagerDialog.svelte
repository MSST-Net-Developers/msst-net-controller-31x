<script lang="ts">
  import Modal from '$lib/components/Modal.svelte';
  import Icon from '$lib/icons';
  import { ipc, errorMessage } from '$lib/api/client';
  import { R } from '$lib/api/requests';
  import { controller } from '$lib/controller.svelte';
  import type { PermissionGroupDef } from '$lib/types';

  let { onclose }: { onclose: () => void } = $props();

  const groups = $derived(controller.state?.acl_groups ?? []);

  let editing = $state<PermissionGroupDef | null>(null);
  let showForm = $state(false);
  let name = $state('');
  let kind = $state<'user' | 'device'>('device');
  let members = $state<string[]>([]);
  let memberInput = $state('');
  let busy = $state(false);
  let error = $state('');
  let listError = $state('');

  function openNew() {
    editing = null;
    name = '';
    kind = 'device';
    members = [];
    memberInput = '';
    error = '';
    showForm = true;
  }

  function openEdit(g: PermissionGroupDef) {
    editing = g;
    name = g.name;
    kind = g.kind;
    members = [...g.member_ids];
    memberInput = '';
    error = '';
    showForm = true;
  }

  function addMember() {
    const v = memberInput.trim();
    if (v && !members.includes(v)) members = [...members, v];
    memberInput = '';
  }

  async function submit() {
    if (!name.trim()) {
      error = '请输入分组名称';
      return;
    }
    busy = true;
    error = '';
    try {
      if (editing) await ipc(R.updateAclGroup(editing.id, { name: name.trim(), member_ids: members }));
      else await ipc(R.createAclGroup({ name: name.trim(), kind, member_ids: members }));
      showForm = false;
      editing = null;
    } catch (e) {
      error = errorMessage(e);
    } finally {
      busy = false;
    }
  }

  async function remove(g: PermissionGroupDef) {
    if (!confirm(`确定删除权限组「${g.name}」？已用它创建的规则不受影响。`)) return;
    listError = '';
    try {
      await ipc(R.deleteAclGroup(g.id));
    } catch (e) {
      listError = errorMessage(e);
    }
  }
</script>

<Modal {onclose} wide busy={busy}>
  <h2 class="modal-title">权限组管理</h2>
  {#if groups.length === 0}
    <p style="text-align:center;color:var(--text-muted);font-size:var(--font-sm)">暂无权限组</p>
  {:else}
    <div class="group-list">
      {#each groups as g (g.id)}
        <div class="group-item">
          <span class="group-name">{g.name}</span>
          <span class="group-kind">{g.kind === 'device' ? '设备组' : '用户组'}</span>
          <span class="group-count">{g.member_ids.length} 个成员</span>
          <button class="icon-btn" onclick={() => openEdit(g)} title="编辑"><Icon icon="mdi:pencil" width={13} height={13} /></button>
          <button class="icon-btn danger" onclick={() => remove(g)} title="删除"><Icon icon="mdi:trash-can-outline" width={13} height={13} /></button>
        </div>
      {/each}
    </div>
  {/if}
  {#if listError}<p class="modal-error">{listError}</p>{/if}
  <p class="inline-note">权限组在创建规则时展开为成员列表；之后修改权限组不会改变已创建的规则。</p>

  {#if showForm}
    <div class="new-group-form">
      <label class="field"><span>分组名称</span>
        <input type="text" bind:value={name} placeholder="如：trusted-friends" disabled={busy} /></label>
      {#if !editing}
        <label class="field"><span>类型</span>
          <select bind:value={kind} class="select-input" disabled={busy}>
            <option value="device">设备组</option>
            <option value="user">用户组</option>
          </select>
        </label>
      {:else}
        <p class="step-hint">类型：{editing.kind === 'device' ? '设备组' : '用户组'}（不可更改）</p>
      {/if}
      <label class="field"><span>{kind === 'device' ? '设备 ID / 助记符' : '用户 ID / 助记符'}</span>
        <div class="input-row">
          <input type="text" bind:value={memberInput} placeholder="输入 ID 或助记符后按添加" class="input-grow"
            onkeydown={(e) => e.key === 'Enter' && addMember()} disabled={busy} />
          <button class="mini-btn" onclick={addMember} disabled={busy}>添加</button>
        </div>
      </label>
      {#if members.length > 0}
        <div class="tag-list">
          {#each members as m}
            <span class="tag">{m}<button class="tag-del" onclick={() => (members = members.filter((x) => x !== m))}>×</button></span>
          {/each}
        </div>
      {/if}
      {#if error}<p class="modal-error">{error}</p>{/if}
      <div class="modal-actions">
        <button class="modal-btn cancel" onclick={() => { showForm = false; editing = null; }} disabled={busy}>取消</button>
        <button class="modal-btn confirm" onclick={submit} disabled={busy || !controller.connected}>
          {busy ? '提交中…' : editing ? '保存' : '创建'}
        </button>
      </div>
    </div>
  {:else}
    <div class="modal-actions">
      <button class="modal-btn cancel" onclick={onclose}>关闭</button>
      <button class="modal-btn confirm" onclick={openNew} disabled={!controller.connected}>＋ 新建分组</button>
    </div>
  {/if}
</Modal>
