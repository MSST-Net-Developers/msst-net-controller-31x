package com.msst.net.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.msst.net.BuildConfig
import com.msst.net.controller.ControllerView

@Composable
fun AboutScreen(modifier: Modifier = Modifier, view: ControllerView? = null) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── 页面标题 ──────────────────────────────────────────────────────
        Text(
            "关于",
            style      = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Normal,
            modifier   = Modifier.padding(top = 8.dp),
        )

        // ── 应用信息卡片 ──────────────────────────────────────────────────
        Card(
            modifier  = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(
                modifier            = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Logo
                AppLogo()

                Spacer(Modifier.height(20.dp))

                // 应用名称
                Text(
                    "MSST-Net 控制器",
                    style       = MaterialTheme.typography.headlineLarge,
                    fontWeight  = FontWeight.Bold,
                    textAlign   = TextAlign.Center,
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    "Compose 版",
                    style  = MaterialTheme.typography.titleMedium,
                    color  = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(24.dp))

                // 版本
                InfoRow(label = "版本", value = BuildConfig.VERSION_NAME, isTag = true)

                Spacer(Modifier.height(12.dp))

                // 代号
                InfoRow(label = "代号", value = BuildConfig.VERSION_CODENAME, isTag = true)

                Spacer(Modifier.height(12.dp))

                // IPC 协议版本
                InfoRow(
                    label = "IPC 协议",
                    value = "控制器 ${view?.controllerProtocol ?: "—"} / 核心 ${view?.coreProtocol ?: "未连接"}",
                    isTag = false,
                )

                Spacer(Modifier.height(20.dp))

                HorizontalDivider()

                Spacer(Modifier.height(20.dp))

                // 标语
                Text(
                    "内网互联焕发生机",
                    style     = MaterialTheme.typography.titleMedium,
                    color     = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    "虽是远在天边，犹如近在眼前",
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(16.dp))

                // 网站链接（含使用说明）
                TextButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://msst-net.msst2031.cn"))
                        context.startActivity(intent)
                    }
                ) {
                    Icon(
                        Icons.Filled.Language,
                        contentDescription = "网站",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "官方网站（含使用说明）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.OpenInNew,
                        contentDescription = "打开链接",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // ── 技术栈卡片 ────────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    "技术栈",
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(Modifier.height(12.dp))

                TechItem("平台",     "Android（Jetpack Compose + Material Design 3）")
                TechItem("开发语言", "Kotlin")
                TechItem("客户端核心", "Rust（msst-net-client-core）")
                TechItem("JNI 桥接", "Rust jni crate + Android NDK")
                TechItem("VPN",      "Android VpnService + TUN/TAP")
            }
        }
    }
}

// ── Logo ──────────────────────────────────────────────────────────────────────

@Composable
private fun AppLogo() {
    Box(
        modifier         = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                    )
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier         = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

// ── 信息行 ────────────────────────────────────────────────────────────────────

@Composable
private fun InfoRow(label: String, value: String, isTag: Boolean = false) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style      = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(8.dp))
        if (isTag) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    value,
                    modifier   = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style      = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        } else {
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

// ── 技术栈条目 ────────────────────────────────────────────────────────────────

@Composable
private fun TechItem(name: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            name,
            style      = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color      = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}