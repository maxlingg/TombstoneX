package com.tombstonex.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tombstonex.manager.WhitelistManager
import com.tombstonex.provider.AppProvider
import com.tombstonex.ui.screen.SettingsScreen
import com.tombstonex.ui.screen.WhitelistScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppListItem(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
    val isWhiteListed: Boolean,
    val willFreeze: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("应用列表", "白名单", "设置")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TombstoneX 墓碑") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTab) {
                0 -> AppListScreen()
                1 -> WhitelistScreen()
                2 -> SettingsScreen()
            }
        }
    }
}

@Composable
fun AppListScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppListItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var includeSystem by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // 加载应用列表
    LaunchedEffect(includeSystem) {
        loading = true
        scope.launch {
            val appProvider = AppProvider.getInstance(context)
            val whiteApps = try {
                WhitelistManager.getInstance().getWhiteApps()
            } catch (e: Exception) { emptySet() }

            val allApps = withContext(Dispatchers.IO) {
                appProvider.getAllApps(includeSystem)
            }

            apps = allApps.map { app ->
                val isWhite = whiteApps.contains(app.packageName)
                AppListItem(
                    label = app.label,
                    packageName = app.packageName,
                    isSystem = app.isSystem,
                    isWhiteListed = isWhite,
                    willFreeze = !isWhite && (!app.isSystem || false)
                )
            }
            loading = false
        }
    }

    val filteredApps = if (searchQuery.isBlank()) {
        apps
    } else {
        apps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    val frozenCount = apps.count { it.willFreeze }
    val whiteCount = apps.count { it.isWhiteListed }

    Column(modifier = Modifier.fillMaxSize()) {
        // 统计卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("总应用", apps.size.toString())
                StatItem("待冻结", frozenCount.toString())
                StatItem("白名单", whiteCount.toString())
            }
        }

        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("搜索应用名称或包名") },
            singleLine = true,
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    TextButton(onClick = { searchQuery = "" }) {
                        Text("清除")
                    }
                }
            }
        )

        // 系统应用过滤开关
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("显示系统应用", modifier = Modifier.weight(1f))
            Switch(
                checked = includeSystem,
                onCheckedChange = { includeSystem = it }
            )
        }

        // 应用列表
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("正在加载应用列表...", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        } else if (filteredApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("未找到应用", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(filteredApps) { app ->
                    AppListItemCard(app)
                }
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun AppListItemCard(app: AppListItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (app.isSystem) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("系统", fontSize = 10.sp) }
                    )
                }
                if (app.isWhiteListed) {
                    AssistChip(
                        onClick = {},
                        label = { Text("白名单", fontSize = 11.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                        )
                    )
                } else {
                    AssistChip(
                        onClick = {},
                        label = { Text("待冻结", fontSize = 11.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }
    }
}
