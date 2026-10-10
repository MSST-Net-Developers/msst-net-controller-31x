import { defineConfig } from 'vite';
import { sveltekit } from '@sveltejs/kit/vite';
// @ts-expect-error node builtin (no @types/node in this project)
import { readFileSync } from 'node:fs';

const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf-8'));
// @ts-expect-error process is a nodejs global
const env = process.env;
const host = env.TAURI_DEV_HOST;

export default defineConfig({
  plugins: [sveltekit()],
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
    __APP_CODENAME__: JSON.stringify(env.MSST_CODENAME ?? 'unknown'),
  },
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
    host: host || false,
    hmr: host ? { protocol: 'ws', host, port: 1421 } : undefined,
    watch: { ignored: ['**/src-tauri/**'] },
    // WebUI development: proxy the API to a running msst-net-webui.
    proxy: env.WEBUI_DEV_TARGET
      ? {
          '/api': { target: env.WEBUI_DEV_TARGET, secure: false },
          '/ws': { target: env.WEBUI_DEV_TARGET, secure: false, ws: true },
        }
      : undefined,
  },
});
