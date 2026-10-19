package com.msst.net.ui.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.msst.net.platform.AppExclusion
import com.msst.net.platform.DeviceApp
import com.msst.net.state.attempt
import com.msst.net.state.errorMessage
import com.msst.net.state.filterApps
import com.msst.net.ui.LocalCompact
import com.msst.net.ui.MdiIcons
import com.msst.net.ui.components.CheckRow
import com.msst.net.ui.components.EmptyState
import com.msst.net.ui.components.InlineError
import com.msst.net.ui.components.InlineNote
import com.msst.net.ui.components.MIcon
import com.msst.net.ui.components.Modal
import com.msst.net.ui.components.PrimaryButton
import com.msst.net.ui.components.SecondaryButton
import com.msst.net.ui.components.Spinner
import com.msst.net.ui.components.TextInput
import com.msst.net.ui.theme.MonoFamily
import com.msst.net.ui.theme.Msst
import com.msst.net.ui.theme.Radius
import kotlinx.coroutines.launch

/** Choose the apps whose traffic bypasses MSST-Net (Android per-app VPN). */
@Composable
fun AppExclusionDialog(exclusion: AppExclusion, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val saved by exclusion.excluded.collectAsState()
    var selected by remember { mutableStateOf(saved) }
    var apps by remember { mutableStateOf<List<DeviceApp>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(exclusion) {
        val r = attempt { exclusion.apps() }
        apps = r.getOrNull()
        loadError = r.errorMessage
    }

    val shown = apps?.let { filterApps(it, query, showSystem, selected) }.orEmpty()

    Modal(onClose, "分应用代理", busy = saving, wide = true, actions = {
        SecondaryButton("取消", onClose, enabled = !saving)
        PrimaryButton(if (saving) "保存中…" else "保存", {
            saving = true
            error = null
            scope.launch {
                val r = attempt { exclusion.setExcluded(selected) }
                saving = false
                if (r.isSuccess) onClose() else error = r.errorMessage
            }
        }, enabled = selected != saved, busy = saving)
    }) {
        InlineNote("勾选的应用不经过 MSST-Net，无法访问 MSST-Net 中的设备和服务。保存后 VPN 接口会短暂重建。")
        TextInput(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "搜索应用名称或包名")
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckRow("显示系统应用", showSystem, { showSystem = it })
            Spacer(Modifier.weight(1f))
            Text("已排除 ${selected.size} 个", style = MaterialTheme.typography.labelSmall, color = Msst.colors.textMuted)
        }
        when {
            loadError != null -> InlineError("读取应用列表失败：$loadError")
            apps == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner(24.dp) }
            shown.isEmpty() -> EmptyState(if (query.isBlank()) "没有可选择的应用" else "没有匹配的应用")
            else -> LazyColumn(
                Modifier.fillMaxWidth().height(if (LocalCompact.current) 280.dp else 360.dp).clip(Radius.md).border(1.dp, Msst.colors.borderLight, Radius.md),
            ) {
                items(shown, key = { it.packageName }) { app ->
                    val checked = app.packageName in selected
                    AppRow(app, checked) { on -> selected = if (on) selected + app.packageName else selected - app.packageName }
                }
            }
        }
        error?.let { InlineError("保存失败：$it") }
    }
}

@Composable
private fun AppRow(app: DeviceApp, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Msst.colors
    Row(
        Modifier.fillMaxWidth().background(if (checked) c.primaryLighter else c.bgPrimary)
            .clickable { onChange(!checked) }.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = app.icon
        if (icon != null) Image(icon, null, Modifier.size(32.dp))
        else Box(Modifier.size(32.dp).clip(Radius.md).background(c.bgTertiary), contentAlignment = Alignment.Center) {
            MIcon(MdiIcons.Cellphone, 18.dp, c.textTertiary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    app.label,
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (app.system) Text("系统", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            }
            Text(
                app.packageName,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                color = c.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = c.primary))
    }
}
