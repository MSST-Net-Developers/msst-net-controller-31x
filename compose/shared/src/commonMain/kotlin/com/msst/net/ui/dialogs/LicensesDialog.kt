package com.msst.net.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.msst.net.resources.Res
import com.msst.net.state.attempt
import com.msst.net.state.errorMessage
import com.msst.net.ui.LocalCompact
import com.msst.net.ui.components.EmptyState
import com.msst.net.ui.components.InlineNote
import com.msst.net.ui.components.Modal
import com.msst.net.ui.components.SecondaryButton
import com.msst.net.ui.theme.MonoFamily
import com.msst.net.ui.theme.Msst
import com.msst.net.ui.theme.Radius

/** The bundled third-party notices (scripts/third-party-licenses.py). */
@Composable
fun LicensesDialog(onClose: () -> Unit) {
    var paragraphs by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val r = attempt { Res.readBytes("files/THIRD_PARTY_LICENSES.txt").decodeToString() }
        // One item per paragraph: the file is too long for a single Text.
        r.onSuccess { text -> paragraphs = text.trimEnd().split("\n\n") }
        error = r.errorMessage
    }

    Modal(onClose, "开源许可", wide = true, actions = { SecondaryButton("关闭", onClose) }) {
        InlineNote("MSST-Net 控制器以 MIT 许可证发布。以下是其中包含的第三方软件及其许可证。")
        val c = Msst.colors
        val list = paragraphs
        when {
            error != null -> EmptyState("无法读取许可声明：$error", error = true)
            list == null -> EmptyState("加载中…")
            else -> SelectionContainer {
                LazyColumn(
                    Modifier.fillMaxWidth().height(if (LocalCompact.current) 360.dp else 440.dp).clip(Radius.md)
                        .background(c.bgSecondary).border(1.dp, c.borderLight, Radius.md).padding(8.dp),
                ) {
                    items(list) {
                        Text(
                            it,
                            Modifier.padding(bottom = 10.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                            color = c.textPrimary,
                        )
                    }
                }
            }
        }
    }
}
