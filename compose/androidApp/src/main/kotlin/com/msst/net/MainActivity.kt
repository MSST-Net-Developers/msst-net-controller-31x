package com.msst.net

import android.Manifest
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.msst.net.ui.ControllerApp

class MainActivity : ComponentActivity() {

    private val app get() = application as MsstApplication

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) app.platform.vpn.startService()
    }

    // Android 13+: ask for notifications (access requests), then start the VPN either way.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startVpn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        app.platform.vpn.requestPermission = ::startVpn

        setContent {
            ControllerApp(app.platform, app.store)
        }

        if (savedInstanceState == null) {
            // Start the VPN (and Core) on launch, as in 3.0.x.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startVpn()
            }
        }
    }

    override fun onDestroy() {
        if (app.platform.vpn.requestPermission == ::startVpn) app.platform.vpn.requestPermission = null
        super.onDestroy()
    }

    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else app.platform.vpn.startService()
    }
}
