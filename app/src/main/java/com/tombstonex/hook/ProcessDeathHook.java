package com.tombstonex.hook;

import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.util.Logger;
import com.tombstonex.util.ReflectionUtils;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Hook 进程死亡事件，清理 ProcessTracker 中的残留记录
 */
public class ProcessDeathHook {

    public static void init(ClassLoader classLoader) {
        hookHandleAppDied(classLoader);
        hookCleanUpApplicationRecord(classLoader);
    }

    /**
     * Hook ProcessRecord.onCleanupApplicationRecord / handleAppDied
     */
    private static void hookHandleAppDied(ClassLoader classLoader) {
        // 尝试 onCleanupApplicationRecord
        try {
            Class<?> processRecordClass = XposedHelpers.findClass(
                "com.android.server.am.ProcessRecord", classLoader);

            XposedHelpers.findAndHookMethod(processRecordClass,
                "onCleanupApplicationRecord",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            int pid = getPidFromProcessRecord(param.thisObject);
                            // P2-06: pid <= 0 时无法定位进程，跳过清理避免无意义的全量遍历
                            if (pid > 0) {
                                ActivitySwitchHook.cancelPendingFreeze(pid);
                                ProcessTracker.getInstance().removeProcess(pid);
                                Logger.i("Process died, cleaned up: pid=" + pid);
                            }
                        } catch (Throwable t) {
                            Logger.w("onCleanupApplicationRecord hook error: " + t.getMessage());
                        }
                    }
                });
            Logger.i("Hooked onCleanupApplicationRecord");
        } catch (Throwable t) {
            Logger.w("onCleanupApplicationRecord not found, trying handleAppDied: " + t.getMessage());
            // 尝试备用方法名
            try {
                Class<?> processRecordClass = XposedHelpers.findClass(
                    "com.android.server.am.ProcessRecord", classLoader);
                XposedHelpers.findAndHookMethod(processRecordClass,
                    "handleAppDied",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                int pid = getPidFromProcessRecord(param.thisObject);
                                // P2-06: pid <= 0 时无法定位进程，跳过清理避免无意义的全量遍历
                                if (pid > 0) {
                                    ActivitySwitchHook.cancelPendingFreeze(pid);
                                    ProcessTracker.getInstance().removeProcess(pid);
                                    Logger.i("Process died (handleAppDied), cleaned up: pid=" + pid);
                                }
                            } catch (Throwable t2) {
                                Logger.w("handleAppDied hook error: " + t2.getMessage());
                            }
                        }
                    });
                Logger.i("Hooked handleAppDied (fallback)");
            } catch (Throwable t2) {
                Logger.w("Failed to hook process death: " + t2.getMessage());
            }
        }
    }

    /**
     * Hook AMS.cleanUpApplicationRecord 和 handleAppCrashLocked
     * 进程被系统杀死或崩溃时也清理
     */
    private static void hookCleanUpApplicationRecord(ClassLoader classLoader) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);
            Class<?> processRecordClass = XposedHelpers.findClass(
                "com.android.server.am.ProcessRecord", classLoader);

            // Hook cleanUpApplicationRecord（不仅仅是 onCleanupApplicationRecord）
            try {
                // 尝试多种签名
                Class<?>[][] cleanUpVariants = {
                    {processRecordClass, boolean.class, boolean.class, boolean.class},
                    {processRecordClass, boolean.class},
                    {processRecordClass},
                };

                for (Class<?>[] paramTypes : cleanUpVariants) {
                    try {
                        Method method = XposedHelpers.findMethodExact(
                            amsClass, "cleanUpApplicationRecord", paramTypes);
                        if (method == null) continue;

                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object processRecord = param.args[0];
                                    if (processRecord == null) return;
                                    int pid = getPidFromProcessRecord(processRecord);
                                    // P2-06: pid <= 0 时无法定位进程，跳过清理避免无意义的全量遍历
                                    if (pid > 0) {
                                        ActivitySwitchHook.cancelPendingFreeze(pid);
                                        ProcessTracker.getInstance().removeProcess(pid);
                                        Logger.i("cleanUpApplicationRecord: cleaned up pid=" + pid);
                                    }
                                } catch (Throwable t) {
                                    Logger.w("cleanUpApplicationRecord hook error: " + t.getMessage());
                                }
                            }
                        });
                        Logger.i("Hooked cleanUpApplicationRecord");
                        break;
                    } catch (Throwable e) {
                        Logger.d("Hook variant failed: " + e.getMessage());
                    }
                }
            } catch (Throwable t) {
                Logger.w("Failed to hook cleanUpApplicationRecord: " + t.getMessage());
            }

            // Hook handleAppCrashLocked — 支持带 String 参数的签名
            Class<?>[][] crashVariants = {
                {processRecordClass, String.class},
                {processRecordClass},
            };

            boolean crashHooked = false;
            for (Class<?>[] paramTypes : crashVariants) {
                try {
                    Method method = XposedHelpers.findMethodExact(
                        amsClass, "handleAppCrashLocked", paramTypes);
                    if (method == null) continue;

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object processRecord = param.args[0];
                                if (processRecord == null) return;
                                int pid = getPidFromProcessRecord(processRecord);
                                // P2-06: pid <= 0 时无法定位进程，跳过清理避免无意义的全量遍历
                                if (pid > 0) {
                                    ActivitySwitchHook.cancelPendingFreeze(pid);
                                    ProcessTracker.getInstance().removeProcess(pid);
                                    Logger.w("App crashed, cleaned up: pid=" + pid);
                                }
                            } catch (Throwable t) {
                                Logger.w("handleAppCrashLocked hook error: " + t.getMessage());
                            }
                        }
                    });
                    Logger.i("Hooked handleAppCrashLocked (" + paramTypes.length + " params)");
                    crashHooked = true;
                    break;
                } catch (Throwable e) {
                    Logger.d("Hook variant failed: " + e.getMessage());
                }
            }

            if (!crashHooked) {
                Logger.w("Could not find handleAppCrashLocked with known signatures");
            }
        } catch (Throwable t) {
            Logger.e("Failed to hook crash cleanup", t);
        }
    }

    /**
     * 使用 ReflectionUtils 安全获取 ProcessRecord 的 pid
     */
    private static int getPidFromProcessRecord(Object processRecord) {
        try {
            Field pidField = ReflectionUtils.findFieldRecursive(
                processRecord.getClass(), "pid");
            if (pidField != null) {
                Object val = ReflectionUtils.getFieldValue(processRecord, pidField);
                if (val instanceof Number) {
                    return ((Number) val).intValue();
                }
            }
        } catch (Throwable t) {
            Logger.w("Failed to get pid from ProcessRecord: " + t.getMessage());
        }
        return -1;
    }
}
