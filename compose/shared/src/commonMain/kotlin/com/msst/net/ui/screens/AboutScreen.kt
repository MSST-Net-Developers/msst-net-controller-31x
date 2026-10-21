package com.msst.net.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.msst.net.BuildInfo
import com.msst.net.ui.LocalController
import com.msst.net.ui.MdiIcons
import com.msst.net.ui.components.Card
import com.msst.net.ui.components.InlineNote
import com.msst.net.ui.components.LinkButton
import com.msst.net.ui.components.Logo
import com.msst.net.ui.components.Pill
import com.msst.net.ui.components.SecondaryButton
import com.msst.net.ui.dialogs.LicensesDialog
import com.msst.net.ui.theme.Msst

@Composable
fun AboutScreen() {
    val ctx = LocalController.current
    val view by ctx.store.view.collectAsState()
    val c = Msst.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Logo(64.dp)
                Text("MSST-Net 控制器", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("v${BuildInfo.VERSION}", c.primaryDark, c.primaryLighter)
                    Text("「${BuildInfo.CODENAME}」", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                }
                InlineNote("IPC 协议：控制器 ${view?.controllerProtocol?.ifEmpty { null } ?: "—"} / 客户端核心 ${view?.coreProtocol ?: "未连接"}")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("MSST", "十周年部计算机办", "MSST-Net 开发组").forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("内网互联焕发生机", style = MaterialTheme.typography.titleMedium, color = c.primaryDark, textAlign = TextAlign.Center)
        Text("虽是远在天边，犹如近在眼前", style = MaterialTheme.typography.bodySmall, color = c.textMuted, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkButton("官方网站（含使用说明）", { ctx.services.openUrl(WEBSITE) }, MdiIcons.Web)
            LinkButton("GitHub", { ctx.services.openUrl("https://github.com/MSST-Net-Developers") }, MdiIcons.Github)
        }
        LicensesCard(Modifier.widthIn(max = 420.dp).fillMaxWidth())
    }
}

@Composable
private fun LicensesCard(modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Card(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "MSST-Net 控制器以 MIT 许可证发布，并使用了第三方开源软件",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = Msst.colors.textSecondary,
            )
            SecondaryButton("开源许可", { open = true }, icon = MdiIcons.BookOpenOutline)
        }
    }
    if (open) LicensesDialog { open = false }
}
