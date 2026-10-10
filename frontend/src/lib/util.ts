// Small presentation helpers.

import type { FirewallRule, PermissionGroup, ProtocolPort } from '$lib/types';

export function formatBytes(b: number): string {
  if (b >= 1_073_741_824) return (b / 1_073_741_824).toFixed(1) + ' GB';
  if (b >= 1_048_576) return (b / 1_048_576).toFixed(1) + ' MB';
  if (b >= 1_024) return (b / 1_024).toFixed(1) + ' KB';
  return b + ' B';
}

export function formatPorts(pp: ProtocolPort[]): string {
  return (
    pp
      .map((p) => {
        const ports = p.start === p.end ? String(p.start) : p.start === 1 && p.end === 65535 ? '*' : `${p.start}-${p.end}`;
        return `${p.proto}/${ports}`;
      })
      .join(', ') || '任意'
  );
}

/** Sources of a rule; IDs are shown with the mnemonic the rule was created with. */
export function formatPrincipal(pg: PermissionGroup, mnemonics?: Record<string, string>): string {
  if (pg.kind === 'space') return '房间模式：当前所有房间成员';
  const prefix = pg.kind === 'devices' ? '设备' : '用户';
  const ids = pg.ids.map((id) => (mnemonics?.[id] ? `${mnemonics[id]} (${id})` : id));
  return `${prefix}: ${ids.join(', ')}`;
}

export function ruleSourceCount(r: FirewallRule): number | null {
  return r.permission_group.kind === 'space' ? null : r.permission_group.ids.length;
}

/** Copy text; returns whether it worked. */
export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

/** Validate a port expression accepted by Core: `*`, `80`, `80-443`. */
export function validPortExpr(s: string): boolean {
  if (s === '*') return true;
  const m = /^(\d{1,5})(?:-(\d{1,5}))?$/.exec(s);
  if (!m) return false;
  const a = Number(m[1]);
  const b = m[2] !== undefined ? Number(m[2]) : a;
  return a >= 1 && b <= 65535 && a <= b;
}

/** Mnemonic handle rule (Core `validate_handle`: lowercase letters, digits, hyphens). */
export function validHandle(s: string): boolean {
  return /^[a-z0-9-]+$/.test(s);
}
