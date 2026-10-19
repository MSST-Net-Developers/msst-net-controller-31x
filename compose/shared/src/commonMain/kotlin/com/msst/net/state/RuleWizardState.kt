package com.msst.net.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.msst.net.model.FirewallRule
import com.msst.net.model.NodeInfo
import com.msst.net.model.obj
import kotlinx.serialization.json.JsonObject

/** Rule sources: a named group, manually entered principals, or room members. */
enum class SourceMode { Group, Manual, Space }

/** Rule targets: services from Core's catalogue, or protocol/port pairs. */
enum class TargetMode { Services, Ports }

/** A principal or port pair entered in the wizard. */
data class SourceItem(val kind: String, val id: String)
data class PortItem(val proto: String, val port: String)

/**
 * Form state of the three-step rule wizard (3.0.x).  The request uses
 * Core's 3.0.x-compatible `RuleRequest` format; Core validates it and
 * resolves mnemonics to stable IDs (unresolvable ones are rejected), so
 * nothing here resolves identities or evaluates rules.
 */
class RuleWizardState(from: FirewallRule? = null) {
    var step by mutableStateOf(1)
    var sourceMode by mutableStateOf(SourceMode.Group)
    var groupId by mutableStateOf<String?>(null)
    /** `device` or `user`; every manual source has the same kind. */
    var sourceKind by mutableStateOf("device")
        private set
    var sourceInput by mutableStateOf("")
    var sources by mutableStateOf(listOf<SourceItem>())
    var targetMode by mutableStateOf(TargetMode.Services)
    var services by mutableStateOf(setOf<String>())
    var allServices by mutableStateOf(false)
    var portProto by mutableStateOf("tcp+udp")
    var portInput by mutableStateOf("")
    var ports by mutableStateOf(listOf<PortItem>())
    var action by mutableStateOf("allow")
    var comment by mutableStateOf("")
    var error by mutableStateOf<String?>(null)

    val copied = from != null

    init {
        // Prefill from an existing rule ("复制为新规则").
        if (from != null) {
            val pg = from.permissionGroup
            if (pg.kind == "space") {
                sourceMode = SourceMode.Space
            } else {
                sourceMode = SourceMode.Manual
                sourceKind = if (pg.kind == "devices") "device" else "user"
                sources = pg.ids.map { SourceItem(sourceKind, it) }
            }
            targetMode = TargetMode.Ports
            ports = from.protocolPorts.map { PortItem(it.proto, formatPort(it)) }
            action = from.action
            comment = from.comment ?: ""
        }
    }

    /** Changing the kind clears the list (all sources must be the same kind). */
    fun changeSourceKind(kind: String) {
        if (kind != sourceKind) sources = emptyList()
        sourceKind = kind
    }

    fun addSource() {
        val v = sourceInput.trim()
        if (v.isEmpty()) return
        if (sources.none { it.id == v }) sources = sources + SourceItem(sourceKind, v)
        sourceInput = ""
    }

    fun removeSource(id: String) {
        sources = sources.filterNot { it.id == id }
    }

    fun toggleService(id: String, on: Boolean) {
        services = if (on) services + id else services - id
    }

    fun changeAllServices(on: Boolean) {
        allServices = on
        if (on) services = emptySet()
    }

    fun addPort() {
        val p = portInput.trim()
        if (p.isEmpty()) return
        if (!validPortExpr(p)) {
            error = "端口格式无效：$p（可填 *、80 或 80-443，范围 1-65535）"
            return
        }
        error = null
        if (ports.none { it.proto == portProto && it.port == p }) ports = ports + PortItem(portProto, p)
        portInput = ""
    }

    fun removePort(item: PortItem) {
        ports = ports - item
    }

    /** Display name of the entered ID or mnemonic, from Core's node list. */
    fun lookup(nodes: Collection<NodeInfo>): String? {
        val v = sourceInput.trim().ifEmpty { return null }
        val n = nodes.firstOrNull {
            if (sourceKind == "device") it.deviceId == v || it.deviceHandle == v else it.userId == v || it.userHandle == v
        } ?: return null
        return n.displayName ?: if (sourceKind == "device") n.deviceId else n.userId
    }

    /** Validate the current step and advance; false (with [error]) if invalid. */
    fun next(): Boolean {
        error = when (step) {
            1 -> when {
                sourceMode == SourceMode.Group && groupId == null -> "请选择一个权限组"
                sourceMode == SourceMode.Manual && sources.isEmpty() -> "请至少添加一个来源"
                else -> null
            }
            2 -> when {
                targetMode == TargetMode.Services && !allServices && services.isEmpty() -> "请选择至少一个服务类型"
                targetMode == TargetMode.Ports && ports.isEmpty() -> "请添加至少一条协议+端口规则"
                else -> null
            }
            else -> null
        }
        if (error != null) return false
        if (step < 3) step++
        return true
    }

    fun back() {
        error = null
        if (step > 1) step--
    }

    /** The `create_acl_rule` body. */
    fun request(): JsonObject {
        val fields = mutableListOf<Pair<String, Any?>>(
            "action" to action,
            "direction" to "inbound",
            "enabled" to true,
            "expires_at" to null,
        )
        when (sourceMode) {
            SourceMode.Group -> {
                fields += "group_id" to groupId
                fields += "principals" to emptyList<Any>()
            }
            SourceMode.Space -> fields += "principals" to listOf(obj("kind" to "space", "id" to "*"))
            SourceMode.Manual -> fields += "principals" to sources.map { obj("kind" to it.kind, "id" to it.id) }
        }
        when {
            targetMode == TargetMode.Ports -> fields += "protocol_ports" to ports.map { obj("proto" to it.proto, "port" to it.port) }
            allServices -> fields += "protocol_ports" to listOf(obj("proto" to "tcp+udp", "port" to "*"))
            else -> fields += "services" to services.toList()
        }
        comment.trim().takeIf { it.isNotEmpty() }?.let { fields += "comment" to it }
        return obj(*fields.toTypedArray())
    }
}
