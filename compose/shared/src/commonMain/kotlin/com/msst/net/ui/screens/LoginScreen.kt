package com.msst.net.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.msst.net.platform.ApiException
import com.msst.net.platform.WebSession
import com.msst.net.state.attempt
import com.msst.net.state.errorMessage
import com.msst.net.ui.components.Card
import com.msst.net.ui.components.InlineError
import com.msst.net.ui.components.Logo
import com.msst.net.ui.components.PrimaryButton
import com.msst.net.ui.components.TextInput
import com.msst.net.ui.theme.Msst
import kotlinx.coroutines.launch

/** WebUI access password (set when the WebUI server is first started). */
@Composable
fun LoginScreen(session: WebSession, onLogin: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun login() {
        if (password.isEmpty() || loading) return
        loading = true
        error = null
        scope.launch {
            val r = attempt { session.login(password) }
            loading = false
            if (r.isSuccess) {
                password = ""
                onLogin()
            } else {
                error = if ((r.exceptionOrNull() as? ApiException)?.code == "invalid_password") "密码错误" else r.errorMessage
            }
        }
    }

    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Logo(48.dp)
                Text("MSST-Net WebUI", style = MaterialTheme.typography.titleLarge, color = Msst.colors.textPrimary)
                Text("请输入访问密码", style = MaterialTheme.typography.bodySmall, color = Msst.colors.textMuted)
                error?.let { InlineError(it) }
                TextInput(password, { password = it }, Modifier.fillMaxWidth(), placeholder = "访问密码", enabled = !loading, password = true, onDone = ::login)
                PrimaryButton(if (loading) "登录中…" else "登录", ::login, Modifier.fillMaxWidth(), enabled = password.isNotEmpty(), busy = loading)
            }
        }
    }
}
