package com.msst.net.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.msst.net.controller.Connection
import com.msst.net.controller.ControllerView
import com.msst.net.ui.theme.ColorSchemeOption
import com.msst.net.viewmodel.MainViewModel

private data class TabItem(val label: String, val selectedIcon: ImageVector, val unselectedIcon: ImageVector)

private val tabs = listOf(
    TabItem("首页", Icons.Filled.Home, Icons.Outlined.Home),
    TabItem("防御", Icons.Filled.Shield, Icons.Outlined.Shield),
    TabItem("设置", Icons.Filled.Settings, Icons.Outlined.Settings),
    TabItem("关于", Icons.Filled.Info, Icons.Outlined.Info),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onSystemColorChanged: ((Boolean) -> Unit)? = null,
    onColorSchemeChanged: ((ColorSchemeOption) -> Unit)? = null,
) {
    val view by viewModel.view.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val authBusy by viewModel.authBusy.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it, withDismissAction = true)
            viewModel.clearMessage()
        }
    }

    val inRoom = view?.room?.inRoom == true
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (inRoom && selectedTab == 0) "当前房间" else tabs[selectedTab].label, fontWeight = FontWeight.Normal) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(if (selectedTab == index) tab.selectedIcon else tab.unselectedIcon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            CoreBanner(view)
            val content = Modifier.fillMaxSize()
            when (selectedTab) {
                0 -> HomeScreen(modifier = content, viewModel = viewModel)
                1 -> DefenseScreen(modifier = content, viewModel = viewModel)
                2 -> SettingsScreen(
                    modifier = content,
                    viewModel = viewModel,
                    onSystemColorChanged = onSystemColorChanged,
                    onColorSchemeChanged = onColorSchemeChanged,
                )
                3 -> AboutScreen(modifier = content, view = view)
            }
        }

        // Interactive authorization: the queue is Core's pending list.
        val pending = if (view?.connected == true) view?.pendingAuth.orEmpty() else emptyList()
        pending.firstOrNull()?.let { req ->
            AuthDialog(
                req = req,
                queueSize = pending.size,
                busy = authBusy != null,
                onSubmit = viewModel::submitAuthDecision,
            )
        }
    }
}

/** Explains why Core data may be missing or stale (AGENTS.md §7.4). */
@Composable
private fun CoreBanner(view: ControllerView?) {
    val (text, isError) = when {
        view == null -> return
        view.connection == Connection.Incompatible ->
            "客户端核心的协议版本（${view.coreProtocol ?: "未知"}）与控制器（${view.controllerProtocol}）不兼容" to true
        view.connection is Connection.Reconnecting || (view.connection == Connection.Disconnected && view.everConnected) ->
            "未连接到客户端核心，正在重试…以下内容可能已过期" to true
        !view.connected -> return
        view.sync == "syncing" -> "正在从客户端核心读取最新状态…" to false
        view.sync == "failed" -> "部分状态读取失败，相关内容可能缺失" to true
        view.accessAuthRequired && view.health?.isAuthenticated != true ->
            "MSST 接入认证未完成或已过期，请在首页完成接入认证" to true
        else -> return
    }
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
