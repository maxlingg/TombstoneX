package com.tombstonex.ui.screen

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tombstonex.manager.ConfigManager
import com.tombstonex.model.FreezeMode

@Composable
fun SettingsScreen() {
    var freezeMode by remember {
        mutableStateOf(try { ConfigManager.getInstance().getFreezeMode() } catch (e: Exception) { FreezeMode.SYSTEM_API })
    }
    var debugEnabled by remember {
        mutableStateOf(try { ConfigManager.getInstance().isDebugEnabled() } catch (e: Exception) { false })
    }
    var showModeDialog by remember { mutableStateOf(false) }

    val modeDisplayName = when (freezeMode) {
        FreezeMode.SYSTEM_API -> "SystemAPI (推荐)"
        FreezeMode.CGROUP_V2 -> "CgroupV2"
        FreezeMode.CGROUP_V1 -> "CgroupV1"
        FreezeMode.SIGNAL_19 -> "SIGSTOP (Kill -19)"
        FreezeMode.SIGNAL_20 -> "SIGTSTP (Kill -20)"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ListItem(
            headlineContent = { Text("冻结方式") },
            supportingContent = { Text(modeDisplayName) },
            modifier = Modifier.clickable { showModeDialog = true }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("调试日志") },
            supportingContent = { Text("日志路径: /data/system/TombstoneX/current.log") },
            trailingContent = {
                Switch(
                    checked = debugEnabled,
                    onCheckedChange = {
                        debugEnabled = it
                        try { ConfigManager.getInstance().setDebugEnabled(it) } catch (e: Exception) {}
                    }
                )
            }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("模块版本") },
            supportingContent = { Text("1.0.0") }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("Android 版本") },
            supportingContent = { Text("API ${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE})") }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("设备信息") },
            supportingContent = { Text("${Build.MANUFACTURER} ${Build.MODEL}") }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("配置目录") },
            supportingContent = { Text("/data/system/TombstoneX/") }
        )
    }

    if (showModeDialog) {
        val modes = listOf(
            FreezeMode.SYSTEM_API to "SystemAPI (推荐)" to "Android 12+ 官方 API，兼容性最好",
            FreezeMode.CGROUP_V2 to "CgroupV2" to "直接写 cgroup.freeze 文件",
            FreezeMode.CGROUP_V1 to "CgroupV1" to "写 freezer.state 文件",
            FreezeMode.SIGNAL_19 to "SIGSTOP (Kill -19)" to "发送 SIGSTOP 信号冻结",
            FreezeMode.SIGNAL_20 to "SIGTSTP (Kill -20)" to "发送 SIGTSTP 信号冻结"
        )

        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text("选择冻结方式") },
            text = {
                Column {
                    modes.forEach { (pair, desc) ->
                        val (mode, name) = pair
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    freezeMode = mode
                                    try { ConfigManager.getInstance().setFreezeMode(mode) } catch (e: Exception) {}
                                    showModeDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = freezeMode == mode,
                                onClick = {
                                    freezeMode = mode
                                    try { ConfigManager.getInstance().setFreezeMode(mode) } catch (e: Exception) {}
                                    showModeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(name)
                                Text(
                                    desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showModeDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
