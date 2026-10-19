package com.msst.net.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msst.net.model.DiagPingJob
import com.msst.net.model.DiagPingResult
import com.msst.net.model.EndpointInfo
import com.msst.net.model.Health
import com.msst.net.model.NetSettings
import com.msst.net.model.PathView
import com.msst.net.model.PathViewEntry
import com.msst.net.model.RelayList
import com.msst.net.model.Req
import com.msst.net.model.TrafficEntry
import com.msst.net.model.TrafficStats
import com.msst.net.model.UiNotice
import com.msst.net.model.obj
import com.msst.net.platform.WebSession
import com.msst.net.platform.formatTime
import com.msst.net.platform.query
import com.msst.net.state.CoreStatus
import com.msst.net.state.Phase
import com.msst.net.state.attempt
import com.msst.net.state.connectionLabel
import com.msst.net.state.coreStatus
import com.msst.net.state.errorMessage
import com.msst.net.state.formatBytes
import com.msst.net.state.oneDecimal
import com.msst.net.state.triState
import com.msst.net.state.validHandle
import com.msst.net.ui.LocalController
import com.msst.net.ui.MdiIcons
import com.msst.net.ui.components.Card
import com.msst.net.ui.components.Collapsible
import com.msst.net.ui.components.Divider
import com.msst.net.ui.components.InfoBlock
import com.msst.net.ui.components.InfoLine
import com.msst.net.ui.components.InlineError
import com.msst.net.ui.components.InlineNote
import com.msst.net.ui.components.LinkButton
import com.msst.net.ui.components.Notice
import com.msst.net.ui.components.PrimaryButton
import com.msst.net.ui.components.RadioRow
import com.msst.net.ui.components.SecondaryButton
import com.msst.net.ui.components.SectionHeader
import com.msst.net.ui.components.Select
import com.msst.net.ui.components.Tag
import com.msst.net.ui.components.TextInput
import com.msst.net.ui.components.ToggleRow
import com.msst.net.ui.dialogs.LogDialog
import com.msst.net.ui.theme.Msst
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val ctx = LocalController.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            IdentityCard()
            ctx.platform.session?.let { RemoteAccessCard(it) }
            DiagnosticsCard()
        }
    }
}

// ── Identity ─────────────────────────────────────────────────────────────────

@Composable
private fun IdentityCard() {
    val ctx = LocalController.current
    val view by ctx.store.view.collectAsState()
    val backendError by ctx.store.backendError.collectAsState()
    val ready = coreStatus(view, backendError) == CoreStatus.Ok
    val health = if (view?.connected == true) view?.state?.health else null
    var name by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }
    var loadedFor by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf(Phase.Idle) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Fill the form from Core once per identity (don't overwrite edits).
    LaunchedEffect(health?.deviceId) {
        val h = health ?: return@LaunchedEffect
        if (h.deviceId != null && h.deviceId != loadedFor) {
            loadedFor = h.deviceId
            name = h.deviceDisplayName ?: ""
            handle = h.deviceHandle ?: ""
        }
    }
    LaunchedEffect(phase) {
        if (phase == Phase.Success) {
            delay(2_000)
            phase = Phase.Idle
        }
    }

    fun save(h: Health?) {
        val n = name.trim()
        val hd = handle.trim()
        error = when {
            n.isEmpty() -> "设备名不能为空"
            hd.isNotEmpty() && !validHandle(hd) -> "助记符只能包含小写字母、数字和横杠"
            else -> null
        }
        if (error != null) return run { phase = Phase.Error }
        phase = Phase.Submitting
        scope.launch {
            val r = attempt {
                if (n != (h?.deviceDisplayName ?: "")) ctx.backend.request(Req.updateDisplayName(null, n))
                if (hd.isNotEmpty() && hd != (h?.deviceHandle ?: "")) ctx.backend.request(Req.updateHandle(null, hd))
            }
            error = r.errorMessage
            phase = if (r.isSuccess) Phase.Success else Phase.Error
        }
    }

    Card {
        SectionHeader("设备标识符", "显示名将展示给房间内的其他成员；助记符只能包含小写字母、数字和横杠，用于在规则中引用")
        Divider()
        if (!ready) Notice("客户端核心未就绪，暂不可编辑", MdiIcons.AlertOutline)
        TextInput(name, { name = it }, Modifier.fillMaxWidth(), label = "设备名", placeholder = "请输入设备名", enabled = ready)
        TextInput(handle, { handle = it }, Modifier.fillMaxWidth(), label = "设备助记符", placeholder = "如：alice-laptop", enabled = ready, mono = true)
        if (health != null) InlineNote("设备 ID：${health.deviceId ?: "—"}　用户 ID：${health.userId ?: "—"}", selectable = true)
        error?.let { InlineError(it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PrimaryButton(
                when (phase) {
                    Phase.Submitting -> "保存中…"
                    Phase.Success -> "✓ 已保存"
                    Phase.Error -> "保存失败，请重试"
                    Phase.Idle -> "保存"
                },
                { save(health) },
                enabled = ready,
                busy = phase == Phase.Submitting,
            )
        }
    }
}

// ── WebUI remote access ──────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteAccessCard(session: WebSession) {
    var cidrs by remember { mutableStateOf(listOf<String>()) }
    var port by remember { mutableStateOf<Int?>(null) }
    var input by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf(Phase.Idle) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(session) {
        val r = attempt { session.remoteAccess() }
        r.onSuccess {
            cidrs = it.allowedCidrs
            port = it.port
        }
        error = r.errorMessage
    }
    LaunchedEffect(phase) {
        if (phase == Phase.Success) {
            delay(2_000)
            phase = Phase.Idle
        }
    }
    fun add() {
        val v = input.trim()
        if (v.isNotEmpty() && v !in cidrs) cidrs = cidrs + v
        input = ""
    }

    Card {
        SectionHeader("远程管理", "允许访问 WebUI 的 IP 地址段（CIDR 格式）。监听端口：${port ?: "—"}")
        Divider()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cidrs.forEach { c -> Tag(c) { cidrs = cidrs - c } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(input, { input = it }, Modifier.weight(1f), placeholder = "如 192.168.1.0/24", mono = true, onDone = ::add)
            SecondaryButton("添加", ::add)
        }
        InlineNote("移除当前所用的地址段后，本浏览器将无法再访问 WebUI。")
        error?.let { InlineError(it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PrimaryButton(
                when (phase) {
                    Phase.Submitting -> "保存中…"
                    Phase.Success -> "✓ 已保存"
                    Phase.Error -> "保存失败"
                    Phase.Idle -> "保存"
                },
                {
                    phase = Phase.Submitting
                    error = null
                    scope.launch {
                        val r = attempt { session.setAllowedCidrs(cidrs) }
                        error = r.errorMessage
                        phase = if (r.isSuccess) Phase.Success else Phase.Error
                    }
                },
                enabled = cidrs.isNotEmpty(),
                busy = phase == Phase.Submitting,
            )
        }
    }
}

// ── Diagnostics and advanced network settings ────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticsCard() {
    val ctx = LocalController.current
    val view by ctx.store.view.collectAsState()
    val backendError by ctx.store.backendError.collectAsState()
    val ready = coreStatus(view, backendError) == CoreStatus.Ok
    val connected = view?.connected == true
    val health = if (connected) view?.state?.health else null
    val scope = rememberCoroutineScope()
    var showLogs by remember { mutableStateOf(false) }
    var reRegister by remember { mutableStateOf(Phase.Idle) }
    var reRegisterError by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }
    val c = Msst.colors

    Card {
        SectionHeader("诊断与调试", "查看日志、重新注册及高级网络控制")
        Divider()
        InfoBlock {
            InfoLine("控制器 ↔ 客户端核心：${connectionLabel(view?.connection)}")
            InfoLine("协议版本：控制器 ${view?.controllerProtocol?.ifEmpty { null } ?: "—"} / 客户端核心 ${view?.coreProtocol ?: "—"}")
            InfoLine(
                "协调服务器：${triState(health?.wsConnected, "已连接", "未连接")}　" +
                    "虚拟网卡：${triState(health?.tunReady, "就绪", "未就绪")}　中转：${health?.relay ?: "—"}",
            )
            InfoLine("累计断线 ${view?.diagnostics?.disconnections ?: 0} 次")
            view?.diagnostics?.lastError?.let { InfoLine("最近错误：$it", c.error) }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            SecondaryButton(
                when (reRegister) {
                    Phase.Submitting -> "注册中…"
                    Phase.Success -> "✓ 已重新注册"
                    else -> "重新注册"
                },
                {
                    reRegister = Phase.Submitting
                    reRegisterError = null
                    scope.launch {
                        val r = attempt { ctx.backend.request(Req.reRegister()) }
                        reRegisterError = r.errorMessage
                        reRegister = if (r.isSuccess) Phase.Success else Phase.Error
                        if (r.isSuccess) {
                            delay(2_000)
                            reRegister = Phase.Idle
                        }
                    }
                },
                enabled = ready && reRegister != Phase.Submitting,
            )
            PrimaryButton("查看日志", { showLogs = true }, enabled = connected)
        }
        reRegisterError?.let { InlineError("重新注册失败：$it") }
        Divider()
        LinkButton("高级选项", { advanced = !advanced }, if (advanced) MdiIcons.ChevronUp else MdiIcons.ChevronDown, color = c.textSecondary)
        if (advanced) AdvancedSections(ready)
    }

    if (showLogs) LogDialog { showLogs = false }
}

@Composable
private fun AdvancedSections(ready: Boolean) {
    val ctx = LocalController.current
    val scope = rememberCoroutineScope()
    val view by ctx.store.view.collectAsState()
    var settings by remember { mutableStateOf<NetSettings?>(null) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val open = remember { mutableStateMapOf<String, Boolean>() }
    val sectionError = remember { mutableStateMapOf<String, String?>() }

    suspend fun loadSettings() {
        val r = attempt { ctx.backend.query<NetSettings>(Req.getNetSettings()) }
        r.onSuccess { settings = it }
        settingsError = r.errorMessage?.let { "读取网络设置失败：$it" }
    }

    /** Send one changed field; show what Core stored. */
    fun patch(vararg kv: Pair<String, Any?>) {
        busy = true
        settingsError = null
        scope.launch {
            val r = attempt { ctx.backend.query<NetSettings>(Req.setNetSettings(obj(*kv))) }
            r.onSuccess { settings = it }
            r.errorMessage?.let {
                settingsError = "保存失败：$it"
                loadSettings()
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) { loadSettings() }
    // Settings changed elsewhere (another Controller): keep the panel current
    // without touching values being edited.
    LaunchedEffect(Unit) {
        ctx.store.changes.collect { n ->
            if (n == UiNotice.Changed && !busy) attempt { ctx.backend.query<NetSettings>(Req.getNetSettings()) }.onSuccess { if (!busy) settings = it }
        }
    }

    fun <T> loader(key: String, block: suspend () -> T, set: (T) -> Unit): () -> Unit = {
        scope.launch {
            val r = attempt { block() }
            r.onSuccess(set)
            sectionError[key] = r.errorMessage
        }
    }

    var pathView by remember { mutableStateOf(listOf<PathViewEntry>()) }
    var relays by remember { mutableStateOf<RelayList?>(null) }
    var relaysError by remember { mutableStateOf<String?>(null) }
    var traffic by remember { mutableStateOf(listOf<TrafficEntry>()) }
    var endpoints by remember { mutableStateOf<EndpointInfo?>(null) }
    val loadTraffic = loader("traffic", { ctx.backend.query<TrafficStats>(Req.getTrafficStats()) }) { traffic = it.peers }
    val loadEndpoints = loader("endpoints", { ctx.backend.query<EndpointInfo>(Req.getEndpoints()) }) { endpoints = it }

    // Path view polls every 2 s while its section is open.
    if (open["path"] == true) {
        LaunchedEffect(Unit) {
            val r = attempt { ctx.backend.query<RelayList>(Req.getRelays()) }
            relays = r.getOrNull()
            relaysError = r.errorMessage
        }
        LaunchedEffect(Unit) {
            while (isActive) {
                val r = attempt { ctx.backend.query<PathView>(Req.getPathView()) }
                r.onSuccess { pathView = it.peers }
                sectionError["path"] = r.errorMessage
                delay(2_000)
            }
        }
    }

    fun toggle(key: String) {
        val now = open[key] != true
        open[key] = now
        if (now && key == "trans") loadTraffic()
        if (now && key == "ep") loadEndpoints()
    }
    val c = Msst.colors
    val s = settings

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        settingsError?.let { InlineError(it) }

        Collapsible("路径选择", open["path"] == true, { toggle("path") }) {
            if (s != null) {
                ToggleRow("禁用 P2P 直连", "始终通过中转服务器转发流量", s.disableP2p, { patch("disable_p2p" to it) }, !busy)
                ToggleRow("锁定当前路径", "禁止任何路径切换（中转↔直连均冻结）", s.lockPath, { patch("lock_path" to it) }, !busy)
                FieldLabel("中转服务器偏好（重新连接协调服务器后生效）")
                val rl = relays
                when {
                    relaysError != null -> InlineError("中转列表读取失败：$relaysError")
                    rl == null -> InlineNote("（加载中…）")
                    else -> {
                        RadioRow("自动选择最优", s.preferredRelay == null, { patch("preferred_relay" to null) }, !busy)
                        rl.relays.forEach { r ->
                            val extra = (if (rl.current == r.addr) "（当前）" else "") + (rl.rttMs[r.addr]?.let { " ${oneDecimal(it)} ms" } ?: "")
                            RadioRow(r.addr + extra, s.preferredRelay == r.addr, { patch("preferred_relay" to r.addr) }, !busy, mono = true)
                        }
                    }
                }
            }
            FieldLabel("路径视图（只读）")
            sectionError["path"]?.let { InlineError(it) }
            if (pathView.isEmpty()) InlineNote("暂无路径数据")
            pathView.forEach { e ->
                InfoBlock {
                    InfoLine(e.displayName?.let { "$it (${e.deviceId})" } ?: e.deviceId, mono = true)
                    InfoLine("选中: ${e.chosen}", c.textPrimary)
                    e.candidates.forEach { cand ->
                        InfoLine(
                            "${cand.addr}  ${cand.rttMs?.let { "${oneDecimal(it)} ms" } ?: "-"}  " +
                                (cand.lossRate?.let { "loss ${kotlin.math.round(it * 100).toInt()}%" } ?: "") + "  [${cand.status}]",
                            mono = true,
                        )
                    }
                }
            }
        }

        Collapsible("网络传输", open["trans"] == true, { toggle("trans") }) {
            if (s != null) {
                FieldLabel("MTU（重启客户端核心后生效）")
                NumberOverride(s.mtuOverride, "1280–1500", 1280..1500, "MTU 应为 1280–1500 之间的整数", "自动", busy,
                    onAuto = { patch("mtu_override" to null) }, onApply = { patch("mtu_override" to it) }, onError = { settingsError = it })
                FieldLabel("DNS 配置模式（仅 Linux）")
                RadioRow("优先使用 DNS 分流（默认）", s.linuxDnsMode != "iptables_only", { patch("linux_dns_mode" to "prefer_split") }, !busy,
                    hint = "优先通过 systemd-resolved 配置，不可用时自动切换为 DNS 拦截")
                RadioRow("仅使用 DNS 拦截", s.linuxDnsMode == "iptables_only", { patch("linux_dns_mode" to "iptables_only") }, !busy,
                    hint = "仅使用 iptables 配置 DNS 拦截规则，跳过 systemd-resolved")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("流量计数（路径切换时自动重置）", Modifier.weight(1f))
                LinkButton("刷新", loadTraffic, MdiIcons.Refresh)
            }
            sectionError["traffic"]?.let { InlineError(it) }
            if (traffic.isEmpty()) InlineNote("暂无流量数据")
            traffic.forEach { t ->
                InfoLine("${t.displayName ?: t.deviceId}  ↑ ${formatBytes(t.tx)}  ↓ ${formatBytes(t.rx)}  丢弃 ${t.drop}", mono = true)
            }
        }

        Collapsible("穿透控制", open["ep"] == true, { toggle("ep") }) {
            sectionError["endpoints"]?.let { InlineError(it) }
            FieldLabel("当前端点")
            val ep = endpoints
            if (ep == null || ep.current.isEmpty()) InlineNote("（未探测到）") else ep.current.forEach { InfoLine(it, mono = true) }
            if (ep != null && ep.history.isNotEmpty()) {
                FieldLabel("历史端点")
                ep.history.forEach { InfoLine("${formatTime(it.timestamp * 1000)}  ${it.endpoints.joinToString(", ")}", mono = true) }
            }
            if (s != null) {
                FieldLabel("保活间隔（P2P + 中转均生效）")
                NumberOverride(s.keepaliveSecs, "5–30 秒", 5..30, "保活间隔应为 5–30 秒之间的整数", "自动 (25 s)", busy,
                    onAuto = { patch("keepalive_secs" to null) }, onApply = { patch("keepalive_secs" to it) }, onError = { settingsError = it })
            }
            var refreshNote by remember { mutableStateOf<String?>(null) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                refreshNote?.let { InlineNote(it, Modifier.weight(1f)) }
                SecondaryButton("强制刷新端点", {
                    refreshNote = "已请求重新探测…"
                    scope.launch {
                        val r = attempt { ctx.backend.request(Req.refreshEndpoint()) }
                        if (r.isFailure) {
                            refreshNote = "请求失败：${r.errorMessage}"
                        } else {
                            // Detection runs in Core asynchronously; re-read shortly after.
                            delay(3_000)
                            loadEndpoints()
                        }
                    }
                }, enabled = ready)
            }
        }

        Collapsible("其他诊断工具", open["diag"] == true, { toggle("diag") }) {
            DiagPing(view?.state?.nodes?.values?.toList().orEmpty().map { it.deviceId to (it.displayName ?: it.deviceId) }, ready)
        }
    }
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = Msst.colors.textSecondary)

/** "Automatic" or a custom integer within [range] (MTU, keepalive). */
@Composable
private fun NumberOverride(
    current: Int?,
    placeholder: String,
    range: IntRange,
    rangeError: String,
    autoLabel: String,
    busy: Boolean,
    onAuto: () -> Unit,
    onApply: (Int) -> Unit,
    onError: (String) -> Unit,
) {
    var custom by remember(current) { mutableStateOf(current != null) }
    var input by remember(current) { mutableStateOf(current?.toString() ?: "") }
    RadioRow(autoLabel, !custom, { custom = false; onAuto() }, !busy)
    RadioRow("自定义", custom, { custom = true }, !busy)
    if (custom) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(input, { v -> input = v.filter(Char::isDigit) }, Modifier.width(160.dp), placeholder = placeholder, number = true)
            SecondaryButton("应用", {
                val v = input.toIntOrNull()
                if (v == null || v !in range) onError(rangeError) else onApply(v)
            }, enabled = !busy)
        }
    }
}

@Composable
private fun DiagPing(peers: List<Pair<String, String>>, ready: Boolean) {
    val ctx = LocalController.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(listOf<Double?>()) }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    fun start() {
        if (target.isBlank() || running) return
        running = true
        done = false
        results = emptyList()
        error = null
        job = scope.launch {
            val started = attempt { ctx.backend.query<DiagPingJob>(Req.startDiagPing(target.trim())) }
            val jobId = started.getOrElse {
                running = false
                error = started.errorMessage
                return@launch
            }.jobId
            while (isActive) {
                delay(500)
                val r = attempt { ctx.backend.query<DiagPingResult>(Req.getDiagPingResult(jobId)) }
                val p = r.getOrElse {
                    running = false
                    error = r.errorMessage
                    return@launch
                }
                results = p.results
                done = p.done
                running = !p.done
                if (p.done) break
            }
        }
    }

    FieldLabel("Ping 节点")
    if (peers.isNotEmpty()) {
        Select(peers, peers.firstOrNull { it.first == target }, { "${it.second}（${it.first}）" }, { target = it.first }, Modifier.fillMaxWidth(),
            placeholder = "从节点列表选择", enabled = !running)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextInput(target, { target = it }, Modifier.weight(1f), placeholder = "目标设备 ID", enabled = !running, mono = true, onDone = ::start)
        SecondaryButton(if (running) "进行中…" else "开始", ::start, enabled = target.isNotBlank() && !running && ready)
    }
    error?.let { InlineError(it) }
    if (results.isNotEmpty()) {
        val rtts = results.filterNotNull()
        val avg = if (rtts.isNotEmpty()) rtts.average() else null
        InfoBlock {
            InfoLine("发送 ${results.size} / 收到 ${rtts.size} / 丢失 ${results.size - rtts.size}" + (avg?.let { " / 平均 RTT ${oneDecimal(it)} ms" } ?: ""))
            InfoLine(results.mapIndexed { i, r -> "#${i + 1}: ${r?.let { "${oneDecimal(it)} ms" } ?: "超时"}" }.joinToString("  "), mono = true)
            if (!done) InfoLine("进行中…", Msst.colors.primary)
        }
    }
}
