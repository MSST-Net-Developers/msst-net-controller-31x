package com.msst.net.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.msst.net.controller.Connection
import com.msst.net.controller.ControllerView
import com.msst.net.controller.CoreStatus
import com.msst.net.controller.Health
import com.msst.net.controller.NodeInfo
import com.msst.net.controller.Peer
import com.msst.net.platform.MsstVpnService
import com.msst.net.viewmodel.AuthFlowState
import com.msst.net.viewmodel.AuthFlowStatus
import com.msst.net.viewmodel.Done
import com.msst.net.viewmodel.MainViewModel
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(modifier: Modifier = Modifier, viewModel: MainViewModel) {
    val view by viewModel.view.collectAsStateWithLifecycle()
    val vpn by viewModel.vpnState.collectAsStateWithLifecycle()
    val authFlow by viewModel.authFlow.collectAsStateWithLifecycle()
    val v = view
    if (v != null && v.connected && v.room.inRoom) {
        RoomPage(modifier, v, onLeave = viewModel::leaveRoom)
    } else {
        IdlePage(modifier, v, vpn, authFlow, viewModel)
    }
}

// ── Not in a room ────────────────────────────────────────────────────────────

@Composable
private fun IdlePage(
    modifier: Modifier,
    view: ControllerView?,
    vpn: MsstVpnService.State,
    authFlow: AuthFlowState,
    vm: MainViewModel,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showCreate by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var showAuth by remember { mutableStateOf(false) }
    var notReady by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) { if (copied != null) { delay(2000); copied = null } }
    LaunchedEffect(notReady) { if (notReady) { delay(2500); notReady = false } }

    val health = if (view?.connected == true) view.health else null
    val status = view?.status ?: CoreStatus.CHECKING
    val ready = view?.coreReady == true
    val authenticated = health?.isAuthenticated == true

    Box(modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Status ──
            Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(status)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(statusTitle(view, vpn), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        Text(
                            statusDetail(view, vpn, authenticated),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = vm::refreshView) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新状态", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── VPN service control ──
            when (vpn) {
                MsstVpnService.State.Running, MsstVpnService.State.Sleeping -> {
                    val sleeping = vpn == MsstVpnService.State.Sleeping
                    OutlinedButton(
                        onClick = { if (sleeping) vm.wakeVpn(context) else vm.sleepVpn(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(if (sleeping) Icons.Default.PlayArrow else Icons.Default.Pause, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (sleeping) "恢复 MSST-Net 客户端" else "休眠 MSST-Net 客户端", fontWeight = FontWeight.Medium)
                    }
                }
                MsstVpnService.State.Stopped, is MsstVpnService.State.Failed -> {
                    Button(onClick = { vm.startVpn(context) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("启动 MSST-Net 客户端", fontWeight = FontWeight.Medium)
                    }
                }
                MsstVpnService.State.Starting -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            // ── Anonymous-access notice ──
            if (health != null && !authenticated && ready) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                            Text("免认证模式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Text(
                            "您正以免认证身份接入 MSST-Net，仅可使用房间模式。如需使用大内网模式或享有更多权限，请完成 MSST 联合认证。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        OutlinedButton(onClick = { showAuth = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.AccountCircle, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("前往完成接入认证", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // ── Room mode ──
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.MeetingRoom, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text("使用房间模式（推荐）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RoomActionCard("创建房间", Icons.Default.AddCircle, ready, primary = true, Modifier.weight(1f)) {
                            if (ready) showCreate = true else notReady = true
                        }
                        RoomActionCard("加入房间", Icons.Default.Login, ready, primary = false, Modifier.weight(1f)) {
                            if (ready) showJoin = true else notReady = true
                        }
                    }
                }
            }

            // ── LAN mode (authenticated users) ──
            if (authenticated && health != null) {
                val deviceMnemonic = when {
                    health.deviceHandle != null && health.userHandle != null -> "${health.deviceHandle}.${health.userHandle}"
                    else -> health.deviceHandle ?: health.deviceId ?: ""
                }
                val userMnemonic = health.userHandle ?: health.userId ?: ""
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.Lan, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                            Text("使用大内网模式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.secondary)
                        }
                        Text("作为客户端", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CopyRow("设备", deviceMnemonic, copied == "dev") { clipboard.setText(AnnotatedString(deviceMnemonic)); copied = "dev" }
                        CopyRow("用户", userMnemonic, copied == "usr") { clipboard.setText(AnnotatedString(userMnemonic)); copied = "usr" }
                        Text("作为服务器", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val host = health.deviceHostname
                        if (host != null) {
                            CopyRow(null, host, copied == "host") { clipboard.setText(AnnotatedString(host)); copied = "host" }
                        } else {
                            Text("ezPlay 主机名将在首次成功连接协调服务器后自动分配", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            TextButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://msst-net.msst2031.cn"))) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Info, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("项目官网 · 使用说明", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(72.dp)) // room for the FAB
        }

        if (notReady) {
            Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp)) { Text("客户端核心未就绪，无法创建或加入房间") }
        }

        FloatingActionButton(
            onClick = { showAuth = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = if (authenticated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        ) {
            Icon(
                if (authenticated) Icons.Default.CheckCircle else Icons.Default.AccountCircle,
                contentDescription = "MSST联合认证",
                tint = if (authenticated) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError,
            )
        }
    }

    if (showAuth) {
        OidcAuthDialog(
            flow = authFlow,
            health = health,
            canStart = ready,
            onStart = vm::startOidcAuth,
            onClose = { vm.cancelOidcAuth(); showAuth = false },
        )
        LaunchedEffect(authFlow.status) {
            if (authFlow.status == AuthFlowStatus.AUTHORIZED) {
                delay(1200)
                vm.cancelOidcAuth()
                showAuth = false
            }
        }
    }
    if (showCreate) {
        RoomInputDialog("创建房间", "请输入房间名", "创建", onDismiss = { showCreate = false }) { name, pw, done ->
            vm.createRoom(name, pw, done)
        }
    }
    if (showJoin) {
        RoomInputDialog("加入房间", "请输入房间号", "加入", onDismiss = { showJoin = false }) { id, pw, done ->
            vm.joinRoom(id, pw, done)
        }
    }
}

private fun statusTitle(view: ControllerView?, vpn: MsstVpnService.State): String = when {
    vpn is MsstVpnService.State.Failed -> "客户端核心启动失败"
    vpn == MsstVpnService.State.Stopped && view?.connected != true -> "客户端核心未启动"
    view == null -> "正在检查客户端核心…"
    view.connection == Connection.Incompatible -> "客户端核心版本不兼容"
    !view.connected -> "正在连接客户端核心…"
    view.status == CoreStatus.OK -> "客户端核心运行正常"
    view.status == CoreStatus.WARNING -> "客户端核心状态异常"
    else -> "正在读取客户端核心状态…"
}

private fun statusDetail(view: ControllerView?, vpn: MsstVpnService.State, authenticated: Boolean): String = when {
    vpn is MsstVpnService.State.Failed -> vpn.message
    vpn == MsstVpnService.State.Stopped && view?.connected != true -> "点击下方按钮启动"
    vpn == MsstVpnService.State.Sleeping -> "已休眠：虚拟网络已暂停，核心保持在线"
    view?.status == CoreStatus.OK -> {
        val coord = if (view.health?.wsConnected == false) "，协调服务器未连接" else ""
        (if (authenticated) "已就绪，可以创建或加入房间" else "可使用房间模式（免认证）") + coord
    }
    view?.status == CoreStatus.WARNING -> view.syncMessage ?: "部分功能可能受限"
    else -> view?.lastError ?: "请稍候"
}

@Composable
private fun RoomActionCard(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    primary: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        primary -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.primary
    }
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.height(100.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = when {
                !enabled -> MaterialTheme.colorScheme.surfaceVariant
                primary -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = fg)
        }
    }
}

@Composable
private fun CopyRow(label: String?, value: String, copied: Boolean, onCopy: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (label != null) {
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.width(36.dp))
            }
            Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (copied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                    contentDescription = "复制",
                    tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// ── OIDC access authentication ───────────────────────────────────────────────

@Composable
private fun OidcAuthDialog(
    flow: AuthFlowState,
    health: Health?,
    canStart: Boolean,
    onStart: () -> Unit,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (flow.status != AuthFlowStatus.PENDING && flow.status != AuthFlowStatus.VERIFYING) onClose() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.VpnKey, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Text("接入认证", fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (flow.status) {
                    AuthFlowStatus.IDLE -> {
                        if (health?.isAuthenticated == true) {
                            Text(health.userDisplayName ?: "已认证用户", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(health.userId ?: "", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                            Text("如需切换账号，可点击「重新认证」覆盖当前凭据。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        } else {
                            Text("通过 MSST 联合认证系统获取接入身份，实现账号统一管理。", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (!canStart) Text("客户端核心未就绪，暂时无法认证。", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    AuthFlowStatus.STARTING -> {
                        CircularProgressIndicator(Modifier.size(36.dp))
                        Text("正在获取认证链接…", style = MaterialTheme.typography.bodySmall)
                    }
                    AuthFlowStatus.PENDING, AuthFlowStatus.VERIFYING -> {
                        if (flow.userCode.isNotEmpty()) {
                            Text("验证码", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(flow.userCode, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold)
                                IconButton(onClick = { clipboard.setText(AnnotatedString(flow.userCode)) }) {
                                    Icon(Icons.Default.ContentCopy, "复制验证码", Modifier.size(16.dp))
                                }
                            }
                        }
                        if (flow.verificationUri.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(flow.verificationUri))) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("在浏览器中打开验证页面") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                if (flow.status == AuthFlowStatus.VERIFYING) "正在等待客户端核心确认…" else "等待认证完成…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    AuthFlowStatus.AUTHORIZED -> {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("认证成功！", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    AuthFlowStatus.ERROR -> Text("认证失败：${flow.error}", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
            }
        },
        confirmButton = {
            when (flow.status) {
                AuthFlowStatus.IDLE -> TextButton(onClick = onStart, enabled = canStart) {
                    Text(if (health?.isAuthenticated == true) "重新认证" else "开始认证")
                }
                AuthFlowStatus.ERROR -> TextButton(onClick = onStart, enabled = canStart) { Text("重试") }
                else -> {}
            }
        },
        dismissButton = {
            if (flow.status != AuthFlowStatus.AUTHORIZED) TextButton(onClick = onClose) { Text("取消") }
        },
    )
}

// ── In a room ────────────────────────────────────────────────────────────────

@Composable
private fun RoomPage(modifier: Modifier, view: ControllerView, onLeave: (Done) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val room = view.room
    val selfId = view.health?.deviceId
    var leaving by remember { mutableStateOf(false) }
    var leaveError by remember { mutableStateOf<String?>(null) }
    var copiedRoomId by remember { mutableStateOf(false) }
    LaunchedEffect(copiedRoomId) { if (copiedRoomId) { delay(2000); copiedRoomId = false } }

    Column(modifier) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MeetingRoom, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("当前房间", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    room.roomName?.let {
                        Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(room.roomId ?: "", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { clipboard.setText(AnnotatedString(room.roomId ?: "")); copiedRoomId = true }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.ContentCopy, "复制房间号", Modifier.size(14.dp),
                                tint = if (copiedRoomId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    room.relay?.let { Text("中转 $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        Text("成员列表（${room.peers.size}）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (room.peers.isEmpty()) {
                item { Text("暂无成员信息", Modifier.fillMaxWidth().padding(top = 40.dp), textAlign = TextAlign.Center) }
            } else {
                items(room.peers, key = { it.deviceId }) { peer ->
                    PeerCard(peer, isSelf = peer.deviceId == selfId, node = view.nodes[peer.deviceId])
                }
            }
        }
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                leaveError?.let { Text("退出失败：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = {
                        leaving = true
                        leaveError = null
                        onLeave { err -> leaving = false; leaveError = err }
                    },
                    enabled = !leaving,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(0.7f).height(48.dp),
                ) { Text(if (leaving) "退出中…" else "退出房间", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

private fun pathLabel(mode: String?): String = when {
    mode == null -> "未知"
    mode == "relay" -> "中转"
    mode == "probing" -> "探测直连中"
    mode.startsWith("direct") -> "直连"
    else -> mode
}

@Composable
private fun PeerCard(peer: Peer, isSelf: Boolean, node: NodeInfo?) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Lan, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text((peer.displayName ?: peer.deviceId) + if (isSelf) "（本机）" else "", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(peer.hostname ?: peer.deviceId, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = if (peer.hostname != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!isSelf) {
                    Text("路径：${pathLabel(node?.pathMode)}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            peer.hostname?.let { host ->
                IconButton(onClick = { clipboard.setText(AnnotatedString(host)); copied = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.ContentCopy, "复制主机名", Modifier.size(18.dp),
                        tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StatusDot(status: CoreStatus) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(1f, 0.4f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "dotAlpha")
    val color by animateColorAsState(
        when (status) {
            CoreStatus.OK -> MaterialTheme.colorScheme.primary
            CoreStatus.WARNING -> androidx.compose.ui.graphics.Color(0xFFFFA726)
            CoreStatus.ERROR -> MaterialTheme.colorScheme.error
            CoreStatus.CHECKING -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "dotColor",
    )
    Box(Modifier.size(12.dp).clip(CircleShape).background(if (status == CoreStatus.CHECKING) color.copy(alpha = alpha) else color))
}

/** Room create/join dialog: shows progress and Core's error, closes on success. */
@Composable
private fun RoomInputDialog(
    title: String,
    placeholder: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Done) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, placeholder = { Text(placeholder) }, singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it },
                    placeholder = { Text("房间密码（可选）") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                if (passwordVisible) "隐藏密码" else "显示密码")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    busy = true
                    error = null
                    onConfirm(text.trim(), password) { err ->
                        busy = false
                        if (err == null) onDismiss() else error = err
                    }
                },
                enabled = text.isNotBlank() && !busy,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(confirmLabel, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = { if (!busy) onDismiss() }) { Text("取消") } },
    )
}
