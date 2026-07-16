package com.tombstonex.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tombstonex.ui.screen.SettingsScreen
import com.tombstonex.ui.screen.WhitelistScreen

data class AppListItem(
    val packageName: String,
    val pid: Int,
    val isFrozen: Boolean,
    val isWhiteListed: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("运行列表", "白名单", "设置")

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
                0 -> RunningListScreen()
                1 -> WhitelistScreen()
                2 -> SettingsScreen()
            }
        }
    }
}

@Composable
fun RunningListScreen() {
    val apps = remember {
        listOf(
            AppListItem("com.tencent.mm", 12345, true, false),
            AppListItem("com.tencent.mobileqq", 12346, false, false),
            AppListItem("com.android.settings", 12347, false, true),
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
                StatItem("总进程", "3")
                StatItem("已冻结", "1")
                StatItem("冻结模式", "SystemAPI")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            items(apps) { app ->
                AppListItemCard(app)
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
            containerColor = if (app.isFrozen)
                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
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
                    text = app.packageName,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "PID: ${app.pid}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (app.isWhiteListed) {
                    AssistChip(
                        onClick = {},
                        label = { Text("白名单", fontSize = 11.sp) }
                    )
                }
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            if (app.isFrozen) "已冻结" else "运行中",
                            fontSize = 11.sp
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (app.isFrozen)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        else
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                    )
                )
            }
        }
    }
}