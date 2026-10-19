package com.msst.net

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.msst.net.platform.AppExclusion
import com.msst.net.platform.DeviceApp
import com.msst.net.platform.MsstVpnService
import com.msst.net.platform.VpnControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Per-app exclusion: the package list is this device's VPN configuration
 * (kept in the app's preferences, not in Core), applied by
 * [MsstVpnService] with `VpnService.Builder.addDisallowedApplication`.
 */
class AndroidAppExclusion(private val context: Context) : AppExclusion {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _excluded = MutableStateFlow(load(context))
    override val excluded: StateFlow<Set<String>> = _excluded.asStateFlow()

    override suspend fun apps(): List<DeviceApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { info ->
                DeviceApp(
                    packageName = info.packageName,
                    label = info.loadLabel(pm).toString(),
                    system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    icon = runCatching { info.loadIcon(pm).toBitmap(ICON_PX, ICON_PX).asImageBitmap() }.getOrNull(),
                )
            }
    }

    override suspend fun setExcluded(packages: Set<String>) {
        withContext(Dispatchers.IO) { prefs.edit().putStringSet(KEY, packages).commit() }
        _excluded.value = packages
        // A running VPN interface is rebuilt with the new list; a sleeping or
        // stopped one picks it up when it is next established (and must not
        // be started just for this).
        if (MsstVpnService.state.value == VpnControl.State.Running) {
            context.startService(Intent(context, MsstVpnService::class.java).setAction(MsstVpnService.ACTION_REAPPLY))
        }
    }

    companion object {
        private const val PREFS = "msst_net_controller_prefs"
        private const val KEY = "excluded_apps"
        private const val ICON_PX = 96

        /** The saved list (read by the VPN service when it builds the interface). */
        fun load(context: Context): Set<String> =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, null)?.toSet() ?: emptySet()
    }
}
