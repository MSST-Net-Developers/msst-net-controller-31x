package com.msst.net.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.msst.net.model.Req
import com.msst.net.platform.ControllerBackend

/** Core's code for `JoinRoom` without a password to a room that has one
 *  (protocol 0.4).  `already_in_room` needs no special handling: its message
 *  asks the user to leave the current room. */
private const val ROOM_PASSWORD_REQUIRED = "room_password_required"

/**
 * The join-room form.  Only Core knows whether a room has a password: the
 * first join goes without one, and when Core answers
 * `room_password_required` the dialog asks for it ([askPassword]) and joins
 * again with it.
 */
class RoomJoinState {
    var roomId by mutableStateOf("")
    var password by mutableStateOf("")
    /** Core reported that the room has a password: show the password prompt. */
    var askPassword by mutableStateOf(false)
        private set
    var phase by mutableStateOf(Phase.Idle)
        private set
    /** Shown in the password prompt while it is open, else in the form. */
    var error by mutableStateOf<String?>(null)
        private set

    val busy get() = phase == Phase.Submitting
    val canSubmit get() = !busy && roomId.isNotBlank() && (!askPassword || password.isNotBlank())

    suspend fun submit(backend: ControllerBackend) {
        if (!canSubmit) return
        phase = Phase.Submitting
        error = null
        val pw = if (askPassword) password.trim() else null
        val r = attempt { backend.request(Req.joinRoom(roomId.trim(), pw)) }
        when {
            r.isSuccess -> phase = Phase.Success
            !askPassword && r.errorCode == ROOM_PASSWORD_REQUIRED -> {
                askPassword = true
                phase = Phase.Idle
            }
            else -> {
                error = r.errorMessage
                phase = Phase.Error
            }
        }
    }

    /** Close the password prompt and return to the room ID. */
    fun cancelPassword() {
        askPassword = false
        password = ""
        error = null
        phase = Phase.Idle
    }
}
