package com.tombstonex.ui.screen

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tombstonex.BuildConfig
import com.tombstonex.model.FreezeMode
import com.tombstonex.service.ServiceClient
import com.tombstonex.ui.safeRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- 主题色常量 ----
private val SurfaceColor = Color(0xFF1C1B1F)
private val PrimaryColor = Color(0xFF00E5FF)
private val PrimaryContainerColor = Color(0x1F00E5FF)
private val OnSurfaceColor = Color(0xFFE6E1E5)
private val OnSurfaceVariantColor = Color(0xFFCAC4D0)
private val OnSurfaceMutedColor = Color(0xFF938F99)
private val OutlineVariantColor = Color(0xFF49454F)

@Composable
fun SettingsScreen(
    showSnackbar: (String) -> Unit,
    onNavigateAbout: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // 首次组合时通过 produceState 异步加载配置
    // P3-R4: 使用 @Immutable data class 替代 Pair，提升 Compose 稳定性
    val initialConfig by produceState<ConfigLoadResult>(
        initialValue = ConfigLoadResult(null, "未知"),
    ) {
        // P3-R1: 并行执行两个无依赖的 IPC 调用，减半延迟
        coroutineScope {
            val cfgDef = async(Dispatchers.IO) {
                safeRunCatching { ServiceClient.getConfig() }.getOrNull()
            }
            val freezerDef = async(Dispatchers.IO) {
                safeRunCatching { ServiceClient.getCurrentFreezerName() }.getOrDefault("未知")
            }
            value = ConfigLoadResult(cfgDef.await(), freezerDef.await())
        }
    }

    // 本地可变状态，初始值由 initialConfig 同步
    var freezeMode by remember { mutableStateOf(FreezeMode.SYSTEM_API) }
    var currentFreezerName by remember { mutableStateOf("未知") }
    var debugEnabled by remember { mutableStateOf(false) }
    var freezeDelay by remember { mutableFloatStateOf(3f) }
    var committedFreezeDelay by remember { mutableFloatStateOf(3f) }
    var showModeDialog by remember { mutableStateOf(false) }
    var showDelayDialog by remember { mutableStateOf(false) }
    var showRotationDialog by remember { mutableStateOf(false) }
    // P2-R3: 配置加载门控，防止 LaunchedEffect 覆盖用户在加载期间的操作
    var configLoaded by remember { mutableStateOf(false) }
    // R7-1 修复：单一 userInteracted 标志过于宽泛，用户触碰任意控件后即设为 true，
    // 此后所有后续加载的配置项都被跳过。改为每配置项独立追踪，仅跳过用户已操作的项。
    var freezeModeInteracted by remember { mutableStateOf(false) }
    var freezeDelayInteracted by remember { mutableStateOf(false) }
    var debugInteracted by remember { mutableStateOf(false) }
    var hookAnrInteracted by remember { mutableStateOf(false) }
    var hookBroadcastInteracted by remember { mutableStateOf(false) }
    var hookWakeLockInteracted by remember { mutableStateOf(false) }
    var hookActivitySwitchInteracted by remember { mutableStateOf(false) }
    var hookScreenStateInteracted by remember { mutableStateOf(false) }

    // 子 Hook 开关
    var hookAnr by remember { mutableStateOf(true) }
    var hookBroadcast by remember { mutableStateOf(true) }
    var hookWakeLock by remember { mutableStateOf(true) }
    var hookActivitySwitch by remember { mutableStateOf(true) }
    var hookScreenState by remember { mutableStateOf(true) }

    // 高级设置：轮番解冻间隔（秒）
    var rotationInterval by remember { mutableFloatStateOf(360f) }
    var committedRotationInterval by remember { mutableFloatStateOf(360f) }
    var rotationLoaded by remember { mutableStateOf(false) }
    // R7-2 修复：rotation 也使用独立的交互标志，与其他配置项保持一致
    var rotationInteracted by remember { mutableStateOf(false) }

    // 当配置首次加载完成时同步到本地状态
    LaunchedEffect(initialConfig) {
        val (cfg, freezer) = initialConfig
        // P2-R3: 仅在首次加载时同步配置，避免覆盖用户在加载期间的操作
        // R7-1 修复：对每个配置项检查独立的交互标志，仅跳过用户已操作的项，
        // 其他未操作的项仍然可以从服务端返回值同步
        // R7-3 修复：configLoaded 无论 cfg 是否为 null 都标记为已加载，
        // 避免 produceState 失败返回 null 时永久显示"加载中…"
        if (!configLoaded) {
            if (cfg != null) {
                if (!freezeModeInteracted) freezeMode = FreezeMode.fromId(cfg.freezeMode)
                if (!debugInteracted) debugEnabled = cfg.debugEnabled
                // R7-2 修复：配置加载时同步 committedFreezeDelay，
                // 避免 IPC 失败回滚到错误的硬编码默认值
                if (!freezeDelayInteracted) {
                    freezeDelay = cfg.freezeDelay.toFloat()
                    committedFreezeDelay = cfg.freezeDelay.toFloat()
                }
                if (!hookAnrInteracted) hookAnr = cfg.hookANR
                if (!hookBroadcastInteracted) hookBroadcast = cfg.hookBroadcast
                if (!hookWakeLockInteracted) hookWakeLock = cfg.hookWakeLock
                if (!hookActivitySwitchInteracted) hookActivitySwitch = cfg.hookActivitySwitch
                if (!hookScreenStateInteracted) hookScreenState = cfg.hookScreenState
            }
            configLoaded = true
        }
        currentFreezerName = freezer
    }

    // 异步加载轮番解冻间隔
    LaunchedEffect(Unit) {
        val interval = withContext(Dispatchers.IO) {
            safeRunCatching { ServiceClient.getRotationInterval() }.getOrDefault(360)
        }
        // R7-2 修复：使用 rotationInteracted 替代全局 userInteracted，
        // 仅当用户未拖动该滑块时同步服务端返回值，同时同步 committedRotationInterval
        if (!rotationLoaded && !rotationInteracted) {
            rotationInterval = interval.toFloat()
            committedRotationInterval = interval.toFloat()
            rotationLoaded = true
        }
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
        // R7-1: 仅标记 freezeMode 交互，不影响其他配置项的后续加载
        freezeModeInteracted = true
        val oldMode = freezeMode
        freezeMode = mode
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                safeRunCatching { ServiceClient.setFreezeMode(mode.id) }.getOrDefault(false)
            }
            if (ok) {
                // 冻结方式切换后重新选择冻结器
                // L3 修复：合并两次 withContext 调用为一次，减少线程切换开销
                val newFreezer = withContext(Dispatchers.IO) {
                    safeRunCatching { ServiceClient.reselectFreezer() }
                    safeRunCatching { ServiceClient.getCurrentFreezerName() }.getOrDefault("未知")
                }
                currentFreezerName = newFreezer
                showSnackbar("冻结方式已切换为 ${mode.displayLabel()}，当前生效：$currentFreezerName")
            } else {
                // P2: 失败时回滚到旧模式，保持 UI 与服务端一致
                // M6 修复：仅当当前值仍为本次设置的值时才回滚，避免覆盖用户在此期间的后续操作
                if (freezeMode == mode) {
                    freezeMode = oldMode
                }
                showSnackbar("设置失败（模块未激活或无权限）")
            }
        }
    }

    /**
     * 切换子 Hook 开关，通过 ServiceClient.setHookEnabled(hookId, enabled) 写入。
     * hookId: 0=ANR, 1=Broadcast, 2=WakeLock, 3=ActivitySwitch, 4=ScreenState
     * M6 修复：仅当当前值仍为本次设置的值时才回滚，避免覆盖用户在此期间的后续操作。
     * R7-1 修复：通过 markInteracted 回调仅标记对应配置项的交互标志，
     * 不再使用全局 userInteracted 影响其他配置项的加载。
     */
    fun toggleHook(
        hookId: Int,
        current: Boolean,
        getCurrent: () -> Boolean,
        onUpdate: (Boolean) -> Unit,
        markInteracted: () -> Unit,
    ) {
        markInteracted()
        val newValue = !current
        onUpdate(newValue)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                safeRunCatching { ServiceClient.setHookEnabled(hookId, newValue) }.getOrDefault(false)
            }
            if (!ok) {
                // 仅当当前值仍为本次设置的值时才回滚
                if (getCurrent() == newValue) {
                    onUpdate(!newValue)
                }
                showSnackbar("设置未生效（模块未激活或无权限）")
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        // ---- 模块设置 ----
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    "模块设置",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = OnSurfaceMutedColor,
                )
            }
        }
        item {
            SettingRow(
                title = "冻结方式",
                value = modeDisplayName,
                hint = "SystemAPI / CgroupV2 / SIGSTOP",
                showChevron = true,
                onClick = { showModeDialog = true },
            )
        }
        item {
            SettingRow(
                title = "冻结延迟",
                value = "${freezeDelay.toInt()} 秒",
                hint = "1-10 秒可调",
                showChevron = true,
                onClick = { showDelayDialog = true },
            )
        }
        item {
            val totalSec = rotationInterval.toInt()
            val mins = totalSec / 60
            val secs = totalSec % 60
            SettingRow(
                title = "轮番解冻间隔",
                value = if (secs == 0) "$mins 分钟" else "$mins 分 $secs 秒",
                hint = "60-3600 秒",
                showChevron = true,
                onClick = { showRotationDialog = true },
            )
        }
        item {
            SettingRow(
                title = "调试日志",
                value = if (debugEnabled) "开启" else "关闭",
                hint = "输出详细日志",
                onClick = {
                    debugInteracted = true
                    val newValue = !debugEnabled
                    debugEnabled = newValue
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            safeRunCatching { ServiceClient.setDebugEnabled(newValue) }.getOrDefault(false)
                        }
                        if (!ok) {
                            debugEnabled = !newValue
                            showSnackbar("设置未生效（模块未激活或无权限）")
                        }
                    }
                },
            )
        }

        // ---- 子 Hook 开关 ----
        item { Spacer(modifier = Modifier.size(8.dp)) }
        item {
            HookSwitchRow(
                title = "Activity 切换 Hook",
                checked = hookActivitySwitch,
            ) {
                toggleHook(3, hookActivitySwitch, { hookActivitySwitch }, { hookActivitySwitch = it }) {
                    hookActivitySwitchInteracted = true
                }
            }
        }
        item {
            HookSwitchRow(
                title = "锁屏批量冻结",
                checked = hookScreenState,
            ) {
                toggleHook(4, hookScreenState, { hookScreenState }, { hookScreenState = it }) {
                    hookScreenStateInteracted = true
                }
            }
        }
        item {
            HookSwitchRow(
                title = "广播拦截",
                checked = hookBroadcast,
            ) {
                toggleHook(1, hookBroadcast, { hookBroadcast }, { hookBroadcast = it }) {
                    hookBroadcastInteracted = true
                }
            }
        }
        item {
            HookSwitchRow(
                title = "WakeLock 拦截",
                checked = hookWakeLock,
            ) {
                toggleHook(2, hookWakeLock, { hookWakeLock }, { hookWakeLock = it }) {
                    hookWakeLockInteracted = true
                }
            }
        }
        item {
            HookSwitchRow(
                title = "ANR 拦截",
                checked = hookAnr,
            ) {
                toggleHook(0, hookAnr, { hookAnr }, { hookAnr = it }) {
                    hookAnrInteracted = true
                }
            }
        }

        // ---- 关于 ----
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    "关于",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = OnSurfaceMutedColor,
                )
            }
        }
        item {
            SettingRow(
                title = "版本",
                value = "v${BuildConfig.VERSION_NAME}",
                hint = "TombstoneX",
            )
        }
        item {
            SettingRow(
                title = "Android 版本",
                value = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            )
        }
        item {
            SettingRow(
                title = "设备",
                value = "${Build.MANUFACTURER} ${Build.MODEL}",
            )
        }
        item {
            val freezerAvailable = currentFreezerName != "未知" && currentFreezerName != "None"
            SettingRow(
                title = "冻结器",
                value = "$currentFreezerName · ${if (freezerAvailable) "可用" else "不可用"}",
            )
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
                                    color = OnSurfaceColor.copy(alpha = 0.6f),
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

    if (showDelayDialog) {
        AlertDialog(
            onDismissRequest = { showDelayDialog = false },
            title = { Text("冻结延迟") },
            text = {
                Column {
                    Text(
                        "${freezeDelay.toInt()} 秒",
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        color = PrimaryColor,
                    )
                    Spacer(modifier = Modifier.size(16.dp))
                    Slider(
                        value = freezeDelay,
                        onValueChange = {
                            freezeDelayInteracted = true
                            freezeDelay = it
                        },
                        onValueChangeFinished = {
                            freezeDelayInteracted = true
                            val newDelay = freezeDelay.toInt()
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    safeRunCatching { ServiceClient.setFreezeDelay(newDelay) }.getOrDefault(false)
                                }
                                if (ok) {
                                    committedFreezeDelay = newDelay.toFloat()
                                } else {
                                    freezeDelay = committedFreezeDelay
                                    showSnackbar("设置未生效（模块未激活或无权限）")
                                }
                            }
                        },
                        valueRange = 1f..10f,
                        steps = 8,
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryColor,
                            activeTrackColor = PrimaryColor,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDelayDialog = false }) { Text("确定") }
            },
        )
    }

    if (showRotationDialog) {
        AlertDialog(
            onDismissRequest = { showRotationDialog = false },
            title = { Text("轮番解冻间隔") },
            text = {
                Column {
                    val totalSec = rotationInterval.toInt()
                    val mins = totalSec / 60
                    val secs = totalSec % 60
                    Text(
                        if (secs == 0) "$mins 分钟" else "$mins 分 $secs 秒",
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        color = PrimaryColor,
                    )
                    Spacer(modifier = Modifier.size(16.dp))
                    Slider(
                        value = rotationInterval,
                        onValueChange = {
                            rotationInteracted = true
                            rotationInterval = it
                        },
                        onValueChangeFinished = {
                            rotationInteracted = true
                            val newInterval = rotationInterval.toInt()
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    safeRunCatching { ServiceClient.setRotationInterval(newInterval) }.getOrDefault(false)
                                }
                                if (ok) {
                                    committedRotationInterval = newInterval.toFloat()
                                } else {
                                    rotationInterval = committedRotationInterval
                                    showSnackbar("设置未生效（模块未激活或无权限）")
                                }
                            }
                        },
                        valueRange = 60f..3600f,
                        steps = 58,
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryColor,
                            activeTrackColor = PrimaryColor,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showRotationDialog = false }) { Text("确定") }
            },
        )
    }
}

@Immutable
data class ConfigLoadResult(
    val config: ServiceClient.ConfigSnapshot?,
    val freezerName: String,
)

@Composable
private fun SettingRow(
    title: String,
    value: String,
    hint: String? = null,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(14.dp),
        color = SurfaceColor,
        border = BorderStroke(1.dp, OutlineVariantColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = OnSurfaceColor,
                )
                if (hint != null) {
                    Text(
                        hint,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = OnSurfaceMutedColor,
                    )
                }
            }
            Text(
                value,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = PrimaryColor,
            )
            if (showChevron) {
                Text(
                    " \u203A",
                    color = OnSurfaceMutedColor,
                )
            }
        }
    }
}

@Composable
private fun HookSwitchRow(
    title: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = RoundedCornerShape(14.dp),
        color = SurfaceColor,
        border = BorderStroke(1.dp, OutlineVariantColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = OnSurfaceColor,
            )
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = PrimaryColor,
                    uncheckedTrackColor = Color(0xFF333138),
                    uncheckedBorderColor = OutlineVariantColor,
                ),
            )
        }
    }
}

private fun FreezeMode.displayLabel(): String = when (this) {
    FreezeMode.SYSTEM_API -> "SystemAPI"
    FreezeMode.CGROUP_V2 -> "CgroupV2"
    FreezeMode.CGROUP_V1 -> "CgroupV1"
    FreezeMode.SIGNAL_19 -> "SIGSTOP"
    FreezeMode.SIGNAL_20 -> "SIGTSTP"
}