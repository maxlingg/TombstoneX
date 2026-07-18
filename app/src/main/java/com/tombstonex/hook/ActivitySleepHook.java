package com.tombstonex.hook;

import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import com.tombstonex.util.ReflectionUtils;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Activity 休眠保护 Hook
 *
 * 防止系统在不恰当的时机清理/休眠已被 TombstoneX 冻结的应用的 Activity，
 * 否则会导致该应用从最近任务中消失、或在重新打开时被完整重载（丢失现场）。
 *
 * 触发跳过休眠的判定：当目标包名在 ProcessTracker 中存在 FROZEN 状态进程时，
 * 表明 TombstoneX 已主动冻结该应用以保留其在内存中的现场，此时应跳过系统的
 * sleepPackage / finishDisabledPackageActivities / goToSleep 调用（setResult(null)）。
 *
 * Hook 目标（多签名兼容，适配 minSdk=32 / targetSdk=36）：
 * 1. ActivityManagerService.sleepPackage / ActivityTaskManagerService.sleepPackage
 * 2. ActivityTaskManagerService.finishDisabledPackageActivities
 * 3. ActivityRecord.goToSleep
 * 4. ActivityStackSupervisor.finishDisabledPackageActivities
 *
 * 设计原则：
 * - 每个 Hook 独立 try/catch，单个失败不影响其他 Hook。
 * - 仅对"已被冻结"的包跳过休眠；未冻结包走系统默认流程，避免影响正常调度。
 * - 多签名兼容：方法名/参数列表跨版本变化时，逐个变体尝试，命中即止。
 */
public class ActivitySleepHook {

    /** ActivityRecord / ActivityTaskManagerService 等类的全限定名（跨版本一致） */
    private static final String AMS_CLASS = "com.android.server.am.ActivityManagerService";
    private static final String ATMS_CLASS = "com.android.server.wm.ActivityTaskManagerService";
    private static final String ACTIVITY_RECORD_CLASS = "com.android.server.wm.ActivityRecord";
    private static final String ACTIVITY_STACK_SUPERVISOR_CLASS = "com.android.server.wm.ActivityStackSupervisor";

    public static void init(ClassLoader classLoader) {
        hookSleepPackage(classLoader);
        hookFinishDisabledPackageActivities(classLoader);
        hookActivityRecordGoToSleep(classLoader);
        hookActivityStackSupervisorFinishDisabledPackageActivities(classLoader);
    }

    /**
     * Hook ActivityManagerService / ActivityTaskManagerService 的 sleepPackage 方法（多签名兼容）。
     * 当目标包已被冻结时，setResult(null) 跳过休眠。
     */
    private static void hookSleepPackage(ClassLoader classLoader) {
        String[] targetClasses = {AMS_CLASS, ATMS_CLASS};
        // sleepPackage 跨版本签名变体
        Class<?>[][] paramVariants = {
            {String.class},
            {String.class, int.class},
            {String.class, boolean.class},
            {String.class, int.class, int.class},
        };

        boolean hooked = false;
        for (String className : targetClasses) {
            Class<?> clazz;
            try {
                clazz = XposedHelpers.findClass(className, classLoader);
            } catch (Throwable t) {
                // 该类在此版本不存在，跳过
                continue;
            }
            for (Class<?>[] params : paramVariants) {
                try {
                    Method method = XposedHelpers.findMethodExact(clazz, "sleepPackage", params);
                    if (method == null) continue;

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String pkg = extractPackageFromArgs(param.args);
                                if (pkg != null && isPackageFrozen(pkg)) {
                                    Logger.d("Skip sleepPackage for frozen app: " + pkg);
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                Logger.e("sleepPackage hook error", t);
                            }
                        }
                    });
                    Logger.i("Hooked sleepPackage on " + className + " (" + params.length + " params)");
                    hooked = true;
                    break;
                } catch (Throwable e) {
                    Logger.d("sleepPackage hook variant failed: " + e.getMessage());
                }
            }
            if (hooked) break;
        }

        if (!hooked) {
            Logger.w("Could not find sleepPackage with known signatures on AMS/ATMS");
        }
    }

    /**
     * Hook ActivityTaskManagerService.finishDisabledPackageActivities（多签名兼容）。
     * 当目标包已被冻结时，setResult(null) 跳过对 Activity 的清理。
     */
    private static void hookFinishDisabledPackageActivities(ClassLoader classLoader) {
        hookFinishDisabledPackageActivitiesOnClass(classLoader, ATMS_CLASS);
    }

    /**
     * Hook ActivityStackSupervisor.finishDisabledPackageActivities（多签名兼容）。
     * Android 12+ 部分逻辑保留在 ActivityStackSupervisor 上。
     */
    private static void hookActivityStackSupervisorFinishDisabledPackageActivities(ClassLoader classLoader) {
        hookFinishDisabledPackageActivitiesOnClass(classLoader, ACTIVITY_STACK_SUPERVISOR_CLASS);
    }

    /**
     * 在指定类上 Hook finishDisabledPackageActivities 方法。
     * 签名变体（AOSP 历史版本）：
     *   (String packageName, ComponentName activity, boolean onlyBubbleActivities, boolean userLeaving)
     *   (String packageName, ComponentName, boolean)
     *   (String packageName, boolean)
     *   (String packageName)
     */
    private static void hookFinishDisabledPackageActivitiesOnClass(ClassLoader classLoader, String className) {
        Class<?> clazz;
        try {
            clazz = XposedHelpers.findClass(className, classLoader);
        } catch (Throwable t) {
            Logger.d("Class not found for finishDisabledPackageActivities: " + className);
            return;
        }

        Class<?> componentNameClass;
        try {
            componentNameClass = XposedHelpers.findClass("android.content.ComponentName", classLoader);
        } catch (Throwable t) {
            componentNameClass = null;
        }

        // 构建签名变体
        Class<?>[][] paramVariants;
        if (componentNameClass != null) {
            paramVariants = new Class<?>[][] {
                {String.class, componentNameClass, boolean.class, boolean.class},
                {String.class, componentNameClass, boolean.class},
                {String.class, boolean.class},
                {String.class},
            };
        } else {
            paramVariants = new Class<?>[][] {
                {String.class, boolean.class},
                {String.class},
            };
        }

        for (Class<?>[] params : paramVariants) {
            try {
                Method method = XposedHelpers.findMethodExact(clazz, "finishDisabledPackageActivities", params);
                if (method == null) continue;

                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            String pkg = extractPackageFromArgs(param.args);
                            if (pkg != null && isPackageFrozen(pkg)) {
                                Logger.d("Skip finishDisabledPackageActivities for frozen app: " + pkg
                                    + " on " + clazz.getName());
                                param.setResult(null);
                            }
                        } catch (Throwable t) {
                            Logger.e("finishDisabledPackageActivities hook error", t);
                        }
                    }
                });
                Logger.i("Hooked finishDisabledPackageActivities on " + className
                    + " (" + params.length + " params)");
                return; // 命中一个签名即止
            } catch (Throwable e) {
                Logger.d("finishDisabledPackageActivities variant failed on "
                    + className + ": " + e.getMessage());
            }
        }
        Logger.w("Could not find finishDisabledPackageActivities with known signatures on " + className);
    }

    /**
     * Hook ActivityRecord.goToSleep（多签名兼容）。
     * 从 ActivityRecord 自身获取 packageName，若该包已被冻结则跳过休眠。
     */
    private static void hookActivityRecordGoToSleep(ClassLoader classLoader) {
        Class<?> clazz;
        try {
            clazz = XposedHelpers.findClass(ACTIVITY_RECORD_CLASS, classLoader);
        } catch (Throwable t) {
            Logger.w("ActivityRecord class not found: " + t.getMessage());
            return;
        }

        // goToSleep 跨版本签名变体
        Class<?>[][] paramVariants = {
            {},
            {boolean.class},
            {int.class},
            {boolean.class, int.class},
        };

        boolean hooked = false;
        for (Class<?>[] params : paramVariants) {
            try {
                Method method = XposedHelpers.findMethodExact(clazz, "goToSleep", params);
                if (method == null) continue;

                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            String pkg = getPackageFromActivityRecord(param.thisObject);
                            if (pkg != null && isPackageFrozen(pkg)) {
                                Logger.d("Skip ActivityRecord.goToSleep for frozen app: " + pkg);
                                param.setResult(null);
                            }
                        } catch (Throwable t) {
                            Logger.e("ActivityRecord.goToSleep hook error", t);
                        }
                    }
                });
                Logger.i("Hooked ActivityRecord.goToSleep (" + params.length + " params)");
                hooked = true;
                break;
            } catch (Throwable e) {
                Logger.d("goToSleep hook variant failed: " + e.getMessage());
            }
        }

        if (!hooked) {
            Logger.w("Could not find ActivityRecord.goToSleep with known signatures");
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 从方法参数中提取第一个 String 类型的包名参数。
     * sleepPackage / finishDisabledPackageActivities 的首个参数均为 packageName。
     */
    private static String extractPackageFromArgs(Object[] args) {
        if (args == null || args.length == 0) return null;
        for (Object arg : args) {
            if (arg instanceof String) {
                return (String) arg;
            }
        }
        return null;
    }

    /**
     * 从 ActivityRecord 对象提取包名。
     * ActivityRecord.packageName 为 String 字段；部分版本提供 getPackage() 方法。
     */
    private static String getPackageFromActivityRecord(Object activityRecord) {
        if (activityRecord == null) return null;
        try {
            Object pn = XposedHelpers.getObjectField(activityRecord, "packageName");
            if (pn instanceof String) return (String) pn;
        } catch (Throwable ignored) {}
        try {
            Method m = ReflectionUtils.findMethodRecursive(activityRecord.getClass(), "getPackage");
            if (m != null) {
                Object r = m.invoke(activityRecord);
                if (r instanceof String) return (String) r;
            }
        } catch (Throwable ignored) {}
        try {
            Object info = XposedHelpers.getObjectField(activityRecord, "info");
            if (info != null) {
                Object pn = XposedHelpers.getObjectField(info, "packageName");
                if (pn instanceof String) return (String) pn;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 判断指定包名是否存在 FROZEN 状态的进程。
     * 通过 ProcessTracker.getAllByPackage(pkg) 检查是否有 FROZEN 状态进程。
     */
    private static boolean isPackageFrozen(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            List<AppInfo> processes = ProcessTracker.getInstance().getAllByPackage(pkg);
            for (AppInfo info : processes) {
                if (info.state == AppState.FROZEN) return true;
            }
        } catch (Throwable t) {
            Logger.d("isPackageFrozen check failed: " + pkg + " - " + t.getMessage());
        }
        return false;
    }
}
