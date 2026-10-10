package com.msst.net

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("msst_net_controller_prefs", Context.MODE_PRIVATE)

    var useSystemColor: Boolean
        get() = prefs.getBoolean(KEY_USE_SYSTEM_COLOR, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_SYSTEM_COLOR, value).apply()

    var colorScheme: String
        get() = prefs.getString(KEY_COLOR_SCHEME, "light_blue") ?: "light_blue"
        set(value) = prefs.edit().putString(KEY_COLOR_SCHEME, value).apply()

    companion object {
        private const val KEY_USE_SYSTEM_COLOR = "use_system_color"
        private const val KEY_COLOR_SCHEME = "color_scheme"
    }
}