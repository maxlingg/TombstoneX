package com.tombstonex.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.tombstonex.manager.ConfigManager
import com.tombstonex.manager.FreezeManager
import com.tombstonex.manager.ProcessTracker
import com.tombstonex.model.AppState

/**
 * 快捷设置磁贴：展示当前冻结状态并切换全局暂停/恢复。
 *
 * - 运行中：磁贴高亮，副标题显示「已冻结 X 个应用」，点击进入暂停态。
 * - 暂停：磁贴置灰，副标题「点击恢复冻结」，点击恢复运行态（暂停时会解冻所有已冻结应用）。
 *
 * 暂停状态通过 [ConfigManager] 的文件标记持久化，Hook 侧可据此判断是否暂停冻结。
 */
class FreezeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val config = ConfigManager.getInstance()
        if (config.isGlobalPaused()) {
            // 恢复：清除暂停标记
            runCatching {
                config.setGlobalPaused(false)
                FreezeManager.getInstance().resumeAll()
            }
        } else {
            // 暂停：写入标记，并尝试解冻所有已冻结应用
            runCatching {
                config.setGlobalPaused(true)
                FreezeManager.getInstance().pauseAll()
            }
        }
        refreshTile()
    }

    /** 刷新磁贴外观与状态 */
    private fun refreshTile() {
        val tile = qsTile ?: return
        val paused = runCatching { ConfigManager.getInstance().isGlobalPaused() }.getOrDefault(false)
        val frozenCount = runCatching {
            ProcessTracker.getInstance().getAllProcesses().values
                .count { it.state == AppState.FROZEN }
        }.getOrDefault(0)

        if (paused) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "已暂停"
            runCatching { tile.subtitle = "点击恢复冻结" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching { tile.contentDescription = "TombstoneX 冻结已暂停" }
            }
        } else {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "冻结中"
            runCatching { tile.subtitle = "已冻结 $frozenCount 个应用" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching {
                    tile.contentDescription = "TombstoneX 冻结运行中，已冻结 $frozenCount 个应用"
                }
            }
        }
        tile.updateTile()
    }
}
