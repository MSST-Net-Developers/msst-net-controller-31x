// Builders for `msst_ipc_protocol::IpcRequest` (serde: tag "type",
// content "params", snake_case).  Field names must match the protocol crate
// (msst-net-core-31x/crates/msst-ipc-protocol/src/message.rs, protocol 0.2).

export type AuthDecision = 'allow_once' | 'always_allow' | 'deny_once' | 'always_deny';

export interface IpcRequest {
  type: string;
  params?: Record<string, unknown>;
}

const q = (type: string, params?: Record<string, unknown>): IpcRequest => (params ? { type, params } : { type });

export const R = {
  // Queries
  health: () => q('health'),
  getNodes: () => q('get_nodes'),
  getAclRules: () => q('get_acl_rules'),
  getAclGroups: () => q('get_acl_groups'),
  getAccessLog: (limit: number | null = null) => q('get_access_log', { limit }),
  getPendingAuth: () => q('get_pending_auth'),
  getNetSettings: () => q('get_net_settings'),
  getEndpoints: () => q('get_endpoints'),
  getTrafficStats: () => q('get_traffic_stats'),
  getPathView: () => q('get_path_view'),
  getLogs: (limit: number | null = null) => q('get_logs', { limit }),
  getDnsDomains: () => q('get_dns_domains'),
  getDnsRecords: (domain: string) => q('get_dns_records', { domain }),
  getRelays: () => q('get_relays'),
  getServices: () => q('get_services'),

  // Commands
  createRoom: (room_name: string, password: string | null) => q('create_room', { room_name, password }),
  joinRoom: (room_id: string, password: string | null) => q('join_room', { room_id, password }),
  leaveRoom: (room_id: string) => q('leave_room', { room_id }),
  createAclRule: (rule: unknown) => q('create_acl_rule', { rule }),
  updateAclRule: (rule_id: string, patch: { enabled?: boolean; comment?: string; expires_at?: number }) =>
    q('update_acl_rule', { rule_id, patch }),
  deleteAclRule: (rule_id: string) => q('delete_acl_rule', { rule_id }),
  createAclGroup: (group: { name: string; kind: 'user' | 'device'; member_ids: string[] }) => q('create_acl_group', { group }),
  updateAclGroup: (group_id: string, patch: { name?: string; member_ids?: string[] }) => q('update_acl_group', { group_id, patch }),
  deleteAclGroup: (group_id: string) => q('delete_acl_group', { group_id }),
  authDecision: (request_id: string, decision: AuthDecision) => q('auth_decision', { request_id, decision }),
  updateDisplayName: (user: string | null, device: string | null) => q('update_display_name', { user, device }),
  updateHandle: (user: string | null, device: string | null) => q('update_handle', { user, device }),
  setNetSettings: (settings: Record<string, unknown>) => q('set_net_settings', { settings }),
  setPingEnabled: (enabled: boolean) => q('set_ping_enabled', { enabled }),
  refreshEndpoint: () => q('refresh_endpoint'),
  reRegister: () => q('re_register'),
  authStart: () => q('auth_start'),
  authPoll: (session_id: string) => q('auth_poll', { session_id }),
  authRefresh: () => q('auth_refresh'),
  dnsRegisterDomain: (namespace: string, reason: string) => q('dns_register_domain', { namespace, reason }),
  dnsCreateRecord: (domain: string, record: unknown) => q('dns_create_record', { domain, record }),
  dnsUpdateRecord: (domain: string, record_id: string, patch: unknown) => q('dns_update_record', { domain, record_id, patch }),
  dnsDeleteRecord: (domain: string, record_id: string) => q('dns_delete_record', { domain, record_id }),
  startDiagPing: (device_id: string) => q('start_diag_ping', { device_id }),
  getDiagPingResult: (job_id: string) => q('get_diag_ping_result', { job_id }),
  clearLogs: () => q('clear_logs'),
};
