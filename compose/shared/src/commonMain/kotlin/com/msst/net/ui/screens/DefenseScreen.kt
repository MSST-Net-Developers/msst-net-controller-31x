package com.msst.net.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msst.net.model.AccessLog
import com.msst.net.model.AccessLogEntry
import com.msst.net.model.AccessReason
import com.msst.net.model.FirewallRule
import com.msst.net.model.ONCE_TTL_SECS
import com.msst.net.model.Req
import com.msst.net.model.SystemState
import com.msst.net.model.obj
import com.msst.net.model.quickDeviceRule
import com.msst.net.model.spaceAllowAllRule
import com.msst.net.platform.formatDateTime
import com.msst.net.platform.formatTime
import com.msst.net.platform.nowMs
import com.msst.net.platform.query
import com.msst.net.state.attempt
import com.msst.net.state.deviceName
import com.msst.net.state.errorMessage
import com.msst.net.state.formatPorts
import com.msst.net.state.formatPrincipal
import com.msst.net.ui.LocalCompact
import com.msst.net.ui.LocalController
import com.msst.net.ui.MdiIcons
import com.msst.net.ui.components.Card
import com.msst.net.ui.components.ConfirmDialog
import com.msst.net.ui.components.EmptyState
import com.msst.net.ui.components.InlineError
import com.msst.net.ui.components.InlineNote
import com.msst.net.ui.components.LinkButton
import com.msst.net.ui.components.Pill
import com.msst.net.ui.components.SecondaryButton
import com.msst.net.ui.components.SmallIconButton
import com.msst.net.ui.components.Toggle
import com.msst.net.ui.dialogs.GroupManagerDialog
import com.msst.net.ui.dialogs.RuleEditDialog
import com.msst.net.ui.dialogs.RuleWizardDialog
import com.msst.net.ui.theme.Msst
import kotlinx.coroutines.launch

private sealed interface DefenseDialog {
    data object Groups : DefenseDialog
    data class Wizard(val from: FirewallRule?) : DefenseDialog
    data class Edit(val rule: FirewallRule) : DefenseDialog
    data class ConfirmDelete(val rule: FirewallRule) : DefenseDialog
    data object ConfirmClear : DefenseDialog
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DefenseScreen() {
    val ctx = LocalController.current
    val view by ctx.store.view.collectAsState()
    val state = view?.state
    val connected = view?.connected == true
    val health = if (connected) state?.health else null
    val rules = state?.aclRules.orEmpty()
    val manual = ctx.features.manualRuleCreation
    val scope = rememberCoroutineScope()

    var showLog by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<DefenseDialog?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pingBusy by remember { mutableStateOf(false) }
    var busyRules by remember { mutableStateOf(setOf<String>()) }
    var clearing by remember { mutableStateOf(false) }
    var spaceBusy by remember { mutableStateOf(false) }

    // Access log: queried on demand; responses of superseded loads are dropped.
    var log by remember { mutableStateOf(listOf<AccessLogEntry>()) }
    var logLoading by remember { mutableStateOf(false) }
    var logError by remember { mutableStateOf<String?>(null) }
    var logSeq by remember { mutableStateOf(0) }
    fun loadLog() {
        val seq = ++logSeq
        logLoading = true
        logError = null
        scope.launch {
            val r = attempt { ctx.backend.query<AccessLog>(Req.getAccessLog(200)) }
            if (seq != logSeq) return@launch
            r.onSuccess { log = it.entries }
            logError = r.errorMessage
            logLoading = false
        }
    }

    fun withRule(id: String, what: String, block: suspend () -> Unit) {
        busyRules = busyRules + id
        error = null
        scope.launch {
            attempt { block() }.errorMessage?.let { error = "${what}失败：$it" }
            busyRules = busyRules - id
        }
    }

    fun logAction(what: String, block: suspend () -> Unit) {
        logError = null
        scope.launch {
            val r = attempt { block() }
            if (r.isFailure) logError = "${what}失败：${r.errorMessage}" else loadLog()
        }
    }

    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth().background(Msst.colors.bgPrimary).padding(horizontal = if (LocalCompact.current) 12.dp else 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Ping (ICMP)" + if (health?.pingEnabled == null) "（未知）" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Msst.colors.textSecondary,
                )
                Spacer(Modifier.width(6.dp))
                // The shown value is always Core's (Health.ping_enabled).
                Toggle(health?.pingEnabled ?: false, { enabled ->
                    pingBusy = true
                    error = null
                    scope.launch {
                        attempt { ctx.backend.request(Req.setPingEnabled(enabled)) }.errorMessage?.let { error = "Ping 设置失败：$it" }
                        pingBusy = false
                    }
                }, enabled = !pingBusy && health?.pingEnabled != null)
            }
            if (manual) SecondaryButton("权限组管理", { dialog = DefenseDialog.Groups }, icon = MdiIcons.AccountGroupOutline)
            SecondaryButton("访问记录", {
                showLog = !showLog
                if (showLog) loadLog()
            }, icon = MdiIcons.History, active = showLog)
            if (!showLog) {
                if (manual) {
                    SecondaryButton("新建规则", { dialog = DefenseDialog.Wizard(null) }, enabled = connected, icon = MdiIcons.Plus)
                } else {
                    SecondaryButton(if (spaceBusy) "添加中…" else "放行房间成员", {
                        spaceBusy = true
                        error = null
                        scope.launch {
                            attempt { ctx.backend.request(Req.createAclRule(spaceAllowAllRule())) }.errorMessage?.let { error = "添加规则失败：$it" }
                            spaceBusy = false
                        }
                    }, enabled = connected && !spaceBusy, icon = MdiIcons.AccountMultipleOutline)
                }
                SecondaryButton(
                    if (clearing) "清空中…" else "清空",
                    { dialog = DefenseDialog.ConfirmClear },
                    enabled = rules.isNotEmpty() && !clearing && connected,
                    icon = MdiIcons.TrashCanOutline,
                    danger = true,
                )
            } else {
                Text("${log.size} 条记录", style = MaterialTheme.typography.labelSmall, color = Msst.colors.textMuted)
                LinkButton(if (logLoading) "加载中…" else "刷新", ::loadLog, MdiIcons.Refresh, enabled = !logLoading)
            }
        }

        error?.let { InlineError(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        if (!manual && !showLog) {
            InlineNote("移动端不能手动创建规则；可通过访问记录、访问请求或「放行房间成员」添加规则。", Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }

        if (!showLog) {
            when {
                state?.sync?.synced != true && rules.isEmpty() ->
                    EmptyState(if (connected) "正在读取规则…" else "未连接客户端核心，无法读取规则")
                rules.isEmpty() -> EmptyState("暂无防火墙规则（未匹配的入站访问默认拒绝）")
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(if (LocalCompact.current) 12.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rules, key = { it.id }) { rule ->
                        RuleCard(
                            rule,
                            busy = rule.id in busyRules || !connected,
                            onToggle = {
                                withRule(rule.id, if (rule.enabled) "停用规则" else "启用规则") {
                                    ctx.backend.request(Req.updateAclRule(rule.id, obj("enabled" to !rule.enabled)))
                                }
                            },
                            onEdit = { dialog = DefenseDialog.Edit(rule) },
                            onDelete = { dialog = DefenseDialog.ConfirmDelete(rule) },
                        )
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(if (LocalCompact.current) 12.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                logError?.let { item { EmptyState(it, error = true) } }
                if (log.isEmpty() && logError == null) item { EmptyState(if (logLoading) "加载中…" else "暂无访问记录") }
                items(log, key = { it.id }) { e ->
                    AccessLogCard(e, state, rules, connected) { what, block -> logAction(what, block) }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        DefenseDialog.Groups -> GroupManagerDialog { dialog = null }
        is DefenseDialog.Wizard -> RuleWizardDialog(d.from) { dialog = null }
        is DefenseDialog.Edit -> RuleEditDialog(
            d.rule,
            onClose = { dialog = null },
            onCopy = if (manual) ({ dialog = DefenseDialog.Wizard(d.rule) }) else null,
        )
        is DefenseDialog.ConfirmDelete -> ConfirmDialog("删除规则", "确定删除这条规则？", "删除", {
            withRule(d.rule.id, "删除规则") { ctx.backend.request(Req.deleteAclRule(d.rule.id)) }
        }, { dialog = null })
        DefenseDialog.ConfirmClear -> ConfirmDialog("清空规则", "确定清空全部 ${rules.size} 条规则？此操作不可恢复。", "清空", {
            clearing = true
            error = null
            scope.launch {
                val r = attempt { ctx.backend.clearAclRules() }
                r.onSuccess { res ->
                    if (res.failed.isNotEmpty()) {
                        error = "已删除 ${res.deleted} 条，${res.failed.size} 条删除失败：" + res.failed.joinToString("；") { it.message }
                    }
                }
                r.errorMessage?.let { error = "清空失败：$it" }
                clearing = false
            }
        }, { dialog = null })
    }
}

@Composable
private fun RuleCard(rule: FirewallRule, busy: Boolean, onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val c = Msst.colors
    val details = @Composable { modifier: Modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rule.action == "allow") Pill("允许", c.success, c.successLight) else Pill("拒绝", c.error, c.errorLight)
                Text(
                    formatPorts(rule.protocolPorts),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (rule.enabled) c.textPrimary else c.textMuted,
                )
            }
            Text(
                formatPrincipal(rule.permissionGroup, rule.principalMnemonics),
                style = MaterialTheme.typography.labelSmall,
                color = if (rule.enabled) c.textSecondary else c.textMuted,
            )
            if (rule.pendingMigration) InlineNote("含尚未解析的助记符")
            rule.expiresAt?.let { InlineNote("有效期至 ${formatDateTime(it * 1000)}") }
            rule.comment?.takeIf { it.isNotEmpty() }?.let { Text("备注：$it", style = MaterialTheme.typography.labelSmall, color = c.textTertiary) }
        }
    }
    Card(padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        if (LocalCompact.current) {
            // Phones: details get the full width; controls in a row below.
            details(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Toggle(rule.enabled, { onToggle() }, enabled = !busy)
                Spacer(Modifier.width(8.dp))
                Text(if (rule.enabled) "已启用" else "已停用", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                SmallIconButton(MdiIcons.Pencil, "编辑", onEdit)
                SmallIconButton(MdiIcons.TrashCanOutline, "删除", onDelete, enabled = !busy, tint = c.error)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Toggle(rule.enabled, { onToggle() }, enabled = !busy)
                Spacer(Modifier.width(12.dp))
                details(Modifier.weight(1f))
                SmallIconButton(MdiIcons.Pencil, "编辑", onEdit)
                SmallIconButton(MdiIcons.TrashCanOutline, "删除", onDelete, enabled = !busy, tint = c.error)
            }
        }
    }
}

private fun reasonLabel(r: AccessReason): String = when (r.type) {
    "matched_rule" -> r.comment?.let { "规则备注：$it" } ?: "规则 ${r.ruleId.orEmpty().take(8)}"
    "interactive_auth" -> "交互式授权"
    "interactive_auth_timeout" -> "交互式授权超时"
    "default_deny" -> "无匹配规则（默认拒绝）"
    else -> "未知原因"
}

private fun decisionLabel(d: String) = when (d) {
    "allow" -> "允许"
    "deny" -> "拒绝"
    else -> "静默拒绝"
}

/** Core reported this entry was decided by a permanent rule with [action]. */
private fun hasPermanentRule(e: AccessLogEntry, rules: List<FirewallRule>, action: String): Boolean {
    val r = e.ruleId?.let { id -> rules.firstOrNull { it.id == id } } ?: return false
    return r.action == action && r.expiresAt == null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccessLogCard(
    e: AccessLogEntry,
    state: SystemState?,
    rules: List<FirewallRule>,
    connected: Boolean,
    act: (String, suspend () -> Unit) -> Unit,
) {
    val ctx = LocalController.current
    val c = Msst.colors
    val allowed = e.decision == "allow"
    val name = deviceName(state, e.srcDeviceId)
    fun rule(action: String, expiresAt: Long?) = act("添加规则") {
        ctx.backend.request(Req.createAclRule(quickDeviceRule(e.srcDeviceId, name, e.proto, e.dstPort, action, expiresAt)))
    }

    Card(
        padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        border = if (allowed) c.success.copy(alpha = 0.35f) else c.error.copy(alpha = 0.35f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
            Text(
                formatDateTime(e.timestampMs) + if (e.count > 1) " ×${e.count}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = c.textMuted,
            )
        }
        Text(
            "在 ${formatTime(e.timestampMs)} 时尝试访问 ${e.serviceName ?: "${e.proto}/${e.dstPort}"}",
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("已因 ${reasonLabel(e.reason)} 被", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
            if (allowed) Pill(decisionLabel(e.decision), c.success, c.successLight) else Pill(decisionLabel(e.decision), c.error, c.errorLight)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (allowed) {
                if (hasPermanentRule(e, rules, "allow")) {
                    LinkButton("撤销授权", { act("撤销授权") { ctx.backend.request(Req.deleteAclRule(e.ruleId!!)) } }, enabled = connected, color = c.warning)
                } else {
                    LinkButton("始终允许", { rule("allow", null) }, enabled = connected, color = c.success)
                }
            } else {
                LinkButton("允许一次", { rule("allow", nowMs() / 1000 + ONCE_TTL_SECS) }, enabled = connected, color = c.success)
                LinkButton("始终允许", { rule("allow", null) }, enabled = connected, color = c.success)
                if (!hasPermanentRule(e, rules, "deny")) LinkButton("始终拒绝", { rule("deny", null) }, enabled = connected, color = c.error)
            }
        }
    }
}
