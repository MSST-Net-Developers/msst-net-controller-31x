<script lang="ts">
  import { onMount } from 'svelte';
  import { isTauri, webui } from '$lib/api/client';
  import { controller } from '$lib/controller.svelte';
  import { copyText } from '$lib/util';
  import CoreBanner from '$lib/components/CoreBanner.svelte';
  import AccessRequestDialog from '$lib/dialogs/AccessRequestDialog.svelte';
  import HomeTab from '$lib/tabs/HomeTab.svelte';
  import DefenseTab from '$lib/tabs/DefenseTab.svelte';
  import SettingsTab from '$lib/tabs/SettingsTab.svelte';
  import DnsTab from '$lib/tabs/DnsTab.svelte';
  import AboutTab from '$lib/tabs/AboutTab.svelte';

  let { onlogout }: { onlogout?: () => void } = $props();

  type Tab = 'home' | 'defense' | 'settings' | 'about' | 'dns';
  let tab = $state<Tab>('home');

  const room = $derived(controller.state?.room ?? null);
  const inRoom = $derived(!!room?.room_id);
  let roomIdCopied = $state(false);

  onMount(() => controller.start());

  async function win(action: 'drag' | 'min' | 'close') {
    if (!isTauri) return;
    const { getCurrentWindow } = await import('@tauri-apps/api/window');
    const w = getCurrentWindow();
    try {
      if (action === 'drag') await w.startDragging();
      else if (action === 'min') await w.minimize();
      else await w.close();
    } catch {
      /* window API unavailable */
    }
  }

  async function logout() {
    try {
      await webui.logout();
    } finally {
      onlogout?.();
    }
  }
</script>

<div class="app" class:room-view={inRoom}>
  <nav class="tabs">
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <div class="tab-logo" onmousedown={() => win('drag')}>
      <span class="tab-logo-icon">⚡</span>
      <span class="tab-logo-text">MSST-Net</span>
    </div>
    <button class="tab-btn" class:active={tab === 'home'} onclick={() => (tab = 'home')}><span class="tab-icon">🏠</span><span class="tab-text">首页</span></button>
    <button class="tab-btn" class:active={tab === 'defense'} onclick={() => (tab = 'defense')}><span class="tab-icon">🛡️</span><span class="tab-text">防御</span></button>
    <button class="tab-btn" class:active={tab === 'settings'} onclick={() => (tab = 'settings')}><span class="tab-icon">⚙️</span><span class="tab-text">设置</span></button>
    <button class="tab-btn" class:active={tab === 'about'} onclick={() => (tab = 'about')}><span class="tab-icon">ℹ️</span><span class="tab-text">关于</span></button>
    <button class="tab-btn" class:active={tab === 'dns'} onclick={() => (tab = 'dns')}><span class="tab-icon">🌐</span><span class="tab-text">域名</span></button>

    {#if isTauri}
      <div class="win-controls">
        <button class="win-btn minimize" onclick={() => win('min')} title="最小化">
          <svg width="12" height="2" viewBox="0 0 12 2"><line x1="0" y1="1" x2="12" y2="1" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" /></svg>
        </button>
        <button class="win-btn close" onclick={() => win('close')} title="关闭">
          <svg width="11" height="11" viewBox="0 0 11 11"><line x1="1" y1="1" x2="10" y2="10" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" /><line x1="10" y1="1" x2="1" y2="10" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" /></svg>
        </button>
      </div>
    {:else}
      <div class="win-controls"><button class="logout-btn" onclick={logout} title="退出登录">退出</button></div>
    {/if}
  </nav>

  <CoreBanner />

  {#if inRoom && room}
    <div class="room-banner">
      <span class="room-banner-title">{room.room_name || '当前房间'}</span>
      <button class="room-id-chip" class:copied={roomIdCopied} title="点击复制房间号"
        onclick={async () => { roomIdCopied = await copyText(room.room_id!); setTimeout(() => (roomIdCopied = false), 2000); }}>
        {roomIdCopied ? '✓ 已复制' : room.room_id}
      </button>
      {#if room.relay}<span class="inline-note" style="margin-left:8px">中转 {room.relay}</span>{/if}
    </div>
  {/if}

  <main class="content">
    {#if tab === 'home'}<HomeTab />
    {:else if tab === 'defense'}<DefenseTab />
    {:else if tab === 'settings'}<SettingsTab />
    {:else if tab === 'dns'}<DnsTab />
    {:else}<AboutTab />{/if}
  </main>
</div>

<AccessRequestDialog />
