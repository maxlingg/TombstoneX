package com.tombstonex.ui.screen

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WhitelistScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var allApps by remember { mutableStateOf<List<AppProvider.AppData>>(emptyList()) }
    var whiteApps by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }

    // 加载数据
    LaunchedEffect(Unit) {
        scope.launch {
            val appProvider = AppProvider.getInstance(context)
            allApps = withContext(Dispatchers.IO) {
                appProvider.getAllApps(true)
            }
            whiteApps = try {
                WhitelistManager.getInstance().getWhiteApps()
            } catch (e: Exception) { emptySet() }
            loading = false
        }
    }

    val filteredApps = if (searchQuery.isBlank()) {
        allApps
    } else {
        allApps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            placeholder = { Text("搜索应用") },
            singleLine = true
        )

        Text(
            text = "已添加白名单: ${whiteApps.size} 个应用",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

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
                    Text("正在加载应用列表...")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                items(filteredApps) { app ->
                    val isWhite = whiteApps.contains(app.packageName)
                    WhitelistAppCard(
                        label = app.label,
                        packageName = app.packageName,
                        isSystem = app.isSystem,
                        isWhiteListed = isWhite,
                        onToggle = {
                            scope.launch {
                                try {
                                    val mgr = WhitelistManager.getInstance()
                                    if (isWhite) {
                                        mgr.removeWhiteApp(app.packageName)
                                    } else {
                                        mgr.addWhiteApp(app.packageName)
                                    }
                                    whiteApps = mgr.getWhiteApps()
                                } catch (e: Exception) {
                                    // 配置目录可能无权限，仅更新 UI 状态
                                    whiteApps = if (isWhite) {
                                        whiteApps - app.packageName
                                    } else {
                                        whiteApps + app.packageName
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun WhitelistAppCard(
    label: String,
    packageName: String,
    isSystem: Boolean,
    isWhiteListed: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isWhiteListed)
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f)
            else
                MaterialTheme.colorScheme.surface
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
                    text = label,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = packageName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isSystem) {
                    Text("系统", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                }
                Switch(
                    checked = isWhiteListed,
                    onCheckedChange = { onToggle() }
                )
            }
        }
    }
}
