package com.msst.net.platform

// The contract between the shared UI and each Controller variant.
//
// Every variant reaches Core the same way — through the Rust Controller
// library's IPC client — and differs only in how it reaches that library
// (JNI on Android and desktop, the WebUI server's HTTPS API in the browser)
// and in the variant behaviour declared by `ControllerFeatures`.

import com.msst.net.model.ClearRulesResult
import com.msst.net.model.ControllerJson
import com.msst.net.model.ControllerView
import com.msst.net.model.IpcRequest
import com.msst.net.model.RetryOutcome
import com.msst.net.model.UiNotice
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/** A failed request: [message] is for users; [category]/[code] are the
 *  Controller's or Core's machine-readable codes; [detail] is diagnostic text. */
class ApiException(
    message: String,
    val category: String,
    val code: String,
    val detail: String? = null,
) : Exception(message)

/** User-facing message for any failure. */
fun Throwable.userMessage(): String = message ?: toString()

/**
 * The Controller library, as seen by the UI.  Domain state comes only from
 * [view]; commands go to Core through [request] and their effects come back
 * through the view.
 */
interface ControllerBackend {
    /** Connection status, diagnostics and Core's state, read in one call. */
    suspend fun view(): ControllerView

    /** Send an `IpcRequest`; returns Core's response data.  For commands,
     *  success means Core *accepted* the request.  Throws [ApiException]. */
    suspend fun request(request: IpcRequest): JsonElement

    /** Delete every firewall rule; partial failures are reported. */
    suspend fun clearAclRules(): ClearRulesResult

    /** Reconnect now if not connected, otherwise re-read everything. */
    suspend fun retry(): RetryOutcome

    /** Change notifications; re-read [view] on [UiNotice.Changed]. */
    val notices: Flow<UiNotice>
}

/** Send a request and decode Core's response data. */
suspend inline fun <reified T> ControllerBackend.query(request: IpcRequest): T {
    val data = request(request)
    return try {
        ControllerJson.decodeFromJsonElement<T>(data)
    } catch (e: IllegalArgumentException) { // SerializationException
        throw ApiException("核心服务返回了无法识别的数据，请将日志提供给开发组。", "codec", "codec", e.message)
    }
}

/** Which Controller this is. */
enum class ControllerVariant { Desktop, Mobile, Web }

/**
 * Behaviour that differs between Controller variants, declared in one
 * place instead of scattered platform checks.
 */
data class ControllerFeatures(
    /** The rule wizard and permission-group management.  Without it, rules
     *  are only created by quick actions (access log, room mode). */
    val manualRuleCreation: Boolean,
    /** Interactive authorization: the access-request dialog and
     *  notifications.  Without it, requests are left to another Controller
     *  (or time out in Core). */
    val interactiveAuth: Boolean,
) {
    companion object {
        fun of(variant: ControllerVariant) = when (variant) {
            ControllerVariant.Desktop -> ControllerFeatures(manualRuleCreation = true, interactiveAuth = true)
            ControllerVariant.Mobile -> ControllerFeatures(manualRuleCreation = false, interactiveAuth = true)
            ControllerVariant.Web -> ControllerFeatures(manualRuleCreation = true, interactiveAuth = false)
        }
    }
}

/** OS services the UI needs. */
interface PlatformServices {
    /** Open a URL in the system browser (or a new browser tab). */
    fun openUrl(url: String)

    /** Copy text; returns whether it worked. */
    suspend fun copyText(text: String): Boolean

    /** Save text as a file the user picks (or share it, on mobile).
     *  Returns false if the user cancelled. */
    suspend fun saveText(suggestedName: String, content: String): Boolean
}

/** Shows a system notification for a new access request (variants with
 *  [ControllerFeatures.interactiveAuth]).  Core enforces the decision and
 *  the timeout. */
fun interface AccessRequestNotifier {
    fun notify(request: UiNotice.AccessRequest)
}

/** The local VPN service hosting the embedded Core (Android).  Its state is
 *  the service's own lifecycle, not Core's networking state. */
interface VpnControl {
    sealed interface State {
        data object Stopped : State
        data object Starting : State
        data object Running : State
        data object Sleeping : State
        data class Failed(val message: String) : State
    }

    val state: StateFlow<State>
    /** Start (asking for the VPN permission first if needed). */
    fun start()
    fun stop()
    /** Pause the VPN interface; Core keeps running. */
    fun sleep()
    fun wake()
}

/** An app installed on this device (per-app exclusion). */
data class DeviceApp(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val icon: ImageBitmap?,
)

/**
 * Per-app exclusion from MSST-Net (Android `VpnService` per-app VPN).  The
 * list belongs to this device's VPN interface, not to Core: excluded apps'
 * traffic never enters the TUN, so Core never sees it.
 */
interface AppExclusion {
    /** Package names currently excluded. */
    val excluded: StateFlow<Set<String>>

    /** Apps the user can choose from (launchable apps, not this one). */
    suspend fun apps(): List<DeviceApp>

    /** Save the list and apply it; a running VPN interface is rebuilt. */
    suspend fun setExcluded(packages: Set<String>)
}

/** WebUI access control: the password session and the IP allowlist,
 *  enforced by the WebUI server. */
interface WebSession {
    /** Whether the browser holds a valid session. */
    suspend fun check(): Boolean
    /** Throws [ApiException] (code `invalid_password` for a wrong password). */
    suspend fun login(password: String)
    suspend fun logout()
    /** Called when any request finds the session missing or expired. */
    val expired: Flow<Unit>
    suspend fun remoteAccess(): RemoteAccessConfig
    suspend fun setAllowedCidrs(cidrs: List<String>)
}

data class RemoteAccessConfig(val port: Int, val allowedCidrs: List<String>)

/** Everything a Controller variant provides to the shared UI. */
interface ControllerPlatform {
    val variant: ControllerVariant
    val features: ControllerFeatures get() = ControllerFeatures.of(variant)
    val backend: ControllerBackend
    val services: PlatformServices
    val notifier: AccessRequestNotifier? get() = null
    /** Present when this Controller hosts Core in a local VPN service. */
    val vpn: VpnControl? get() = null
    /** Present when apps can be excluded from MSST-Net (Android). */
    val appExclusion: AppExclusion? get() = null
    /** Present when access needs a password (WebUI). */
    val session: WebSession? get() = null
}
