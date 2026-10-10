<script lang="ts">
  import { onMount } from 'svelte';
  import App from '$lib/App.svelte';
  import LoginPage from '$lib/LoginPage.svelte';
  import { isTauri, setUnauthorizedHandler, webui } from '$lib/api/client';

  // Desktop: always the app.  Browser (WebUI): login first.
  let session = $state<'checking' | 'in' | 'out'>(isTauri ? 'in' : 'checking');

  onMount(() => {
    if (isTauri) return;
    setUnauthorizedHandler(() => (session = 'out'));
    webui.session().then(() => (session = 'in')).catch(() => (session = 'out'));
  });
</script>

{#if session === 'in'}
  <App onlogout={() => (session = 'out')} />
{:else if session === 'out'}
  <LoginPage onlogin={() => (session = 'in')} />
{/if}
