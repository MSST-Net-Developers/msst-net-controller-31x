<script lang="ts">
  import { api } from "$lib/api/client";
  import type { AclGroup } from "$lib/types";

  let {
    groups,
    onrefresh,
  }: {
    groups: AclGroup[];
    onrefresh: () => void;
  } = $props();

  // ── Create/Edit state ──────────────────────────────────────────────────
  let showForm = $state(false);
  let editingGroup = $state<AclGroup | null>(null);
  let formName = $state("");
  let formKind = $state<"device" | "user">("device");
  let formMembers = $state<string[]>([]);
  let memberInput = $state("");
  let formError = $state<string | null>(null);
  let submitting = $state(false);

  function openCreate() {
    editingGroup = null;
    formName = "";
    formKind = "device";
    formMembers = [];
    memberInput = "";
    formError = null;
    showForm = true;
  }

  function openEdit(g: AclGroup) {
    editingGroup = g;
    formName = g.name;
    formKind = g.kind as "device" | "user";
    formMembers = [...g.member_ids];
    memberInput = "";
    formError = null;
    showForm = true;
  }

  function addMember() {
    const v = memberInput.trim();
    if (v && !formMembers.includes(v)) {
      formMembers = [...formMembers, v];
    }
    memberInput = "";
  }

  function removeMember(id: string) {
    formMembers = formMembers.filter((m) => m !== id);
  }

  async function submitForm() {
    if (!formName.trim()) {
      formError = "请输入分组名称";
      return;
    }
    submitting = true;
    formError = null;
    try {
      if (editingGroup) {
        await api.updateAclGroup(editingGroup.id, {
          name: formName.trim(),
          member_ids: formMembers,
        });
      } else {
        await api.createAclGroup({
          name: formName.trim(),
          kind: formKind,
          member_ids: formMembers,
        });
      }
      showForm = false;
      onrefresh();
    } catch (e) {
      formError = String(e);
    } finally {
      submitting = false;
    }
  }

  async function deleteGroup(g: AclGroup) {
    if (!confirm(`确定删除权限组「${g.name}」？`)) return;
    try {
      await api.deleteAclGroup(g.id);
      onrefresh();
    } catch (e) {
      alert(String(e));
    }
  }
</script>

<div class="group-mgr">
  <div class="group-header">
    <h3>权限组</h3>
    <button class="btn-sm" onclick={openCreate}>＋ 新建</button>
  </div>

  {#if groups.length === 0}
    <p class="empty">暂无权限组</p>
  {:else}
    <div class="group-list">
      {#each groups as g (g.id)}
        <div class="group-card">
          <div class="group-info">
            <span class="group-name">{g.name}</span>
            <span class="group-kind">{g.kind === "device" ? "设备组" : "用户组"}</span>
            <span class="group-count">{g.member_ids.length} 个成员</span>
          </div>
          <div class="group-actions">
            <button class="icon-btn" onclick={() => openEdit(g)} title="编辑">✎</button>
            <button class="icon-btn danger" onclick={() => deleteGroup(g)} title="删除">✕</button>
          </div>
        </div>
      {/each}
    </div>
  {/if}
</div>

{#if showForm}
  <!-- svelte-ignore a11y_click_events_have_key_events -->
  <!-- svelte-ignore a11y_no_static_element_interactions -->
  <div class="backdrop" onclick={() => (showForm = false)}>
    <div class="dialog" onclick={(e) => e.stopPropagation()}>
      <h2>{editingGroup ? "编辑权限组" : "新建权限组"}</h2>

      <label class="field">
        <span>名称</span>
        <input type="text" bind:value={formName} placeholder="如：朋友" disabled={submitting} />
      </label>

      {#if !editingGroup}
        <label class="field">
          <span>类型</span>
          <select bind:value={formKind} disabled={submitting}>
            <option value="device">设备组</option>
            <option value="user">用户组</option>
          </select>
        </label>
      {/if}

      <label class="field">
        <span>成员 ID</span>
        <div class="member-input-row">
          <input
            type="text"
            bind:value={memberInput}
            placeholder="输入设备 ID 或用户 ID"
            disabled={submitting}
            onkeydown={(e) => e.key === "Enter" && (e.preventDefault(), addMember())}
          />
          <button class="btn-sm" onclick={addMember} disabled={submitting}>添加</button>
        </div>
      </label>

      {#if formMembers.length > 0}
        <div class="member-tags">
          {#each formMembers as m}
            <span class="tag">
              {m}
              <button class="tag-del" onclick={() => removeMember(m)}>×</button>
            </span>
          {/each}
        </div>
      {/if}

      {#if formError}
        <p class="form-error">{formError}</p>
      {/if}

      <div class="dialog-actions">
        <button class="btn-sm" onclick={() => (showForm = false)} disabled={submitting}>取消</button>
        <button class="btn-sm primary" onclick={submitForm} disabled={submitting}>
          {submitting ? "保存中…" : "保存"}
        </button>
      </div>
    </div>
  </div>
{/if}

<style>
  .group-mgr {
    margin-bottom: 24px;
  }
  .group-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12px;
  }
  .group-header h3 {
    font-size: 0.95rem;
    margin: 0;
  }
  .empty {
    color: var(--text-secondary);
    font-size: 0.85rem;
    padding: 12px 0;
  }
  .group-list {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .group-card {
    display: flex;
    align-items: center;
    justify-content: space-between;
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 10px 12px;
  }
  .group-info {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 0.85rem;
  }
  .group-name {
    font-weight: 600;
  }
  .group-kind {
    color: var(--text-secondary);
    font-size: 0.75rem;
    background: rgba(255, 255, 255, 0.05);
    padding: 1px 6px;
    border-radius: 3px;
  }
  .group-count {
    color: var(--text-secondary);
    font-size: 0.75rem;
  }
  .group-actions {
    display: flex;
    gap: 4px;
  }
  .icon-btn {
    background: none;
    border: none;
    color: var(--text-secondary);
    cursor: pointer;
    font-size: 1rem;
    padding: 4px;
  }
  .icon-btn:hover {
    color: var(--primary);
  }
  .icon-btn.danger:hover {
    color: var(--error);
  }

  /* Dialog */
  .backdrop {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.5);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 100;
  }
  .dialog {
    background: var(--bg);
    border-radius: 12px;
    padding: 24px;
    min-width: 380px;
    max-width: 480px;
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
  }
  .dialog h2 {
    margin: 0 0 16px;
    font-size: 1.1rem;
  }
  .field {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin-bottom: 12px;
    font-size: 0.85rem;
  }
  .field span {
    color: var(--text-secondary);
  }
  .field input, .field select {
    padding: 8px;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--bg-card);
    color: var(--text);
    font-size: 0.85rem;
  }
  .member-input-row {
    display: flex;
    gap: 6px;
  }
  .member-input-row input {
    flex: 1;
  }
  .member-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: 12px;
  }
  .tag {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    background: rgba(59, 130, 246, 0.15);
    color: var(--primary);
    padding: 3px 8px;
    border-radius: 4px;
    font-size: 0.8rem;
  }
  .tag-del {
    background: none;
    border: none;
    color: var(--text-secondary);
    cursor: pointer;
    font-size: 0.9rem;
    padding: 0 2px;
  }
  .tag-del:hover {
    color: var(--error);
  }
  .form-error {
    color: var(--error);
    font-size: 0.8rem;
    margin-bottom: 8px;
  }
  .dialog-actions {
    display: flex;
    gap: 8px;
    justify-content: flex-end;
    margin-top: 16px;
  }
  .btn-sm {
    padding: 6px 12px;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: transparent;
    color: var(--text);
    cursor: pointer;
    font-size: 0.8rem;
  }
  .btn-sm:hover {
    background: rgba(255, 255, 255, 0.05);
  }
  .btn-sm.primary {
    background: var(--primary);
    border-color: var(--primary);
    color: white;
  }
  .btn-sm.primary:hover {
    opacity: 0.9;
  }
  .btn-sm:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
</style>