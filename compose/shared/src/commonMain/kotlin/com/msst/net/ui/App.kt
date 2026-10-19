package com.msst.net.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.msst.net.platform.ControllerPlatform
import com.msst.net.platform.ControllerVariant
import com.msst.net.platform.WebSession
import com.msst.net.state.ControllerStore
import com.msst.net.state.attempt
import com.msst.net.state.coreBanner
import com.msst.net.ui.components.BannerBar
import com.msst.net.ui.components.CopyButton
import com.msst.net.ui.components.LinkButton
import com.msst.net.ui.components.Logo
import com.msst.net.ui.components.MIcon
import com.msst.net.ui.dialogs.AccessRequestDialog
import com.msst.net.ui.screens.AboutScreen
import com.msst.net.ui.screens.DefenseScreen
import com.msst.net.ui.screens.DnsScreen
import com.msst.net.ui.screens.HomeScreen
import com.msst.net.ui.screens.LoginScreen
import com.msst.net.ui.screens.SettingsScreen
import com.msst.net.ui.theme.MonoFamily
import com.msst.net.ui.theme.Msst
import com.msst.net.ui.theme.MsstTheme
import com.msst.net.ui.theme.Radius
import kotlinx.coroutines.launch

/** What every screen needs: the platform contract and the shared store. */
class ControllerContext(val platform: ControllerPlatform, val store: ControllerStore) {
    val backend get() = platform.backend
    val services get() = platform.services
    val features get() = platform.features
}

val LocalController = staticCompositionLocalOf<ControllerContext> { error("no ControllerContext") }

/** The window is phone-sized (narrower than 600 dp): tighter spacing,
 *  larger touch targets, stacked toolbars. */
val LocalCompact = staticCompositionLocalOf { false }

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("首页", MdiIcons.HomeOutline),
    Defense("防御", MdiIcons.ShieldOutline),
    Settings("设置", MdiIcons.CogOutline),
    About("关于", MdiIcons.InformationOutline),
    Dns("域名", MdiIcons.Web),
}

/** Root of every Controller variant. */
@Composable
fun ControllerApp(platform: ControllerPlatform, store: ControllerStore) {
    val ctx = remember(platform, store) { ControllerContext(platform, store) }
    MsstTheme {
        CompositionLocalProvider(LocalController provides ctx) {
            Box(Modifier.fillMaxSize().background(Msst.colors.bgSecondary).safeDrawingPadding()) {
                val session = platform.session
                if (session == null) MainShell(onLogout = null) else SessionGate(session)
            }
        }
    }
}

/** WebUI: the password session comes first (enforced by the server). */
@Composable
private fun SessionGate(session: WebSession) {
    var state by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(session) {
        state = attempt { session.check() }.getOrDefault(false)
        session.expired.collect { state = false }
    }
    when (state) {
        null -> Unit
        true -> MainShell(onLogout = {
            scope.launch {
                attempt { session.logout() }
                state = false
            }
        })
        false -> LoginScreen(session) { state = true }
    }
}

@Composable
private fun MainShell(onLogout: (() -> Unit)?) {
    val ctx = LocalController.current
    LaunchedEffect(ctx.store) { ctx.store.start() }
    val view by ctx.store.view.collectAsState()
    val backendError by ctx.store.backendError.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val room = view?.state?.room
    val authPlace = if (ctx.platform.variant == ControllerVariant.Mobile) "首页" else "首页右下角"

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        CompositionLocalProvider(LocalCompact provides compact) {
        Column(Modifier.fillMaxSize()) {
            if (compact) CompactTopBar(if (tab == Tab.Home && room?.inRoom == true) "当前房间" else tab.label, onLogout)
            else TopTabs(tab, { tab = it }, onLogout)

            coreBanner(view, backendError, authPlace)?.let { BannerBar(it) }
            if (room != null && room.inRoom) RoomBanner(room.roomName, room.roomId!!, room.relay)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.Home -> HomeScreen()
                    Tab.Defense -> DefenseScreen()
                    Tab.Settings -> SettingsScreen()
                    Tab.About -> AboutScreen()
                    Tab.Dns -> DnsScreen()
                }
            }

            if (compact) BottomTabs(tab) { tab = it }
        }
        if (ctx.features.interactiveAuth) AccessRequestDialog()
        }
    }
}

@Composable
private fun TopTabs(tab: Tab, onSelect: (Tab) -> Unit, onLogout: (() -> Unit)?) {
    val c = Msst.colors
    Row(
        Modifier.fillMaxWidth().background(c.bgPrimary)
            .drawBehind { drawLine(c.borderLight, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(22.dp)
        Spacer(Modifier.width(8.dp))
        Text("MSST-Net", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = c.textPrimary)
        Spacer(Modifier.width(16.dp))
        Tab.entries.forEach { t ->
            val active = t == tab
            Row(
                Modifier.clickable { onSelect(t) }
                    .drawBehind {
                        if (active) drawRect(c.primary, Offset(8.dp.toPx(), size.height - 2.dp.toPx()), Size(size.width - 16.dp.toPx(), 2.dp.toPx()))
                    }
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MIcon(t.icon, 18.dp, if (active) c.primary else c.textTertiary)
                Spacer(Modifier.width(6.dp))
                Text(
                    t.label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (active) c.primary else c.textTertiary,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (onLogout != null) LinkButton("退出", onLogout, MdiIcons.Logout, color = c.textTertiary)
    }
}

@Composable
private fun CompactTopBar(title: String, onLogout: (() -> Unit)?) {
    val c = Msst.colors
    Row(
        Modifier.fillMaxWidth().background(c.bgPrimary).height(52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(22.dp)
        Spacer(Modifier.width(10.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        if (onLogout != null) LinkButton("退出", onLogout, MdiIcons.Logout, color = c.textTertiary)
    }
}

@Composable
private fun BottomTabs(tab: Tab, onSelect: (Tab) -> Unit) {
    val c = Msst.colors
    NavigationBar(containerColor = c.bgPrimary, tonalElevation = 0.dp) {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = t == tab,
                onClick = { onSelect(t) },
                icon = { MIcon(t.icon, 22.dp) },
                label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = c.primary,
                    selectedTextColor = c.primary,
                    indicatorColor = c.primaryLighter,
                    unselectedIconColor = c.textTertiary,
                    unselectedTextColor = c.textTertiary,
                ),
            )
        }
    }
}

@Composable
private fun RoomBanner(name: String?, roomId: String, relay: String?) {
    val c = Msst.colors
    val services = LocalController.current.services
    Row(
        Modifier.fillMaxWidth().background(c.primaryLighter).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(name ?: "当前房间", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.primaryDark)
        Row(
            Modifier.clip(Radius.full).background(c.bgPrimary).border(1.dp, c.primarySoftBorder, Radius.full).padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(roomId, style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily), color = c.primaryDark)
            CopyButton(roomId, services, "复制房间号", 13.dp)
        }
        if (relay != null) {
            Text(
                "中转 $relay",
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelSmall,
                color = c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
