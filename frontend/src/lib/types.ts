// Types of the Controller view (Rust `ControllerView`) and of Core 3.1.x
// response payloads.  Core payload shapes follow msst-net-core-31x
// (src/ipc/handler.rs and the modules it calls).

// ── Controller view ──────────────────────────────────────────────────────────

export type ConnectionStatus =
  | 'Disconnected'
  | 'Connecting'
  | 'Connected'
  | 'Incompatible'
  | { Reconnecting: { attempt: number; next_retry_ms: number } };

export type SyncState =
  | { state: 'not_synced' }
  | { state: 'syncing' }
  | { state: 'synced' }
  | { state: 'failed'; message: string };

export interface ConnectionDiagnostics {
  total_connect_attempts: number;
  successful_connects: number;
  disconnections: number;
  version_mismatches: number;
  last_connection_duration_ms: number | null;
  last_error: string | null;
}

export interface Health {
  status: string;
  /** Advances on every Controller-visible change (restarts with Core). */
  generation: number | null;
  device_id: string | null;
  user_id: string | null;
  device_handle: string | null;
  user_handle: string | null;
  user_display_name: string | null;
  device_display_name: string | null;
  device_hostname: string | null;
  is_authenticated: boolean;
  auth_expires_at: number | null;
  ws_connected: boolean | null;
  coordinator_state: string | null;
  tun_ready: boolean | null;
  relay: string | null;
  peer_count: number | null;
  rule_count: number | null;
  ping_enabled: boolean | null;
  /** Protocol 0.3; null from older Cores. */
  access_auth_required: boolean | null;
}

export interface NodeInfo {
  device_id: string;
  ipv4: string | null;
  ipv6: string | null;
  hostname: string | null;
  display_name: string | null;
  user_id: string | null;
  user_handle: string | null;
  device_handle: string | null;
  is_authenticated: boolean;
  path_mode: string | null;
}

export interface RoomPeer {
  device_id: string;
  ipv4: string | null;
  ipv6: string | null;
  hostname: string | null;
  display_name: string | null;
}

export interface RoomState {
  room_id: string | null;
  room_name: string | null;
  relay: string | null;
  peers: RoomPeer[];
}

export interface ProtocolPort { proto: 'tcp' | 'udp'; start: number; end: number; }
export type PermissionGroup =
  | { kind: 'devices'; ids: string[] }
  | { kind: 'users'; ids: string[] }
  | { kind: 'space' };

export interface FirewallRule {
  id: string;
  permission_group: PermissionGroup;
  protocol_ports: ProtocolPort[];
  action: 'allow' | 'deny';
  direction: 'inbound' | 'outbound';
  expires_at: number | null;
  enabled: boolean;
  comment: string | null;
  /** ID → mnemonic the rule was created with (display only). */
  principal_mnemonics?: Record<string, string>;
  /** The rule still references mnemonics Core could not resolve yet. */
  pending_migration?: boolean;
}

export interface PermissionGroupDef { id: string; name: string; kind: 'user' | 'device'; member_ids: string[]; }

export interface PendingAuthRequest {
  request_id: string;
  src_device_id: string;
  src_user_id: string | null;
  src_device_display_name: string | null;
  src_user_display_name: string | null;
  proto: string;
  dst_port: number;
  service_name: string | null;
  expires_in_secs: number | null;
  /** Local deadline derived from Core's `expires_in_secs`; null until known. */
  expires_at_ms: number | null;
}

export interface SystemState {
  sync: SyncState;
  health: Health | null;
  nodes: Record<string, NodeInfo>;
  room: RoomState;
  acl_rules: FirewallRule[];
  acl_groups: PermissionGroupDef[];
  pending_auth: PendingAuthRequest[];
  /** Core needs MSST access authentication. */
  access_auth_required: boolean;
}

export interface ControllerView {
  connection: ConnectionStatus;
  diagnostics: ConnectionDiagnostics;
  core_protocol: string | null;
  controller_protocol: string;
  capabilities: { get_services: boolean; clear_acl_rules: boolean; full_events: boolean };
  state: SystemState;
}

export type UiNotice =
  | { type: 'changed' }
  | {
      type: 'access_request';
      request_id: string;
      src_device_id: string;
      src_device_display_name: string | null;
      proto: string;
      dst_port: number;
      service_name: string | null;
    };

// ── Core query payloads (not kept in the view) ───────────────────────────────

export interface NetSettings {
  disable_p2p: boolean;
  lock_path: boolean;
  preferred_relay: string | null;
  mtu_override: number | null;
  keepalive_secs: number | null;
  linux_dns_mode: string | null;
}

export interface PathCandidate { addr: string; rtt_ms?: number | null; loss_rate?: number; status: string; }
export interface PathViewEntry { device_id: string; display_name: string | null; chosen: string; candidates: PathCandidate[]; }
export interface TrafficEntry { device_id: string; display_name: string | null; tx: number; rx: number; drop: number; }
export interface EndpointRecord { endpoints: string[]; timestamp: number; }
export interface EndpointInfo { current: string[]; history: EndpointRecord[]; }
export interface RelayList { relays: { addr: string }[]; current?: string | null; rtt_ms?: Record<string, number>; }

export interface LogEntry { level: string; target: string; message: string; timestamp_ms: number; }

export type AccessDecision = 'allow' | 'deny' | 'silent_deny';
export type AccessReason =
  | { type: 'matched_rule'; rule_id: string; comment: string | null }
  | { type: 'interactive_auth' }
  | { type: 'interactive_auth_timeout' }
  | { type: 'default_deny' };

export interface AccessLogEntry {
  id: string;
  src_device_id: string;
  service_name: string | null;
  proto: string;
  dst_port: number;
  decision: AccessDecision;
  reason: AccessReason;
  timestamp_ms: number;
  rule_id: string | null;
  count: number;
}

export interface ServiceItem { id: string; name: string; ports: string[]; }
export interface ServiceCategory { id: string; label: string; services: ServiceItem[]; }

export interface AuthStartResult { session_id: string; user_code: string; verification_uri: string; interval?: number; }
export interface AuthPollResult { pending?: boolean; status?: string; user_id?: string; [k: string]: unknown; }

export interface NamespaceRegistration {
  namespace: string; user_id: string; reason: string;
  status: 'pending' | 'approved' | 'rejected'; reject_reason?: string; created_at: number; paused: boolean;
}
export interface DnsRecord {
  id: string; namespace: string; record_type: 'CNAME' | 'TXT' | 'SRV';
  name: string; content: string; ttl: number; created_at: number;
}

export interface ClearRulesResult { deleted: number; failed: { rule_id: string; message: string }[]; }
