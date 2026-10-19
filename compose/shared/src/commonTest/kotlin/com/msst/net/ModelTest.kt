package com.msst.net

import com.msst.net.model.ConnectionStatus
import com.msst.net.model.ControllerJson
import com.msst.net.model.ControllerView
import com.msst.net.model.AuthDecision
import com.msst.net.model.Req
import com.msst.net.model.UiNotice
import com.msst.net.model.quickDeviceRule
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Decoding of the Rust library's JSON and encoding of `IpcRequest`s. */
class ModelTest {
    /** `Controller::view_json()` output against the FakeCore (protocol 0.4). */
    private val viewJson = """
        {"capabilities":{"clear_acl_rules":true,"event_seq":true,"full_events":true,"get_services":true,"log_stream":true,"operations":true},
         "connection":"Connected","controller_protocol":"0.4.0","core_protocol":"0.4.0",
         "diagnostics":{"disconnections":0,"last_connection_duration_ms":null,"last_error":null,"successful_connects":1,"total_connect_attempts":1,"version_mismatches":0},
         "state":{"access_auth_required":false,"acl_groups":[],
           "acl_rules":[{"id":"r1","permission_group":{"kind":"devices","ids":["d1"]},"protocol_ports":[{"proto":"tcp","start":25565,"end":25565}],
                         "action":"allow","direction":"inbound","expires_at":null,"enabled":true,"comment":null,"principal_mnemonics":{"d1":"pc.alice"},"future_field":1}],
           "health":{"access_auth_required":null,"auth_expires_at":null,"coordinator_state":null,"device_display_name":null,"device_handle":null,
                     "device_hostname":null,"device_id":null,"generation":null,"ipc_role":"admin","is_authenticated":false,"peer_count":null,
                     "ping_enabled":true,"relay":null,"rule_count":null,"status":"ok","tun_ready":null,"user_display_name":null,"user_handle":null,
                     "user_id":null,"ws_connected":true},
           "nodes":{},
           "operations":{"op-1":{"request_type":"create_room","stage":"relay_selected","result":null}},
           "pending_auth":[],
           "room":{"peers":[],"relay":null,"room_id":null,"room_name":null},
           "sync":{"state":"synced"}}}
    """.trimIndent()

    @Test
    fun decodesTheControllerView() {
        val v = ControllerJson.decodeFromString(ControllerView.serializer(), viewJson)
        assertEquals(ConnectionStatus.Connected, v.connection)
        assertTrue(v.connected)
        assertEquals("0.4.0", v.coreProtocol)
        assertTrue(v.capabilities.operations)
        assertTrue(v.state.sync.synced)
        assertEquals(false, v.state.health?.isViewer)
        assertEquals(true, v.state.health?.pingEnabled)
        val rule = v.state.aclRules.single()
        assertEquals("devices", rule.permissionGroup.kind)
        assertEquals("pc.alice", rule.principalMnemonics["d1"])
        assertEquals("relay_selected", v.state.operations["op-1"]!!.stage)
    }

    @Test
    fun decodesEveryConnectionStatus() {
        fun status(json: String) = ControllerJson.decodeFromString(ControllerView.serializer(), """{"connection":$json}""").connection
        assertEquals(ConnectionStatus.Disconnected, status("\"Disconnected\""))
        assertEquals(ConnectionStatus.Incompatible, status("\"Incompatible\""))
        assertEquals(ConnectionStatus.Reconnecting(3, 4000), status("""{"Reconnecting":{"attempt":3,"next_retry_ms":4000}}"""))
        assertIs<ConnectionStatus.Unknown>(status("\"SomethingNew\""))
    }

    @Test
    fun parsesNotices() {
        assertEquals(UiNotice.Changed, UiNotice.parse("""{"type":"changed"}"""))
        val n = UiNotice.parse(
            """{"type":"access_request","request_id":"a1","src_device_id":"d1","src_device_display_name":"Bob","proto":"tcp","dst_port":25565,"service_name":null}""",
        )
        assertEquals(UiNotice.AccessRequest("a1", "d1", "Bob", "tcp", 25565, null), n)
        assertNull(UiNotice.parse("""{"type":"future"}"""))
        assertNull(UiNotice.parse("not json"))
    }

    @Test
    fun buildsRequestsInTheProtocolShape() {
        val r = Req.createRoom("game", null)
        assertEquals("create_room", r["type"]!!.jsonPrimitive.content)
        val params = r["params"]!!.jsonObject
        assertEquals("game", params["room_name"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, params["password"]) // explicit null, as serde expects
        assertEquals(null, Req.health()["params"])
        assertEquals("always_deny", Req.authDecision("r", AuthDecision.AlwaysDeny)["params"]!!.jsonObject["decision"]!!.jsonPrimitive.content)
    }

    @Test
    fun quickRuleTargetsOneDeviceAndPort() {
        val rule = quickDeviceRule("d1", "Bob", "udp", 19132, "deny", null)
        val principal = (rule["principals"] as kotlinx.serialization.json.JsonArray).single() as JsonObject
        assertEquals("device", principal["kind"]!!.jsonPrimitive.content)
        assertEquals("d1", principal["id"]!!.jsonPrimitive.content)
        val port = (rule["protocol_ports"] as kotlinx.serialization.json.JsonArray).single() as JsonObject
        assertEquals("19132", port["port"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, rule["expires_at"])
    }
}
