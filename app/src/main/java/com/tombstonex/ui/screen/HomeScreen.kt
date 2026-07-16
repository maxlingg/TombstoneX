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
import androidx.compose.foundation.layout.fillParentMaxSize
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
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tombstonex.manager.FreezeManager
import com.tombstonex.manager.ProcessTracker
import com.tombstonex.manager.WhitelistManager
import com.tombstonex.model.AppState
import com.tombstonex.provider.AppProvider
import com.tombstonex.ui.util.toImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主页列表项：合并 [AppProvider.AppData] 与 [com.tombstonex.model.AppInfo] 信息
 */
data class HomeAppItem(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
    val icon: ImageBitmap?,
    val pid: Int,
    val uid: Int,
    val state: AppState?,
    val isWhiteListed: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(showSnackbar: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<HomeAppItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var includeSystem by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }

    // 搜索防抖
    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedQuery = searchQuery
    }

    // 重新读取 ProcessTracker 中的实时状态，刷新本地列表
    fun refreshStates() {
        val procMap = runCatching {
            ProcessTracker.getInstance().getAllProcesses()
        }.getOrDefault(emptyMap())
        val procByPkg = procMap.values.associateBy { it.packageName }
        items = items.map { it.copy(state = procByPkg[it.packageName]?.state) }
    }

    fun loadApps() {
        scope.launch {
            try {
                val appProvider = AppProvider.getInstance(context)
                val whiteApps = runCatching {
                    WhitelistManager.getInstance().getWhiteApps()
                }.getOrDefault(emptySet())
                val procMap = runCatching {
                    ProcessTracker.getInstance().getAllProcesses()
                }.getOrDefault(emptyMap())
                val procByPkg = procMap.values.associateBy { it.packageName }

                val loaded = withContext(Dispatchers.IO) {
                    appProvider.getAllApps(includeSystem).map { app ->
                        val info = procByPkg[app.packageName]
                        HomeAppItem(
                            label = app.label,
                            packageName = app.packageName,
                            isSystem = app.isSystem,
                            icon = runCatching { app.icon.toImageBitmap(96) }.getOrNull(),
                            pid = info?.pid ?: -1,
                            uid = info?.uid ?: -1,
                            state = info?.state,
                            isWhiteListed = whiteApps.contains(app.packageName),
                        )
                    }
                }
                items = loaded
            } catch (e: Exception) {
                showSnackbar("加载应用列表失败：${e.message ?: "未知错误"}")
            } finally {
                loading = false
                refreshing = false
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
                runCatching {
                    val mgr = FreezeManager.getInstance()
                    if (willFreeze) mgr.freezeProcess(item.pid, item.uid)
                    else mgr.unfreezeProcess(item.pid, item.uid)
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

    val filtered = if (debouncedQuery.isBlank()) {
        items
    } else {
        items.filter {
            it.label.contains(debouncedQuery, ignoreCase = true) ||
                it.packageName.contains(debouncedQuery, ignoreCase = true)
        }
    }

    val runningCount = items.count { it.pid > 0 }
    val frozenCount = items.count { it.state == AppState.FROZEN }
    val whiteCount = items.count { it.isWhiteListed }

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
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                loadApps()
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
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

                when {
                    loading -> {
                        item {
                            Box(
                                modifier = Modifier.fillParentMaxSize(),
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
                    filtered.isEmpty() -> {
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
                    }
                    else -> {
                        items(filtered, key = { it.packageName }) { app ->
                            AppCard(app, onFreezeClick)
                        }
                    }
                }
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
            StatItem("总进程", running.toString())
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
private fun AppCard(item: HomeAppItem, onFreezeClick: (HomeAppItem) -> Unit) {
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
            if (item.icon != null) {
                androidx.compose.foundation.Image(
                    bitmap = item.icon,
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
