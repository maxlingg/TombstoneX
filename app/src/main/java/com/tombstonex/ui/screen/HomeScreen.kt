package com.tombstonex.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tombstonex.model.AppState
import com.tombstonex.provider.AppProvider
import com.tombstonex.service.ServiceClient
import com.tombstonex.ui.safeRunCatching
import com.tombstonex.ui.util.toImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(showSnackbar: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loadJob by remember { mutableStateOf<Job?>(null) }

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

    // 搜索防抖
    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedQuery = searchQuery
    }

    // 图标懒加载器：按包名从 PackageManager 异步获取图标
    val pm = context.packageManager
    val loadIcon: suspend (String) -> ImageBitmap? = remember(pm) {
        { pkg ->
            withContext(Dispatchers.IO) {
                safeRunCatching {
                    pm.getApplicationIcon(pkg).toImageBitmap(96)
                }.getOrNull()
            }
        }
    }

    // 异步刷新进程状态（从 ServiceClient 获取）
    fun refreshStates() {
        scope.launch {
            val procList = withContext(Dispatchers.IO) {
                safeRunCatching { ServiceClient.getAllProcesses() }.getOrDefault(emptyList())
            }
            val procByPkg = procList.associateBy { it.packageName }
            items = items.map { item ->
                val info = procByPkg[item.packageName]
                item.copy(state = info?.state?.toAppState())
            }
        }
    }

    fun loadApps() {
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                // 同时检测模块启用状态、加载状态和 Binder 服务可用性
                withContext(Dispatchers.IO) {
                    val e = safeRunCatching { ServiceClient.isModuleEnabled }.getOrDefault(false)
                    val l = safeRunCatching { ServiceClient.isModuleLoaded }.getOrDefault(false)
                    val a = safeRunCatching { ServiceClient.isAvailable }.getOrDefault(false)
                    val rs = safeRunCatching { ServiceClient.regStatus }.getOrDefault("")
                    Triple(e, l, a) to rs
                }.let { (triple, rs) ->
                    moduleEnabled = triple.first
                    moduleLoaded = triple.second
                    moduleAvailable = triple.third
                    regStatus = rs
                }
                val appProvider = AppProvider.getInstance(context)
                val whiteApps = withContext(Dispatchers.IO) {
                    safeRunCatching { ServiceClient.getWhiteApps() }.getOrDefault(emptySet())
                }
                val procList = withContext(Dispatchers.IO) {
                    safeRunCatching { ServiceClient.getAllProcesses() }.getOrDefault(emptyList())
                }
                val procByPkg = procList.associateBy { it.packageName }

                val loaded = withContext(Dispatchers.IO) {
                    // P3-R5: 传 loadIcon=false 跳过预加载图标，HomeScreen 自行懒加载
                    appProvider.getAllApps(includeSystem, false).map { app ->
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
                }
                items = loaded
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showSnackbar("加载应用列表失败：${e.message ?: "未知错误"}")
            } finally {
                // P2: 仅当当前 Job 仍为活跃 Job 时才重置 loading 状态，
                // 避免旧 Job 的 finally 覆盖新 Job 的 loading=true
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

    fun onFreezeClick(item: HomeAppItem) {
        if (item.pid <= 0) {
            showSnackbar("${item.label} 当前未运行，无法操作")
            return
        }
        val willFreeze = item.state != AppState.FROZEN
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                safeRunCatching {
                    if (willFreeze) ServiceClient.freezeProcess(item.pid, item.uid)
                    else ServiceClient.unfreezeProcess(item.pid, item.uid)
                }.getOrDefault(false)
            }
            if (ok) {
                refreshStates()
                showSnackbar(
                    if (willFreeze) "已冻结：${item.label}"
                    else "已解冻：${item.label}"
                )
            } else {
                showSnackbar("操作失败（模块未激活或无权限）")
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

    val runningCount = remember(items) { items.count { it.pid > 0 } }
    val frozenCount = remember(items) { items.count { it.state == AppState.FROZEN } }
    val whiteCount = remember(items) { items.count { it.isWhiteListed } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "TombstoneX",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "墓碑",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                },
                actions = {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else if (!moduleAvailable) {
                        // 模块未激活时显示重启按钮
                        IconButton(onClick = { showRebootDialog = true }) {
                            Icon(Icons.Filled.PowerSettingsNew, contentDescription = "重启设备")
                        }
                    } else {
                        IconButton(onClick = {
                            refreshing = true
                            loadApps()
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 模块未激活提示
                if (!moduleAvailable) {
                    item { ModuleNotActiveCard(moduleEnabled = moduleEnabled, moduleLoaded = moduleLoaded, regStatus = regStatus) }
                }

                if (!loading) {
                    item { StatsCard(runningCount, frozenCount, whiteCount) }

                    item {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("搜索应用名称或包名") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Filled.Close, contentDescription = "清除")
                                    }
                                }
                            },
                            singleLine = true,
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "显示系统应用",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Switch(
                                checked = includeSystem,
                                onCheckedChange = { includeSystem = it },
                            )
                        }
                    }

                    if (filtered.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillParentMaxSize(),
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
                            AppCard(app, loadIcon) { onFreezeClick(it) }
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
                        // 通过 root 权限执行 reboot
                        try {
                            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
                            process.waitFor()
                        } catch (e: Exception) {
                            // su 不可用时尝试普通 reboot（通常需要系统签名权限）
                            try {
                                Runtime.getRuntime().exec(arrayOf("reboot"))
                            } catch (_: Exception) {
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
    }
}

@Composable
private fun ModuleNotActiveCard(moduleEnabled: Boolean = false, moduleLoaded: Boolean = false, regStatus: String = "") {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "模块未激活",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                val statusText = when {
                    !moduleEnabled -> "LSPosed 未启用模块\n请在 LSPosed 管理器中启用 TombstoneX 模块\n点击右上角电源按钮可快速重启设备"
                    !moduleLoaded -> "模块已启用，但未加载到系统框架\n请在 LSPosed 作用域中勾选「Android 系统」\n点击右上角电源按钮可快速重启设备"
                    else -> {
                        val base = "模块已加载，但 Binder 服务注册失败\n请检查 LSPosed 日志或尝试重新启用模块\n点击右上角电源按钮可快速重启设备"
                        if (regStatus.isNotEmpty()) {
                            "$base\n\n注册诊断: $regStatus"
                        } else {
                            base
                        }
                    }
                }
                Text(
                    text = statusText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun StatsCard(running: Int, frozen: Int, white: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatItem("运行中", running.toString())
            VerticalDivider()
            StatItem("已冻结", frozen.toString())
            VerticalDivider()
            StatItem("白名单", white.toString())
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        modifier = Modifier
            .size(width = 1.dp, height = 32.dp)
            .background(
                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f)
            ),
    )
}

@Composable
private fun AppCard(
    item: HomeAppItem,
    loadIcon: suspend (String) -> ImageBitmap?,
    onFreezeClick: (HomeAppItem) -> Unit,
) {
    // 图标在列表项内异步懒加载
    var icon by remember(item.packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.packageName) {
        icon = loadIcon(item.packageName)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 应用图标
            if (icon != null) {
                Image(
                    bitmap = icon!!,
                    contentDescription = item.label,
                    modifier = Modifier.size(40.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.label.firstOrNull()?.toString() ?: "?",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.label,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = item.packageName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StateBadge(item.state)
                    if (item.isSystem) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("系统", fontSize = 10.sp) },
                        )
                    }
                    if (item.isWhiteListed) {
                        AssistChip(
                            onClick = {},
                            label = { Text("白名单", fontSize = 10.sp) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
                            ),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 立即冻结 / 解冻 按钮
            if (item.pid > 0) {
                val isFrozen = item.state == AppState.FROZEN
                FilledTonalButton(
                    onClick = { onFreezeClick(item) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    Icon(
                        imageVector = if (isFrozen) Icons.Filled.PlayArrow else Icons.Filled.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isFrozen) "解冻" else "冻结",
                        fontSize = 12.sp,
                    )
                }
            } else {
                Text(
                    text = "未运行",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
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
        AppState.FOREGROUND -> "前台" to MaterialTheme.colorScheme.tertiary
        AppState.BACKGROUND -> "后台" to MaterialTheme.colorScheme.secondary
        AppState.KILLED -> "已终止" to MaterialTheme.colorScheme.error
        null -> "未运行" to MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}
