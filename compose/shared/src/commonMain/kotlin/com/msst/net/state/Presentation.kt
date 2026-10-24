package com.msst.net.state

// Pure presentation helpers: how Core's state is described to users.  They
// only describe what Core reported; nothing here decides networking state.

import com.msst.net.model.AclGroup
import com.msst.net.model.ConnectionStatus
import com.msst.net.model.ControllerView
import com.msst.net.model.FirewallRule
import com.msst.net.model.NetworkWarning
import com.msst.net.model.PermissionGroup
import com.msst.net.model.ProtocolPort
import com.msst.net.model.ResolvedPrincipal
import com.msst.net.model.ServiceItem
import com.msst.net.model.RoomRelay
import com.msst.net.model.SystemState
import com.msst.net.platform.DeviceApp

/** Overall status shown in the status bar. */
enum class CoreStatus { Ok, Warning, Error, Checking }

fun coreStatus(view: ControllerView?, backendError: String?): CoreStatus {
    val v = view ?: return if (backendError != null) CoreStatus.Error else CoreStatus.Checking
    return when (v.connection) {
        ConnectionStatus.Connected -> {
            val h = v.state.health
            when {
                h == null -> if (v.state.sync.failed) CoreStatus.Warning else CoreStatus.Checking
                h.status != "ok" -> CoreStatus.Warning
                coordinatorUnreachable(v) -> CoreStatus.Warning
                else -> CoreStatus.Ok
            }
        }
        ConnectionStatus.Connecting -> CoreStatus.Checking
        else -> CoreStatus.Error
    }
}

/**
 * Core reports that its coordinator connection is down (`Health.ws_connected
 * = false`).  Unknown (older Cores, Health not read yet) is not "down".
 */
fun coordinatorUnreachable(view: ControllerView?): Boolean =
    view?.connected == true && view.state.health?.wsConnected == false

/** Core itself is usable: connected and its Health is `ok` (the
 *  coordinator connection may still be down). */
fun coreReady(view: ControllerView?): Boolean =
    view?.connected == true && view.state.health?.status == "ok"

/** Actions that need the coordinator — creating or joining rooms, access
 *  authentication — can be offered. */
fun coordinatorActionsAvailable(view: ControllerView?): Boolean =
    coreReady(view) && !coordinatorUnreachable(view)

/** One-line explanation of [coreStatus] for the status bar. */
fun coreStatusLabel(view: ControllerView?, backendError: String?): String {
    val v = view ?: return if (backendError != null) "无法读取控制器状态：$backendError" else "检查中…"
    return when (coreStatus(v, backendError)) {
        CoreStatus.Ok -> if (v.state.health?.coordinatorCredential == "guest") "客户端核心状态正常（访客身份：仅可使用房间模式）" else "客户端核心状态正常"
        CoreStatus.Warning -> when {
            coordinatorUnreachable(v) -> "客户端核心无法连接协调服务器"
            v.state.sync.failed -> "客户端核心部分状态读取失败"
            else -> "客户端核心状态异常"
        }
        CoreStatus.Checking -> if (v.connected) "正在读取客户端核心状态…" else "正在连接客户端核心…"
        CoreStatus.Error -> if (v.connection == ConnectionStatus.Incompatible) "客户端核心版本不兼容" else "客户端核心可能没有启动"
    }
}

enum class BannerKind { Info, Warn, Error }

data class Banner(val kind: BannerKind, val text: String, val detail: String? = null)

/** Why Core data may be missing, stale or read-only (AGENTS.md §7.4);
 *  null when everything is current. */
fun coreBanner(view: ControllerView?, backendError: String?, authPlace: String): Banner? {
    val v = view ?: return backendError?.let { Banner(BannerKind.Error, "无法读取控制器状态", it) }
    val d = v.diagnostics
    val s = v.state
    return when {
        v.connection == ConnectionStatus.Incompatible -> Banner(
            BannerKind.Error,
            "客户端核心的协议版本（${v.coreProtocol ?: "未知"}）与控制器（${v.controllerProtocol}）不兼容，请将两者更新到同一版本",
            d.lastError,
        )
        v.connection is ConnectionStatus.Reconnecting ->
            Banner(BannerKind.Error, "未连接到客户端核心，正在重试…以下内容可能已过期", d.lastError)
        v.connection == ConnectionStatus.Disconnected && d.successfulConnects > 0 ->
            Banner(BannerKind.Warn, "与客户端核心的连接已断开，正在重连…以下内容可能已过期", d.lastError)
        !v.connected -> null // first connect; the status bar shows progress
        s.sync.syncing -> Banner(BannerKind.Info, "正在从客户端核心读取最新状态…")
        s.sync.failed -> Banner(BannerKind.Warn, "部分状态读取失败，相关内容可能缺失", s.sync.message)
        // While the coordinator is unreachable authentication is impossible;
        // the status bar explains why.
        s.accessAuthRequired && s.health?.isAuthenticated != true && !coordinatorUnreachable(v) ->
            Banner(BannerKind.Warn, "MSST-Net 接入认证未完成或已过期，请在${authPlace}完成接入认证")
        s.health?.isViewer == true -> Banner(
            BannerKind.Info,
            "只读模式：当前账户可以查看状态，但不能进行更改。如需管理权限，请将该账户加入 msst-net 用户组（Windows：MSST-Net Administrators）后重新启动控制器",
        )
        else -> null
    }
}

/** Controller ↔ Core channel status in words (diagnostics). */
fun connectionLabel(c: ConnectionStatus?): String = when (c) {
    null -> "未知"
    ConnectionStatus.Connected -> "已连接"
    ConnectionStatus.Connecting -> "连接中"
    ConnectionStatus.Disconnected -> "未连接"
    ConnectionStatus.Incompatible -> "版本不兼容"
    is ConnectionStatus.Reconnecting -> "重连中（第 ${c.attempt} 次）"
    is ConnectionStatus.Unknown -> c.raw
}

/** Yes/no/unknown for an optional Core flag. */
fun triState(b: Boolean?, yes: String, no: String) = when (b) {
    null -> "未知"
    true -> yes
    false -> no
}

private val OPERATION_STAGES = mapOf(
    "relay_selected" to "已选定中转服务器",
    "room_created" to "房间已创建，正在加入",
    "join_sent" to "已发送加入请求，等待确认",
)

/** Progress of a running [requestType] operation as reported by Core
 *  (protocol 0.4), or null when none is known. */
fun operationStage(state: SystemState?, requestType: String): String? {
    val op = state?.operations?.values?.firstOrNull { it.requestType == requestType && it.result == null } ?: return null
    return op.stage?.let { OPERATION_STAGES[it] }
}

/** Path mode as reported by Core, in words; null when Core reported none. */
fun pathLabel(mode: String?): String? = when {
    mode == null -> null
    mode == "relay" -> "中转"
    mode == "probing" -> "探测直连中"
    mode.startsWith("direct") -> "直连"
    else -> mode
}

private val PATH_REASONS = mapOf(
    "probe" to "发现直连候选，开始探测",
    "punch" to "协调服务器发起打洞",
    "upgrade" to "直连探测成功",
    "switch" to "换到更优的直连端点",
    "no_pong" to "直连无响应",
    "loss" to "直连丢包过多",
    "rtt_spike" to "直连延迟持续升高",
    "data_stalled" to "直连能 ping 通但数据不通",
    "probe_failed" to "所有直连候选均无响应",
    "p2p_disabled" to "已关闭 P2P",
)

/** Why the path last changed (`PathChanged.reason`), in words; unknown
 *  reasons (newer Cores) are shown as sent. */
fun pathReasonLabel(reason: String?): String? = reason?.let { PATH_REASONS[it] ?: it }

/** [pathLabel] with the reason it changed, e.g. `中转（直连无响应）`. */
fun pathWithReason(mode: String?, reason: String?): String? {
    val label = pathLabel(mode) ?: return null
    return pathReasonLabel(reason)?.let { "$label（$it）" } ?: label
}

fun formatBytes(b: Long): String = when {
    b >= 1_073_741_824 -> "${oneDecimal(b / 1_073_741_824.0)} GB"
    b >= 1_048_576 -> "${oneDecimal(b / 1_048_576.0)} MB"
    b >= 1_024 -> "${oneDecimal(b / 1_024.0)} KB"
    else -> "$b B"
}

/** `x.y` with one decimal (no platform formatter in common code). */
fun oneDecimal(v: Double): String {
    val tenths = kotlin.math.round(v * 10).toLong()
    val sign = if (tenths < 0) "-" else ""
    val abs = kotlin.math.abs(tenths)
    return "$sign${abs / 10}.${abs % 10}"
}

fun formatPort(p: ProtocolPort): String = when {
    p.start == p.end -> p.start.toString()
    p.start == 1 && p.end == 65535 -> "*"
    else -> "${p.start}-${p.end}"
}

fun formatPorts(pp: List<ProtocolPort>): String =
    pp.joinToString(", ") { "${it.proto}/${formatPort(it)}" }.ifEmpty { "任意" }

/** A rule's ports as Core's catalogue writes them (`tcp/25565`; a full range as `*`). */
private fun portKeys(pp: List<ProtocolPort>): Set<String> = pp.map { "${it.proto}/${formatPort(it)}" }.toSet()

/** What a rule's ports amount to in terms of Core's service catalogue. */
sealed interface ServiceMatch {
    /** Every TCP and UDP port. */
    data object AllPorts : ServiceMatch
    /** Exactly these services (IDs), no other port. */
    data class Services(val ids: List<String>) : ServiceMatch
}

/** [ports] as services from [catalog] when they are exactly the ports of
 *  some services (rules store expanded ports, not service names), or
 *  "all ports"; `null` when they aren't. */
fun matchServices(ports: List<ProtocolPort>, catalog: List<ServiceItem>): ServiceMatch? {
    val want = portKeys(ports)
    if (want.isEmpty()) return null
    if (want == setOf("tcp/*", "udp/*")) return ServiceMatch.AllPorts
    val fits = catalog.filter { it.ports.isNotEmpty() && want.containsAll(it.ports) }
    return if (fits.flatMap { it.ports }.toSet() == want) ServiceMatch.Services(fits.map { it.id }.distinct()) else null
}

/** A rule's ports for display: services from [catalog] that the rule fully
 *  covers by name, then the remaining ports. */
fun formatTargets(ports: List<ProtocolPort>, catalog: List<ServiceItem>): String {
    val want = portKeys(ports)
    if (want == setOf("tcp/*", "udp/*")) return "所有协议及端口"
    val fits = catalog.filter { it.ports.isNotEmpty() && want.containsAll(it.ports) }
    val covered = fits.flatMap { it.ports }.toSet()
    val rest = ports.filter { "${it.proto}/${formatPort(it)}" !in covered }
    return (fits.map { it.name }.distinct() + listOfNotNull(rest.takeIf { it.isNotEmpty() }?.let(::formatPorts)))
        .joinToString("、").ifEmpty { formatPorts(ports) }
}

/** The principal ID Core matches against every device or user (`*`). */
const val ANY_PRINCIPAL = "*"

/** Sources of a rule; IDs are shown with the mnemonic the rule was created with. */
fun formatPrincipal(pg: PermissionGroup, mnemonics: Map<String, String>): String {
    if (pg.kind == "space") return "房间模式：当前所有房间成员"
    if (pg.ids.isEmpty()) return (if (pg.kind == "devices") "设备" else "用户") + "：无（不匹配任何来源）"
    val devices = pg.kind == "devices"
    val prefix = if (devices) "设备" else "用户"
    return "$prefix: " + pg.ids.joinToString(", ") { id ->
        if (id == ANY_PRINCIPAL) (if (devices) "所有设备 (*)" else "所有用户 (*)")
        else mnemonics[id]?.let { "$it ($id)" } ?: id
    }
}

/** Sources of [rule], naming the permission group it follows, if any. */
fun formatRuleSource(rule: FirewallRule, groups: List<AclGroup>): String {
    val members = formatPrincipal(rule.permissionGroup, rule.principalMnemonics)
    val gid = rule.groupId ?: return members
    val name = groups.firstOrNull { it.id == gid }?.name ?: gid
    return "权限组「$name」 · $members"
}

/** How a resolved principal is shown: its name, else its mnemonic or ID. */
fun principalLabel(p: ResolvedPrincipal): String {
    if (p.id == ANY_PRINCIPAL) return if (p.kind == "device") "所有设备" else "所有用户"
    val name = p.displayName ?: p.mnemonic ?: return p.id
    return if (p.kind == "device" && p.userDisplayName != null) "$name（${p.userDisplayName}）" else name
}

/** "Who → can or cannot → which services" (AGENTS.md §9.1). */
fun ruleSummary(r: FirewallRule): String {
    val pg = r.permissionGroup
    val who = when {
        pg.kind == "space" -> "当前房间成员"
        ANY_PRINCIPAL in pg.ids -> if (pg.kind == "devices") "所有设备" else "所有用户"
        pg.kind == "devices" -> "${pg.ids.size} 台设备"
        else -> "${pg.ids.size} 位用户"
    }
    val verb = if (r.action == "allow") "可以访问" else "不能访问"
    return "$who$verb ${r.protocolPorts.size} 组端口"
}

/** A port expression accepted by Core: `*`, `80`, `80-443`. */
fun validPortExpr(s: String): Boolean {
    if (s == "*") return true
    val m = Regex("""^(\d{1,5})(?:-(\d{1,5}))?$""").matchEntire(s) ?: return false
    val a = m.groupValues[1].toInt()
    val b = m.groupValues[2].ifEmpty { m.groupValues[1] }.toInt()
    return a >= 1 && b <= 65535 && a <= b
}

/** A port expression that matches every port (`*`, `1-65535`). */
fun coversAllPorts(expr: String): Boolean = expr.trim().let { it == "*" || it == "1-65535" }

/** Mnemonic handle rule (Core `validate_handle`: lowercase letters, digits, hyphens). */
fun validHandle(s: String): Boolean = Regex("^[a-z0-9-]+$").matches(s)

/** Core's display-name limit (`normalize_display_name`), in characters. */
const val MAX_DISPLAY_NAME_CHARS = 64

/** Why Core would reject [name] as a display name (trimmed: at most 64
 *  characters, no control characters), or null if it is acceptable.  An
 *  empty name is acceptable: it clears the name. */
fun displayNameProblem(name: String): String? {
    val n = name.trim()
    return when {
        n.count { !it.isLowSurrogate() } > MAX_DISPLAY_NAME_CHARS -> "名称最多 $MAX_DISPLAY_NAME_CHARS 个字符"
        n.any { it.isISOControl() } -> "名称不能包含控制字符"
        else -> null
    }
}

/** A WireGuard session state from `GetPathView` / `EncryptionChanged`. */
fun encryptionLabel(state: String?): String? = when (state) {
    null -> null
    "no_key" -> "无公钥（无法通信）"
    // No session yet, or an idle one whose keys were erased: Core starts
    // the handshake with the next packet in either direction.
    "unknown" -> "空闲（有通信时自动建立）"
    "handshaking" -> "握手中"
    "encrypted" -> "已加密"
    "failed" -> "握手失败"
    else -> state
}

/** MSST-Net's internal DNS zone (3.1.7; `msst.net` before). */
const val MSST_ZONE = "net.msst2031.cn"

/**
 * Namespace labels the coordinator refuses (its `zone.go` is authoritative;
 * this copy only gives early feedback): the official (`srv`) and ezPlay
 * sub-zones and names that would pass for official ones.
 */
val RESERVED_NAMESPACES = setOf(
    "srv", "ezplay", "serv", "msst", "msst-net", "official", "admin", "coord", "relay", "www", "api", "ns", "dns",
)

/** A namespace label under [MSST_ZONE] (one label: a-z, 0-9, `-`), or null. */
fun normalizeNamespace(input: String): String? {
    val ns = input.trim().lowercase().removeSuffix(".$MSST_ZONE")
    return ns.takeIf { it.length <= 63 && Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$").matches(it) }
}

/** Device and user mnemonics shown for "大内网模式". */
fun deviceMnemonic(v: ControllerView?): String {
    val h = v?.state?.health ?: return ""
    return if (h.deviceHandle != null && h.userHandle != null) "${h.deviceHandle}.${h.userHandle}"
    else h.deviceHandle ?: h.deviceId ?: ""
}

fun userMnemonic(v: ControllerView?): String = v?.state?.health?.let { it.userHandle ?: it.userId } ?: ""

/** Display name for a device ID from Core's node list. */
fun deviceName(state: SystemState?, id: String): String =
    state?.nodes?.get(id)?.let { it.displayName ?: it.hostname } ?: id

/**
 * Apps shown in the per-app exclusion picker: matching [query] (label or
 * package), system apps only when [showSystem] — but an app that is already
 * selected is always shown so it can be unselected.  Selected first, then
 * by label.
 */
fun filterApps(apps: List<DeviceApp>, query: String, showSystem: Boolean, selected: Set<String>): List<DeviceApp> {
    val q = query.trim()
    return apps
        .filter { it.packageName in selected || showSystem || !it.system }
        .filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
        .sortedWith(compareBy<DeviceApp>({ it.packageName !in selected }, { it.label.lowercase() }))
}

/** One line for a host routing problem Core reported. */
fun networkWarningLabel(w: NetworkWarning): String = when (w.kind) {
    "route_conflict" -> "${w.`interface`} 的路由 ${w.route ?: w.range} 与 MSST-Net 网段 ${w.range} 重叠，" +
        "MSST-Net 未接管该网段，可能无法与部分设备通信"
    "route_hijacked" -> "发往 MSST-Net 网段 ${w.range} 的流量被路由到了 ${w.`interface`}，可能无法与其他设备通信"
    else -> "网络配置问题（${w.kind}）：${w.range} / ${w.`interface`}"
}

/** The game discovery line of a room member's card; null when there is
 *  nothing to say (supported, this device, or an older Core). */
fun discoveryNote(peer: com.msst.net.model.RoomPeer): String? = when (peer.discovery) {
    "unsupported" -> "该成员版本过旧，无法互相发现游戏"
    else -> null
}

/** The game discovery counters (`GetRelays.discovery`) in one line. */
fun discoveryStatsLine(d: com.msst.net.model.DiscoveryStats): String =
    "已发送 ${d.txMessages}  已接收 ${d.rxAccepted}" +
        (if (d.txSkippedUnsupported > 0) "  未发送（成员版本过旧）${d.txSkippedUnsupported}" else "") +
        d.drop.filterValues { it > 0 }.entries.joinToString("") { "  丢弃（${discoveryDropLabel(it.key)}）${it.value}" }

private fun discoveryDropLabel(reason: String) = when (reason) {
    "not_member" -> "发送方不是房间成员"
    "not_discovery" -> "不是游戏发现广播"
    "rate_limited" -> "超出速率限制"
    else -> reason
}

/** The room relay line of the room view; null when the room has none. */
fun roomRelayLabel(r: RoomRelay?): String? {
    r ?: return null
    val kind = if (r.selfHosted) "此房间使用自托管中转服务器" else "此房间使用官方中转服务器"
    val state = when (r.state) {
        "ok" -> "正常"
        "probing" -> "检测中"
        "down" -> "不可用，已回退到各自的中转服务器"
        else -> "状态未知"
    }
    return "$kind ${r.address}（$state）"
}
