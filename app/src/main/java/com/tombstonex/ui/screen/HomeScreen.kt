package com.tombstonex.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Size
import com.tombstonex.model.AppState
import com.tombstonex.provider.AppProvider
import com.tombstonex.service.ServiceClient
import com.tombstonex.ui.safeRunCatching
import com.tombstonex.ui.util.toImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 主页列表项：合并 [AppProvider.AppData] 与 [ServiceClient.ProcessInfo] 信息。
 * 图标不再随数据预加载，改由列表项内 [LaunchedEffect] 异步懒加载。
 */
@Immutable
data class HomeAppItem(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
    val pid: Int,
    val uid: Int,
    val state: AppState?,
    val isWhiteListed: Boolean,
)

/** 将 ServiceClient 返回的 state 整数映射为 AppState */
private fun Int.toAppState(): AppState? = when (this) {
    0 -> AppState.FOREGROUND
    1 -> AppState.BACKGROUND
    2 -> AppState.FROZEN
    3 -> AppState.KILLED
    else -> null
}

/**
 * 执行命令并带超时等待，使用并行线程消费 stdout/stderr 避免管道阻塞。
 * M2 修复：readText() 在 waitFor 之前会阻塞，改为并行消费模式（同 RootModuleInstaller.waitForProcess）。
 * R7-5 修复：超时后 destroyForcibly 未 join 读取线程，可能导致线程泄漏；添加 join 等待线程退出。
 * R7-6 修复：runRootCommandWithTimeout 与 runCommandWithTimeout 逻辑几乎完全相同，
 * 提取公共方法 runProcessWithTimeout 消除重复代码。
 */
private suspend fun runProcessWithTimeout(
    cmdArray: Array<String>,
    timeoutSec: Long = 5,
): Boolean = withContext(Dispatchers.IO) {
    val process = Runtime.getRuntime().exec(cmdArray)
    // M-45: 将 stdout/stderr 读取线程设为守护线程，避免 JVM 退出时被阻塞
    val stdoutThread = Thread {
        try { process.inputStream.bufferedReader().use { it.readText() } } catch (e: IOException) {
            // m-11: 记录管道关闭异常，避免静默丢失诊断信息
            android.util.Log.w("HomeScreen", "stdout read interrupted", e)
        }
    }.apply { isDaemon = true }
    val stderrThread = Thread {
        try { process.errorStream.bufferedReader().use { it.readText() } } catch (e: IOException) {
            // m-12: 记录管道关闭异常
            android.util.Log.w("HomeScreen", "stderr read interrupted", e)
        }
    }.apply { isDaemon = true }
    stdoutThread.start()
    stderrThread.start()
    val exited = process.waitFor(timeoutSec, TimeUnit.SECONDS)
    if (!exited) {
        // R7-5: destroyForcibly 后 join 读取线程，避免线程在管道阻塞状态下泄漏
        process.destroyForcibly()
        stdoutThread.join(1000)
        stderrThread.join(1000)
        false
    } else {
        stdoutThread.join(3000)
        stderrThread.join(3000)
        process.exitValue() == 0
    }
}

/**
 * 通过 root 执行命令并带超时等待。
 * 用于需要 su 权限的命令（如 reboot、setprop）。
 */
private suspend fun runRootCommandWithTimeout(cmd: String, timeoutSec: Long = 5): Boolean =
    runProcessWithTimeout(arrayOf("su", "-c", cmd), timeoutSec)

/**
 * 执行命令（非 root）并带超时等待。
 * 用于 su 不可用时的回退方案。
 */
private suspend fun runCommandWithTimeout(cmdArray: Array<String>, timeoutSec: Long = 5): Boolean =
    runProcessWithTimeout(cmdArray, timeoutSec)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(showSnackbar: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loadJob by remember { mutableStateOf<Job?>(null) }
    // L7 修复：refreshStates 任务跟踪，取消前序未完成的刷新，避免并发执行导致状态错乱
    var refreshJob by remember { mutableStateOf<Job?>(null) }

    var items by remember { mutableStateOf<List<HomeAppItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var includeSystem by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var moduleAvailable by remember { mutableStateOf(true) }
    var moduleLoaded by remember { mutableStateOf(false) }
    var moduleEnabled by remember { mutableStateOf(false) }
    var regStatus by remember { mutableStateOf("") }
    var showRebootDialog by remember { mutableStateOf(false) }
    // 应用级配置 BottomSheet 状态
    var showAppConfigSheet by remember { mutableStateOf(false) }
    var selectedAppForConfig by remember { mutableStateOf<HomeAppItem?>(null) }

    // 搜索防抖
    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedQuery = searchQuery
    }

    // 图标懒加载器：按包名从 PackageManager 异步获取图标
    val pm = context.packageManager
    // L3: 应用图标 LRU 缓存，避免滚动时重复解码
    // L1 修复：改为基于字节大小的 LruCache（3MB），避免大图标导致内存溢出
    val iconCache = remember {
        object : android.util.LruCache<String, ImageBitmap>(3 * 1024 * 1024) {  // 3MB
            // m-13: asAndroidBitmap() 每次调用都会创建临时 Bitmap 对象用于获取 byteCount，
            // 这是 LruCache.sizeOf() 驱动的已知开销。在 system_server 环境下可接受，
            // 若在其他内存受限环境中使用，可考虑缓存 byteCount 或改用 Bitmap 直接存储。
            override fun sizeOf(key: String, value: ImageBitmap): Int =
                value.asAndroidBitmap().byteCount
            // M3 修复：不手动 recycle Bitmap，依赖 GC 回收 native 内存。
            // 手动 recycle 会导致仍持有该 Bitmap 引用的 Composable 崩溃
            // （IllegalStateException: Cannot draw a recycled Bitmap）。
            // LruCache 驱逐后旧引用仅存在于缓存中，Compose 的 Image(bitmap=...) 已自行持有引用，
            // 由 GC 负责回收即可。
            override fun entryRemoved(
                evicted: Boolean,
                key: String,
                oldValue: ImageBitmap,
                newValue: ImageBitmap?,
            ) {
                // 不手动 recycle，依赖 GC 回收
            }
        }
    }
    val loadIcon: suspend (String) -> ImageBitmap? = remember(pm) {
        { pkg ->
            // 先查缓存
            val cached = iconCache.get(pkg)
            if (cached != null) {
                cached
            } else {
                withContext(Dispatchers.IO) {
                    safeRunCatching {
                        pm.getApplicationIcon(pkg).toImageBitmap(96)
                    }.getOrNull()?.also { bmp ->
                        iconCache.put(pkg, bmp)
                    }
                }
            }
        }
    }

    // 异步刷新进程状态（从 ServiceClient 获取）
    fun refreshStates() {
        // L7 修复：取消前序未完成的刷新任务，避免并发执行导致状态错乱
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val procList = withContext(Dispatchers.IO) {
                safeRunCatching { ServiceClient.getAllProcesses() }.getOrDefault(emptyList())
            }
            val procByPkg = procList.associateBy { it.packageName }
            // M3 修复：在 launch 内读取最新 items 合并而非替换快照；仅更新 pid/uid/state，
            // 保留当前 isWhiteListed。
            // M2 修复：进程不在新快照中时重置 pid/state 为未运行，避免保留过期的进程信息
            items = items.map { item ->
                val info = procByPkg[item.packageName]
                if (info != null) {
                    item.copy(
                        pid = info.pid,
                        uid = info.uid,
                        state = info.state.toAppState(),
                    )
                } else {
                    // 进程已消失，重置为未运行状态（保留 isWhiteListed 等其他字段）
                    item.copy(pid = -1, state = null)
                }
            }
        }
    }

    fun loadApps() {
        loadJob?.cancel()
        // M5 修复：同时取消进行中的刷新任务，避免刷新结果覆盖新加载结果
        refreshJob?.cancel()
        loadJob = scope.launch {
            try {
                val appProvider = AppProvider.getInstance(context)

                // 并行执行：模块状态检测（只读系统属性，快速）+ 批量 IPC + 应用列表加载
                val moduleDeferred = async(Dispatchers.IO) {
                    val e = safeRunCatching { ServiceClient.isModuleEnabled }.getOrDefault(false)
                    val l = safeRunCatching { ServiceClient.isModuleLoaded }.getOrDefault(false)
                    val a = safeRunCatching { ServiceClient.isAvailable }.getOrDefault(false)
                    val rs = safeRunCatching { ServiceClient.regStatus }.getOrDefault("")
                    Triple(e, l, a) to rs
                }
                // 批量 IPC：配置 + 白名单 + 进程列表合并为 1 次调用
                val dataDeferred = async(Dispatchers.IO) {
                    safeRunCatching { ServiceClient.getInitData() }.getOrNull()
                }
                val appsDeferred = async(Dispatchers.IO) {
                    appProvider.getAllApps(includeSystem, false)
                }

                val (triple, rs) = moduleDeferred.await()
                moduleEnabled = triple.first
                moduleLoaded = triple.second
                moduleAvailable = triple.third
                regStatus = rs

                val initData = dataDeferred.await()
                val whiteApps = initData?.whiteApps ?: emptySet()
                val procList = initData?.processes ?: emptyList()
                val allApps = appsDeferred.await()

                val procByPkg = procList.associateBy { it.packageName }
                items = allApps.map { app ->
                    val info = procByPkg[app.packageName]
                    HomeAppItem(
                        label = app.label,
                        packageName = app.packageName,
                        isSystem = app.isSystem,
                        pid = info?.pid ?: -1,
                        uid = info?.uid ?: -1,
                        state = info?.state?.toAppState(),
                        isWhiteListed = whiteApps.contains(app.packageName),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showSnackbar("加载应用列表失败：${e.message ?: "未知错误"}")
            } finally {
                if (loadJob == coroutineContext[Job]) {
                    loading = false
                    refreshing = false
                }
            }
        }
    }

    // 首次加载 / 系统应用过滤变化时重新加载
    LaunchedEffect(includeSystem) {
        loading = true
        loadApps()
    }

    // 定期刷新进程状态（每 5 秒从 ServiceClient 拉取最新状态）
    // 轻微-8 修复：模块不可用时直接 return，避免每 5 秒空转
    LaunchedEffect(moduleAvailable) {
        if (!moduleAvailable) return@LaunchedEffect
        while (true) {
            delay(5000)
            if (loading || items.isEmpty()) continue
            refreshStates()
        }
    }

    fun onFreezeClick(item: HomeAppItem) {
        if (item.pid <= 0) {
            showSnackbar("${item.label} 当前未运行，无法操作")
            return
        }
        val willFreeze = item.state != AppState.FROZEN
        // M-43: 先取消进行中的 refreshJob，避免刷新任务与本次修改产生竞态
        refreshJob?.cancel()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                safeRunCatching {
                    if (willFreeze) ServiceClient.freezeProcess(item.pid, item.uid)
                    else ServiceClient.unfreezeProcess(item.pid, item.uid)
                }.getOrDefault(false)
            }
            if (ok) {
                // M-43: 修改完成后触发 refreshStates() 拉取最新状态
                refreshStates()
                showSnackbar(
                    if (willFreeze) "已冻结：${item.label}"
                    else "已解冻：${item.label}"
                )
            } else {
                // R7-8 修复：操作失败时刷新状态，因为列表快照中的 pid 最多有 5 秒延迟，
                // 进程可能已重启导致 pid 过期。刷新后用户可基于最新状态重试。
                refreshStates()
                showSnackbar("操作失败（进程可能已重启，请重试）")
            }
        }
    }

    /**
     * 切换应用的冻结参与状态。
     * enabled=true: 参与自动冻结（从白名单移除）
     * enabled=false: 不参与冻结（加入白名单）
     *
     * 采用乐观更新：先更新 UI，IPC 失败后回滚。
     *
     * 中等-6 修复：记录 toggle 前的旧值，回滚时恢复旧值而非基于 enabled 反推，
     * 避免快速连续切换时回滚到错误状态。
     */
    fun onToggleFreeze(item: HomeAppItem, enabled: Boolean) {
        // 记录旧值用于回滚（避免快速连续切换时基于 enabled 反推错误）
        val oldValue = items.find { it.packageName == item.packageName }?.isWhiteListed ?: return
        // M-43: 先取消进行中的 refreshJob，避免刷新任务与本次修改产生竞态
        refreshJob?.cancel()
        // 乐观更新 UI
        items = items.map {
            if (it.packageName == item.packageName) it.copy(isWhiteListed = !enabled) else it
        }
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                safeRunCatching {
                    if (enabled) {
                        // 参与冻结 = 从白名单移除
                        ServiceClient.removeWhiteApp(item.packageName)
                    } else {
                        // 不冻结 = 加入白名单
                        ServiceClient.addWhiteApp(item.packageName)
                    }
                }.getOrDefault(false)
            }
            if (success) {
                // M-43: 修改完成后触发 refreshStates() 拉取最新状态
                refreshStates()
                showSnackbar(
                    if (enabled) "已开启冻结：${item.label}"
                    else "已加入白名单：${item.label}"
                )
            } else {
                // M6 修复：仅当当前值仍为本次乐观更新设置的值（!enabled）时才回滚，
                // 避免覆盖用户在此期间的后续操作
                val currentValue = items.find { it.packageName == item.packageName }?.isWhiteListed
                if (currentValue == !enabled) {
                    // 回滚到旧值（而非基于 enabled 反推）
                    items = items.map {
                        if (it.packageName == item.packageName) it.copy(isWhiteListed = oldValue) else it
                    }
                }
                showSnackbar("设置失败（模块未激活或无权限）")
            }
        }
    }

    val filtered = remember(items, debouncedQuery) {
        if (debouncedQuery.isBlank()) {
            items
        } else {
            items.filter {
                it.label.contains(debouncedQuery, ignoreCase = true) ||
                    it.packageName.contains(debouncedQuery, ignoreCase = true)
            }
        }
    }

    // 运行中：有进程在运行（pid > 0）且未冻结
    val runningCount = remember(items) { items.count { it.pid > 0 && it.state != AppState.FROZEN } }
    // 已冻结：进程被冻结（state=FROZEN）或开关打开但进程未运行（配置为参与冻结）
    val frozenCount = remember(items) {
        items.count { it.state == AppState.FROZEN || (!it.isWhiteListed && it.pid <= 0) }
    }
    val whiteCount = remember(items) { items.count { it.isWhiteListed } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // ---- 顶部栏 ----
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Logo: 36x36dp, 圆角 12dp, 青蓝色背景, 白色 "TX" 文字
                        Surface(
                            modifier = Modifier.size(36.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF00E5FF).copy(alpha = 0.2f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "TX",
                                    color = Color(0xFF003543),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "TombstoneX",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        // 状态芯片
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (moduleAvailable)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.errorContainer,
                            border = if (moduleAvailable)
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                            else
                                BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(
                                            if (moduleAvailable) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error,
                                            CircleShape,
                                        ),
                                )
                                Text(
                                    text = if (moduleAvailable) "已激活" else "未激活",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (moduleAvailable) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                // 模块未激活（Binder 服务不可用）时显示状态卡片
                if (!moduleAvailable) {
                    item {
                        ModuleNotActiveCard(
                            moduleEnabled = moduleEnabled,
                            moduleLoaded = moduleLoaded,
                            regStatus = regStatus,
                        )
                    }
                }

                if (!loading) {
                    item { StatsCard(runningCount, frozenCount, whiteCount) }

                    // ---- 搜索栏 + 系统应用 FilterChip ----
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("搜索应用名称或包名…") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = null,
                                    )
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "清除",
                                            )
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(24.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ),
                            )
                            FilterChip(
                                selected = includeSystem,
                                onClick = { includeSystem = !includeSystem },
                                label = { Text("系统应用") },
                                leadingIcon = if (includeSystem) {
                                    {
                                        Box(
                                            Modifier
                                                .size(7.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.primary,
                                                    CircleShape,
                                                ),
                                        )
                                    }
                                } else {
                                    null
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                                    enabled = true,
                                    selected = includeSystem,
                                ),
                            )
                        }
                    }

                    // ---- 应用列表标题 ----
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "应用列表",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${filtered.size} 个",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (filtered.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "未找到匹配的应用",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            }
                        }
                    } else {
                        items(filtered, key = { it.packageName }) { app ->
                            AppCard(
                                app,
                                loadIcon,
                                onFreezeClick = { onFreezeClick(it) },
                                onToggleFreeze = { item, enabled ->
                                    onToggleFreeze(item, enabled)
                                },
                                onConfigClick = { item ->
                                    selectedAppForConfig = item
                                    showAppConfigSheet = true
                                },
                            )
                        }
                    }
                }
            }

            // loading 状态作为 Box 同级覆盖层（避免在 LazyColumn item 内使用 fillMaxSize）
            if (loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "正在加载应用列表...",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }

        // 重启设备确认对话框
        if (showRebootDialog) {
            AlertDialog(
                onDismissRequest = { showRebootDialog = false },
                title = { Text("重启设备") },
                text = {
                    Text(
                        "确定要重启设备吗？\n\n" +
                            "模块未激活时，重启后 LSPosed 会重新加载模块到 system_server。\n" +
                            "请确保已在 LSPosed 中启用模块并勾选「Android 系统」作用域。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showRebootDialog = false
                        // M2 修复：使用并行线程消费 stdout/stderr，避免 readText() 在 waitFor 之前阻塞
                        scope.launch {
                            try {
                                runRootCommandWithTimeout("reboot")
                            } catch (e: Exception) {
                                // su 不可用时尝试普通 reboot（通常需要系统签名权限）
                                try {
                                    runCommandWithTimeout(arrayOf("reboot"))
                                } catch (e: Exception) {
                                    android.util.Log.w("TombstoneX", "重启失败", e)
                                }
                            }
                        }
                    }) {
                        Text("重启", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRebootDialog = false }) {
                        Text("取消")
                    }
                },
            )
        }

        // 应用级配置 BottomSheet
        if (showAppConfigSheet) {
            selectedAppForConfig?.let { app ->
                AppConfigSheet(
                    item = app,
                    onDismiss = {
                        showAppConfigSheet = false
                        selectedAppForConfig = null
                    },
                    showSnackbar = showSnackbar,
                )
            }
        }
    }
}

@Composable
private fun ModuleNotActiveCard(
    moduleEnabled: Boolean = false,
    moduleLoaded: Boolean = false,
    regStatus: String = "",
) {
    var showInstallDialog by remember { mutableStateOf(false) }
    var installResult by remember { mutableStateOf<com.tombstonex.util.RootModuleInstaller.InstallResult?>(null) }
    var isInstalling by remember { mutableStateOf(false) }
    var showRebootDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 模块已加载但 Binder 不可用 → 需要安装 SELinux 策略或重启
    val isBinderFailed = moduleEnabled && moduleLoaded
    val containerColor = MaterialTheme.colorScheme.errorContainer
    val contentColor = MaterialTheme.colorScheme.onErrorContainer

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isBinderFailed) Icons.Filled.Error else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = contentColor,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (isBinderFailed) "Binder 服务未就绪" else "模块未激活",
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
            }
            val statusText = when {
                !moduleEnabled -> "LSPosed 未启用模块\n请在 LSPosed 管理器中启用 TombstoneX 模块"
                !moduleLoaded -> "模块已启用，但未加载到系统框架\n请在 LSPosed 作用域中勾选「Android 系统」"
                isBinderFailed -> {
                    val base = "Binder 服务注册失败，需要安装 SELinux 策略模块。\n点击下方按钮一键安装（需 root），安装后重启设备生效。"
                    if (regStatus.isNotEmpty() && !regStatus.startsWith("ok") && !regStatus.startsWith("already")) {
                        "$base\n\n注册诊断: $regStatus"
                    } else {
                        base
                    }
                }
                else -> "模块已加载，通信通道未就绪，正在等待..."
            }
            Text(
                text = statusText,
                fontSize = 12.sp,
                color = contentColor.copy(alpha = 0.8f),
            )

            // 模块已加载但 Binder 失败时，显示一键安装 SELinux 策略按钮
            if (isBinderFailed) {
                Button(
                    onClick = {
                        showInstallDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = contentColor,
                        contentColor = containerColor,
                    ),
                ) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("一键安装 SELinux 策略")
                }
            }
        }
    }

    // 安装确认对话框
    if (showInstallDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isInstalling) showInstallDialog = false
            },
            title = { Text("安装 SELinux 策略") },
            text = {
                if (isInstalling) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("正在安装 SELinux 策略...")
                    }
                } else if (installResult != null) {
                    Text(installResult!!.message)
                } else {
                    Text(
                        "将使用 root 权限安装 SELinux 策略，允许 system_server 注册 Binder 服务。\n\n" +
                            "安装后会尝试立即生效，如果不行则需要重启设备。\n\n" +
                            "兼容 Magisk/KernelSU/APatch。",
                    )
                }
            },
            confirmButton = {
                if (installResult == null && !isInstalling) {
                    TextButton(onClick = {
                        isInstalling = true
                        scope.launch(Dispatchers.IO) {
                            val result = com.tombstonex.util.RootModuleInstaller.install()
                            installResult = result
                            isInstalling = false
                        }
                    }) { Text("安装") }
                } else if (installResult != null) {
                    TextButton(onClick = {
                        showInstallDialog = false
                        showRebootDialog = true
                    }) { Text("重启设备") }
                }
            },
            dismissButton = {
                if (installResult == null && !isInstalling) {
                    TextButton(onClick = { showInstallDialog = false }) { Text("取消") }
                } else if (installResult != null) {
                    TextButton(onClick = {
                        showInstallDialog = false
                        installResult = null
                    }) { Text("关闭") }
                }
            },
        )
    }

    // 重启确认对话框
    if (showRebootDialog) {
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            title = { Text("重启设备") },
            text = {
                Text("SELinux 策略模块已安装，但需要重启设备才能加载新的 SELinux 规则。\n\n重启后 Binder 服务将自动注册成功。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showRebootDialog = false
                    // M2 修复：使用并行线程消费 stdout/stderr，避免 readText() 在 waitFor 之前阻塞
                    scope.launch {
                        try {
                            runRootCommandWithTimeout("setprop sys.powerctl reboot")
                        } catch (e: Exception) {
                            android.util.Log.w("TombstoneX", "重启失败", e)
                        }
                    }
                }) { Text("重启") }
            },
            dismissButton = {
                TextButton(onClick = { showRebootDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun StatsCard(runningCount: Int, frozenCount: Int, whitelistCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatItem("运行中", runningCount, MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
        StatItem("已冻结", frozenCount, MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        StatItem("白名单", whitelistCount, MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        StatItem("总应用", runningCount + frozenCount, MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatItem(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.headlineLarge,
                color = if (count > 0) color else MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Serif,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.5.sp,
            )
        }
    }
}

@Composable
private fun AppCard(
    item: HomeAppItem,
    loadIcon: suspend (String) -> ImageBitmap?,
    onFreezeClick: (HomeAppItem) -> Unit,
    onToggleFreeze: (HomeAppItem, Boolean) -> Unit,
    onConfigClick: (HomeAppItem) -> Unit,
) {
    // 图标在列表项内异步懒加载
    var icon by remember(item.packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.packageName) {
        icon = loadIcon(item.packageName)
    }

    val accentColor = when (item.state) {
        AppState.FROZEN -> MaterialTheme.colorScheme.primary
        AppState.FOREGROUND, AppState.BACKGROUND -> MaterialTheme.colorScheme.secondary
        else -> Color.Transparent
    }
    val isFrozen = item.state == AppState.FROZEN

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .drawBehind {
                if (accentColor != Color.Transparent) {
                    drawRect(
                        color = accentColor,
                        size = Size(3.dp.toPx(), size.height),
                    )
                }
            },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isFrozen)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 应用图标
            val bmp = icon
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = item.label,
                    modifier = Modifier.size(40.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            Color(0xFF00E5FF).copy(alpha = if (isFrozen) 1f else 0.12f),
                            RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.label.firstOrNull()?.toString() ?: "?",
                        color = if (isFrozen) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                }
            }

            // 中间文字区
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.label,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (item.isWhiteListed) {
                        Text(
                            " ☆",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                        )
                    }
                }
                Text(
                    text = item.packageName + if (item.isSystem) " · 系统" else "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 状态标签 + PID
            Column(horizontalAlignment = Alignment.End) {
                StateBadge(item.state)
                if (item.pid > 0) {
                    Text(
                        text = "PID ${item.pid}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 冻结按钮
            IconButton(
                onClick = { onFreezeClick(item) },
                modifier = Modifier
                    .size(38.dp)
                    .background(
                        if (isFrozen) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                        RoundedCornerShape(10.dp),
                    )
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(10.dp),
                    ),
            ) {
                Text(
                    text = if (isFrozen) "\u2746" else "\u2745",
                    fontSize = 18.sp,
                )
            }
        }
    }
}

/**
 * 进程状态徽章
 */
@Composable
private fun StateBadge(state: AppState?) {
    val (text, color) = when (state) {
        AppState.FROZEN -> "已冻结" to MaterialTheme.colorScheme.primary
        AppState.FOREGROUND -> "前台" to MaterialTheme.colorScheme.secondary
        AppState.BACKGROUND -> "后台" to MaterialTheme.colorScheme.onSurfaceVariant
        AppState.KILLED -> "已终止" to MaterialTheme.colorScheme.error
        null -> "未运行" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.2f)),
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            color = color,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * 应用级配置 BottomSheet。
 *
 * 打开时异步加载该应用的 [ServiceClient.getAppConfig] 与 [ServiceClient.getAppPriority]；
 * 切换开关时立即保存（乐观更新），失败回滚。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppConfigSheet(
    item: HomeAppItem,
    onDismiss: () -> Unit,
    showSnackbar: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var configLoaded by remember { mutableStateOf(false) }
    // R7-4 修复：IPC 失败时不再静默用默认值填充，标记失败状态并提示用户
    var configLoadFailed by remember { mutableStateOf(false) }
    var playAllowed by remember { mutableStateOf(false) }
    var ongoingNotification by remember { mutableStateOf(false) }
    var netTransfer by remember { mutableStateOf(false) }
    var autoStartAllowed by remember { mutableStateOf(false) }
    var keepConnection by remember { mutableStateOf(false) }
    var priority by remember { mutableStateOf(1) }
    var backgroundLevel by remember { mutableStateOf(0) }

    // BottomSheet 打开时异步加载配置（批量获取配置+优先级，单次 IPC）
    // R7-4 修复：IPC 失败（返回 null）时不再用默认值填充，标记 configLoadFailed，
    // UI 中显示错误信息与重试按钮，避免用户误把默认值当作实际配置
    LaunchedEffect(item.packageName) {
        configLoaded = false
        configLoadFailed = false
        val full = withContext(Dispatchers.IO) {
            safeRunCatching { ServiceClient.getAppConfigFull(item.packageName) }.getOrNull()
        }
        if (full == null) {
            configLoadFailed = true
            configLoaded = true
            return@LaunchedEffect
        }
        configLoadFailed = false
        val cfg = full.config ?: JSONObject()
        playAllowed = cfg.optBoolean("playAllowed", false)
        ongoingNotification = cfg.optBoolean("ongoingNotification", false)
        netTransfer = cfg.optBoolean("netTransfer", false)
        autoStartAllowed = cfg.optBoolean("autoStartAllowed", false)
        keepConnection = cfg.optBoolean("keepConnection", false)
        backgroundLevel = cfg.optInt("backgroundLevel", 0)
        priority = full.priority
        configLoaded = true
    }

    /**
     * 重新加载应用配置（重试按钮触发）。
     * R7-4: 提供手动重试入口，避免用户必须关闭重开 BottomSheet 才能重新加载。
     */
    fun retryLoadConfig() {
        scope.launch {
            configLoaded = false
            configLoadFailed = false
            val full = withContext(Dispatchers.IO) {
                safeRunCatching { ServiceClient.getAppConfigFull(item.packageName) }.getOrNull()
            }
            if (full == null) {
                configLoadFailed = true
                configLoaded = true
                return@launch
            }
            configLoadFailed = false
            val cfg = full.config ?: JSONObject()
            playAllowed = cfg.optBoolean("playAllowed", false)
            ongoingNotification = cfg.optBoolean("ongoingNotification", false)
            netTransfer = cfg.optBoolean("netTransfer", false)
            autoStartAllowed = cfg.optBoolean("autoStartAllowed", false)
            keepConnection = cfg.optBoolean("keepConnection", false)
            backgroundLevel = cfg.optInt("backgroundLevel", 0)
            priority = full.priority
            configLoaded = true
        }
    }

    /**
     * 切换布尔配置项：乐观更新，失败回滚。
     * M6 修复：仅当当前值仍为本次设置的值时才回滚，避免覆盖用户在此期间的后续操作。
     */
    fun toggleConfig(
        key: String,
        current: Boolean,
        getCurrent: () -> Boolean,
        onUpdate: (Boolean) -> Unit,
    ) {
        val newValue = !current
        onUpdate(newValue)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                safeRunCatching {
                    ServiceClient.setAppConfigItem(item.packageName, key, newValue)
                }.getOrDefault(false)
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

    /** 设置优先级：乐观更新，失败回滚。 */
    fun setPriority(newPriority: Int) {
        val old = priority
        priority = newPriority
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                safeRunCatching {
                    ServiceClient.setAppPriority(item.packageName, newPriority)
                }.getOrDefault(false)
            }
            // M4 修复：仅当当前值仍为本次设置值时才回滚，避免覆盖用户在此期间的新选择
            if (!ok && priority == newPriority) {
                priority = old
                showSnackbar("设置未生效（模块未激活或无权限）")
            }
        }
    }

    /** 设置后台级别：乐观更新，失败回滚。 */
    fun setBackgroundLevel(newLevel: Int) {
        val old = backgroundLevel
        backgroundLevel = newLevel
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                safeRunCatching {
                    ServiceClient.setAppConfigItem(item.packageName, "backgroundLevel", newLevel)
                }.getOrDefault(false)
            }
            // M4 修复：仅当当前值仍为本次设置值时才回滚，避免覆盖用户在此期间的新选择
            if (!ok && backgroundLevel == newLevel) {
                backgroundLevel = old
                showSnackbar("设置未生效（模块未激活或无权限）")
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "应用配置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = item.label,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Text(
                text = item.packageName,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (!configLoaded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (configLoadFailed) {
                // R7-4: IPC 失败时显示错误信息与重试按钮，而非静默用默认值填充
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = "配置加载失败（模块未激活或无权限）",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { retryLoadConfig() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("重试")
                    }
                }
            } else {
                // ---- 开关配置项 ----
                ConfigSwitchRow(
                    title = "后台播放",
                    subtitle = "播放期间不冻结",
                    checked = playAllowed,
                ) { toggleConfig("playAllowed", playAllowed, { playAllowed }) { playAllowed = it } }
                ConfigSwitchRow(
                    title = "常驻通知",
                    subtitle = "通知常驻时不冻结",
                    checked = ongoingNotification,
                ) { toggleConfig("ongoingNotification", ongoingNotification, { ongoingNotification }) { ongoingNotification = it } }
                ConfigSwitchRow(
                    title = "网速识别",
                    subtitle = "后台上传/下载达到阈值时不冻结",
                    checked = netTransfer,
                ) { toggleConfig("netTransfer", netTransfer, { netTransfer }) { netTransfer = it } }
                ConfigSwitchRow(
                    title = "允许自启",
                    subtitle = "允许后台自启动",
                    checked = autoStartAllowed,
                ) { toggleConfig("autoStartAllowed", autoStartAllowed, { autoStartAllowed }) { autoStartAllowed = it } }
                ConfigSwitchRow(
                    title = "保持连接",
                    subtitle = "冻结后保持网络连接",
                    checked = keepConnection,
                ) { toggleConfig("keepConnection", keepConnection, { keepConnection }) { keepConnection = it } }

                Spacer(modifier = Modifier.height(8.dp))

                // ---- 优先级 ----
                Text(
                    text = "优先级",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                val priorities = listOf(
                    0 to "高",
                    1 to "中",
                    2 to "低",
                )
                priorities.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { setPriority(value) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = priority == value,
                            onClick = { setPriority(value) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ---- 后台级别 ----
                Text(
                    text = "后台级别",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                val levels = listOf(
                    0 to "前台服务",
                    1 to "可见窗口",
                    2 to "强制冻结",
                )
                levels.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { setBackgroundLevel(value) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = backgroundLevel == value,
                            onClick = { setBackgroundLevel(value) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * 应用配置项开关行（Column + Row 布局）。
 */
@Composable
private fun ConfigSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}