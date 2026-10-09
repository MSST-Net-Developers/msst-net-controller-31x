// API client — works in both browser (HTTP/WS) and Tauri (invoke) modes.
//
// In browser mode: uses fetch() to the WebUI backend + WebSocket for events.
// In Tauri mode: uses invoke() to the Rust commands.

declare const __API_BASE_URL__: string;

const BASE_URL = typeof __API_BASE_URL__ !== "undefined" ? __API_BASE_URL__ : "http://localhost:18080";

// ── HTTP helpers ─────────────────────────────────────────────────────────────

async function apiGet<T>(path: string): Promise<T> {
  const resp = await fetch(`${BASE_URL}${path}`, { credentials: "include" });
  if (!resp.ok) {
    const text = await resp.text();
    throw new Error(`${resp.status}: ${text}`);
  }
  return resp.json();
}

async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  const resp = await fetch(`${BASE_URL}${path}`, {
    method: "POST",
    credentials: "include",
    headers: body ? { "Content-Type": "application/json" } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!resp.ok) {
    const text = await resp.text();
    throw new Error(`${resp.status}: ${text}`);
  }
  return resp.json();
}

async function apiPut<T>(path: string, body: unknown): Promise<T> {
  const resp = await fetch(`${BASE_URL}${path}`, {
    method: "PUT",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!resp.ok) {
    const text = await resp.text();
    throw new Error(`${resp.status}: ${text}`);
  }
  return resp.json();
}

async function apiDelete(path: string): Promise<void> {
  const resp = await fetch(`${BASE_URL}${path}`, {
    method: "DELETE",
    credentials: "include",
  });
  if (!resp.ok) {
    const text = await resp.text();
    throw new Error(`${resp.status}: ${text}`);
  }
}

// ── Tauri detection ──────────────────────────────────────────────────────────

let tauriInvoke: ((cmd: string, args?: Record<string, unknown>) => Promise<unknown>) | null = null;

async function getTauriInvoke() {
  if (tauriInvoke !== null) return tauriInvoke;
  try {
    const mod = await import("@tauri-apps/api/core");
    tauriInvoke = mod.invoke;
    return tauriInvoke;
  } catch {
    tauriInvoke = undefined as unknown as null;
    return null;
  }
}

// ── Public API ───────────────────────────────────────────────────────────────

import type {
  SystemState,
  HealthState,
  NodeInfo,
  RoomState,
  AclRule,
  AclGroup,
  PendingAuthRequest,
  ConnectionStatus,
} from "$lib/types";

export const api = {
  // State queries
  async getSystemState(): Promise<SystemState> {
    return apiGet("/api/state");
  },

  async getHealth(): Promise<HealthState> {
    return apiGet("/api/health");
  },

  async getNodes(): Promise<NodeInfo[]> {
    return apiGet("/api/nodes");
  },

  async getRoom(): Promise<RoomState> {
    return apiGet("/api/room");
  },

  async getAclRules(): Promise<{ rules: AclRule[] }> {
    return apiGet("/api/acl/rules");
  },

  async getAclGroups(): Promise<{ groups: AclGroup[] }> {
    return apiGet("/api/acl/groups");
  },

  async getPendingAuth(): Promise<{ requests: PendingAuthRequest[] }> {
    return apiGet("/api/auth/pending");
  },

  async getConnectionStatus(): Promise<{ status: ConnectionStatus }> {
    return apiGet("/api/connection");
  },

  // Commands
  async createRoom(room_name: string, password?: string): Promise<unknown> {
    return apiPost("/api/room/create", { room_name, password: password || null });
  },

  async joinRoom(room_id: string, password?: string): Promise<unknown> {
    return apiPost("/api/room/join", { room_id, password: password || null });
  },

  async leaveRoom(room_id: string): Promise<unknown> {
    return apiPost("/api/room/leave", { room_id });
  },

  async createAclRule(rule: unknown): Promise<unknown> {
    return apiPost("/api/acl/rules", rule);
  },

  async updateAclRule(rule_id: string, patch: unknown): Promise<unknown> {
    return apiPost(`/api/acl/rules/${rule_id}`, patch);
  },

  async deleteAclRule(rule_id: string): Promise<void> {
    return apiDelete(`/api/acl/rules/${rule_id}`);
  },

  async submitAuthDecision(request_id: string, decision: string): Promise<unknown> {
    return apiPost("/api/auth/decision", { request_id, decision });
  },

  async getNetSettings(): Promise<unknown> {
    return apiGet("/api/net/settings");
  },

  async setNetSettings(settings: unknown): Promise<unknown> {
    return apiPut("/api/net/settings", settings);
  },

  async getPathView(): Promise<unknown> {
    return apiGet("/api/net/path-view");
  },

  async getTrafficStats(): Promise<unknown> {
    return apiGet("/api/net/traffic");
  },

  async getEndpoints(): Promise<unknown> {
    return apiGet("/api/net/endpoints");
  },

  async getRelays(): Promise<unknown> {
    return apiGet("/api/relays");
  },

  async updateDisplayName(user: string, device: string): Promise<unknown> {
    return apiPost("/api/identity/display-name", { user, device });
  },

  async updateHandle(user: string, device: string): Promise<unknown> {
    return apiPost("/api/identity/handle", { user, device });
  },

  async reRegister(): Promise<unknown> {
    return apiPost("/api/re-register");
  },

  async getLogs(): Promise<unknown> {
    return apiGet("/api/logs");
  },

  async clearLogs(): Promise<void> {
    return apiDelete("/api/logs");
  },
};

// ── WebSocket connection ─────────────────────────────────────────────────────

export type WsMessageHandler = (data: unknown) => void;

export function connectWs(onMessage: WsMessageHandler): () => void {
  const wsUrl = BASE_URL.replace(/^http/, "ws") + "/ws/events";
  let ws: WebSocket | null = null;
  let closed = false;
  let reconnectTimer: ReturnType<typeof setTimeout>;

  function connect() {
    if (closed) return;
    ws = new WebSocket(wsUrl);

    ws.onmessage = (evt) => {
      try {
        const data = JSON.parse(evt.data);
        onMessage(data);
      } catch {}
    };

    ws.onclose = () => {
      if (!closed) {
        reconnectTimer = setTimeout(connect, 3000);
      }
    };

    ws.onerror = () => {
      ws?.close();
    };
  }

  connect();

  return () => {
    closed = true;
    clearTimeout(reconnectTimer);
    ws?.close();
  };
}