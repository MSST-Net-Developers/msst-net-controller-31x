package com.msst.net.ui.dialogs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.msst.net.model.Relay
import com.msst.net.model.RelayList
import com.msst.net.model.Req
import com.msst.net.platform.query
import com.msst.net.state.Phase
import com.msst.net.state.RoomCreateState
import com.msst.net.state.RoomJoinState
import com.msst.net.state.RoomRelayChoice
import com.msst.net.state.attempt
import com.msst.net.state.errorMessage
import com.msst.net.state.operationStage
import com.msst.net.ui.LocalController
import com.msst.net.ui.components.CheckRow
import com.msst.net.ui.components.FieldLabel
import com.msst.net.ui.components.InlineError
import com.msst.net.ui.components.InlineNote
import com.msst.net.ui.components.Modal
import com.msst.net.ui.components.PrimaryButton
import com.msst.net.ui.components.SecondaryButton
import com.msst.net.ui.components.Select
import com.msst.net.ui.components.SuccessFeedback
import com.msst.net.ui.components.TextInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Core's progress report for [requestType] while [busy]. */
@Composable
private fun stageOf(busy: Boolean, requestType: String): String? {
    val view by LocalController.current.store.view.collectAsState()
    return if (busy) operationStage(view?.state, requestType) else null
}

/** Close the dialog shortly after Core confirmed the room operation. */
@Composable
private fun CloseOnSuccess(phase: Phase, onClose: () -> Unit) {
    LaunchedEffect(phase) {
        if (phase == Phase.Success) {
            delay(800)
            onClose()
        }
    }
}

/**
 * Core answers once the room is created/joined (as a background operation
 * on protocol 0.4, whose stage is shown while waiting); the room view itself
 * comes from Core's room state.
 */
@Composable
fun CreateRoomDialog(onClose: () -> Unit) {
    val ctx = LocalController.current
    val scope = rememberCoroutineScope()
    val s = remember { RoomCreateState() }
    val submit = { if (s.canSubmit) scope.launch { s.submit(ctx.backend) } }
    CloseOnSuccess(s.phase, onClose)

    // Official relays come from Core (the coordinator's relay list).
    var official by remember { mutableStateOf<List<Relay>?>(null) }
    var officialError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val r = attempt { ctx.backend.query<RelayList>(Req.getRelays()) }
        official = r.getOrNull()?.relays.orEmpty()
        officialError = r.errorMessage
    }
    val choices = listOf(RoomRelayChoice.Unspecified) +
        official.orEmpty().map { RoomRelayChoice.Official(it.addr) } +
        RoomRelayChoice.SelfHosted

    Modal(onClose, "创建房间", busy = s.busy, actions = if (s.phase == Phase.Success) null else {
        {
            SecondaryButton("取消", onClose, enabled = !s.busy)
            PrimaryButton(if (s.busy) "创建中…" else "创建", { submit() }, enabled = s.canSubmit || s.busy, busy = s.busy)
        }
    }) {
        if (s.phase == Phase.Success) {
            SuccessFeedback("房间创建成功")
        } else {
            TextInput(s.name, { s.name = it }, Modifier.fillMaxWidth(), label = "房间名", placeholder = "请输入房间名", enabled = !s.busy, onDone = { submit() })
            CheckRow("设置房间密码", s.hasPassword, { s.hasPassword = it }, enabled = !s.busy)
            if (s.hasPassword) {
                TextInput(s.password, { s.password = it }, Modifier.fillMaxWidth(), label = "房间密码", placeholder = "留空则无密码保护", enabled = !s.busy, password = true)
            }

            FieldLabel("房间中转服务器")
            Select(choices, s.relay, ::relayChoiceLabel, { s.relay = it }, Modifier.fillMaxWidth(), enabled = !s.busy)
            when {
                officialError != null -> InlineNote("官方中转服务器列表读取失败：$officialError")
                official == null -> InlineNote("正在读取官方中转服务器列表…")
            }
            when (s.relay) {
                RoomRelayChoice.Unspecified -> InlineNote("房间成员各自使用自己的中转服务器。")
                is RoomRelayChoice.Official -> InlineNote("房间内需要中转的流量都经过所选的官方中转服务器；它不可用时，成员自动回退到各自的中转服务器。")
                RoomRelayChoice.SelfHosted -> {
                    TextInput(
                        s.relayLink, { s.relayLink = it }, Modifier.fillMaxWidth(),
                        label = "中转服务器地址", placeholder = "msst-relay://主机:端口#公钥",
                        enabled = !s.busy, mono = true,
                    )
                    InlineNote("填写自托管中转服务器启动时显示的连接串。房间成员会看到此房间使用自托管中转服务器；它不可用时，成员自动回退到各自的中转服务器。")
                    if (s.showRelayPassword) {
                        TextInput(
                            s.relayPassword, { s.relayPassword = it }, Modifier.fillMaxWidth(),
                            label = "中转服务器接入密码", placeholder = "请输入中转服务器接入密码",
                            enabled = !s.busy, password = true, onDone = { submit() },
                        )
                    }
                }
            }

            stageOf(s.busy, "create_room")?.let { InlineNote("$it…") }
            s.error?.let { InlineError(it) }
        }
    }
}

private fun relayChoiceLabel(c: RoomRelayChoice) = when (c) {
    RoomRelayChoice.Unspecified -> "不指定"
    is RoomRelayChoice.Official -> "官方：${c.addr}"
    RoomRelayChoice.SelfHosted -> "自托管中转服务器…"
}

@Composable
fun JoinRoomDialog(onClose: () -> Unit) {
    val ctx = LocalController.current
    val scope = rememberCoroutineScope()
    val s = remember { RoomJoinState() }
    val submit = { if (s.canSubmit) scope.launch { s.submit(ctx.backend) } }
    CloseOnSuccess(s.phase, onClose)
    val stage = stageOf(s.busy, "join_room")

    Modal(onClose, "加入房间", busy = s.busy, actions = if (s.phase == Phase.Success) null else {
        {
            SecondaryButton("取消", onClose, enabled = !s.busy)
            PrimaryButton(if (s.busy && !s.askPassword) "加入中…" else "加入", { submit() }, enabled = s.roomId.isNotBlank(), busy = s.busy && !s.askPassword)
        }
    }) {
        if (s.phase == Phase.Success) {
            SuccessFeedback("已成功加入房间")
        } else {
            TextInput(s.roomId, { s.roomId = it }, Modifier.fillMaxWidth(), label = "房间号", placeholder = "请输入房间号", enabled = !s.busy, mono = true, onDone = { submit() })
            if (!s.askPassword) {
                stage?.let { InlineNote("$it…") }
                s.error?.let { InlineError(it) }
            }
        }
    }

    // Core reported that the room has a password.
    if (s.askPassword && s.phase != Phase.Success) {
        Modal({ s.cancelPassword() }, "输入房间密码", busy = s.busy, actions = {
            SecondaryButton("取消", { s.cancelPassword() }, enabled = !s.busy)
            PrimaryButton(if (s.busy) "加入中…" else "加入", { submit() }, enabled = s.canSubmit, busy = s.busy)
        }) {
            InlineNote("房间 ${s.roomId.trim()} 设有密码，请输入房间创建者提供的密码。")
            TextInput(s.password, { s.password = it }, Modifier.fillMaxWidth(), label = "房间密码", placeholder = "请输入房间密码", enabled = !s.busy, password = true, onDone = { submit() })
            stage?.let { InlineNote("$it…") }
            s.error?.let { InlineError(it) }
        }
    }
}
