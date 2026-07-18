package com.tombstonex.manager;

import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;

import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 轮番解冻管理器。
 *
 * 定期（默认 360 秒 / 6 分钟，可通过 {@link ConfigManager#getRotationInterval()} 配置）
 * 找到冻结时间最长的应用，解冻 3 秒后重新冻结，避免长时间冻结导致应用丢失
 * 网络连接、推送通道或内部状态过期。
 *
 * 全局暂停（{@link ConfigManager#isGlobalPaused()}）时跳过整个轮番动作。
 */
public class RotationThawManager {
    /** 默认轮番间隔（秒），与 {@link ConfigManager#getRotationInterval()} 的默认值一致 */
    private static final int DEFAULT_ROTATION_INTERVAL = 360;
    /** 解冻持续时间（毫秒） */
    private static final long THAW_DURATION_MS = 3000L;

    private static volatile RotationThawManager instance;

    private volatile ScheduledThreadPoolExecutor executor;

    private RotationThawManager() {}

    // P3-R6: 使用双重检查锁定
    public static RotationThawManager getInstance() {
        RotationThawManager local = instance;
        if (local == null) {
            synchronized (RotationThawManager.class) {
                local = instance;
                if (local == null) {
                    local = new RotationThawManager();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * 启动轮番解冻。重复调用安全（已运行时直接返回）。
     * 间隔从 {@link ConfigManager#getRotationInterval()} 读取。
     */
    public synchronized void start() {
        if (executor != null && !executor.isShutdown()) {
            Logger.d("RotationThawManager already running");
            return;
        }
        int interval = ConfigManager.getInstance().getRotationInterval();
        executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        executor.scheduleAtFixedRate(this::rotateThaw,
            interval, interval, TimeUnit.SECONDS);
        Logger.i("RotationThawManager started, interval=" + interval + "s");
    }

    /**
     * 停止轮番解冻并清理 executor。
     */
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
            Logger.i("RotationThawManager stopped");
        }
    }

    /**
     * 执行一次轮番解冻：找到冻结时间最长的应用 -> 解冻 -> 等待 3 秒 -> 重新冻结。
     */
    private void rotateThaw() {
        try {
            // 全局暂停时跳过
            if (ConfigManager.getInstance().isGlobalPaused()) {
                Logger.d("RotationThawManager: global paused, skip rotation");
                return;
            }

            AppInfo target = findLongestFrozen();
            if (target == null) {
                Logger.d("RotationThawManager: no frozen app to rotate");
                return;
            }

            Logger.i("RotationThawManager: rotating " + target.packageName
                + " pid=" + target.pid
                + " frozenSince=" + target.freezeTimestamp);

            // 1. 解冻
            boolean unfrozen = FreezeManager.getInstance().unfreezeProcess(target.pid, target.uid);
            if (!unfrozen) {
                Logger.w("RotationThawManager: unfreeze failed for pid=" + target.pid);
                return;
            }

            // 2. 等待 3 秒，让应用有机会刷新网络连接与推送状态
            try {
                Thread.sleep(THAW_DURATION_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Logger.d("RotationThawManager: sleep interrupted, re-freezing immediately");
            }

            // 3. 重新冻结（再次检查全局暂停，避免暂停期间误冻结）
            if (ConfigManager.getInstance().isGlobalPaused()) {
                Logger.d("RotationThawManager: global paused during thaw, skip re-freeze");
                return;
            }
            FreezeManager.getInstance().freezeProcess(target.pid, target.uid);

            // 4. 更新冻结时间戳为当前时间
            // freezeProcess 成功后 ProcessTracker 已将状态置为 FROZEN 并更新 freezeTimestamp，
            // 这里显式再更新一次以保证时间戳反映轮番解冻后的新冻结时刻。
            ProcessTracker.getInstance().updateState(target.pid, AppState.FROZEN);

            Logger.d("RotationThawManager: re-froze " + target.packageName
                + " pid=" + target.pid);
        } catch (Throwable t) {
            Logger.e("RotationThawManager rotate error", t);
        }
    }

    /**
     * 在所有已冻结进程中找到冻结时间最长（{@link AppInfo#freezeTimestamp} 最早）的应用。
     *
     * @return 冻结时间最长的应用，没有冻结进程时返回 null
     */
    private AppInfo findLongestFrozen() {
        List<AppInfo> frozen = ProcessTracker.getInstance().getFrozenProcesses();
        if (frozen.isEmpty()) return null;

        AppInfo longest = null;
        long earliest = Long.MAX_VALUE;
        for (AppInfo info : frozen) {
            if (info.freezeTimestamp <= 0) continue;
            if (info.freezeTimestamp < earliest) {
                earliest = info.freezeTimestamp;
                longest = info;
            }
        }

        // 边界兜底：所有冻结进程的 freezeTimestamp 都为 0 时回退到第一个
        if (longest == null) {
            longest = frozen.get(0);
        }
        return longest;
    }
}
