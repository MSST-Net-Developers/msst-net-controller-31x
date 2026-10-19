package com.msst.net.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.msst.net.model.LogEntry
import com.msst.net.model.LogList
import com.msst.net.model.Req
import com.msst.net.platform.formatDateTime
import com.msst.net.platform.query
import com.msst.net.state.attempt
import com.msst.net.state.errorMessage
import com.msst.net.ui.LocalController
import com.msst.net.ui.components.ConfirmDialog
import com.msst.net.ui.components.EmptyState
import com.msst.net.ui.components.InlineError
import com.msst.net.ui.components.InlineNote
import com.msst.net.ui.components.Modal
import com.msst.net.ui.components.PrimaryButton
import com.msst.net.ui.components.SecondaryButton
import com.msst.net.ui.components.Select
import com.msst.net.ui.theme.MonoFamily
import com.msst.net.ui.theme.Msst
import com.msst.net.ui.theme.Radius
import kotlinx.coroutines.launch

private val LEVELS = listOf("all", "error", "warn", "info", "debug", "trace")

/** Core's log buffer (`GetLogs`). */
@Composable
fun LogDialog(onClose: () -> Unit) {
    val ctx = LocalController.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(listOf<LogEntry>()) }
    var level by remember { mutableStateOf("all") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val filtered = if (level == "all") entries else entries.filter { it.level.lowercase() == level }

    suspend fun load() {
        loading = true
        error = null
        val r = attempt { ctx.backend.query<LogList>(Req.getLogs(2000)) }
        r.onSuccess { entries = it.entries }
        error = r.errorMessage
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    fun line(e: LogEntry) = "[${formatDateTime(e.timestampMs)}] [${e.level.uppercase()}] ${e.target}: ${e.message}"

    Modal(onClose, "客户端核心日志", wide = true, actions = {
        SecondaryButton("关闭", onClose)
    }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Select(LEVELS, level, { if (it == "all") "全部级别" else it.uppercase() }, { level = it }, Modifier.width(130.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                SecondaryButton("刷新", { scope.launch { load() } }, enabled = !loading)
                SecondaryButton("清空", { confirmClear = true })
                PrimaryButton("导出", {
                    note = null
                    val text = filtered.joinToString("\n", transform = ::line)
                    scope.launch {
                        val r = attempt { ctx.services.saveText("msst-net-logs.txt", text) }
                        r.onSuccess { if (it) note = "已导出 ${filtered.size} 条日志" }
                        r.errorMessage?.let { error = "导出失败：$it" }
                    }
                }, enabled = filtered.isNotEmpty())
            }
        }
        error?.let { InlineError(it) }
        note?.let { InlineNote(it) }
        val c = Msst.colors
        if (filtered.isEmpty()) {
            EmptyState(if (loading) "加载中…" else "暂无日志")
        } else {
            SelectionContainer {
                LazyColumn(
                    Modifier.fillMaxWidth().height(360.dp).clip(Radius.md).background(c.bgSecondary)
                        .border(1.dp, c.borderLight, Radius.md).padding(8.dp),
                ) {
                    itemsIndexed(filtered) { _, e ->
                        val levelColor = when (e.level.lowercase()) {
                            "error" -> c.error
                            "warn" -> c.warning
                            "debug", "trace" -> c.textMuted
                            else -> c.primaryDark
                        }
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = c.textMuted)) { append(formatDateTime(e.timestampMs) + " ") }
                                withStyle(SpanStyle(color = levelColor)) { append("[${e.level.uppercase()}] ") }
                                withStyle(SpanStyle(color = c.textTertiary)) { append("${e.target}: ") }
                                append(e.message)
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                            color = c.textPrimary,
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog("清空日志", "确定清空客户端核心的日志缓冲区？", "清空", {
            scope.launch {
                val r = attempt { ctx.backend.request(Req.clearLogs()) }
                if (r.isFailure) error = r.errorMessage else load()
            }
        }, { confirmClear = false })
    }
}
