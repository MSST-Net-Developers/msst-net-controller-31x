package com.msst.net

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.msst.net.ui.MainScreen
import com.msst.net.ui.theme.ColorSchemeOption
import com.msst.net.ui.theme.MsstNetTheme
import com.msst.net.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var prefs: AppPreferences

    private var useSystemColor by mutableStateOf(true)
    private var colorSchemeOption by mutableStateOf(ColorSchemeOption.LIGHT_BLUE)

    // ── VPN 权限请求 ──────────────────────────────────────────────────────────
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.startVpn(this)
        }
    }

    // ── 通知权限请求（Android 13+）────────────────────────────────────────────
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 无论是否授权都尝试启动 VPN */ requestVpnPermission() }

    // ── Activity 生命周期 ──────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        prefs = AppPreferences(this)
        useSystemColor = prefs.useSystemColor
        colorSchemeOption = ColorSchemeOption.fromKey(prefs.colorScheme)

        setContent {
            MsstNetTheme(
                dynamicColor      = useSystemColor,
                colorSchemeOption = colorSchemeOption,
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        viewModel            = viewModel,
                        onSystemColorChanged = { newValue ->
                            prefs.useSystemColor = newValue
                            useSystemColor = newValue
                        },
                        onColorSchemeChanged = { newOption ->
                            prefs.colorScheme = newOption.key
                            colorSchemeOption = newOption
                        },
                    )
                }
            }
        }

        if (savedInstanceState == null) {
            // 应用启动时自动申请权限并启动 VPN（3.0.x 行为）
            requestNotificationPermissionIfNeeded()
        }
    }

    // ── 权限流程 ──────────────────────────────────────────────────────────────

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestVpnPermission()
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            viewModel.startVpn(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}