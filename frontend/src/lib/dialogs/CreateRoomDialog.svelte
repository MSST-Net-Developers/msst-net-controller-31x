<script lang="ts">
  import Modal from '$lib/components/Modal.svelte';
  import { ipc, errorMessage } from '$lib/api/client';
  import { R } from '$lib/api/requests';

  let { onclose }: { onclose: () => void } = $props();

  let name = $state('');
  let hasPassword = $state(false);
  let password = $state('');
  let phase = $state<'idle' | 'submitting' | 'success' | 'error'>('idle');
  let error = $state('');

  async function submit() {
    if (!name.trim() || phase === 'submitting') return;
    phase = 'submitting';
    error = '';
    try {
      // Core answers once the room is created and joined; the room view
      // itself comes from Core's room state.
      await ipc(R.createRoom(name.trim(), hasPassword ? password.trim() || null : null));
      phase = 'success';
      setTimeout(onclose, 800);
    } catch (e) {
      phase = 'error';
      error = errorMessage(e);
    }
  }
</script>

<Modal {onclose} busy={phase === 'submitting'}>
  <h2 class="modal-title">创建房间</h2>
  {#if phase === 'success'}
    <div class="modal-feedback success">✓ 房间创建成功</div>
  {:else}
    <label class="field"><span>房间名</span>
      <input type="text" bind:value={name} placeholder="请输入房间名" disabled={phase === 'submitting'}
        onkeydown={(e) => e.key === 'Enter' && submit()} /></label>
    <label class="room-pw-toggle">
      <input type="checkbox" bind:checked={hasPassword} disabled={phase === 'submitting'} />
      <span>设置房间密码</span>
    </label>
    {#if hasPassword}
      <label class="field"><span>房间密码</span>
        <input type="password" bind:value={password} placeholder="留空则无密码保护" disabled={phase === 'submitting'} /></label>
    {/if}
    {#if phase === 'error'}<p class="modal-error">{error}</p>{/if}
    <div class="modal-actions">
      <button class="modal-btn cancel" onclick={onclose} disabled={phase === 'submitting'}>取消</button>
      <button class="modal-btn confirm" class:submitting={phase === 'submitting'} onclick={submit}
        disabled={phase === 'submitting' || !name.trim()}>
        {phase === 'submitting' ? '创建中…' : '创建'}
      </button>
    </div>
  {/if}
</Modal>
