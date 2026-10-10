<script lang="ts">
  // Edit a rule.  Core's UpdateAclRule only changes `enabled`, `comment`
  // and `expires_at` (same as 3.0.x), so sources and ports are shown
  // read-only; changing them means creating a new rule.
  import { untrack } from 'svelte';
  import Modal from '$lib/components/Modal.svelte';
  import { ipc, errorMessage } from '$lib/api/client';
  import { R } from '$lib/api/requests';
  import { controller } from '$lib/controller.svelte';
  import { formatPorts, formatPrincipal } from '$lib/util';
  import type { FirewallRule } from '$lib/types';

  let { rule, onclose, oncopy }: { rule: FirewallRule; onclose: () => void; oncopy: (r: FirewallRule) => void } = $props();

  // Form starts from the rule as it was when the dialog opened.
  let comment = $state(untrack(() => rule.comment ?? ''));
  let busy = $state(false);
  let error = $state('');

  async function save() {
    busy = true;
    error = '';
    try {
      await ipc(R.updateAclRule(rule.id, { comment: comment.trim() }));
      onclose();
    } catch (e) {
      error = errorMessage(e);
    } finally {
      busy = false;
    }
  }
</script>

<Modal {onclose} wide busy={busy}>
  <h2 class="modal-title">编辑防火墙规则</h2>
  <div class="readonly-block">
    <span class="ro-label">来源</span>
    <span>{formatPrincipal(rule.permission_group, rule.principal_mnemonics)}</span>
    <span class="ro-label">协议/端口</span>
    <span>{formatPorts(rule.protocol_ports)}</span>
    <span class="ro-label">动作</span>
    <span>{rule.action === 'allow' ? '允许' : '拒绝'}{rule.expires_at ? `（有效期至 ${new Date(rule.expires_at * 1000).toLocaleString()}）` : ''}</span>
  </div>
  <p class="inline-note">客户端核心目前只支持修改规则的备注和启用状态。如需修改来源、端口或动作，请「复制为新规则」后删除旧规则。</p>
  <label class="field" style="margin-top:12px">
    <span>备注</span>
    <input type="text" bind:value={comment} class="select-input" disabled={busy} />
  </label>
  {#if error}<p class="modal-error">{error}</p>{/if}
  <div class="modal-actions">
    <button class="modal-btn cancel" onclick={onclose} disabled={busy}>取消</button>
    <button class="modal-btn cancel" onclick={() => oncopy(rule)} disabled={busy}>复制为新规则</button>
    <button class="modal-btn confirm" onclick={save} disabled={busy || !controller.connected}>{busy ? '保存中…' : '保存'}</button>
  </div>
</Modal>
