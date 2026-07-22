package com.tombstonex.manager;

import com.tombstonex.model.AppInfo;
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
     * 间隔从 {@link ConfigManager#getRotationInterval()} 读取，并在每次执行后
     * 重新读取，使配置变更即时生效（不再使用 scheduleAtFixedRate 固定间隔）。
     */
    public synchronized void start() {
        if (executor != null && !executor.isShutdown()) {
            Logger.d("RotationThawManager 已在运行");
            return;
        }
        executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        // M-22: 允许核心线程在空闲时超时回收，避免线程池永久占用资源。
        executor.setKeepAliveTime(60, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        Logger.i("RotationThawManager 已启动");
        scheduleNext();
    }

    /**
     * 调度下一次轮番解冻。每次执行完后重新读取 interval，使配置变更即时生效。
     */
    private void scheduleNext() {
        // M-23: volatile 读 this.executor 保证可见性，与 this.executor == exec 检查
        // 配合确保 stop() 后旧 lambda 不会在新 executor 上重复调度。
        // volatile 保证：stop() 写入 null 后，scheduleNext() 的读可见该变更。
        // 无需额外锁：this.executor == exec 检查本就是正确的 happens-before 模式。
        ScheduledThreadPoolExecutor exec = this.executor;
        if (exec == null || exec.isShutdown()) return;
        int interval = ConfigManager.getInstance().getRotationInterval();
        // L2: 减去 3 秒解冻窗口时长，避免 Thread.sleep(3s) 使实际轮番间隔变为 interval+3 秒
        // （如默认 360s 实际为 363s）。下限保护为 1 秒。
        int adjustedInterval = Math.max(1, interval - (int) (THAW_DURATION_MS / 1000));
        try {
            exec.schedule(() -> {
                try {
                    rotateThaw();
                } catch (Throwable t) {
                    Logger.e("RotationThaw error", t);
                }
                // 仅当当前 executor 仍是本次调度所用的 executor 时才调度下一次，
                // 避免 stop()+start() 期间旧 lambda 在新 executor 上重复调度。
                if (this.executor == exec) {
                    scheduleNext();
                }
            }, adjustedInterval, TimeUnit.SECONDS);
        } catch (Throwable t) {
            Logger.d("RotationThawManager: 调度下一次失败（可能已停止）: " + t.getMessage());
        }
    }

    /**
     * 停止轮番解冻并清理 executor。
     */
    public synchronized void stop() {
        if (executor != null) {
            List<Runnable> discarded = executor.shutdownNow();
            executor = null;
            Logger.i("RotationThawManager 已停止，丢弃了 " + discarded.size() + " 个待执行任务");
        }
    }

    /**
     * 执行一次轮番解冻：找到冻结时间最长的应用 -> 解冻 -> 等待 3 秒 -> 重新冻结。
     */
    private void rotateThaw() {
        AppInfo target = null;
        try {
            // 全局暂停时跳过
            if (ConfigManager.getInstance().isGlobalPaused()) {
                Logger.d("RotationThawManager: 全局已暂停，跳过轮番");
                return;
            }

            target = findLongestFrozen();
            if (target == null) {
                Logger.d("RotationThawManager: 没有可轮番的冻结应用");
                return;
            }

            Logger.i("RotationThawManager: 正在轮番 " + target.packageName
                + " pid=" + target.pid
                + " frozenSince=" + target.getFreezeTimestamp());

            // M1/M2: 先抑制冻结，确保解冻窗口期间不会被 scanAndFreeze 及其他 Hook 重新冻结。
            // suppressFreeze 必须在 unfreezeProcess 之前调用，避免解冻与抑制之间的竞态窗口
            // （旧代码在 unfreezeProcess 之后才调用 suppressFreeze，存在被重新冻结的竞态）。
            // FreezeManager.suppressFreeze 覆盖所有冻结入口（ScreenStateHook /
            // ActivitySwitchHook / ScheduledFreezeManager 均经过 FreezeManager.freezeProcess）；
            // ScheduledFreezeManager.suppressFreeze 额外抑制其自身的 lastFreezeTime 窗口。
            FreezeManager.suppressFreeze(target.pid);
            ScheduledFreezeManager.getInstance().suppressFreeze(target.pid);

            // 1. 解冻
            boolean unfrozen = FreezeManager.getInstance().unfreezeProcess(target.pid, target.uid);
            if (!unfrozen) {
                Logger.w("RotationThawManager: 解冻失败 pid=" + target.pid);
                return;
            }

            // 2. 等待 3 秒，让应用有机会刷新网络连接与推送状态
            try {
                Thread.sleep(THAW_DURATION_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Logger.d("RotationThawManager: 睡眠被中断，立即重新冻结");
            }

            // 3. 重新冻结（再次检查全局暂停，避免暂停期间误冻结）
            if (ConfigManager.getInstance().isGlobalPaused()) {
                Logger.d("RotationThawManager: 解冻期间全局已暂停，跳过重新冻结");
                return;
            }
            // 解除冻结抑制，使重新冻结能够执行（freezeProcess 内部会检查抑制标志）。
            // finally 块会再次调用 unsuppressFreeze 作为兜底（对已移除的 pid 是 no-op）。
            FreezeManager.unsuppressFreeze(target.pid);
            // 轻微-3: freezeProcess 内部已调用 updateState(FROZEN) 并设置 freezeTimestamp，
            // 此处无需再次调用 updateState（旧注释声称"不刷新时间戳"与实际行为矛盾，
            // 且第二次调用完全冗余），仅记录日志即可。
            if (FreezeManager.getInstance().freezeProcess(target.pid, target.uid)) {
                Logger.d("RotationThawManager: 已重新冻结 " + target.packageName
                    + " pid=" + target.pid);
            } else {
                Logger.w("RotationThawManager: 重新冻结失败 pid=" + target.pid);
            }
        } catch (Throwable t) {
            Logger.e("RotationThawManager 轮番出错", t);
        } finally {
            // M2: 确保所有退出路径（早返回/异常/正常完成）都解除冻结抑制，避免永久抑制。
            // 仅在已设置 target（即已调用 suppressFreeze）时才需要解除。
            if (target != null) {
                FreezeManager.unsuppressFreeze(target.pid);
                // R7: 同时清理 ScheduledFreezeManager.lastFreezeTime，避免下次冻结时
                // 使用过期的 lastFreezeTime 而在 MIN_REFREEZE_INTERVAL_MS 窗口内被跳过。
                // suppressFreeze 写入了 lastFreezeTime，若重新冻结失败则该时间戳会
                // 阻止下一次 scanAndFreeze 重试冻结。
                ScheduledFreezeManager.getInstance().clearLastFreezeTime(target.pid);
            }
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
            if (info.getFreezeTimestamp() <= 0) continue;
            if (info.getFreezeTimestamp() < earliest) {
                earliest = info.getFreezeTimestamp();
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
