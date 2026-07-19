package com.tombstonex.manager;

import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 定时冻结管理器。
 *
 * 使用单线程调度器每 60 秒扫描一次 {@link ProcessTracker} 中所有进程，
 * 对处于后台（非 FOREGROUND / FROZEN / KILLED）且不在白名单的应用执行冻结。
 *
 * 冻结前会通过反射调用 {@code SmartStateHook.isAppActive(uid, packageName)}
 * 检查智能活跃状态（SmartStateHook 尚未实现时降级为"不活跃"）。
 *
 * 使用 {@link ConcurrentHashMap} 跟踪每个 pid 的上次冻结时间，避免在短时间内
 * 重复冻结同一进程。
 */
public class ScheduledFreezeManager {
    private static final long SCAN_INTERVAL_SECONDS = 60;
    // 同一进程在短时间内不重复冻结，防止与 ActivitySwitchHook 的延迟冻结任务抖动
    private static final long MIN_REFREEZE_INTERVAL_MS = 30 * 1000L;

    private static final String SMART_STATE_HOOK_CLASS = "com.tombstonex.hook.SmartStateHook";
    private static final String SMART_STATE_HOOK_METHOD = "isAppActive";

    private static volatile ScheduledFreezeManager instance;

    private volatile ScheduledThreadPoolExecutor executor;
    private final Map<Integer, Long> lastFreezeTime = new ConcurrentHashMap<>();

    private ScheduledFreezeManager() {}

    // P3-R6: 使用双重检查锁定
    public static ScheduledFreezeManager getInstance() {
        ScheduledFreezeManager local = instance;
        if (local == null) {
            synchronized (ScheduledFreezeManager.class) {
                local = instance;
                if (local == null) {
                    local = new ScheduledFreezeManager();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * 启动定时扫描。重复调用安全（已运行时直接返回）。
     */
    public synchronized void start() {
        if (executor != null && !executor.isShutdown()) {
            Logger.d("ScheduledFreezeManager 已在运行");
            return;
        }
        executor = new ScheduledThreadPoolExecutor(1);
        // 已取消的任务立即从工作队列移除，避免驻留队列造成内存泄漏
        executor.setRemoveOnCancelPolicy(true);
        executor.scheduleAtFixedRate(this::scanAndFreeze,
            SCAN_INTERVAL_SECONDS, SCAN_INTERVAL_SECONDS, TimeUnit.SECONDS);
        Logger.i("ScheduledFreezeManager 已启动，间隔=" + SCAN_INTERVAL_SECONDS + "s");
    }

    /**
     * 停止定时扫描并清理 executor 与冻结时间记录。
     */
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
            Logger.i("ScheduledFreezeManager 已停止");
        }
        lastFreezeTime.clear();
    }

    /**
     * 扫描所有进程并冻结符合条件的后台应用。
     */
    private void scanAndFreeze() {
        try {
            // 全局暂停时不执行新的冻结
            if (ConfigManager.getInstance().isGlobalPaused()) {
                Logger.d("ScheduledFreezeManager: 全局已暂停，跳过扫描");
                return;
            }

            Map<Integer, AppInfo> all = ProcessTracker.getInstance().getAllProcesses();
            long now = System.currentTimeMillis();
            int scanned = 0;
            int frozen = 0;

            for (AppInfo info : all.values()) {
                scanned++;
                if (info == null) continue;

                // 跳过：FOREGROUND / FROZEN / KILLED
                if (info.state == AppState.FOREGROUND) continue;
                if (info.state == AppState.FROZEN) continue;
                if (info.state == AppState.KILLED) continue;

                // 跳过：白名单应用（先查缓存标记，再查权威 WhitelistManager）
                if (info.isWhiteListed) continue;
                if (!WhitelistManager.getInstance().shouldFreeze(
                        info.packageName, info.processName, info.isSystemApp)) {
                    continue;
                }

                // 智能状态检查（如果 SmartStateHook 可用）
                if (info.packageName != null && isAppActive(info.uid, info.packageName)) {
                    Logger.d("ScheduledFreezeManager: 应用活跃，跳过: " + info.packageName);
                    continue;
                }

                // 避免短时间内重复冻结同一进程
                Long last = lastFreezeTime.get(info.pid);
                if (last != null && (now - last) < MIN_REFREEZE_INTERVAL_MS) {
                    continue;
                }

                if (FreezeManager.getInstance().freezeProcess(info.pid, info.uid)) {
                    lastFreezeTime.put(info.pid, now);
                    frozen++;
                }
            }

            Logger.d("ScheduledFreezeManager 扫描完成: scanned=" + scanned
                + " frozen=" + frozen);
        } catch (Throwable t) {
            Logger.e("ScheduledFreezeManager 扫描出错", t);
        }
    }

    /**
     * 通过反射调用 {@code SmartStateHook.isAppActive(int uid, String packageName)}。
     *
     * SmartStateHook 尚未实现时（ClassNotFoundException）静默降级为 false
     * （视为不活跃，可冻结）；其它反射异常仅记录调试日志后返回 false。
     *
     * @return true 表示应用当前活跃，不应冻结
     */
    private boolean isAppActive(int uid, String packageName) {
        try {
            Class<?> clazz = Class.forName(SMART_STATE_HOOK_CLASS);
            Method method = clazz.getMethod(SMART_STATE_HOOK_METHOD, int.class, String.class);
            Object result = method.invoke(null, uid, packageName);
            return Boolean.TRUE.equals(result);
        } catch (ClassNotFoundException e) {
            // SmartStateHook 未实现，降级为不活跃 —— 不记录日志避免刷屏
        } catch (Throwable t) {
            Logger.d("SmartStateHook.isAppActive 反射调用失败: " + t.getMessage());
        }
        return false;
    }
}
