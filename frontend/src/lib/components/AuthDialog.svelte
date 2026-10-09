<script lang="ts">
  import type { PendingAuthRequest } from "$lib/types";
  import { api } from "$lib/api/client";

  let {
    request,
    onclose,
  }: {
    request: PendingAuthRequest;
    onclose: () => void;
  } = $props();

  let submitting = $state(false);
  let error = $state<string | null>(null);

  let srcLabel = $derived(
    request.src_user_display_name
      ? `${request.src_user_display_name} (${request.src_device_display_name ?? request.src_device_id})`
      : request.src_device_display_name ?? request.src_device_id
  );

  let serviceLabel = $derived(
    request.service_name ?? `${request.proto}/${request.dst_port}`
  );

  let timeLeft = $state(request.expires_at ?? 60);
  let timer: ReturnType<typeof setInterval>;

  $effect(() => {
    timer = setInterval(() => {
      timeLeft = Math.max(0, timeLeft - 1);
      if (timeLeft <= 0) {
        clearInterval(timer);
        onclose();
      }
    }, 1000);
    return () => clearInterval(timer);
  });

  async function decide(decision: string) {
    submitting = true;
    error = null;
    try {
      await api.submitAuthDecision(request.request_id, decision);
      onclose();
    } catch (e) {
      error = String(e);
      submitting = false;
    }
  }
</script>

<div class="backdrop" onclick={onclose} role="presentation">
  <!-- svelte-ignore a11y_click_events_have_key_events -->
  <!-- svelte-ignore a11y_no_static_element_interactions -->
  <div class="dialog" onclick={(e) => e.stopPropagation()}>
    <h2>访问请求</h2>

    <div class="info">
      <p><strong>{srcLabel}</strong> 想访问您的 <strong>{serviceLabel}</strong> 服务</p>
      <p class="meta">协议: {request.proto} / 端口: {request.dst_port}</p>
      <p class="timer">剩余 {timeLeft} 秒</p>
    </div>

    {#if error}
      <p class="error">{error}</p>
    {/if}

    <div class="actions">
      <button class="btn allow" onclick={() => decide("always_allow")} disabled={submitting}>
        始终允许
      </button>
      <button class="btn allow" onclick={() => decide("allow_once")} disabled={submitting}>
        允许一次
      </button>
      <button class="btn deny" onclick={() => decide("deny_once")} disabled={submitting}>
        拒绝
      </button>
      <button class="btn deny" onclick={() => decide("always_deny")} disabled={submitting}>
        始终拒绝
      </button>
    </div>
  </div>
</div>

<style>
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
    min-width: 360px;
    max-width: 480px;
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
  }
  h2 {
    margin: 0 0 16px;
    font-size: 1.2rem;
  }
  .info {
    margin-bottom: 16px;
    line-height: 1.6;
  }
  .meta {
    font-size: 0.85rem;
    color: var(--text-secondary);
  }
  .timer {
    font-size: 0.85rem;
    color: var(--warn);
    margin-top: 4px;
  }
  .error {
    color: var(--error);
    font-size: 0.85rem;
    margin-bottom: 12px;
  }
  .actions {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
  .btn {
    flex: 1;
    min-width: 80px;
    padding: 8px 12px;
    border: none;
    border-radius: 6px;
    cursor: pointer;
    font-size: 0.85rem;
  }
  .btn.allow {
    background: var(--ok);
    color: white;
  }
  .btn.deny {
    background: var(--error);
    color: white;
  }
  .btn:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
</style>