<script lang="ts">
  // Brand mark (formerly inline in AboutTab); colours follow the theme tokens.
  let { size = 24 }: { size?: number } = $props();
</script>

<svg width={size} height={size} viewBox="0 0 64 64" fill="none" aria-hidden="true">
  <circle cx="32" cy="32" r="30" stroke="var(--primary)" stroke-width="3" fill="var(--primary-lighter)" />
  <path d="M16 32 Q24 20 32 32 Q40 44 48 32" stroke="var(--primary-dark)" stroke-width="3" fill="none" stroke-linecap="round" />
  <circle cx="32" cy="32" r="4" fill="var(--primary-dark)" />
</svg>
