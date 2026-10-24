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
import com.msst.net.model.ResolvedPrincipal
import com.msst.net.model.ServiceCategory
import com.msst.net.model.ServiceItem
import com.msst.net.model.ServiceList
import com.msst.net.model.obj
import com.msst.net.platform.formatDateTime
import com.msst.net.platform.query
import com.msst.net.state.PresetDevice
import com.msst.net.state.RoomMembers
import com.msst.net.state.RuleSourcePreset
import com.msst.net.state.RuleWizardState
import com.msst.net.state.SourceMode
import com.msst.net.state.TargetMode
import com.msst.net.state.coversAllPorts
import com.msst.net.state.attempt
import com.msst.net.state.errorMessage
import com.msst.net.state.formatPorts
import com.msst.net.state.formatTargets
import com.msst.net.state.formatPrincipal
import com.msst.net.state.formatRuleSource
import com.msst.net.state.principalLabel
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
fun RuleWizardDialog(
    from: FirewallRule?,
    preset: RuleSourcePreset? = null,
    editing: FirewallRule? = null,
    onClose: () -> Unit,
) {
    val ctx = LocalController.current
    val view by ctx.store.view.collectAsState()
    val groups = view?.state?.aclGroups.orEmpty()
    val nodes = view?.state?.nodes?.values.orEmpty()
    // Core 0.5: names from Core, rules linked to groups, editing in place.
    val aclEdit = view?.capabilities?.aclEdit == true
    val w = remember { RuleWizardState(from, preset, editing) }
    var resolving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var categories by remember { mutableStateOf(listOf<ServiceCategory>()) }
    var servicesError by remember { mutableStateOf<String?>(null) }
    // A pending risky choice ("all protocols and ports", or a port entry
    // covering every port): what it covers, and what confirming does.
    var riskConfirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    LaunchedEffect(Unit) {
        if (view?.capabilities?.getServices != true) {
            servicesError = "当前客户端核心版本不提供服务列表，请使用「协议/端口」方式。"
            return@LaunchedEffect
        }
        val r = attempt { ctx.backend.query<ServiceList>(Req.getServices()) }
        r.onSuccess {
            categories = it.categories
            w.useCatalog(it.categories.flatMap { c -> c.services })
        }
        r.errorMessage?.let { servicesError = "服务列表加载失败：$it" }
    }

    // Names of the sources a copied or edited rule starts with.
    LaunchedEffect(Unit) {
        if (!aclEdit) return@LaunchedEffect
        for (s in w.sources.filter { it.id !in w.names && it.id != "*" }) {
            attempt { ctx.backend.query<ResolvedPrincipal>(Req.resolveAclPrincipal(s.kind, s.id)) }
                .onSuccess { w.names = w.names + (s.id to principalLabel(it)) }
        }
    }

    /** Add the entered source once Core has resolved it (Core 0.5); an
     *  unknown ID or mnemonic is reported instead of added. */
    fun addSource() {
        val v = w.sourceInput.trim()
        if (v.isEmpty() || resolving) return
        if (!aclEdit) {
            w.addSource(w.lookup(nodes))
            return
        }
        resolving = true
        w.error = null
        scope.launch {
            val r = attempt { ctx.backend.query<ResolvedPrincipal>(Req.resolveAclPrincipal(w.sourceKind, v)) }
            resolving = false
            r.onSuccess { w.addSource(principalLabel(it)) }
            r.errorMessage?.let { w.error = "无法识别「$v」：$it" }
        }
    }

    fun submit() {
        busy = true
        w.error = null
        scope.launch {
            val r = attempt {
                if (editing != null) ctx.backend.request(Req.updateAclRule(editing.id, w.patch()))
                else ctx.backend.request(Req.createAclRule(w.request()))
            }
            busy = false
            if (r.isSuccess) onClose() else w.error = r.errorMessage
        }
    }

    val title = when {
        editing != null -> "编辑防火墙规则"
        preset is PresetDevice -> "为 ${preset.name} 添加防火墙规则"
        preset == RoomMembers -> "添加房间级规则"
        w.copied -> "复制为新规则"
        else -> "新建防火墙规则"
    }
    Modal(onClose, title, busy = busy, wide = true, actions = {
        if (w.step > w.firstStep) SecondaryButton("上一步", w::back, enabled = !busy) else SecondaryButton("取消", onClose)
        if (w.step < w.lastStep) PrimaryButton("下一步", { w.next() })
        else if (editing != null) PrimaryButton(if (busy) "保存中…" else "保存", ::submit, enabled = view?.connected == true, busy = busy)
        else PrimaryButton(if (busy) "创建中…" else "创建", ::submit, enabled = view?.connected == true, busy = busy)
    }) {
        WizardSteps(w.step)
        if (preset != null) FixedSource(preset)
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
                        InlineNote(
                            if (aclEdit) "规则会关联此权限组：之后修改权限组的成员时，规则会随之更新。"
                            else "规则创建时会复制权限组的当前成员；之后修改权限组不会改变此规则。",
                        )
                    }
                    SourceMode.Space -> InlineNote("规则将自动匹配当前加入的所有房间成员，无需手动指定。")
                    SourceMode.Manual -> {
                        Select(listOf("device", "user"), w.sourceKind, { if (it == "device") "设备" else "用户" }, w::changeSourceKind, Modifier.fillMaxWidth())
                        InlineNote("来源类型（列表中所有来源必须相同）")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextInput(w.sourceInput, { w.sourceInput = it }, Modifier.weight(1f), placeholder = "输入助记符或 ID", onDone = ::addSource)
                            SecondaryButton(if (resolving) "识别中…" else "添加", ::addSource, enabled = !resolving)
                        }
                        w.lookup(nodes)?.let { Text("→ $it", style = MaterialTheme.typography.labelSmall, color = Msst.colors.primaryDark) }
                        if (w.sources.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                w.sources.forEach { s -> Tag("${if (s.kind == "device") "设备" else "用户"}：${w.sourceLabel(s.id)}") { w.removeSource(s.id) } }
                            }
                        }
                        InlineNote(
                            if (aclEdit) "添加时由客户端核心识别设备或用户并显示其名称；保存时以固定 ID 记录，无法识别的不能添加。"
                            else "助记符会在创建时由客户端核心解析为固定 ID；无法解析的助记符将被拒绝。",
                        )
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
                        CheckRow("所有协议及端口（不推荐）", w.allServices, { on ->
                            // Selecting it needs an explicit confirmation; unselecting does not.
                            if (on) riskConfirm = "所有协议及端口" to { w.changeAllServices(true) } else w.changeAllServices(false)
                        }, hint = "tcp+udp / *")
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
                        val addPort = {
                            if (w.portInputCoversAll) {
                                val proto = if (w.portProto == "tcp+udp") "TCP 和 UDP" else w.portProto.uppercase()
                                riskConfirm = "$proto 的全部端口" to w::addPort
                            } else {
                                w.addPort()
                            }
                        }
                        TextInput(w.portInput, { w.portInput = it }, Modifier.weight(1f), placeholder = "端口：* / 80 / 80-443", onDone = addPort)
                        SecondaryButton("添加", addPort)
                    }
                    if (w.ports.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            w.ports.forEach { p -> Tag("${p.proto}/${p.port}") { w.removePort(p) } }
                        }
                    }
                }
            }
            3 -> {
                StepDesc("设置规则的操作和可选备注")
                ChoiceCard("允许", "放行匹配此规则的流量", w.action == "allow", Msst.colors.success) { w.action = "allow" }
                ChoiceCard("拒绝", "拦截匹配此规则的流量", w.action == "deny", Msst.colors.error) { w.action = "deny" }
                TextInput(w.comment, { w.comment = it }, Modifier.fillMaxWidth(), label = "备注（可选）", placeholder = "规则用途说明，如：允许朋友访问游戏服务器")
            }
            else -> RuleSummary(w, groups, categories, editing != null)
        }
        w.error?.let { InlineError(it) }
    }

    riskConfirm?.let { (what, confirm) ->
        ConfirmDialog(
            "存在安全风险",
            "这条规则将匹配本设备上$what。" +
                "如果动作为「允许」，规则来源将能访问本设备上所有正在监听的服务，" +
                "包括远程桌面、文件共享、数据库、调试接口等本不打算对外开放的服务。\n\n" +
                "建议只选择实际需要的服务，或填写具体端口。",
            "仍然选择",
            onConfirm = confirm,
            onDismiss = { riskConfirm = null },
        )
    }
}

@Composable
private fun WizardSteps(step: Int) {
    val c = Msst.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        listOf("来源匹配", "目标匹配", "操作与备注", "确认").forEachIndexed { i, label ->
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

/** The last wizard step: what the rule will do, as one readable summary. */
@Composable
private fun RuleSummary(w: RuleWizardState, groups: List<AclGroup>, categories: List<ServiceCategory>, editing: Boolean) {
    val c = Msst.colors
    val source = when (w.sourceMode) {
        SourceMode.Group -> groups.firstOrNull { it.id == w.groupId }?.let { g ->
            "权限组「${g.name}」（${if (g.kind == "device") "设备组" else "用户组"}，当前 ${g.memberIds.size} 个成员）"
        } ?: "权限组 ${w.groupId}"
        SourceMode.Space -> "当前所有房间成员（成员变化时自动生效）"
        SourceMode.Manual -> {
            val kind = if (w.sourceKind == "device") "设备" else "用户"
            "$kind：" + w.sources.joinToString("、") { if (it.id == "*") "所有$kind" else w.sourceLabel(it.id) }
        }
    }
    val serviceNames = categories.flatMap { it.services }.associate { it.id to it.name }
    val target = when {
        w.targetMode == TargetMode.Ports -> w.ports.joinToString("、") { "${it.proto.uppercase()} ${it.port}" }
        w.allServices -> "所有协议及端口"
        else -> w.services.joinToString("、") { serviceNames[it] ?: it }
    }
    val allow = w.action == "allow"
    StepDesc(if (editing) "确认修改后的规则" else "确认要创建的规则")
    Column(
        Modifier.fillMaxWidth().clip(Radius.md).background(c.bgSecondary).border(1.dp, c.borderLight, Radius.md).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            (if (allow) "允许" else "拒绝") + "以下来源访问本设备的这些服务：",
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (allow) c.success else c.error,
        )
        ReadOnly("来源", source)
        ReadOnly("服务/端口", target)
        ReadOnly("动作", if (allow) "允许（放行匹配的入站流量）" else "拒绝（拦截匹配的入站流量）")
        ReadOnly("方向", "入站")
        ReadOnly("备注", w.comment.trim().ifEmpty { "无" })
        ReadOnly("有效期", "永久")
    }
    if (w.sourceMode == SourceMode.Group) InlineNote("此规则关联权限组，之后权限组成员变化时规则会随之更新。")
    if (allow && (w.allServices || w.ports.any { coversAllPorts(it.port) })) {
        InlineError("此规则会放行来源对本设备所有端口的访问，请确认这是你想要的。")
    }
    if (editing) InlineNote("保存后，此规则之前放行的连接会按新规则重新检查。")
}

/** The source of a rule started from a room member: shown, not editable. */
@Composable
private fun FixedSource(preset: RuleSourcePreset) {
    val c = Msst.colors
    Row(
        Modifier.fillMaxWidth().clip(Radius.md).background(c.bgSecondary).border(1.dp, c.borderLight, Radius.md)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(if (preset is PresetDevice) MdiIcons.Monitor else MdiIcons.AccountGroupOutline, 16.dp, c.primary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            when (preset) {
                is PresetDevice -> {
                    Text("来源设备：${preset.name}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
                    com.msst.net.ui.components.MonoText(preset.deviceId, color = c.textMuted, small = true)
                }
                RoomMembers -> {
                    Text("来源：当前所有房间成员", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
                    Text("规则自动匹配当前加入的所有房间成员，成员变化时无需修改。", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                }
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

// ── Rule view / edit ─────────────────────────────────────────────────────────

/** A temporary rule (with an expiry): it can be viewed and deleted only. */
@Composable
fun RuleDetailDialog(rule: FirewallRule, catalog: List<ServiceItem>, onClose: () -> Unit, onCopy: (() -> Unit)?) {
    val groups = LocalController.current.store.view.collectAsState().value?.state?.aclGroups.orEmpty()
    val c = Msst.colors
    Modal(onClose, "查看防火墙规则", wide = true, actions = {
        if (onCopy != null) SecondaryButton("复制为新规则", onCopy)
        PrimaryButton("关闭", onClose)
    }) {
        Column(
            Modifier.fillMaxWidth().clip(Radius.md).background(c.bgSecondary).border(1.dp, c.borderLight, Radius.md).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ReadOnly("来源", formatRuleSource(rule, groups))
            ReadOnly("服务/端口", formatTargets(rule.protocolPorts, catalog))
            if (catalog.isNotEmpty()) ReadOnly("端口", formatPorts(rule.protocolPorts))
            ReadOnly("动作", if (rule.action == "allow") "允许" else "拒绝")
            ReadOnly("有效期", rule.expiresAt?.let { "至 ${formatDateTime(it * 1000)}" } ?: "永久")
            ReadOnly("状态", if (rule.enabled) "已启用" else "已停用")
            ReadOnly("备注", rule.comment?.takeIf { it.isNotEmpty() } ?: "无")
        }
        InlineNote("这是一条临时规则（例如交互式授权中「仅此一次」生成的规则），到期后自动删除。临时规则只能查看或删除，不能修改。")
    }
}

/** Editing for Cores before protocol 0.5 (and where rules can't be created
 *  by hand): `UpdateAclRule` then only changes `enabled`, `comment` and
 *  `expires_at`, so sources and ports are shown read-only. */
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
            if (onCopy == null) "此设备上只能修改规则的备注；如需修改来源、端口或动作，请在桌面端或 WebUI 编辑。"
            else "当前客户端核心只支持修改规则的备注和启用状态。如需修改来源、端口或动作，请「复制为新规则」后删除旧规则。",
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
    val rules = view?.state?.aclRules.orEmpty()
    val connected = view?.connected == true
    val aclEdit = view?.capabilities?.aclEdit == true
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
    // Member (ID or mnemonic as entered) → name Core resolved it to.
    var names by remember { mutableStateOf(mapOf<String, String>()) }
    var resolving by remember { mutableStateOf(false) }
    val c = Msst.colors

    suspend fun resolve(kind: String, id: String) =
        attempt { ctx.backend.query<ResolvedPrincipal>(Req.resolveAclPrincipal(kind, id)) }

    fun open(g: AclGroup?) {
        editing = g
        name = g?.name ?: ""
        kind = g?.kind ?: "device"
        members = g?.memberIds ?: emptyList()
        names = g?.memberMnemonics.orEmpty()
        memberInput = ""
        error = null
        showForm = true
        // Names of the current members (Core knows its peers' names).
        if (g != null && aclEdit) scope.launch {
            for (m in g.memberIds) resolve(g.kind, m).onSuccess { names = names + (m to principalLabel(it)) }
        }
    }

    /** Add the entered member once Core has resolved it (Core 0.5); an
     *  unknown ID or mnemonic is reported instead of added. */
    fun addMember() {
        val v = memberInput.trim()
        if (v.isEmpty() || resolving) return
        if (v in members) {
            memberInput = ""
            return
        }
        if (!aclEdit) {
            members = members + v
            memberInput = ""
            return
        }
        resolving = true
        error = null
        scope.launch {
            val r = resolve(kind, v)
            resolving = false
            r.onSuccess {
                members = members + v
                names = names + (v to principalLabel(it))
                memberInput = ""
            }
            r.errorMessage?.let { error = "无法识别「$v」：$it" }
        }
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
                    rules.count { it.groupId == g.id }.takeIf { it > 0 }?.let {
                        Text("$it 条规则", style = MaterialTheme.typography.labelSmall, color = c.primaryDark)
                    }
                    SmallIconButton(MdiIcons.Pencil, "编辑", { open(g) })
                    SmallIconButton(MdiIcons.TrashCanOutline, "删除", { confirmDelete = g }, tint = c.error)
                }
            }
        }
        listError?.let { InlineError(it) }
        InlineNote(
            if (aclEdit) "使用权限组的规则会随权限组成员自动更新；仍被规则使用的权限组不能删除。"
            else "权限组在创建规则时展开为成员列表；之后修改权限组不会改变已创建的规则。",
        )

        if (showForm) {
            Column(
                Modifier.fillMaxWidth().clip(Radius.md).background(c.bgSecondary).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextInput(name, { name = it }, Modifier.fillMaxWidth(), label = "分组名称", placeholder = "如：trusted-friends", enabled = !busy)
                if (editing == null) {
                    // Members are resolved as this kind: fixed once there are any.
                    Select(listOf("device", "user"), kind, { if (it == "device") "设备组" else "用户组" }, { kind = it }, Modifier.fillMaxWidth(), enabled = !busy && members.isEmpty())
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
                    SecondaryButton(if (resolving) "识别中…" else "添加", ::addMember, enabled = !busy && !resolving)
                }
                if (members.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        members.forEach { m -> Tag(names[m]?.takeIf { it != m }?.let { "$it（$m）" } ?: m) { members = members - m } }
                    }
                }
                if (aclEdit) InlineNote("添加时由客户端核心识别设备或用户并显示其名称；保存时以固定 ID 记录。")
                if (editing != null && aclEdit && rules.any { it.groupId == editing?.id }) {
                    InlineNote("保存后，使用此权限组的 ${rules.count { it.groupId == editing?.id }} 条规则会随之更新。")
                }
                error?.let { InlineError(it) }
            }
        }
    }

    confirmDelete?.let { g ->
        val users = rules.filter { it.groupId == g.id }
        if (users.isNotEmpty()) {
            Modal({ confirmDelete = null }, "无法删除权限组", actions = { PrimaryButton("知道了", { confirmDelete = null }) }) {
                Text(
                    "权限组「${g.name}」仍被以下 ${users.size} 条规则使用。请先删除这些规则，或把它们改为其他来源后再删除权限组。",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textPrimary,
                )
                users.forEach { r ->
                    Text(
                        "· ${if (r.action == "allow") "允许" else "拒绝"} ${formatPorts(r.protocolPorts)}" +
                            (r.comment?.takeIf { it.isNotEmpty() }?.let { "（$it）" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textSecondary,
                    )
                }
            }
        } else {
            ConfirmDialog(
                "删除权限组",
                "确定删除权限组「${g.name}」？" + if (aclEdit) "" else "已用它创建的规则不受影响。",
                "删除",
                {
                    listError = null
                    scope.launch { listError = attempt { ctx.backend.request(Req.deleteAclGroup(g.id)) }.errorMessage }
                },
                { confirmDelete = null },
            )
        }
    }
}
