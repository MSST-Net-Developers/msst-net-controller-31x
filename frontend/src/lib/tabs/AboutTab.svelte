<script lang="ts">
  import { openExternal } from '$lib/api/client';
  import { controller } from '$lib/controller.svelte';
</script>

<div class="about-page">
  <div class="about-card">
    <div class="app-logo">
      <svg width="64" height="64" viewBox="0 0 64 64" fill="none">
        <circle cx="32" cy="32" r="30" stroke="#29b6f6" stroke-width="3" fill="#e1f5fe" />
        <path d="M16 32 Q24 20 32 32 Q40 44 48 32" stroke="#0288d1" stroke-width="2.5" fill="none" stroke-linecap="round" />
        <circle cx="32" cy="32" r="4" fill="#0288d1" />
      </svg>
    </div>
    <h1 class="app-name">MSST-Net 控制器</h1>
    <div class="version-row">
      <span class="version-badge">v{__APP_VERSION__}</span>
      <span class="codename">「{__APP_CODENAME__}」</span>
    </div>
    <p class="inline-note">IPC 协议：控制器 {controller.view?.controller_protocol ?? '—'} / 客户端核心 {controller.view?.core_protocol ?? '未连接'}</p>
  </div>
  <div class="about-org">
    <span>MSST</span>
    <span>十周年部计算机办</span>
    <span>MSST-Net 开发组</span>
  </div>
  <footer class="about-footer">
    <p class="slogan">内网互联焕发生机</p>
    <p class="slogan sub">虽是远在天边，犹如近在眼前</p>
    <div class="about-links">
      <!-- svelte-ignore a11y_invalid_attribute -->
      <a href="#" class="about-link" onclick={(e) => { e.preventDefault(); openExternal('https://msst-net.msst2031.cn'); }}>🌐 官方网站（含使用说明）</a>
      <!-- svelte-ignore a11y_invalid_attribute -->
      <a href="#" class="about-link" onclick={(e) => { e.preventDefault(); openExternal('https://github.com/MSST-Net-Developers'); }}>⌨ GitHub</a>
    </div>
  </footer>
</div>
