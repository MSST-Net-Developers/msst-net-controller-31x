package com.msst.net.ui.screens

// .msst.net namespace management (3.0.x).  Core forwards these requests to
// the coordinator with the user's token, so MSST-Net access authentication is
// required.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msst.net.model.DnsRecord
import com.msst.net.model.DnsRecordList
import com.msst.net.model.NamespaceList
import com.msst.net.model.NamespaceRegistration
import com.msst.net.model.Req
import com.msst.net.model.obj
import com.msst.net.platform.query
import com.msst.net.state.Phase
import com.msst.net.state.attempt
import com.msst.net.state.errorMessage
import com.msst.net.state.normalizeNamespace
import com.msst.net.ui.LocalCompact
import com.msst.net.ui.LocalController
import com.msst.net.ui.MdiIcons
import com.msst.net.ui.components.Card
import com.msst.net.ui.components.ConfirmDialog
import com.msst.net.ui.components.EmptyState
import com.msst.net.ui.components.InlineError
import com.msst.net.ui.components.InlineNote
import com.msst.net.ui.components.LinkButton
import com.msst.net.ui.components.Modal
import com.msst.net.ui.components.MonoText
import com.msst.net.ui.components.Pill
import com.msst.net.ui.components.PrimaryButton
import com.msst.net.ui.components.SecondaryButton
import com.msst.net.ui.components.SmallIconButton
import com.msst.net.ui.components.SuccessFeedback
import com.msst.net.ui.components.TextInput
import com.msst.net.ui.theme.Msst
import com.msst.net.ui.theme.Radius
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DnsScreen() {
    val ctx = LocalController.current
    val view by ctx.store.view.collectAsState()
    val connected = view?.connected == true
    val authenticated = connected && view?.state?.health?.isAuthenticated == true
    val scope = rememberCoroutineScope()
    var namespaces by remember { mutableStateOf(listOf<NamespaceRegistration>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showRegister by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf<String?>(null) }
    val c = Msst.colors

    suspend fun load() {
        loading = true
        error = null
        val r = attempt { ctx.backend.query<NamespaceList>(Req.getDnsDomains()) }
        r.onSuccess { namespaces = it.namespaces }
        error = r.errorMessage
        loading = false
    }
    LaunchedEffect(authenticated) { if (authenticated) load() }

    val compact = LocalCompact.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (compact) 12.dp else 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val title = @Composable { modifier: Modifier ->
                Column(modifier) {
                    Text(".msst.net 命名空间管理", style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, color = c.textPrimary)
                    Text("注册命名空间后，可在 *.<namespace>.msst.net 下添加 CNAME / TXT / SRV 记录。", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
            }
            if (compact) {
                title(Modifier.fillMaxWidth())
                PrimaryButton("申请命名空间", { showRegister = true }, Modifier.fillMaxWidth(), enabled = authenticated, icon = MdiIcons.Plus)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    title(Modifier.weight(1f))
                    PrimaryButton("申请命名空间", { showRegister = true }, enabled = authenticated, icon = MdiIcons.Plus)
                }
            }
            when {
                !authenticated -> EmptyState("域名管理需要先完成 MSST-Net 接入认证\n请点击首页的「接入认证」按钮完成认证")
                loading -> EmptyState("加载中…")
                error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    InlineError(error!!)
                    LinkButton("重试", { scope.launch { load() } }, MdiIcons.Refresh)
                }
                namespaces.isEmpty() -> EmptyState("您还没有命名空间\n点击「申请命名空间」开始注册（需审核）")
                else -> namespaces.forEach { ns ->
                    NamespaceCard(ns, active == ns.namespace) { active = if (active == ns.namespace) null else ns.namespace }
                }
            }
        }
    }

    if (showRegister) RegisterDialog(onClose = { showRegister = false }, onDone = { scope.launch { load() } })
}

@Composable
private fun NamespaceCard(ns: NamespaceRegistration, open: Boolean, onToggle: () -> Unit) {
    val c = Msst.colors
    Card(border = if (open) c.borderPrimary else c.borderLight) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoText("*.${ns.namespace}.msst.net", Modifier.weight(1f))
            when (ns.status) {
                "approved" -> Pill("已审批", c.success, c.successLight)
                "pending" -> Pill("待审核", c.warning, c.warningLight)
                else -> Pill("已拒绝", c.error, c.errorLight)
            }
        }
        if (ns.reason.isNotEmpty()) Text("申请理由：${ns.reason}", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
        if (ns.status == "rejected") ns.rejectReason?.let { Text("拒绝原因：$it", style = MaterialTheme.typography.labelSmall, color = c.error) }
        if (ns.status == "approved") {
            LinkButton("管理记录", onToggle, if (open) MdiIcons.ChevronDown else MdiIcons.ChevronRight)
            if (open) RecordsPanel(ns.namespace)
        }
    }
}

@Composable
private fun RecordsPanel(ns: String) {
    val ctx = LocalController.current
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(listOf<DnsRecord>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<DnsRecord?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<DnsRecord?>(null) }
    val c = Msst.colors

    suspend fun load() {
        loading = true
        error = null
        val r = attempt { ctx.backend.query<DnsRecordList>(Req.getDnsRecords(ns)) }
        r.onSuccess { records = it.records }
        error = r.errorMessage
        loading = false
    }
    LaunchedEffect(ns) { load() }

    Column(
        Modifier.fillMaxWidth().clip(Radius.md).background(c.bgSecondary).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DNS 记录 — *.$ns.msst.net", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
            LinkButton("添加记录", { editing = null; showForm = true }, MdiIcons.Plus)
        }
        error?.let { InlineError(it) }
        when {
            loading -> InlineNote("加载中…")
            records.isEmpty() && error == null -> InlineNote("暂无记录，点击「添加记录」创建")
            else -> records.forEach { rec ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(rec.recordType, c.primaryDark, c.primaryLighter)
                    Column(Modifier.weight(1f)) {
                        MonoText(if (rec.name == "@") "$ns.msst.net" else "${rec.name}.$ns.msst.net", small = true)
                        MonoText(rec.content, color = c.textSecondary, small = true)
                    }
                    Text("TTL ${rec.ttl}", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                    SmallIconButton(MdiIcons.Pencil, "编辑", { editing = rec; showForm = true })
                    SmallIconButton(MdiIcons.TrashCanOutline, "删除", { confirmDelete = rec }, tint = c.error)
                }
            }
        }
    }

    if (showForm) RecordDialog(ns, editing, onClose = { showForm = false }, onDone = { scope.launch { load() } })
    confirmDelete?.let { rec ->
        ConfirmDialog("删除记录", "确定删除此记录？", "删除", {
            scope.launch {
                val r = attempt { ctx.backend.request(Req.dnsDeleteRecord(ns, rec.id)) }
                if (r.isFailure) error = "删除失败：${r.errorMessage}" else load()
            }
        }, { confirmDelete = null })
    }
}

@Composable
private fun RegisterDialog(onClose: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalController.current
    val scope = rememberCoroutineScope()
    var ns by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf(Phase.Idle) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = phase == Phase.Submitting
    LaunchedEffect(phase) {
        if (phase == Phase.Success) {
            delay(800)
            onClose()
            onDone()
        }
    }

    Modal(onClose, "申请命名空间", busy = busy, actions = if (phase == Phase.Success) null else {
        {
            SecondaryButton("取消", onClose, enabled = !busy)
            PrimaryButton(if (busy) "提交中…" else "提交申请", {
                val name = normalizeNamespace(ns)
                if (name == null) {
                    phase = Phase.Error
                    error = "命名空间只能包含小写字母、数字和连字符，且不能以连字符开头或结尾"
                    return@PrimaryButton
                }
                phase = Phase.Submitting
                error = null
                scope.launch {
                    val r = attempt { ctx.backend.request(Req.dnsRegisterDomain(name, reason.trim())) }
                    error = r.errorMessage
                    phase = if (r.isSuccess) Phase.Success else Phase.Error
                }
            }, enabled = ns.isNotBlank() && reason.isNotBlank(), busy = busy)
        }
    }) {
        if (phase == Phase.Success) {
            SuccessFeedback("申请已提交，等待管理员审核")
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextInput(ns, { ns = it }, Modifier.weight(1f), label = "命名空间", placeholder = "如 abjust", enabled = !busy, mono = true)
                Text(".msst.net", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall, color = Msst.colors.textMuted)
            }
            TextInput(reason, { reason = it }, Modifier.fillMaxWidth(), label = "申请理由（必填）", placeholder = "请说明命名空间的用途和必要性",
                enabled = !busy, singleLine = false, minLines = 3)
            InlineNote("每人最多同时拥有 5 个命名空间（待审核+已审批合计）")
            error?.let { InlineError(it) }
        }
    }
}

@Composable
private fun RecordDialog(ns: String, editing: DnsRecord?, onClose: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalController.current
    val scope = rememberCoroutineScope()
    val srv = editing?.takeIf { it.recordType == "SRV" }?.content?.split(' ')
    var type by remember { mutableStateOf(editing?.recordType ?: "CNAME") }
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var content by remember { mutableStateOf(if (srv == null) editing?.content ?: "" else "") }
    var priority by remember { mutableStateOf(srv?.getOrNull(0) ?: "10") }
    var weight by remember { mutableStateOf(srv?.getOrNull(1) ?: "20") }
    var port by remember { mutableStateOf(srv?.getOrNull(2) ?: "443") }
    var target by remember { mutableStateOf(srv?.getOrNull(3) ?: "") }
    var ttl by remember { mutableStateOf((editing?.ttl ?: 300).toString()) }
    var phase by remember { mutableStateOf(Phase.Idle) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = phase == Phase.Submitting
    LaunchedEffect(phase) {
        if (phase == Phase.Success) {
            delay(600)
            onClose()
            onDone()
        }
    }

    fun submit() {
        val body = if (type == "SRV") "${priority.trim()} ${weight.trim()} ${port.trim()} ${target.trim()}" else content.trim()
        val ttlValue = ttl.toIntOrNull()
        error = when {
            body.isEmpty() || (type == "SRV" && target.isBlank()) -> "请填写记录内容"
            ttlValue == null || ttlValue !in 60..86400 -> "TTL 应为 60–86400 之间的整数"
            type == "SRV" && listOf(priority, weight).any { it.toIntOrNull() !in 0..65535 } -> "优先级和权重应为 0–65535 之间的整数"
            type == "SRV" && port.toIntOrNull() !in 1..65535 -> "端口应为 1–65535 之间的整数"
            else -> null
        }
        if (error != null) return run { phase = Phase.Error }
        phase = Phase.Submitting
        scope.launch {
            val r = attempt {
                if (editing != null) ctx.backend.request(Req.dnsUpdateRecord(ns, editing.id, obj("content" to body, "ttl" to ttlValue)))
                else ctx.backend.request(Req.dnsCreateRecord(ns, obj("record_type" to type, "name" to name.trim().ifEmpty { "@" }, "content" to body, "ttl" to ttlValue)))
            }
            error = r.errorMessage
            phase = if (r.isSuccess) Phase.Success else Phase.Error
        }
    }

    Modal(onClose, if (editing != null) "编辑 DNS 记录" else "添加 DNS 记录", busy = busy, actions = if (phase == Phase.Success) null else {
        {
            SecondaryButton("取消", onClose, enabled = !busy)
            PrimaryButton(if (busy) "保存中…" else if (editing != null) "保存" else "添加", ::submit, busy = busy)
        }
    }) {
        InlineNote("命名空间：*.$ns.msst.net")
        if (phase == Phase.Success) {
            SuccessFeedback(if (editing != null) "已更新" else "已添加")
            return@Modal
        }
        if (editing == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("CNAME", "TXT", "SRV").forEach { t ->
                    if (t == type) PrimaryButton(t, {}) else SecondaryButton(t, { type = t })
                }
            }
            TextInput(name, { name = it }, Modifier.fillMaxWidth(), label = "子域名（如 myapp、api、www）", placeholder = "如 myapp", enabled = !busy, mono = true)
        }
        when (type) {
            "CNAME" -> TextInput(content, { content = it }, Modifier.fillMaxWidth(), label = "目标（ezPlay 主机名）", placeholder = "device.user.ezplay.msst.net", enabled = !busy, mono = true)
            "TXT" -> TextInput(content, { content = it }, Modifier.fillMaxWidth(), label = "文本内容", placeholder = "v=spf1 include:...", enabled = !busy)
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput(priority, { priority = it.filter(Char::isDigit) }, Modifier.weight(1f), label = "优先级", enabled = !busy, number = true)
                    TextInput(weight, { weight = it.filter(Char::isDigit) }, Modifier.weight(1f), label = "权重", enabled = !busy, number = true)
                    TextInput(port, { port = it.filter(Char::isDigit) }, Modifier.weight(1f), label = "端口", enabled = !busy, number = true)
                }
                TextInput(target, { target = it }, Modifier.fillMaxWidth(), label = "目标主机（ezPlay 主机名）", placeholder = "device.user.ezplay.msst.net", enabled = !busy, mono = true)
            }
        }
        TextInput(ttl, { ttl = it.filter(Char::isDigit) }, Modifier.width(200.dp), label = "TTL（秒，默认 300）", enabled = !busy, number = true)
        error?.let { InlineError(it) }
    }
}
