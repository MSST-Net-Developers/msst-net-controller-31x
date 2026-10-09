// Svelte 5 runes-based reactive stores for application state.
//
// These stores poll the API and listen to WebSocket events to keep
// the UI in sync with Core's authoritative state.

import { api, connectWs } from "$lib/api/client";
import type {
  SystemState,
  ConnectionStatus,
  PendingAuthRequest,
  WsEvent,
} from "$lib/types";

// ── Connection status ────────────────────────────────────────────────────────

export function createConnectionStore() {
  let status = $state<ConnectionStatus>("Disconnected");
  let connected = $derived(
    typeof status === "string" && status === "Connected"
  );

  async function refresh() {
    try {
      const data = await api.getConnectionStatus();
      status = data.status;
    } catch {
      status = "Disconnected";
    }
  }

  return {
    get status() { return status; },
    get connected() { return connected; },
    refresh,
  };
}

// ── System state ─────────────────────────────────────────────────────────────

export function createStateStore() {
  let state = $state<SystemState | null>(null);
  let loading = $state(true);
  let error = $state<string | null>(null);

  async function refresh() {
    try {
      state = await api.getSystemState();
      error = null;
    } catch (e) {
      error = String(e);
    } finally {
      loading = false;
    }
  }

  return {
    get state() { return state; },
    get loading() { return loading; },
    get error() { return error; },
    refresh,
  };
}

// ── Pending auth requests ────────────────────────────────────────────────────

export function createAuthStore() {
  let requests = $state<PendingAuthRequest[]>([]);
  let hasRequests = $derived(requests.length > 0);

  function addRequest(req: PendingAuthRequest) {
    if (!requests.some((r) => r.request_id === req.request_id)) {
      requests = [...requests, req];
    }
  }

  function removeRequest(requestId: string) {
    requests = requests.filter((r) => r.request_id !== requestId);
  }

  async function refresh() {
    try {
      const data = await api.getPendingAuth();
      requests = data.requests ?? [];
    } catch {}
  }

  async function submitDecision(requestId: string, decision: string) {
    try {
      await api.submitAuthDecision(requestId, decision);
      removeRequest(requestId);
    } catch (e) {
      throw e;
    }
  }

  return {
    get requests() { return requests; },
    get hasRequests() { return hasRequests; },
    addRequest,
    removeRequest,
    refresh,
    submitDecision,
  };
}

// ── WebSocket bridge ─────────────────────────────────────────────────────────

let wsCleanup: (() => void) | null = null;

export function startWebSocket(
  stateStore: ReturnType<typeof createStateStore>,
  authStore: ReturnType<typeof createAuthStore>,
) {
  wsCleanup?.();

  wsCleanup = connectWs((data) => {
    const msg = data as WsEvent;
    switch (msg.type) {
      case "snapshot_applied":
        stateStore.refresh();
        break;
      case "event":
        // Refresh state on any event
        stateStore.refresh();
        authStore.refresh();
        break;
      case "lagged":
        // Full resync needed
        stateStore.refresh();
        break;
    }
  });
}

export function stopWebSocket() {
  wsCleanup?.();
  wsCleanup = null;
}