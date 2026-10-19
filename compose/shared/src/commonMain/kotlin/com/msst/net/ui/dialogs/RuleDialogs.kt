package com.msst.net.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msst.net.model.AclGroup
import com.msst.net.model.FirewallRule
import com.msst.net.model.Req
import com.msst.net.model.ServiceCategory
import com.msst.net.model.ServiceList
import com.msst.net.model.obj
import com.msst.net.platform.formatDateTime
import com.msst.net.platform.query
import com.msst.net.state.RuleWizardState
import com.msst.net.state.SourceMode
import com.msst.net.state.TargetMode
import com.msst.net.state.attempt
import com.msst.net.state.errorMessage
import com.msst.net.state.formatPorts
import com.msst.net.state.formatPrincipal
import com.msst.net.ui.LocalController
import com.msst.net.ui.MdiIcons
import com.msst.net.ui.components.CheckRow
import com.msst.net.ui.components.ConfirmDialog
import com.msst.net.ui.components.InlineError
import com.msst.net.ui.components.InlineNote
import com.msst.net.ui.components.MIcon
import com.msst.net.ui.components.Modal
import com.msst.net.ui.components.PrimaryButton
import com.msst.net.ui.components.SecondaryButton
import com.msst.net.ui.components.Select
import com.msst.net.ui.components.SmallIconButton
import com.msst.net.ui.components.Tag
import com.msst.net.ui.components.TextInput
import com.msst.net.ui.theme.Msst
import com.msst.net.ui.theme.Radius
import kotlinx.coroutines.launch

// ── Rule wizard ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleWizardDialog(from: FirewallRule?, onClose: () -> Unit) {
    val ctx = LocalController.current
    val view by ctx.store.view.collectAsState()
    val groups = view?.state?.aclGroups.orEmpty()
    val nodes = view?.state?.nodes?.values.orEmpty()
    val w = remember { RuleWizardState(from) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var categories by remember { mutableStateOf(listOf<ServiceCategory>()) }
    var servicesError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (view?.capabilities?.getServices != true) {
            servicesError = "当前客户端核心版本不提供服务列表，请使用「协议/端口」方式。"
            return@LaunchedEffect
        }
        val r = attempt { ctx.backend.query<ServiceList>(Req.getServices()) }
        r.onSuccess { categories = it.categories }
        r.errorMessage?.let { servicesError = "服务列表加载失败：$it" }
    }

    fun submit() {
        busy = true
        w.error = null
        scope.launch {
            val r = attempt { ctx.backend.request(Req.createAclRule(w.request())) }
            busy = false
            if (r.isSuccess) onClose() else w.error = r.errorMessage
        }
    }

    Modal(onClose, if (w.copied) "复制为新规则" else "新建防火墙规则", busy = busy, wide = true, actions = {
        if (w.step > 1) SecondaryButton("上一步", w::back, enabled = !busy) else SecondaryButton("取消", onClose)
        if (w.step < 3) PrimaryButton("下一步", { w.next() })
        else PrimaryButton(if (busy) "创建中…" else "创建", ::submit, enabled = view?.connected == true, busy = busy)
    }) {
        WizardSteps(w.step)
        when (w.step) {
            1 -> {
                StepDesc("选择此规则匹配的来源")
                ChoiceCard("使用命名权限组", "选择一个预定义的权限组（推荐）", w.sourceMode == SourceMode.Group) { w.sourceMode = SourceMode.Group }
                ChoiceCard("手动指定来源", "输入设备或用户的助记符或 ID", w.sourceMode == SourceMode.Manual) { w.sourceMode = SourceMode.Manual }
                ChoiceCard("用于房间模式", "匹配当前所有房间成员", w.sourceMode == SourceMode.Space) { w.sourceMode = SourceMode.Space }
                when (w.sourceMode) {
                    SourceMode.Group -> {
                        Select(
                            groups,
                            groups.firstOrNull { it.id == w.groupId },
                            { g -> "${g.name}（${if (g.kind == "device") "设备组" else "用户组"}，${g.memberIds.size} 个成员）" },
                            { w.groupId = it.id },
                            Modifier.fillMaxWidth(),
                            placeholder = "— 选择权限组 —",
                        )
                        if (groups.isEmpty()) InlineNote("暂无权限组，请先在「权限组管理」中创建")
                        InlineNote("规则创建时会复制权限组的当前成员；之后修改权限组不会改变此规则。")
                    }
                    SourceMode.Space -> InlineNote("规则将自动匹配当前加入的所有房间成员，无需手动指定。")
                    SourceMode.Manual -> {
                        Select(listOf("device", "user"), w.sourceKind, { if (it == "device") "设备" else "用户" }, w::changeSourceKind, Modifier.fillMaxWidth())
                        InlineNote("来源类型（列表中所有来源必须相同）")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextInput(w.sourceInput, { w.sourceInput = it }, Modifier.weight(1f), placeholder = "输入助记符或 ID", onDone = w::addSource)
                            SecondaryButton("添加", w::addSource)
                        }
                        w.lookup(nodes)?.let { Text("→ $it", style = MaterialTheme.typography.labelSmall, color = Msst.colors.primaryDark) }
                        if (w.sources.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                w.sources.forEach { s -> Tag("${if (s.kind == "device") "设备" else "用户"}:${s.id}") { w.removeSource(s.id) } }
                            }
                        }
                        InlineNote("助记符会在创建时由客户端核心解析为固定 ID；无法解析的助记符将被拒绝。")
                    }
                }
            }
            2 -> {
                StepDesc("选择此规则匹配的目标服务或端口")
                ChoiceCard("服务（简单）", "从常用服务列表中选择", w.targetMode == TargetMode.Services) { w.targetMode = TargetMode.Services }
                ChoiceCard("协议/端口（进阶）", "手动指定协议和端口", w.targetMode == TargetMode.Ports) { w.targetMode = TargetMode.Ports }
                if (w.targetMode == TargetMode.Services) {
                    servicesError?.let { InlineError(it) }
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(Radius.md).border(1.dp, Msst.colors.borderLight, Radius.md)
                            .verticalScroll(rememberScrollState()).padding(8.dp),
                    ) {
                        CheckRow("所有服务", w.allServices, w::changeAllServices, hint = "tcp+udp / *")
                        categories.forEach { cat ->
                            Text(cat.label, Modifier.padding(top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = Msst.colors.textTertiary)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                cat.services.forEach { svc ->
                                    Box(Modifier.widthIn(min = 180.dp)) {
                                        CheckRow(svc.name, svc.id in w.services, { w.toggleService(svc.id, it) }, enabled = !w.allServices, hint = svc.ports.joinToString(", "))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Select(listOf("tcp+udp", "tcp", "udp"), w.portProto, { it.uppercase() }, { w.portProto = it }, Modifier.width(120.dp))
                        TextInput(w.portInput, { w.portInput = it }, Modifier.weight(1f), placeholder = "端口：* / 80 / 80-443", onDone = w::addPort)
                        SecondaryButton("添加", w::addPort)
                    }
                    if (w.ports.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            w.ports.forEach { p -> Tag("${p.proto}/${p.port}") { w.removePort(p) } }
                        }
                    }
                }
            }
            else -> {
                StepDesc("设置规则的操作和可选备注")
                ChoiceCard("允许", "放行匹配此规则的流量", w.action == "allow", Msst.colors.success) { w.action = "allow" }
                ChoiceCard("拒绝", "拦截匹配此规则的流量", w.action == "deny", Msst.colors.error) { w.action = "deny" }
                TextInput(w.comment, { w.comment = it }, Modifier.fillMaxWidth(), label = "备注（可选）", placeholder = "规则用途说明，如：允许朋友访问游戏服务器")
            }
        }
        w.error?.let { InlineError(it) }
    }
}

@Composable
private fun WizardSteps(step: Int) {
    val c = Msst.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        listOf("来源匹配", "目标匹配", "操作与备注").forEachIndexed { i, label ->
            val n = i + 1
            if (i > 0) Box(Modifier.weight(1f).height(2.dp).padding(horizontal = 6.dp).background(if (step > i) c.primary else c.borderLight))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(22.dp).clip(Radius.full).background(if (step >= n) c.primary else c.bgSecondary)
                        .border(1.dp, if (step >= n) c.primary else c.borderMedium, Radius.full),
                    contentAlignment = Alignment.Center,
                ) {
                    if (step > n) MIcon(MdiIcons.Check, 14.dp, c.textInverse)
                    else Text("$n", style = MaterialTheme.typography.labelSmall, color = if (step >= n) c.textInverse else c.textMuted)
                }
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (step == n) FontWeight.SemiBold else FontWeight.Normal), color = if (step >= n) c.primary else c.textMuted)
            }
        }
    }
}

@Composable
private fun StepDesc(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = Msst.colors.textSecondary)

@Composable
private fun ChoiceCard(title: String, desc: String, selected: Boolean, accent: Color = Msst.colors.primary, onClick: () -> Unit) {
    val c = Msst.colors
    Row(
        Modifier.fillMaxWidth().clip(Radius.md).background(if (selected) c.primaryLighter else c.bgPrimary)
            .border(1.dp, if (selected) accent else c.borderLight, Radius.md)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(Radius.full).border(if (selected) 4.dp else 1.dp, if (selected) accent else c.borderMedium, Radius.full))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = if (selected) accent else c.textPrimary)
            Text(desc, style = MaterialTheme.typography.labelSmall, color = c.textMuted)
        }
    }
}

// ── Rule edit ────────────────────────────────────────────────────────────────

/** Core's UpdateAclRule only changes `enabled`, `comment` and `expires_at`
 *  (same as 3.0.x), so sources and ports are shown read-only. */
@Composable
fun RuleEditDialog(rule: FirewallRule, onClose: () -> Unit, onCopy: (() -> Unit)?) {
    val ctx = LocalController.current
    val connected = ctx.store.view.collectAsState().value?.connected == true
    var comment by remember { mutableStateOf(rule.comment ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val c = Msst.colors

    Modal(onClose, "编辑防火墙规则", busy = busy, wide = true, actions = {
        SecondaryButton("取消", onClose, enabled = !busy)
        if (onCopy != null) SecondaryButton("复制为新规则", onCopy, enabled = !busy)
        PrimaryButton(if (busy) "保存中…" else "保存", {
            busy = true
            error = null
            scope.launch {
                val r = attempt { ctx.backend.request(Req.updateAclRule(rule.id, obj("comment" to comment.trim()))) }
                busy = false
                if (r.isSuccess) onClose() else error = r.errorMessage
            }
        }, enabled = connected, busy = busy)
    }) {
        Column(
            Modifier.fillMaxWidth().clip(Radius.md).background(c.bgSecondary).border(1.dp, c.borderLight, Radius.md).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ReadOnly("来源", formatPrincipal(rule.permissionGroup, rule.principalMnemonics))
            ReadOnly("协议/端口", formatPorts(rule.protocolPorts))
            ReadOnly(
                "动作",
                (if (rule.action == "allow") "允许" else "拒绝") + (rule.expiresAt?.let { "（有效期至 ${formatDateTime(it * 1000)}）" } ?: ""),
            )
        }
        InlineNote(
            "客户端核心目前只支持修改规则的备注和启用状态。" +
                if (onCopy != null) "如需修改来源、端口或动作，请「复制为新规则」后删除旧规则。" else "",
        )
        TextInput(comment, { comment = it }, Modifier.fillMaxWidth(), label = "备注", enabled = !busy)
        error?.let { InlineError(it) }
    }
}

@Composable
private fun ReadOnly(label: String, value: String) {
    Row {
        Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.labelSmall, color = Msst.colors.textMuted)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Msst.colors.textPrimary)
    }
}

// ── Permission groups ────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupManagerDialog(onClose: () -> Unit) {
    val ctx = LocalController.current
    val view by ctx.store.view.collectAsState()
    val groups = view?.state?.aclGroups.orEmpty()
    val connected = view?.connected == true
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<AclGroup?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("device") }
    var members by remember { mutableStateOf(listOf<String>()) }
    var memberInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<AclGroup?>(null) }
    val c = Msst.colors

    fun open(g: AclGroup?) {
        editing = g
        name = g?.name ?: ""
        kind = g?.kind ?: "device"
        members = g?.memberIds ?: emptyList()
        memberInput = ""
        error = null
        showForm = true
    }

    fun addMember() {
        val v = memberInput.trim()
        if (v.isNotEmpty() && v !in members) members = members + v
        memberInput = ""
    }

    fun submit() {
        if (name.isBlank()) {
            error = "请输入分组名称"
            return
        }
        busy = true
        error = null
        val g = editing
        scope.launch {
            val r = attempt {
                if (g != null) ctx.backend.request(Req.updateAclGroup(g.id, name.trim(), members))
                else ctx.backend.request(Req.createAclGroup(name.trim(), kind, members))
            }
            busy = false
            if (r.isSuccess) {
                showForm = false
                editing = null
            } else {
                error = r.errorMessage
            }
        }
    }

    Modal(onClose, "权限组管理", busy = busy, wide = true, actions = {
        if (showForm) {
            SecondaryButton("取消", { showForm = false; editing = null }, enabled = !busy)
            PrimaryButton(if (busy) "提交中…" else if (editing != null) "保存" else "创建", ::submit, enabled = connected, busy = busy)
        } else {
            SecondaryButton("关闭", onClose)
            PrimaryButton("新建分组", { open(null) }, enabled = connected, icon = MdiIcons.Plus)
        }
    }) {
        if (groups.isEmpty()) {
            Text("暂无权限组", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        } else {
            groups.forEach { g ->
                Row(
                    Modifier.fillMaxWidth().clip(Radius.md).border(1.dp, c.borderLight, Radius.md).padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(g.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
                    Text(if (g.kind == "device") "设备组" else "用户组", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                    Text("${g.memberIds.size} 个成员", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                    SmallIconButton(MdiIcons.Pencil, "编辑", { open(g) })
                    SmallIconButton(MdiIcons.TrashCanOutline, "删除", { confirmDelete = g }, tint = c.error)
                }
            }
        }
        listError?.let { InlineError(it) }
        InlineNote("权限组在创建规则时展开为成员列表；之后修改权限组不会改变已创建的规则。")

        if (showForm) {
            Column(
                Modifier.fillMaxWidth().clip(Radius.md).background(c.bgSecondary).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextInput(name, { name = it }, Modifier.fillMaxWidth(), label = "分组名称", placeholder = "如：trusted-friends", enabled = !busy)
                if (editing == null) {
                    Select(listOf("device", "user"), kind, { if (it == "device") "设备组" else "用户组" }, { kind = it }, Modifier.fillMaxWidth(), enabled = !busy)
                } else {
                    InlineNote("类型：${if (kind == "device") "设备组" else "用户组"}（不可更改）")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput(
                        memberInput, { memberInput = it }, Modifier.weight(1f),
                        label = if (kind == "device") "设备 ID / 助记符" else "用户 ID / 助记符",
                        placeholder = "输入 ID 或助记符后按添加",
                        enabled = !busy,
                        onDone = ::addMember,
                    )
                    SecondaryButton("添加", ::addMember, enabled = !busy)
                }
                if (members.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        members.forEach { m -> Tag(m) { members = members - m } }
                    }
                }
                error?.let { InlineError(it) }
            }
        }
    }

    confirmDelete?.let { g ->
        ConfirmDialog("删除权限组", "确定删除权限组「${g.name}」？已用它创建的规则不受影响。", "删除", {
            listError = null
            scope.launch { listError = attempt { ctx.backend.request(Req.deleteAclGroup(g.id)) }.errorMessage }
        }, { confirmDelete = null })
    }
}
