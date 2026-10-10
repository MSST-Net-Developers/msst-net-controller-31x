<script lang="ts">
  import { webui, errorMessage } from '$lib/api/client';

  let { onlogin }: { onlogin: () => void } = $props();

  let password = $state('');
  let error = $state('');
  let loading = $state(false);

  async function login() {
    if (!password || loading) return;
    loading = true;
    error = '';
    try {
      await webui.login(password);
      password = '';
      onlogin();
    } catch (e) {
      error = errorMessage(e).includes('invalid password') ? '密码错误' : errorMessage(e);
    } finally {
      loading = false;
    }
  }
</script>

<div class="login-page">
  <div class="login-card">
    <div class="login-logo">⚡</div>
    <h1 class="login-title">MSST-Net WebUI</h1>
    <p class="login-sub">请输入访问密码</p>
    {#if error}<p class="login-error">{error}</p>{/if}
    <!-- svelte-ignore a11y_autofocus -->
    <input class="login-input" type="password" placeholder="访问密码" autofocus bind:value={password}
      onkeydown={(e) => e.key === 'Enter' && login()} disabled={loading} />
    <button class="login-btn" onclick={login} disabled={loading || !password}>{loading ? '登录中…' : '登录'}</button>
  </div>
</div>
