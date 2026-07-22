package com.tombstonex.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.tombstonex.ui.screen.AboutScreen
import com.tombstonex.ui.screen.HomeScreen
import com.tombstonex.ui.screen.LogViewerScreen
import com.tombstonex.ui.screen.SettingsScreen
import com.tombstonex.ui.screen.WhitelistScreen
import kotlinx.coroutines.launch

/**
 * 顶层导航目的地
 */
sealed class TxDestination(val route: String, val label: String, val icon: ImageVector) {
    object Home : TxDestination("home", "首页", Icons.Filled.Home)
    object Whitelist : TxDestination("whitelist", "白名单", Icons.Filled.Star)
    object Settings : TxDestination("settings", "设置", Icons.Filled.Settings)
    object Logs : TxDestination("logs", "日志", Icons.Filled.List)
    object About : TxDestination("about", "关于", Icons.Filled.Info)
}

/** 底部导航栏展示的 Tab 列表 */
private val BottomTabs = listOf(
    TxDestination.Home,
    TxDestination.Whitelist,
    TxDestination.Settings,
    TxDestination.Logs,
)

/**
 * 极简导航控制器（基于状态列表的回退栈）
 *
 * 不引入 navigation-compose 依赖，使用 SnapshotStateList 实现可观察的页面切换。
 */
class TxNavController(initial: TxDestination = TxDestination.Home) {
    private val backStack = mutableStateListOf(initial)

    /** 当前页面 */
    val current: TxDestination get() = backStack.last()

    /** 是否可回退 */
    val canPop: Boolean get() = backStack.size > 1

    /** 推入新页面（用于二级页面，如「关于」） */
    fun navigate(dest: TxDestination) {
        if (backStack.last() == dest) return
        backStack.add(dest)
    }

    /** 切换底部 Tab —— 重置回退栈到该 Tab，避免栈过深 */
    fun switchTab(dest: TxDestination) {
        if (backStack.last() == dest && backStack.size == 1) return
        backStack.clear()
        backStack.add(dest)
    }

    /** 回退 */
    fun popBackStack(): Boolean {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.size - 1)
            return true
        }
        return false
    }
}

@Composable
fun rememberTxNavController(initial: TxDestination = TxDestination.Home): TxNavController {
    return remember { TxNavController(initial) }
}

/**
 * 应用顶层导航容器
 *
 * - 底部 NavigationBar 在 4 个一级页面间切换
 * - 「关于」作为「设置」的二级页面推入
 * - 统一 SnackbarHost 供各页面复用
 */
@Composable
fun NavigationHost() {
    val navController = rememberTxNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val showSnackbar: (String) -> Unit = remember(scope, snackbarHostState) {
        { message: String -> scope.launch { snackbarHostState.showSnackbar(message) } }
    }

    // 系统返回键支持
    BackHandler(enabled = navController.canPop) {
        navController.popBackStack()
    }

    val currentDest = navController.current

    // 计算底部 Tab 当前选中项（「关于」归到「设置」分组）
    val selectedTabIndex = when (currentDest) {
        TxDestination.Home -> 0
        TxDestination.Whitelist -> 1
        TxDestination.Settings -> 2
        TxDestination.Logs -> 3
        TxDestination.About -> 2
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                BottomTabs.forEachIndexed { index, dest ->
                    NavigationBarItem(
                        selected = selectedTabIndex == index,
                        onClick = { navController.switchTab(dest) },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 由内部各页面的 Scaffold 单独处理顶部状态栏内边距
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // R7-7 设计决策说明：
            // 使用 when(currentDest) 切换页面，每次切换都会销毁并重建对应 Composable，
            // 导致页面内状态（如滚动位置、临时输入）丢失。这是有意为之的权衡：
            // - 优势：每次进入页面都重新加载数据，确保用户看到最新的应用列表/进程状态/配置，
            //   对本应用这类"状态查看+操作"类工具型页面更符合预期。
            // - 劣势：重建有轻微性能开销，且无法保留页面内的临时状态。
            // 替代方案（如使用 AnimatedContent 或控制可见性保留组合）会增加内存占用，
            // 且需要在各页面内部自行处理数据刷新逻辑。当前实现优先保证数据新鲜度与内存占用最小。
            // 如未来需要保留页面状态，可改为基于 visible 控制可见性的方案。
            when (currentDest) {
                TxDestination.Home -> HomeScreen(showSnackbar = showSnackbar)
                TxDestination.Whitelist -> WhitelistScreen(showSnackbar = showSnackbar)
                TxDestination.Settings -> SettingsScreen(
                    showSnackbar = showSnackbar,
                    onNavigateAbout = { navController.navigate(TxDestination.About) },
                )
                TxDestination.Logs -> LogViewerScreen(showSnackbar = showSnackbar)
                TxDestination.About -> AboutScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
