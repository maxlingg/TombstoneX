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
 * @param moduleLoaded 模块是否已加载到 system_server（通过系统属性检测），
 *   用于区分"模块未加载"和"服务注册失败"
 */
@Immutable
data class ModuleState(
    val installed: Boolean,
    val entryClass: String,
    val activated: Boolean,
    val moduleLoaded: Boolean = false,
)

/**
 * 全局模块状态，供 SettingsScreen / AboutScreen 读取
 */
val LocalModuleState = compositionLocalOf {
    ModuleState(installed = false, entryClass = "", activated = false)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 初始状态：读取 xposed_init 判断模块入口是否存在
        val moduleState = mutableStateOf(detectModuleState())

        // 异步通过 ServiceClient 检测模块激活状态
        // 1. isModuleLoaded: 检查系统属性，判断模块是否已加载到 system_server
        // 2. isAvailable: 检查 Binder 服务，判断服务是否已就绪
        lifecycleScope.launch {
            val (loaded, activated) = withContext(Dispatchers.IO) {
                val loaded = safeRunCatching { ServiceClient.isModuleLoaded }.getOrDefault(false)
                val activated = safeRunCatching { ServiceClient.isAvailable }.getOrDefault(false)
                loaded to activated
            }
            moduleState.value = moduleState.value.copy(
                moduleLoaded = loaded,
                activated = activated,
            )
        }

        setContent {
            TombstoneXTheme {
                CompositionLocalProvider(LocalModuleState provides moduleState.value) {
                    NavigationHost()
                }
            }
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
