package com.tombstonex.service

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.util.concurrent.Executors

/**
 * 快捷设置磁贴：展示当前冻结状态并切换全局暂停/恢复。
 *
 * - 运行中：磁贴高亮，副标题显示「已冻结 X 个应用」，点击进入暂停态。
 * - 暂停：磁贴置灰，副标题「点击恢复冻结」，点击恢复运行态（暂停时会解冻所有已冻结应用）。
 * - 未激活：模块未启用时磁贴不可用，显示「未激活」。
 *
 * 所有 ServiceClient 调用在后台线程执行，避免阻塞主线程。
 */
class FreezeTileService : TileService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceExecutor = Executors.newSingleThreadExecutor()

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        serviceExecutor.execute {
            // 检查模块是否激活
            if (!ServiceClient.isAvailable) {
                refreshTile()
                return@execute
            }
            // 读取当前暂停状态，切换
            val paused = ServiceClient.isGlobalPaused()
            if (paused) {
                // 恢复：清除暂停标记并恢复冻结
                ServiceClient.setGlobalPaused(false)
                ServiceClient.resumeAll()
            } else {
                // 暂停：写入标记并暂停冻结
                ServiceClient.setGlobalPaused(true)
                ServiceClient.pauseAll()
            }
            refreshTile()
        }
    }

    /** 刷新磁贴外观与状态（ServiceClient 调用在后台线程，UI 更新切回主线程） */
    private fun refreshTile() {
        serviceExecutor.execute {
            val available = ServiceClient.isAvailable
            val paused = if (available) ServiceClient.isGlobalPaused() else false
            val frozenCount = if (available) ServiceClient.getFrozenCount() else 0

            mainHandler.post {
                val tile = qsTile ?: return@post
                when {
                    !available -> {
                        tile.state = Tile.STATE_UNAVAILABLE
                        tile.label = "未激活"
                        runCatching { tile.subtitle = "模块未启用" }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            runCatching { tile.contentDescription = "TombstoneX 模块未激活" }
                        }
                    }
                    paused -> {
                        tile.state = Tile.STATE_INACTIVE
                        tile.label = "已暂停"
                        runCatching { tile.subtitle = "点击恢复冻结" }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            runCatching { tile.contentDescription = "TombstoneX 冻结已暂停" }
                        }
                    }
                    else -> {
                        tile.state = Tile.STATE_ACTIVE
                        tile.label = "冻结中"
                        runCatching { tile.subtitle = "已冻结 $frozenCount 个应用" }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            runCatching {
                                tile.contentDescription = "TombstoneX 冻结运行中，已冻结 $frozenCount 个应用"
                            }
                        }
                    }
                }
                tile.updateTile()
            }
        }
    }
}
