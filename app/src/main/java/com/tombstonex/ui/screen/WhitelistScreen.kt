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
import androidx.compose.material.icons.filled.Search
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
import com.tombstonex.manager.WhitelistManager
import com.tombstonex.provider.AppProvider
import com.tombstonex.ui.util.toImageBitmap
import com.tombstonex.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 白名单页面渲染所用的应用条目 */
private data class WhitelistAppItem(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
    val icon: ImageBitmap?,
)

/** 三个白名单配置文件 */
private object WhitelistFiles {
    const val APPS = "whiteApp.conf"
    const val PROCESSES = "whiteProcess.conf"
    const val SYSTEM_BLACK = "blackSystemApp.conf"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhitelistScreen(showSnackbar: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<WhitelistAppItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }

    // 三类名单的当前条目集合
    var whiteApps by remember { mutableStateOf<Set<String>>(emptySet()) }
    var whiteProcesses by remember { mutableStateOf<Set<String>>(emptySet()) }
    var blackSystem by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun reloadSets() {
        whiteApps = runCatching { WhitelistManager.getInstance().getWhiteApps() }.getOrDefault(emptySet())
        whiteProcesses = runCatching { FileUtils.readLines(WhitelistFiles.PROCESSES) }.getOrDefault(emptySet())
        blackSystem = runCatching { WhitelistManager.getInstance().getBlackSystemApps() }.getOrDefault(emptySet())
    }

    LaunchedEffect(Unit) {
        try {
            val provider = AppProvider.getInstance(context)
            val loaded = withContext(Dispatchers.IO) {
                provider.getAllApps(true).map { app ->
                    WhitelistAppItem(
                        label = app.label,
                        packageName = app.packageName,
                        isSystem = app.isSystem,
                        icon = runCatching { app.icon.toImageBitmap(96) }.getOrNull(),
                    )
                }
            }
            apps = loaded
            reloadSets()
        } catch (e: Exception) {
            showSnackbar("加载应用列表失败：${e.message ?: "未知错误"}")
        } finally {
            loading = false
        }
    }

    /**
     * 切换某条目在指定配置文件中的状态，写后回读校验是否真正生效。
     * 失败（例如配置目录无写权限）时通过 Snackbar 提示。
     */
    fun toggleEntry(file: String, key: String, currentlyOn: Boolean, onDone: (Boolean) -> Unit) {
        scope.launch {
            val changed = withContext(Dispatchers.IO) {
                runCatching {
                    val current = FileUtils.readLines(file)
                    val updated = if (currentlyOn) current - key else current + key
                    FileUtils.writeLines(file, updated)
                    // 同步 WhitelistManager 内存缓存
                    runCatching { WhitelistManager.getInstance().reload() }
                    // 回读校验
                    val verify = FileUtils.readLines(file)
                    verify.contains(key) != currentlyOn
                }.getOrDefault(false)
            }
            if (changed) {
                reloadSets()
                onDone(true)
            } else {
                showSnackbar("操作失败：无法写入配置（目录可能无写权限）")
                onDone(false)
            }
        }
    }

    val tabs = listOf("应用白名单", "进程白名单", "系统冻结名单")

    Scaffold(
        topBar = { TopAppBar(title = { Text("白名单管理", fontWeight = FontWeight.SemiBold) }) },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
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

            // 各 Tab 对应的数据
            val (visibleApps, onSet, countLabel) = when (selectedTab) {
                0 -> Triple(
                    apps.filter { !it.isSystem || it.packageName in whiteApps },
                    whiteApps,
                    "已加入应用白名单：${whiteApps.size} 个",
                )
                1 -> Triple(
                    apps,
                    whiteProcesses,
                    "已加入进程白名单：${whiteProcesses.size} 个",
                )
                else -> Triple(
                    apps.filter { it.isSystem },
                    blackSystem,
                    "系统应用冻结名单：${blackSystem.size} 个",
                )
            }

            val filtered = if (searchQuery.isBlank()) visibleApps else {
                visibleApps.filter {
                    it.label.contains(searchQuery, ignoreCase = true) ||
                        it.packageName.contains(searchQuery, ignoreCase = true)
                }
            }

            Text(
                text = countLabel,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
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
                        Text("正在加载应用列表...")
                    }
                }
            } else {
                val file = when (selectedTab) {
                    0 -> WhitelistFiles.APPS
                    1 -> WhitelistFiles.PROCESSES
                    else -> WhitelistFiles.SYSTEM_BLACK
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        val isOn = onSet.contains(app.packageName)
                        WhitelistAppCard(
                            app = app,
                            isOn = isOn,
                            onToggle = {
                                toggleEntry(file, app.packageName, isOn) {}
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WhitelistAppCard(
    app: WhitelistAppItem,
    isOn: Boolean,
    onToggle: () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
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
            if (app.icon != null) {
                androidx.compose.foundation.Image(
                    bitmap = app.icon,
                    contentDescription = app.label,
                    modifier = Modifier.size(36.dp),
                )
            } else {
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
                        text = app.label.firstOrNull()?.toString() ?: "?",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = app.packageName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (app.isSystem) {
                Text(
                    text = "系统",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(end = 8.dp),
                )
            }

            Switch(checked = isOn, onCheckedChange = { onToggle() })
        }
    }
}
