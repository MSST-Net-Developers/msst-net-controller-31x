package com.msst.net.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msst.net.model.AuthDecision
import com.msst.net.model.Req
import com.msst.net.platform.formatDateTime
import com.msst.net.platform.nowMs
import com.msst.net.state.OidcFlow
import com.msst.net.state.OidcFlow.Status
import com.msst.net.state.attempt
import com.msst.net.state.coordinatorActionsAvailable
import com.msst.net.state.coordinatorUnreachable
import com.msst.net.state.errorMessage
import com.msst.net.ui.LocalController
import com.msst.net.ui.MdiIcons
import com.msst.net.ui.components.CopyButton
import com.msst.net.ui.components.InlineError
import com.msst.net.ui.components.InlineNote
import com.msst.net.ui.components.MIcon
import com.msst.net.ui.components.Modal
import com.msst.net.ui.components.MonoText
import com.msst.net.ui.components.PrimaryButton
import com.msst.net.ui.components.SecondaryButton
import com.msst.net.ui.components.Spinner
import com.msst.net.ui.theme.MonoFamily
import com.msst.net.ui.theme.Msst
import com.msst.net.ui.theme.Radius
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── MSST access authentication (OIDC device flow) ────────────────────────────

@Composable
fun OidcDialog(onClose: () -> Unit) {
    val ctx = LocalController.current
    val scope = rememberCoroutineScope()
    val view by ctx.store.view.collectAsState()
    // Authentication goes through the coordinator; while it is unreachable
    // the stored status cannot be checked either, so none is shown.
    val ready = coordinatorActionsAvailable(view)
    val noCoordinator = coordinatorUnreachable(view)
    val health = if (ready) view?.state?.health else null
    val flow = remember { OidcFlow(ctx.backend, ctx.store, scope, ctx.services::openUrl) }
    DisposableEffect(flow) { onDispose { flow.cancel() } }
    LaunchedEffect(flow.status) {
        if (flow.status == Status.Authorized) {
            delay(1_500)
            onClose()
        }
    }
    val c = Msst.colors
    val close = { flow.cancel(); onClose() }

    Modal(
        close,
        "接入认证",
        busy = flow.status == Status.Starting,
        icon = {
            MIcon(
                when (flow.status) {
                    Status.Authorized -> MdiIcons.CheckCircleOutline
                    Status.Error -> MdiIcons.AlertCircleOutline
                    else -> MdiIcons.KeyOutline
                },
                36.dp,
                when (flow.status) {
                    Status.Authorized -> c.success
                    Status.Error -> c.error
                    else -> c.primary
                },
            )
        },
        actions = {
            when (flow.status) {
                Status.Idle -> {
                    SecondaryButton(if (health?.isAuthenticated == true) "关闭" else "取消", close)
                    PrimaryButton(if (health?.isAuthenticated == true) "重新认证" else "开始认证", flow::start, enabled = ready)
                }
                Status.Pending, Status.Verifying -> SecondaryButton("取消", close)
                Status.Error -> {
                    SecondaryButton("关闭", close)
                    PrimaryButton("重试", flow::start, enabled = ready)
                }
                else -> Unit
            }
        },
    ) {
        if (flow.status != Status.Error) Steps(flow.status)
        when (flow.status) {
            Status.Idle -> {
                if (health?.isAuthenticated == true) {
                    Row(
                        Modifier.fillMaxWidth().clip(Radius.md).background(c.bgSecondary).border(1.dp, c.borderLight, Radius.md).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(36.dp).clip(Radius.full).background(c.successLight), contentAlignment = Alignment.Center) {
                            MIcon(MdiIcons.AccountCheckOutline, 20.dp, c.success)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(health.userDisplayName ?: "已认证用户", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
                            health.userId?.let { MonoText(it, color = c.textMuted, small = true) }
                        }
                    }
                    health.authExpiresAt?.let { InlineNote("凭据有效期至 ${formatDateTime(it * 1000)}") }
                    Hint("如需切换账号，可重新认证（将覆盖当前凭据）。")
                } else {
                    Hint("通过 MSST 联合认证系统获取接入身份，实现账号统一管理。")
                    when {
                        noCoordinator -> InlineError("无法连接协调服务器，暂时无法查看认证状态或发起接入认证。")
                        !ready -> InlineError("客户端核心未就绪，暂时无法认证。")
                    }
                }
            }
            Status.Starting -> {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Spinner(28.dp) }
                Hint("正在获取认证链接…")
            }
            Status.Pending, Status.Verifying -> {
                Hint("认证页面已在浏览器中打开，若未自动打开请手动复制链接。")
                if (flow.verificationUri.isNotEmpty()) CodeBlock("认证链接", flow.verificationUri, big = false)
                if (flow.userCode.isNotEmpty()) CodeBlock("验证码", flow.userCode, big = true)
                Text(
                    if (flow.status == Status.Verifying) "正在等待客户端核心确认…" else "正在等待认证完成…",
                    Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
            Status.Authorized -> {
                Text("认证成功！", Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, color = c.success, textAlign = TextAlign.Center)
            }
            Status.Error -> Text("认证失败：${flow.error}", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = c.error, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Hint(text: String) = Text(
    text,
    Modifier.fillMaxWidth(),
    style = MaterialTheme.typography.bodySmall,
    color = Msst.colors.textSecondary,
    textAlign = TextAlign.Center,
)

@Composable
private fun Steps(status: Status) {
    val c = Msst.colors
    val reached = when (status) {
        Status.Idle -> 0
        Status.Starting -> 1
        Status.Pending, Status.Verifying -> 2
        Status.Authorized -> 3
        Status.Error -> 0
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Top) {
        listOf("开始", "认证中", "完成").forEachIndexed { i, label ->
            if (i > 0) Box(Modifier.padding(top = 5.dp).width(36.dp).height(2.dp).background(if (reached > i) c.primary else c.borderLight))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val on = reached > i
                Box(Modifier.size(12.dp).clip(Radius.full).background(if (on) c.primary else c.bgPrimary).border(2.dp, if (on) c.primary else c.borderMedium, Radius.full))
                Text(label, style = MaterialTheme.typography.labelSmall, color = if (on) c.primary else c.textMuted)
            }
        }
    }
}

@Composable
private fun CodeBlock(label: String, value: String, big: Boolean) {
    val c = Msst.colors
    Column(
        Modifier.fillMaxWidth().clip(Radius.md).background(c.bgSecondary).border(1.dp, c.borderLight, Radius.md).padding(12.dp),
        horizontalAlignment = if (big) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (big) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = MonoFamily, letterSpacing = 6.sp), color = c.primaryDark)
                }
            } else {
                MonoText(value, Modifier.weight(1f), color = c.textSecondary, small = true)
            }
            CopyButton(value, LocalController.current.services, "复制$label")
        }
    }
}

// ── Interactive authorization ────────────────────────────────────────────────

/**
 * Pending access requests and their deadlines come from Core; Core enforces
 * the decision and the timeout.  The dialog never decides on its own: an
 * unanswered request simply times out in Core.
 */
@Composable
fun AccessRequestDialog() {
    val ctx = LocalController.current
    val view by ctx.store.view.collectAsState()
    val queue = if (view?.connected == true) view?.state?.pendingAuth.orEmpty() else emptyList()
    val req = queue.firstOrNull() ?: return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Pair<String, String>?>(null) }
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            now = nowMs()
        }
    }
    val remaining = req.expiresAtMs?.let { maxOf(0L, (it - now + 999) / 1000) }
    val total = (req.expiresInSecs ?: 60L).coerceAtLeast(1)
    val c = Msst.colors

    fun decide(d: AuthDecision) {
        if (busy) return
        busy = true
        error = null
        val id = req.requestId
        scope.launch {
            // The request leaves the queue when Core's pending list no longer has it.
            attempt { ctx.backend.request(Req.authDecision(id, d)) }.errorMessage?.let { error = id to it }
            busy = false
        }
    }

    Modal(
        onDismiss = {},
        title = if (queue.size > 1) "访问请求（${queue.size}）" else "访问请求",
        busy = true,
        icon = { MIcon(MdiIcons.ShieldLockOutline, 36.dp, c.primary) },
    ) {
        val who = req.srcUserDisplayName ?: req.srcUserId
        Text(
            (if (who != null) "$who 的设备 " else "设备 ") + (req.srcDeviceDisplayName ?: req.srcDeviceId),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = c.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (req.srcDeviceDisplayName != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { MonoText(req.srcDeviceId, color = c.textMuted, small = true) }
        }
        Text(
            req.serviceName?.let { "想访问您的 $it 服务（${req.proto.uppercase()}/${req.dstPort}）" }
                ?: "想访问端口 ${req.proto.uppercase()}/${req.dstPort}",
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
        )
        Text("是否允许访问？", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (remaining != null) {
                val color = when {
                    remaining < 15 -> c.errorDot
                    remaining < 30 -> c.warningDot
                    else -> c.primary
                }
                Box(Modifier.fillMaxWidth().height(4.dp).clip(Radius.full).background(c.borderLight)) {
                    Box(Modifier.fillMaxWidth((remaining.toFloat() / total).coerceIn(0f, 1f)).height(4.dp).clip(Radius.full).background(color))
                }
                Text(
                    if (remaining > 0) "未处理将在 ${remaining}s 后自动拒绝" else "已超时，等待客户端核心确认…",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (remaining < 15) c.error else c.textMuted,
                )
            } else {
                Text("正在读取剩余时间…", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            }
        }

        error?.takeIf { it.first == req.requestId }?.let { InlineError(it.second) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("允许", { decide(AuthDecision.AllowOnce) }, Modifier.weight(1f), enabled = !busy)
            SecondaryButton("始终允许", { decide(AuthDecision.AlwaysAllow) }, Modifier.weight(1f), enabled = !busy)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("拒绝", { decide(AuthDecision.DenyOnce) }, Modifier.weight(1f), enabled = !busy, danger = true)
            SecondaryButton("始终拒绝", { decide(AuthDecision.AlwaysDeny) }, Modifier.weight(1f), enabled = !busy, danger = true)
        }
        InlineNote("「允许」「拒绝」仅在 10 分钟内有效；「始终」会创建永久规则，可在「防御」中管理。")
    }
}
