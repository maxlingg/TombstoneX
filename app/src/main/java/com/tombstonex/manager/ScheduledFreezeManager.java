package com.tombstonex.manager;

import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;

import java.lang.reflect.Method;
import java.util.List;
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

    // M5: 缓存 SmartStateHook.isAppActive 的反射查找结果，避免每次扫描都反射。
    // resolved=false 表示尚未解析；available=true 表示类与方法均存在。
    private volatile boolean smartStateHookResolved = false;
    private volatile boolean smartStateHookAvailable = false;
    private volatile Method cachedIsAppActiveMethod;

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
            Logger.d("定时冻结管理器已在运行");
            return;
        }
        executor = new ScheduledThreadPoolExecutor(1);
        // 已取消的任务立即从工作队列移除，避免驻留队列造成内存泄漏
        executor.setRemoveOnCancelPolicy(true);
        // M-20: 允许核心线程在空闲时超时回收，避免线程池永久占用资源。
        executor.setKeepAliveTime(60, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        executor.scheduleAtFixedRate(this::scanAndFreeze,
            SCAN_INTERVAL_SECONDS, SCAN_INTERVAL_SECONDS, TimeUnit.SECONDS);
        Logger.i("定时冻结管理器已启动，间隔= + SCAN_INTERVAL_SECONDS + "s");
    }

    /**
     * 停止定时扫描并清理 executor 与冻结时间记录。
     */
    public synchronized void stop() {
        if (executor != null) {
            // M-21: 记录被丢弃的待执行任务数量，便于排查调度异常。
            List<Runnable> discarded = executor.shutdownNow();
            Logger.i("定时冻结管理器已停止，丢弃了  + discarded.size() + " 个待执行任务");
            executor = null;
        }
        lastFreezeTime.clear();
    }

    /**
     * 清除指定 pid 的上次冻结时间记录。
     *
     * <p>轻微-8: 用于 pid 复用场景。当 {@link ProcessTracker#registerProcess}
     * 检测到同一 pid 被新进程（不同 uid）复用时调用此方法，避免新进程因
     * 残留的旧冻结时间戳而被误判为"刚冻结过"从而在 {@code MIN_REFREEZE_INTERVAL_MS}
     * 窗口内被跳过冻结。
     *
     * @param pid 需要清除记录的进程 id
     */
    public void clearLastFreezeTime(int pid) {
        lastFreezeTime.remove(pid);
    }

    /**
     * 清空所有 pid 的上次冻结时间记录。
     *
     * <p>轻微-1: 用于 {@link ProcessTracker#clear()} 整体重置场景，确保
     * ProcessTracker 清空后不会残留过期的冻结时间戳，避免新注册的进程
     * 因残留时间戳而在 {@code MIN_REFREEZE_INTERVAL_MS} 窗口内被误跳过冻结。
     */
    public void clearAllLastFreezeTime() {
        lastFreezeTime.clear();
    }

    /**
     * 抑制对指定 pid 的冻结（在 MIN_REFREEZE_INTERVAL_MS 窗口内）。
     *
     * <p>中等-2: 用于 {@link RotationThawManager#rotateThaw()} 的解冻窗口期间。
     * 通过写入当前时间戳到 lastFreezeTime，使下一次 scanAndFreeze 扫描时
     * 该 pid 会被 {@code (now - last) < MIN_REFREEZE_INTERVAL_MS} 判定跳过，
     * 避免在 3 秒解冻窗口内被重新冻结。
     *
     * @param pid 需要抑制冻结的进程 id
     */
    public void suppressFreeze(int pid) {
        lastFreezeTime.put(pid, System.currentTimeMillis());
    }

    /**
     * 扫描所有进程并冻结符合条件的后台应用。
     */
    private void scanAndFreeze() {
        try {
            // 全局暂停时不执行新的冻结
            if (ConfigManager.getInstance().isGlobalPaused()) {
                Logger.d("定时冻结管理器: 全局已暂停，跳过扫描");
                return;
            }

            Map<Integer, AppInfo> all = ProcessTracker.getInstance().getAllProcesses();
            // M16: 清理已死亡进程的残留条目，避免 lastFreezeTime 无限增长
            lastFreezeTime.keySet().retainAll(all.keySet());
            long now = System.currentTimeMillis();
            int scanned = 0;
            int frozen = 0;

            for (AppInfo info : all.values()) {
                scanned++;
                if (info == null) continue;

                // 跳过：FOREGROUND / FROZEN / KILLED
                if (info.getState() == AppState.FOREGROUND) continue;
                if (info.getState() == AppState.FROZEN) continue;
                if (info.getState() == AppState.KILLED) continue;

                // M3: 移除 info.isWhiteListed() 短路检查，统一走 WhitelistManager.shouldFreeze()。
                // info.isWhiteListed() 在注册时设置一次且不再刷新，可能因白名单变更而过时；
                // shouldFreeze() 直接查询内存中的 HashSet，始终是权威结果。
                if (!WhitelistManager.getInstance().shouldFreeze(
                        info.packageName, info.processName, info.isSystemApp())) {
                    continue;
                }

                // 智能状态检查（如果 SmartStateHook 可用）
                if (info.packageName != null && isAppActive(info.uid, info.packageName)) {
                    Logger.d("定时冻结管理器: 应用活跃，跳过: " + info.packageName);
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

            Logger.d("定时冻结管理器扫描完成: 扫描= + scanned
                + " frozen=" + frozen);
        } catch (Throwable t) {
            Logger.e("定时冻结管理器扫描出错", t);
        }
    }

    /**
     * 通过反射调用 {@code SmartStateHook.isAppActive(int uid, String packageName)}。
     *
     * SmartStateHook 尚未实现时（ClassNotFoundException）静默降级为 false
     * （视为不活跃，可冻结）；其它反射异常仅记录调试日志后返回 false。
     *
     * <p>M5: 反射类/方法查找结果通过 volatile 字段缓存，避免每次扫描都执行
     * Class.forName + getMethod。仅 method.invoke 在每次调用时执行。
     *
     * @return true 表示应用当前活跃，不应冻结
     */
    private boolean isAppActive(int uid, String packageName) {
        if (!smartStateHookResolved) {
            resolveSmartStateHook();
        }
        if (!smartStateHookAvailable) {
            return false;
        }
        Method method = cachedIsAppActiveMethod;
        if (method == null) {
            return false;
        }
        try {
            Object result = method.invoke(null, uid, packageName);
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            Logger.d("SmartStateHook.isAppActive 反射调用失败: " + t.getMessage());
        }
        return false;
    }

    /**
     * 解析 SmartStateHook 类与 isAppActive 方法，结果缓存到 volatile 字段。
     * synchronized 确保只有一个线程执行解析，避免并发竞态：线程 A 失败设置
     * smartStateHookAvailable=false 与线程 B 成功设置 cachedIsAppActiveMethod=method
     * 交错，导致 Hook 永久不可用。
     */
    private synchronized void resolveSmartStateHook() {
        try {
            Class<?> clazz = Class.forName(SMART_STATE_HOOK_CLASS);
            Method method = clazz.getMethod(SMART_STATE_HOOK_METHOD, int.class, String.class);
            cachedIsAppActiveMethod = method;
            smartStateHookAvailable = true;
        } catch (ClassNotFoundException e) {
            // SmartStateHook 未实现，降级为不活跃 —— 不记录日志避免刷屏
            smartStateHookAvailable = false;
        } catch (Throwable t) {
            Logger.d("SmartStateHook.isAppActive 反射解析失败: " + t.getMessage());
            smartStateHookAvailable = false;
        }
        smartStateHookResolved = true;
    }
}
