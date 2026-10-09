<script lang="ts">
  import type { ConnectionStatus } from "$lib/types";

  let { status }: { status: ConnectionStatus } = $props();

  let label = $derived(
    typeof status === "string"
      ? status === "Connected" ? "已连接"
        : status === "Connecting" ? "连接中…"
        : status === "Disconnected" ? "未连接"
        : "未知"
      : `重连中 (第 ${status.Reconnecting.attempt} 次)`
  );

  let color = $derived(
    typeof status === "string"
      ? status === "Connected" ? "var(--ok)"
        : status === "Connecting" ? "var(--warn)"
        : "var(--error)"
      : "var(--warn)"
  );
</script>

<span class="status" style="--color: {color}">
  <span class="dot"></span>
  {label}
</span>

<style>
  .status {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 0.85rem;
  }
  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--color);
  }
</style>