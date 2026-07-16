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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tombstonex.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(showSnackbar: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    fun loadLogs() {
        scope.launch {
            loading = true
            errorMessage = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(BuildConfig.LOG_PATH)
                    if (!file.exists()) return@runCatching emptyList<String>()
                    val all = file.readLines()
                    // 限制最大行数，避免内存溢出
                    if (all.size > 5000) all.takeLast(5000) else all
                }
            }
            result.fold(
                onSuccess = {
                    lines = it
                    if (it.isEmpty()) errorMessage = "日志文件为空或不存在"
                },
                onFailure = { e ->
                    lines = emptyList()
                    errorMessage = "无法读取日志文件：${e.message ?: "权限不足"}"
                },
            )
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadLogs() }

    // 自动滚动到底部
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    fun clearLogs() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    File(BuildConfig.LOG_PATH).writeText("")
                    true
                }.getOrDefault(false)
            }
            if (ok) {
                lines = emptyList()
                showSnackbar("日志已清空")
            } else {
                showSnackbar("清空失败：权限不足")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行日志", fontFamily = FontFamily.Monospace) },
                actions = {
                    IconButton(onClick = { loadLogs() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "清空日志")
                    }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = BuildConfig.LOG_PATH,
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Text(
                    text = "${lines.size} 行",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            when {
                loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("正在读取日志...")
                        }
                    }
                }
                errorMessage != null && lines.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = errorMessage ?: "",
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            FilledTonalButton(onClick = { loadLogs() }) {
                                Icon(Icons.Filled.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("重新加载")
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(lines) { line ->
                            LogLineView(line)
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空日志") },
            text = { Text("确定要清空当前日志文件吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    clearLogs()
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun LogLineView(line: String) {
    val color = when {
        line.contains("][E]") -> Color(0xFFEF5350)       // 红
        line.contains("][W]") -> Color(0xFFFFA726)       // 橙
        line.contains("][I]") -> MaterialTheme.colorScheme.primary // 蓝
        line.contains("][D]") -> Color(0xFF9E9E9E)      // 灰
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color.copy(alpha = 0.06f),
                RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = line,
            color = color,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp,
        )
    }
}
