package com.msst.net.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.msst.net.controller.ApiException
import com.msst.net.controller.Controller
import com.msst.net.controller.ControllerView
import com.msst.net.controller.Notice
import com.msst.net.controller.Req
import com.msst.net.controller.objects
import com.msst.net.controller.str
import com.msst.net.controller.strings
import com.msst.net.platform.MsstVpnService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

// ── Screen models for on-demand queries (not part of the Controller view) ────

data class NetSettingsState(
    val disableP2p: Boolean = false,
    val lockPath: Boolean = false,
    val preferredRelay: String? = null,
    val mtuOverride: Int? = null,
    val keepaliveSecs: Int? = null,
)

data class PathCandidate(val addr: String, val rttMs: Double?, val lossRate: Double?, val status: String)
data class PathViewEntry(val deviceId: String, val displayName: String?, val chosen: String, val candidates: List<PathCandidate>)
data class TrafficEntry(val deviceId: String, val displayName: String?, val tx: Long, val rx: Long, val drop: Long)
data class EndpointRecord(val endpoints: List<String>, val timestamp: Long)
data class EndpointInfo(val current: List<String> = emptyList(), val history: List<EndpointRecord> = emptyList())
data class RelayEntry(val addr: String)
data class LogEntry(val level: String, val target: String, val message: String, val timestampMs: Long)

data class PingState(
    val results: List<Double?> = emptyList(),
    val done: Boolean = false,
    val running: Boolean = false,
    val error: String? = null,
)

sealed class AccessReason {
    data class MatchedRule(val ruleId: String, val comment: String?) : AccessReason()
    object InteractiveAuth : AccessReason()
    object InteractiveAuthTimeout : AccessReason()
    object DefaultDeny : AccessReason()
    object Unknown : AccessReason()
}

data class AccessLogEntry(
    val id: String,
    val srcDeviceId: String,
    val serviceName: String?,
    val proto: String,
    val dstPort: Int,
    val decision: String,
    val reason: AccessReason,
    val timestampMs: Long,
    val ruleId: String?,
    val count: Long,
)

data class ServiceItem(val id: String, val name: String, val ports: List<String>)
data class ServiceCategory(val id: String, val label: String, val services: List<ServiceItem>)

enum class SaveStatus { IDLE, SAVING, SAVED, ERROR }

enum class AuthFlowStatus { IDLE, STARTING, PENDING, VERIFYING, AUTHORIZED, ERROR }

data class AuthFlowState(
    val status: AuthFlowStatus = AuthFlowStatus.IDLE,
    val userCode: String = "",
    val verificationUri: String = "",
    val error: String = "",
)

/** Result of a user action, for dialogs that show their own outcome. */
typealias Done = (error: String?) -> Unit

// Same TTL Core uses for "allow once" decisions (inbound.rs ONCE_RULE_TTL_SECS).
private const val ONCE_TTL_SECS = 600L

/**
 * UI state for all screens.  Domain state comes only from the Controller
 * view (re-read on every change notice); everything else is per-screen
 * query results or form state.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _view = MutableStateFlow<ControllerView?>(null)
    val view: StateFlow<ControllerView?> = _view.asStateFlow()

    val vpnState: StateFlow<MsstVpnService.State> = MsstVpnService.state

    private val _message = MutableStateFlow<String?>(null)
    /** Transient feedback (snackbar). */
    val message: StateFlow<String?> = _message.asStateFlow()

    private var viewSeq = 0L
    private var viewApplied = 0L

    init {
        viewModelScope.launch { refreshView() }
        viewModelScope.launch {
            Controller.notices.filterIsInstance<Notice.Changed>().collect {
                refreshView()
                // Settings changed elsewhere (SettingsChanged): keep an open panel current.
                if (_netSettings.value != null) loadNetSettings()
            }
        }
        // Safety net for missed notices.
        viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                refreshView()
            }
        }
    }

    fun refreshView() {
        val seq = ++viewSeq
        viewModelScope.launch {
            val v = runCatching { Controller.view() }.getOrNull() ?: return@launch
            if (seq < viewApplied) return@launch // a newer read already landed
            viewApplied = seq
            _view.value = v
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /** Run a request; report failure in the snackbar (or via [done]). */
    private fun act(what: String, done: Done? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            val err = try {
                block()
                null
            } catch (e: ApiException) {
                e.message ?: "未知错误"
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            if (done != null) done(err) else if (err != null) _message.value = "${what}失败：$err"
        }
    }

    // ── VPN service ─────────────────────────────────────────────────────────

    private fun vpnIntent(ctx: Context, action: String) = Intent(ctx, MsstVpnService::class.java).setAction(action)

    fun startVpn(ctx: Context) = ctx.startForegroundService(vpnIntent(ctx, MsstVpnService.ACTION_START))
    fun stopVpn(ctx: Context) = ctx.startService(vpnIntent(ctx, MsstVpnService.ACTION_STOP))
    fun sleepVpn(ctx: Context) = ctx.startService(vpnIntent(ctx, MsstVpnService.ACTION_SLEEP))
    fun wakeVpn(ctx: Context) = ctx.startService(vpnIntent(ctx, MsstVpnService.ACTION_WAKE))

    // ── Rooms ───────────────────────────────────────────────────────────────

    /** Room membership appears once Core's room state reports it. */
    fun createRoom(name: String, password: String, done: Done) =
        act("创建房间", done) { Controller.request(Req.createRoom(name, password.ifEmpty { null })) }

    fun joinRoom(id: String, password: String, done: Done) =
        act("加入房间", done) { Controller.request(Req.joinRoom(id, password.ifEmpty { null })) }

    fun leaveRoom(done: Done) {
        val id = _view.value?.room?.roomId ?: return done(null)
        act("退出房间", done) { Controller.request(Req.leaveRoom(id)) }
    }

    // ── Identity ────────────────────────────────────────────────────────────

    private val _saveStatus = MutableStateFlow(SaveStatus.IDLE)
    val saveStatus: StateFlow<SaveStatus> = _saveStatus.asStateFlow()

    fun saveIdentity(deviceName: String, deviceHandle: String) {
        val h = _view.value?.health
        _saveStatus.value = SaveStatus.SAVING
        act("保存设备标识", { err ->
            _saveStatus.value = if (err == null) SaveStatus.SAVED else SaveStatus.ERROR
            if (err != null) _message.value = "保存失败：$err"
            viewModelScope.launch { delay(2_000); _saveStatus.value = SaveStatus.IDLE }
        }) {
            if (deviceName.isNotBlank() && deviceName != h?.deviceDisplayName) Controller.request(Req.updateDisplayName(null, deviceName.trim()))
            if (deviceHandle.isNotBlank() && deviceHandle != h?.deviceHandle) Controller.request(Req.updateHandle(null, deviceHandle.trim()))
        }
    }

    // ── Firewall ────────────────────────────────────────────────────────────

    private val _ruleBusy = MutableStateFlow<Set<String>>(emptySet())
    val ruleBusy: StateFlow<Set<String>> = _ruleBusy.asStateFlow()

    private fun withRule(id: String, what: String, block: suspend () -> Unit) {
        _ruleBusy.value = _ruleBusy.value + id
        act(what, { err ->
            _ruleBusy.value = _ruleBusy.value - id
            if (err != null) _message.value = "${what}失败：$err"
        }, block)
    }

    fun setRuleEnabled(id: String, enabled: Boolean) =
        withRule(id, if (enabled) "启用规则" else "停用规则") {
            Controller.request(Req.updateAclRule(id, JSONObject().put("enabled", enabled)))
        }

    fun deleteRule(id: String) = withRule(id, "删除规则") { Controller.request(Req.deleteAclRule(id)) }

    fun clearRules() = act("清空规则") {
        val r = Controller.clearRules()
        val failed = r.optJSONArray("failed")
        if (failed != null && failed.length() > 0) {
            _message.value = "已删除 ${r.optInt("deleted")} 条，${failed.length()} 条删除失败"
        }
    }

    private val _pingBusy = MutableStateFlow(false)
    val pingBusy: StateFlow<Boolean> = _pingBusy.asStateFlow()

    /** Shown value is always Core's (Health.ping_enabled). */
    fun setPingEnabled(enabled: Boolean) {
        _pingBusy.value = true
        act("Ping 设置", { err ->
            _pingBusy.value = false
            if (err != null) _message.value = "Ping 设置失败：$err"
        }) { Controller.request(Req.setPingEnabled(enabled)) }
    }

    private val _accessLog = MutableStateFlow<List<AccessLogEntry>>(emptyList())
    val accessLog: StateFlow<List<AccessLogEntry>> = _accessLog.asStateFlow()
    private val _accessLogError = MutableStateFlow<String?>(null)
    val accessLogError: StateFlow<String?> = _accessLogError.asStateFlow()

    fun loadAccessLog() = act("读取访问记录", { err -> _accessLogError.value = err }) {
        val arr = Controller.requestObject(Req.getAccessLog(200)).optJSONArray("entries")
        _accessLog.value = arr.objects { e ->
            val r = e.optJSONObject("reason") ?: JSONObject()
            AccessLogEntry(
                id = e.getString("id"),
                srcDeviceId = e.optString("src_device_id"),
                serviceName = e.str("service_name"),
                proto = e.optString("proto"),
                dstPort = e.optInt("dst_port"),
                decision = e.optString("decision"),
                reason = when (r.optString("type")) {
                    "matched_rule" -> AccessReason.MatchedRule(r.optString("rule_id"), r.str("comment"))
                    "interactive_auth" -> AccessReason.InteractiveAuth
                    "interactive_auth_timeout" -> AccessReason.InteractiveAuthTimeout
                    "default_deny" -> AccessReason.DefaultDeny
                    else -> AccessReason.Unknown
                },
                timestampMs = e.optLong("timestamp_ms"),
                ruleId = e.str("rule_id"),
                count = e.optLong("count", 1),
            )
        }
    }

    /** Create a rule for one access-log entry (3.0.x quick actions). */
    fun ruleFromLog(entry: AccessLogEntry, action: String, once: Boolean, done: Done) = act("添加规则", done) {
        val rule = JSONObject()
            .put("action", action)
            .put("direction", "inbound")
            .put("enabled", true)
            .put("expires_at", if (once) System.currentTimeMillis() / 1000 + ONCE_TTL_SECS else JSONObject.NULL)
            .put("principals", org.json.JSONArray().put(JSONObject().put("kind", "device").put("id", entry.srcDeviceId)))
            .put("protocol_ports", org.json.JSONArray().put(JSONObject().put("proto", entry.proto).put("port", entry.dstPort.toString())))
            .put("comment", "访问记录操作：${if (action == "allow") "允许" else "拒绝"} ${entry.proto}/${entry.dstPort}")
        Controller.request(Req.createAclRule(rule))
        loadAccessLog()
    }

    /** Revoke: delete the rule Core reported as deciding this entry. */
    fun revokeFromLog(entry: AccessLogEntry, done: Done) = act("撤销授权", done) {
        val id = entry.ruleId ?: throw ApiException("该记录没有对应的规则", "no_rule")
        Controller.request(Req.deleteAclRule(id))
        loadAccessLog()
    }

    fun createSpaceRule(done: Done) = act("添加规则", done) {
        val rule = JSONObject()
            .put("action", "allow")
            .put("direction", "inbound")
            .put("enabled", true)
            .put("principals", org.json.JSONArray().put(JSONObject().put("kind", "space").put("id", "*")))
            .put("protocol_ports", org.json.JSONArray().put(JSONObject().put("proto", "tcp+udp").put("port", "*")))
            .put("comment", "房间模式：放行当前房间内所有成员")
        Controller.request(Req.createAclRule(rule))
    }

    private val _services = MutableStateFlow<List<ServiceCategory>>(emptyList())
    val services: StateFlow<List<ServiceCategory>> = _services.asStateFlow()

    fun loadServices() {
        if (_view.value?.supportsServices != true) return
        act("读取服务列表", { }) {
            _services.value = Controller.requestObject(Req.getServices()).optJSONArray("categories").objects { c ->
                ServiceCategory(
                    c.optString("id"), c.optString("label"),
                    c.optJSONArray("services").objects { s -> ServiceItem(s.optString("id"), s.optString("name"), s.optJSONArray("ports").strings()) },
                )
            }
        }
    }

    // ── Interactive authorization ───────────────────────────────────────────

    private val _authBusy = MutableStateFlow<String?>(null)
    val authBusy: StateFlow<String?> = _authBusy.asStateFlow()

    /** The request leaves the queue when Core's pending list drops it. */
    fun submitAuthDecision(requestId: String, decision: String) {
        _authBusy.value = requestId
        act("处理访问请求", { err ->
            _authBusy.value = null
            if (err != null) _message.value = "处理访问请求失败：$err"
        }) { Controller.request(Req.authDecision(requestId, decision)) }
    }

    // ── OIDC access authentication ──────────────────────────────────────────

    private val _authFlow = MutableStateFlow(AuthFlowState())
    val authFlow: StateFlow<AuthFlowState> = _authFlow.asStateFlow()
    private var authJob: Job? = null

    fun startOidcAuth() {
        authJob?.cancel()
        _authFlow.value = AuthFlowState(status = AuthFlowStatus.STARTING)
        authJob = viewModelScope.launch {
            try {
                val res = Controller.requestObject(Req.authStart())
                val sessionId = res.getString("session_id")
                var interval = res.optInt("interval", 5).coerceAtLeast(3)
                _authFlow.value = AuthFlowState(AuthFlowStatus.PENDING, res.optString("user_code"), res.optString("verification_uri"))
                while (isActive) {
                    delay(interval * 1000L)
                    val p = Controller.requestObject(Req.authPoll(sessionId))
                    val status = p.optString("status")
                    if (p.optBoolean("pending") || status == "authorization_pending" || status == "slow_down") {
                        if (status == "slow_down") interval += 5
                        continue
                    }
                    break
                }
                // Core stored the result; success only once Core's Health confirms it.
                _authFlow.value = _authFlow.value.copy(status = AuthFlowStatus.VERIFYING)
                repeat(30) {
                    if (_view.value?.health?.isAuthenticated == true) {
                        _authFlow.value = _authFlow.value.copy(status = AuthFlowStatus.AUTHORIZED)
                        return@launch
                    }
                    refreshView()
                    delay(500)
                }
                _authFlow.value = AuthFlowState(
                    status = AuthFlowStatus.ERROR,
                    error = "认证页面已完成，但客户端核心尚未确认认证结果，请稍后查看状态或重试。",
                )
            } catch (e: ApiException) {
                _authFlow.value = AuthFlowState(status = AuthFlowStatus.ERROR, error = e.message ?: "未知错误")
            }
        }
    }

    fun cancelOidcAuth() {
        authJob?.cancel()
        _authFlow.value = AuthFlowState()
    }

    // ── Diagnostics / advanced network settings ─────────────────────────────

    private val _netSettings = MutableStateFlow<NetSettingsState?>(null)
    val netSettings: StateFlow<NetSettingsState?> = _netSettings.asStateFlow()

    private fun parseSettings(d: JSONObject) = NetSettingsState(
        disableP2p = d.optBoolean("disable_p2p"),
        lockPath = d.optBoolean("lock_path"),
        preferredRelay = d.str("preferred_relay"),
        mtuOverride = if (d.isNull("mtu_override")) null else d.optInt("mtu_override"),
        keepaliveSecs = if (d.isNull("keepalive_secs")) null else d.optInt("keepalive_secs"),
    )

    fun loadNetSettings() = act("读取网络设置") { _netSettings.value = parseSettings(Controller.requestObject(Req.getNetSettings())) }

    /** Send only the changed field; show what Core stored. */
    fun patchNetSettings(patch: JSONObject) = act("保存网络设置", { err ->
        if (err != null) {
            _message.value = "保存失败：$err"
            loadNetSettings()
        }
    }) { _netSettings.value = parseSettings(Controller.requestObject(Req.setNetSettings(patch))) }

    private val _pathView = MutableStateFlow<List<PathViewEntry>>(emptyList())
    val pathView: StateFlow<List<PathViewEntry>> = _pathView.asStateFlow()
    private var pathJob: Job? = null

    private suspend fun fetchPathView() {
        _pathView.value = Controller.requestObject(Req.getPathView()).optJSONArray("peers").objects { p ->
            PathViewEntry(
                p.optString("device_id"), p.str("display_name"), p.optString("chosen"),
                p.optJSONArray("candidates").objects { c ->
                    PathCandidate(
                        c.optString("addr"),
                        if (c.isNull("rtt_ms") || !c.has("rtt_ms")) null else c.optDouble("rtt_ms"),
                        if (c.has("loss_rate")) c.optDouble("loss_rate") else null,
                        c.optString("status"),
                    )
                },
            )
        }
    }

    fun startPathViewPolling() {
        pathJob?.cancel()
        pathJob = viewModelScope.launch {
            while (isActive) {
                runCatching { fetchPathView() }
                delay(2_000)
            }
        }
    }

    fun stopPathViewPolling() {
        pathJob?.cancel()
        pathJob = null
    }

    private val _traffic = MutableStateFlow<List<TrafficEntry>>(emptyList())
    val traffic: StateFlow<List<TrafficEntry>> = _traffic.asStateFlow()

    fun loadTraffic() = act("读取流量统计") {
        _traffic.value = Controller.requestObject(Req.getTrafficStats()).optJSONArray("peers").objects { p ->
            TrafficEntry(p.optString("device_id"), p.str("display_name"), p.optLong("tx"), p.optLong("rx"), p.optLong("drop"))
        }
    }

    private val _endpoints = MutableStateFlow(EndpointInfo())
    val endpoints: StateFlow<EndpointInfo> = _endpoints.asStateFlow()

    fun loadEndpoints() = act("读取端点") {
        val d = Controller.requestObject(Req.getEndpoints())
        _endpoints.value = EndpointInfo(
            d.optJSONArray("current").strings(),
            d.optJSONArray("history").objects { h -> EndpointRecord(h.optJSONArray("endpoints").strings(), h.optLong("timestamp")) },
        )
    }

    fun refreshEndpoint() = act("刷新端点") {
        Controller.request(Req.refreshEndpoint())
        _message.value = "已请求重新探测端点"
        delay(3_000) // detection runs asynchronously in Core
        loadEndpoints()
    }

    private val _relays = MutableStateFlow<List<RelayEntry>>(emptyList())
    val relays: StateFlow<List<RelayEntry>> = _relays.asStateFlow()
    private val _relaysError = MutableStateFlow<String?>(null)
    val relaysError: StateFlow<String?> = _relaysError.asStateFlow()

    fun loadRelays() = act("读取中转列表", { err -> _relaysError.value = err }) {
        _relays.value = Controller.requestObject(Req.getRelays()).optJSONArray("relays").objects { RelayEntry(it.optString("addr")) }
    }

    private val _pingState = MutableStateFlow(PingState())
    val pingState: StateFlow<PingState> = _pingState.asStateFlow()
    private var pingJob: Job? = null

    fun startPing(deviceId: String) {
        pingJob?.cancel()
        _pingState.value = PingState(running = true)
        pingJob = viewModelScope.launch {
            try {
                val jobId = Controller.requestObject(Req.startDiagPing(deviceId)).getString("job_id")
                while (isActive) {
                    delay(500)
                    val d = Controller.requestObject(Req.getDiagPingResult(jobId))
                    val arr = d.optJSONArray("results")
                    val results = if (arr == null) emptyList() else (0 until arr.length()).map { if (arr.isNull(it)) null else arr.getDouble(it) }
                    val done = d.optBoolean("done")
                    _pingState.value = PingState(results, done, !done)
                    if (done) break
                }
            } catch (e: ApiException) {
                _pingState.value = PingState(error = e.message)
            }
        }
    }

    fun reRegister() = act("重新注册") {
        Controller.request(Req.reRegister())
        _message.value = "已重新注册"
    }

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun loadLogs() = act("读取日志") {
        _logs.value = Controller.requestObject(Req.getLogs(2000)).optJSONArray("entries").objects { e ->
            LogEntry(e.optString("level"), e.optString("target"), e.optString("message"), e.optLong("timestamp_ms"))
        }
    }

    fun clearLogs() = act("清空日志") {
        Controller.request(Req.clearLogs())
        loadLogs()
    }
}
