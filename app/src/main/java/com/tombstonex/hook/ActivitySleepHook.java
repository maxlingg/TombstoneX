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
 * - 多签名兼容：方法名/参数列表跨版本变化时，逐个变体尝试，命中即 Hook
 *   （R8-M8-2: 不再命中即止，对所有命中重载都 Hook，避免遗漏其他重载）。
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
     *
     * M10 修复：旧代码在首个命中类上 hook 后即 break，导致仅 hook AMS 而漏掉 ATMS
     * （或反之）。Android 11+ sleepPackage 实际入口在 ATMS，部分版本 AMS 仍保留转发壳，
     * 二者签名可能不同。现对每个类都尝试 hook 所有命中签名，不提前 break。
     *
     * R11-m-6: 已知开销——targetClasses 中若存在继承关系，相同方法可能被重复 Hook。
     * 但 AMS (com.android.server.am.ActivityManagerService) 与 ATMS
     * (com.android.server.wm.ActivityTaskManagerService) 为独立类，不互相继承，
     * 故实际不会重复 Hook。保持当前实现不改代码。
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

        int hookCount = 0;
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
                                    Logger.d("跳过已冻结应用的 sleepPackage: " + pkg);
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                Logger.e("sleepPackage Hook 出错", t);
                            }
                        }
                    });
                    Logger.i("已 Hook sleepPackage 于 " + className + " (" + params.length + " 个参数)");
                    hookCount++;
                    // 不 break：继续尝试其他签名变体与类
                } catch (Throwable e) {
                    Logger.d("sleepPackage Hook 变体失败: " + e.getMessage());
                }
            }
        }

        if (hookCount == 0) {
            Logger.w("在 AMS/ATMS 上未找到已知签名的 sleepPackage");
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
            Logger.d("未找到 finishDisabledPackageActivities 的类: " + className);
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

        int hookCount = 0;
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
                                Logger.d("跳过已冻结应用的 finishDisabledPackageActivities: " + pkg
                                    + " 于 " + clazz.getName());
                                // R10-M-2: finishDisabledPackageActivities 返回 boolean，
                                // setResult(null) 对基本类型方法不安全（可能导致 NPE/类型不匹配），
                                // 改为 setResult(false) 表示未完成任何 Activity 清理。
                                // sleepPackage / ActivityRecord.goToSleep 返回 void，仍使用 setResult(null)。
                                param.setResult(false);
                            }
                        } catch (Throwable t) {
                            Logger.e("finishDisabledPackageActivities Hook 出错", t);
                        }
                    }
                });
                Logger.i("已 Hook finishDisabledPackageActivities 于 " + className
                    + " (" + params.length + " 个参数)");
                hookCount++;
                // R8-M8-2: 不 return，继续尝试其他签名变体（与 hookSleepPackage 策略一致）
            } catch (Throwable e) {
                Logger.d("finishDisabledPackageActivities 变体失败 于 "
                    + className + ": " + e.getMessage());
            }
        }
        if (hookCount == 0) {
            Logger.w("在 " + className + " 上未找到已知签名的 finishDisabledPackageActivities");
        }
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
            Logger.w("未找到 ActivityRecord 类: " + t.getMessage());
            return;
        }

        // goToSleep 跨版本签名变体
        Class<?>[][] paramVariants = {
            {},
            {boolean.class},
            {int.class},
            {boolean.class, int.class},
        };

        int hookCount = 0;
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
                                Logger.d("跳过已冻结应用的 ActivityRecord.goToSleep: " + pkg);
                                param.setResult(null);
                            }
                        } catch (Throwable t) {
                            Logger.e("ActivityRecord.goToSleep Hook 出错", t);
                        }
                    }
                });
                Logger.i("已 Hook ActivityRecord.goToSleep (" + params.length + " 个参数)");
                hookCount++;
                // R8-M8-2: 不 break，继续尝试其他签名变体（与 hookSleepPackage 策略一致）
            } catch (Throwable e) {
                Logger.d("goToSleep Hook 变体失败: " + e.getMessage());
            }
        }

        if (hookCount == 0) {
            Logger.w("未找到已知签名的 ActivityRecord.goToSleep");
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 从方法参数中提取第一个 String 类型的包名参数。
     * sleepPackage / finishDisabledPackageActivities 的首个参数均为 packageName。
     *
     * m-5: 添加包名格式验证——合法包名应包含至少一个 "." 且不含空格。
     * 旧代码取首个 String 参数即返回，可能匹配到非包名的 String 参数（如
     * ComponentName.toString() 等）。现在对每个 String 参数做格式校验，
     * 不合法则继续扫描后续参数。
     */
    private static String extractPackageFromArgs(Object[] args) {
        if (args == null || args.length == 0) return null;
        for (Object arg : args) {
            if (arg instanceof String) {
                String str = (String) arg;
                // m-5: 包名格式验证——合法包名包含至少一个 "." 且不含空格
                if (isValidPackageName(str)) {
                    return str;
                }
            }
        }
        return null;
    }

    /**
     * m-5: 验证字符串是否符合包名格式。
     * 合法包名：包含至少一个 "."，不含空格，不以 "." 开头或结尾。
     */
    private static boolean isValidPackageName(String str) {
        if (str == null || str.isEmpty()) return false;
        if (str.contains(" ")) return false;       // 包名不含空格
        if (!str.contains(".")) return false;       // 包名至少含一个 "."
        if (str.startsWith(".") || str.endsWith(".")) return false; // 不以 "." 开头/结尾
        return true;
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
        } catch (Throwable ignored) {
            Logger.d("getPackageFromActivityRecord packageName 字段失败: " + ignored.getMessage());
        }
        try {
            Method m = ReflectionUtils.findMethodRecursive(activityRecord.getClass(), "getPackage");
            if (m != null) {
                Object r = m.invoke(activityRecord);
                if (r instanceof String) return (String) r;
            }
        } catch (Throwable ignored) {
            Logger.d("getPackageFromActivityRecord getPackage() 失败: " + ignored.getMessage());
        }
        try {
            Object info = XposedHelpers.getObjectField(activityRecord, "info");
            if (info != null) {
                Object pn = XposedHelpers.getObjectField(info, "packageName");
                if (pn instanceof String) return (String) pn;
            }
        } catch (Throwable ignored) {
            Logger.d("getPackageFromActivityRecord info.packageName 失败: " + ignored.getMessage());
        }
        return null;
    }

    /**
     * 判断指定包名是否存在 FROZEN 状态的进程。
     * 通过 ProcessTracker.getAllByPackage(pkg) 检查是否有 FROZEN 状态进程。
     *
     * R8-m8-6: 已知限制——按包名检查，多进程应用仅部分进程被冻结时也会返回 true，
     * 从而跳过整个包的 sleepPackage / finishDisabledPackageActivities / goToSleep。
     * 当前行为可接受（宁可不休眠也不误清理已冻结应用的现场），因为 sleepPackage 等
     * 主要影响已冻结应用，部分冻结时整体跳过更安全。如需精确到进程级别，需额外区分
     * 调用方针对的具体进程，但系统 API 通常仅按包名操作，无法获取进程维度信息。
     *
     * M-8: 此为保守策略（已知行为限制），不改逻辑。多进程应用部分冻结时整体跳过
     * 虽然可能导致部分未冻结进程的 Activity 也被保留在内存中，但这比误清理已冻结
     * 进程的现场（导致应用从最近任务消失或重载丢失数据）代价更低。系统 API
     * sleepPackage / finishDisabledPackageActivities 均以包名为粒度操作，无法
     * 精确到进程级别，故此限制无法在当前 Hook 层面解决。
     */
    private static boolean isPackageFrozen(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            List<AppInfo> processes = ProcessTracker.getInstance().getAllByPackage(pkg);
            for (AppInfo info : processes) {
                if (info.getState() == AppState.FROZEN) return true;
            }
        } catch (Throwable t) {
            Logger.d("isPackageFrozen 检查失败: " + pkg + " - " + t.getMessage());
        }
        return false;
    }
}
