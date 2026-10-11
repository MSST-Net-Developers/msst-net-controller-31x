<script lang="ts">
  // MSST access authentication (OIDC device flow, 3.0.x "接入认证").
  // Success is only shown once Core's Health reports a valid authentication.
  import { onDestroy } from 'svelte';
  import Modal from '$lib/components/Modal.svelte';
  import Icon from '$lib/icons';
  import { ipc, errorMessage, openExternal } from '$lib/api/client';
  import { R } from '$lib/api/requests';
  import { controller } from '$lib/controller.svelte';
  import { copyText } from '$lib/util';
  import type { AuthPollResult, AuthStartResult } from '$lib/types';

  let { onclose }: { onclose: () => void } = $props();

  type Flow = 'idle' | 'starting' | 'pending' | 'verifying' | 'authorized' | 'error';
  let flow = $state<Flow>('idle');
  let sessionId = '';
  let userCode = $state('');
  let verifyUri = $state('');
  let intervalSecs = 5;
  let error = $state('');
  let codeCopied = $state(false);
  let linkCopied = $state(false);
  let pollTimer: ReturnType<typeof setTimeout>;
  let verifyDeadline = 0;

  const health = $derived(controller.health);

  async function start() {
    flow = 'starting';
    error = '';
    try {
      const res = await ipc<AuthStartResult>(R.authStart());
      sessionId = res.session_id;
      userCode = res.user_code;
      verifyUri = res.verification_uri;
      intervalSecs = res.interval ?? 5;
      flow = 'pending';
      openExternal(verifyUri).catch(() => {});
      schedule();
    } catch (e) {
      flow = 'error';
      error = errorMessage(e);
    }
  }

  function schedule() {
    clearTimeout(pollTimer);
    pollTimer = setTimeout(poll, intervalSecs * 1000);
  }

  async function poll() {
    if (flow !== 'pending' || !sessionId) return;
    try {
      const res = await ipc<AuthPollResult>(R.authPoll(sessionId));
      if (flow !== 'pending') return; // cancelled meanwhile
      if (res.pending || res.status === 'authorization_pending' || res.status === 'slow_down') {
        if (res.status === 'slow_down') intervalSecs += 5;
        schedule();
      } else {
        // Core stored the result; wait for Core to confirm via Health.
        flow = 'verifying';
        verifyDeadline = Date.now() + 15000;
      }
    } catch (e) {
      if (flow !== 'pending') return;
      flow = 'error';
      error = errorMessage(e);
    }
  }

  $effect(() => {
    if (flow !== 'verifying') return;
    if (health?.is_authenticated) {
      flow = 'authorized';
      setTimeout(onclose, 1500);
      return;
    }
    const t = setInterval(() => {
      if (flow === 'verifying' && Date.now() > verifyDeadline) {
        flow = 'error';
        error = '认证页面已完成，但客户端核心尚未确认认证结果。请稍后查看首页状态，或重试。';
      }
    }, 1000);
    return () => clearInterval(t);
  });

  function cancel() {
    clearTimeout(pollTimer);
    flow = 'idle';
    sessionId = '';
    onclose();
  }

  onDestroy(() => clearTimeout(pollTimer));
</script>

<Modal onclose={cancel} cls="auth-modal" busy={flow === 'starting'}>
  <div class="auth-modal-icon" class:auth-icon-success={flow === 'authorized'} class:auth-icon-error={flow === 'error'}>
    <Icon icon={flow === 'authorized' ? 'mdi:check-circle-outline' : flow === 'error' ? 'mdi:alert-circle-outline' : 'mdi:key-outline'} width={36} height={36} />
  </div>
  <h2 class="modal-title">接入认证</h2>
  {#if flow !== 'error'}
    <div class="auth-steps">
      <div class="auth-step" class:step-done={flow !== 'idle'}><span class="step-dot"></span><span class="step-label">开始</span></div>
      <div class="auth-step-line" class:step-line-done={flow === 'pending' || flow === 'verifying' || flow === 'authorized'}></div>
      <div class="auth-step" class:step-active={flow === 'pending' || flow === 'verifying'} class:step-done={flow === 'authorized'}>
        <span class="step-dot"></span><span class="step-label">认证中</span>
      </div>
      <div class="auth-step-line" class:step-line-done={flow === 'authorized'}></div>
      <div class="auth-step" class:step-active={flow === 'authorized'}><span class="step-dot"></span><span class="step-label">完成</span></div>
    </div>
  {/if}

  {#if flow === 'idle'}
    {#if health?.is_authenticated}
      <div class="auth-current-user">
        <div class="auth-current-avatar"><Icon icon="mdi:account-check-outline" width={20} height={20} /></div>
        <div class="auth-current-info">
          <span class="auth-current-name">{health.user_display_name || '已认证用户'}</span>
          <span class="auth-current-id">{health.user_id ?? ''}</span>
        </div>
      </div>
      {#if health.auth_expires_at}
        <p class="inline-note">凭据有效期至 {new Date(health.auth_expires_at * 1000).toLocaleString()}</p>
      {/if}
      <p class="auth-hint">如需切换账号，可重新认证（将覆盖当前凭据）。</p>
      <div class="modal-actions">
        <button class="modal-btn confirm" onclick={start} disabled={!controller.coreReady}>重新认证</button>
        <button class="modal-btn cancel" onclick={cancel}>关闭</button>
      </div>
    {:else}
      <p class="auth-hint">通过 MSST 联合认证系统获取接入身份，实现账号统一管理。</p>
      {#if !controller.coreReady}<p class="inline-error">客户端核心未就绪，暂时无法认证。</p>{/if}
      <div class="modal-actions">
        <button class="modal-btn confirm" onclick={start} disabled={!controller.coreReady}>开始认证</button>
        <button class="modal-btn cancel" onclick={cancel}>取消</button>
      </div>
    {/if}
  {:else if flow === 'starting'}
    <div class="auth-spinner"></div>
    <p class="auth-hint">正在获取认证链接…</p>
  {:else if flow === 'pending' || flow === 'verifying'}
    <p class="auth-hint">认证页面已在浏览器中打开，若未自动打开请手动复制链接。</p>
    {#if verifyUri}
      <div class="auth-link-block">
        <span class="auth-code-label">认证链接</span>
        <div class="auth-link-row">
          <span class="auth-link-text">{verifyUri}</span>
          <button class="auth-copy-btn" title="复制链接"
            onclick={async () => { linkCopied = await copyText(verifyUri); setTimeout(() => (linkCopied = false), 1500); }}>
            <Icon icon={linkCopied ? 'mdi:check' : 'mdi:content-copy'} width={14} height={14} />
          </button>
        </div>
      </div>
    {/if}
    {#if userCode}
      <div class="auth-code-block">
        <span class="auth-code-label">验证码</span>
        <div class="auth-code-row">
          <span class="auth-code">{userCode}</span>
          <button class="auth-copy-btn" title="复制验证码"
            onclick={async () => { codeCopied = await copyText(userCode); setTimeout(() => (codeCopied = false), 1500); }}>
            <Icon icon={codeCopied ? 'mdi:check' : 'mdi:content-copy'} width={14} height={14} />
          </button>
        </div>
      </div>
    {/if}
    <p class="auth-polling">{flow === 'verifying' ? '正在等待客户端核心确认…' : '正在等待认证完成…'}</p>
    <div class="modal-actions">
      <button class="modal-btn cancel" onclick={cancel}>取消</button>
    </div>
  {:else if flow === 'authorized'}
    <div class="auth-success-icon"><Icon icon="mdi:check-circle" width={40} height={40} /></div>
    <p class="auth-hint auth-success">认证成功！</p>
  {:else if flow === 'error'}
    <p class="auth-hint auth-error">认证失败：{error}</p>
    <div class="modal-actions">
      <button class="modal-btn confirm" onclick={start} disabled={!controller.coreReady}>重试</button>
      <button class="modal-btn cancel" onclick={cancel}>关闭</button>
    </div>
  {/if}
</Modal>
