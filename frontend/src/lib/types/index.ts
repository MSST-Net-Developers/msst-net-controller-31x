// Domain types — mirror the Rust models in src/state/types.rs

export type ConnectionStatus =
  | "Disconnected"
  | "Connecting"
  | "Connected"
  | { Reconnecting: { attempt: number; next_retry_ms: number } };

export interface HealthState {
  status: string;
  user_id: string | null;
  device_id: string | null;
  user_display_name: string | null;
  device_display_name: string | null;
  user_handle: string | null;
  device_handle: string | null;
  device_hostname: string | null;
  is_authenticated: boolean;
  ws_connected: boolean | null;
  tun_ready: boolean | null;
  relay: string | null;
}

export interface NodeInfo {
  device_id: string;
  ipv4: string;
  ipv6: string;
  hostname: string | null;
  display_name: string | null;
  path_mode: string | null;
}

export interface RoomState {
  room_id: string | null;
  room_name: string | null;
  relay: string | null;
  peers: RoomPeer[];
}

export interface RoomPeer {
  device_id: string;
  hostname: string | null;
  display_name: string | null;
}

export interface AclRule {
  id: string;
  enabled: boolean;
  action: string;
  direction: string;
  principals: unknown[];
  protocol_ports: unknown[];
  comment: string | null;
  expires_at: number | null;
}

export interface AclGroup {
  id: string;
  name: string;
  kind: string;
  member_ids: string[];
}

export interface PendingAuthRequest {
  request_id: string;
  src_device_id: string;
  src_user_id: string | null;
  src_device_display_name: string | null;
  src_user_display_name: string | null;
  proto: string;
  dst_port: number;
  service_name: string | null;
  expires_at: number | null;
}

export interface SystemState {
  health: HealthState;
  nodes: Record<string, NodeInfo>;
  room: RoomState;
  acl_rules: AclRule[];
  acl_groups: AclGroup[];
  pending_auth: PendingAuthRequest[];
}

// WebSocket event types
export type WsEvent =
  | { type: "snapshot_applied" }
  | { type: "event"; data: unknown }
  | { type: "lagged"; dropped: number };