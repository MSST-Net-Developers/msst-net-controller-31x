<script lang="ts">
  import Icon from '$lib/icons';
  import { ipc, errorMessage, openExternal } from '$lib/api/client';
  import { R } from '$lib/api/requests';
  import { controller } from '$lib/controller.svelte';
  import { copyText } from '$lib/util';
  import CreateRoomDialog from '$lib/dialogs/CreateRoomDialog.svelte';
  import JoinRoomDialog from '$lib/dialogs/JoinRoomDialog.svelte';
  import OidcDialog from '$lib/dialogs/OidcDialog.svelte';

  const health = $derived(controller.health);
  const room = $derived(controller.state?.room ?? null);
  const inRoom = $derived(!!room?.room_id);
  const nodes = $derived(controller.state?.nodes ?? {});

  let showCreate = $state(false);
  let showJoin = $state(false);
  let showAuth = $state(false);
  let notReadyToast = $state(false);
  let toastTimer: ReturnType<typeof setTimeout>;

  let leaving = $state(false);
  let leaveError = $state('');

  let copied = $state<string | null>(null);
  let copiedTimer: ReturnType<typeof setTimeout>;
  async function copy(key: string, text: string) {
    if (await copyText(text)) {
      copied = key;
      clearTimeout(copiedTimer);
      copiedTimer = setTimeout(() => (copied = null), 2000);
    }
  }

  const deviceMnemonic = $derived(
    health?.device_handle && health?.user_handle
      ? `${health.device_handle}.${health.user_handle}`
      : (health?.device_handle || health?.device_id || ''),
  );
  const userMnemonic = $derived(health?.user_handle || health?.user_id || '');

  function requireCore(open: () => void) {
    if (!controller.coreReady) {
      notReadyToast = true;
      clearTimeout(toastTimer);
      toastTimer = setTimeout(() => (notReadyToast = false), 2500);
      return;
    }
    open();
  }

  async function leaveRoom() {
    if (!room?.room_id) return;
    leaving = true;
    leaveError = '';
    try {
      await ipc(R.leaveRoom(room.room_id));
      // The room view disappears once Core's room state says so.
    } catch (e) {
      leaveError = errorMessage(e);
    } finally {
      leaving = false;
    }
  }

  function pathLabel(mode: string | null): string | null {
    if (!mode) return null;
    if (mode === 'relay') return '中转';
    if (mode === 'probing') return '探测直连中';
    if (mode.startsWith('direct')) return '直连';
    return mode;
  }
</script>

{#if inRoom && room}
  <section class="peer-list-section">
    {#if room.peers.length === 0}
      <div class="peer-empty">暂无成员信息</div>
    {:else}
      <ul class="peer-list">
        {#each room.peers as peer (peer.device_id)}
          {@const self = peer.device_id === health?.device_id}
          {@const path = self ? null : pathLabel(nodes[peer.device_id]?.path_mode ?? null)}
          <li class="peer-card">
            <div class="peer-icon"><Icon icon="mdi:monitor" width={18} height={18} /></div>
            <div class="peer-info">
              <span class="peer-name">{peer.display_name ?? peer.device_id}{self ? '（本机）' : ''}</span>
              {#if peer.display_name}<span class="peer-device-id">{peer.device_id}</span>{/if}
              {#if peer.hostname}<span class="peer-hostname">{peer.hostname}</span>{/if}
              {#if peer.ipv4}<span class="peer-ip">IPv4 <b>{peer.ipv4}</b></span>{/if}
              {#if !self}
                <span class="peer-path">路径：{#if path}{path}{:else}<span class="unknown-status">未知</span>{/if}</span>
              {/if}
            </div>
            {#if peer.hostname}
              <button class="copy-hostname-btn" class:copied={copied === peer.device_id} title="复制主机名"
                onclick={() => copy(peer.device_id, peer.hostname!)}>
                <Icon icon={copied === peer.device_id ? 'mdi:check' : 'mdi:content-copy'} width={15} height={15} />
              </button>
            {/if}
          </li>
        {/each}
      </ul>
    {/if}
  </section>
  <footer class="room-footer">
    {#if leaveError}<p class="inline-error">退出失败：{leaveError}</p>{/if}
    <button class="leave-btn" onclick={leaveRoom} disabled={leaving || !controller.connected}>
      {leaving ? '退出中…' : '退出房间'}
    </button>
  </footer>
{:else}
  <div class="home-page">
    <div class="action-area">
      <div class="mode-group">
        <div class="mode-group-hdr">
          <span class="mode-group-icon"><Icon icon="mdi:account-multiple-outline" width={18} height={18} /></span>
          <span class="mode-group-title">使用房间模式（推荐）</span>
        </div>
        {#if health && !health.is_authenticated && controller.coreReady}
          <p class="mode-group-notice"><Icon icon="mdi:information-outline" width={14} height={14} />当前以免认证身份接入，仅可使用房间模式。</p>
        {/if}
        <div class="btn-group">
          <button class="action-btn primary" class:unavailable={!controller.coreReady}
            onclick={() => requireCore(() => (showCreate = true))}>
            <span class="btn-icon"><Icon icon="mdi:plus" width={22} height={22} /></span>创建房间
          </button>
          <button class="action-btn secondary" class:unavailable={!controller.coreReady}
            onclick={() => requireCore(() => (showJoin = true))}>
            <span class="btn-icon"><Icon icon="mdi:login" width={22} height={22} /></span>加入房间
          </button>
        </div>
      </div>

      {#if health?.is_authenticated}
        <div class="mode-group mode-group-lan">
          <div class="mode-group-hdr">
            <span class="mode-group-icon"><Icon icon="mdi:lan" width={18} height={18} /></span>
            <span class="mode-group-title mode-group-title-lan">使用大内网模式</span>
          </div>
          <div class="lan-section">
            <p class="lan-section-label">作为客户端</p>
            {#if deviceMnemonic}
              <div class="lan-id-row">
                <span class="lan-id-key">设备</span>
                <span class="hostname-value">{deviceMnemonic}</span>
                <button class="hostname-copy-btn" class:copied={copied === 'dev'} onclick={() => copy('dev', deviceMnemonic)} title="复制设备标识">
                  <Icon icon={copied === 'dev' ? 'mdi:check' : 'mdi:content-copy'} width={13} height={13} />
                </button>
              </div>
              <div class="lan-id-row">
                <span class="lan-id-key">用户</span>
                <span class="hostname-value">{userMnemonic}</span>
                <button class="hostname-copy-btn" class:copied={copied === 'usr'} onclick={() => copy('usr', userMnemonic)} title="复制用户标识">
                  <Icon icon={copied === 'usr' ? 'mdi:check' : 'mdi:content-copy'} width={13} height={13} />
                </button>
              </div>
            {:else}
              <p class="hostname-pending">核心就绪后自动加载</p>
            {/if}
          </div>
          <div class="lan-section">
            <p class="lan-section-label">作为服务器</p>
            {#if health.device_hostname}
              <div class="hostname-row">
                <span class="hostname-value">{health.device_hostname}</span>
                <button class="hostname-copy-btn" class:copied={copied === 'host'} onclick={() => copy('host', health.device_hostname!)} title="复制主机名">
                  <Icon icon={copied === 'host' ? 'mdi:check' : 'mdi:content-copy'} width={13} height={13} />
                </button>
              </div>
            {:else}
              <p class="hostname-pending">ezPlay 主机名（.ezplay.msst.net）将在首次成功连接协调服务器后自动分配</p>
            {/if}
          </div>
        </div>
      {/if}

      {#if notReadyToast}
        <p class="core-unavailable-hint">客户端核心未就绪，无法创建或加入房间</p>
      {/if}
      <!-- svelte-ignore a11y_invalid_attribute -->
      <a href="#" class="home-docs-link" onclick={(e) => { e.preventDefault(); openExternal('https://msst-net.msst2031.cn'); }}><Icon icon="mdi:book-open-outline" width={14} height={14} />项目官网 · 使用说明</a>
    </div>
    <footer class="status-bar">
      <span class="status-label">客户端核心状态：</span>
      <span class="indicator {controller.status}"></span>
      <span class="status-text {controller.status}">{controller.statusLabel}</span>
      <button class="refresh-btn" class:spinning={controller.retrying} onclick={() => controller.retry()} disabled={controller.retrying}
        title={controller.connected ? '重新读取客户端核心状态' : '立即重试连接客户端核心'}><Icon icon="mdi:refresh" width={17} height={17} /></button>
    </footer>
    <button class="auth-fab" onclick={() => (showAuth = true)} title="接入认证" aria-label="接入认证"><Icon icon="mdi:account-key-outline" width={22} height={22} /></button>
  </div>
{/if}

{#if showCreate}<CreateRoomDialog onclose={() => (showCreate = false)} />{/if}
{#if showJoin}<JoinRoomDialog onclose={() => (showJoin = false)} />{/if}
{#if showAuth}<OidcDialog onclose={() => (showAuth = false)} />{/if}
