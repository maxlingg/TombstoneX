package com.tombstonex.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tombstonex.provider.AppProvider
import com.tombstonex.service.ServiceClient
import com.tombstonex.ui.safeRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 白名单页面渲染所用的应用条目 */
@Immutable
private data class WhitelistAppItem(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
)

/** 进程白名单 Tab 展示的运行进程条目 */
@Immutable
private data class ProcessDisplayItem(
    val processName: String,
    val packageName: String,
    val pid: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhitelistScreen(showSnackbar: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<WhitelistAppItem>>(emptyList()) }
    var processes by remember { mutableStateOf<List<ProcessDisplayItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }

    // 三类名单的当前条目集合
    var whiteApps by remember { mutableStateOf<Set<String>>(emptySet()) }
    var whiteProcesses by remember { mutableStateOf<Set<String>>(emptySet()) }
    var blackSystem by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 异步从 ServiceClient 重新读取三类名单
    fun reloadSets() {
        scope.launch {
            val (wa, wp, bs) = withContext(Dispatchers.IO) {
                Triple(
                    safeRunCatching { ServiceClient.getWhiteApps() }.getOrDefault(emptySet()),
                    safeRunCatching { ServiceClient.getWhiteProcesses() }.getOrDefault(emptySet()),
                    safeRunCatching { ServiceClient.getBlackSystemApps() }.getOrDefault(emptySet()),
                )
            }
            whiteApps = wa
            whiteProcesses = wp
            blackSystem = bs
        }
    }

    LaunchedEffect(Unit) {
        try {
            val provider = AppProvider.getInstance(context)
            val (loadedApps, loadedProcs) = withContext(Dispatchers.IO) {
                val a = provider.getAllApps(true).map { app ->
                    WhitelistAppItem(
                        label = app.label,
                        packageName = app.packageName,
                        isSystem = app.isSystem,
                    )
                }
                // 进程白名单 Tab 展示运行中的进程名（如 com.foo:pushservice）
                val p = safeRunCatching { ServiceClient.getAllProcesses() }
                    .getOrDefault(emptyList())
                    .filter { it.processName.isNotBlank() }
                    .map { proc ->
                        ProcessDisplayItem(
                            processName = proc.processName,
                            packageName = proc.packageName,
                            pid = proc.pid,
                        )
                    }
                    .distinctBy { it.processName }
                Pair(a, p)
            }
            apps = loadedApps
            processes = loadedProcs
            reloadSets()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            showSnackbar("加载列表失败：${e.message ?: "未知错误"}")
        } finally {
            loading = false
        }
    }

    /**
     * 切换某条目在指定名单中的状态，通过 ServiceClient 写入。
     * 失败（模块未激活或无权限）时通过 Snackbar 提示。
     */
    fun toggleWhiteApp(pkg: String, currentlyOn: Boolean) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                safeRunCatching {
                    if (currentlyOn) ServiceClient.removeWhiteApp(pkg)
                    else ServiceClient.addWhiteApp(pkg)
                }.getOrDefault(false)
            }
            if (ok) reloadSets()
            else showSnackbar("操作失败（模块未激活或无权限）")
        }
    }

    fun toggleWhiteProcess(proc: String, currentlyOn: Boolean) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                safeRunCatching {
                    if (currentlyOn) ServiceClient.removeWhiteProcess(proc)
                    else ServiceClient.addWhiteProcess(proc)
                }.getOrDefault(false)
            }
            if (ok) reloadSets()
            else showSnackbar("操作失败（模块未激活或无权限）")
        }
    }

    fun toggleBlackSystem(pkg: String, currentlyOn: Boolean) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                safeRunCatching {
                    if (currentlyOn) ServiceClient.removeBlackSystemApp(pkg)
                    else ServiceClient.addBlackSystemApp(pkg)
                }.getOrDefault(false)
            }
            if (ok) reloadSets()
            else showSnackbar("操作失败（模块未激活或无权限）")
        }
    }

    val tabs = listOf("应用白名单", "进程白名单", "系统冻结名单")

    Scaffold(
        topBar = { TopAppBar(title = { Text("白名单管理", fontWeight = FontWeight.SemiBold) }) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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

            if (loading) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("正在加载列表...")
                    }
                }
            } else {
                // 各 Tab 对应的数据与名单集合
                // Tab 0：仅非系统应用（系统应用在 Tab 2 管理）
                // Tab 1：运行进程名（来自 ServiceClient.getAllProcesses()）
                // Tab 2：系统应用
                when (selectedTab) {
                    0 -> {
                        val visibleApps = remember(apps) { apps.filter { !it.isSystem } }
                        val filtered = remember(visibleApps, searchQuery) {
                            if (searchQuery.isBlank()) visibleApps else {
                                visibleApps.filter {
                                    it.label.contains(searchQuery, ignoreCase = true) ||
                                        it.packageName.contains(searchQuery, ignoreCase = true)
                                }
                            }
                        }
                        Text(
                            text = "已加入应用白名单：${whiteApps.size} 个",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(filtered, key = { it.packageName }) { app ->
                                val isOn = whiteApps.contains(app.packageName)
                                WhitelistAppCard(
                                    label = app.label,
                                    subtitle = app.packageName,
                                    isOn = isOn,
                                    onToggle = { toggleWhiteApp(app.packageName, isOn) },
                                )
                            }
                        }
                    }
                    1 -> {
                        val filtered = remember(processes, searchQuery) {
                            if (searchQuery.isBlank()) processes else {
                                processes.filter {
                                    it.processName.contains(searchQuery, ignoreCase = true) ||
                                        it.packageName.contains(searchQuery, ignoreCase = true)
                                }
                            }
                        }
                        Text(
                            text = "已加入进程白名单：${whiteProcesses.size} 个",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(filtered, key = { it.processName }) { proc ->
                                val isOn = whiteProcesses.contains(proc.processName)
                                WhitelistProcessCard(
                                    processName = proc.processName,
                                    packageName = proc.packageName,
                                    pid = proc.pid,
                                    isOn = isOn,
                                    onToggle = { toggleWhiteProcess(proc.processName, isOn) },
                                )
                            }
                        }
                    }
                    else -> {
                        val visibleApps = remember(apps) { apps.filter { it.isSystem } }
                        val filtered = remember(visibleApps, searchQuery) {
                            if (searchQuery.isBlank()) visibleApps else {
                                visibleApps.filter {
                                    it.label.contains(searchQuery, ignoreCase = true) ||
                                        it.packageName.contains(searchQuery, ignoreCase = true)
                                }
                            }
                        }
                        Text(
                            text = "系统应用冻结名单：${blackSystem.size} 个",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(filtered, key = { it.packageName }) { app ->
                                val isOn = blackSystem.contains(app.packageName)
                                WhitelistAppCard(
                                    label = app.label,
                                    subtitle = app.packageName,
                                    isOn = isOn,
                                    onToggle = { toggleBlackSystem(app.packageName, isOn) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WhitelistAppCard(
    label: String,
    subtitle: String,
    isOn: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isOn)
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)
            else
                MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label.firstOrNull()?.toString() ?: "?",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Switch(checked = isOn, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun WhitelistProcessCard(
    processName: String,
    packageName: String,
    pid: Int,
    isOn: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isOn)
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)
            else
                MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ":",
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = processName,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "$packageName (pid=$pid)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Switch(checked = isOn, onCheckedChange = { onToggle() })
        }
    }
}
