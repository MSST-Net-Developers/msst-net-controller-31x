package com.msst.net.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.msst.net.platform.ControllerPlatform
import com.msst.net.platform.ControllerVariant
import com.msst.net.platform.WebSession
import com.msst.net.state.ControllerStore
import com.msst.net.state.attempt
import com.msst.net.model.UpdateInfo
import com.msst.net.state.updateDownloadLabel
import com.msst.net.state.updateHowto
import com.msst.net.state.coreBanner
import com.msst.net.state.updateKey
import com.msst.net.state.updateSummary
import com.msst.net.ui.components.BannerBar
import com.msst.net.ui.components.CopyButton
import com.msst.net.ui.components.LinkButton
import com.msst.net.ui.components.Logo
import com.msst.net.ui.components.MIcon
import com.msst.net.ui.components.SmallIconButton
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
import com.msst.net.ui.theme.LocalThemeSetting
import com.msst.net.ui.theme.ThemeMode
import com.msst.net.ui.theme.ThemeSetting
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

private enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Home("首页", MdiIcons.HomeOutline, MdiIcons.Home),
    Defense("防御", MdiIcons.ShieldOutline, MdiIcons.Shield),
    Settings("设置", MdiIcons.CogOutline, MdiIcons.Cog),
    About("关于", MdiIcons.InformationOutline, MdiIcons.Information),
    Dns("域名", MdiIcons.Web, MdiIcons.Web),
}

private const val THEME_KEY = "theme"

/**
 * Root of every Controller variant; [chrome] when the window has no system
 * title bar.  [onDarkTheme] reports the effective theme (Android: system bar
 * icon colours).
 */
@Composable
fun ControllerApp(
    platform: ControllerPlatform,
    store: ControllerStore,
    chrome: WindowChrome? = null,
    onDarkTheme: (Boolean) -> Unit = {},
) {
    val ctx = remember(platform, store) { ControllerContext(platform, store) }
    val prefs = platform.preferences
    var mode by remember(prefs) { mutableStateOf(ThemeMode.parse(prefs?.get(THEME_KEY))) }
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    LaunchedEffect(dark) { onDarkTheme(dark) }
    val themeSetting = remember(mode, prefs) {
        ThemeSetting(mode) {
            mode = it
            prefs?.set(THEME_KEY, it.key)
        }
    }
    MsstTheme(dark) {
        CompositionLocalProvider(LocalController provides ctx, LocalWindowChrome provides chrome, LocalThemeSetting provides themeSetting) {
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
    // Until the Controller restarts, or a newer release appears.
    var dismissedUpdate by rememberSaveable { mutableStateOf<String?>(null) }
    val room = view?.state?.room
    val authPlace = if (ctx.platform.variant == ControllerVariant.Mobile) "首页" else "首页右下角"

    val title = if (tab == Tab.Home && room?.inRoom == true) "当前房间" else tab.label

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        CompositionLocalProvider(LocalCompact provides compact) {
            val body = @Composable { modifier: Modifier ->
                Column(modifier) {
                    Banners(
                        coreBanner = coreBanner(view, backendError, authPlace),
                        update = view?.update?.takeIf { it.available && updateKey(it) != dismissedUpdate },
                        onDismissUpdate = { dismissedUpdate = updateKey(it) },
                        room = room?.takeIf { it.inRoom },
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (tab) {
                            Tab.Home -> HomeScreen()
                            Tab.Defense -> DefenseScreen()
                            Tab.Settings -> SettingsScreen()
                            Tab.About -> AboutScreen()
                            Tab.Dns -> DnsScreen()
                        }
                    }
                }
            }
            if (compact) {
                // Phones: top app bar, content, bottom navigation bar.
                Column(Modifier.fillMaxSize()) {
                    TopAppBar(title, onLogout, showLogo = true)
                    body(Modifier.weight(1f).fillMaxWidth())
                    BottomTabs(tab) { tab = it }
                }
            } else {
                // Desktop and browser windows: navigation rail beside the content.
                Row(Modifier.fillMaxSize()) {
                    SideRail(tab) { tab = it }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        TopAppBar(title, onLogout, showLogo = false)
                        body(Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
            if (ctx.features.interactiveAuth) AccessRequestDialog()
        }
    }
}

/** Core status, update and room banners between the app bar and the page. */
@Composable
private fun Banners(
    coreBanner: com.msst.net.state.Banner?,
    update: UpdateInfo?,
    onDismissUpdate: (UpdateInfo) -> Unit,
    room: com.msst.net.model.RoomState?,
) {
    val ctx = LocalController.current
    if (coreBanner == null && update == null && room == null) return
    val compact = LocalCompact.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 20.dp).padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        coreBanner?.let { BannerBar(it) }
        update?.let { u -> UpdateBanner(u, ctx.services::openUrl) { onDismissUpdate(u) } }
        if (room != null) RoomBanner(room.roomName, room.roomId!!, room.relay)
    }
}

/**
 * The top bar moves the window when it stands in for the system title bar.
 * [modifier] sizes the drag area: the rail header must not use
 * `fillMaxWidth`, or the rail takes the whole window width in its Row.
 */
@Composable
private fun TitleBar(modifier: Modifier = Modifier.fillMaxWidth(), content: @Composable () -> Unit) {
    val chrome = LocalWindowChrome.current
    if (chrome == null) content() else chrome.DragArea(modifier, content)
}

/** Material top app bar: page title, sign-out (WebUI), window controls (desktop). */
@Composable
private fun TopAppBar(title: String, onLogout: (() -> Unit)?, showLogo: Boolean) = TitleBar {
    val c = Msst.colors
    val chrome = LocalWindowChrome.current
    val compact = LocalCompact.current
    Row(
        Modifier.fillMaxWidth().height(if (compact) 64.dp else 56.dp).background(c.bgSecondary)
            .padding(start = if (compact) 16.dp else 20.dp, end = if (chrome != null) 0.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLogo) {
            Logo(28.dp)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onLogout != null) {
            if (compact) SmallIconButton(MdiIcons.Logout, "退出登录", onLogout, tint = c.textSecondary, size = 22.dp)
            else LinkButton("退出登录", onLogout, MdiIcons.Logout, color = c.textSecondary)
        }
        if (chrome != null) {
            Spacer(Modifier.width(4.dp))
            Box(Modifier.align(Alignment.Top).height(40.dp)) { WindowControls(chrome) }
        }
    }
}

/** Material navigation rail (windows at least 600 dp wide). */
@Composable
private fun SideRail(tab: Tab, onSelect: (Tab) -> Unit) {
    val c = Msst.colors
    NavigationRail(
        Modifier.fillMaxHeight(),
        containerColor = c.bgSecondary,
        header = {
            TitleBar(Modifier.width(80.dp)) {
                Column(
                    Modifier.width(80.dp).padding(top = 14.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Logo(36.dp)
                    Text("MSST-Net", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = c.textSecondary)
                }
            }
        },
    ) {
        Tab.entries.forEach { t ->
            val selected = t == tab
            NavigationRailItem(
                selected = selected,
                onClick = { onSelect(t) },
                icon = { MIcon(if (selected) t.selectedIcon else t.icon, 24.dp) },
                label = { Text(t.label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)) },
                modifier = Modifier.padding(vertical = 2.dp),
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = c.primaryDark,
                    selectedTextColor = c.textPrimary,
                    indicatorColor = c.navIndicator,
                    unselectedIconColor = c.textSecondary,
                    unselectedTextColor = c.textSecondary,
                ),
            )
        }
    }
}

/** Material navigation bar (phones). */
@Composable
private fun BottomTabs(tab: Tab, onSelect: (Tab) -> Unit) {
    val c = Msst.colors
    NavigationBar(containerColor = c.bgPrimary, tonalElevation = 0.dp) {
        Tab.entries.forEach { t ->
            val selected = t == tab
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(t) },
                icon = { MIcon(if (selected) t.selectedIcon else t.icon, 24.dp) },
                label = { Text(t.label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = c.primaryDark,
                    selectedTextColor = c.textPrimary,
                    indicatorColor = c.navIndicator,
                    unselectedIconColor = c.textSecondary,
                    unselectedTextColor = c.textSecondary,
                ),
            )
        }
    }
}

/** "A newer release exists — run the installer (or get the new app)",
 *  with download links.  Phones stack the links under the text. */
@Composable
private fun UpdateBanner(u: UpdateInfo, openUrl: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Msst.colors
    val text = @Composable { modifier: Modifier ->
        Text("发现新版本：${updateSummary(u)}。${updateHowto(u)}", modifier, style = MaterialTheme.typography.bodySmall, color = c.primaryDark)
    }
    val links = @Composable {
        LinkButton(updateDownloadLabel(u, "Gitee"), { openUrl(u.downloadGitee) })
        LinkButton("GitHub", { openUrl(u.downloadGithub) }, MdiIcons.Github)
    }
    val bar = Modifier.fillMaxWidth().clip(Radius.md).background(c.primaryLighter).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
    if (LocalCompact.current) {
        Column(bar) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                text(Modifier.weight(1f))
                SmallIconButton(MdiIcons.Close, "暂不提醒", onDismiss)
            }
            Row { links() }
        }
    } else {
        Row(bar, verticalAlignment = Alignment.CenterVertically) {
            text(Modifier.weight(1f))
            links()
            SmallIconButton(MdiIcons.Close, "暂不提醒", onDismiss)
        }
    }
}

@Composable
private fun RoomBanner(name: String?, roomId: String, relay: String?) {
    val c = Msst.colors
    val services = LocalController.current.services
    Row(
        Modifier.fillMaxWidth().clip(Radius.md).background(c.primaryLighter).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MIcon(MdiIcons.AccountMultipleOutline, 18.dp, c.primaryDark)
        Text(name ?: "当前房间", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.primaryDark)
        Row(
            Modifier.clip(Radius.sm).background(c.bgPrimary).border(1.dp, c.primarySoftBorder, Radius.sm).padding(start = 10.dp),
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
