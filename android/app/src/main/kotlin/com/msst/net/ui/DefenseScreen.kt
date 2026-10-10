package com.msst.net.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.msst.net.controller.FirewallRule
import com.msst.net.controller.NodeInfo
import com.msst.net.controller.PendingAuthRequest
import com.msst.net.viewmodel.AccessLogEntry
import com.msst.net.viewmodel.AccessReason
import com.msst.net.viewmodel.MainViewModel
import com.msst.net.viewmodel.ServiceCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** Service names from Core's catalog (GetServices), keyed by "proto/port". */
private fun serviceNames(cats: List<ServiceCategory>): Map<String, String> =
    cats.flatMap { c -> c.services.flatMap { s -> s.ports.map { it to s.name } } }.toMap()

@Composable
fun DefenseScreen(modifier: Modifier = Modifier, viewModel: MainViewModel) {
    val view by viewModel.view.collectAsStateWithLifecycle()
    val ruleBusy by viewModel.ruleBusy.collectAsStateWithLifecycle()
    val pingBusy by viewModel.pingBusy.collectAsStateWithLifecycle()
    val accessLog by viewModel.accessLog.collectAsStateWithLifecycle()
    val accessLogError by viewModel.accessLogError.collectAsStateWithLifecycle()
    val services by viewModel.services.collectAsStateWithLifecycle()
    var subTab by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<FirewallRule?>(null) }

    val connected = view?.connected == true
    val rules = view?.rules.orEmpty()
    val names = remember(services) { serviceNames(services) }
    LaunchedEffect(view?.supportsServices) { viewModel.loadServices() }

    Column(modifier) {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    val ping = if (connected) view?.health?.pingEnabled else null
                    Text(if (ping == null) "允许 Ping（状态未知）" else "允许 Ping", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = ping == true, onCheckedChange = viewModel::setPingEnabled, enabled = ping != null && !pingBusy)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { if (subTab != 1) { subTab = 1; viewModel.loadAccessLog() } else subTab = 0 },
                        modifier = Modifier.weight(1f),
                        colors = if (subTab == 1) ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                        else ButtonDefaults.outlinedButtonColors(),
                    ) {
                        Icon(Icons.Filled.History, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (subTab == 1) "返回规则列表" else "访问记录", style = MaterialTheme.typography.labelMedium)
                    }
                    if (subTab == 0) {
                        OutlinedButton(
                            onClick = { confirmClear = true },
                            enabled = connected && rules.isNotEmpty(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.Filled.DeleteSweep, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("清空", style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        OutlinedButton(onClick = viewModel::loadAccessLog) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        if (subTab == 0) {
            when {
                rules.isEmpty() -> EmptyState(
                    Icons.Filled.Shield,
                    when {
                        !connected -> "未连接客户端核心，无法读取规则"
                        view?.sync != "synced" -> "正在读取规则…"
                        else -> "暂无防火墙规则（未匹配的入站访问默认拒绝）"
                    },
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rules, key = { it.id }) { rule ->
                        RuleCard(
                            rule = rule,
                            names = names,
                            busy = rule.id in ruleBusy || !connected,
                            onToggle = { viewModel.setRuleEnabled(rule.id, it) },
                            onDelete = { confirmDelete = rule },
                        )
                    }
                }
            }
        } else {
            accessLogError?.let {
                Text("读取访问记录失败：$it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            if (accessLog.isEmpty()) {
                EmptyState(Icons.Filled.History, "暂无访问记录")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(accessLog, key = { it.id }) { entry ->
                        AccessLogCard(entry, rules, view?.nodes?.get(entry.srcDeviceId), viewModel)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空规则") },
            text = { Text("确定清空全部 ${rules.size} 条规则？此操作不可恢复。") },
            confirmButton = { TextButton(onClick = { confirmClear = false; viewModel.clearRules() }) { Text("清空", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
    confirmDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除规则") },
            text = { Text("确定删除这条规则？") },
            confirmButton = { TextButton(onClick = { confirmDelete = null; viewModel.deleteRule(rule.id) }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

// ── Access log ───────────────────────────────────────────────────────────────

@Composable
private fun AccessLogCard(entry: AccessLogEntry, rules: List<FirewallRule>, node: NodeInfo?, vm: MainViewModel) {
    val isAllow = entry.decision == "allow"
    val timeStr = remember(entry.timestampMs) { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.timestampMs)) }
    val decisionLabel = when (entry.decision) {
        "allow" -> "放行"
        "deny" -> "拒绝"
        "silent_deny" -> "静默拒绝"
        else -> entry.decision
    }
    val reasonLabel = when (val r = entry.reason) {
        is AccessReason.MatchedRule -> "规则" + (r.comment?.let { "「${it}」" } ?: "")
        AccessReason.InteractiveAuth -> "手动授权"
        AccessReason.InteractiveAuthTimeout -> "授权超时"
        AccessReason.DefaultDeny -> "无匹配规则（默认拒绝）"
        AccessReason.Unknown -> "未知原因"
    }
    // Which rule decided this entry is reported by Core (rule_id); the
    // Controller does not re-evaluate rules itself.
    val decidingRule = entry.ruleId?.let { id -> rules.find { it.id == id } }
    val permanent = decidingRule != null && decidingRule.expiresAt == null

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val done: (String?) -> Unit = { err -> busy = false; error = err }
    fun run(f: () -> Unit) { busy = true; error = null; f() }

    Card(
        Modifier.fillMaxWidth(),
        border = BorderStroke(1.5.dp, (if (isAllow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error).copy(alpha = 0.5f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(node?.displayName ?: entry.srcDeviceId, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), maxLines = 1)
                Text(timeStr + if (entry.count > 1) " ×${entry.count}" else "", style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("尝试访问 ${entry.serviceName ?: "${entry.proto.uppercase()}/${entry.dstPort}"}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("因 $reasonLabel 被", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = MaterialTheme.shapes.small,
                    color = if (isAllow) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer) {
                    Text(decisionLabel, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold)
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isAllow) {
                    if (permanent && decidingRule?.action == "allow") {
                        OutlinedButton(onClick = { run { vm.revokeFromLog(entry, done) } }, enabled = !busy, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Text("撤销授权", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        Button(onClick = { run { vm.ruleFromLog(entry, "allow", once = false, done) } }, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text("始终允许", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    OutlinedButton(onClick = { run { vm.ruleFromLog(entry, "allow", once = true, done) } }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("允许一次", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(onClick = { run { vm.ruleFromLog(entry, "allow", once = false, done) } }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("始终允许", style = MaterialTheme.typography.labelSmall)
                    }
                    if (!(permanent && decidingRule?.action == "deny")) {
                        OutlinedButton(onClick = { run { vm.ruleFromLog(entry, "deny", once = false, done) } }, enabled = !busy, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Text("始终拒绝", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { run { vm.createSpaceRule(done) } },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
            ) {
                Icon(Icons.Filled.MeetingRoom, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("用于房间模式（放行所有房间成员的全部端口）", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ── Rules ────────────────────────────────────────────────────────────────────

@Composable
private fun RuleCard(rule: FirewallRule, names: Map<String, String>, busy: Boolean, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    fun label(id: String) = rule.mnemonics[id]?.let { "$it ($id)" } ?: id
    val src = when (rule.kind) {
        "users" -> "用户：${rule.principals.joinToString(", ", transform = ::label)}"
        "devices" -> "设备：${rule.principals.joinToString(", ", transform = ::label)}"
        "space" -> "房间模式：当前所有房间成员"
        else -> rule.principals.joinToString(", ")
    }
    val ports = if (rule.ports.isEmpty()) "全部端口" else rule.ports.joinToString("、") { p -> names[p]?.let { "${it}（${p}）" } ?: p }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (rule.enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (rule.enabled) 2.dp else 0.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(src, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 2)
                Text(
                    "${if (rule.action == "allow") "允许" else "拒绝"} · $ports",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (rule.action == "allow") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                rule.expiresAt?.let {
                    Text("有效期至 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it * 1000))}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                rule.comment?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                if (rule.pendingMigration) {
                    Text("含尚未解析的助记符", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle, enabled = !busy, modifier = Modifier.padding(start = 4.dp))
            IconButton(onClick = onDelete, enabled = !busy, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, "删除", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ── Interactive authorization dialog ─────────────────────────────────────────

/**
 * The countdown is Core's remaining time; Core enforces the timeout.  The
 * dialog never answers on its own (3.0.x auto-sent "deny once").
 */
@Composable
internal fun AuthDialog(req: PendingAuthRequest, queueSize: Int, busy: Boolean, onSubmit: (String, String) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(req.requestId) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val remaining = req.expiresAtMs?.let { ((it - now) / 1000).coerceAtLeast(0) }
    val total = (req.expiresInSecs ?: 60).coerceAtLeast(1)
    val progress by animateFloatAsState(
        targetValue = remaining?.let { (it.toFloat() / total).coerceIn(0f, 1f) } ?: 1f,
        animationSpec = tween(500),
        label = "countdown",
    )
    val urgent = remaining != null && remaining <= 15
    val user = req.srcUserDisplayName ?: req.srcUserId
    val device = req.srcDeviceDisplayName ?: req.srcDeviceId
    val port = "${req.proto.uppercase()}/${req.dstPort}"

    AlertDialog(
        onDismissRequest = {},
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Shield, null, tint = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp))
                    Text(if (queueSize > 1) "访问请求（${queueSize}）" else "访问请求", style = MaterialTheme.typography.titleLarge)
                }
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(),
                    color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Text(
                    when {
                        remaining == null -> "正在读取剩余时间…"
                        remaining > 0 -> "未处理将在 ${remaining}s 后自动拒绝"
                        else -> "已超时，等待客户端核心确认…"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (user != null) "$user 的设备 $device" else "设备 $device", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(req.serviceName?.let { "想访问您的 $it 服务（${port}）" } ?: "想访问端口 $port", style = MaterialTheme.typography.bodyMedium)
                    Text("「允许」「拒绝」仅在 10 分钟内有效；「始终」会创建永久规则。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSubmit(req.requestId, "allow_once") }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("允许", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(onClick = { onSubmit(req.requestId, "always_allow") }, enabled = !busy, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                        Text("始终允许", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSubmit(req.requestId, "always_deny") }, enabled = !busy, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("始终拒绝", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(onClick = { onSubmit(req.requestId, "deny_once") }, enabled = !busy, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text("拒绝", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
    )
}
