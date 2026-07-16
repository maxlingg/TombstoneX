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

public class ActivitySwitchHook {

    // AOSP 实际调度组常量 (ActivityManager.java / ProcessList.java)
    private static final int SCHED_GROUP_BACKGROUND = 0;
    private static final int SCHED_GROUP_RESTRICTED = 1;
    private static final int SCHED_GROUP_DEFAULT = 2;
    private static final int SCHED_GROUP_TOP_APP = 3;

    // AOSP 进程状态常量
    private static final int PROCESS_STATE_TOP = 2;
    // Android 14+ (SDK 34) PROCESS_STATE_FOREGROUND_SERVICE = 4
    // Android 12-13 PROCESS_STATE_FOREGROUND_SERVICE = 5
    private static final int PROCESS_STATE_FOREGROUND_SERVICE =
        Build.VERSION.SDK_INT >= 34 ? 4 : 5;

    // P2: 2 个核心线程
    private static final ScheduledThreadPoolExecutor freezeExecutor = new ScheduledThreadPoolExecutor(2);
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
            int pid = getIntFieldValue(processRecord, "pid");
            int uid = getIntFieldValue(processRecord, "uid");
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
            if (isAnyAudioPlaying()) {
                Logger.d("App is playing audio, skip freeze: " + packageName);
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
                return freezeExecutor.schedule(() -> {
                    try {
                        AppInfo info = ProcessTracker.getInstance().getByPid(targetPid);
                        if (info == null) return;
                        if (info.state == AppState.FOREGROUND) {
                            Logger.d("App returned to foreground, cancel freeze: " + targetPkg);
                            return;
                        }
                        if (!WhitelistManager.getInstance().shouldFreeze(targetPkg, info.processName, info.isSystemApp)) {
                            Logger.d("App added to whitelist during delay, cancel freeze: " + targetPkg);
                            return;
                        }
                        FreezeManager.getInstance().freezeProcess(targetPid, targetUid);
                    } catch (Throwable t) {
                        Logger.e("Delayed freeze error for pid=" + targetPid, t);
                    }
                }, delaySec, TimeUnit.SECONDS);
            });
        } catch (Throwable t) {
            Logger.e("handleActivityPaused error", t);
        }
    }

    /**
     * 取消待冻结任务（public，供 ProcessDeathHook 调用）
     */
    public static void cancelPendingFreeze(int pid) {
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
                                Logger.e("setCurrentSchedulingGroup hook error", t);
                            }
                        }
                    });
                Logger.i("Hooked setCurrentSchedulingGroup");
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
                                    if (procState <= PROCESS_STATE_TOP) {
                                        cancelPendingFreeze(pid);
                                        FreezeManager.getInstance().unfreezeProcess(pid, uid);
                                        ProcessTracker.getInstance().updateState(pid, AppState.FOREGROUND);
                                    } else if (procState <= PROCESS_STATE_FOREGROUND_SERVICE) {
                                        // 前台服务不冻结，需解冻已冻结进程
                                        cancelPendingFreeze(pid);
                                        FreezeManager.getInstance().unfreezeProcess(pid, uid);
                                        ProcessTracker.getInstance().updateState(pid, AppState.FOREGROUND);
                                    }
                                } catch (Throwable t) {
                                    Logger.e("setProcessState hook error", t);
                                }
                            }
                        });
                    Logger.i("Hooked setProcessState");
                } catch (Throwable e) {
                    Logger.d("setProcessState hook variant failed: " + e.getMessage());
                }
            } else {
                // Android 14+：尝试在 ProcessStateRecord 上查找
                hookSetProcessStateOnStateRecord(processRecordClass, classLoader);
            }
        } catch (Throwable t) {
            Logger.e("Failed to hook process state", t);
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
                            Logger.e("setCurrentSchedulingGroup (StateRecord) hook error", t);
                        }
                    }
                });
            Logger.i("Hooked setCurrentSchedulingGroup on ProcessStateRecord");
        } catch (Throwable t) {
            Logger.w("Failed to hook setCurrentSchedulingGroup on StateRecord: " + t.getMessage());
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
                            if (procState <= PROCESS_STATE_TOP) {
                                cancelPendingFreeze(pid);
                                FreezeManager.getInstance().unfreezeProcess(pid, uid);
                                ProcessTracker.getInstance().updateState(pid, AppState.FOREGROUND);
                            } else if (procState <= PROCESS_STATE_FOREGROUND_SERVICE) {
                                // 前台服务不冻结，需解冻已冻结进程
                                cancelPendingFreeze(pid);
                                FreezeManager.getInstance().unfreezeProcess(pid, uid);
                                ProcessTracker.getInstance().updateState(pid, AppState.FOREGROUND);
                            }
                        } catch (Throwable t) {
                            Logger.e("setProcessState (StateRecord) hook error", t);
                        }
                    }
                });
            Logger.i("Hooked setProcessState on ProcessStateRecord");
        } catch (Throwable t) {
            Logger.w("Failed to hook setProcessState on StateRecord: " + t.getMessage());
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
                        } catch (Throwable t) {
                            Logger.w("setOomAdj hook error: " + t.getMessage());
                        }
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
                                    Logger.e("uidStateChanged hook error", t);
                                }
                            }
                        });
                    Logger.i("Hooked " + methodName);
                    break;
                } catch (Throwable e) {
                    Logger.d("Hook variant failed: " + e.getMessage());
                }
            }
        } catch (Throwable t) {
            Logger.e("Failed to hook uid state change", t);
        }
    }

    // --- 保护性检查 ---

    /**
     * 检查是否有前台服务
     * 尝试 hasForeground 和 hasForegroundServices 两种方法名
     * public 供 ScreenStateHook 复用
     */
    public static boolean hasForegroundService(Object processRecord) {
        try {
            Field servicesField = ReflectionUtils.findFieldRecursive(
                processRecord.getClass(), "mServices");
            if (servicesField == null) return true;
            Object services = ReflectionUtils.getFieldValue(processRecord, servicesField);
            if (services == null) return false;

            // 尝试 hasForeground 和 hasForegroundServices 两种方法名
            String[] methodNames = {"hasForeground", "hasForegroundServices"};
            for (String name : methodNames) {
                Method hasForeground = ReflectionUtils.findMethodRecursive(
                    services.getClass(), name);
                if (hasForeground != null) {
                    return (boolean) hasForeground.invoke(services);
                }
            }
        } catch (Throwable e) {
            Logger.d("Hook variant failed: " + e.getMessage());
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
            Logger.d("Hook variant failed: " + e.getMessage());
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
            Logger.d("isAnyAudioPlaying check failed: " + t.getMessage());
        }
        return false;
    }

    // --- 辅助方法 ---

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
        return colonIdx > 0 ? processName.substring(0, colonIdx) : processName;
    }

    private static Object getFieldValue(Object obj, String fieldName) {
        Field field = ReflectionUtils.findFieldRecursive(obj.getClass(), fieldName);
        return field != null ? ReflectionUtils.getFieldValue(obj, field) : null;
    }
}
