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
import java.util.ArrayList;
import java.util.List;

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

            // S-2: Android 12+ ProcessRecord.appNotResponding 实际使用 ApplicationInfo 而非 ApplicationExitInfo
            Class<?> appInfoClass = XposedHelpers.findClass(
                "android.content.pm.ApplicationInfo", classLoader);

            // S-2: WindowProcessController 类（Android 12+ 引入）
            Class<?> wpcClass = null;
            try {
                wpcClass = XposedHelpers.findClass(
                    "com.android.server.wm.WindowProcessController", classLoader);
            } catch (Throwable ignore) {
                Logger.d("WindowProcessController 不可用（Android 11 及以下）: " + ignore.getMessage());
            }

            // 安全查找 ProcessErrorStateRecord 类（Android 12 及以下无此类）
            Class<?> errorStateRecordClass = null;
            try {
                errorStateRecordClass = XposedHelpers.findClass(
                    "com.android.server.am.ProcessRecord$ProcessErrorStateRecord", classLoader);
            } catch (Throwable ignore) {
                Logger.d("ProcessErrorStateRecord 不可用（Android 12 及以下）: " + ignore.getMessage());
            }

            // 尝试多种签名（ProcessErrorStateRecord 不可用时仅尝试简单变体）
            List<Class<?>[]> paramTypeVariants = new ArrayList<>();
            // S-2: Android 12+ 正确的6参数变体：
            // appNotResponding(String, ApplicationInfo, String, WindowProcessController, boolean, String)
            if (wpcClass != null) {
                paramTypeVariants.add(new Class<?>[]{String.class, appInfoClass, String.class, wpcClass, boolean.class, String.class});
            }
            if (errorStateRecordClass != null) {
                paramTypeVariants.add(new Class<?>[]{String.class, exitInfoClass, errorStateRecordClass, boolean.class});
            }
            paramTypeVariants.add(new Class<?>[]{String.class, exitInfoClass});

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
                                if (appInfo != null && appInfo.getState() == AppState.FROZEN) {
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

                // AnrHelper.appNotResponding — 跨版本签名变体
                Class<?> processRecordClass = XposedHelpers.findClass(
                    "com.android.server.am.ProcessRecord", classLoader);

                // S-1: 查找 ApplicationInfo 和 WindowProcessController 类
                Class<?> appInfoClass = XposedHelpers.findClass(
                    "android.content.pm.ApplicationInfo", classLoader);
                Class<?> wpcClass = null;
                try {
                    wpcClass = XposedHelpers.findClass(
                        "com.android.server.wm.WindowProcessController", classLoader);
                } catch (Throwable ignore) {
                    Logger.d("WindowProcessController 不可用（AnrHelper 路径）: " + ignore.getMessage());
                }

                List<Class<?>[]> paramTypeVariants = new ArrayList<>();
                // S-1: AnrHelper.appNotResponding 实际签名（Android 12+）：
                // (ProcessRecord, String, ApplicationInfo, String, WindowProcessController, boolean, String)
                if (wpcClass != null) {
                    paramTypeVariants.add(new Class<?>[]{processRecordClass, String.class, appInfoClass, String.class, wpcClass, boolean.class, String.class});
                }
                paramTypeVariants.add(new Class<?>[]{processRecordClass, String.class});
                paramTypeVariants.add(new Class<?>[]{processRecordClass, String.class, String.class});
                paramTypeVariants.add(new Class<?>[]{processRecordClass});

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
                                    if (appInfo != null && appInfo.getState() == AppState.FROZEN) {
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
     *
     * M6 修复：Android 14+ 上 inputDispatchingTimedOut 已从 AMS 迁移到 AnrHelper，
     * 仿照 hookAppNotResponding 的模式，先尝试 AMS，失败则尝试 AnrHelper。
     */
    private static void hookInputDispatchingTimedOut(ClassLoader classLoader) {
        Class<?> amsClass = null;
        Class<?> processRecordClass = null;
        Class<?> activityRecordClass = null;
        // S-3: 添加 ApplicationInfo 和 WindowProcessController 类（Android 11+）
        Class<?> appInfoClass = null;
        Class<?> wpcClass = null;
        try {
            amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);
            processRecordClass = XposedHelpers.findClass(
                "com.android.server.am.ProcessRecord", classLoader);
            activityRecordClass = XposedHelpers.findClass(
                "com.android.server.wm.ActivityRecord", classLoader);
            // S-3: ApplicationInfo 是标准 Android 类，始终可用
            appInfoClass = XposedHelpers.findClass(
                "android.content.pm.ApplicationInfo", classLoader);
        } catch (Throwable t) {
            Logger.d("AMS/ProcessRecord/ActivityRecord 类查找失败: " + t.getMessage());
        }
        // S-3: WindowProcessController 类（Android 12+ 引入）
        try {
            wpcClass = XposedHelpers.findClass(
                "com.android.server.wm.WindowProcessController", classLoader);
        } catch (Throwable t) {
            Logger.d("WindowProcessController 不可用（Android 11 及以下）: " + t.getMessage());
        }

        // R8-M1 修复：动态构建变体列表，跳过含 null 类的变体，避免 findMethodExact NPE
        // 当 activityRecordClass 查找失败时，旧代码的静态数组含 null 元素，导致 NPE。
        Class<?>[][] candidateVariants = {
            // S-3: AMS.inputDispatchingTimedOut 实际签名（Android 11+）：
            // (ProcessRecord, String, ApplicationInfo, String, WindowProcessController, boolean, String)
            {processRecordClass, String.class, appInfoClass, String.class, wpcClass, boolean.class, String.class},
            {processRecordClass, activityRecordClass, activityRecordClass,
             boolean.class, String.class},
            {processRecordClass, String.class, String.class, int.class, int.class, boolean.class},
            {processRecordClass, activityRecordClass, String.class, int.class, int.class, boolean.class},
            {processRecordClass, String.class},
        };
        List<Class<?>[]> paramTypeVariants = new ArrayList<>();
        for (Class<?>[] variant : candidateVariants) {
            boolean hasNull = false;
            for (Class<?> c : variant) {
                if (c == null) { hasNull = true; break; }
            }
            if (!hasNull) paramTypeVariants.add(variant);
        }

        // 共享回调：首个参数预期为 ProcessRecord，拦截已冻结应用的输入 ANR
        // R10-m-3: 增加类型校验，不假设 args[0] 恒为 ProcessRecord
        // R11-m-1: 将字符串包含匹配改为精确类型校验，避免误匹配类名包含 "ProcessRecord" 的无关类
        final Class<?> prClass = processRecordClass;
        XC_MethodHook callback = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    // R10-m-3: 类型校验——args[0] 可能为 null 或非 ProcessRecord 类型
                    // R11-m-1: 使用 isInstance 精确校验，替代过于宽泛的字符串包含匹配
                    if (param.args == null || param.args.length == 0) return;
                    Object arg0 = param.args[0];
                    if (prClass == null || arg0 == null || !prClass.isInstance(arg0)) return;

                    int pid = getPidFromProcessRecord(arg0);
                    AppInfo appInfo = ProcessTracker.getInstance().getByPid(pid);

                    if (appInfo != null && appInfo.getState() == AppState.FROZEN) {
                        Logger.d("拦截已冻结应用的输入 ANR: "
                            + appInfo.packageName);
                        param.setResult(null);
                    }
                } catch (Throwable t) {
                    Logger.e("inputDispatchingTimedOut Hook 出错", t);
                }
            }
        };

        boolean hooked = false;

        // 先尝试 AMS.inputDispatchingTimedOut（Android 13 及以下）
        if (amsClass != null) {
            for (Class<?>[] paramTypes : paramTypeVariants) {
                try {
                    Method method = XposedHelpers.findMethodExact(
                        amsClass, "inputDispatchingTimedOut", paramTypes);
                    if (method == null) continue;

                    XposedBridge.hookMethod(method, callback);
                    Logger.i("已 Hook AMS.inputDispatchingTimedOut");
                    hooked = true;
                    break;
                } catch (Throwable e) {
                    Logger.d("AMS Hook 变体失败: " + e.getMessage());
                }
            }
        }

        // S-3: inputDispatchingTimedOut 仅存在于 AMS，不在 AnrHelper 上尝试
        if (!hooked) {
            Logger.w("未能在 AMS 上 Hook inputDispatchingTimedOut");
        }
    }

    /**
     * 降级策略：Hook ActivityManagerService.killAppWithReason
     * 当主 Hook 失败时，作为最后防线拦截因冻结导致的 kill
     *
     * M-1 修复：killAppWithReason 在 AnrHelper 上不存在（AOSP 中该方法仅存在于 AMS），
     * 移除 AnrHelper 候选类，仅保留 AMS。
     *
     * R8-m5 设计权衡说明：本 Hook 拦截所有冻结状态应用的 ANR/kill，
     * 无法区分"冻结导致的 ANR"与"冻结前已存在的 ANR"。
     * 当前行为可接受：冻结应用本不应产生有意义的 ANR（其主线程已被冻结），
     * 拦截所有冻结应用的 ANR 避免了不必要的进程终止，副作用仅为可能延迟
     * 已有问题应用的 ANR 报告（但应用在解冻后仍会正常产生 ANR）。
     */
    private static void hookKillAppWithReasonFallback(ClassLoader classLoader) {
        // M-1: killAppWithReason 仅存在于 AMS，不在 AnrHelper 上尝试
        String targetClass = "com.android.server.am.ActivityManagerService";

        Class<?> targetClassObj;
        try {
            targetClassObj = XposedHelpers.findClass(targetClass, classLoader);
        } catch (Throwable t) {
            Logger.d("未找到 " + targetClass + "，跳过 killAppWithReason: " + t.getMessage());
            return;
        }

        Class<?> processRecordClass;
        try {
            processRecordClass = XposedHelpers.findClass(
                "com.android.server.am.ProcessRecord", classLoader);
        } catch (Throwable t) {
            Logger.d("未找到 ProcessRecord，跳过 killAppWithReason: " + t.getMessage());
            return;
        }

        // killAppWithReason(ProcessRecord, String)
        try {
            Method method = XposedHelpers.findMethodExact(
                targetClassObj, "killAppWithReason", processRecordClass, String.class);
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

                        if (appInfo != null && appInfo.getState() == AppState.FROZEN
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
            Logger.i("已 Hook killAppWithReason (降级) on " + targetClass);
        } catch (Throwable e) {
            Logger.d("killAppWithReason 变体失败 on " + targetClass + ": " + e.getMessage());
            Logger.w("killAppWithReason 不可用");
        }
    }

    /**
     * 使用 ReflectionUtils.findFieldRecursive 安全获取 ProcessRecord 的 pid。
     * S-1: AOSP 12+ 中 ProcessRecord.pid 字段改名为 mPid，先尝试 "pid"，
     * 失败则尝试 "mPid"，若仍失败则尝试调用 getPid() 方法。
     * 参考 ProcessDeathHook.getPidFromProcessRecord 的实现模式。
     */
    private static int getPidFromProcessRecord(Object processRecord) {
        try {
            // 先尝试旧字段名 "pid"
            Field pidField = ReflectionUtils.findFieldRecursive(
                processRecord.getClass(), "pid");
            if (pidField == null) {
                // S-1: AOSP 12+ 字段名变为 "mPid"
                pidField = ReflectionUtils.findFieldRecursive(
                    processRecord.getClass(), "mPid");
            }
            if (pidField != null) {
                Object val = ReflectionUtils.getFieldValue(processRecord, pidField);
                if (val instanceof Number) {
                    return ((Number) val).intValue();
                }
            }
            // S-1: 两个字段名均未找到，尝试调用 getPid() 方法作为最后手段
            Method getPidMethod = ReflectionUtils.findMethodRecursive(
                processRecord.getClass(), "getPid");
            if (getPidMethod != null) {
                Object val = getPidMethod.invoke(processRecord);
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
