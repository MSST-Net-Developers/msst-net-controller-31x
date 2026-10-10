<script lang="ts">
  // Interactive authorization.  Requests and their deadlines come from
  // Core; Core enforces the decision and the timeout.  The dialog never
  // decides on its own (3.0.x auto-sent "deny once" when its local timer ran
  // out; now an unanswered request simply times out in Core).
  import { onDestroy } from 'svelte';
  import { ipc, errorMessage } from '$lib/api/client';
  import { R, type AuthDecision } from '$lib/api/requests';
  import { controller } from '$lib/controller.svelte';

  const queue = $derived(controller.connected ? (controller.state?.pending_auth ?? []) : []);
  const req = $derived(queue[0] ?? null);

  let now = $state(Date.now());
  const tick = setInterval(() => (now = Date.now()), 500);
  onDestroy(() => clearInterval(tick));

  const remaining = $derived(req?.expires_at_ms != null ? Math.max(0, Math.ceil((req.expires_at_ms - now) / 1000)) : null);
  const total = $derived(req?.expires_in_secs ?? 60);

  let busyId = $state<string | null>(null);
  let error = $state('');
  let errorFor = $state<string | null>(null);

  async function decide(decision: AuthDecision) {
    if (!req || busyId) return;
    const id = req.request_id;
    busyId = id;
    error = '';
    try {
      await ipc(R.authDecision(id, decision));
      // The request leaves the queue when Core's pending list no longer has it.
    } catch (e) {
      error = errorMessage(e);
      errorFor = id;
    } finally {
      busyId = null;
    }
  }
</script>

{#if req}
  <div class="modal-backdrop auth-backdrop">
    <div class="modal auth-modal">
      <div class="auth-icon">🔐</div>
      <h2 class="modal-title auth-title">访问请求
        {#if queue.length > 1}<span class="auth-queue-badge">{queue.length}</span>{/if}
      </h2>

      <div class="auth-info">
        <p class="auth-who">
          {#if req.src_user_display_name || req.src_user_id}
            <b>{req.src_user_display_name ?? req.src_user_id}</b> 的设备
          {:else}
            设备
          {/if}
          <b>{req.src_device_display_name ?? req.src_device_id}</b>
        </p>
        {#if req.src_device_display_name}<p class="inline-note" style="user-select:text">{req.src_device_id}</p>{/if}
        {#if req.service_name}
          <p class="auth-what">想访问您的 <b>{req.service_name}</b> 服务（{req.proto.toUpperCase()}/{req.dst_port}）</p>
        {:else}
          <p class="auth-what">想访问端口 <b>{req.proto.toUpperCase()}/{req.dst_port}</b></p>
        {/if}
        <p class="auth-what">是否允许访问？</p>
      </div>

      <div class="auth-countdown">
        {#if remaining != null}
          <div class="countdown-bar"
            style="width:{Math.min(100, (remaining / total) * 100)}%;background:{remaining < 15 ? 'var(--error)' : remaining < 30 ? 'var(--warning)' : 'var(--primary)'}"></div>
          <span class="countdown-text" class:countdown-urgent={remaining < 15}>
            {remaining > 0 ? `未处理将在 ${remaining}s 后自动拒绝` : '已超时，等待客户端核心确认…'}
          </span>
        {:else}
          <span class="countdown-text">正在读取剩余时间…</span>
        {/if}
      </div>

      {#if error && errorFor === req.request_id}<p class="modal-error">{error}</p>{/if}

      <div class="auth-actions">
        <button class="auth-btn allow" disabled={!!busyId} onclick={() => decide('allow_once')}>允许</button>
        <button class="auth-btn always-allow" disabled={!!busyId} onclick={() => decide('always_allow')}>始终允许</button>
        <button class="auth-btn always-deny" disabled={!!busyId} onclick={() => decide('always_deny')}>始终拒绝</button>
        <button class="auth-btn deny" disabled={!!busyId} onclick={() => decide('deny_once')}>拒绝</button>
      </div>
      <p class="inline-note" style="text-align:center">「允许」「拒绝」仅在 10 分钟内有效；「始终」会创建永久规则，可在「防御」中管理。</p>
    </div>
  </div>
{/if}
