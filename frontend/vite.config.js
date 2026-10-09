import { defineConfig } from "vite";
import { sveltekit } from "@sveltejs/kit/vite";

// Detect if running under Tauri
const isTauri = process.env.TAURI_ENV_PLATFORM !== undefined;

export default defineConfig({
  plugins: [sveltekit()],
  clearScreen: false,
  server: {
    port: isTauri ? 1420 : 5173,
    strictPort: isTauri,
  },
  define: {
    __API_BASE_URL__: JSON.stringify(
      process.env.VITE_API_URL || "http://localhost:18080"
    ),
  },
});