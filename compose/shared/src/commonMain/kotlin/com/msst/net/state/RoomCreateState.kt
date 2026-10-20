package com.msst.net.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.msst.net.model.Req
import com.msst.net.platform.ControllerBackend

/** The relay a new room uses for its traffic ("房间中转服务器"). */
sealed interface RoomRelayChoice {
    /** Members keep using their own relays. */
    data object Unspecified : RoomRelayChoice
    /** An official relay from Core's relay list, by address. */
    data class Official(val addr: String) : RoomRelayChoice
    /** A self-hosted relay, given by its `msst-relay://` link. */
    data object SelfHosted : RoomRelayChoice
}

private const val RELAY_LINK_SCHEME = "msst-relay://"

/** The link marks the relay as having a password (`?pw=1`).  A display hint
 *  only: Core parses and validates the link. */
fun relayLinkHasPassword(link: String): Boolean {
    val s = link.trim()
    if (!s.lowercase().startsWith(RELAY_LINK_SCHEME)) return false
    val query = s.substringAfter('#', "").substringAfter('?', "")
    return query.split('&').any { it == "pw=1" }
}

/**
 * The create-room form.  Core checks the relay: whether a link is valid,
 * whether an address is an official relay, and (with the Coordinator) whether
 * the relay is online and its password right.
 */
class RoomCreateState {
    var name by mutableStateOf("")
    var hasPassword by mutableStateOf(false)
    var password by mutableStateOf("")
    var relay by mutableStateOf<RoomRelayChoice>(RoomRelayChoice.Unspecified)
    var relayLink by mutableStateOf("")
    var relayPassword by mutableStateOf("")
    /** Core said the self-hosted relay needs a password though its link
     *  does not say so. */
    private var relayPasswordAsked by mutableStateOf(false)
    var phase by mutableStateOf(Phase.Idle)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val busy get() = phase == Phase.Submitting

    /** Ask for the relay password: the link says the relay has one, or Core did. */
    val showRelayPassword get() =
        relay == RoomRelayChoice.SelfHosted && (relayLinkHasPassword(relayLink) || relayPasswordAsked)

    val canSubmit get() = !busy && name.isNotBlank() && when (relay) {
        RoomRelayChoice.SelfHosted -> relayLink.isNotBlank() && (!showRelayPassword || relayPassword.isNotBlank())
        else -> true
    }

    fun request() = Req.createRoom(
        name.trim(),
        if (hasPassword) password.trim().ifEmpty { null } else null,
        when (val r = relay) {
            RoomRelayChoice.Unspecified -> null
            is RoomRelayChoice.Official -> r.addr
            RoomRelayChoice.SelfHosted -> relayLink.trim()
        },
        if (showRelayPassword) relayPassword.trim() else null,
    )

    suspend fun submit(backend: ControllerBackend) {
        if (!canSubmit) return
        phase = Phase.Submitting
        error = null
        val r = attempt { backend.request(request()) }
        if (r.isSuccess) {
            phase = Phase.Success
            return
        }
        phase = Phase.Error
        error = when (r.errorCode) {
            "relay_password_required" -> {
                relayPasswordAsked = true
                r.errorMessage
            }
            // On CreateRoom this comes from the relay, not from a room.
            "room_password_required" -> {
                hasPassword = true
                "所选中转服务器要求房间设置密码，请为房间设置密码。"
            }
            else -> r.errorMessage
        }
    }
}
