package com.tombstonex.hook;

import com.tombstonex.manager.ConfigManager;
import com.tombstonex.manager.FreezeManager;
import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.manager.WhitelistManager;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import com.tombstonex.util.ReflectionUtils;
import android.os.Build;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ActivitySwitchHook {

    // AOSP 实际调度组常量 (ActivityManager.java / ProcessList.java)
    // 注：SCHED_GROUP_RESTRICTED(1) 已删除，项目中未使用。
    private static final int SCHED_GROUP_BACKGROUND = 0;
    private static final int SCHED_GROUP_DEFAULT = 2;
    private static final int SCHED_GROUP_TOP_APP = 3;

    // L1: RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE = 125
    // 前台服务及更高优先级（IMPORTANCE_FOREGROUND=100 等）的进程需解冻
    private static final int IMPORTANCE_FOREGROUND_SERVICE = 125;

    // AOSP 进程状态常量
    // Android 14+ (SDK 34) PROCESS_STATE_FOREGROUND_SERVICE = 4
    // Android 12-13 PROCESS_STATE_FOREGROUND_SERVICE = 5
    private static final int PROCESS_STATE_FOREGROUND_SERVICE =
        Build.VERSION.SDK_INT >= 34 ? 4 : 5;

    // P2: 2 个核心线程
    private static final ScheduledThreadPoolExecutor freezeExecutor = new ScheduledThreadPoolExecutor(2, r -> {
        Thread t = new Thread(r, "TombstoneX-ActSwitch");
        t.setDaemon(true);
        return t;
    });
    private static final Map<Integer, ScheduledFuture<?>> pendingFreezes = new ConcurrentHashMap<>();
    private static final AtomicBoolean cleanupTaskStarted = new AtomicBoolean(false);
    static {
        // P1-04: 已取消的任务立即从工作队列移除，避免驻留队列造成内存泄漏与重复触发
        freezeExecutor.setRemoveOnCancelPolicy(true);
    }

    // R8-S2: AppConfigManager 结果缓存（5秒TTL），避免在 AMS Handler 线程
    // 每次 Activity 暂停都执行 3 次含文件 IO 的 getConfig 调用
    private static final long APP_CONFIG_CACHE_TTL = 5000L;
    // R11-m-6: 按 packageName 隔离缓存，限制为小型缓存避免无限增长
    private static final int CACHE_MAX_SIZE = 16;
    private static final class CacheEntry {
        final String packageName;
        final boolean shouldFreeze;
        final long timestamp;
        CacheEntry(String p, boolean s, long t) { packageName = p; shouldFreeze = s; timestamp = t; }
    }
    // R11-m-6: 旧实现使用单个 volatile CacheEntry，多包名交替时缓存持续失效（每次都 miss）。
    // 改为 ConcurrentHashMap 按 packageName 隔离，每个包名维护独立的缓存条目。
    private static final ConcurrentHashMap<String, CacheEntry> cacheMap = new ConcurrentHashMap<>();

    public static void init(ClassLoader classLoader) {
        // P3-R3: 定期清理任务延迟到 init() 内启动，避免类加载时即启动后台线程。
        // P3-N1: 使用 AtomicBoolean.compareAndSet 保证原子性，避免并发 init() 重复调度。
        if (cleanupTaskStarted.compareAndSet(false, true)) {
            // P2-05: 定期清理 pendingFreezes 中已不存在于 ProcessTracker 的残留条目。
            // 正常情况下 ProcessDeathHook 会调用 cancelPendingFreeze 清理，但若进程未走
            // 死亡回调（如被直接 kill 且未触发 hook），pendingFreezes 条目会永久残留，
            // 因此每小时兜底清理一次。
            freezeExecutor.scheduleAtFixedRate(() -> {
                try {
                    for (Integer pid : pendingFreezes.keySet()) {
                        if (ProcessTracker.getInstance().getByPid(pid) == null) {
                            ScheduledFuture<?> f = pendingFreezes.remove(pid);
                            if (f != null) {
                                f.cancel(false);
                                Logger.d("已清理残留的 pendingFreezes 条目 pid=" + pid);
                            }
                        }
                    }
                } catch (Throwable t) {
                    Logger.e("pendingFreezes 定期清理出错", t);
                }
            }, 1, 1, TimeUnit.HOURS);
        }
        hookActivityPaused(classLoader);
        hookProcessStateChanged(classLoader);
        hookSetOomAdj(classLoader);
        hookUidStateChanged(classLoader);
    }

    /**
     * Hook activityPaused()
     * Android 11+ 该方法已从 AMS 迁移到 ActivityTaskManagerService (ATMS)
     * 依次尝试 ATMS 和 AMS 两个类，兼容不同版本
     */
    private static void hookActivityPaused(ClassLoader classLoader) {
        // Android 11+ 的目标类
        String[] targetClasses = {
            "com.android.server.wm.ActivityTaskManagerService",  // Android 11+
            "com.android.server.am.ActivityManagerService",       // Android 10 及以下
        };
        boolean hooked = false;
        for (String className : targetClasses) {
            try {
                Class<?> targetClass = XposedHelpers.findClass(className, classLoader);
                XposedHelpers.findAndHookMethod(targetClass,
                    "activityPaused", "android.os.IBinder",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                handleActivityPaused(param);
                            } catch (Throwable t) {
                                Logger.e("activityPaused Hook 出错", t);
                            }
                        }
                    });
                Logger.i("已 Hook activityPaused (类=" + className + ")");
                hooked = true;
                break;
            } catch (Throwable t) {
                Logger.d("Hook activityPaused 在 " + className + " 上失败: " + t.getMessage());
            }
        }
        if (!hooked) {
            Logger.w("Hook activityPaused 失败: ATMS 和 AMS 上均未找到该方法");
        }
    }

    private static void handleActivityPaused(XC_MethodHook.MethodHookParam param) {
        try {
            Object token = param.args[0];
            if (token == null) return;

            Object processRecord = getProcessRecordFromActivity(param.thisObject, token);
            if (processRecord == null) return;

            String processName = (String) getFieldValue(processRecord, "processName");
            // R11-S-1: 使用 getPidFromRecord 统一处理 pid 字段名变更（pid/mPid/getPid()）
            int pid = getPidFromRecord(processRecord);
            // M-2: 使用 getUidFromRecord 统一处理 uid 字段名变更（uid/mUid/getUid()）
            int uid = getUidFromRecord(processRecord);
            String packageName = extractPackageName(processName);

            if (pid <= 0 || packageName == null) return;

            boolean isSystemApp = uid < 10000;
            Logger.d("应用暂停: " + packageName + " pid=" + pid + " uid=" + uid);

            // 检查白名单
            if (!WhitelistManager.getInstance().shouldFreeze(packageName, processName, isSystemApp)) {
                Logger.d("应用在白名单中，跳过冻结: " + packageName);
                return;
            }

            // 检查前台服务保护
            if (hasForegroundService(processRecord)) {
                Logger.d("应用有前台服务，跳过冻结: " + packageName);
                return;
            }

            // 检查 ContentProvider 保护
            if (hasActiveContentProvider(processRecord)) {
                Logger.d("应用有活跃的 ContentProvider，跳过冻结: " + packageName);
                return;
            }

            // R8-S2/m6: 移除 isAnyAudioPlaying() 全局音频检查（2次 Binder IPC 无缓存）。
            // SmartStateHook.isAppActive 已有 per-uid 音频检测含 500ms 缓存，此处冗余。

            // 智能状态识别：通话/定位/录音/相机/VPN/无障碍/输入法/自动填充/悬浮窗/常驻通知
            try {
                if (com.tombstonex.hook.SmartStateHook.isAppActive(uid, packageName)) {
                    Logger.d("应用处于活跃状态（智能状态识别），跳过冻结: " + packageName);
                    return;
                }
            } catch (Throwable t) {
                Logger.d("SmartStateHook 检查失败: " + t.getMessage());
            }

            // R8-S2: 使用缓存的配置检查（5秒TTL），避免每次 Activity 暂停都执行 3 次文件 IO
            if (!getCachedShouldFreeze(packageName)) {
                Logger.d("应用有配置保护（playAllowed/ongoingNotification/netTransfer），跳过冻结: " + packageName);
                return;
            }

            // 注册进程
            ProcessTracker.getInstance().registerProcess(packageName, processName, pid, uid, isSystemApp);

            // 原子地取消旧任务并安排新任务（防竞态）
            final int delaySec = ConfigManager.getInstance().getFreezeDelay();
            final int targetPid = pid;
            final int targetUid = uid;
            final String targetPkg = packageName;
            pendingFreezes.compute(targetPid, (k, oldFuture) -> {
                if (oldFuture != null) oldFuture.cancel(false);
                // R11-M-2: 同时取消 ProcessStartHook 中该 pid 的待冻结任务，
                // 避免两条独立的延迟冻结路径对同一进程重复调度冻结
                ProcessStartHook.cancelPendingFreeze(targetPid);
                // L1: 捕获 ScheduledFuture 引用，finally 中使用条件 remove 避免误删新 future
                ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
                holder[0] = freezeExecutor.schedule(() -> {
                    try {
                        AppInfo info = ProcessTracker.getInstance().getByPid(targetPid);
                        if (info == null) return;
                        if (info.getState() == AppState.FOREGROUND) {
                            Logger.d("应用已返回前台，取消冻结: " + targetPkg);
                            return;
                        }
                        // R11-M-1: 进程已被冻结（可能由其他路径如锁屏批量冻结触发），
                        // 无需再次冻结，直接返回避免重复冻结操作
                        if (info.getState() == AppState.FROZEN) return;
                        if (!WhitelistManager.getInstance().shouldFreeze(targetPkg, info.processName, info.isSystemApp())) {
                            Logger.d("延迟期间应用被加入白名单，取消冻结: " + targetPkg);
                            return;
                        }
                        FreezeManager.getInstance().freezeProcess(targetPid, targetUid);
                    } catch (Throwable t) {
                        Logger.e("延迟冻结出错 pid=" + targetPid, t);
                    } finally {
                        pendingFreezes.remove(targetPid, holder[0]);
                    }
                }, delaySec, TimeUnit.SECONDS);
                return holder[0];
            });
        } catch (Throwable t) {
            Logger.e("handleActivityPaused 出错", t);
        }
    }

    /**
     * 取消待冻结任务（public，供 ProcessDeathHook 调用）
     */
    public static void cancelPendingFreeze(int pid) {
        ScheduledFuture<?> future = pendingFreezes.remove(pid);
        if (future != null) {
            future.cancel(false);
            Logger.d("已取消待冻结任务 pid=" + pid);
        }
    }

    /**
     * M-1: 关闭线程池，供模块卸载时调用，避免线程池永不关闭导致资源泄漏
     */
    public static void shutdown() {
        freezeExecutor.shutdownNow();
        cacheMap.clear();
        pendingFreezes.clear();
        Logger.i("ActivitySwitchHook 已关闭线程池并清理缓存");
    }

    /**
     * R8-S2: 获取缓存的配置检查结果（5秒TTL）。
     * 合并 playAllowed / ongoingNotification / netTransfer 三项检查，
     * 任一为 true 则不应冻结。缓存按包名隔离，避免不同包名共用缓存结果。
     *
     * R11-m-6: 旧实现使用单个 volatile CacheEntry，多包名交替场景下缓存持续失效
     * （每次切换包名都会 miss 并重新计算）。改为 ConcurrentHashMap 按 packageName
     * 隔离缓存，每个包名维护独立条目，限制为最多 16 个条目避免无限增长。
     */
    private static boolean getCachedShouldFreeze(String packageName) {
        if (packageName == null) return true;
        long now = System.currentTimeMillis();
        // R11-m-6: 按 packageName 从隔离缓存中读取，命中且未过期则直接返回
        CacheEntry e = cacheMap.get(packageName);
        if (e != null && (now - e.timestamp) < APP_CONFIG_CACHE_TTL) {
            return e.shouldFreeze;
        }
        // 缓存未命中，重新计算
        boolean result = true;
        try {
            com.tombstonex.manager.AppConfigManager appConfig =
                com.tombstonex.manager.AppConfigManager.getInstance();
            if (appConfig.getConfig(packageName, "playAllowed", false)
                || appConfig.getConfig(packageName, "ongoingNotification", false)
                || appConfig.getConfig(packageName, "netTransfer", false)) {
                result = false;
            }
        } catch (Throwable t) {
            Logger.d("AppConfigManager 配置检查失败: " + t.getMessage());
        }
        // R11-m-6: 写入按 packageName 隔离的缓存条目，限制缓存大小为 CACHE_MAX_SIZE
        if (cacheMap.size() >= CACHE_MAX_SIZE) {
            // 缓存已满，简单清理过期条目腾出空间
            cacheMap.entrySet().removeIf(entry ->
                (now - entry.getValue().timestamp) >= APP_CONFIG_CACHE_TTL);
        }
        // m-2: 清理后再次检查 size，若仍满则移除最旧条目
        if (cacheMap.size() >= CACHE_MAX_SIZE) {
            String oldestKey = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, CacheEntry> entry : cacheMap.entrySet()) {
                if (entry.getValue().timestamp < oldestTime) {
                    oldestTime = entry.getValue().timestamp;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey != null) {
                cacheMap.remove(oldestKey);
            }
        }
        cacheMap.put(packageName, new CacheEntry(packageName, result, now));
        return result;
    }

    /**
     * Hook 进程调度组变化 — 前台时解冻
     */
    private static void hookProcessStateChanged(ClassLoader classLoader) {
        try {
            Class<?> processRecordClass = XposedHelpers.findClass(
                "com.android.server.am.ProcessRecord", classLoader);

            // Hook setCurrentSchedulingGroup — 当变为 TOP 时解冻
            // Android 14+ 此方法可能在 ProcessStateRecord 上而非 ProcessRecord
            // R8-M5: Android 14+ 参数可能从 int 改为 long，同时尝试两种变体
            Method setSchedGroupMethod = ReflectionUtils.findMethodRecursive(
                processRecordClass, "setCurrentSchedulingGroup", int.class);
            if (setSchedGroupMethod == null) {
                setSchedGroupMethod = ReflectionUtils.findMethodRecursive(
                    processRecordClass, "setCurrentSchedulingGroup", long.class);
            }
            if (setSchedGroupMethod != null) {
                XposedHelpers.findAndHookMethod(setSchedGroupMethod,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                // R8-M5: 兼容 int/long 参数类型
                                int schedulingGroup = ((Number) param.args[0]).intValue();
                                // P0: 解冻条件改为 >= SCHED_GROUP_DEFAULT (即 >=2)
                                if (schedulingGroup >= SCHED_GROUP_DEFAULT) {
                                    Object processRecord = param.thisObject;
                                    // R11-S-1: 使用 getPidFromRecord 统一处理 pid 字段名变更
                                    int pid = getPidFromRecord(processRecord);
                                    // M-2: 使用 getUidFromRecord 统一处理 uid 字段名变更
                                    int uid = getUidFromRecord(processRecord);
                                    if (pid > 0) {
                                        cancelPendingFreeze(pid);
                                        // R8-S5: 同时取消 ProcessStartHook 的待冻结任务
                                        ProcessStartHook.cancelPendingFreeze(pid);
                                        boolean unfrozen = FreezeManager.getInstance().unfreezeProcess(pid, uid);
                                        if (unfrozen) {
                                            ProcessTracker.getInstance().updateState(pid, AppState.FOREGROUND);
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                Logger.e("setCurrentSchedulingGroup Hook 出错", t);
                            }
                        }
                    });
                Logger.i("已 Hook setCurrentSchedulingGroup");
            } else {
                // Android 14+：尝试在 ProcessStateRecord 上查找
                hookSetSchedulingGroupOnStateRecord(processRecordClass, classLoader);
            }

            // Hook setProcessState — 更精确的进程状态追踪
            // Android 14+ 此方法也可能在 ProcessStateRecord 上
            // R8-M5: Android 14+ 参数可能从 int 改为 long，同时尝试两种变体
            Method setProcessStateMethod = ReflectionUtils.findMethodRecursive(
                processRecordClass, "setProcessState", int.class, int.class, int.class);
            if (setProcessStateMethod == null) {
                setProcessStateMethod = ReflectionUtils.findMethodRecursive(
                    processRecordClass, "setProcessState", long.class, long.class, long.class);
            }
            if (setProcessStateMethod != null) {
                try {
                    XposedHelpers.findAndHookMethod(setProcessStateMethod,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    // R8-M5: 兼容 int/long 参数类型
                                    int procState = ((Number) param.args[0]).intValue();
                                    Object processRecord = param.thisObject;
                                    // R11-S-1: 使用 getPidFromRecord 统一处理 pid 字段名变更
                                    int pid = getPidFromRecord(processRecord);
                                    if (pid <= 0) return;

                                    // M-2: 使用 getUidFromRecord 统一处理 uid 字段名变更
                                    int uid = getUidFromRecord(processRecord);
                                    // P3-R2: 合并冗余分支，PROCESS_STATE_TOP <= PROCESS_STATE_FOREGROUND_SERVICE
                                    if (procState <= PROCESS_STATE_FOREGROUND_SERVICE) {
                                        // 前台应用（TOP）和前台服务均需解冻
                                        cancelPendingFreeze(pid);
                                        // R8-S5: 同时取消 ProcessStartHook 的待冻结任务
                                        ProcessStartHook.cancelPendingFreeze(pid);
                                        boolean unfrozen = FreezeManager.getInstance().unfreezeProcess(pid, uid);
                                        if (unfrozen) {
                                            ProcessTracker.getInstance().updateState(pid, AppState.FOREGROUND);
                                        }
                                    }
                                } catch (Throwable t) {
                                    Logger.e("setProcessState Hook 出错", t);
                                }
                            }
                        });
                    Logger.i("已 Hook setProcessState");
                } catch (Throwable e) {
                    Logger.d("setProcessState Hook 变体失败: " + e.getMessage());
                }
            } else {
                // Android 14+：尝试在 ProcessStateRecord 上查找
                hookSetProcessStateOnStateRecord(processRecordClass, classLoader);
            }
        } catch (Throwable t) {
            Logger.e("Hook 进程状态失败", t);
        }
    }

    /**
     * Android 14+：setCurrentSchedulingGroup 可能在 ProcessStateRecord 上
     */
    private static void hookSetSchedulingGroupOnStateRecord(
            Class<?> processRecordClass, ClassLoader classLoader) {
        try {
            // S-3: 查找 ProcessRecord 上的 ProcessStateRecord 字段。
            // Android 12+ 使用 m-prefix 命名 "mProcessStateRecord"，先尝试该字段名，
            // 再尝试旧版本字段名 "processStateRecord"。
            Object stateRecord = null;
            Field stateField = ReflectionUtils.findFieldRecursive(processRecordClass, "mProcessStateRecord");
            if (stateField == null) {
                stateField = ReflectionUtils.findFieldRecursive(processRecordClass, "processStateRecord");
            }
            if (stateField == null) return;

            Class<?> stateRecordClass = stateField.getType();
            // R8-M5: Android 14+ 参数可能从 int 改为 long，同时尝试两种变体
            Method method = ReflectionUtils.findMethodRecursive(
                stateRecordClass, "setCurrentSchedulingGroup", int.class);
            if (method == null) {
                method = ReflectionUtils.findMethodRecursive(
                    stateRecordClass, "setCurrentSchedulingGroup", long.class);
            }
            if (method == null) return;

            XposedHelpers.findAndHookMethod(method,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            // R8-M5: 兼容 int/long 参数类型
                            int schedulingGroup = ((Number) param.args[0]).intValue();
                            if (schedulingGroup >= SCHED_GROUP_DEFAULT) {
                                // 需要从 ProcessStateRecord 回溯到 ProcessRecord 获取 pid
                                // ProcessStateRecord 通常持有对 ProcessRecord 的引用
                                Object stateRec = param.thisObject;
                                Object processRecord = getFieldValue(stateRec, "mApp");
                                if (processRecord == null) processRecord = getFieldValue(stateRec, "app");
                                if (processRecord == null) return;
                                // R11-S-1: 使用 getPidFromRecord 统一处理 pid 字段名变更
                                int pid = getPidFromRecord(processRecord);
                                // M-2: 使用 getUidFromRecord 统一处理 uid 字段名变更
                                int uid = getUidFromRecord(processRecord);
                                if (pid > 0) {
                                    cancelPendingFreeze(pid);
                                    // R8-S5: 同时取消 ProcessStartHook 的待冻结任务
                                    ProcessStartHook.cancelPendingFreeze(pid);
                                    boolean unfrozen = FreezeManager.getInstance().unfreezeProcess(pid, uid);
                                    if (unfrozen) {
                                        ProcessTracker.getInstance().updateState(pid, AppState.FOREGROUND);
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            Logger.e("setCurrentSchedulingGroup (StateRecord) Hook 出错", t);
                        }
                    }
                });
            Logger.i("已在 ProcessStateRecord 上 Hook setCurrentSchedulingGroup");
        } catch (Throwable t) {
            Logger.w("在 StateRecord 上 Hook setCurrentSchedulingGroup 失败: " + t.getMessage());
        }
    }

    /**
     * Android 14+：setProcessState 可能在 ProcessStateRecord 上
     */
    private static void hookSetProcessStateOnStateRecord(
            Class<?> processRecordClass, ClassLoader classLoader) {
        try {
            // S-3: 查找 ProcessRecord 上的 ProcessStateRecord 字段。
            // Android 12+ 使用 m-prefix 命名，先尝试 "mProcessStateRecord"，
            // 再尝试旧版本 "processStateRecord"。
            Field stateField = ReflectionUtils.findFieldRecursive(processRecordClass, "mProcessStateRecord");
            if (stateField == null) {
                stateField = ReflectionUtils.findFieldRecursive(processRecordClass, "processStateRecord");
            }
            if (stateField == null) return;

            Class<?> stateRecordClass = stateField.getType();
            // R8-M5: Android 14+ 参数可能从 int 改为 long，同时尝试两种变体
            Method method = ReflectionUtils.findMethodRecursive(
                stateRecordClass, "setProcessState", int.class, int.class, int.class);
            if (method == null) {
                method = ReflectionUtils.findMethodRecursive(
                    stateRecordClass, "setProcessState", long.class, long.class, long.class);
            }
            if (method == null) return;

            XposedHelpers.findAndHookMethod(method,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            // R8-M5: 兼容 int/long 参数类型
                            int procState = ((Number) param.args[0]).intValue();
                            Object stateRec = param.thisObject;
                            Object processRecord = getFieldValue(stateRec, "mApp");
                            if (processRecord == null) processRecord = getFieldValue(stateRec, "app");
                            if (processRecord == null) return;
                            // R11-S-1: 使用 getPidFromRecord 统一处理 pid 字段名变更
                            int pid = getPidFromRecord(processRecord);
                            if (pid <= 0) return;

                            // M-2: 使用 getUidFromRecord 统一处理 uid 字段名变更
                            int uid = getUidFromRecord(processRecord);
                            // P3-R2: 合并冗余分支
                            if (procState <= PROCESS_STATE_FOREGROUND_SERVICE) {
                                cancelPendingFreeze(pid);
                                // R8-S5: 同时取消 ProcessStartHook 的待冻结任务
                                ProcessStartHook.cancelPendingFreeze(pid);
                                boolean unfrozen = FreezeManager.getInstance().unfreezeProcess(pid, uid);
                                if (unfrozen) {
                                    ProcessTracker.getInstance().updateState(pid, AppState.FOREGROUND);
                                }
                            }
                        } catch (Throwable t) {
                            Logger.e("setProcessState (StateRecord) Hook 出错", t);
                        }
                    }
                });
            Logger.i("已在 ProcessStateRecord 上 Hook setProcessState");
        } catch (Throwable t) {
            Logger.w("在 StateRecord 上 Hook setProcessState 失败: " + t.getMessage());
        }
    }

    /**
     * Hook OOM adj 变化
     * setOomAdj 会被系统为所有进程调用，是自动发现已运行进程的理想入口。
     * 注意：OOM adj 调优必须在 beforeHookedMethod 中修改 param.args 才能生效，
     *       在 afterHookedMethod 中修改 args 对已执行的方法无效。
     * 自动注册逻辑放在 afterHookedMethod（不需要修改参数）。
     *
     * S2 修复：Android 14+ 上 setOomAdj 已从 AMS 迁移到 OomAdjuster，
     *         先尝试 AMS.setOomAdj，失败则尝试 OomAdjuster.setOomAdj。
     */
    private static void hookSetOomAdj(ClassLoader classLoader) {
        // 共享回调逻辑（AMS 与 OomAdjuster 的 setOomAdj 签名均为 (int, int, int)）
        XC_MethodHook callback = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    int pid = (int) param.args[0];
                    int uid = (int) param.args[1];
                    int oomAdj = (int) param.args[2];

                    // 若进程尚未注册（模块加载前已运行的进程），先注册
                    // 这样首次 setOomAdj 也能被 OomAdjManager 调整
                    if (ProcessTracker.getInstance().getByPid(pid) == null) {
                        tryAutoRegisterProcess(pid, uid);
                    }

                    // R8-m8: 在 beforeHookedMethod 中用原始值更新 OomAdj，
                    // 避免 afterHookedMethod 读取到被 applyOomAdj 修改后的 param.args[2]
                    try {
                        ProcessTracker.getInstance().updateOomAdj(pid, oomAdj);
                    } catch (Throwable ignored) {
                            Logger.d("updateOomAdj 失败 pid=" + pid + ": " + ignored.getMessage());
                        }

                    // OOM adj 调优：必须在方法执行前修改参数才能生效
                    // R8-M12: 仅对用户应用（uid >= 10000）执行调优，系统进程不调优
                    if (uid >= 10000) {
                        try {
                            int adjusted = com.tombstonex.manager.OomAdjManager.getInstance().applyOomAdj(uid, pid, oomAdj);
                            if (adjusted != oomAdj) {
                                param.args[2] = adjusted;
                            }
                        } catch (Throwable t) {
                            Logger.d("OomAdjManager 调优失败: " + t.getMessage());
                        }
                    }
                } catch (Throwable t) {
                    Logger.w("setOomAdj beforeHook 出错: " + t.getMessage());
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                // R10-m-6: 移除冗余的自动注册检查（getByPid + tryAutoRegisterProcess）。
                // beforeHookedMethod 中已执行相同的注册逻辑，afterHookedMethod 中重复执行
                // 仅增加 Binder 线程开销而无额外收益。updateOomAdj 也已在 beforeHookedMethod
                // 中使用原始值完成，此处无需任何操作。
            }
        };

        // 先尝试 AMS.setOomAdj（Android 13 及以下）
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);
            Method method = ReflectionUtils.findMethodRecursive(
                amsClass, "setOomAdj", int.class, int.class, int.class);
            if (method != null) {
                XposedBridge.hookMethod(method, callback);
                Logger.i("已 Hook AMS.setOomAdj");
                return;
            }
        } catch (Throwable t) {
            Logger.d("AMS.setOomAdj 不可用: " + t.getMessage());
        }

        // Android 14+: 尝试 OomAdjuster.setOomAdj
        try {
            Class<?> oomAdjusterClass = XposedHelpers.findClass(
                "com.android.server.am.OomAdjuster", classLoader);
            Method method = ReflectionUtils.findMethodRecursive(
                oomAdjusterClass, "setOomAdj", int.class, int.class, int.class);
            if (method != null) {
                XposedBridge.hookMethod(method, callback);
                Logger.i("已 Hook OomAdjuster.setOomAdj (Android 14+)");
                return;
            }
        } catch (Throwable t) {
            Logger.d("OomAdjuster.setOomAdj 不可用: " + t.getMessage());
        }

        Logger.w("Hook setOomAdj 失败：AMS 和 OomAdjuster 均不可用");
    }

    /**
     * 通过读取 /proc/<pid>/cmdline 自动注册未跟踪的进程。
     * 在 system_server 中有权限读取 /proc 文件系统。
     *
     * M9 修复：每次 setOomAdj 回调（Binder 线程）都会执行文件 I/O，
     * 对已注册的进程重复读取 /proc/<pid>/cmdline 是无意义的开销。
     * 先检查 ProcessTracker 是否已注册该 pid，若已注册则直接跳过。
     */
    private static void tryAutoRegisterProcess(int pid, int uid) {
        if (pid <= 0) return;
        // M9: 已注册的进程跳过文件 I/O，避免 Binder 线程上的重复开销
        if (ProcessTracker.getInstance().getByPid(pid) != null) return;
        try {
            java.io.File cmdFile = new java.io.File("/proc/" + pid + "/cmdline");
            if (!cmdFile.exists()) return;
            // 使用 FileInputStream 替代 java.nio.file.Files，兼容所有 Android 版本
            String processName = readFirstCmdlineSegment(cmdFile);
            if (processName == null || processName.isEmpty()) return;

            String packageName = extractPackageName(processName);
            if (packageName == null) return;

            boolean isSystemApp = uid < 10000;
            ProcessTracker.getInstance().registerProcess(packageName, processName, pid, uid, isSystemApp);

            // 根据 uid 判断初始状态：系统进程或前台进程不设为 BACKGROUND
            // 这里只注册，不改变状态。状态会由后续 hook 更新。
            Logger.d("通过 setOomAdj 自动注册进程: " + processName + " pid=" + pid + " uid=" + uid);
        } catch (Throwable t) {
            Logger.d("自动注册进程失败 pid=" + pid + ": " + t.getMessage());
        }
    }

    /**
     * 读取 /proc/<pid>/cmdline 的第一段（null 字节前的内容）。
     * 使用 FileInputStream 替代 java.nio.file.Files 以确保兼容性。
     */
    private static String readFirstCmdlineSegment(java.io.File cmdFile) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(cmdFile)) {
            byte[] buf = new byte[512];
            int len = fis.read(buf);
            if (len <= 0) return null;
            int end = 0;
            while (end < len && buf[end] != 0) end++;
            if (end == 0) return null;
            return new String(buf, 0, end, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Hook UID 状态变化 — 更精确的前台/后台判断
     *
     * M4 修复：旧代码仅尝试 (int, int) 签名，部分 Android 版本上
     * reportUidImportanceChanged / onUidStateChanged 带有第三个参数
     * (int uid, int importance, int...) 或 (int uid, int importance, long...)，
     * 导致 hook 失败。改为遍历多种参数签名，命中即 hook。
     */
    private static void hookUidStateChanged(ClassLoader classLoader) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);

            // M4: 尝试多种方法名 + 多种参数签名以适配不同 Android 版本
            String[] methodNames = {"reportUidImportanceChanged", "onUidStateChanged"};
            Class<?>[][] paramVariants = {
                {int.class, int.class},
                {int.class, int.class, int.class},
                {int.class, int.class, long.class},
            };

            boolean anyHooked = false;
            // R8-M6: 移除 break outer，对所有匹配变体都进行 hook，
            // 避免遗漏其他重载变体（不同 Android 版本可能同时存在多个重载）
            // R9-m3: 若 reportUidImportanceChanged 和 onUidStateChanged 同时存在，
            // 同一事件可能触发两次回调。由于回调内操作（取消 pending freeze / 解冻 / 更新状态）
            // 均幂等，重复触发无功能影响。
            for (String methodName : methodNames) {
                for (Class<?>[] params : paramVariants) {
                    try {
                        Method method = XposedHelpers.findMethodExact(
                            amsClass, methodName, params);
                        if (method == null) continue;
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    int uid = (int) param.args[0];
                                    int importance = (int) param.args[1];
                                    // IMPORTANCE_FOREGROUND = 100
                                    if (importance <= IMPORTANCE_FOREGROUND_SERVICE) {
                                        // P2: 使用 getByUid 而非遍历全部进程
                                        List<AppInfo> uidProcesses = ProcessTracker.getInstance().getByUid(uid);
                                        for (AppInfo info : uidProcesses) {
                                            // S1: 无条件取消 pending freeze，防止 BACKGROUND 进程的 pending freeze 误冻结前台进程
                                            cancelPendingFreeze(info.pid);
                                            // R8-S5: 同时取消 ProcessStartHook 的待冻结任务
                                            ProcessStartHook.cancelPendingFreeze(info.pid);
                                            if (info.getState() == AppState.FROZEN) {
                                                boolean unfrozen = FreezeManager.getInstance().unfreezeProcess(info.pid, info.uid);
                                                if (unfrozen) {
                                                    ProcessTracker.getInstance().updateState(info.pid, AppState.FOREGROUND);
                                                }
                                            } else {
                                                // R7: 非 FROZEN 进程也更新为 FOREGROUND，防止 pending freeze 误冻结前台进程
                                                // 但 KILLED 状态的进程不应被更新为 FOREGROUND（避免对死亡进程状态覆盖）
                                                if (info.getState() != AppState.KILLED) {
                                                    ProcessTracker.getInstance().updateState(info.pid, AppState.FOREGROUND);
                                                }
                                            }
                                        }
                                    }
                                } catch (Throwable t) {
                                    Logger.e("uidStateChanged Hook 出错", t);
                                }
                            }
                        });
                        Logger.i("已 Hook " + methodName + " (" + params.length + " 个参数)");
                        anyHooked = true;
                        // m-5: 成功 hook 后 break，避免对同一方法名的多个参数变体重复 hook
                        break;
                    } catch (Throwable e) {
                        Logger.d("Hook 变体失败: " + e.getMessage());
                    }
                }
            }
            if (!anyHooked) {
                Logger.w("Hook uid 状态变化失败：所有方法名与签名变体均不可用");
            }
        } catch (Throwable t) {
            Logger.e("Hook uid 状态变化失败", t);
        }
    }

    // --- 保护性检查 ---

    /**
     * 检查是否有前台服务
     *
     * AOSP 中 hasForegroundServices() 方法直接在 ProcessRecord 上，
     * 而非在 mServices 字段值上。旧代码先取 mServices 字段再到字段值上找方法，
     * 导致找不到方法而返回 true（过度保守，几乎所有进程都被跳过冻结）。
     *
     * 修复：直接在 processRecord 上调用 hasForegroundServices() 方法。
     * public 供 ScreenStateHook 复用
     */
    public static boolean hasForegroundService(Object processRecord) {
        try {
            // 直接在 ProcessRecord 上查找 hasForegroundServices / hasForeground 方法
            String[] methodNames = {"hasForegroundServices", "hasForeground"};
            for (String name : methodNames) {
                Method hasForeground = ReflectionUtils.findMethodRecursive(
                    processRecord.getClass(), name);
                if (hasForeground != null) {
                    hasForeground.setAccessible(true);
                    return (boolean) hasForeground.invoke(processRecord);
                }
            }
            // 方法不存在时 fail-safe 返回 true（不冻结）
            // R10-m-7: 提升为 Logger.w，以便更容易发现 API 兼容性问题
            Logger.w("ProcessRecord 上未找到 hasForegroundServices 方法");
        } catch (Throwable e) {
            // R10-m-7: 提升为 Logger.w，以便更容易发现 API 兼容性问题
            Logger.w("hasForegroundService 检查失败: " + e.getMessage());
        }
        return true;
    }

    /**
     * 检查是否有活跃的 ContentProvider
     * 遍历 pubProviders 检查 size > 0，而非依赖 toString().contains("->")
     * public 供 ScreenStateHook 复用
     */
    public static boolean hasActiveContentProvider(Object processRecord) {
        try {
            // R11-S-3: AOSP 12+ 中 pubProviders 字段名变为 mPubProviders，
            // 且可能在 ProcessProviderRecord 对象上而非 ProcessRecord 上。
            // 依次尝试 "pubProviders" → "mPubProviders" → 通过 "mProviders" 获取 ProcessProviderRecord 后在其上查找 "mPubProviders"。
            Field pubProvidersField = ReflectionUtils.findFieldRecursive(
                processRecord.getClass(), "pubProviders");
            if (pubProvidersField == null) {
                // R11-S-3: AOSP 12+ 字段名变为 "mPubProviders"
                pubProvidersField = ReflectionUtils.findFieldRecursive(
                    processRecord.getClass(), "mPubProviders");
            }
            Object pubProviders = null;
            if (pubProvidersField == null) {
                // R11-S-3: AOSP 12+ pubProviders 迁移至 ProcessProviderRecord
                Field providersField = ReflectionUtils.findFieldRecursive(
                    processRecord.getClass(), "mProviders");
                if (providersField != null) {
                    Object providers = ReflectionUtils.getFieldValue(processRecord, providersField);
                    if (providers != null) {
                        // 在 providers（ProcessProviderRecord）对象上查找 mPubProviders
                        pubProvidersField = ReflectionUtils.findFieldRecursive(
                            providers.getClass(), "mPubProviders");
                        if (pubProvidersField == null) {
                            // 兼容旧字段名
                            pubProvidersField = ReflectionUtils.findFieldRecursive(
                                providers.getClass(), "pubProviders");
                        }
                        if (pubProvidersField != null) {
                            pubProviders = ReflectionUtils.getFieldValue(providers, pubProvidersField);
                            // 注意：在 providers 对象上操作而非 processRecord
                        }
                    }
                }
            } else {
                pubProviders = ReflectionUtils.getFieldValue(processRecord, pubProvidersField);
            }
            if (pubProvidersField == null) return true;
            if (pubProviders == null) return false;

            // pubProviders 通常是 ArrayMap<String, ContentProviderRecord>
            // R8-M4: 旧代码只要 pubProviders 非空就返回 true（过度保守），
            // 只要进程发布过 ContentProvider 就跳过冻结，不检查是否有活跃客户端。
            // 现在检查 ContentProviderRecord 的 connections 是否有活跃连接，
            // 无活跃连接的 ContentProvider 不阻止冻结。
            if (pubProviders instanceof Map) {
                for (Object value : ((Map<?, ?>) pubProviders).values()) {
                    Object connections = getFieldValue(value, "connections");
                    // M-3: 添加 mConnections fallback，兼容 AOSP 12+ 字段名变更
                    if (connections == null) {
                        connections = getFieldValue(value, "mConnections");
                    }
                    if (connections instanceof List && !((List<?>) connections).isEmpty()) {
                        return true;
                    }
                }
                return false;
            }
            // 尝试通过反射获取 size()
            Method sizeMethod = ReflectionUtils.findMethodRecursive(pubProviders.getClass(), "size");
            if (sizeMethod != null) {
                int size = (int) sizeMethod.invoke(pubProviders);
                return size > 0;
            }
        } catch (Throwable e) {
            // R10-m-7: 提升为 Logger.w，以便更容易发现 API 兼容性问题
            Logger.w("hasActiveContentProvider 检查失败: " + e.getMessage());
        }
        return true;
    }

    /**
     * 通过反射调用 AudioManager.isMusicActive() 检查是否有活跃音频播放
     * public 供 ScreenStateHook 复用
     *
     * S3 修复：失败时 fail-closed（返回 true，保守认为有音频播放，跳过冻结），
     * 避免音乐应用因检测失败被误冻结。
     */
    public static boolean isAnyAudioPlaying() {
        try {
            // 在 system_server 中可以通过 ServiceManager 获取 AudioService
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getServiceMethod = serviceManagerClass.getMethod("getService", String.class);
            Object binder = getServiceMethod.invoke(null, "audio");
            if (binder == null) return true; // fail-closed: 保守认为有音频播放

            Class<?> stubClass = Class.forName("android.media.IAudioService$Stub");
            Method asInterfaceMethod = stubClass.getMethod("asInterface", android.os.IBinder.class);
            Object audioService = asInterfaceMethod.invoke(null, binder);
            if (audioService == null) return true; // fail-closed

            // 调用 isMusicActive()
            Method isMusicActiveMethod = ReflectionUtils.findMethodRecursive(
                audioService.getClass(), "isMusicActive");
            if (isMusicActiveMethod == null) return true; // fail-closed: 未找到方法
            return (boolean) isMusicActiveMethod.invoke(audioService);
        } catch (Throwable t) {
            Logger.w("检查音频播放状态失败，保守认为有音频播放: " + t.getMessage());
            return true; // fail-closed: 失败时不冻结
        }
    }

    // --- 辅助方法 ---
    // R11-m-5: 旧注释声称 ReflectionUtils 未缓存 Field，此描述已过时。
    // ReflectionUtils 已实现 fieldCache 和 methodCache，按 (className, fieldName/methodName) 缓存
    // 反射查找结果并 setAccessible，避免每次调用都沿继承链重新查找。
    // getProcessRecordByPid / hasForegroundService / hasActiveContentProvider 等保护性检查
    // 高频调用 getFieldValue / getIntFieldValue 时的反射开销已被缓存显著降低。

    /**
     * 安全获取 int 类型字段值，先获取 Object 再 null 检查再拆箱
     */
    private static int getIntFieldValue(Object obj, String fieldName) {
        Object val = getFieldValue(obj, fieldName);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return -1;
    }

    /**
     * R11-S-1: 统一从 ProcessRecord 提取 pid。
     * AOSP 12+ 中 ProcessRecord.pid 字段改名为 mPid，导致 getIntFieldValue(processRecord, "pid") 失败。
     * 此方法依次尝试 "pid" 字段、"mPid" 字段、getPid() 方法，兼容不同 AOSP 版本。
     */
    private static int getPidFromRecord(Object processRecord) {
        if (processRecord == null) return -1;
        try {
            // 先尝试旧字段名 "pid"
            Field pidField = ReflectionUtils.findFieldRecursive(
                processRecord.getClass(), "pid");
            if (pidField == null) {
                // R11-S-1: AOSP 12+ 字段名变为 "mPid"
                pidField = ReflectionUtils.findFieldRecursive(
                    processRecord.getClass(), "mPid");
            }
            if (pidField != null) {
                Object val = ReflectionUtils.getFieldValue(processRecord, pidField);
                if (val instanceof Number) {
                    return ((Number) val).intValue();
                }
            }
            // R11-S-1: 两个字段名均未找到，尝试调用 getPid() 方法作为最后手段
            Method getPidMethod = ReflectionUtils.findMethodRecursive(
                processRecord.getClass(), "getPid");
            if (getPidMethod != null) {
                Object val = getPidMethod.invoke(processRecord);
                if (val instanceof Number) {
                    return ((Number) val).intValue();
                }
            }
        } catch (Throwable t) {
            Logger.w("getPidFromRecord 获取 pid 失败: " + t.getMessage());
        }
        return -1;
    }

    /**
     * M-2: 统一从 ProcessRecord 提取 uid。
     * AOSP 12+ 中 ProcessRecord.uid 字段可能改名为 mUid，或通过 getUid() 方法获取。
     * 此方法依次尝试 "uid" 字段、"mUid" 字段、getUid() 方法，兼容不同 AOSP 版本。
     */
    private static int getUidFromRecord(Object processRecord) {
        if (processRecord == null) return -1;
        try {
            // 先尝试旧字段名 "uid"
            Field uidField = ReflectionUtils.findFieldRecursive(
                processRecord.getClass(), "uid");
            if (uidField == null) {
                // M-2: AOSP 12+ 字段名可能变为 "mUid"
                uidField = ReflectionUtils.findFieldRecursive(
                    processRecord.getClass(), "mUid");
            }
            if (uidField != null) {
                Object val = ReflectionUtils.getFieldValue(processRecord, uidField);
                if (val instanceof Number) {
                    return ((Number) val).intValue();
                }
            }
            // M-2: 两个字段名均未找到，尝试调用 getUid() 方法作为最后手段
            Method getUidMethod = ReflectionUtils.findMethodRecursive(
                processRecord.getClass(), "getUid");
            if (getUidMethod != null) {
                Object val = getUidMethod.invoke(processRecord);
                if (val instanceof Number) {
                    return ((Number) val).intValue();
                }
            }
        } catch (Throwable t) {
            Logger.w("getUidFromRecord 获取 uid 失败: " + t.getMessage());
        }
        return -1;
    }

    /**
     * 从 Activity token 获取 ProcessRecord
     * 兼容 AMS 和 ATMS 两种入参：
     * - 若传入的是 AMS，从其 mAtmService 字段取出 ATMS
     * - 若传入的已是 ATMS，直接使用
     */
    private static Object getProcessRecordFromActivity(Object amsOrAtms, Object token) {
        try {
            Object atms = amsOrAtms;
            // 尝试从 mAtmService 字段获取 ATMS（AMS 入参时）
            Object maybeAtms = getFieldValue(amsOrAtms, "mAtmService");
            if (maybeAtms != null) {
                atms = maybeAtms;
            }

            Method getActivityRecord = ReflectionUtils.findMethodRecursive(
                atms.getClass(), "getActivityRecord", android.os.IBinder.class);
            if (getActivityRecord == null) return null;

            Object activityRecord = null;
            try {
                activityRecord = getActivityRecord.invoke(atms, token);
            } catch (Exception e) {
                return null;
            }

            if (activityRecord == null) return null;
            // S-3: 添加 mApp fallback，兼容 AOSP 12+ 字段名变更
            Object processRecord = getFieldValue(activityRecord, "app");
            if (processRecord == null) {
                processRecord = getFieldValue(activityRecord, "mApp");
            }
            return processRecord;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String extractPackageName(String processName) {
        if (processName == null) return null;
        int colonIdx = processName.indexOf(':');
        if (colonIdx == 0) return null; // 冒号开头的进程名无包名
        if (colonIdx > 0) return processName.substring(0, colonIdx);
        return processName;
    }

    private static Object getFieldValue(Object obj, String fieldName) {
        Field field = ReflectionUtils.findFieldRecursive(obj.getClass(), fieldName);
        return field != null ? ReflectionUtils.getFieldValue(obj, field) : null;
    }
}
