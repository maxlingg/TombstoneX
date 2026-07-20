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
                                ProcessStartHook.cancelPendingFreeze(pid);
                                ProcessTracker.getInstance().removeProcess(pid);
                                Logger.i("进程已死亡，已清理: pid=" + pid);
                            }
                        } catch (Throwable t) {
                            Logger.w("onCleanupApplicationRecord Hook 出错: " + t.getMessage());
                        }
                    }
                });
            Logger.i("已 Hook onCleanupApplicationRecord");
        } catch (Throwable t) {
            Logger.w("未找到 onCleanupApplicationRecord，尝试 handleAppDied: " + t.getMessage());
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
                                ProcessStartHook.cancelPendingFreeze(pid);
                                    ProcessTracker.getInstance().removeProcess(pid);
                                    Logger.i("进程已死亡 (handleAppDied)，已清理: pid=" + pid);
                                }
                            } catch (Throwable t2) {
                                Logger.w("handleAppDied Hook 出错: " + t2.getMessage());
                            }
                        }
                    });
                Logger.i("已 Hook handleAppDied (降级)");
            } catch (Throwable t2) {
                Logger.w("Hook 进程死亡失败: " + t2.getMessage());
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
                                ProcessStartHook.cancelPendingFreeze(pid);
                                        ProcessTracker.getInstance().removeProcess(pid);
                                        Logger.i("cleanUpApplicationRecord: 已清理 pid=" + pid);
                                    }
                                } catch (Throwable t) {
                                    Logger.w("cleanUpApplicationRecord Hook 出错: " + t.getMessage());
                                }
                            }
                        });
                        Logger.i("已 Hook cleanUpApplicationRecord");
                        break;
                    } catch (Throwable e) {
                        Logger.d("Hook 变体失败: " + e.getMessage());
                    }
                }
            } catch (Throwable t) {
                Logger.w("Hook cleanUpApplicationRecord 失败: " + t.getMessage());
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
                                ProcessStartHook.cancelPendingFreeze(pid);
                                    ProcessTracker.getInstance().removeProcess(pid);
                                    Logger.w("应用崩溃，已清理: pid=" + pid);
                                }
                            } catch (Throwable t) {
                                Logger.w("handleAppCrashLocked Hook 出错: " + t.getMessage());
                            }
                        }
                    });
                    Logger.i("已 Hook handleAppCrashLocked (" + paramTypes.length + " 个参数)");
                    crashHooked = true;
                    break;
                } catch (Throwable e) {
                    Logger.d("Hook 变体失败: " + e.getMessage());
                }
            }

            if (!crashHooked) {
                Logger.w("未找到已知签名的 handleAppCrashLocked");
            }
        } catch (Throwable t) {
            Logger.e("Hook 崩溃清理失败", t);
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
            Logger.w("从 ProcessRecord 获取 pid 失败: " + t.getMessage());
        }
        return -1;
    }
}
