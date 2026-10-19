package com.msst.net

import com.msst.net.model.ConnectionDiagnostics
import com.msst.net.model.ConnectionStatus
import com.msst.net.model.ControllerView
import com.msst.net.model.Health
import com.msst.net.model.OperationState
import com.msst.net.model.PermissionGroup
import com.msst.net.model.ProtocolPort
import com.msst.net.model.SyncState
import com.msst.net.model.SystemState
import com.msst.net.platform.ControllerFeatures
import com.msst.net.platform.ControllerVariant
import com.msst.net.state.BannerKind
import com.msst.net.state.CoreStatus
import com.msst.net.state.coreBanner
import com.msst.net.state.coreStatus
import com.msst.net.state.formatPorts
import com.msst.net.state.formatPrincipal
import com.msst.net.state.normalizeNamespace
import com.msst.net.state.oneDecimal
import com.msst.net.state.operationStage
import com.msst.net.state.pathLabel
import com.msst.net.state.validHandle
import com.msst.net.state.validPortExpr
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertTrue(validHandle("alice-laptop2"))
        assertFalse(validHandle("Alice"))
        assertEquals("abjust", normalizeNamespace(" ABjust.msst.net "))
        assertNull(normalizeNamespace("-bad"))
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
    }
}
