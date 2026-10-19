package com.msst.net.state

// Pure presentation helpers: how Core's state is described to users.  They
// only describe what Core reported; nothing here decides networking state.

import com.msst.net.model.ConnectionStatus
import com.msst.net.model.ControllerView
import com.msst.net.model.FirewallRule
import com.msst.net.model.PermissionGroup
import com.msst.net.model.ProtocolPort
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
        CoreStatus.Ok -> "客户端核心状态正常"
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
            Banner(BannerKind.Warn, "MSST 接入认证未完成或已过期，请在${authPlace}完成接入认证")
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

/** Sources of a rule; IDs are shown with the mnemonic the rule was created with. */
fun formatPrincipal(pg: PermissionGroup, mnemonics: Map<String, String>): String {
    if (pg.kind == "space") return "房间模式：当前所有房间成员"
    val prefix = if (pg.kind == "devices") "设备" else "用户"
    return "$prefix: " + pg.ids.joinToString(", ") { id -> mnemonics[id]?.let { "$it ($id)" } ?: id }
}

/** "Who → can or cannot → which services" (AGENTS.md §9.1). */
fun ruleSummary(r: FirewallRule): String {
    val who = when (r.permissionGroup.kind) {
        "space" -> "当前房间成员"
        "devices" -> "${r.permissionGroup.ids.size} 台设备"
        else -> "${r.permissionGroup.ids.size} 位用户"
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

/** Mnemonic handle rule (Core `validate_handle`: lowercase letters, digits, hyphens). */
fun validHandle(s: String): Boolean = Regex("^[a-z0-9-]+$").matches(s)

/** A `.msst.net` namespace label (3.0.x rule). */
fun normalizeNamespace(input: String): String? {
    val ns = input.trim().lowercase().removeSuffix(".msst.net")
    return ns.takeIf { Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$").matches(it) }
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
