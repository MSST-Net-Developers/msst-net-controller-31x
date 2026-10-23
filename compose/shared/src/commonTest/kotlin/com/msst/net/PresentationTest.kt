package com.msst.net

import com.msst.net.model.ConnectionDiagnostics
import com.msst.net.model.ConnectionStatus
import com.msst.net.model.ControllerView
import com.msst.net.model.Health
import com.msst.net.model.NetworkWarning
import com.msst.net.model.OperationState
import com.msst.net.model.PermissionGroup
import com.msst.net.model.ProtocolPort
import com.msst.net.model.RoomRelay
import com.msst.net.model.SyncState
import com.msst.net.model.SystemState
import com.msst.net.platform.ControllerFeatures
import com.msst.net.platform.ControllerVariant
import com.msst.net.platform.DeviceApp
import com.msst.net.state.filterApps
import com.msst.net.state.BannerKind
import com.msst.net.state.CoreStatus
import com.msst.net.state.coreBanner
import com.msst.net.state.coreReady
import com.msst.net.state.coversAllPorts
import com.msst.net.state.coreStatus
import com.msst.net.state.coreStatusLabel
import com.msst.net.state.displayNameProblem
import com.msst.net.state.encryptionLabel
import com.msst.net.state.coordinatorActionsAvailable
import com.msst.net.state.coordinatorUnreachable
import com.msst.net.state.formatPorts
import com.msst.net.state.formatPrincipal
import com.msst.net.state.RESERVED_NAMESPACES
import com.msst.net.state.normalizeNamespace
import com.msst.net.state.oneDecimal
import com.msst.net.state.operationStage
import com.msst.net.state.pathLabel
import com.msst.net.state.networkWarningLabel
import com.msst.net.state.roomRelayLabel
import com.msst.net.state.validHandle
import com.msst.net.state.validPortExpr
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresentationTest {
    private fun view(
        connection: ConnectionStatus = ConnectionStatus.Connected,
        health: Health? = Health(status = "ok"),
        sync: String = "synced",
        successfulConnects: Long = 1,
    ) = ControllerView(
        connection = connection,
        diagnostics = ConnectionDiagnostics(successfulConnects = successfulConnects, lastError = "refused"),
        state = SystemState(sync = SyncState(sync), health = health),
    )

    @Test
    fun statusFollowsCoreOnly() {
        assertEquals(CoreStatus.Checking, coreStatus(null, null))
        assertEquals(CoreStatus.Error, coreStatus(null, "unreachable"))
        assertEquals(CoreStatus.Ok, coreStatus(view(), null))
        assertEquals(CoreStatus.Warning, coreStatus(view(health = Health(status = "degraded")), null))
        // Connected but Health not read yet: unknown, not "ok".
        assertEquals(CoreStatus.Checking, coreStatus(view(health = null, sync = "syncing"), null))
        assertEquals(CoreStatus.Warning, coreStatus(view(health = null, sync = "failed"), null))
        assertEquals(CoreStatus.Error, coreStatus(view(ConnectionStatus.Reconnecting(1, 500)), null))
        assertEquals(CoreStatus.Error, coreStatus(view(ConnectionStatus.Incompatible), null))
    }

    @Test
    fun coordinatorDownIsAWarningAndBlocksCoordinatorActions() {
        val down = view(health = Health(status = "ok", wsConnected = false))
        assertEquals(CoreStatus.Warning, coreStatus(down, null))
        assertEquals("客户端核心无法连接协调服务器", coreStatusLabel(down, null))
        assertTrue(coordinatorUnreachable(down))
        assertTrue(coreReady(down), "Core itself is still usable")
        assertFalse(coordinatorActionsAvailable(down))
        val authNeeded = down.copy(state = down.state.copy(accessAuthRequired = true))
        assertNull(coreBanner(authNeeded, null, "首页"), "no prompt to authenticate while that is impossible")

        val up = view(health = Health(status = "ok", wsConnected = true))
        assertEquals(CoreStatus.Ok, coreStatus(up, null))
        assertTrue(coordinatorActionsAvailable(up))

        // Older Cores do not report it: unknown is not "down".
        val unknown = view(health = Health(status = "ok", wsConnected = null))
        assertEquals(CoreStatus.Ok, coreStatus(unknown, null))
        assertTrue(coordinatorActionsAvailable(unknown))

        // Not connected to Core at all: nothing is available, but that is
        // the Core error, not a coordinator warning.
        val lost = view(ConnectionStatus.Reconnecting(1, 500), health = Health(status = "ok", wsConnected = false))
        assertFalse(coordinatorUnreachable(lost))
        assertFalse(coordinatorActionsAvailable(lost))
        assertEquals(CoreStatus.Error, coreStatus(lost, null))
    }

    @Test
    fun bannerExplainsStaleOrReadOnlyData() {
        assertNull(coreBanner(view(), null, "首页"))
        assertNull(coreBanner(view(ConnectionStatus.Connecting, successfulConnects = 0), null, "首页"))
        val lost = coreBanner(view(ConnectionStatus.Disconnected), null, "首页")!!
        assertEquals(BannerKind.Warn, lost.kind)
        assertEquals("refused", lost.detail)
        assertEquals(BannerKind.Error, coreBanner(view(ConnectionStatus.Reconnecting(2, 1000)), null, "首页")!!.kind)
        assertEquals(BannerKind.Info, coreBanner(view(sync = "syncing"), null, "首页")!!.kind)
        val viewer = coreBanner(view(health = Health(status = "ok", ipcRole = "viewer")), null, "首页")!!
        assertTrue(viewer.text.startsWith("只读模式"))
    }

    @Test
    fun operationStageOnlyForRunningOperations() {
        val running = SystemState(operations = mapOf("a" to OperationState("create_room", "room_created")))
        assertEquals("房间已创建，正在加入", operationStage(running, "create_room"))
        assertNull(operationStage(running, "join_room"))
        val finished = SystemState(operations = mapOf("a" to OperationState("create_room", "room_created", JsonObject(emptyMap()))))
        assertNull(operationStage(finished, "create_room"))
    }

    @Test
    fun formatsRulesForPeople() {
        assertEquals("tcp/25565, udp/*, tcp/80-443", formatPorts(listOf(ProtocolPort("tcp", 25565, 25565), ProtocolPort("udp", 1, 65535), ProtocolPort("tcp", 80, 443))))
        assertEquals("任意", formatPorts(emptyList()))
        assertEquals("设备: pc.alice (d1), d2", formatPrincipal(PermissionGroup("devices", listOf("d1", "d2")), mapOf("d1" to "pc.alice")))
        assertEquals("房间模式：当前所有房间成员", formatPrincipal(PermissionGroup("space"), emptyMap()))
        assertEquals("直连", pathLabel("direct_lan"))
        assertNull(pathLabel(null))
        assertEquals("1.5", oneDecimal(1.46))
        assertEquals("12.0", oneDecimal(12.0))
    }

    @Test
    fun validatesInput() {
        assertTrue(validPortExpr("*"))
        assertTrue(validPortExpr("80-443"))
        assertFalse(validPortExpr("0"))
        assertFalse(validPortExpr("443-80"))
        assertFalse(validPortExpr("65536"))
        assertFalse(validPortExpr("80,443"))
        assertTrue(coversAllPorts("*"))
        assertTrue(coversAllPorts(" 1-65535 "))
        assertFalse(coversAllPorts("1-65534"))
        assertFalse(coversAllPorts("80"))
        assertTrue(validHandle("alice-laptop2"))
        assertFalse(validHandle("Alice"))
        assertEquals("abjust", normalizeNamespace(" ABjust.net.msst2031.cn "))
        assertNull(normalizeNamespace("abjust.msst.net"))
        assertNull(normalizeNamespace("-bad"))
        assertNull(normalizeNamespace("a".repeat(64)))
        assertTrue("srv" in RESERVED_NAMESPACES && "ezplay" in RESERVED_NAMESPACES)
    }

    @Test
    fun displayNamesFollowCoreLimits() {
        assertNull(displayNameProblem(""), "empty clears the name")
        assertNull(displayNameProblem("  Alice 的电脑  "))
        assertNull(displayNameProblem("名".repeat(64)))
        assertNotNull(displayNameProblem("名".repeat(65)))
        // Counted in characters, not UTF-16 units.
        assertNull(displayNameProblem("😀".repeat(64)))
        assertNotNull(displayNameProblem("a\tb"))
    }

    @Test
    fun pathLabelsUseProtocolModes() {
        assertEquals("中转", pathLabel("relay"))
        assertEquals("探测直连中", pathLabel("probing"))
        assertEquals("直连", pathLabel("direct:203.0.113.7:21031"))
        assertEquals("已加密", encryptionLabel("encrypted"))
        assertEquals("空闲（有通信时自动建立）", encryptionLabel("unknown"))
        assertEquals("无公钥（无法通信）", encryptionLabel("no_key"))
        assertNull(encryptionLabel(null))
    }

    @Test
    fun appPickerFiltersAndKeepsSelectedAppsVisible() {
        val apps = listOf(
            DeviceApp("com.example.chat", "Chat", system = false, icon = null),
            DeviceApp("com.android.settings", "Settings", system = true, icon = null),
            DeviceApp("com.example.browser", "Browser", system = false, icon = null),
        )
        assertEquals(listOf("Browser", "Chat"), filterApps(apps, "", false, emptySet()).map { it.label })
        // A selected system app stays visible (and first) so it can be unselected.
        assertEquals(listOf("Settings", "Browser", "Chat"), filterApps(apps, "", false, setOf("com.android.settings")).map { it.label })
        assertEquals(listOf("com.example.chat"), filterApps(apps, "CHAT", true, emptySet()).map { it.packageName })
        assertEquals(listOf("Settings"), filterApps(apps, "android", true, emptySet()).map { it.label })
    }

    @Test
    fun variantsDeclareTheirDifferences() {
        val desktop = ControllerFeatures.of(ControllerVariant.Desktop)
        val mobile = ControllerFeatures.of(ControllerVariant.Mobile)
        val web = ControllerFeatures.of(ControllerVariant.Web)
        assertTrue(desktop.manualRuleCreation && desktop.interactiveAuth)
        assertFalse(mobile.manualRuleCreation)
        assertTrue(mobile.interactiveAuth)
        assertTrue(web.manualRuleCreation)
        assertFalse(web.interactiveAuth)
        // Linux-only settings: not for the embedded Android Core.
        assertTrue(desktop.linuxCoreSettings && web.linuxCoreSettings)
        assertFalse(mobile.linuxCoreSettings)
    }

    @Test
    fun networkWarningLabels() {
        assertEquals(
            "br-1a2b 的路由 172.22.0.0/16 dev br-1a2b table main 与 MSST-Net 网段 172.22.0.0/16 重叠，MSST-Net 未接管该网段，可能无法与部分设备通信",
            networkWarningLabel(NetworkWarning("route_conflict", "172.22.0.0/16", "br-1a2b", "172.22.0.0/16 dev br-1a2b table main")),
        )
        assertEquals(
            "发往 MSST-Net 网段 172.22.0.0/16 的流量被路由到了 tailscale0，可能无法与其他设备通信",
            networkWarningLabel(NetworkWarning("route_hijacked", "172.22.0.0/16", "tailscale0")),
        )
        assertEquals("网络配置问题（future）：172.22.0.0/16 / zt0", networkWarningLabel(NetworkWarning("future", "172.22.0.0/16", "zt0")))
    }

    @Test
    fun roomRelayAndGuestLabels() {
        assertNull(roomRelayLabel(null))
        assertEquals("此房间使用自托管中转服务器 r.example:7000（不可用，已回退到各自的中转服务器）", roomRelayLabel(RoomRelay("r.example:7000", true, "down")))
        assertEquals("此房间使用官方中转服务器 198.51.100.1:7000（正常）", roomRelayLabel(RoomRelay("198.51.100.1:7000", false, "ok")))
        val guest = ControllerView(
            connection = ConnectionStatus.Connected,
            state = SystemState(health = Health(status = "ok", wsConnected = true, coordinatorCredential = "guest")),
        )
        assertEquals("客户端核心状态正常（访客身份：仅可使用房间模式）", coreStatusLabel(guest, null))
    }
}
