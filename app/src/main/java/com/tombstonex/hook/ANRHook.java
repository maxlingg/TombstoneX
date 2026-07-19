package com.tombstonex.hook;

import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import com.tombstonex.util.ReflectionUtils;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class ANRHook {

    public static void init(ClassLoader classLoader) {
        hookAppNotResponding(classLoader);
        hookInputDispatchingTimedOut(classLoader);
        hookKillAppWithReasonFallback(classLoader);
    }

    /**
     * Hook appNotResponding
     * Android 14+ 该方法迁移到 AnrHelper 类
     */
    private static void hookAppNotResponding(ClassLoader classLoader) {
        boolean hooked = false;

        // 尝试在 ProcessRecord 上 hook（Android 13 及以下）
        try {
            Class<?> processRecordClass = XposedHelpers.findClass(
                "com.android.server.am.ProcessRecord", classLoader);

            // 查找 appNotResponding 方法 — 签名可能因版本不同
            // Android 12-: appNotResponding(String, ApplicationExitInfo)
            // Android 13: appNotResponding(String, ApplicationExitInfo, ProcessErrorStateRecord, boolean)
            Class<?> exitInfoClass = XposedHelpers.findClass(
                "android.app.ApplicationExitInfo", classLoader);

            // 尝试多种签名
            Class<?>[][] paramTypeVariants = {
                {String.class, exitInfoClass,
                 XposedHelpers.findClass("com.android.server.am.ProcessRecord$ProcessErrorStateRecord", classLoader),
                 boolean.class},
                {String.class, exitInfoClass},
            };

            for (Class<?>[] paramTypes : paramTypeVariants) {
                try {
                    Method method = XposedHelpers.findMethodExact(
                        processRecordClass, "appNotResponding", paramTypes);
                    if (method == null) continue;

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object processRecord = param.thisObject;
                                int pid = getPidFromProcessRecord(processRecord);

                                AppInfo appInfo = ProcessTracker.getInstance().getByPid(pid);
                                if (appInfo != null && appInfo.state == AppState.FROZEN) {
                                    Logger.d("拦截已冻结应用的 ANR: "
                                        + appInfo.packageName + " pid=" + pid);
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                Logger.e("appNotResponding Hook 出错", t);
                            }
                        }
                    });
                    Logger.i("已在 ProcessRecord 上 Hook appNotResponding");
                    hooked = true;
                    break;
                } catch (Throwable e) {
                    Logger.d("Hook 变体失败: " + e.getMessage());
                }
            }
        } catch (Throwable t) {
            Logger.w("未找到 ProcessRecord.appNotResponding，尝试 AnrHelper: " + t.getMessage());
        }

        // Android 14+：尝试在 AnrHelper 上 hook
        if (!hooked) {
            try {
                Class<?> anrHelperClass = XposedHelpers.findClass(
                    "com.android.server.am.AnrHelper", classLoader);

                // AnrHelper.appNotResponding(ProcessRecord, String) 或类似签名
                Class<?> processRecordClass = XposedHelpers.findClass(
                    "com.android.server.am.ProcessRecord", classLoader);

                Class<?>[][] paramTypeVariants = {
                    {processRecordClass, String.class},
                    {processRecordClass, String.class, String.class},
                    {processRecordClass},
                };

                for (Class<?>[] paramTypes : paramTypeVariants) {
                    try {
                        Method method = XposedHelpers.findMethodExact(
                            anrHelperClass, "appNotResponding", paramTypes);
                        if (method == null) continue;

                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    Object processRecord = param.args[0];
                                    if (processRecord == null) return;
                                    int pid = getPidFromProcessRecord(processRecord);

                                    AppInfo appInfo = ProcessTracker.getInstance().getByPid(pid);
                                    if (appInfo != null && appInfo.state == AppState.FROZEN) {
                                        Logger.d("拦截已冻结应用的 ANR (AnrHelper): "
                                            + appInfo.packageName + " pid=" + pid);
                                        param.setResult(null);
                                    }
                                } catch (Throwable t) {
                                    Logger.e("AnrHelper.appNotResponding Hook 出错", t);
                                }
                            }
                        });
                        Logger.i("已在 AnrHelper 上 Hook appNotResponding");
                        hooked = true;
                        break;
                    } catch (Throwable e) {
                        Logger.d("Hook 变体失败: " + e.getMessage());
                    }
                }
            } catch (Throwable t) {
                Logger.w("未找到 AnrHelper.appNotResponding: " + t.getMessage());
            }
        }

        if (!hooked) {
            Logger.w("未能在任何已知类上 Hook appNotResponding");
        }
    }

    /**
     * Hook inputDispatchingTimedOut — 多版本兼容
     */
    private static void hookInputDispatchingTimedOut(ClassLoader classLoader) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);

            Class<?> processRecordClass = XposedHelpers.findClass(
                "com.android.server.am.ProcessRecord", classLoader);
            Class<?> activityRecordClass = XposedHelpers.findClass(
                "com.android.server.wm.ActivityRecord", classLoader);

            // 多版本签名兼容
            Class<?>[][] paramTypeVariants = {
                {processRecordClass, activityRecordClass, activityRecordClass,
                 boolean.class, String.class},
                {processRecordClass, String.class, String.class, int.class, int.class, boolean.class},
                {processRecordClass, activityRecordClass, String.class, int.class, int.class, boolean.class},
                {processRecordClass, String.class},
            };

            boolean hooked = false;
            for (Class<?>[] paramTypes : paramTypeVariants) {
                try {
                    Method method = XposedHelpers.findMethodExact(
                        amsClass, "inputDispatchingTimedOut", paramTypes);
                    if (method == null) continue;

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object processRecord = param.args[0];
                                if (processRecord == null) return;

                                int pid = getPidFromProcessRecord(processRecord);
                                AppInfo appInfo = ProcessTracker.getInstance().getByPid(pid);

                                if (appInfo != null && appInfo.state == AppState.FROZEN) {
                                    Logger.d("拦截已冻结应用的输入 ANR: "
                                        + appInfo.packageName);
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                Logger.e("inputDispatchingTimedOut Hook 出错", t);
                            }
                        }
                    });
                    Logger.i("已 Hook inputDispatchingTimedOut");
                    hooked = true;
                    break;
                } catch (Throwable e) {
                    Logger.d("Hook 变体失败: " + e.getMessage());
                }
            }

            if (!hooked) {
                Logger.w("未找到已知签名的 inputDispatchingTimedOut");
            }
        } catch (Throwable t) {
            Logger.e("Hook inputDispatchingTimedOut 失败", t);
        }
    }

    /**
     * 降级策略：Hook ActivityManagerService.killAppWithReason
     * 当主 Hook 失败时，作为最后防线拦截因冻结导致的 kill
     */
    private static void hookKillAppWithReasonFallback(ClassLoader classLoader) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);

            Class<?> processRecordClass = XposedHelpers.findClass(
                "com.android.server.am.ProcessRecord", classLoader);

            // killAppWithReason(ProcessRecord, String)
            Method method = XposedHelpers.findMethodExact(
                amsClass, "killAppWithReason", processRecordClass, String.class);
            if (method == null) return;

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object processRecord = param.args[0];
                        if (processRecord == null) return;
                        int pid = getPidFromProcessRecord(processRecord);

                        AppInfo appInfo = ProcessTracker.getInstance().getByPid(pid);
                        String reason = (String) param.args[1];

                        if (appInfo != null && appInfo.state == AppState.FROZEN
                            && reason != null && reason.contains("ANR")) {
                            Logger.d("拦截已冻结应用的 killAppWithReason (ANR): "
                                + appInfo.packageName + " pid=" + pid);
                            param.setResult(null);
                        }
                    } catch (Throwable t) {
                        Logger.e("killAppWithReason Hook 出错", t);
                    }
                }
            });
            Logger.i("已 Hook killAppWithReason (降级)");
        } catch (Throwable t) {
            Logger.w("killAppWithReason 不可用: " + t.getMessage());
        }
    }

    /**
     * 使用 ReflectionUtils.findFieldRecursive 安全获取 ProcessRecord 的 pid
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
            Logger.e("从 ProcessRecord 获取 pid 失败", t);
        }
        return -1;
    }
}
