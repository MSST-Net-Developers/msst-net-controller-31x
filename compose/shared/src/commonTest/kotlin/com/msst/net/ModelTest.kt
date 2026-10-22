package com.msst.net

import com.msst.net.model.ConnectionStatus
import com.msst.net.model.ControllerJson
import com.msst.net.model.ControllerView
import com.msst.net.model.DiagPingResult
import com.msst.net.model.Health
import com.msst.net.model.NetSettings
import com.msst.net.model.PathView
import com.msst.net.model.RelayList
import com.msst.net.model.RelayRegistration
import com.msst.net.model.RoomRelay
import com.msst.net.model.RoomState
import com.msst.net.model.AuthDecision
import com.msst.net.model.Req
import com.msst.net.model.UiNotice
import com.msst.net.model.quickDeviceRule
import com.msst.net.model.RoomPeer
import com.msst.net.state.discoveryNote
import com.msst.net.state.discoveryStatsLine
import com.msst.net.ui.screens.diagPathLabel
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
    fun decodesNetworkWarningsAndTheBypassSetting() {
        val room = ControllerJson.decodeFromString(RoomState.serializer(), """
            {"room_id":null,"peers":[],"network_warnings":[
              {"kind":"route_conflict","range":"172.22.0.0/16","interface":"br-1a2b","route":"172.22.0.0/16 dev br-1a2b table main"},
              {"kind":"route_hijacked","range":"fd22:2222:2222:2222::/64","interface":"tailscale0","route":null}]}
        """.trimIndent())
        assertEquals(listOf("br-1a2b", "tailscale0"), room.networkWarnings.map { it.`interface` })
        assertNull(room.networkWarnings[1].route)
        // Older Rust libraries / Cores: none.
        assertTrue(ControllerJson.decodeFromString(RoomState.serializer(), """{"peers":[]}""").networkWarnings.isEmpty())

        assertEquals(false, ControllerJson.decodeFromString(NetSettings.serializer(), """{"bypass_other_vpns":false}""").bypassOtherVpns)
        assertNull(ControllerJson.decodeFromString(NetSettings.serializer(), """{"disable_p2p":true}""").bypassOtherVpns, "older Core: no switch")
        assertEquals(true, ControllerJson.decodeFromString(NetSettings.serializer(), """{"block_other_vpns_over_msst":true}""").blockOtherVpnsOverMsst)
        assertNull(ControllerJson.decodeFromString(NetSettings.serializer(), """{"bypass_other_vpns":true}""").blockOtherVpnsOverMsst)
    }

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

    @Test
    fun decodesProtocol04Diagnostics() {
        // Shapes from Core's diagnostics.rs (GetPathView, GetRelays).
        val path = ControllerJson.decodeFromString<PathView>("""
            {"peers":[{"device_id":"d1","display_name":null,"chosen":"direct:203.0.113.7:21031",
              "candidates":[{"addr":"203.0.113.7:21031","rtt_ms":12.5,"loss_rate":0.0,"status":"direct"}],
              "encryption":"encrypted","last_handshake_at":1900000000},
             {"device_id":"d2","chosen":"relay","candidates":[],"encryption":"no_key",
              "probe_failures":2,"next_probe_at":1900000060}]}""")
        assertEquals("encrypted", path.peers[0].encryption)
        assertEquals(1_900_000_000L, path.peers[0].lastHandshakeAt)
        assertEquals(0, path.peers[0].probeFailures)
        assertEquals(2, path.peers[1].probeFailures)
        assertEquals(1_900_000_060L, path.peers[1].nextProbeAt)
        val relays = ControllerJson.decodeFromString<RelayList>("""
            {"relays":[{"addr":"198.51.100.1:7000","pub_key":"k"}],"current":"198.51.100.1:7000","rtt_ms":{},
             "discovery":{"transport":"wireguard","tx_messages":6,"tx_skipped_unsupported":2,"rx_accepted":3,
               "drop":{"not_member":0,"not_discovery":0,"rate_limited":4}}}""")
        val d = relays.discovery!!
        assertEquals("wireguard", d.transport)
        assertEquals(3, d.rxAccepted)
        assertEquals("已发送 6  已接收 3  未发送（成员版本过旧）2  丢弃（超出速率限制）4", discoveryStatsLine(d))
        // A 3.1.5 Core's relay-based secure discovery is no longer shown.
        assertNull(ControllerJson.decodeFromString<RelayList>("""{"secure_discovery":{"active":true}}""").discovery)
    }

    @Test
    fun decodesDiagPingPath() {
        // Core diagnostics.rs `PingJob`; older Cores send no `path`.
        val r = ControllerJson.decodeFromString<DiagPingResult>(
            """{"target_device_id":"d1","results":[160.3,null],"done":true,"path":"relay"}""")
        assertEquals(listOf(160.3, null), r.results)
        assertEquals("经中转（WireGuard 隧道内）", diagPathLabel(r.path))
        assertEquals("P2P 直连", diagPathLabel("direct"))
        val old = ControllerJson.decodeFromString<DiagPingResult>("""{"target_device_id":"d1","results":[],"done":false}""")
        assertNull(old.path)
        assertNull(diagPathLabel(old.path))
    }

    @Test
    fun decodesRoomRelayAndGuestCredential() {
        // Shapes from Core's rooms.rs (room view), diagnostics.rs (GetRelays)
        // and the Health vector.
        val room = ControllerJson.decodeFromString<RoomState>("""
            {"room_id":"space-1","room_name":"g","relay":null,"peers":[],
             "room_relay":{"address":"relay.example.com:7000","self_hosted":true,"state":"down"}}""")
        assertEquals(RoomRelay("relay.example.com:7000", true, "down"), room.roomRelay)
        assertNull(ControllerJson.decodeFromString<RoomState>("""{"room_id":null,"room_relay":null}""").roomRelay)
        assertEquals("guest", ControllerJson.decodeFromString<Health>("""{"status":"ok","coordinator_credential":"guest"}""").coordinatorCredential)

        val relays = ControllerJson.decodeFromString<RelayList>("""
            {"relays":[{"addr":"198.51.100.1:7000","pub_key":"k","features":["sdisc1","sreg1"]}],
             "registration":{"mode":"sreg1","tx_sreg":5,"last_relay_path_ok_at":null},
             "room_relay":{"address":"relay.example.com:7000","self_hosted":true,"state":"ok","addr":"203.0.113.9:7000","tx_sreg":3}}""")
        assertEquals(listOf("sdisc1", "sreg1"), relays.relays[0].features)
        assertEquals(RelayRegistration("sreg1", 5, null), relays.registration)
        assertEquals("203.0.113.9:7000", relays.roomRelay!!.addr)
    }

    @Test
    fun roomMembersShowGameDiscoverySupport() {
        val room = ControllerJson.decodeFromString<RoomState>("""
            {"room_id":"space-1","peers":[
              {"device_id":"device-00000001","discovery":null},
              {"device_id":"device-0000000b","discovery":"ok"},
              {"device_id":"device-0000000c","discovery":"unsupported"},
              {"device_id":"device-0000000d"}]}""")
        assertEquals(listOf(null, "ok", "unsupported", null), room.peers.map { it.discovery })
        assertEquals(listOf(null, null, "该成员版本过旧，无法互相发现游戏", null), room.peers.map { discoveryNote(it) })
        assertNull(discoveryNote(RoomPeer("device-0000000e", discovery = "unknown")))
    }

    @Test
    fun createRoomCarriesTheRelay() {
        val p = Req.createRoom("game", null, "msst-relay://r:7000#k?pw=1", "pw")["params"]!!.jsonObject
        assertEquals("msst-relay://r:7000#k?pw=1", p["relay"]!!.jsonPrimitive.content)
        assertEquals("pw", p["relay_password"]!!.jsonPrimitive.content)
    }
}
