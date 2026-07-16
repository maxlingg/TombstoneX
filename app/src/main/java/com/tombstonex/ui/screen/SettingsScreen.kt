package com.tombstonex.ui.screen

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tombstonex.BuildConfig
import com.tombstonex.model.FreezeMode
import com.tombstonex.service.ServiceClient
import com.tombstonex.ui.LocalModuleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    showSnackbar: (String) -> Unit,
    onNavigateAbout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val moduleState = LocalModuleState.current

    // 首次组合时通过 produceState 异步加载配置
    val initialConfig by produceState<Pair<ServiceClient.ConfigSnapshot?, String>>(
        initialValue = null to "未知",
    ) {
        val cfg = withContext(Dispatchers.IO) {
            runCatching { ServiceClient.getConfig() }.getOrNull()
        }
        val freezer = withContext(Dispatchers.IO) {
            runCatching { ServiceClient.getCurrentFreezerName() }.getOrDefault("未知")
        }
        value = cfg to freezer
    }

    // 本地可变状态，初始值由 initialConfig 同步
    var freezeMode by remember { mutableStateOf(FreezeMode.SYSTEM_API) }
    var currentFreezerName by remember { mutableStateOf("未知") }
    var debugEnabled by remember { mutableStateOf(false) }
    var freezeDelay by remember { mutableFloatStateOf(3f) }
    var showModeDialog by remember { mutableStateOf(false) }

    // 子 Hook 开关
    var hookAnr by remember { mutableStateOf(true) }
    var hookBroadcast by remember { mutableStateOf(true) }
    var hookWakeLock by remember { mutableStateOf(true) }
    var hookActivitySwitch by remember { mutableStateOf(true) }
    var hookScreenState by remember { mutableStateOf(true) }

    // 当配置首次加载完成时同步到本地状态
    LaunchedEffect(initialConfig) {
        val (cfg, freezer) = initialConfig
        if (cfg != null) {
            freezeMode = FreezeMode.values().getOrElse(cfg.freezeMode) { FreezeMode.SYSTEM_API }
            debugEnabled = cfg.debugEnabled
            freezeDelay = cfg.freezeDelay.toFloat()
            hookAnr = cfg.hookANR
            hookBroadcast = cfg.hookBroadcast
            hookWakeLock = cfg.hookWakeLock
            hookActivitySwitch = cfg.hookActivitySwitch
            hookScreenState = cfg.hookScreenState
        }
        currentFreezerName = freezer
    }

    val modeDisplayName = freezeMode.displayLabel()

    // 冻结方式列表用 remember 缓存，避免每次重组重建
    val modes = remember {
        listOf(
            FreezeMode.SYSTEM_API to "SystemAPI（推荐）" to "Android 12+ 官方 API，兼容性最好",
            FreezeMode.CGROUP_V2 to "CgroupV2" to "直接写 cgroup.freeze 文件",
            FreezeMode.CGROUP_V1 to "CgroupV1" to "写 freezer.state 文件",
            FreezeMode.SIGNAL_19 to "SIGSTOP（Kill -19）" to "发送 SIGSTOP 信号冻结",
            FreezeMode.SIGNAL_20 to "SIGTSTP（Kill -20）" to "发送 SIGTSTP 信号冻结",
        )
    }

    fun applyFreezeMode(mode: FreezeMode) {
        freezeMode = mode
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ServiceClient.setFreezeMode(mode.ordinal) }.getOrDefault(false)
            }
            if (ok) {
                // 冻结方式切换后重新选择冻结器
                withContext(Dispatchers.IO) {
                    runCatching { ServiceClient.reselectFreezer() }
                }
                currentFreezerName = withContext(Dispatchers.IO) {
                    runCatching { ServiceClient.getCurrentFreezerName() }.getOrDefault("未知")
                }
                showSnackbar("冻结方式已切换为 ${mode.displayLabel()}，当前生效：$currentFreezerName")
            } else {
                showSnackbar("设置失败（模块未激活或无权限）")
            }
        }
    }

    /**
     * 切换子 Hook 开关，通过 ServiceClient.setHookEnabled(hookId, enabled) 写入。
     * hookId: 0=ANR, 1=Broadcast, 2=WakeLock, 3=ActivitySwitch, 4=ScreenState
     */
    fun toggleHook(hookId: Int, current: Boolean, onUpdate: (Boolean) -> Unit) {
        val newValue = !current
        onUpdate(newValue)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ServiceClient.setHookEnabled(hookId, newValue) }.getOrDefault(false)
            }
            if (!ok) {
                // 回滚
                onUpdate(!newValue)
                showSnackbar("设置未生效（模块未激活或无权限）")
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置", fontWeight = FontWeight.SemiBold) }) },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // ---- 模块状态 ----
            item { SectionHeader("模块状态") }
            item {
                ListItem(
                    headlineContent = { Text("激活状态") },
                    supportingContent = {
                        Text(
                            if (moduleState.activated) "已安装并配置入口（${moduleState.entryClass}）"
                            else "未检测到模块入口，请在 LSPosed 中启用"
                        )
                    },
                    trailingContent = { StatusDot(moduleState.activated) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("模块版本") },
                    supportingContent = { Text("v${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）") },
                )
            }
            item { HorizontalDivider() }

            // ---- 冻结配置 ----
            item { SectionHeader("冻结配置") }
            item {
                ListItem(
                    headlineContent = { Text("冻结方式") },
                    supportingContent = {
                        Column {
                            Text("已选：$modeDisplayName")
                            Text(
                                "当前生效冻结器：$currentFreezerName",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 13.sp,
                            )
                        }
                    },
                    modifier = Modifier.clickable { showModeDialog = true },
                )
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("冻结延迟", modifier = Modifier.weight(1f))
                        Text(
                            "${freezeDelay.toInt()} 秒",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Slider(
                        value = freezeDelay,
                        onValueChange = { freezeDelay = it },
                        onValueChangeFinished = {
                            val delay = freezeDelay.toInt()
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { ServiceClient.setFreezeDelay(delay) }
                                }
                            }
                        },
                        valueRange = 1f..10f,
                        steps = 8,
                    )
                    Text(
                        "应用退到后台后等待指定秒数再冻结",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
            item { HorizontalDivider() }

            // ---- 日志与调试 ----
            item { SectionHeader("日志与调试") }
            item {
                ListItem(
                    headlineContent = { Text("调试日志") },
                    supportingContent = { Text(BuildConfig.LOG_PATH) },
                    trailingContent = {
                        Switch(
                            checked = debugEnabled,
                            onCheckedChange = {
                                debugEnabled = it
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        runCatching { ServiceClient.setDebugEnabled(it) }.getOrDefault(false)
                                    }
                                    if (!ok) {
                                        debugEnabled = !it
                                        showSnackbar("设置未生效（模块未激活或无权限）")
                                    }
                                }
                            },
                        )
                    },
                )
            }
            item { HorizontalDivider() }

            // ---- 子 Hook 开关 ----
            item { SectionHeader("子 Hook 开关") }
            item {
                HookSwitchRow(
                    title = "Activity 切换",
                    subtitle = "切换 Activity 时触发冻结（核心功能）",
                    checked = hookActivitySwitch,
                ) {
                    toggleHook(3, hookActivitySwitch) { hookActivitySwitch = it }
                }
            }
            item {
                HookSwitchRow(
                    title = "锁屏批量冻结",
                    subtitle = "息屏后延迟批量冻结后台应用",
                    checked = hookScreenState,
                ) {
                    toggleHook(4, hookScreenState) { hookScreenState = it }
                }
            }
            item {
                HookSwitchRow(
                    title = "广播拦截",
                    subtitle = "冻结后屏蔽广播投递",
                    checked = hookBroadcast,
                ) {
                    toggleHook(1, hookBroadcast) { hookBroadcast = it }
                }
            }
            item {
                HookSwitchRow(
                    title = "WakeLock 拦截",
                    subtitle = "冻结后阻止申请唤醒锁",
                    checked = hookWakeLock,
                ) {
                    toggleHook(2, hookWakeLock) { hookWakeLock = it }
                }
            }
            item {
                HookSwitchRow(
                    title = "ANR 拦截",
                    subtitle = "冻结后屏蔽应用无响应弹窗",
                    checked = hookAnr,
                ) {
                    toggleHook(0, hookAnr) { hookAnr = it }
                }
            }
            item { HorizontalDivider() }

            // ---- 关于 ----
            item { SectionHeader("其他") }
            item {
                ListItem(
                    headlineContent = { Text("关于 TombstoneX") },
                    supportingContent = { Text("版本信息、系统信息与致谢") },
                    trailingContent = {
                        Icon(
                            Icons.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.clickable { onNavigateAbout() },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Android 版本") },
                    supportingContent = { Text("API ${Build.VERSION.SDK_INT}（Android ${Build.VERSION.RELEASE}）") },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("设备信息") },
                    supportingContent = { Text("${Build.MANUFACTURER} ${Build.MODEL}") },
                )
            }
        }
    }

    if (showModeDialog) {
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
                                    applyFreezeMode(mode)
                                    showModeDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = freezeMode == mode,
                                onClick = {
                                    applyFreezeMode(mode)
                                    showModeDialog = false
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(name)
                                Text(
                                    desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showModeDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun StatusDot(active: Boolean) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .size(10.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun HookSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onToggle)
        },
    )
}

private fun FreezeMode.displayLabel(): String = when (this) {
    FreezeMode.SYSTEM_API -> "SystemAPI"
    FreezeMode.CGROUP_V2 -> "CgroupV2"
    FreezeMode.CGROUP_V1 -> "CgroupV1"
    FreezeMode.SIGNAL_19 -> "SIGSTOP"
    FreezeMode.SIGNAL_20 -> "SIGTSTP"
}
