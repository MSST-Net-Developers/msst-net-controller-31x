// Transport to the Controller backend.
//
// - Desktop (Tauri): `invoke` commands of the Rust shell + the
//   `controller-changed` event.
// - Browser (WebUI): same-origin HTTPS API with a session cookie + the
//   `/ws/events` WebSocket.
//
// Both expose the same three operations: read the view, send an
// `IpcRequest`, and get notified when the view changed.

import type { ClearRulesResult, ControllerView, UiNotice } from '$lib/types';
import type { IpcRequest } from './requests';

export const isTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;

/** An error from the Controller or Core, with a user-facing message. */
export class ApiError extends Error {
  constructor(
    message: string,
    public category: string,
    public code: string,
  ) {
    super(message);
  }
}

function toApiError(e: unknown): ApiError {
  if (e instanceof ApiError) return e;
  if (e && typeof e === 'object' && 'message' in e && 'code' in e) {
    const u = e as { message: string; category?: string; code: string };
    return new ApiError(u.message, u.category ?? 'core', u.code);
  }
  return new ApiError(String(e), 'unknown', 'unknown');
}

/** User-facing message for any thrown value. */
export function errorMessage(e: unknown): string {
  return toApiError(e).message;
}

// ── Tauri ────────────────────────────────────────────────────────────────────

async function invoke<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  const { invoke } = await import('@tauri-apps/api/core');
  try {
    return await invoke<T>(cmd, args);
  } catch (e) {
    throw toApiError(e);
  }
}

// ── WebUI ────────────────────────────────────────────────────────────────────

let onUnauthorized: (() => void) | null = null;

/** Called when the WebUI session is missing or expired. */
export function setUnauthorizedHandler(f: () => void) {
  onUnauthorized = f;
}

async function http<T>(method: string, path: string, body?: unknown): Promise<T> {
  let resp: Response;
  try {
    resp = await fetch(path, {
      method,
      credentials: 'same-origin',
      headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError('无法连接到 WebUI 服务，请检查网络。', 'connection', 'webui_unreachable');
  }
  if (resp.status === 401 && resp.headers.get('X-Webui-Unauthorized')) {
    onUnauthorized?.();
    throw new ApiError('登录已失效，请重新登录。', 'session', 'unauthorized');
  }
  const text = await resp.text();
  let data: unknown = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    /* non-JSON body */
  }
  if (!resp.ok) {
    if (data && typeof data === 'object' && 'message' in data) throw toApiError(data);
    const msg = data && typeof data === 'object' && 'error' in data ? String((data as { error: unknown }).error) : text;
    throw new ApiError(msg || `HTTP ${resp.status}`, 'http', String(resp.status));
  }
  return data as T;
}

export const webui = {
  session: () => http<{ authenticated: boolean }>('GET', '/api/session'),
  login: (password: string) => http<{ ok: boolean }>('POST', '/api/login', { password }),
  logout: () => http<{ ok: boolean }>('POST', '/api/logout', {}),
  info: () => http<{ version: string; codename: string; core_protocol: string | null }>('GET', '/api/info'),
  getConfig: () => http<{ port: number; allowed_cidrs: string[] }>('GET', '/api/config'),
  setConfig: (allowed_cidrs: string[]) => http<{ ok: boolean }>('PUT', '/api/config', { allowed_cidrs }),
};

// ── Common API ───────────────────────────────────────────────────────────────

export function getView(): Promise<ControllerView> {
  return isTauri ? invoke('get_view') : http('GET', '/api/view');
}

/** Send a request to Core.  Success of a command means Core accepted it. */
export function ipc<T = unknown>(request: IpcRequest): Promise<T> {
  return isTauri ? invoke('ipc_request', { request }) : http('POST', '/api/ipc', request);
}

export function clearAclRules(): Promise<ClearRulesResult> {
  return isTauri ? invoke('clear_acl_rules') : http('POST', '/api/acl/clear', {});
}

/** Save log text: native save dialog on desktop, download in the browser.
 *  Returns false if the user cancelled. */
export async function exportLogs(content: string): Promise<boolean> {
  if (isTauri) return invoke<boolean>('export_logs', { content });
  const url = URL.createObjectURL(new Blob([content], { type: 'text/plain' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = 'msst-net-logs.txt';
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  return true;
}

/** Open a URL in the system browser (desktop) or a new tab (browser). */
export async function openExternal(url: string) {
  if (isTauri) {
    const { openUrl } = await import('@tauri-apps/plugin-opener');
    await openUrl(url);
  } else {
    window.open(url, '_blank', 'noopener');
  }
}

/** Subscribe to change notifications.  Returns an unsubscribe function. */
export function onNotice(cb: (n: UiNotice) => void): () => void {
  if (isTauri) {
    let unlisten: (() => void) | null = null;
    let closed = false;
    import('@tauri-apps/api/event').then(({ listen }) =>
      listen('controller-changed', () => cb({ type: 'changed' })).then((u) => {
        if (closed) u();
        else unlisten = u;
      }),
    );
    return () => {
      closed = true;
      unlisten?.();
    };
  }

  let ws: WebSocket | null = null;
  let closed = false;
  let retry: ReturnType<typeof setTimeout>;
  let delay = 1000;
  const connect = () => {
    if (closed) return;
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(`${proto}//${location.host}/ws/events`);
    ws.onopen = () => {
      delay = 1000;
      cb({ type: 'changed' }); // may have missed changes while disconnected
    };
    ws.onmessage = (e) => {
      try {
        cb(JSON.parse(e.data) as UiNotice);
      } catch {
        /* ignore malformed */
      }
    };
    ws.onclose = () => {
      if (closed) return;
      retry = setTimeout(connect, delay);
      delay = Math.min(delay * 2, 15000);
    };
  };
  connect();
  return () => {
    closed = true;
    clearTimeout(retry);
    ws?.close();
  };
}
