package com.tombstonex.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen() {
    var freezeMode by remember { mutableStateOf("SystemAPI (推荐)") }
    var debugEnabled by remember { mutableStateOf(false) }
    var showModeDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        ListItem(
            headlineContent = { Text("冻结方式") },
            supportingContent = { Text(freezeMode) },
            modifier = Modifier.clickable { showModeDialog = true }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("调试日志") },
            supportingContent = { Text("启用后可在 /data/system/TombstoneX/current.log 查看日志") },
            trailingContent = {
                Switch(
                    checked = debugEnabled,
                    onCheckedChange = { debugEnabled = it }
                )
            }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("版本") },
            supportingContent = { Text("1.0.0") }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("Android 版本") },
            supportingContent = { Text("16") }
        )
    }

    if (showModeDialog) {
        val modes = listOf(
            "SystemAPI (推荐)" to "Android 12+ 官方 API",
            "CgroupV2" to "直接写 cgroup.freeze",
            "CgroupV1" to "写 freezer.state",
            "SIGSTOP" to "Kill -19 信号",
            "SIGTSTP" to "Kill -20 信号"
        )

        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text("选择冻结方式") },
            text = {
                Column {
                    modes.forEach { (name, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    freezeMode = name
                                    showModeDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = freezeMode == name,
                                onClick = {
                                    freezeMode = name
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