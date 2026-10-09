<script lang="ts">
  import {
    createConnectionStore,
    createStateStore,
    createAuthStore,
    startWebSocket,
    stopWebSocket,
  } from "$lib/stores";
  import StatusIndicator from "$lib/components/StatusIndicator.svelte";
  import AuthDialog from "$lib/components/AuthDialog.svelte";
  import { onMount } from "svelte";

  let { children } = $props();

  const conn = createConnectionStore();
  const state = createStateStore();
  const auth = createAuthStore();

  // Make stores available to child pages via context
  import { setContext } from "svelte";
  setContext("conn", conn);
  setContext("state", state);
  setContext("auth", auth);

  let showAuthDialog = $derived(auth.hasRequests);
  let activeTab = $state<"home" | "nodes" | "room" | "acl" | "settings">("home");

  onMount(() => {
    conn.refresh();
    state.refresh();
    auth.refresh();
    startWebSocket(state, auth);

    // Periodic connection status poll
    const interval = setInterval(() => conn.refresh(), 5000);

    return () => {
      clearInterval(interval);
      stopWebSocket();
    };
  });
</script>

<div class="app">
  <!-- Title bar -->
  <header class="titlebar">
    <div class="titlebar-drag">
      <span class="logo">MSST-Net</span>
      <span class="subtitle">控制器</span>
    </div>
    <div class="titlebar-status">
      <StatusIndicator status={conn.status} />
    </div>
  </header>

  <!-- Navigation -->
  <nav class="tabs">
    <button class:active={activeTab === "home"} onclick={() => (activeTab = "home")}>
      首页
    </button>
    <button class:active={activeTab === "nodes"} onclick={() => (activeTab = "nodes")}>
      节点
    </button>
    <button class:active={activeTab === "room"} onclick={() => (activeTab = "room")}>
      房间
    </button>
    <button class:active={activeTab === "acl"} onclick={() => (activeTab = "acl")}>
      防御
    </button>
    <button class:active={activeTab === "settings"} onclick={() => (activeTab = "settings")}>
      设置
    </button>
  </nav>

  <!-- Content -->
  <main class="content">
    {#if activeTab === "home"}
      {#if state.loading}
        <p class="loading">加载中…</p>
      {:else if state.error}
        <p class="error">{state.error}</p>
      {:else if state.state}
        <section class="home-grid">
          <div class="card">
            <h3>健康状态</h3>
            <p>状态: <strong>{state.state.health.status}</strong></p>
            <p>已认证: {state.state.health.is_authenticated ? "是" : "否"}</p>
            <p>协调服务器: {state.state.health.ws_connected ? "已连接" : "未连接"}</p>
            {#if state.state.health.user_display_name}
              <p>用户: {state.state.health.user_display_name}</p>
            {/if}
          </div>

          <div class="card">
            <h3>节点</h3>
            <p>{Object.keys(state.state.nodes).length} 个已知节点</p>
          </div>

          <div class="card">
            <h3>房间</h3>
            {#if state.state.room.room_id}
              <p>{state.state.room.room_name ?? "当前房间"}</p>
              <p>{state.state.room.peers.length} 个成员</p>
            {:else}
              <p>未加入房间</p>
            {/if}
          </div>

          <div class="card">
            <h3>规则</h3>
            <p>{state.state.acl_rules.length} 条防火墙规则</p>
          </div>
        </section>
      {:else}
        <p class="loading">等待 Core 连接…</p>
      {/if}

    {:else if activeTab === "nodes"}
      <h2>节点列表</h2>
      {#if state.state}
        {@const nodes = Object.values(state.state.nodes)}
        {#if nodes.length === 0}
          <p class="empty">暂无已知节点</p>
        {:else}
          <div class="node-grid">
            {#each nodes as node (node.device_id)}
              <div class="node-card">
                <div class="node-name">
                  {node.display_name ?? node.device_id}
                </div>
                {#if node.hostname}
                  <div class="node-hostname">{node.hostname}</div>
                {/if}
                <div class="node-addr">{node.ipv4}</div>
                {#if node.path_mode}
                  <div class="node-path">路径: {node.path_mode}</div>
                {/if}
              </div>
            {/each}
          </div>
        {/if}
      {/if}

    {:else if activeTab === "room"}
      <h2>房间管理</h2>
      {#if state.state?.room.room_id}
        <div class="room-active">
          <h3>{state.state.room.room_name ?? "当前房间"}</h3>
          <p class="room-id">房间号: {state.state.room.room_id}</p>
          {#if state.state.room.relay}
            <p class="room-relay">中转: {state.state.room.relay}</p>
          {/if}
          <h4>成员 ({state.state.room.peers.length})</h4>
          {#each state.state.room.peers as peer (peer.device_id)}
            <div class="peer-item">
              {peer.display_name ?? peer.device_id}
              {#if peer.hostname}
                <span class="peer-hostname">({peer.hostname})</span>
              {/if}
            </div>
          {/each}
          <button class="btn" onclick={() => state.state?.room.room_id && api.leaveRoom(state.state.room.room_id).then(() => state.refresh())}>
            退出房间
          </button>
        </div>
      {:else}
        <div class="room-actions">
          <h3>创建房间</h3>
          <CreateRoomForm onsuccess={() => state.refresh()} />

          <h3>加入房间</h3>
          <JoinRoomForm onsuccess={() => state.refresh()} />
        </div>
      {/if}

    {:else if activeTab === "acl"}
      <h2>防火墙规则</h2>
      {#if state.state}
        {@const rules = state.state.acl_rules}
        {#if rules.length === 0}
          <p class="empty">暂无规则</p>
        {:else}
          <table class="rules-table">
            <thead>
              <tr>
                <th>启用</th>
                <th>动作</th>
                <th>方向</th>
                <th>备注</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {#each rules as rule (rule.id)}
                <tr class:disabled={!rule.enabled}>
                  <td>
                    <input type="checkbox" checked={rule.enabled}
                      onchange={() => api.updateAclRule(rule.id, { enabled: !rule.enabled }).then(() => state.refresh())} />
                  </td>
                  <td>
                    <span class="badge" class:allow={rule.action === "allow"} class:deny={rule.action !== "allow"}>
                      {rule.action === "allow" ? "允许" : "拒绝"}
                    </span>
                  </td>
                  <td>{rule.direction === "inbound" ? "入站" : "出站"}</td>
                  <td>{rule.comment ?? "—"}</td>
                  <td>
                    <button class="icon-btn" onclick={() => api.deleteAclRule(rule.id).then(() => state.refresh())} title="删除">
                      ✕
                    </button>
                  </td>
                </tr>
              {/each}
            </tbody>
          </table>
        {/if}
      {/if}

    {:else if activeTab === "settings"}
      <h2>设置</h2>
      {#if state.state}
        <div class="settings-section">
          <h3>设备标识</h3>
          <p>设备 ID: <code>{state.state.health.device_id ?? "—"}</code></p>
          <p>用户 ID: <code>{state.state.health.user_id ?? "—"}</code></p>
          {#if state.state.health.device_hostname}
            <p>主机名: <code>{state.state.health.device_hostname}</code></p>
          {/if}
        </div>
      {/if}
    {/if}
  </main>
</div>

<!-- Auth dialog overlay -->
{#if showAuthDialog && auth.requests.length > 0}
  <AuthDialog
    request={auth.requests[0]}
    onclose={() => {
      if (auth.requests.length <= 1) showAuthDialog = false;
    }}
  />
{/if}

<script module lang="ts">
  import { api } from "$lib/api/client";

  // ── Inline sub-components (room forms) ──

  function CreateRoomForm({ onsuccess }: { onsuccess: () => void }) {
    let name = $state("");
    let password = $state("");
    let submitting = $state(false);
    let error = $state<string | null>(null);

    async function submit() {
      if (!name.trim()) return;
      submitting = true;
      error = null;
      try {
        await api.createRoom(name.trim(), password || undefined);
        onsuccess();
      } catch (e) {
        error = String(e);
      } finally {
        submitting = false;
      }
    }

    return { name, password, submitting, error, submit };
  }

  function JoinRoomForm({ onsuccess }: { onsuccess: () => void }) {
    let roomId = $state("");
    let password = $state("");
    let submitting = $state(false);
    let error = $state<string | null>(null);

    async function submit() {
      if (!roomId.trim()) return;
      submitting = true;
      error = null;
      try {
        await api.joinRoom(roomId.trim(), password || undefined);
        onsuccess();
      } catch (e) {
        error = String(e);
      } finally {
        submitting = false;
      }
    }

    return { roomId, password, submitting, error, submit };
  }
</script>

<style>
  :global(*) {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
  }
  :global(body) {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    background: var(--bg, #0f0f13);
    color: var(--text, #e0e0e0);
    --ok: #22c55e;
    --warn: #f59e0b;
    --error: #ef4444;
    --primary: #3b82f6;
    --bg: #0f0f13;
    --bg-card: #1a1a24;
    --text: #e0e0e0;
    --text-secondary: #888;
    --border: #2a2a3a;
  }

  .app {
    display: flex;
    flex-direction: column;
    height: 100vh;
  }

  .titlebar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8px 16px;
    background: var(--bg-card);
    border-bottom: 1px solid var(--border);
    -webkit-app-region: drag;
  }
  .titlebar-drag {
    display: flex;
    align-items: baseline;
    gap: 8px;
  }
  .logo {
    font-weight: 700;
    font-size: 1rem;
  }
  .subtitle {
    font-size: 0.8rem;
    color: var(--text-secondary);
  }

  .tabs {
    display: flex;
    gap: 2px;
    padding: 4px 16px;
    background: var(--bg-card);
    border-bottom: 1px solid var(--border);
  }
  .tabs button {
    padding: 8px 16px;
    border: none;
    background: transparent;
    color: var(--text-secondary);
    cursor: pointer;
    border-radius: 6px 6px 0 0;
    font-size: 0.85rem;
  }
  .tabs button.active {
    background: var(--bg);
    color: var(--text);
  }
  .tabs button:hover {
    color: var(--text);
  }

  .content {
    flex: 1;
    overflow-y: auto;
    padding: 16px;
  }

  h2 {
    font-size: 1.1rem;
    margin-bottom: 16px;
  }

  .loading, .empty, .error {
    text-align: center;
    padding: 32px;
    color: var(--text-secondary);
  }
  .error {
    color: var(--error);
  }

  .home-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
    gap: 12px;
  }
  .card {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 16px;
  }
  .card h3 {
    font-size: 0.9rem;
    color: var(--text-secondary);
    margin-bottom: 8px;
  }
  .card p {
    font-size: 0.85rem;
    line-height: 1.6;
  }

  .node-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
    gap: 12px;
  }
  .node-card {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 12px;
  }
  .node-name {
    font-weight: 600;
    margin-bottom: 4px;
  }
  .node-hostname {
    font-size: 0.8rem;
    color: var(--text-secondary);
  }
  .node-addr {
    font-family: monospace;
    font-size: 0.8rem;
    color: var(--primary);
  }
  .node-path {
    font-size: 0.75rem;
    color: var(--text-secondary);
    margin-top: 4px;
  }

  .room-active, .room-actions {
    max-width: 480px;
  }
  .room-active h3 {
    margin-bottom: 8px;
  }
  .room-id, .room-relay {
    font-size: 0.85rem;
    color: var(--text-secondary);
    margin-bottom: 8px;
  }
  .peer-item {
    padding: 6px 0;
    font-size: 0.85rem;
    border-bottom: 1px solid var(--border);
  }
  .peer-hostname {
    color: var(--text-secondary);
    font-size: 0.8rem;
  }

  .rules-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.85rem;
  }
  .rules-table th, .rules-table td {
    padding: 8px 12px;
    text-align: left;
    border-bottom: 1px solid var(--border);
  }
  .rules-table th {
    color: var(--text-secondary);
    font-weight: 500;
  }
  .rules-table tr.disabled {
    opacity: 0.5;
  }
  .badge {
    display: inline-block;
    padding: 2px 8px;
    border-radius: 4px;
    font-size: 0.75rem;
    font-weight: 600;
  }
  .badge.allow {
    background: rgba(34, 197, 94, 0.15);
    color: var(--ok);
  }
  .badge.deny {
    background: rgba(239, 68, 68, 0.15);
    color: var(--error);
  }

  .btn {
    padding: 8px 16px;
    border: none;
    border-radius: 6px;
    background: var(--primary);
    color: white;
    cursor: pointer;
    font-size: 0.85rem;
    margin-top: 12px;
  }
  .btn:hover {
    opacity: 0.9;
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
    color: var(--error);
  }

  .settings-section {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 16px;
    margin-bottom: 12px;
  }
  .settings-section h3 {
    font-size: 0.9rem;
    color: var(--text-secondary);
    margin-bottom: 8px;
  }
  .settings-section code {
    font-family: monospace;
    font-size: 0.8rem;
    background: rgba(255, 255, 255, 0.05);
    padding: 2px 6px;
    border-radius: 3px;
  }
</style>