<script lang="ts">
  import type { Snippet } from 'svelte';
  let {
    onclose,
    busy = false,
    wide = false,
    cls = '',
    children,
  }: { onclose: () => void; busy?: boolean; wide?: boolean; cls?: string; children: Snippet } = $props();
</script>

<div
  class="modal-backdrop"
  role="presentation"
  onkeydown={(e) => {
    if (e.key === 'Escape' && !busy) onclose();
  }}
  onclick={(e) => {
    if ((e.target as HTMLElement).classList.contains('modal-backdrop') && !busy) onclose();
  }}
>
  <div class="modal {cls}" class:modal-wide={wide}>
    {@render children()}
  </div>
</div>
