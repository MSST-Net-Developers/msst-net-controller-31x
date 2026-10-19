@file:OptIn(ExperimentalWasmJsInterop::class)

package com.msst.net

import com.msst.net.model.ClearRulesResult
import com.msst.net.model.ControllerJson
import com.msst.net.model.ControllerView
import com.msst.net.model.IpcRequest
import com.msst.net.model.RetryOutcome
import com.msst.net.model.UiNotice
import com.msst.net.model.obj
import com.msst.net.platform.ApiException
import com.msst.net.platform.ControllerBackend
import com.msst.net.platform.ControllerPlatform
import com.msst.net.platform.ControllerVariant
import com.msst.net.platform.PlatformServices
import com.msst.net.platform.RemoteAccessConfig
import com.msst.net.platform.WebSession
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.w3c.dom.WebSocket
import kotlin.js.Promise

/**
 * WebUI Controller: the browser talks to the msst-net-webui server
 * (same origin, HTTPS, session cookie), which holds the Controller
 * library's IPC client and enforces the access password and IP allowlist.
 */
class WebPlatform : ControllerPlatform {
    private val http = Http()
    override val variant = ControllerVariant.Web
    override val backend: ControllerBackend = WebBackend(http)
    override val session: WebSession = Session(http)

    override val services = object : PlatformServices {
        override fun openUrl(url: String) {
            window.open(url, "_blank", "noopener")
        }

        override suspend fun copyText(text: String): Boolean =
            runCatching { window.navigator.clipboard.writeText(text).await<JsAny?>() }.isSuccess

        override suspend fun saveText(suggestedName: String, content: String): Boolean {
            downloadText(suggestedName, content)
            return true
        }
    }
}

// ── HTTP ─────────────────────────────────────────────────────────────────────

private external interface HttpResult : JsAny {
    val status: Int
    val unauthorized: Boolean
    val text: String
}

private fun fetchJs(method: String, path: String, body: String?): Promise<HttpResult> = js(
    """fetch(path, {
        method: method,
        credentials: 'same-origin',
        headers: body != null ? { 'Content-Type': 'application/json' } : undefined,
        body: body != null ? body : undefined,
    }).then(async (r) => ({
        status: r.status,
        unauthorized: r.headers.get('X-Webui-Unauthorized') != null,
        text: await r.text(),
    }))"""
)

private fun downloadText(name: String, content: String): Unit = js(
    """{
        const url = URL.createObjectURL(new Blob([content], { type: 'text/plain' }));
        const a = document.createElement('a');
        a.href = url;
        a.download = name;
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    }"""
)

private class Http {
    /** Emits when the server says the session is missing or expired. */
    val expired = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    suspend fun call(method: String, path: String, body: JsonElement? = null): JsonElement {
        val r = try {
            fetchJs(method, path, body?.toString()).await<HttpResult>()
        } catch (e: Throwable) {
            throw ApiException("无法连接到 WebUI 服务，请检查网络。", "connection", "webui_unreachable")
        }
        if (r.status == 401 && r.unauthorized) {
            expired.tryEmit(Unit)
            throw ApiException("登录已失效，请重新登录。", "session", "unauthorized")
        }
        val data = runCatching { if (r.text.isEmpty()) JsonNull else ControllerJson.parseToJsonElement(r.text) }.getOrNull()
        if (r.status !in 200..299) {
            val o = data as? JsonObject
            fun s(k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull
            // A Controller/Core error (`UiError`) as-is, else the server's own message.
            if (s("message") != null) throw ApiException(s("message")!!, s("category") ?: "core", s("code") ?: "unknown", s("detail"))
            throw ApiException(s("error") ?: r.text.ifEmpty { "HTTP ${r.status}" }, "http", r.status.toString())
        }
        return data ?: throw ApiException("WebUI 服务返回了无法识别的数据。", "codec", "codec")
    }
}

// ── Backend ──────────────────────────────────────────────────────────────────

private class WebBackend(private val http: Http) : ControllerBackend {
    override suspend fun view(): ControllerView =
        ControllerJson.decodeFromJsonElement(ControllerView.serializer(), http.call("GET", "/api/view"))

    override suspend fun request(request: IpcRequest): JsonElement = http.call("POST", "/api/ipc", request)

    override suspend fun clearAclRules(): ClearRulesResult =
        ControllerJson.decodeFromJsonElement(ClearRulesResult.serializer(), http.call("POST", "/api/acl/clear", obj()))

    override suspend fun retry(): RetryOutcome {
        val o = http.call("POST", "/api/reconnect", obj()) as? JsonObject
        return if ((o?.get("outcome") as? JsonPrimitive)?.contentOrNull == "resyncing") RetryOutcome.Resyncing else RetryOutcome.Reconnecting
    }

    /** `/ws/events`, reconnecting with backoff; emits [UiNotice.Changed] on
     *  every (re)connect, as changes may have been missed meanwhile. */
    override val notices: Flow<UiNotice> = callbackFlow {
        var socket: WebSocket? = null
        var closed = false
        var delayMs = 1_000
        var retry = 0
        fun connect() {
            if (closed) return
            val proto = if (window.location.protocol == "https:") "wss:" else "ws:"
            val ws = WebSocket("$proto//${window.location.host}/ws/events")
            socket = ws
            ws.onopen = {
                delayMs = 1_000
                trySend(UiNotice.Changed)
            }
            ws.onmessage = { e -> (e.data as? JsString)?.toString()?.let(UiNotice::parse)?.let { trySend(it) } }
            ws.onclose = {
                if (!closed) {
                    retry = window.setTimeout({ connect(); null }, delayMs)
                    delayMs = minOf(delayMs * 2, 15_000)
                }
            }
        }
        connect()
        awaitClose {
            closed = true
            window.clearTimeout(retry)
            socket?.close()
        }
    }
}

// ── Session ──────────────────────────────────────────────────────────────────

private class Session(private val http: Http) : WebSession {
    override val expired: Flow<Unit> = http.expired.asSharedFlow()

    override suspend fun check(): Boolean = try {
        http.call("GET", "/api/session")
        true
    } catch (e: ApiException) {
        if (e.code == "unauthorized") false else throw e
    }

    override suspend fun login(password: String) {
        try {
            http.call("POST", "/api/login", obj("password" to password))
        } catch (e: ApiException) {
            if (e.code == "401") throw ApiException("密码错误", "session", "invalid_password")
            throw e
        }
    }

    override suspend fun logout() {
        http.call("POST", "/api/logout", obj())
    }

    override suspend fun remoteAccess(): RemoteAccessConfig {
        val o = http.call("GET", "/api/config") as? JsonObject ?: JsonObject(emptyMap())
        return RemoteAccessConfig(
            port = (o["port"] as? JsonPrimitive)?.intOrNull ?: 0,
            allowedCidrs = (o["allowed_cidrs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
    }

    override suspend fun setAllowedCidrs(cidrs: List<String>) {
        http.call("PUT", "/api/config", obj("allowed_cidrs" to cidrs))
    }
}
