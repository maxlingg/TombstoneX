package com.tombstonex.hook;

import com.tombstonex.manager.ConfigManager;
import com.tombstonex.manager.FreezeManager;
import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.manager.WhitelistManager;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import com.tombstonex.util.ReflectionUtils;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ActivitySwitchHook {

    private static final int SCHED_GROUP_TOP = 2; // SCHED_GROUP_TOP
    private static final int SCHED_GROUP_FOREGROUND = 1;
    private static final int PROCESS_STATE_TOP = 2;
    private static final int PROCESS_STATE_FOREGROUND_SERVICE = 3;
    private static final int PROCESS_STATE_TOP_SLEEPING = 5;

    private static final ScheduledThreadPoolExecutor freezeExecutor = new ScheduledThreadPoolExecutor(1);
    private static final Map<Integer, ScheduledFuture<?>> pendingFreezes = new ConcurrentHashMap<>();

    public static void init(ClassLoader classLoader) {
        hookActivityPaused(classLoader);
        hookProcessStateChanged(classLoader);
        hookSetOomAdj(classLoader);
        hookUidStateChanged(classLoader);
    }

    /**
     * Hook ActivityManagerService.activityPaused()
     * 延迟冻结，带取消机制避免竞态
     */
    private static void hookActivityPaused(ClassLoader classLoader) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);

            XposedHelpers.findAndHookMethod(amsClass,
                "activityPaused", "android.os.IBinder",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            handleActivityPaused(param);
                        } catch (Throwable t) {
                            Logger.e("activityPaused hook error", t);
                        }
                    }
                });
            Logger.i("Hooked activityPaused");
        } catch (Throwable t) {
            Logger.e("Failed to hook activityPaused", t);
        }
    }

    private static void handleActivityPaused(XC_MethodHook.MethodHookParam param) {
        try {
            Object token = param.args[0];
            if (token == null) return;

            Object processRecord = getProcessRecordFromActivity(param.thisObject, token);
            if (processRecord == null) return;

            String processName = (String) getFieldValue(processRecord, "processName");
            int pid = (int) getFieldValue(processRecord, "pid");
            int uid = (int) getFieldValue(processRecord, "uid");
            String packageName = extractPackageName(processName);

            if (pid <= 0 || packageName == null) return;

            boolean isSystemApp = uid < 10000;
            Logger.d("App paused: " + packageName + " pid=" + pid + " uid=" + uid);

            // 检查白名单
            if (!WhitelistManager.getInstance().shouldFreeze(packageName, processName, isSystemApp)) {
                Logger.d("App in whitelist, skip freeze: " + packageName);
                return;
            }

            // 检查前台服务保护
            if (hasForegroundService(processRecord)) {
                Logger.d("App has foreground service, skip freeze: " + packageName);
                return;
            }

            // 检查 ContentProvider 保护
            if (hasActiveContentProvider(processRecord)) {
                Logger.d("App has active ContentProvider, skip freeze: " + packageName);
                return;
            }

            // 检查音频播放
            if (isAudioPlaying(uid)) {
                Logger.d("App is playing audio, skip freeze: " + packageName);
                return;
            }

            // 注册进程
            ProcessTracker.getInstance().registerProcess(packageName, processName, pid, uid, isSystemApp);

            // 取消之前的待冻结任务（防竞态）
            cancelPendingFreeze(pid);

            // 获取配置的延迟时间
            int delaySec = ConfigManager.getInstance().getFreezeDelay();
            final int targetPid = pid;
            final int targetUid = uid;
            final String targetPkg = packageName;

            ScheduledFuture<?> future = freezeExecutor.schedule(() -> {
                try {
                    // 再次检查进程是否仍然在后台
                    AppInfo info = ProcessTracker.getInstance().getByPid(targetPid);
                    if (info == null) return;
                    if (info.state == AppState.FOREGROUND) {
                        Logger.d("App returned to foreground, cancel freeze: " + targetPkg);
                        return;
                    }
                    // 再次检查白名单（可能在延迟期间被加入白名单）
                    if (!WhitelistManager.getInstance().shouldFreeze(targetPkg, info.processName, info.isSystemApp)) {
                        Logger.d("App added to whitelist during delay, cancel freeze: " + targetPkg);
                        return;
                    }
                    FreezeManager.getInstance().freezeProcess(targetPid, targetUid);
                } catch (Throwable t) {
                    Logger.e("Delayed freeze error for pid=" + targetPid, t);
                }
            }, delaySec, TimeUnit.SECONDS);

            pendingFreezes.put(pid, future);
        } catch (Throwable t) {
            Logger.e("handleActivityPaused error", t);
        }
    }

    /**
     * 取消待冻结任务
     */
    private static void cancelPendingFreeze(int pid) {
        ScheduledFuture<?> future = pendingFreezes.remove(pid);
        if (future != null) {
            future.cancel(false);
            Logger.d("Cancelled pending freeze for pid=" + pid);
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
            XposedHelpers.findAndHookMethod(processRecordClass,
                "setCurrentSchedulingGroup", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            int schedulingGroup = (int) param.args[0];
                            if (schedulingGroup <= SCHED_GROUP_FOREGROUND) {
                                Object processRecord = param.thisObject;
                                int pid = (int) getFieldValue(processRecord, "pid");
                                int uid = (int) getFieldValue(processRecord, "uid");
                                if (pid > 0) {
                                    cancelPendingFreeze(pid);
                                    FreezeManager.getInstance().unfreezeProcess(pid, uid);
                                    ProcessTracker.getInstance().updateState(pid, AppState.FOREGROUND);
                                }
                            }
                        } catch (Throwable t) {
                            Logger.e("setCurrentSchedulingGroup hook error", t);
                        }
                    }
                });
            Logger.i("Hooked setCurrentSchedulingGroup");

            // Hook setProcessState — 更精确的进程状态追踪
            try {
                XposedHelpers.findAndHookMethod(processRecordClass,
                    "setProcessState", int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                int procState = (int) param.args[0];
                                Object processRecord = param.thisObject;
                                int pid = (int) getFieldValue(processRecord, "pid");
                                if (pid <= 0) return;

                                if (procState <= PROCESS_STATE_TOP) {
                                    int uid = (int) getFieldValue(processRecord, "uid");
                                    cancelPendingFreeze(pid);
                                    FreezeManager.getInstance().unfreezeProcess(pid, uid);
                                    ProcessTracker.getInstance().updateState(pid, AppState.FOREGROUND);
                                } else if (procState == PROCESS_STATE_FOREGROUND_SERVICE) {
                                    // 前台服务不冻结
                                    cancelPendingFreeze(pid);
                                    ProcessTracker.getInstance().updateState(pid, AppState.FOREGROUND);
                                }
                            } catch (Throwable t) {
                                Logger.e("setProcessState hook error", t);
                            }
                        }
                    });
                Logger.i("Hooked setProcessState");
            } catch (Throwable ignored) {
                // 某些版本可能没有此方法
            }
        } catch (Throwable t) {
            Logger.e("Failed to hook process state", t);
        }
    }

    /**
     * Hook OOM adj 变化
     */
    private static void hookSetOomAdj(ClassLoader classLoader) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);

            XposedHelpers.findAndHookMethod(amsClass,
                "setOomAdj", int.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            int pid = (int) param.args[0];
                            int uid = (int) param.args[1];
                            int oomAdj = (int) param.args[2];
                            // oomAdj >= 900 通常是缓存进程
                            ProcessTracker.getInstance().updateOomAdj(pid, oomAdj);
                        } catch (Throwable ignored) {}
                    }
                });
            Logger.i("Hooked setOomAdj");
        } catch (Throwable t) {
            Logger.e("Failed to hook setOomAdj", t);
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
                                        // UID 回到前台，解冻所有该 UID 的进程
                                        for (Map.Entry<Integer, AppInfo> entry :
                                                ProcessTracker.getInstance().getAllProcesses().entrySet()) {
                                            AppInfo info = entry.getValue();
                                            if (info.uid == uid && info.state == AppState.FROZEN) {
                                                cancelPendingFreeze(info.pid);
                                                FreezeManager.getInstance().unfreezeProcess(info.pid, info.uid);
                                                ProcessTracker.getInstance().updateState(info.pid, AppState.FOREGROUND);
                                            }
                                        }
                                    }
                                } catch (Throwable t) {
                                    Logger.e("uidStateChanged hook error", t);
                                }
                            }
                        });
                    Logger.i("Hooked " + methodName);
                    break;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Logger.e("Failed to hook uid state change", t);
        }
    }

    // --- 保护性检查 ---

    private static boolean hasForegroundService(Object processRecord) {
        try {
            // 检查 mServices 字段中的前台服务
            Field servicesField = ReflectionUtils.findFieldRecursive(
                processRecord.getClass(), "mServices");
            if (servicesField == null) return false;
            Object services = ReflectionUtils.getFieldValue(processRecord, servicesField);
            if (services == null) return false;
            // 检查是否有前台服务
            Method hasForeground = ReflectionUtils.findMethodRecursive(
                services.getClass(), "hasForeground");
            if (hasForeground != null) {
                return (boolean) hasForeground.invoke(services);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean hasActiveContentProvider(Object processRecord) {
        try {
            Field pubProvidersField = ReflectionUtils.findFieldRecursive(
                processRecord.getClass(), "pubProviders");
            if (pubProvidersField == null) return false;
            Object pubProviders = ReflectionUtils.getFieldValue(processRecord, pubProvidersField);
            if (pubProviders == null) return false;
            // 如果有发布的 ContentProvider 且被其他进程引用，不冻结
            return pubProviders.toString().contains("->");
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isAudioPlaying(int uid) {
        try {
            // 通过 AudioService 检查是否有活跃的音频播放
            // 在系统进程中可以获取
            return false; // 简化实现，后续可扩展
        } catch (Throwable ignored) {}
        return false;
    }

    // --- 辅助方法 ---

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
        return colonIdx > 0 ? processName.substring(0, colonIdx) : processName;
    }

    private static Object getFieldValue(Object obj, String fieldName) {
        Field field = ReflectionUtils.findFieldRecursive(obj.getClass(), fieldName);
        return field != null ? ReflectionUtils.getFieldValue(obj, field) : null;
    }
}