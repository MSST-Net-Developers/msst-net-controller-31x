package com.msst.net

import com.msst.net.model.ClearRulesResult
import com.msst.net.model.ControllerView
import com.msst.net.model.FirewallRule
import com.msst.net.model.Health
import com.msst.net.model.IpcRequest
import com.msst.net.model.PermissionGroup
import com.msst.net.model.ProtocolPort
import com.msst.net.model.RetryOutcome
import com.msst.net.model.SystemState
import com.msst.net.model.UiNotice
import com.msst.net.model.obj
import com.msst.net.platform.AccessRequestNotifier
import com.msst.net.platform.ApiException
import com.msst.net.platform.ControllerBackend
import com.msst.net.state.ControllerStore
import com.msst.net.state.OidcFlow
import com.msst.net.state.Phase
import com.msst.net.state.PresetDevice
import com.msst.net.state.RoomMembers
import com.msst.net.state.RoomCreateState
import com.msst.net.state.RoomJoinState
import com.msst.net.state.RoomRelayChoice
import com.msst.net.state.relayLinkHasPassword
import com.msst.net.state.RuleWizardState
import com.msst.net.state.SourceMode
import com.msst.net.state.TargetMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A scripted backend: views and responses are supplied by the test. */
private class FakeBackend : ControllerBackend {
    val views = ArrayDeque<suspend () -> ControllerView>()
    var respond: (IpcRequest) -> JsonElement = { obj() }
    val sent = mutableListOf<IpcRequest>()
    val noticeFlow = MutableSharedFlow<UiNotice>(extraBufferCapacity = 16)
    var viewReads = 0

    override suspend fun view(): ControllerView {
        viewReads++
        return views.removeFirstOrNull()?.invoke() ?: ControllerView()
    }

    override suspend fun request(request: IpcRequest): JsonElement {
        sent += request
        return respond(request)
    }

    override suspend fun clearAclRules() = ClearRulesResult()
    override suspend fun retry() = RetryOutcome.Resyncing
    override val notices = noticeFlow
}

private fun joinParams(req: IpcRequest) = req["params"]!!.jsonObject

private fun joinPassword(req: IpcRequest): String? =
    joinParams(req)["password"]?.jsonPrimitive?.contentOrNull

private fun health(authenticated: Boolean) =
    ControllerView(state = SystemState(health = Health(status = "ok", isAuthenticated = authenticated)))

@OptIn(ExperimentalCoroutinesApi::class)
class StateTest {
    @Test
    fun staleViewDoesNotOverwriteNewerOne() = runTest {
        val backend = FakeBackend()
        val slow = CompletableDeferred<ControllerView>()
        backend.views += { slow.await() }
        backend.views += { health(true) }
        val store = ControllerStore(backend, this)

        val first = launch { store.refresh() }
        runCurrent()
        store.refresh() // newer read lands first
        assertEquals(true, store.view.value?.state?.health?.isAuthenticated)
        slow.complete(health(false))
        first.join()
        assertEquals(true, store.view.value?.state?.health?.isAuthenticated, "the older response must be dropped")
    }

    @Test
    fun noticesRefreshAndAccessRequestsNotify() = runTest {
        val backend = FakeBackend()
        val notified = mutableListOf<UiNotice.AccessRequest>()
        val store = ControllerStore(backend, backgroundScope, AccessRequestNotifier { notified += it }, fallbackPollMs = 60_000)
        store.start()
        runCurrent()
        val reads = backend.viewReads

        backend.noticeFlow.emit(UiNotice.Changed)
        runCurrent()
        assertEquals(reads + 1, backend.viewReads)

        val req = UiNotice.AccessRequest("a1", "d1", null, "tcp", 22, null)
        backend.noticeFlow.emit(req)
        runCurrent()
        assertEquals(listOf(req), notified)
        assertEquals(reads + 1, backend.viewReads, "an access request alone does not re-read the view")
    }

    @Test
    fun backendFailureIsReportedAndCleared() = runTest {
        val backend = FakeBackend()
        backend.views += { throw ApiException("无法连接到 WebUI 服务，请检查网络。", "connection", "webui_unreachable") }
        val store = ControllerStore(backend, this)
        store.refresh()
        assertEquals("无法连接到 WebUI 服务，请检查网络。", store.backendError.value)
        store.refresh()
        assertNull(store.backendError.value)
    }

    @Test
    fun wizardValidatesEachStepAndBuildsTheRequest() {
        val w = RuleWizardState()
        assertFalse(w.next())
        assertEquals("请选择一个权限组", w.error)

        w.sourceMode = SourceMode.Manual
        w.sourceInput = "pc.alice"
        w.addSource()
        w.sourceInput = "pc.alice"
        w.addSource() // duplicates are ignored
        assertEquals(1, w.sources.size)
        assertTrue(w.next())

        w.targetMode = TargetMode.Ports
        w.portInput = "99999"
        w.addPort()
        assertTrue(w.error!!.startsWith("端口格式无效"))
        w.portInput = "25565"
        w.addPort()
        assertTrue(w.next())
        assertEquals(3, w.step)

        w.action = "deny"
        val r = w.request()
        assertEquals("deny", r["action"]!!.jsonPrimitive.content)
        val principal = (r["principals"] as JsonArray).single() as JsonObject
        assertEquals("device", principal["kind"]!!.jsonPrimitive.content)
        assertEquals("pc.alice", principal["id"]!!.jsonPrimitive.content)
        assertNull(r["services"])
        assertNull(r["comment"], "empty comment is not sent")
    }

    @Test
    fun wizardForARoomMemberOnlyAsksForTargetsAndComment() {
        val w = RuleWizardState(preset = PresetDevice("device-0123", "Bob 的台式机"))
        assertEquals(2, w.step)
        w.back()
        assertEquals(2, w.step, "the source step cannot be reopened")

        assertFalse(w.next())
        assertEquals("请选择至少一个服务类型", w.error)
        w.toggleService("minecraft-java", true)
        assertTrue(w.next())
        assertEquals(3, w.step)
        w.back()
        assertEquals(2, w.step)
        assertTrue(w.next())

        w.comment = "  Bob 的 MC  "
        val r = w.request()
        val principal = (r["principals"] as JsonArray).single() as JsonObject
        assertEquals("device", principal["kind"]!!.jsonPrimitive.content)
        assertEquals("device-0123", principal["id"]!!.jsonPrimitive.content, "Core's device ID, unchanged")
        assertEquals("minecraft-java", (r["services"] as JsonArray).single().jsonPrimitive.content)
        assertEquals("allow", r["action"]!!.jsonPrimitive.content)
        assertEquals("Bob 的 MC", r["comment"]!!.jsonPrimitive.content)
        assertNull(r["group_id"])
    }

    @Test
    fun roomLevelRuleMatchesAllRoomMembers() {
        val w = RuleWizardState(preset = RoomMembers)
        assertEquals(2, w.step)
        w.back()
        assertEquals(2, w.step, "the source step cannot be reopened")
        w.targetMode = TargetMode.Ports
        w.portProto = "tcp"
        w.portInput = "25565"
        w.addPort()
        assertTrue(w.next())

        val r = w.request()
        val principal = (r["principals"] as JsonArray).single() as JsonObject
        assertEquals("space", principal["kind"]!!.jsonPrimitive.content)
        assertEquals("*", principal["id"]!!.jsonPrimitive.content)
        assertNull(r["group_id"])
    }

    @Test
    fun wizardCopiesAnExistingRule() {
        val rule = FirewallRule(
            id = "r1",
            permissionGroup = PermissionGroup("users", listOf("u1")),
            protocolPorts = listOf(ProtocolPort("udp", 1, 65535)),
            action = "allow",
            comment = "friends",
        )
        val w = RuleWizardState(rule)
        assertEquals(SourceMode.Manual, w.sourceMode)
        assertEquals("user", w.sourceKind)
        assertEquals("*", w.ports.single().port)
        assertEquals("friends", w.comment)
        w.changeSourceKind("device")
        assertTrue(w.sources.isEmpty(), "sources of one kind only")
    }

    @Test
    fun oidcSucceedsOnlyOnceCoreConfirms() = runTest {
        val backend = FakeBackend()
        var polls = 0
        backend.respond = { req ->
            when (req["type"]!!.jsonPrimitive.content) {
                "auth_start" -> obj("session_id" to "s", "user_code" to "ABCD", "verification_uri" to "https://x", "interval" to 1)
                "auth_poll" -> if (++polls < 2) obj("status" to "authorization_pending") else obj("status" to "ok")
                else -> obj()
            }
        }
        // Core confirms on the second view read after the poll finished.
        backend.views += { health(false) }
        backend.views += { health(true) }
        val store = ControllerStore(backend, this)
        val opened = mutableListOf<String>()
        val flow = OidcFlow(backend, store, this, { opened += it })
        flow.start()
        runCurrent()
        assertEquals(OidcFlow.Status.Pending, flow.status)
        assertEquals(listOf("https://x"), opened)
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(OidcFlow.Status.Verifying, flow.status)
        advanceUntilIdle()
        assertEquals(OidcFlow.Status.Authorized, flow.status)
    }

    @Test
    fun oidcTimesOutWithoutConfirmation() = runTest {
        val backend = FakeBackend()
        backend.respond = { req ->
            if (req["type"]!!.jsonPrimitive.content == "auth_start") obj("session_id" to "s", "interval" to 1) else obj("status" to "ok")
        }
        val flow = OidcFlow(backend, ControllerStore(backend, this), this, {}, verifyTimeoutMs = 3_000)
        flow.start()
        advanceUntilIdle()
        assertEquals(OidcFlow.Status.Error, flow.status)
    }

    @Test
    fun joinAsksForPasswordWhenCoreRequiresOne() = runTest {
        val backend = FakeBackend()
        backend.respond = { req ->
            when (joinPassword(req)) {
                null -> throw ApiException("该房间设有密码，请输入房间密码。", "core", "room_password_required")
                "secret" -> obj()
                else -> throw ApiException("无法加入房间，请检查房间号和密码。", "core", "room_rejected")
            }
        }
        val s = RoomJoinState()
        s.roomId = " room-1 "
        s.submit(backend)
        // No error: the password prompt opens instead.
        assertTrue(s.askPassword)
        assertNull(s.error)
        assertEquals(Phase.Idle, s.phase)
        assertFalse(s.canSubmit, "an empty password is not sent")

        s.password = "wrong"
        s.submit(backend)
        assertTrue(s.askPassword, "a wrong password keeps the prompt open")
        assertEquals("无法加入房间，请检查房间号和密码。", s.error)

        s.password = "secret"
        s.submit(backend)
        assertEquals(Phase.Success, s.phase)
        assertEquals(listOf(null, "wrong", "secret"), backend.sent.map(::joinPassword))
        assertEquals("room-1", backend.sent.last()["params"]!!.jsonObject["room_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun joinShowsAlreadyInRoomAndPasswordPromptCanBeCancelled() = runTest {
        val backend = FakeBackend()
        backend.respond = { throw ApiException("你已在一个房间中，请先退出当前房间。", "core", "already_in_room") }
        val s = RoomJoinState()
        s.roomId = "room-2"
        s.submit(backend)
        assertFalse(s.askPassword)
        assertEquals(Phase.Error, s.phase)
        assertEquals("你已在一个房间中，请先退出当前房间。", s.error)

        backend.respond = { throw ApiException("该房间设有密码，请输入房间密码。", "core", "room_password_required") }
        s.submit(backend)
        assertTrue(s.askPassword)
        s.password = "x"
        s.cancelPassword()
        assertFalse(s.askPassword)
        assertEquals("", s.password)
        assertNull(s.error)
    }

    @Test
    fun createRoomRelayChoices() {
        val s = RoomCreateState()
        s.name = " game "
        assertNull(joinParams(s.request())["relay"]?.jsonPrimitive?.contentOrNull, "不指定: no relay")

        s.relay = RoomRelayChoice.Official("198.51.100.1:7000")
        assertEquals("198.51.100.1:7000", joinParams(s.request())["relay"]!!.jsonPrimitive.content)

        s.relay = RoomRelayChoice.SelfHosted
        assertFalse(s.canSubmit, "a self-hosted relay needs its link")
        s.relayLink = " msst-relay://r.example:7000#key "
        assertFalse(s.showRelayPassword)
        assertTrue(s.canSubmit)
        s.relayLink = "msst-relay://r.example:7000#key?pw=1"
        assertTrue(s.showRelayPassword)
        assertFalse(s.canSubmit, "the link says the relay has a password")
        s.relayPassword = "relay-pw"
        val p = joinParams(s.request())
        assertEquals("msst-relay://r.example:7000#key?pw=1", p["relay"]!!.jsonPrimitive.content)
        assertEquals("relay-pw", p["relay_password"]!!.jsonPrimitive.content)
        assertEquals("game", p["room_name"]!!.jsonPrimitive.content)

        // Switching away drops the relay password.
        s.relay = RoomRelayChoice.Unspecified
        assertNull(joinParams(s.request())["relay_password"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun createRoomFollowsCoreRelayErrors() = runTest {
        val backend = FakeBackend()
        val s = RoomCreateState()
        s.name = "game"
        s.relay = RoomRelayChoice.SelfHosted
        s.relayLink = "msst-relay://r.example:7000#key"
        backend.respond = { throw ApiException("该中转服务器设有接入密码，请输入中转服务器接入密码。", "core", "relay_password_required") }
        s.submit(backend)
        assertTrue(s.showRelayPassword, "Core asked for the relay password")
        assertEquals(Phase.Error, s.phase)

        s.relayPassword = "pw"
        backend.respond = { throw ApiException("…", "core", "room_password_required") }
        s.submit(backend)
        assertTrue(s.hasPassword, "the relay requires a room password")
        assertEquals("所选中转服务器要求房间设置密码，请为房间设置密码。", s.error)

        s.password = "room-pw"
        backend.respond = { obj() }
        s.submit(backend)
        assertEquals(Phase.Success, s.phase)
        assertEquals("room-pw", joinParams(backend.sent.last())["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun relayLinkPasswordHint() {
        assertTrue(relayLinkHasPassword("msst-relay://h:1#k?pw=1"))
        assertTrue(relayLinkHasPassword(" MSST-RELAY://h:1#k?x=2&pw=1 "))
        assertFalse(relayLinkHasPassword("msst-relay://h:1#k"))
        assertFalse(relayLinkHasPassword("msst-relay://h:1#k?pw=0"))
        assertFalse(relayLinkHasPassword("198.51.100.1:7000"))
    }
}
