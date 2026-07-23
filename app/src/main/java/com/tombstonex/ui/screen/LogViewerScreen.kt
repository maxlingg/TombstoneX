package com.tombstonex.ui.screen

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import com.tombstonex.service.ServiceClient
import com.tombstonex.ui.safeRunCatching
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- 主题色常量 ----
private val SurfaceColor = Color(0xFF1C1B1F)
private val Surface2Color = Color(0xFF242329)
private val PrimaryColor = Color(0xFF00E5FF)
private val SecondaryColor = Color(0xFFFFB347)
private val ErrorColor = Color(0xFFFF453A)
private val OnSurfaceColor = Color(0xFFE6E1E5)
private val OnSurfaceMutedColor = Color(0xFF938F99)
private val OutlineVariantColor = Color(0xFF49454F)

@Composable
fun LogViewerScreen(showSnackbar: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var moduleAvailable by remember { mutableStateOf(true) }
    var showClearDialog by remember { mutableStateOf(false) }
    // 轻微-11 修复：loadLogs 可并发执行导致状态错乱，使用 loadJob 取消前序任务
    var loadJob by remember { mutableStateOf<Job?>(null) }
    // L2 修复：clearLogs 跟踪自身 Job，避免并发清空导致状态错乱
    var clearJob by remember { mutableStateOf<Job?>(null) }

    fun loadLogs() {
        // 取消前序未完成的加载任务，避免并发执行导致状态错乱
        loadJob?.cancel()
        // L2 修复：同时取消进行中的清空任务，避免清空结果覆盖新加载结果
        clearJob?.cancel()
        loadJob = scope.launch {
            // S1 修复：try-finally 保证 loading 在协程被取消（如 clearLogs 调用 loadJob?.cancel()）时也能复位，
            // 避免取消后 loading = false 永不执行导致 UI 永久卡在加载态。
            try {
                loading = true
                errorMessage = null
                moduleAvailable = withContext(Dispatchers.IO) { safeRunCatching { ServiceClient.isAvailable }.getOrDefault(false) }
                if (!moduleAvailable) {
                    lines = emptyList()
                    errorMessage = "模块未激活，无法读取日志"
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    safeRunCatching { ServiceClient.readLog(5000) }.getOrDefault("")
                }
                lines = if (result.isBlank()) emptyList() else result.lines()
                if (lines.isEmpty()) errorMessage = "日志为空"
            } finally {
                // M1 修复：仅当当前 loadJob 仍为本协程时才复位 loading，
                // 避免被取消的旧协程 finally 覆盖新协程设置的 loading=true
                if (loadJob == coroutineContext[Job]) {
                    loading = false
                }
            }
        }
    }

    LaunchedEffect(Unit) { loadLogs() }

    // 自动滚动：仅当用户已在底部附近时才滚动到底部
    LaunchedEffect(lines.size) {
        if (lines.isEmpty()) return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val total = layoutInfo.totalItemsCount
        // 用户位于底部附近（最后 3 项以内）时才自动滚动
        if (total == 0 || lastVisibleIndex >= total - 3) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    fun clearLogs() {
        // L2 修复：取消前序未完成的清空任务，避免并发执行
        clearJob?.cancel()
        // 取消在途加载，防止加载完成后覆盖清空结果
        loadJob?.cancel()
        clearJob = scope.launch {
            val ok = withContext(Dispatchers.IO) {
                safeRunCatching { ServiceClient.clearLog() }.getOrDefault(false)
            }
            if (ok) {
                lines = emptyList()
                errorMessage = "日志为空"
                showSnackbar("日志已清空")
            } else {
                showSnackbar("清空失败（模块未激活或无权限）")
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "运行日志",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = OnSurfaceMutedColor,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { showClearDialog = true },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Surface2Color,
                    contentColor = OnSurfaceColor,
                ),
                border = BorderStroke(1.dp, OutlineVariantColor),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text("清空", fontSize = 11.sp)
            }
        }

        // 日志内容区
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(14.dp),
            color = SurfaceColor,
            border = BorderStroke(1.dp, OutlineVariantColor),
        ) {
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
                                color = if (!moduleAvailable)
                                    ErrorColor
                                else
                                    OnSurfaceColor.copy(alpha = 0.6f),
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
                        modifier = Modifier.padding(12.dp),
                        state = listState,
                    ) {
                        items(lines) { line ->
                            LogLine(line)
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
                }) { Text("清空", color = ErrorColor) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun LogLine(line: String) {
    val color = when {
        line.startsWith("[错误]") || line.startsWith("E/") -> ErrorColor
        line.startsWith("[警告]") || line.startsWith("W/") -> SecondaryColor
        line.startsWith("[信息]") || line.startsWith("I/") -> PrimaryColor
        line.startsWith("[调试]") || line.startsWith("D/") -> OnSurfaceMutedColor
        else -> OnSurfaceMutedColor
    }
    Text(
        text = line,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = color,
        lineHeight = 18.sp,
    )
}