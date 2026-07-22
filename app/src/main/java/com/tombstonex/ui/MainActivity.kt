package com.tombstonex.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.lifecycleScope
import com.tombstonex.BuildConfig
import com.tombstonex.service.ServiceClient
import com.tombstonex.ui.theme.TombstoneXTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LSPosed 模块状态信息
 *
 * @param installed 模块入口（xposed_init 资源）是否存在于本 APK
 * @param entryClass Hook 入口类全限定名
 * @param activated 模块是否真正激活（Binder 服务已就绪，可正常通信）
 * @param moduleEnabled LSPosed 是否已启用模块（initZygote 已执行）
 * @param moduleLoaded 模块是否已加载到 system_server（handleLoadPackage("android") 已执行）
 */
@Immutable
data class ModuleState(
    val installed: Boolean,
    val entryClass: String,
    val activated: Boolean,
    val moduleEnabled: Boolean = false,
    val moduleLoaded: Boolean = false,
)

/**
 * 全局模块状态，供 SettingsScreen / AboutScreen 读取
 */
val LocalModuleState = compositionLocalOf {
    ModuleState(installed = false, entryClass = "", activated = false)
}

class MainActivity : ComponentActivity() {

    // 模块状态：初始为默认值，在 onCreate 中通过 detectModuleState() 更新
    private val moduleState = mutableStateOf(ModuleState(installed = false, entryClass = "", activated = false))

    // 轻微-9 修复：onResume 与 onCreate 重复调用 checkModuleState，加 5 秒节流
    private var lastCheckTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 初始状态：读取 xposed_init 判断模块入口是否存在
        moduleState.value = detectModuleState()

        // 异步通过 ServiceClient 检测模块激活状态
        // 记录检测时间，避免 onResume 立即重复检测
        lastCheckTime = System.currentTimeMillis()
        checkModuleState()

        setContent {
            TombstoneXTheme {
                CompositionLocalProvider(LocalModuleState provides moduleState.value) {
                    NavigationHost()
                }
            }
        }
    }

    // L25: onResume 中重新检测模块状态，确保从后台返回时状态最新
    // 轻微-9 修复：加 5 秒节流，避免与 onCreate 中的检测重复
    override fun onResume() {
        super.onResume()
        val now = System.currentTimeMillis()
        if (now - lastCheckTime > 5000) {
            lastCheckTime = now
            checkModuleState()
        }
    }

    /**
     * 异步通过 ServiceClient 检测模块激活状态（三级检测）
     * 1. isModuleEnabled: 检查 sys.tombstonex.loaded，判断 LSPosed 是否已启用模块
     * 2. isModuleLoaded: 检查 sys.tombstonex.active，判断模块是否已加载到 system_server
     * 3. isAvailable: 检查 Binder 服务，判断服务是否已就绪
     */
    private fun checkModuleState() {
        lifecycleScope.launch {
            val (enabled, loaded, activated) = withContext(Dispatchers.IO) {
                val enabled = safeRunCatching { ServiceClient.isModuleEnabled }.getOrDefault(false)
                val loaded = safeRunCatching { ServiceClient.isModuleLoaded }.getOrDefault(false)
                val activated = safeRunCatching { ServiceClient.isAvailable }.getOrDefault(false)
                Triple(enabled, loaded, activated)
            }
            moduleState.value = moduleState.value.copy(
                moduleEnabled = enabled,
                moduleLoaded = loaded,
                activated = activated,
            )
        }
    }

    /**
     * 检测 LSPosed 模块安装/配置状态
     *
     * 通过读取 assets/xposed_init 资源获取 Hook 入口类名，
     * 判定模块入口是否存在于本 APK。
     *
     * 注意：此处仅判断「已安装/已配置」，真正的「已激活」状态
     * 由 [ServiceClient.isAvailable] 异步检测。
     */
    private fun detectModuleState(): ModuleState {
        return try {
            // m-10: xposed_init 文件通常很小（< 1KB），主线程读取可接受。
            // 若文件大小增长，应考虑使用 Dispatchers.IO 异步读取。
            val entry = assets.open("xposed_init").bufferedReader(Charsets.UTF_8).use {
                it.readText().trim()
            }
            if (entry.isEmpty() || entry.startsWith("#")) {
                return ModuleState(installed = false, entryClass = "", activated = false)
            }
            ModuleState(
                installed = true,
                entryClass = entry,
                activated = false, // 由 ServiceClient.isAvailable 异步更新
            )
        } catch (e: Exception) {
            ModuleState(installed = false, entryClass = "", activated = false)
        }
    }

    companion object {
        /** 供非 Compose 代码（如磁贴服务）读取的版本号 */
        val VERSION_NAME: String get() = BuildConfig.VERSION_NAME
    }
}
