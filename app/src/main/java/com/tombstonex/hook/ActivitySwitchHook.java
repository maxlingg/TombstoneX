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

    // AOSP 进程状态常量
    // Android 14+ (SDK 34) PROCESS_STATE_FOREGROUND_SERVICE = 4
    // Android 12-13 PROCESS_STATE_FOREGROUND_SERVICE = 5
    private static final int PROCESS_STATE_FOREGROUND_SERVICE =
        Build.VERSION.SDK_INT >= 34 ? 4 : 5;

    // P2: 2 个核心线程
    private static final ScheduledThreadPoolExecutor freezeExecutor = new ScheduledThreadPoolExecutor(2);
    private static final Map<Integer, ScheduledFuture<?>> pendingFreezes = new ConcurrentHashMap<>();
    private static final AtomicBoolean cleanupTaskStarted = new AtomicBoolean(false);
    static {
        // P1-04: 已取消的任务立即从工作队列移除，避免驻留队列造成内存泄漏与重复触发
        freezeExecutor.setRemoveOnCancelPolicy(true);
    }

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
                // 此类上没有该方法，尝试下一个
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
            int pid = getIntFieldValue(processRecord, "pid");
            int uid = getIntFieldValue(processRecord, "uid");
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

            // 检查音频播放
            if (isAnyAudioPlaying()) {
                Logger.d("应用正在播放音频，跳过冻结: " + packageName);
                return;
            }

            // 智能状态识别：通话/定位/录音/相机/VPN/无障碍/输入法/自动填充/悬浮窗/常驻通知
            try {
                if (com.tombstonex.hook.SmartStateHook.isAppActive(uid, packageName)) {
                    Logger.d("应用处于活跃状态（智能状态识别），跳过冻结: " + packageName);
                    return;
                }
            } catch (Throwable t) {
                // SmartStateHook 可能未初始化，忽略
            }

            // 检查应用级配置：后台播放/常驻通知/网速识别
            try {
                com.tombstonex.manager.AppConfigManager appConfig = com.tombstonex.manager.AppConfigManager.getInstance();
                if (appConfig.getConfig(packageName, "playAllowed", false)) {
                    Logger.d("应用有 playAllowed 配置，跳过冻结: " + packageName);
                    return;
                }
                if (appConfig.getConfig(packageName, "ongoingNotification", false)) {
                    Logger.d("应用有 ongoingNotification 配置，跳过冻结: " + packageName);
                    return;
                }
                if (appConfig.getConfig(packageName, "netTransfer", false)) {
                    Logger.d("应用有 netTransfer 配置，跳过冻结: " + packageName);
                    return;
                }
            } catch (Throwable t) {
                // AppConfigManager 可能未初始化，忽略
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
                return freezeExecutor.schedule(() -> {
                    try {
                        AppInfo info = ProcessTracker.getInstance().getByPid(targetPid);
                        if (info == null) return;
                        if (info.state == AppState.FOREGROUND) {
                            Logger.d("应用已返回前台，取消冻结: " + targetPkg);
                            return;
                        }
                        if (!WhitelistManager.getInstance().shouldFreeze(targetPkg, info.processName, info.isSystemApp)) {
                            Logger.d("延迟期间应用被加入白名单，取消冻结: " + targetPkg);
                            return;
                        }
                        FreezeManager.getInstance().freezeProcess(targetPid, targetUid);
                    } catch (Throwable t) {
                        Logger.e("延迟冻结出错 pid=" + targetPid, t);
                    }
                }, delaySec, TimeUnit.SECONDS);
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
     * Hook 进程调度组变化 — 前台时解冻
     */
    private static void hookProcessStateChanged(ClassLoader classLoader) {
        try {
            Class<?> processRecordClass = XposedHelpers.findClass(
                "com.android.server.am.ProcessRecord", classLoader);

            // Hook setCurrentSchedulingGroup — 当变为 TOP 时解冻
            // Android 14+ 此方法可能在 ProcessStateRecord 上而非 ProcessRecord
            Method setSchedGroupMethod = ReflectionUtils.findMethodRecursive(
                processRecordClass, "setCurrentSchedulingGroup", int.class);
            if (setSchedGroupMethod != null) {
                XposedHelpers.findAndHookMethod(setSchedGroupMethod,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                int schedulingGroup = (int) param.args[0];
                                // P0: 解冻条件改为 >= SCHED_GROUP_DEFAULT (即 >=2)
                                if (schedulingGroup >= SCHED_GROUP_DEFAULT) {
                                    Object processRecord = param.thisObject;
                                    int pid = getIntFieldValue(processRecord, "pid");
                                    int uid = getIntFieldValue(processRecord, "uid");
                                    if (pid > 0) {
                                        cancelPendingFreeze(pid);
                                        FreezeManager.getInstance().unfreezeProcess(pid, uid);
                                        ProcessTracker.getInstance().updateState(pid, AppState.FOREGROUND);
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
            Method setProcessStateMethod = ReflectionUtils.findMethodRecursive(
                processRecordClass, "setProcessState", int.class, int.class, int.class);
            if (setProcessStateMethod != null) {
                try {
                    XposedHelpers.findAndHookMethod(setProcessStateMethod,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    int procState = (int) param.args[0];
                                    Object processRecord = param.thisObject;
                                    int pid = getIntFieldValue(processRecord, "pid");
                                    if (pid <= 0) return;

                                    int uid = getIntFieldValue(processRecord, "uid");
                                    // P3-R2: 合并冗余分支，PROCESS_STATE_TOP <= PROCESS_STATE_FOREGROUND_SERVICE
                                    if (procState <= PROCESS_STATE_FOREGROUND_SERVICE) {
                                        // 前台应用（TOP）和前台服务均需解冻
                                        cancelPendingFreeze(pid);
                                        FreezeManager.getInstance().unfreezeProcess(pid, uid);
                                        ProcessTracker.getInstance().updateState(pid, AppState.FOREGROUND);
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
            // 查找 ProcessRecord 上的 processStateRecord / state 字段
            Object stateRecord = null;
            Field stateField = ReflectionUtils.findFieldRecursive(processRecordClass, "processStateRecord");
            if (stateField == null) {
                stateField = ReflectionUtils.findFieldRecursive(processRecordClass, "mState");
            }
            if (stateField == null) return;

            Class<?> stateRecordClass = stateField.getType();
            Method method = ReflectionUtils.findMethodRecursive(
                stateRecordClass, "setCurrentSchedulingGroup", int.class);
            if (method == null) return;

            XposedHelpers.findAndHookMethod(method,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            int schedulingGroup = (int) param.args[0];
                            if (schedulingGroup >= SCHED_GROUP_DEFAULT) {
                                // 需要从 ProcessStateRecord 回溯到 ProcessRecord 获取 pid
                                // ProcessStateRecord 通常持有对 ProcessRecord 的引用
                                Object stateRec = param.thisObject;
                                Object processRecord = getFieldValue(stateRec, "mApp");
                                if (processRecord == null) processRecord = getFieldValue(stateRec, "app");
                                if (processRecord == null) return;
                                int pid = getIntFieldValue(processRecord, "pid");
                                int uid = getIntFieldValue(processRecord, "uid");
                                if (pid > 0) {
                                    cancelPendingFreeze(pid);
                                    FreezeManager.getInstance().unfreezeProcess(pid, uid);
                                    ProcessTracker.getInstance().updateState(pid, AppState.FOREGROUND);
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
            Field stateField = ReflectionUtils.findFieldRecursive(processRecordClass, "processStateRecord");
            if (stateField == null) {
                stateField = ReflectionUtils.findFieldRecursive(processRecordClass, "mState");
            }
            if (stateField == null) return;

            Class<?> stateRecordClass = stateField.getType();
            Method method = ReflectionUtils.findMethodRecursive(
                stateRecordClass, "setProcessState", int.class, int.class, int.class);
            if (method == null) return;

            XposedHelpers.findAndHookMethod(method,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            int procState = (int) param.args[0];
                            Object stateRec = param.thisObject;
                            Object processRecord = getFieldValue(stateRec, "mApp");
                            if (processRecord == null) processRecord = getFieldValue(stateRec, "app");
                            if (processRecord == null) return;
                            int pid = getIntFieldValue(processRecord, "pid");
                            if (pid <= 0) return;

                            int uid = getIntFieldValue(processRecord, "uid");
                            // P3-R2: 合并冗余分支
                            if (procState <= PROCESS_STATE_FOREGROUND_SERVICE) {
                                cancelPendingFreeze(pid);
                                FreezeManager.getInstance().unfreezeProcess(pid, uid);
                                ProcessTracker.getInstance().updateState(pid, AppState.FOREGROUND);
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
     */
    private static void hookSetOomAdj(ClassLoader classLoader) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);

            XposedHelpers.findAndHookMethod(amsClass,
                "setOomAdj", int.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            int pid = (int) param.args[0];
                            int uid = (int) param.args[1];
                            int oomAdj = (int) param.args[2];

                            // OOM adj 调优：必须在方法执行前修改参数才能生效
                            try {
                                int adjusted = com.tombstonex.manager.OomAdjManager.getInstance().applyOomAdj(uid, pid, oomAdj);
                                if (adjusted != oomAdj) {
                                    param.args[2] = adjusted;
                                }
                            } catch (Throwable t) {
                                // OomAdjManager 可能未初始化，忽略
                            }
                        } catch (Throwable t) {
                            Logger.w("setOomAdj beforeHook 出错: " + t.getMessage());
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            int pid = (int) param.args[0];
                            int uid = (int) param.args[1];
                            int oomAdj = (int) param.args[2];

                            // 自动注册未跟踪的进程：模块加载前已在运行的进程不会经过 activityPaused/startProcess hook，
                            // 通过 setOomAdj 兜底发现并注册它们。
                            if (ProcessTracker.getInstance().getByPid(pid) == null) {
                                tryAutoRegisterProcess(pid, uid);
                            }

                            // oomAdj >= 900 通常是缓存进程
                            ProcessTracker.getInstance().updateOomAdj(pid, oomAdj);
                        } catch (Throwable t) {
                            Logger.w("setOomAdj afterHook 出错: " + t.getMessage());
                        }
                    }
                });
            Logger.i("已 Hook setOomAdj");
        } catch (Throwable t) {
            Logger.e("Hook setOomAdj 失败", t);
        }
    }

    /**
     * 通过读取 /proc/<pid>/cmdline 自动注册未跟踪的进程。
     * 在 system_server 中有权限读取 /proc 文件系统。
     */
    private static void tryAutoRegisterProcess(int pid, int uid) {
        if (pid <= 0) return;
        try {
            java.io.File cmdFile = new java.io.File("/proc/" + pid + "/cmdline");
            if (!cmdFile.exists()) return;
            byte[] data = java.nio.file.Files.readAllBytes(cmdFile.toPath());
            // cmdline 以 null 分隔，取第一段作为进程名
            int nullIdx = 0;
            while (nullIdx < data.length && data[nullIdx] != 0) nullIdx++;
            String processName = new String(data, 0, nullIdx, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (processName.isEmpty()) return;

            String packageName = extractPackageName(processName);
            if (packageName == null) return;

            boolean isSystemApp = uid < 10000;
            ProcessTracker.getInstance().registerProcess(packageName, processName, pid, uid, isSystemApp);

            // 根据 uid 判断初始状态：系统进程或前台进程不设为 BACKGROUND
            // 这里只注册，不改变状态。状态会由后续 hook 更新。
            Logger.d("通过 setOomAdj 自动注册进程: " + processName + " pid=" + pid + " uid=" + uid);
        } catch (Throwable t) {
            // 进程可能已退出，或无权限读取
        }
    }

    /**
     * Hook UID 状态变化 — 更精确的前台/后台判断
     */
    private static void hookUidStateChanged(ClassLoader classLoader) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);

            // 尝试多种方法名以适配不同版本
            String[] methodNames = {"reportUidImportanceChanged", "onUidStateChanged"};
            for (String methodName : methodNames) {
                try {
                    XposedHelpers.findAndHookMethod(amsClass,
                        methodName, int.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    int uid = (int) param.args[0];
                                    int importance = (int) param.args[1];
                                    // IMPORTANCE_FOREGROUND = 100
                                    if (importance <= 125) {
                                        // P2: 使用 getByUid 而非遍历全部进程
                                        List<AppInfo> uidProcesses = ProcessTracker.getInstance().getByUid(uid);
                                        for (AppInfo info : uidProcesses) {
                                            if (info.state == AppState.FROZEN) {
                                                cancelPendingFreeze(info.pid);
                                                FreezeManager.getInstance().unfreezeProcess(info.pid, info.uid);
                                                ProcessTracker.getInstance().updateState(info.pid, AppState.FOREGROUND);
                                            }
                                        }
                                    }
                                } catch (Throwable t) {
                                    Logger.e("uidStateChanged Hook 出错", t);
                                }
                            }
                        });
                    Logger.i("已 Hook " + methodName);
                    break;
                } catch (Throwable e) {
                    Logger.d("Hook 变体失败: " + e.getMessage());
                }
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
            Logger.d("ProcessRecord 上未找到 hasForegroundServices 方法");
        } catch (Throwable e) {
            Logger.d("hasForegroundService 检查失败: " + e.getMessage());
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
            Field pubProvidersField = ReflectionUtils.findFieldRecursive(
                processRecord.getClass(), "pubProviders");
            if (pubProvidersField == null) return true;
            Object pubProviders = ReflectionUtils.getFieldValue(processRecord, pubProvidersField);
            if (pubProviders == null) return false;

            // pubProviders 通常是 ArrayMap<String, ContentProviderRecord>
            // 遍历检查是否有发布的 ContentProvider（size > 0）
            if (pubProviders instanceof Map) {
                return !((Map<?, ?>) pubProviders).isEmpty();
            }
            // 尝试通过反射获取 size()
            Method sizeMethod = ReflectionUtils.findMethodRecursive(pubProviders.getClass(), "size");
            if (sizeMethod != null) {
                int size = (int) sizeMethod.invoke(pubProviders);
                return size > 0;
            }
        } catch (Throwable e) {
            Logger.d("Hook 变体失败: " + e.getMessage());
        }
        return true;
    }

    /**
     * 通过反射调用 AudioManager.isMusicActive() 检查是否有活跃音频播放
     * public 供 ScreenStateHook 复用
     */
    public static boolean isAnyAudioPlaying() {
        try {
            // 在 system_server 中可以通过 ServiceManager 获取 AudioService
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getServiceMethod = serviceManagerClass.getMethod("getService", String.class);
            Object binder = getServiceMethod.invoke(null, "audio");
            if (binder == null) return false;

            Class<?> stubClass = Class.forName("android.media.IAudioService$Stub");
            Method asInterfaceMethod = stubClass.getMethod("asInterface", android.os.IBinder.class);
            Object audioService = asInterfaceMethod.invoke(null, binder);
            if (audioService == null) return false;

            // 调用 isMusicActive()
            Method isMusicActiveMethod = ReflectionUtils.findMethodRecursive(
                audioService.getClass(), "isMusicActive");
            if (isMusicActiveMethod != null) {
                return (boolean) isMusicActiveMethod.invoke(audioService);
            }
        } catch (Throwable t) {
            Logger.d("isAnyAudioPlaying 检查失败: " + t.getMessage());
        }
        return false;
    }

    // --- 辅助方法 ---
    // P3-02 已知限制：getProcessRecordByPid / hasForegroundService / hasActiveContentProvider 等
    // 保护性检查在每次冻结流程中都会高频调用 getFieldValue / getIntFieldValue，而
    // ReflectionUtils.findFieldRecursive 每次都会沿继承链重新查找 Field 并 setAccessible，
    // 未对 Field 做缓存。在批量冻结多个进程时存在反射开销。当前实现为保证兼容性与
    // 稳定性暂不引入 Field 缓存（缓存需处理类加载/字节码差异），后续可考虑按
    // (className, fieldName) 缓存 Field 以降低开销。

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

    private static Object getProcessRecordFromActivity(Object ams, Object token) {
        try {
            Object atms = getFieldValue(ams, "mAtmService");
            if (atms == null) return null;

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
            return getFieldValue(activityRecord, "app");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String extractPackageName(String processName) {
        if (processName == null) return null;
        int colonIdx = processName.indexOf(':');
        // P3-01: colonIdx >= 0 以正确处理冒号出现在首位的边界情况
        return colonIdx >= 0 ? processName.substring(0, colonIdx) : processName;
    }

    private static Object getFieldValue(Object obj, String fieldName) {
        Field field = ReflectionUtils.findFieldRecursive(obj.getClass(), fieldName);
        return field != null ? ReflectionUtils.getFieldValue(obj, field) : null;
    }
}
