<script lang="ts">
  import { onMount } from 'svelte';
  import Modal from '$lib/components/Modal.svelte';
  import { ipc, errorMessage, exportLogs } from '$lib/api/client';
  import { R } from '$lib/api/requests';
  import type { LogEntry } from '$lib/types';

  let { onclose }: { onclose: () => void } = $props();

  const LEVELS = ['all', 'error', 'warn', 'info', 'debug', 'trace'];
  let entries = $state<LogEntry[]>([]);
  let level = $state('all');
  let loading = $state(true);
  let error = $state('');
  let note = $state('');

  const filtered = $derived(level === 'all' ? entries : entries.filter((e) => e.level.toLowerCase() === level));

  async function load() {
    loading = true;
    error = '';
    try {
      entries = (await ipc<{ entries: LogEntry[] }>(R.getLogs(2000))).entries ?? [];
    } catch (e) {
      error = errorMessage(e);
    } finally {
      loading = false;
    }
  }

  async function clear() {
    if (!confirm('确定清空客户端核心的日志缓冲区？')) return;
    try {
      await ipc(R.clearLogs());
      await load();
    } catch (e) {
      error = errorMessage(e);
    }
  }

  async function doExport() {
    note = '';
    const text = filtered
      .map((e) => `[${new Date(e.timestamp_ms).toISOString()}] [${e.level.toUpperCase()}] ${e.target}: ${e.message}`)
      .join('\n');
    try {
      if (await exportLogs(text)) note = `已导出 ${filtered.length} 条日志`;
    } catch (e) {
      error = `导出失败：${errorMessage(e)}`;
    }
  }

  onMount(load);
</script>

<Modal {onclose} cls="log-modal">
  <h2 class="modal-title">客户端核心日志</h2>
  <div class="log-toolbar">
    <select bind:value={level} class="log-level-select">
      {#each LEVELS as l}<option value={l}>{l === 'all' ? '全部级别' : l.toUpperCase()}</option>{/each}
    </select>
    <div class="log-toolbar-actions">
      <button class="modal-btn cancel" onclick={load} disabled={loading}>刷新</button>
      <button class="modal-btn cancel" onclick={clear}>清空</button>
      <button class="modal-btn confirm" onclick={doExport} disabled={filtered.length === 0}>导出</button>
    </div>
  </div>
  {#if error}<p class="modal-error">{error}</p>{/if}
  {#if note}<p class="inline-note">{note}</p>{/if}
  <div class="log-box">
    {#each filtered as e, i (i)}
      <div class="log-line log-level-{e.level.toLowerCase()}">
        <span class="log-ts">{new Date(e.timestamp_ms).toISOString().replace('T', ' ').replace('Z', '')}</span>
        <span class="log-lvl">[{e.level.toUpperCase()}]</span>
        <span class="log-target">{e.target}:</span>
        <span class="log-msg">{e.message}</span>
      </div>
    {:else}
      <p class="log-empty">{loading ? '加载中…' : '暂无日志'}</p>
    {/each}
  </div>
  <div class="modal-actions">
    <button class="modal-btn cancel" onclick={onclose}>关闭</button>
  </div>
</Modal>
