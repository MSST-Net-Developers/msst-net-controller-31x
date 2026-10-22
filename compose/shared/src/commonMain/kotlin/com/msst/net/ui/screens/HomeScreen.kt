package com.msst.net.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.msst.net.model.ControllerView
import com.msst.net.model.NetworkWarning
import com.msst.net.model.Req
import com.msst.net.model.RoomPeer
import com.msst.net.model.RoomRelay
import com.msst.net.platform.VpnControl
import com.msst.net.state.CoreStatus
import com.msst.net.state.attempt
import com.msst.net.state.coreStatus
import com.msst.net.state.coreStatusLabel
import com.msst.net.state.coordinatorActionsAvailable
import com.msst.net.state.coordinatorUnreachable
import com.msst.net.state.deviceMnemonic
import com.msst.net.state.discoveryNote
import com.msst.net.state.errorMessage
import com.msst.net.state.networkWarningLabel
import com.msst.net.state.pathLabel
import com.msst.net.state.roomRelayLabel
import com.msst.net.state.userMnemonic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import com.msst.net.state.PresetDevice
import com.msst.net.state.RoomMembers
import com.msst.net.state.RuleSourcePreset
import com.msst.net.ui.LocalCompact
import com.msst.net.ui.components.contextMenuGesture
import com.msst.net.ui.dialogs.RuleWizardDialog
import com.msst.net.ui.LocalController
import com.msst.net.ui.MdiIcons
import com.msst.net.ui.components.Card
import com.msst.net.ui.components.CopyButton
import com.msst.net.ui.components.Dot
import com.msst.net.ui.components.EmptyState
import com.msst.net.ui.components.InlineError
import com.msst.net.ui.components.LinkButton
import com.msst.net.ui.components.MIcon
import com.msst.net.ui.components.MonoText
import com.msst.net.ui.components.Notice
import com.msst.net.ui.components.PrimaryButton
import com.msst.net.ui.components.SecondaryButton
import com.msst.net.ui.components.SmallIconButton
import com.msst.net.ui.components.SpinningIcon
import com.msst.net.ui.dialogs.CreateRoomDialog
import com.msst.net.ui.dialogs.JoinRoomDialog
import com.msst.net.ui.dialogs.OidcDialog
import com.msst.net.ui.theme.Msst
import com.msst.net.ui.theme.Radius
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val WEBSITE = "https://msst-net.msst2031.cn"

@Composable
fun HomeScreen() {
    val ctx = LocalController.current
    val view by ctx.store.view.collectAsState()
    val room = view?.state?.room
    Column(Modifier.fillMaxSize()) {
        room?.networkWarnings?.takeIf { it.isNotEmpty() }?.let { NetworkWarningsBar(it) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (room != null && room.inRoom) RoomView(view!!) else ModePage(view)
        }
    }
}

/** Host routing problems Core reported (another VPN or network on the
 *  MSST-Net ranges), in room and LAN mode alike. */
@Composable
private fun NetworkWarningsBar(warnings: List<NetworkWarning>) {
    val c = Msst.colors
    Column(
        Modifier.fillMaxWidth().background(c.warningLight).padding(horizontal = if (LocalCompact.current) 16.dp else 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        warnings.forEach { w ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(c.warningDot)
                Text(networkWarningLabel(w), style = MaterialTheme.typography.bodySmall, color = c.warning)
            }
        }
    }
}

// ── In a room ────────────────────────────────────────────────────────────────

@Composable
private fun RoomView(view: ControllerView) {
    val ctx = LocalController.current
    val room = view.state.room
    val selfId = view.state.health?.deviceId
    var leaving by remember { mutableStateOf(false) }
    var leaveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        room.roomRelay?.let { RoomRelayBar(it) }
        if (room.peers.isEmpty()) {
            EmptyState("暂无成员信息", Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = if (LocalCompact.current) 12.dp else 20.dp,
                    vertical = if (LocalCompact.current) 12.dp else 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(room.peers, key = { it.deviceId }) { peer ->
                    val self = peer.deviceId == selfId
                    PeerCard(peer, self, if (self) null else pathLabel(view.state.nodes[peer.deviceId]?.pathMode), view.connected)
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().background(Msst.colors.bgPrimary).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            leaveError?.let { InlineError("退出失败：$it") }
            SecondaryButton(
                if (leaving) "退出中…" else "退出房间",
                onClick = {
                    val id = room.roomId ?: return@SecondaryButton
                    leaving = true
                    leaveError = null
                    scope.launch {
                        // The room view disappears once Core's room state says so.
                        leaveError = attempt { ctx.backend.request(Req.leaveRoom(id)) }.errorMessage
                        leaving = false
                    }
                },
                enabled = !leaving && view.connected,
                danger = true,
            )
        }
    }
}

/** The room's own relay and its state (Core's `room_relay`). */
@Composable
private fun RoomRelayBar(relay: RoomRelay) {
    val c = Msst.colors
    val (dot, bg) = when (relay.state) {
        "ok" -> c.successDot to c.bgPrimary
        "down" -> c.warningDot to c.warningLight
        else -> c.borderMedium to c.bgPrimary
    }
    Row(
        Modifier.fillMaxWidth().background(bg).padding(horizontal = if (LocalCompact.current) 16.dp else 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(dot)
        Text(
            roomRelayLabel(relay).orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = if (relay.state == "down") c.warning else c.textSecondary,
        )
    }
}

/**
 * A room member.  Every card, ours included, has a context menu (secondary
 * click on desktop / WebUI, long press on touch) with "添加房间级规则",
 * which opens the rule wizard with all room members as the fixed source.
 * Other members' cards also offer "添加防火墙规则" with this device as the
 * source.
 */
@Composable
private fun PeerCard(peer: RoomPeer, self: Boolean, path: String?, connected: Boolean) {
    val c = Msst.colors
    val services = LocalController.current.services
    val name = peer.displayName ?: peer.deviceId
    var menuAt by remember { mutableStateOf<DpOffset?>(null) }
    var addRule by remember { mutableStateOf<RuleSourcePreset?>(null) }
    val forDevice = PresetDevice(peer.deviceId, name)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var height by remember { mutableStateOf(0) }
    val openMenu = { at: Offset ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        // DropdownMenu is placed below its anchor: offset it to the press.
        menuAt = with(density) { DpOffset(at.x.toDp(), (at.y - height).toDp()) }
    }
    Box(
        Modifier
            .onSizeChanged { height = it.height }
            .contextMenuGesture(enabled = true) { openMenu(it) }
            .semantics {
                customActions = listOfNotNull(
                    if (self) null else CustomAccessibilityAction("添加防火墙规则") { addRule = forDevice; true },
                    CustomAccessibilityAction("添加房间级规则") { addRule = RoomMembers; true },
                )
            },
    ) {
        PeerCardContent(peer, self, path, services)
        DropdownMenu(menuAt != null, { menuAt = null }, offset = menuAt ?: DpOffset.Zero, containerColor = c.bgPrimary) {
            if (!self) {
                DropdownMenuItem(
                    text = { Text("添加防火墙规则", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = { MIcon(MdiIcons.ShieldOutline, 18.dp, c.primary) },
                    enabled = connected,
                    onClick = {
                        menuAt = null
                        addRule = forDevice
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("添加房间级规则", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { MIcon(MdiIcons.AccountGroupOutline, 18.dp, c.primary) },
                enabled = connected,
                onClick = {
                    menuAt = null
                    addRule = RoomMembers
                },
            )
        }
    }
    addRule?.let { RuleWizardDialog(null, it) { addRule = null } }
}

@Composable
private fun PeerCardContent(peer: RoomPeer, self: Boolean, path: String?, services: com.msst.net.platform.PlatformServices) {
    val c = Msst.colors
    Card(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(Radius.full).background(c.primaryLighter), contentAlignment = Alignment.Center) {
                MIcon(MdiIcons.Monitor, 18.dp, c.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    (peer.displayName ?: peer.deviceId) + if (self) "（本机）" else "",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (peer.displayName != null) MonoText(peer.deviceId, color = c.textMuted, small = true, selectable = self)
                // The hostname is how members reach each other; the virtual IP
                // is not shown.
                peer.hostname?.let { MonoText(it, color = c.textSecondary, small = true, selectable = self) }
                if (!self) {
                    Text(
                        "路径：${path ?: "未知"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (path == null) c.textMuted else c.textSecondary,
                    )
                    discoveryNote(peer)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = c.warning) }
                }
            }
            peer.hostname?.let { CopyButton(it, services, "复制主机名") }
        }
    }
}

// ── Not in a room ────────────────────────────────────────────────────────────

@Composable
private fun ModePage(view: ControllerView?) {
    val ctx = LocalController.current
    val backendError by ctx.store.backendError.collectAsState()
    // Rooms and access authentication go through the coordinator: only
    // offered while Core reports its coordinator connection is up.
    val ready = coordinatorActionsAvailable(view)
    val noCoordinator = coordinatorUnreachable(view)
    val health = if (view?.connected == true) view.state.health else null
    var dialog by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(2_500)
            hint = null
        }
    }
    fun requireCoordinator(open: String, what: String) {
        hint = when {
            ready -> null.also { dialog = open }
            noCoordinator -> "无法连接协调服务器，暂时无法$what"
            else -> "客户端核心未就绪，暂时无法$what"
        }
    }

    val compact = LocalCompact.current
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(if (compact) 12.dp else 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ctx.platform.vpn?.let { VpnCard(it) }

                Card(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                    ModeHeader(MdiIcons.AccountMultipleOutline, "使用房间模式（推荐）")
                    if (health != null && !health.isAuthenticated && ready) Notice("当前以免认证身份接入，仅可使用房间模式。")
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        BigAction("创建房间", MdiIcons.Plus, primary = true, available = ready, Modifier.weight(1f)) { requireCoordinator("create", "创建或加入房间") }
                        BigAction("加入房间", MdiIcons.Login, primary = false, available = ready, Modifier.weight(1f)) { requireCoordinator("join", "创建或加入房间") }
                    }
                }

                if (health?.isAuthenticated == true) {
                    Card(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                        ModeHeader(MdiIcons.Lan, "使用大内网模式")
                        Label("作为客户端")
                        val dev = deviceMnemonic(view)
                        if (dev.isEmpty()) Pending("核心就绪后自动加载")
                        else {
                            IdRow("设备", dev)
                            IdRow("用户", userMnemonic(view))
                        }
                        Label("作为服务器")
                        val host = health.deviceHostname
                        if (host != null) IdRow(null, host)
                        else Pending("ezPlay 主机名（.ezplay.msst.net）将在首次成功连接协调服务器后自动分配")
                    }
                }

                hint?.let { InlineError(it) }
                // Phones: an in-page button instead of the floating one, which
                // would cover the cards on small screens.
                if (compact) {
                    SecondaryButton(
                        "接入认证",
                        { requireCoordinator("auth", "查看认证状态或发起接入认证") },
                        Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                        enabled = ready,
                        icon = MdiIcons.AccountKeyOutline,
                    )
                }
                LinkButton("项目官网 · 使用说明", { ctx.services.openUrl(WEBSITE) }, MdiIcons.BookOpenOutline, color = Msst.colors.textTertiary)
            }
            StatusBar(view, backendError)
        }
        if (!compact) FloatingActionButton(
            onClick = { requireCoordinator("auth", "查看认证状态或发起接入认证") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 64.dp).size(48.dp),
            containerColor = if (ready) Msst.colors.primary else Msst.colors.borderPrimary,
            elevation = if (ready) FloatingActionButtonDefaults.elevation() else FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
            contentColor = Msst.colors.textInverse,
            shape = Radius.full,
        ) { MIcon(MdiIcons.AccountKeyOutline, 22.dp, contentDescription = "接入认证") }
    }

    when (dialog) {
        "create" -> CreateRoomDialog { dialog = null }
        "join" -> JoinRoomDialog { dialog = null }
        "auth" -> OidcDialog { dialog = null }
    }
}

@Composable
private fun ModeHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MIcon(icon, 18.dp, Msst.colors.primary)
        Text(title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = Msst.colors.textPrimary)
    }
}

@Composable
private fun Label(text: String) =
    Text(text, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = Msst.colors.textTertiary)

@Composable
private fun Pending(text: String) = Text(text, style = MaterialTheme.typography.labelSmall, color = Msst.colors.textMuted)

@Composable
private fun IdRow(key: String?, value: String) {
    val c = Msst.colors
    Row(
        Modifier.fillMaxWidth().clip(Radius.md).background(c.bgSecondary).border(1.dp, c.borderLight, Radius.md)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (key != null) Text(key, Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall, color = c.textMuted)
        MonoText(value, Modifier.weight(1f))
        CopyButton(value, LocalController.current.services, if (key != null) "复制${key}标识" else "复制主机名", 13.dp)
    }
}

@Composable
private fun BigAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean,
    available: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val c = Msst.colors
    val bg = if (primary) c.primary else c.bgPrimary
    val fg = if (primary) c.textInverse else c.primary
    Column(
        modifier.clip(Radius.lg).background(bg.copy(alpha = if (available) 1f else 0.45f))
            .border(1.dp, (if (primary) c.primary else c.borderPrimary).copy(alpha = if (available) 1f else 0.45f), Radius.lg)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MIcon(icon, 22.dp, fg.copy(alpha = if (available) 1f else 0.6f))
        Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = fg.copy(alpha = if (available) 1f else 0.6f))
    }
}

@Composable
private fun StatusBar(view: ControllerView?, backendError: String?) {
    val ctx = LocalController.current
    val c = Msst.colors
    val retrying by ctx.store.retrying.collectAsState()
    val status = coreStatus(view, backendError)
    val (dot, text) = when (status) {
        CoreStatus.Ok -> c.successDot to c.success
        CoreStatus.Warning -> c.warningDot to c.warning
        CoreStatus.Error -> c.errorDot to c.error
        CoreStatus.Checking -> c.borderMedium to c.textMuted
    }
    Row(
        Modifier.fillMaxWidth().background(c.bgPrimary).padding(start = if (LocalCompact.current) 16.dp else 24.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!LocalCompact.current) Text("客户端核心状态：", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        Dot(dot)
        Text(
            coreStatusLabel(view, backendError),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (status == CoreStatus.Checking) FontWeight.Normal else FontWeight.Medium),
            color = text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Box {
            SmallIconButton(
                MdiIcons.Refresh,
                if (view?.connected == true) "重新读取客户端核心状态" else "立即重试连接客户端核心",
                onClick = ctx.store::retry,
                enabled = !retrying,
            )
            if (retrying) Box(Modifier.align(Alignment.Center)) { SpinningIcon(MdiIcons.Refresh, true, 16.dp, c.primary) }
        }
    }
}

// ── Android: the VPN service hosting Core ────────────────────────────────────

@Composable
private fun VpnCard(vpn: VpnControl) {
    val state by vpn.state.collectAsState()
    val c = Msst.colors
    Card(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val (dot, label) = when (val s = state) {
                VpnControl.State.Running -> c.successDot to "VPN 服务运行中"
                VpnControl.State.Sleeping -> c.warningDot to "VPN 服务已休眠（客户端核心仍在运行）"
                VpnControl.State.Starting -> c.borderMedium to "VPN 服务启动中…"
                VpnControl.State.Stopped -> c.borderMedium to "VPN 服务未启动"
                is VpnControl.State.Failed -> c.errorDot to "VPN 服务启动失败：${s.message}"
            }
            Dot(dot)
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = if (state is VpnControl.State.Failed) c.error else c.textSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                VpnControl.State.Stopped, is VpnControl.State.Failed ->
                    PrimaryButton("启动 MSST-Net 客户端", vpn::start, Modifier.fillMaxWidth(), icon = MdiIcons.Power)
                VpnControl.State.Starting -> PrimaryButton("启动中…", {}, Modifier.fillMaxWidth(), busy = true)
                VpnControl.State.Running -> {
                    SecondaryButton("休眠", vpn::sleep, Modifier.weight(1f), icon = MdiIcons.Sleep)
                    SecondaryButton("停止", vpn::stop, Modifier.weight(1f), icon = MdiIcons.Power, danger = true)
                }
                VpnControl.State.Sleeping -> {
                    PrimaryButton("恢复", vpn::wake, Modifier.weight(1f), icon = MdiIcons.Play)
                    SecondaryButton("停止", vpn::stop, Modifier.weight(1f), icon = MdiIcons.Power, danger = true)
                }
            }
        }
    }
}
