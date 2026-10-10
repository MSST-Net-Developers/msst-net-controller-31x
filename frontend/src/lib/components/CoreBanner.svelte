<script lang="ts">
  // Explains why Core data may be missing or stale (AGENTS.md §7.4).
  import { controller } from '$lib/controller.svelte';

  const banner = $derived.by(() => {
    const v = controller.view;
    if (!v) {
      return controller.backendError ? { kind: 'error', text: '无法读取控制器状态', detail: controller.backendError } : null;
    }
    const d = v.diagnostics;
    switch (true) {
      case v.connection === 'Incompatible':
        return {
          kind: 'error',
          text: `客户端核心的协议版本（${v.core_protocol ?? '未知'}）与控制器（${v.controller_protocol}）不兼容，请将两者更新到同一版本`,
          detail: d.last_error,
        };
      case typeof v.connection === 'object':
        return { kind: 'error', text: '未连接到客户端核心，正在重试…以下内容可能已过期', detail: d.last_error };
      case v.connection === 'Disconnected' && d.successful_connects > 0:
        return { kind: 'warn', text: '与客户端核心的连接已断开，正在重连…以下内容可能已过期', detail: d.last_error };
      case v.connection !== 'Connected':
        return null; // first connect; the status bar shows progress
      case v.state.sync.state === 'syncing':
        return { kind: 'info', text: '正在从客户端核心读取最新状态…', detail: null };
      case v.state.sync.state === 'failed':
        return {
          kind: 'warn',
          text: '部分状态读取失败，相关内容可能缺失',
          detail: v.state.sync.state === 'failed' ? v.state.sync.message : null,
        };
      case v.state.access_auth_required !== null && !v.state.health?.is_authenticated:
        return { kind: 'warn', text: 'MSST 接入认证未完成或已过期，请在首页右下角完成接入认证', detail: null };
      default:
        return null;
    }
  });
</script>

{#if banner}
  <div class="core-banner {banner.kind}" role="status">
    <span>{banner.text}</span>
    {#if banner.detail}<span class="banner-detail" title={banner.detail}>{banner.detail}</span>{/if}
  </div>
{/if}
