package com.msst.net.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.msst.net.model.FirewallRule
import com.msst.net.model.NodeInfo
import com.msst.net.model.ServiceItem
import com.msst.net.model.obj
import kotlinx.serialization.json.JsonObject

/** Rule sources: a named group, manually entered principals, or room members. */
enum class SourceMode { Group, Manual, Space }

/** Rule targets: services from Core's catalogue, or protocol/port pairs. */
enum class TargetMode { Services, Ports }

/** A principal or port pair entered in the wizard. */
data class SourceItem(val kind: String, val id: String)
data class PortItem(val proto: String, val port: String)

/** A rule source fixed by the room view's member menu: the wizard skips
 *  the source step. */
sealed interface RuleSourcePreset

/** A device picked in the room view ("添加防火墙规则"): its canonical ID as
 *  reported by Core, and the name shown for it. */
data class PresetDevice(val deviceId: String, val name: String) : RuleSourcePreset

/** "添加房间级规则": Core's `space` source, matching every current room
 *  member (whichever card the menu was opened on). */
data object RoomMembers : RuleSourcePreset

/**
 * Form state of the rule wizard: source, target, action, then a summary to
 * confirm.  The request uses Core's 3.0.x-compatible `RuleRequest` format;
 * Core validates it and resolves mnemonics to stable IDs (unresolvable ones
 * are rejected), so nothing here resolves identities or evaluates rules —
 * [names] only holds what Core reported, for display.
 *
 * [from] prefills a new rule ("复制为新规则"); [editing] prefills the form
 * to update that permanent rule in place (Core 0.5).
 */
class RuleWizardState(
    from: FirewallRule? = null,
    val preset: RuleSourcePreset? = null,
    val editing: FirewallRule? = null,
) {
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
    /** Source ID or mnemonic as entered → name shown for it. */
    var names by mutableStateOf(mapOf<String, String>())

    val copied = from != null && editing == null

    /** With a [preset] the source is fixed: the wizard starts at the targets. */
    val firstStep = if (preset != null) 2 else 1

    init {
        when (preset) {
            is PresetDevice -> {
                sourceMode = SourceMode.Manual
                sources = listOf(SourceItem("device", preset.deviceId))
            }
            RoomMembers -> sourceMode = SourceMode.Space
            null -> {}
        }
        step = firstStep
        // Prefill from an existing rule ("复制为新规则" or editing it).
        (editing ?: from)?.let { r ->
            val pg = r.permissionGroup
            when {
                r.groupId != null -> {
                    sourceMode = SourceMode.Group
                    groupId = r.groupId
                }
                pg.kind == "space" -> sourceMode = SourceMode.Space
                else -> {
                    sourceMode = SourceMode.Manual
                    sourceKind = if (pg.kind == "devices") "device" else "user"
                    sources = pg.ids.map { SourceItem(sourceKind, it) }
                    names = pg.ids.mapNotNull { id -> r.principalMnemonics[id]?.let { id to it } }.toMap()
                }
            }
            targetMode = TargetMode.Ports
            ports = r.protocolPorts.map { PortItem(it.proto, formatPort(it)) }
            action = r.action
            comment = r.comment ?: ""
        }
    }

    /** The confirmation step (the last one). */
    val lastStep = 4

    /** Ports prefilled from a rule, until [useCatalog] has looked at them. */
    private var prefilledPorts = (editing ?: from)?.protocolPorts

    /**
     * Once Core's service catalogue is known: show a prefilled rule's ports
     * as the services they are (rules store expanded ports, not service
     * names).  Ports that aren't exactly some services stay as they are.
     */
    fun useCatalog(catalog: List<ServiceItem>) {
        val pp = prefilledPorts ?: return
        prefilledPorts = null
        if (targetMode != TargetMode.Ports || ports != pp.map { PortItem(it.proto, formatPort(it)) }) return
        when (val m = matchServices(pp, catalog)) {
            ServiceMatch.AllPorts -> {
                targetMode = TargetMode.Services
                changeAllServices(true)
            }
            is ServiceMatch.Services -> {
                targetMode = TargetMode.Services
                services = m.ids.toSet()
            }
            null -> {}
        }
    }

    /** Changing the kind clears the list (all sources must be the same kind). */
    fun changeSourceKind(kind: String) {
        if (kind != sourceKind) sources = emptyList()
        sourceKind = kind
    }

    /** Add the entered source; [name]: what Core resolved it to, if known. */
    fun addSource(name: String? = null) {
        val v = sourceInput.trim()
        if (v.isEmpty()) return
        if (sources.none { it.id == v }) sources = sources + SourceItem(sourceKind, v)
        if (name != null) names = names + (v to name)
        sourceInput = ""
        error = null
    }

    fun removeSource(id: String) {
        sources = sources.filterNot { it.id == id }
    }

    /** "name (id)" for a source, or the ID alone while no name is known. */
    fun sourceLabel(id: String): String = names[id]?.takeIf { it != id }?.let { "$it（$id）" } ?: id

    fun toggleService(id: String, on: Boolean) {
        services = if (on) services + id else services - id
    }

    fun changeAllServices(on: Boolean) {
        allServices = on
        if (on) services = emptySet()
    }

    /** The entered port matches every port of [portProto]: adding it needs
     *  the same risk confirmation as "所有协议及端口". */
    val portInputCoversAll: Boolean get() = coversAllPorts(portInput)

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
        if (step < lastStep) step++
        return true
    }

    fun back() {
        error = null
        if (step > firstStep) step--
    }

    /** The `create_acl_rule` body. */
    fun request(): JsonObject {
        val fields = mutableListOf<Pair<String, Any?>>(
            "direction" to "inbound",
            "enabled" to true,
            "expires_at" to null,
        )
        fields += common()
        if (sourceMode == SourceMode.Group) fields += "principals" to emptyList<Any>()
        comment.trim().takeIf { it.isNotEmpty() }?.let { fields += "comment" to it }
        return obj(*fields.toTypedArray())
    }

    /** The `update_acl_rule` patch for [editing]: source, target, action and
     *  comment are replaced as a whole; `enabled` stays as it is. */
    fun patch(): JsonObject = obj(*(common() + ("comment" to comment.trim())).toTypedArray())

    private fun common(): List<Pair<String, Any?>> {
        val fields = mutableListOf<Pair<String, Any?>>("action" to action)
        when (sourceMode) {
            SourceMode.Group -> fields += "group_id" to groupId
            SourceMode.Space -> fields += "principals" to listOf(obj("kind" to "space", "id" to "*"))
            SourceMode.Manual -> fields += "principals" to sources.map { obj("kind" to it.kind, "id" to it.id) }
        }
        when {
            targetMode == TargetMode.Ports -> fields += "protocol_ports" to ports.map { obj("proto" to it.proto, "port" to it.port) }
            allServices -> fields += "protocol_ports" to listOf(obj("proto" to "tcp+udp", "port" to "*"))
            else -> fields += "services" to services.toList()
        }
        return fields
    }
}
