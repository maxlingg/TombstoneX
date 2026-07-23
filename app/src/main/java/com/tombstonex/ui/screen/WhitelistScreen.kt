package com.tombstonex.ui.screen

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tombstonex.provider.AppProvider
import com.tombstonex.service.ServiceClient
import com.tombstonex.ui.safeRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/** 自定义 pill 标签页 */
@Composable
private fun PillTabs(selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("应用白名单", "进程白名单", "系统冻结名单").forEachIndexed { index, label ->
            val selected = selectedIndex == index
            FilterChip(
                selected = selected,
                onClick = { onSelect(index) },
                label = {
                    Text(
                        text = label,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                },
                shape = RoundedCornerShape(24.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                    enabled = true,
                    selected = selected,
                ),
            )
        }
    }
}

/** 空状态 */
@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "\u2606",
            fontSize = 40.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "白名单为空",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "白名单中的应用不会被冻结。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun WhitelistScreen(showSnackbar: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<WhitelistAppItem>>(emptyList()) }
    var processes by remember { mutableStateOf<List<ProcessDisplayItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }

    // 三类名单的当前条目集合
    var whiteApps by remember { mutableStateOf<Set<String>>(emptySet()) }
    var whiteProcesses by remember { mutableStateOf<Set<String>>(emptySet()) }
    var blackSystem by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 异步从 ServiceClient 重新读取三类名单
    // M8 修复：改为 suspend 函数，在 LaunchedEffect 中 await，
    // 避免异步执行覆盖用户在此期间的乐观更新
    suspend fun reloadSets() {
        val (wa, wp, bs) = withContext(Dispatchers.IO) {
            Triple(
                safeRunCatching { ServiceClient.getWhiteApps() }.getOrNull(),
                safeRunCatching { ServiceClient.getWhiteProcesses() }.getOrNull(),
                safeRunCatching { ServiceClient.getBlackSystemApps() }.getOrNull(),
            )
        }
        // M25: 仅在 IPC 成功时更新对应集合，失败时保留旧值，避免清空本地白名单状态
        if (wa != null) whiteApps = wa
        if (wp != null) whiteProcesses = wp
        if (bs != null) blackSystem = bs
    }

    // 搜索防抖：延迟 300ms 后同步 debouncedQuery，避免每次按键都触发过滤
    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedQuery = searchQuery
    }

    LaunchedEffect(Unit) {
        try {
            val provider = AppProvider.getInstance(context)
            val (loadedApps, loadedProcs) = withContext(Dispatchers.IO) {
                // P3-R5: 传 loadIcon=false 跳过预加载图标，WhitelistScreen 不使用图标
                val a = provider.getAllApps(true, false).map { app ->
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
        // P2: 乐观更新 UI，失败时回滚
        whiteApps = if (currentlyOn) whiteApps - pkg else whiteApps + pkg
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                safeRunCatching {
                    if (currentlyOn) ServiceClient.removeWhiteApp(pkg)
                    else ServiceClient.addWhiteApp(pkg)
                }.getOrDefault(false)
            }
            // M5 修复：移除 reloadSets()，依赖乐观更新 + 失败回滚，避免整体替换集合丢弃其他待定乐观更新
            if (!ok) {
                // M6 修复：仅当当前状态仍为本次乐观更新设置的状态时才回滚，避免覆盖用户后续操作
                val currentContains = whiteApps.contains(pkg)
                if (currentContains != currentlyOn) {
                    whiteApps = if (currentlyOn) whiteApps + pkg else whiteApps - pkg
                }
                showSnackbar("操作失败（模块未激活或无权限）")
            }
        }
    }

    fun toggleWhiteProcess(proc: String, currentlyOn: Boolean) {
        // P2: 乐观更新 UI，失败时回滚
        whiteProcesses = if (currentlyOn) whiteProcesses - proc else whiteProcesses + proc
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                safeRunCatching {
                    if (currentlyOn) ServiceClient.removeWhiteProcess(proc)
                    else ServiceClient.addWhiteProcess(proc)
                }.getOrDefault(false)
            }
            // M5 修复：移除 reloadSets()，依赖乐观更新 + 失败回滚，避免整体替换集合丢弃其他待定乐观更新
            if (!ok) {
                // M6 修复：仅当当前状态仍为本次乐观更新设置的状态时才回滚，避免覆盖用户后续操作
                val currentContains = whiteProcesses.contains(proc)
                if (currentContains != currentlyOn) {
                    whiteProcesses = if (currentlyOn) whiteProcesses + proc else whiteProcesses - proc
                }
                showSnackbar("操作失败（模块未激活或无权限）")
            }
        }
    }

    fun toggleBlackSystem(pkg: String, currentlyOn: Boolean) {
        // P2: 乐观更新 UI，失败时回滚
        blackSystem = if (currentlyOn) blackSystem - pkg else blackSystem + pkg
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                safeRunCatching {
                    if (currentlyOn) ServiceClient.removeBlackSystemApp(pkg)
                    else ServiceClient.addBlackSystemApp(pkg)
                }.getOrDefault(false)
            }
            // M5 修复：移除 reloadSets()，依赖乐观更新 + 失败回滚，避免整体替换集合丢弃其他待定乐观更新
            if (!ok) {
                // M6 修复：仅当当前状态仍为本次乐观更新设置的状态时才回滚，避免覆盖用户后续操作
                val currentContains = blackSystem.contains(pkg)
                if (currentContains != currentlyOn) {
                    blackSystem = if (currentlyOn) blackSystem + pkg else blackSystem - pkg
                }
                showSnackbar("操作失败（模块未激活或无权限）")
            }
        }
    }

    Column(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            PillTabs(selectedIndex = selectedTab, onSelect = { selectedTab = it })

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
                        val filtered = remember(visibleApps, debouncedQuery) {
                            if (debouncedQuery.isBlank()) visibleApps else {
                                visibleApps.filter {
                                    it.label.contains(debouncedQuery, ignoreCase = true) ||
                                        it.packageName.contains(debouncedQuery, ignoreCase = true)
                                }
                            }
                        }
                        if (filtered.isEmpty()) {
                            EmptyState()
                        } else {
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
                    }
                    1 -> {
                        val filtered = remember(processes, debouncedQuery) {
                            if (debouncedQuery.isBlank()) processes else {
                                processes.filter {
                                    it.processName.contains(debouncedQuery, ignoreCase = true) ||
                                        it.packageName.contains(debouncedQuery, ignoreCase = true)
                                }
                            }
                        }
                        if (filtered.isEmpty()) {
                            EmptyState()
                        } else {
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
                    }
                    else -> {
                        val visibleApps = remember(apps) { apps.filter { it.isSystem } }
                        val filtered = remember(visibleApps, debouncedQuery) {
                            if (debouncedQuery.isBlank()) visibleApps else {
                                visibleApps.filter {
                                    it.label.contains(debouncedQuery, ignoreCase = true) ||
                                        it.packageName.contains(debouncedQuery, ignoreCase = true)
                                }
                            }
                        }
                        if (filtered.isEmpty()) {
                            EmptyState()
                        } else {
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
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isOn)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label.firstOrNull()?.toString() ?: "?",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }

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
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            OutlinedButton(
                onClick = { onToggle() },
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(if (isOn) "移除" else "加入")
            }
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
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isOn)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                        RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ":",
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }

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
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            OutlinedButton(
                onClick = { onToggle() },
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(if (isOn) "移除" else "加入")
            }
        }
    }
}
