package com.tombstonex.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tombstonex.BuildConfig
import com.tombstonex.service.ServiceClient
import com.tombstonex.ui.LocalModuleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val moduleState = LocalModuleState.current

    // 通过 ServiceClient 异步获取冻结器名称
    var freezerName by remember { mutableStateOf("未知") }
    LaunchedEffect(Unit) {
        freezerName = withContext(Dispatchers.IO) {
            runCatching { ServiceClient.getCurrentFreezerName() }.getOrDefault("未知")
        }
    }
    val freezerAvailable = freezerName.isNotBlank() &&
        freezerName != "未知" &&
        freezerName != "None"

    val androidVersion = Build.VERSION.RELEASE
    val sdkLevel = Build.VERSION.SDK_INT
    val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"

    fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // 无浏览器可用时用 Toast 提示
            Toast.makeText(context, "未找到可打开链接的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 品牌头部 ----
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "TX",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "TombstoneX",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "墓碑 · 应用冻结框架",
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            fontSize = 14.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // ---- 模块描述 ----
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "模块描述",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "TombstoneX 是一个基于 LSPosed 的应用冻结框架，通过 Hook " +
                                "system_server 实现对后台应用的高效冻结与解冻，支持 SystemAPI、" +
                                "Cgroup、信号等多种冻结方式，并具备白名单、子 Hook 等精细控制能力。" +
                                "可有效降低后台应用对电量与性能的消耗。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // ---- 模块信息 ----
            item {
                SectionTitle("模块信息")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        InfoRow("模块名称", "TombstoneX")
                        HorizontalDivider()
                        InfoRow("版本号", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        HorizontalDivider()
                        InfoRow(
                            "Hook 入口",
                            moduleState.entryClass.ifEmpty { "未配置" },
                        )
                        HorizontalDivider()
                        InfoRow(
                            "激活状态",
                            if (moduleState.activated) "已安装/已配置" else "未安装",
                            valueColor = if (moduleState.activated)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ---- 系统信息 ----
            item {
                SectionTitle("系统信息")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        InfoRow("Android 版本", androidVersion)
                        HorizontalDivider()
                        InfoRow("SDK 级别", sdkLevel.toString())
                        HorizontalDivider()
                        InfoRow("设备型号", deviceModel)
                    }
                }
            }

            // ---- 冻结器状态 ----
            item {
                SectionTitle("冻结器状态")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        InfoRow("当前冻结器", freezerName)
                        HorizontalDivider()
                        InfoRow(
                            "是否可用",
                            if (freezerAvailable) "可用" else "不可用",
                            valueColor = if (freezerAvailable)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ---- 开源链接 ----
            item {
                SectionTitle("开源与反馈")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        ListItem(
                            headlineContent = { Text("GitHub 仓库") },
                            supportingContent = { Text("github.com/maxlingg/TombstoneX") },
                            leadingContent = {
                                Icon(
                                    Icons.Filled.OpenInNew,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            modifier = Modifier.clickable {
                                openUrl("https://github.com/maxlingg/TombstoneX")
                            },
                        )
                    }
                }
            }

            // ---- 致谢 ----
            item {
                SectionTitle("致谢")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "感谢以下开源项目与社区：",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Bullet("LSPosed / Xposed Framework")
                        Bullet("Jetpack Compose & Material Design 3")
                        Bullet("Android Open Source Project")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "感谢所有为 TombstoneX 贡献代码、提交问题与建议的用户。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            item {
                Text(
                    text = "Made with Material Design 3",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Text(
                text = value,
                color = valueColor,
                fontWeight = FontWeight.Medium,
            )
        },
    )
}

@Composable
private fun Bullet(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp, end = 8.dp)
                .size(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}
