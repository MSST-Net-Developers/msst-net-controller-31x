<script lang="ts">
  import Modal from '$lib/components/Modal.svelte';
  import { ipc, errorMessage } from '$lib/api/client';
  import { R } from '$lib/api/requests';

  let { onclose }: { onclose: () => void } = $props();

  let roomId = $state('');
  let password = $state('');
  let phase = $state<'idle' | 'submitting' | 'success' | 'error'>('idle');
  let error = $state('');

  async function submit() {
    if (!roomId.trim() || phase === 'submitting') return;
    phase = 'submitting';
    error = '';
    try {
      await ipc(R.joinRoom(roomId.trim(), password.trim() || null));
      phase = 'success';
      setTimeout(onclose, 800);
    } catch (e) {
      phase = 'error';
      error = errorMessage(e);
    }
  }
</script>

<Modal {onclose} busy={phase === 'submitting'}>
  <h2 class="modal-title">加入房间</h2>
  {#if phase === 'success'}
    <div class="modal-feedback success">✓ 已成功加入房间</div>
  {:else}
    <label class="field"><span>房间号</span>
      <input type="text" bind:value={roomId} placeholder="请输入房间号" disabled={phase === 'submitting'} /></label>
    <label class="field"><span>房间密码（可选）</span>
      <input type="password" bind:value={password} placeholder="无密码请留空" disabled={phase === 'submitting'}
        onkeydown={(e) => e.key === 'Enter' && submit()} /></label>
    {#if phase === 'error'}<p class="modal-error">{error}</p>{/if}
    <div class="modal-actions">
      <button class="modal-btn cancel" onclick={onclose} disabled={phase === 'submitting'}>取消</button>
      <button class="modal-btn confirm" class:submitting={phase === 'submitting'} onclick={submit}
        disabled={phase === 'submitting' || !roomId.trim()}>
        {phase === 'submitting' ? '加入中…' : '加入'}
      </button>
    </div>
  {/if}
</Modal>
