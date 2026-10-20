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
import com.msst.net.model.IpcRequest
import com.msst.net.model.Req
import com.msst.net.state.Phase
import com.msst.net.state.RoomJoinState
import com.msst.net.state.attempt
import com.msst.net.state.errorMessage
import com.msst.net.state.operationStage
import com.msst.net.ui.LocalController
import com.msst.net.ui.components.CheckRow
import com.msst.net.ui.components.InlineError
import com.msst.net.ui.components.InlineNote
import com.msst.net.ui.components.Modal
import com.msst.net.ui.components.PrimaryButton
import com.msst.net.ui.components.SecondaryButton
import com.msst.net.ui.components.SuccessFeedback
import com.msst.net.ui.components.TextInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Submits a room request.  Core answers once the room is created/joined
 * (as a background operation on protocol 0.4, whose stage is shown while
 * waiting); the room view itself comes from Core's room state.
 */
@Composable
private fun rememberRoomSubmit(onClose: () -> Unit): RoomSubmit {
    val ctx = LocalController.current
    val scope = rememberCoroutineScope()
    val s = remember { RoomSubmit() }
    s.submit = { req ->
        s.phase = Phase.Submitting
        s.error = null
        scope.launch {
            val r = attempt { ctx.backend.request(req) }
            s.error = r.errorMessage
            s.phase = if (r.isSuccess) Phase.Success else Phase.Error
        }
    }
    LaunchedEffect(s.phase) {
        if (s.phase == Phase.Success) {
            delay(800)
            onClose()
        }
    }
    return s
}

/** Core's progress report for the running request, while submitting. */
@Composable
private fun stageOf(s: RoomSubmit, requestType: String): String? {
    val view by LocalController.current.store.view.collectAsState()
    return if (s.busy) operationStage(view?.state, requestType) else null
}

private class RoomSubmit {
    var phase by mutableStateOf(Phase.Idle)
    var error by mutableStateOf<String?>(null)
    var submit: (IpcRequest) -> Unit = {}
    val busy get() = phase == Phase.Submitting
}

@Composable
fun CreateRoomDialog(onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var hasPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    val s = rememberRoomSubmit(onClose)
    val submit = {
        if (name.isNotBlank() && !s.busy) s.submit(Req.createRoom(name.trim(), if (hasPassword) password.trim().ifEmpty { null } else null))
    }

    Modal(onClose, "创建房间", busy = s.busy, actions = if (s.phase == Phase.Success) null else {
        {
            SecondaryButton("取消", onClose, enabled = !s.busy)
            PrimaryButton(if (s.busy) "创建中…" else "创建", submit, enabled = name.isNotBlank(), busy = s.busy)
        }
    }) {
        if (s.phase == Phase.Success) {
            SuccessFeedback("房间创建成功")
        } else {
            TextInput(name, { name = it }, Modifier.fillMaxWidth(), label = "房间名", placeholder = "请输入房间名", enabled = !s.busy, onDone = submit)
            CheckRow("设置房间密码", hasPassword, { hasPassword = it }, enabled = !s.busy)
            if (hasPassword) {
                TextInput(password, { password = it }, Modifier.fillMaxWidth(), label = "房间密码", placeholder = "留空则无密码保护", enabled = !s.busy, password = true)
            }
            stageOf(s, "create_room")?.let { InlineNote("$it…") }
            s.error?.let { InlineError(it) }
        }
    }
}

@Composable
fun JoinRoomDialog(onClose: () -> Unit) {
    val ctx = LocalController.current
    val scope = rememberCoroutineScope()
    val s = remember { RoomJoinState() }
    val submit = { if (s.canSubmit) scope.launch { s.submit(ctx.backend) } }
    LaunchedEffect(s.phase) {
        if (s.phase == Phase.Success) {
            delay(800)
            onClose()
        }
    }
    val view by ctx.store.view.collectAsState()
    val stage = if (s.busy) operationStage(view?.state, "join_room") else null

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
