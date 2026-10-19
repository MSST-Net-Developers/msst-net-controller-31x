package com.msst.net.jni

import com.msst.net.model.ClearRulesResult
import com.msst.net.model.ControllerJson
import com.msst.net.model.ControllerView
import com.msst.net.model.IpcRequest
import com.msst.net.model.RetryOutcome
import com.msst.net.model.UiNotice
import com.msst.net.platform.ApiException
import com.msst.net.platform.ControllerBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The Controller library in this process, over JNI (Android and desktop).
 * Create one per process: the library holds a single IPC client.
 */
class JniBackend(socketPath: String?) : ControllerBackend {
    private val _notices = MutableSharedFlow<UiNotice>(extraBufferCapacity = 64)
    override val notices: Flow<UiNotice> = _notices.asSharedFlow()

    init {
        unwrap(MsstNative.nativeInitController(socketPath))
        unwrap(MsstNative.nativeSetListener { json -> UiNotice.parse(json)?.let { _notices.tryEmit(it) } })
    }

    override suspend fun view(): ControllerView = call {
        val v = unwrap(MsstNative.nativeGetView())["view"] ?: JsonNull
        ControllerJson.decodeFromJsonElement(ControllerView.serializer(), v)
    }

    override suspend fun request(request: IpcRequest): JsonElement = call {
        unwrap(MsstNative.nativeRequest(request.toString()))["data"] ?: JsonNull
    }

    override suspend fun clearAclRules(): ClearRulesResult = call {
        ControllerJson.decodeFromJsonElement(ClearRulesResult.serializer(), unwrap(MsstNative.nativeClearRules())["data"] ?: JsonNull)
    }

    override suspend fun retry(): RetryOutcome = call {
        when ((unwrap(MsstNative.nativeRetry())["outcome"] as? JsonPrimitive)?.contentOrNull) {
            "resyncing" -> RetryOutcome.Resyncing
            else -> RetryOutcome.Reconnecting
        }
    }

    private suspend fun <T> call(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** `{"ok":true,…}` → the object; `{"ok":false,"error":…}` → [ApiException]. */
        fun unwrap(json: String): JsonObject {
            val o = ControllerJson.parseToJsonElement(json).jsonObject
            if ((o["ok"] as? JsonPrimitive)?.contentOrNull == "true") return o
            val e = o["error"] as? JsonObject ?: JsonObject(emptyMap())
            fun s(k: String) = (e[k] as? JsonPrimitive)?.contentOrNull
            throw ApiException(s("message") ?: "未知错误", s("category") ?: "unknown", s("code") ?: "unknown", s("detail"))
        }
    }
}
