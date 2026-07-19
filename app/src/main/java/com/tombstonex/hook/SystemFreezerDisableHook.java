package com.tombstonex.hook;

import com.tombstonex.util.Logger;
import com.tombstonex.util.ReflectionUtils;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 禁用系统自带 Cached Apps Freezer 的 Hook
 *
 * Android 系统自带 Cached Apps Freezer（由 CachedAppOptimizer 实现）会在应用进入缓存后
 * 自动冻结进程，这与 TombstoneX 自身的冻结逻辑会产生冲突：
 *   - 系统 freezer 可能在 TombstoneX 未预期时冻结/解冻进程，导致状态不一致；
 *   - 系统 freezer 使用 cgroup freezer，可能与 TombstoneX 的冻结模式互相覆盖。
 *
 * 因此 TombstoneX 需要在初始化时禁用系统 freezer，并 Hook 相关入口防止其被重新启用。
 *
 * 重要：本 Hook 必须在 TombstoneX 其他冻结逻辑之前初始化（在 MainHook 中应排在最前），
 * 确保系统 freezer 不会干扰 TombstoneX 的冻结流程。
 *
 * Hook 目标（多签名兼容，适配 minSdk=32 / targetSdk=36）：
 * 1. ActivityManagerService / ActivityManagerConstants 的 setCachedAppsFreezerEnabled —— 强制参数为 false
 * 2. CachedAppOptimizer.enableFreezer —— 强制参数为 false（disabled）
 * 3. Settings.Global.getCachedAppsFreezerEnabled（若存在）—— 强制返回 false
 * 4. ActivityManagerConstants 中 KEY_CACHED_APPS_FREEZER_ENABLED 相关方法 —— after-hooked 强制字段为 false
 *
 * 此外在 init 阶段通过 Hook AMS.systemReady（after-hooked）立即调用相关方法禁用系统 freezer。
 *
 * 设计原则：
 * - 每个 Hook 独立 try/catch，单个失败不影响其他 Hook。
 * - 禁用策略：修改 boolean 参数为 false 后放行执行（让系统以"关闭"状态应用配置），
 *   而非 setResult(null) 跳过——因为跳过 enableFreezer(true) 并不能保证 freezer 已关闭，
 *   修改为 enableFreezer(false) 才能确保 disabled。
 * - 对于返回 boolean 的 getter（如 getCachedAppsFreezerEnabled），使用 setResult(false) 强制返回。
 */
public class SystemFreezerDisableHook {

    private static final String AMS_CLASS = "com.android.server.am.ActivityManagerService";
    private static final String AMC_CLASS = "com.android.server.am.ActivityManagerConstants";
    private static final String CACHED_APP_OPTIMIZER_CLASS = "com.android.server.am.CachedAppOptimizer";

    /** ActivityManagerConstants 中存储 freezer 开关的字段名 */
    private static final String FREEZER_ENABLED_FIELD = "mCachedAppsFreezerEnabled";

    public static void init(ClassLoader classLoader) {
        // 注意：以下顺序无强依赖，但 setCachedAppsFreezerEnabled / enableFreezer 的 Hook
        // 必须先于 systemReady 的主动禁用调用之前安装，以拦截系统启动期间的启用尝试。
        hookSetCachedAppsFreezerEnabled(classLoader);
        hookCachedAppOptimizerEnableFreezer(classLoader);
        hookSettingsGlobalGetCachedAppsFreezerEnabled(classLoader);
        hookActivityManagerConstantsFreezerMethods(classLoader);
        hookAmsSystemReadyToDisable(classLoader);
        Logger.i("SystemFreezerDisableHook 已初始化，系统 CachedAppsFreezer 将被禁用");
    }

    /**
     * Hook ActivityManagerService / ActivityManagerConstants 的 setCachedAppsFreezerEnabled，
     * 强制 boolean 参数为 false。
     */
    private static void hookSetCachedAppsFreezerEnabled(ClassLoader classLoader) {
        String[] targetClasses = {AMS_CLASS, AMC_CLASS};
        // 签名变体：(boolean) 或 (String key, boolean value)
        Class<?>[][] paramVariants = {
            {boolean.class},
            {String.class, boolean.class},
        };

        for (String className : targetClasses) {
            Class<?> clazz;
            try {
                clazz = XposedHelpers.findClass(className, classLoader);
            } catch (Throwable t) {
                continue;
            }
            for (Class<?>[] params : paramVariants) {
                try {
                    Method method = XposedHelpers.findMethodExact(
                        clazz, "setCachedAppsFreezerEnabled", params);
                    if (method == null) continue;

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // 定位 boolean 参数索引：(boolean) 在 index 0；(String, boolean) 在 index 1
                                int boolIdx = (param.args.length >= 2) ? 1 : 0;
                                if (boolIdx < param.args.length) {
                                    param.args[boolIdx] = Boolean.FALSE;
                                    Logger.d("已强制 setCachedAppsFreezerEnabled 参数为 false on "
                                        + clazz.getName());
                                }
                            } catch (Throwable t) {
                                Logger.e("setCachedAppsFreezerEnabled Hook 出错", t);
                            }
                        }
                    });
                    Logger.i("已 Hook setCachedAppsFreezerEnabled on " + className
                        + "（" + params.length + " 个参数）");
                } catch (Throwable e) {
                    Logger.d("setCachedAppsFreezerEnabled 变体失败 on "
                        + className + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Hook CachedAppOptimizer.enableFreezer，强制参数为 false（disabled）。
     * enableFreezer 跨版本签名变体：(boolean) / (String, boolean) / 无参。
     */
    private static void hookCachedAppOptimizerEnableFreezer(ClassLoader classLoader) {
        Class<?> clazz;
        try {
            clazz = XposedHelpers.findClass(CACHED_APP_OPTIMIZER_CLASS, classLoader);
        } catch (Throwable t) {
            Logger.w("未找到 CachedAppOptimizer 类: " + t.getMessage());
            return;
        }

        // 收集所有 enableFreezer 变体方法并逐一 Hook
        List<Method> candidates = new ArrayList<>();
        try {
            Method m = ReflectionUtils.findMethodRecursive(clazz, "enableFreezer", boolean.class);
            if (m != null) candidates.add(m);
        } catch (Throwable ignored) {}
        try {
            Method m = ReflectionUtils.findMethodRecursive(clazz, "enableFreezer", String.class, boolean.class);
            if (m != null) candidates.add(m);
        } catch (Throwable ignored) {}
        try {
            Method m = ReflectionUtils.findMethodRecursive(clazz, "enableFreezer");
            if (m != null) candidates.add(m);
        } catch (Throwable ignored) {}

        if (candidates.isEmpty()) {
            Logger.w("未找到已知签名的 CachedAppOptimizer.enableFreezer");
            return;
        }

        for (Method method : candidates) {
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            // 将最后一个 boolean 参数强制为 false
                            for (int i = param.args.length - 1; i >= 0; i--) {
                                if (param.args[i] instanceof Boolean) {
                                    param.args[i] = Boolean.FALSE;
                                    break;
                                }
                            }
                            Logger.d("已强制 CachedAppOptimizer.enableFreezer 为禁用状态");
                        } catch (Throwable t) {
                            Logger.e("enableFreezer Hook 出错", t);
                        }
                    }
                });
                Logger.i("已 Hook CachedAppOptimizer.enableFreezer（"
                    + method.getParameterCount() + " 个参数）");
            } catch (Throwable e) {
                Logger.d("enableFreezer Hook 变体失败: " + e.getMessage());
            }
        }
    }

    /**
     * Hook Settings.Global 的 getCachedAppsFreezerEnabled（若存在），强制返回 false。
     * 该方法为部分 OEM/版本的扩展，不存在时跳过。
     */
    private static void hookSettingsGlobalGetCachedAppsFreezerEnabled(ClassLoader classLoader) {
        String[] candidateClasses = {
            "android.provider.Settings$Global",
            AMS_CLASS,
            AMC_CLASS,
            CACHED_APP_OPTIMIZER_CLASS,
        };

        boolean hooked = false;
        for (String className : candidateClasses) {
            Class<?> clazz;
            try {
                clazz = XposedHelpers.findClass(className, classLoader);
            } catch (Throwable t) {
                continue;
            }
            try {
                Method method = ReflectionUtils.findMethodRecursive(
                    clazz, "getCachedAppsFreezerEnabled");
                if (method == null) continue;

                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            // 强制返回 false，表示系统 freezer 未启用
                            param.setResult(Boolean.FALSE);
                            Logger.d("已强制 getCachedAppsFreezerEnabled 返回 false on " + clazz.getName());
                        } catch (Throwable t) {
                            Logger.e("getCachedAppsFreezerEnabled Hook 出错", t);
                        }
                    }
                });
                Logger.i("已 Hook getCachedAppsFreezerEnabled on " + className);
                hooked = true;
            } catch (Throwable e) {
                Logger.d("getCachedAppsFreezerEnabled 变体失败 on "
                    + className + ": " + e.getMessage());
            }
        }
        if (!hooked) {
            // 该方法在多数 AOSP 版本不存在，未命中属正常情况，仅记录 debug 日志
            Logger.d("在候选类上未找到 getCachedAppsFreezerEnabled（在多数 AOSP 上属正常）");
        }
    }

    /**
     * Hook ActivityManagerConstants 中所有与 freezer 相关的方法（名称含 "Freezer"），
     * 在方法执行后强制 mCachedAppsFreezerEnabled 字段为 false，
     * 防止系统通过 onPropertiesChanged / updateXxxLocked 等路径重新启用 freezer。
     */
    private static void hookActivityManagerConstantsFreezerMethods(ClassLoader classLoader) {
        Class<?> clazz;
        try {
            clazz = XposedHelpers.findClass(AMC_CLASS, classLoader);
        } catch (Throwable t) {
            Logger.w("未找到 ActivityManagerConstants 类: " + t.getMessage());
            return;
        }

        // 收集名称包含 "Freezer"（忽略大小写）的声明方法
        List<Method> freezerMethods = new ArrayList<>();
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().toLowerCase().contains("freezer")) {
                    m.setAccessible(true);
                    freezerMethods.add(m);
                }
            }
        } catch (Throwable t) {
            Logger.d("枚举 ActivityManagerConstants freezer 方法失败: " + t.getMessage());
        }

        if (freezerMethods.isEmpty()) {
            Logger.w("未在 ActivityManagerConstants 上找到 freezer 相关方法");
            return;
        }

        for (Method method : freezerMethods) {
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            forceFreezerFieldFalse(param.thisObject);
                        } catch (Throwable t) {
                            Logger.e("ActivityManagerConstants freezer Hook 出错", t);
                        }
                    }
                });
                Logger.i("已 Hook ActivityManagerConstants." + method.getName());
            } catch (Throwable e) {
                Logger.d("Hook ActivityManagerConstants." + method.getName()
                    + " 失败: " + e.getMessage());
            }
        }
    }

    /**
     * Hook AMS.systemReady（多签名兼容），在系统就绪后立即通过反射禁用系统 freezer。
     * systemReady 在 system_server 启动后期调用，此时 CachedAppOptimizer /
     * ActivityManagerConstants 等组件已初始化，可安全调用禁用方法。
     */
    private static void hookAmsSystemReadyToDisable(ClassLoader classLoader) {
        Class<?> amsClass;
        try {
            amsClass = XposedHelpers.findClass(AMS_CLASS, classLoader);
        } catch (Throwable t) {
            Logger.w("未找到 AMS 类用于 systemReady Hook: " + t.getMessage());
            return;
        }

        // TimingsTraceLog 类在部分版本不存在，需安全解析（Class.forName 抛 checked 异常）
        Class<?> timingsTraceLogClass = null;
        try {
            timingsTraceLogClass = Class.forName("com.android.server.utils.TimingsTraceLog",
                false, classLoader);
        } catch (Throwable t) {
            Logger.d("未找到 TimingsTraceLog 类，将跳过 (Runnable, TimingsTraceLog) 变体");
        }

        // systemReady 跨版本签名变体（动态构建以避免 checked 异常外泄）
        List<Class<?>[]> paramVariants = new ArrayList<>();
        paramVariants.add(new Class<?>[]{});
        paramVariants.add(new Class<?>[]{Runnable.class});
        if (timingsTraceLogClass != null) {
            paramVariants.add(new Class<?>[]{Runnable.class, timingsTraceLogClass});
        }

        boolean hooked = false;
        for (Class<?>[] params : paramVariants) {
            try {
                Method method = XposedHelpers.findMethodExact(amsClass, "systemReady", params);
                if (method == null) continue;

                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Logger.i("检测到 AMS systemReady，正在禁用系统 freezer");
                            disableSystemFreezerOnAms(param.thisObject);
                        } catch (Throwable t) {
                            Logger.e("在 systemReady 时禁用系统 freezer 失败", t);
                        }
                    }
                });
                Logger.i("已 Hook AMS.systemReady（" + params.length + " 个参数）用于主动禁用 freezer");
                hooked = true;
                break;
            } catch (Throwable e) {
                Logger.d("systemReady Hook 变体失败: " + e.getMessage());
            }
        }

        if (!hooked) {
            Logger.w("未找到已知签名的 AMS.systemReady；主动禁用将仅依赖方法级 Hook");
        }
    }

    /**
     * 在 AMS 实例上主动禁用系统 freezer：
     * 1. 调用 setCachedAppsFreezerEnabled(false)（若存在）
     * 2. 通过 ActivityManagerConstants 设置字段为 false
     * 3. 调用 CachedAppOptimizer.enableFreezer(false)（若存在）
     * 每步独立 try/catch，失败不影响后续步骤。
     */
    private static void disableSystemFreezerOnAms(Object ams) {
        if (ams == null) return;

        // 1. AMS.setCachedAppsFreezerEnabled(false)
        try {
            Method m = ReflectionUtils.findMethodRecursive(
                ams.getClass(), "setCachedAppsFreezerEnabled", boolean.class);
            if (m != null) {
                m.invoke(ams, false);
                Logger.i("已通过 AMS.setCachedAppsFreezerEnabled(false) 禁用系统 freezer");
            }
        } catch (Throwable t) {
            Logger.d("AMS.setCachedAppsFreezerEnabled 调用失败: " + t.getMessage());
        }

        // 2. ActivityManagerConstants 上禁用
        try {
            Object constants = getFieldValue(ams, "mActivityManagerConstants");
            if (constants != null) {
                // 调用 setter
                Method m = ReflectionUtils.findMethodRecursive(
                    constants.getClass(), "setCachedAppsFreezerEnabled", boolean.class);
                if (m != null) {
                    m.invoke(constants, false);
                    Logger.i("已通过 ActivityManagerConstants.setCachedAppsFreezerEnabled(false) 禁用 freezer");
                }
                // 同时直接强制字段为 false（兜底）
                forceFreezerFieldFalse(constants);
            }
        } catch (Throwable t) {
            Logger.d("ActivityManagerConstants freezer 禁用失败: " + t.getMessage());
        }

        // 3. CachedAppOptimizer.enableFreezer(false)
        try {
            Object optimizer = getFieldValue(ams, "mCachedAppOptimizer");
            if (optimizer == null) {
                optimizer = getFieldValue(ams, "mOomAdjuster");
                if (optimizer != null) {
                    optimizer = getFieldValue(optimizer, "mCachedAppOptimizer");
                }
            }
            if (optimizer != null) {
                Method m = ReflectionUtils.findMethodRecursive(
                    optimizer.getClass(), "enableFreezer", boolean.class);
                if (m != null) {
                    m.invoke(optimizer, false);
                    Logger.i("已通过 CachedAppOptimizer.enableFreezer(false) 禁用 freezer");
                }
            }
        } catch (Throwable t) {
            Logger.d("CachedAppOptimizer.enableFreezer 调用失败: " + t.getMessage());
        }

        Logger.i("系统 CachedAppsFreezer 禁用尝试已完成");
    }

    // ==================== 辅助方法 ====================

    /** 强制 ActivityManagerConstants 的 mCachedAppsFreezerEnabled 字段为 false */
    private static void forceFreezerFieldFalse(Object constants) {
        if (constants == null) return;
        try {
            Field f = ReflectionUtils.findFieldRecursive(constants.getClass(), FREEZER_ENABLED_FIELD);
            if (f != null) {
                f.set(constants, false);
                Logger.d("已强制 " + FREEZER_ENABLED_FIELD + " = false on "
                    + constants.getClass().getName());
            }
        } catch (Throwable t) {
            Logger.d("forceFreezerFieldFalse 失败: " + t.getMessage());
        }
    }

    /** 安全获取对象字段值 */
    private static Object getFieldValue(Object obj, String fieldName) {
        if (obj == null) return null;
        Field field = ReflectionUtils.findFieldRecursive(obj.getClass(), fieldName);
        return field != null ? ReflectionUtils.getFieldValue(obj, field) : null;
    }
}
