// The UI's single source of Core state: the Controller view, re-read
// whenever the backend says it changed.  Components read from here and send
// commands through `ipc()`; they never keep their own copy of Core state.

import { getView, onNotice } from '$lib/api/client';
import type { ControllerView, Health, UiNotice } from '$lib/types';

/** Overall status shown in the status bar. */
export type CoreStatus = 'ok' | 'warning' | 'error' | 'checking';

const FALLBACK_POLL_MS = 5000;

class Controller {
  view = $state<ControllerView | null>(null);
  /** Error reading the view from the backend itself (WebUI unreachable). */
  backendError = $state<string | null>(null);
  #seq = 0;
  #applied = 0;
  #listeners = new Set<(n: UiNotice) => void>();

  /** Re-read the view; responses older than one already applied are dropped. */
  async refresh() {
    const seq = ++this.#seq;
    try {
      const v = await getView();
      if (seq < this.#applied) return;
      this.#applied = seq;
      this.view = v;
      this.backendError = null;
    } catch (e) {
      if (seq < this.#applied) return;
      this.backendError = e instanceof Error ? e.message : String(e);
    }
  }

  /** Start listening; returns a stop function. */
  start(): () => void {
    this.refresh();
    const unsub = onNotice((n) => {
      if (n.type === 'changed') this.refresh();
      for (const l of this.#listeners) l(n);
    });
    // Safety net if a notification is missed (e.g. WebSocket reconnecting).
    const timer = setInterval(() => this.refresh(), FALLBACK_POLL_MS);
    return () => {
      unsub();
      clearInterval(timer);
    };
  }

  /** Listen to raw notices (e.g. new access requests). */
  listen(f: (n: UiNotice) => void): () => void {
    this.#listeners.add(f);
    return () => this.#listeners.delete(f);
  }

  get connected(): boolean {
    return this.view?.connection === 'Connected';
  }

  get state() {
    return this.view?.state ?? null;
  }

  get health(): Health | null {
    return this.connected ? (this.view?.state.health ?? null) : null;
  }

  /** Commands can be sent: IPC connected and Core reports healthy. */
  get coreReady(): boolean {
    return this.status === 'ok';
  }

  get status(): CoreStatus {
    const v = this.view;
    if (!v) return this.backendError ? 'error' : 'checking';
    if (v.connection === 'Incompatible') return 'error';
    if (v.connection !== 'Connected') return v.connection === 'Connecting' ? 'checking' : 'error';
    const h = v.state.health;
    if (!h) return v.state.sync.state === 'failed' ? 'warning' : 'checking';
    return h.status === 'ok' ? 'ok' : 'warning';
  }

  /** One-line explanation of `status` for the status bar. */
  get statusLabel(): string {
    const v = this.view;
    if (!v) return this.backendError ? `无法读取控制器状态：${this.backendError}` : '检查中…';
    switch (this.status) {
      case 'ok':
        return v.state.health?.ws_connected === false ? '客户端核心正常，协调服务器未连接' : '客户端核心状态正常';
      case 'warning':
        return v.state.sync.state === 'failed' ? '客户端核心部分状态读取失败' : '客户端核心状态异常';
      case 'checking':
        return v.connection === 'Connected' ? '正在读取客户端核心状态…' : '正在连接客户端核心…';
      case 'error':
        return v.connection === 'Incompatible' ? '客户端核心版本不兼容' : '客户端核心可能没有启动';
    }
  }
}

export const controller = new Controller();
