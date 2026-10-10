package com.msst.net.ui

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.msst.net.AppPreferences
import com.msst.net.controller.Connection
import com.msst.net.controller.ControllerView
import com.msst.net.ui.theme.*
import com.msst.net.viewmodel.LogEntry
import com.msst.net.viewmodel.MainViewModel
import com.msst.net.viewmodel.SaveStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

// ── 配色预览色映射 ─────────────────────────────────────────────────────────────

fun getColorForScheme(option: ColorSchemeOption): Color = when (option) {
    ColorSchemeOption.RED         -> Red500
    ColorSchemeOption.PINK        -> Pink500
    ColorSchemeOption.PURPLE      -> Purple500
    ColorSchemeOption.DEEP_PURPLE -> DeepPurple500
    ColorSchemeOption.INDIGO      -> Indigo500
    ColorSchemeOption.BLUE        -> Blue500
    ColorSchemeOption.LIGHT_BLUE  -> LightBlue500
    ColorSchemeOption.CYAN        -> Cyan500
    ColorSchemeOption.TEAL        -> Teal500
    ColorSchemeOption.GREEN       -> Green500
    ColorSchemeOption.LIGHT_GREEN -> LightGreen500
    ColorSchemeOption.LIME        -> Lime500
    ColorSchemeOption.YELLOW      -> Yellow500
    ColorSchemeOption.AMBER       -> Amber500
    ColorSchemeOption.ORANGE      -> Orange500
    ColorSchemeOption.DEEP_ORANGE -> DeepOrange500
    ColorSchemeOption.BROWN       -> Brown500
    ColorSchemeOption.GREY        -> Grey500
    ColorSchemeOption.BLUE_GREY   -> BlueGrey500
}

// ── SettingsScreen ────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    onSystemColorChanged: ((Boolean) -> Unit)? = null,
    onColorSchemeChanged: ((ColorSchemeOption) -> Unit)? = null,
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val view by viewModel.view.collectAsStateWithLifecycle()
    val saveStatus by viewModel.saveStatus.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val health = if (view?.connected == true) view?.health else null
    val coreReady = view?.coreReady == true

    var showLogDialog by remember { mutableStateOf(false) }
    var logLevelFilter by remember { mutableStateOf("all") }
    val logLevels = listOf("all", "error", "warn", "info", "debug", "trace")
    var exportNote by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) {
            val filtered = if (logLevelFilter == "all") logs else logs.filter { it.level.equals(logLevelFilter, ignoreCase = true) }
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            val content = filtered.joinToString("\n") { e -> "[${fmt.format(Date(e.timestampMs))}] [${e.level.uppercase()}] ${e.target}: ${e.message}" }
            exportNote = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                "已导出 ${filtered.size} 条日志"
            }.getOrElse { "导出失败：${it.message}" }
        }
    }

    var useSystemColor by remember { mutableStateOf(prefs.useSystemColor) }
    var selectedColorScheme by remember { mutableStateOf(ColorSchemeOption.fromKey(prefs.colorScheme)) }
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // Identity form: filled from Core once per device ID, then edited locally.
    var deviceName by remember { mutableStateOf("") }
    var deviceHandle by remember { mutableStateOf("") }
    var loadedFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(health?.deviceId) {
        val h = health
        if (h?.deviceId != null && h.deviceId != loadedFor) {
            loadedFor = h.deviceId
            deviceName = h.deviceDisplayName ?: ""
            deviceHandle = h.deviceHandle ?: ""
        }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Appearance ──
        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("外观", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("自定义应用程序外观", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Palette, "配色", Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("使用系统配色", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        Text(
                            if (supportsDynamicColor) (if (useSystemColor) "跟随系统壁纸配色" else "使用自定义配色") else "此设备不支持动态颜色",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = useSystemColor,
                        onCheckedChange = { useSystemColor = it; prefs.useSystemColor = it; onSystemColorChanged?.invoke(it) },
                        enabled = supportsDynamicColor,
                    )
                }
                AnimatedVisibility(!useSystemColor, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Text("选择配色方案", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 12.dp))
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ColorSchemeOption.entries.forEach { option ->
                                ColorChip(option, selectedColorScheme == option) {
                                    selectedColorScheme = option
                                    prefs.colorScheme = option.key
                                    onColorSchemeChanged?.invoke(option)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Account (read-only) ──
        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("账户信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("由系统自动生成，不可修改", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                if (health?.userId != null) {
                    ReadOnlyRow(Icons.Outlined.Badge, "用户 ID", health.userId, isMonospace = true)
                    Spacer(Modifier.height(12.dp))
                    ReadOnlyRow(Icons.Outlined.DevicesOther, "设备 ID", health.deviceId ?: "", isMonospace = true)
                } else {
                    Text("暂无账户信息，请确认客户端核心已启动", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ── Device identifiers (editable) ──
        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("设备标识符", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("设备名将展示给房间内的其他成员；助记符只能包含小写字母、数字和横杠，用于在规则中引用",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                if (!coreReady) Text("⚠ 客户端核心未就绪，暂不可编辑", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(deviceName, { deviceName = it }, label = { Text("设备名") },
                    leadingIcon = { Icon(Icons.Outlined.DevicesOther, null, tint = MaterialTheme.colorScheme.primary) },
                    placeholder = { Text("请输入设备名") }, singleLine = true, enabled = coreReady, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(deviceHandle, { v -> deviceHandle = v.filter { it.isLowerCase() || it.isDigit() || it == '-' } },
                    label = { Text("设备助记符") },
                    leadingIcon = { Icon(Icons.Outlined.DevicesOther, null, tint = MaterialTheme.colorScheme.primary) },
                    placeholder = { Text("例：laptop") }, singleLine = true, enabled = coreReady, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SaveStatusButton(saveStatus, onSave = { viewModel.saveIdentity(deviceName, deviceHandle) }, enabled = coreReady && deviceName.isNotBlank())
                }
            }
        }

        DiagnosticsCard(view, viewModel, onShowLogs = { showLogDialog = true; viewModel.loadLogs() })
        exportNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    if (showLogDialog) {
        LogDialog(
            entries = logs,
            levelFilter = logLevelFilter,
            levels = logLevels,
            onFilterChange = { logLevelFilter = it },
            onClear = viewModel::clearLogs,
            onExport = { exportLauncher.launch("msst-net-logs.txt") },
            onDismiss = { showLogDialog = false },
        )
    }
}

private fun connectionLabel(v: ControllerView?): String = when (val c = v?.connection) {
    null -> "未知"
    Connection.Connected -> "已连接"
    Connection.Connecting -> "连接中"
    Connection.Disconnected -> "未连接"
    Connection.Incompatible -> "版本不兼容"
    is Connection.Reconnecting -> "重连中（第 ${c.attempt} 次）"
}

private fun triState(b: Boolean?, yes: String, no: String) = when (b) { null -> "未知"; true -> yes; false -> no }

// ── Diagnostics ──────────────────────────────────────────────────────────────

@Composable
private fun DiagnosticsCard(view: ControllerView?, vm: MainViewModel, onShowLogs: () -> Unit) {
    val netSettings by vm.netSettings.collectAsStateWithLifecycle()
    val pathView by vm.pathView.collectAsStateWithLifecycle()
    val traffic by vm.traffic.collectAsStateWithLifecycle()
    val endpoints by vm.endpoints.collectAsStateWithLifecycle()
    val relays by vm.relays.collectAsStateWithLifecycle()
    val relaysError by vm.relaysError.collectAsStateWithLifecycle()
    val pingState by vm.pingState.collectAsStateWithLifecycle()
    val coreReady = view?.coreReady == true
    val health = if (view?.connected == true) view.health else null

    var advancedExpanded by remember { mutableStateOf(false) }
    var pathExpanded by remember { mutableStateOf(false) }
    var netExpanded by remember { mutableStateOf(false) }
    var endpointExpanded by remember { mutableStateOf(false) }
    var diagExpanded by remember { mutableStateOf(false) }
    var mtuInput by remember(netSettings?.mtuOverride) { mutableStateOf(netSettings?.mtuOverride?.toString() ?: "") }
    var keepaliveInput by remember(netSettings?.keepaliveSecs) { mutableStateOf(netSettings?.keepaliveSecs?.toString() ?: "") }
    var mtuCustom by remember(netSettings?.mtuOverride) { mutableStateOf(netSettings?.mtuOverride != null) }
    var kaCustom by remember(netSettings?.keepaliveSecs) { mutableStateOf(netSettings?.keepaliveSecs != null) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var pingInput by remember { mutableStateOf("") }

    DisposableEffect(Unit) { onDispose { vm.stopPathViewPolling() } }

    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text("诊断与调试", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("查看日志、重新注册及高级网络控制", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val small = MaterialTheme.typography.bodySmall
                    Text("控制器 ↔ 客户端核心：${connectionLabel(view)}", style = small)
                    Text("协议版本：控制器 ${view?.controllerProtocol ?: "—"} / 客户端核心 ${view?.coreProtocol ?: "—"}", style = small)
                    Text("协调服务器：${triState(health?.wsConnected, "已连接", "未连接")}　虚拟网卡：${triState(health?.tunReady, "就绪", "未就绪")}　中转：${health?.relay ?: "—"}", style = small)
                    Text("累计断线 ${view?.disconnections ?: 0} 次", style = small)
                    view?.lastError?.let { Text("最近错误：$it", style = small, color = MaterialTheme.colorScheme.error) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = vm::reRegister, enabled = coreReady, modifier = Modifier.height(40.dp)) { Text("重新注册", fontWeight = FontWeight.SemiBold) }
                Button(onClick = onShowLogs, enabled = view?.connected == true, modifier = Modifier.height(40.dp)) { Text("查看日志", fontWeight = FontWeight.SemiBold) }
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable {
                    advancedExpanded = !advancedExpanded
                    if (advancedExpanded) vm.loadNetSettings()
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("高级选项", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Icon(if (advancedExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
            }
            AnimatedVisibility(advancedExpanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    inputError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    val s = netSettings

                    AdvancedSection("路径选择", pathExpanded, onToggle = {
                        pathExpanded = !pathExpanded
                        if (pathExpanded) { vm.loadRelays(); vm.startPathViewPolling() } else vm.stopPathViewPolling()
                    }) {
                        if (s != null) {
                            SettingSwitch("禁用 P2P 直连", "始终通过中转服务器转发流量", s.disableP2p) {
                                vm.patchNetSettings(JSONObject().put("disable_p2p", it))
                            }
                            SettingSwitch("锁定当前路径", "禁止任何路径切换（中转↔直连均冻结）", s.lockPath) {
                                vm.patchNetSettings(JSONObject().put("lock_path", it))
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("中转服务器偏好（重新连接协调服务器后生效）", style = MaterialTheme.typography.bodyMedium)
                                when {
                                    relaysError != null -> Text("中转列表读取失败：$relaysError", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    relays.isEmpty() -> Text("（加载中…）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    else -> (listOf<String?>(null) + relays.map { it.addr }).forEach { addr ->
                                        Row(
                                            Modifier.fillMaxWidth().clickable {
                                                vm.patchNetSettings(JSONObject().put("preferred_relay", addr ?: JSONObject.NULL))
                                            }.padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(selected = s.preferredRelay == addr, onClick = null)
                                            Spacer(Modifier.width(8.dp))
                                            Text(addr ?: "自动选择最优", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("路径视图", style = MaterialTheme.typography.bodyMedium)
                            if (pathView.isEmpty()) {
                                Text("暂无路径数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else pathView.forEach { e ->
                                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(e.displayName?.let { "$it (${e.deviceId})" } ?: e.deviceId, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                                        Text("选中: ${e.chosen}", style = MaterialTheme.typography.bodySmall)
                                        e.candidates.forEach { c ->
                                            Text(
                                                "  ${c.addr}  ${c.rttMs?.let { "%.1f ms".format(it) } ?: "-"}  ${c.lossRate?.let { "loss %.0f%%".format(it * 100) } ?: ""}  [${c.status}]",
                                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    AdvancedSection("网络传输", netExpanded, onToggle = { netExpanded = !netExpanded; if (netExpanded) vm.loadTraffic() }) {
                        if (s != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("MTU（重启客户端核心后生效）", style = MaterialTheme.typography.bodyMedium)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    RadioLabel("自动", !mtuCustom) { mtuCustom = false; vm.patchNetSettings(JSONObject().put("mtu_override", JSONObject.NULL)) }
                                    RadioLabel("自定义", mtuCustom) { mtuCustom = true }
                                }
                                if (mtuCustom) {
                                    OutlinedTextField(mtuInput, { v -> mtuInput = v.filter(Char::isDigit) }, label = { Text("MTU 值（1280–1500）") }, singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        trailingIcon = {
                                            TextButton(onClick = {
                                                // Range defined by Core (protocol response P7).
                                                val v = mtuInput.toIntOrNull()
                                                if (v == null || v !in 1280..1500) inputError = "MTU 应为 1280–1500 之间的整数"
                                                else { inputError = null; vm.patchNetSettings(JSONObject().put("mtu_override", v)) }
                                            }) { Text("应用") }
                                        })
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("流量计数（路径切换时自动重置）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = vm::loadTraffic) { Text("刷新") }
                            }
                            if (traffic.isEmpty()) {
                                Text("暂无流量数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else traffic.forEach { t ->
                                Text("${t.displayName ?: t.deviceId}  ↑ ${formatBytes(t.tx)}  ↓ ${formatBytes(t.rx)}  丢弃 ${t.drop}",
                                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    AdvancedSection("穿透控制", endpointExpanded, onToggle = { endpointExpanded = !endpointExpanded; if (endpointExpanded) vm.loadEndpoints() }) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("当前端点", style = MaterialTheme.typography.bodyMedium)
                            if (endpoints.current.isEmpty()) Text("（未探测到）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            endpoints.current.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                        }
                        if (endpoints.history.isNotEmpty()) {
                            val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.US) }
                            Text("历史端点", style = MaterialTheme.typography.bodyMedium)
                            endpoints.history.forEach { r ->
                                Text("${fmt.format(Date(r.timestamp * 1000L))}  ${r.endpoints.joinToString(", ")}", style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (s != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("保活间隔（P2P + 中转均生效）", style = MaterialTheme.typography.bodyMedium)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    RadioLabel("自动 (25 s)", !kaCustom) { kaCustom = false; vm.patchNetSettings(JSONObject().put("keepalive_secs", JSONObject.NULL)) }
                                    RadioLabel("自定义", kaCustom) { kaCustom = true }
                                }
                                if (kaCustom) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(keepaliveInput, { v -> keepaliveInput = v.filter(Char::isDigit) }, label = { Text("秒数 (5–30)") },
                                            singleLine = true, modifier = Modifier.weight(1f))
                                        TextButton(onClick = {
                                            val v = keepaliveInput.toIntOrNull()
                                            if (v == null || v !in 5..30) inputError = "保活间隔应为 5–30 秒之间的整数"
                                            else { inputError = null; vm.patchNetSettings(JSONObject().put("keepalive_secs", v)) }
                                        }) { Text("应用") }
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            OutlinedButton(onClick = vm::refreshEndpoint, enabled = coreReady, modifier = Modifier.height(40.dp)) {
                                Text("强制刷新端点", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    AdvancedSection("其他诊断工具", diagExpanded, onToggle = { diagExpanded = !diagExpanded }) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Ping 节点", style = MaterialTheme.typography.bodyMedium)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(pingInput, { pingInput = it }, label = { Text("目标设备 ID") }, singleLine = true,
                                    modifier = Modifier.weight(1f), enabled = !pingState.running)
                                Button(onClick = { vm.startPing(pingInput.trim()) }, enabled = pingInput.isNotBlank() && !pingState.running && coreReady,
                                    modifier = Modifier.height(56.dp)) {
                                    if (pingState.running) CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                    else Text("开始")
                                }
                            }
                            pingState.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            if (pingState.results.isNotEmpty()) {
                                val sent = pingState.results.size
                                val recv = pingState.results.count { it != null }
                                val avg = pingState.results.filterNotNull().takeIf { it.isNotEmpty() }?.average()
                                Text("发送 $sent / 收到 $recv / 丢失 ${sent - recv}" + (avg?.let { " / 平均 RTT ${"%.1f".format(it)} ms" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall)
                                Text(pingState.results.mapIndexed { i, v -> "#${i + 1}: ${v?.let { "%.1f ms".format(it) } ?: "超时"}" }.joinToString("  "),
                                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                if (!pingState.done) Text("进行中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RadioLabel(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.clickable(onClick = onClick).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AdvancedSection(
    title:    String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content:  @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color  = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape  = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style      = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier   = Modifier.weight(1f),
                )
                Icon(
                    imageVector        = if (expanded)
                        Icons.Filled.KeyboardArrowUp
                    else
                        Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier           = Modifier.size(20.dp),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter   = fadeIn() + expandVertically(),
                exit    = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "${"%.1f".format(bytes / 1_073_741_824.0)} GB"
    bytes >= 1_048_576L     -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
    bytes >= 1_024L         -> "${"%.1f".format(bytes / 1_024.0)} KB"
    else                    -> "$bytes B"
}

// ── 日志弹窗 ──────────────────────────────────────────────────────────────────

@Composable
private fun LogDialog(
    entries:       List<LogEntry>,
    levelFilter:   String,
    levels:        List<String>,
    onFilterChange: (String) -> Unit,
    onClear:       () -> Unit,
    onExport:      () -> Unit,
    onDismiss:     () -> Unit,
) {
    val filtered = if (levelFilter == "all") entries else entries.filter { it.level == levelFilter }
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("客户端核心日志") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 级别筛选
                Row(
                    modifier              = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    levels.forEach { lvl ->
                        val selected = lvl == levelFilter
                        FilterChip(
                            selected = selected,
                            onClick  = { onFilterChange(lvl) },
                            label    = { Text(if (lvl == "all") "全部" else lvl.uppercase(), fontSize = 11.sp) },
                            modifier = Modifier.height(28.dp),
                        )
                    }
                }
                // 日志列表
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 320.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                "暂无日志",
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                style    = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        items(filtered) { entry ->
                            val levelColor = when (entry.level) {
                                "error" -> MaterialTheme.colorScheme.error
                                "warn"  -> MaterialTheme.colorScheme.tertiary
                                "info"  -> MaterialTheme.colorScheme.primary
                                else    -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    fmt.format(Date(entry.timestampMs)),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize   = 10.sp,
                                    color      = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier   = Modifier.alignByBaseline(),
                                )
                                Text(
                                    "[${entry.level.uppercase()}]",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize   = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color      = levelColor,
                                    modifier   = Modifier.alignByBaseline(),
                                )
                                Text(
                                    entry.message,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize   = 10.sp,
                                    color      = MaterialTheme.colorScheme.onSurface,
                                    modifier   = Modifier.weight(1f).alignByBaseline(),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClear)  { Text("清空") }
                TextButton(onClick = onExport) { Text("导出") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

// ── 配色选择芯片 ──────────────────────────────────────────────────────────────

@Composable
private fun ColorChip(
    option:     ColorSchemeOption,
    isSelected: Boolean,
    onClick:    () -> Unit,
) {
    val color       = getColorForScheme(option)
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(56.dp)
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (isSelected) 3.dp else 0.dp,
                    color = borderColor,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelected) {
                Icon(
                    imageVector        = Icons.Filled.Check,
                    contentDescription = "已选择",
                    tint               = if (option in listOf(
                            ColorSchemeOption.YELLOW,
                            ColorSchemeOption.LIME,
                            ColorSchemeOption.AMBER,
                        )) Grey900 else Color.White,
                    modifier           = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text  = option.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

// ── 只读行 ────────────────────────────────────────────────────────────────────

@Composable
private fun ReadOnlyRow(
    icon:        androidx.compose.ui.graphics.vector.ImageVector,
    label:       String,
    value:       String,
    isMonospace: Boolean = false,
) {
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            modifier           = Modifier.size(20.dp),
            tint               = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                style      = MaterialTheme.typography.bodySmall,
                fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
                color      = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ── 保存按钮 ──────────────────────────────────────────────────────────────────

@Composable
private fun SaveStatusButton(saveStatus: SaveStatus, onSave: () -> Unit, enabled: Boolean = true) {
    val label = when (saveStatus) {
        SaveStatus.IDLE   -> "保存"
        SaveStatus.SAVING -> "保存中…"
        SaveStatus.SAVED  -> "已保存"
        SaveStatus.ERROR  -> "保存失败，重试"
    }
    val containerColor = when (saveStatus) {
        SaveStatus.SAVED -> MaterialTheme.colorScheme.secondary
        SaveStatus.ERROR -> MaterialTheme.colorScheme.error
        else             -> MaterialTheme.colorScheme.primary
    }

    Button(
        onClick  = onSave,
        enabled  = enabled && saveStatus != SaveStatus.SAVING,
        colors   = ButtonDefaults.buttonColors(containerColor = containerColor),
        modifier = Modifier.height(40.dp),
    ) {
        if (saveStatus == SaveStatus.SAVING) {
            CircularProgressIndicator(
                modifier    = Modifier.size(16.dp),
                color       = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}