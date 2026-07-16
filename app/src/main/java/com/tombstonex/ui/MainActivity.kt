package com.tombstonex.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.tombstonex.BuildConfig
import com.tombstonex.ui.theme.TombstoneXTheme

/**
 * LSPosed 模块状态信息
 *
 * @param installed 模块入口（xposed_init 资源）是否存在于本 APK
 * @param entryClass Hook 入口类全限定名
 * @param activated 在 LSPosed 管理器中是否已激活（仅作 UI 提示，
 *   由于 Hook 运行在 system_server 进程，UI 进程无法直接感知运行时激活，
 *   这里以「入口类可加载」作为「已配置」的判定依据）
 */
data class ModuleState(
    val installed: Boolean,
    val entryClass: String,
    val activated: Boolean,
)

/**
 * 全局模块状态，供 SettingsScreen / AboutScreen 读取
 */
val LocalModuleState = staticCompositionLocalOf {
    ModuleState(installed = false, entryClass = "", activated = false)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val moduleState = detectModuleState()

        setContent {
            TombstoneXTheme {
                CompositionLocalProvider(LocalModuleState provides moduleState) {
                    NavigationHost()
                }
            }
        }
    }

    /**
     * 检测 LSPosed 模块激活状态
     *
     * 通过读取 assets/xposed_init 资源获取 Hook 入口类名，
     * 若入口类可加载则判定为「模块已安装/已配置」。
     */
    private fun detectModuleState(): ModuleState {
        return try {
            val entry = assets.open("xposed_init").bufferedReader().use {
                it.readText().trim()
            }
            if (entry.isEmpty() || entry.startsWith("#")) {
                return ModuleState(installed = false, entryClass = "", activated = false)
            }
            val classLoaded = runCatching { Class.forName(entry) }.isSuccess
            ModuleState(
                installed = classLoaded,
                entryClass = entry,
                activated = classLoaded,
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
