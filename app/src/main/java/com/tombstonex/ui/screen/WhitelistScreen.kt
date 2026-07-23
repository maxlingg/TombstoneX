package com.tombstonex.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- 主题色常量 ----
private val SurfaceColor = Color(0xFF1C1B1F)
private val Surface2Color = Color(0xFF242329)
private val PrimaryColor = Color(0xFF00E5FF)
private val PrimaryContainerColor = Color(0x1F00E5FF)
private val ErrorColor = Color(0xFFFF453A)
private val OnSurfaceVariantColor = Color(0xFFCAC4D0)
private val OnSurfaceMutedColor = Color(0xFF938F99)
private val OutlineVariantColor = Color(0xFF49454F)

/** 应用图标背景色调色板，根据包名确定性选取，模拟"应用自身颜色" */
private val AppIconPalette = listOf(
    Color(0xFF5E60CE),
    Color(0xFF4361EE),
    Color(0xFF4895EF),
    Color(0xFF4CC9F0),
    Color(0xFF7209B7),
    Color(0xFFF72585),
    Color(0xFFEF476F),
    Color(0xFFFF6B6B),
    Color(0xFFFF8C42),
    Color(0xFFFFD166),
    Color(0xFF06D6A0),
    Color(0xFF118AB2),
    Color(0xFF2D6A4F),
    Color(0xFF40916C),
)

/** 根据包名生成确定性的图标背景色，无匹配时回退到调色板首色（默认色） */
private fun colorForApp(packageName: String): Color {
    if (packageName.isBlank()) return AppIconPalette[0]
    return AppIconPalette[Math.floorMod(packageName.hashCode(), AppIconPalette.size)]
}

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

/** 分区标题 + Pill 标签页 */
@Composable
private fun SectionHeader(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    tabItemCount: Int,
) {
    // 标题行：左侧 "白名单管理" + 右侧 "N 个"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "白名单管理",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = OnSurfaceMutedColor,
        )
        Text(
            text = "$tabItemCount 个",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = OnSurfaceMutedColor,
        )
    }

    // Pill 标签页
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("应用白名单", "进程白名单", "系统服务").forEachIndexed { index, label ->
            val selected = selectedIndex == index
            FilterChip(
                selected = selected,
                onClick = { onSelect(index) },
                label = {
                    Text(
                        text = label,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.border(
                    width = 1.dp,
                    color = if (selected) PrimaryColor else OutlineVariantColor,
                    shape = RoundedCornerShape(24.dp),
                ),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Surface2Color,
                    selectedContainerColor = PrimaryContainerColor,
                    labelColor = OnSurfaceVariantColor,
                    selectedLabelColor = PrimaryColor,
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
            color = OnSurfaceVariantColor.copy(alpha = 0.2f),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "白名单为空",
            style = MaterialTheme.typography.titleMedium,
            color = OnSurfaceVariantColor,
        )
        Text(
            text = "白名单中的应用不会被冻结。",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceVariantColor,
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

    // 当前 Tab 下总条目数（用于分区标题右侧计数）
    val tabItemCount = when (selectedTab) {
        0 -> apps.count { whiteApps.contains(it.packageName) }
        1 -> processes.size
        else -> apps.count { it.isSystem }
    }

    Column(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        SectionHeader(
            selectedIndex = selectedTab,
            onSelect = { selectedTab = it },
            tabItemCount = tabItemCount,
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
            // Tab 0：已加入白名单的应用（仅"移除"操作）
            // Tab 1：运行进程名（来自 ServiceClient.getAllProcesses()）
            // Tab 2：系统应用
            when (selectedTab) {
                0 -> {
                    // 应用白名单 Tab：仅展示已加入白名单的应用，按钮始终为"移除"
                    val visibleApps = remember(apps, whiteApps) {
                        apps.filter { whiteApps.contains(it.packageName) }
                    }
                    if (visibleApps.isEmpty()) {
                        EmptyState()
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(visibleApps, key = { it.packageName }) { app ->
                                WhitelistAppCard(
                                    label = app.label,
                                    subtitle = app.packageName,
                                    isOn = true,
                                    onToggle = { toggleWhiteApp(app.packageName, true) },
                                )
                            }
                        }
                    }
                }
                1 -> {
                    if (processes.isEmpty()) {
                        EmptyState()
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(processes, key = { it.processName }) { proc ->
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
                    if (visibleApps.isEmpty()) {
                        EmptyState()
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(visibleApps, key = { it.packageName }) { app ->
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
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceColor,
        ),
        border = BorderStroke(1.dp, OutlineVariantColor),
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
                    .size(42.dp)
                    .background(
                        colorForApp(subtitle),
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label.firstOrNull()?.toString() ?: "?",
                    color = Color.White,
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
                    color = OnSurfaceVariantColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            OutlinedButton(
                onClick = { onToggle() },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = ErrorColor,
                ),
                border = BorderStroke(1.dp, ErrorColor.copy(alpha = 0.2f)),
                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (isOn) "移除" else "加入",
                    fontSize = 11.sp,
                )
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
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceColor,
        ),
        border = BorderStroke(1.dp, OutlineVariantColor),
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
                        PrimaryContainerColor,
                        RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ":",
                    color = PrimaryColor,
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
                    color = OnSurfaceVariantColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            TextButton(
                onClick = { onToggle() },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = ErrorColor,
                ),
            ) {
                Text(if (isOn) "移除" else "加入")
            }
        }
    }
}