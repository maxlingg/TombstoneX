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
        // R8-m7: 已知限制 — 本 Hook 未覆盖 DeviceConfig / SystemProperties 层面的 freezer
        // 配置覆盖（如 DeviceConfig.CachedAppsFreezerEnabled）。当前覆盖（AMS/AMC/CachedAppOptimizer
        // 方法级 Hook + systemReady 主动禁用）在多数设备上已足够。若未来发现 OEM 通过
        // DeviceConfig 重新启用 freezer，可在此添加 DeviceConfig.named() Hook。低优先级。
        // 注意：以下顺序无强依赖，但 setCachedAppsFreezerEnabled / enableFreezer 的 Hook
        // 必须先于 systemReady 的主动禁用调用之前安装，以拦截系统启动期间的启用尝试。
        hookSetCachedAppsFreezerEnabled(classLoader);
        hookCachedAppOptimizerEnableFreezer(classLoader);
        hookSettingsGlobalGetCachedAppsFreezerEnabled(classLoader);
        hookActivityManagerConstantsFreezerMethods(classLoader);
        hookAmsSystemReadyToDisable(classLoader);
        // M-9: systemReady 之外的其他 freezer 启用入口补充覆盖
        hookAmsFinishBooting(classLoader);
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
                    // m-11: 成功 hook 后添加 break，避免对同一类的多个重载变体重复 Hook
                    break;
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
        } catch (Throwable ignored) {
            Logger.d("enableFreezer(boolean) 未找到: " + ignored.getMessage());
        }
        try {
            Method m = ReflectionUtils.findMethodRecursive(clazz, "enableFreezer", String.class, boolean.class);
            if (m != null) candidates.add(m);
        } catch (Throwable ignored) {
            Logger.d("enableFreezer(String,boolean) 未找到: " + ignored.getMessage());
        }
        try {
            Method m = ReflectionUtils.findMethodRecursive(clazz, "enableFreezer");
            if (m != null) candidates.add(m);
        } catch (Throwable ignored) {
            Logger.d("enableFreezer() 未找到: " + ignored.getMessage());
        }
        // M-8: Android 15+ 引入 (int) 和 (String, int) 变体
        try {
            Method m = ReflectionUtils.findMethodRecursive(clazz, "enableFreezer", int.class);
            if (m != null) candidates.add(m);
        } catch (Throwable ignored) {
            Logger.d("enableFreezer(int) 未找到: " + ignored.getMessage());
        }
        try {
            Method m = ReflectionUtils.findMethodRecursive(clazz, "enableFreezer", String.class, int.class);
            if (m != null) candidates.add(m);
        } catch (Throwable ignored) {
            Logger.d("enableFreezer(String,int) 未找到: " + ignored.getMessage());
        }

        if (candidates.isEmpty()) {
            Logger.w("未找到已知签名的 CachedAppOptimizer.enableFreezer");
            return;
        }

        for (Method method : candidates) {
            try {
                final boolean hasBooleanParam = java.util.Arrays.stream(method.getParameterTypes())
                    .anyMatch(t -> t == boolean.class);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args.length == 0) {
                                // R8-M5: 不跳过执行，让初始化副作用执行，在 afterHook 强制禁用
                                Logger.d("无参 enableFreezer 调用放行，将在 afterHook 强制禁用");
                                return; // 不 setResult，让方法执行
                            }
                            // 将最后一个 boolean 参数强制为 false
                            for (int i = param.args.length - 1; i >= 0; i--) {
                                if (param.args[i] instanceof Boolean) {
                                    param.args[i] = Boolean.FALSE;
                                    break;
                                }
                            }
                            // M-8: 将最后一个 int 参数强制为 0（disabled），
                            // 覆盖 Android 15 的 (int) / (String, int) 变体
                            for (int i = param.args.length - 1; i >= 0; i--) {
                                if (param.args[i] instanceof Integer) {
                                    param.args[i] = 0;
                                    break;
                                }
                            }
                            Logger.d("已强制 CachedAppOptimizer.enableFreezer 为禁用状态");
                        } catch (Throwable t) {
                            Logger.e("enableFreezer Hook 出错", t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            // R8-M5: 强制 mFreezerEnabled / mCachedAppsFreezerEnabled 字段为 false，
                            // 确保无参变体也不会启用 freezer
                            forceFreezerEnabledFieldFalse(param.thisObject);
                        } catch (Throwable t) {
                            Logger.e("enableFreezer afterHook 出错", t);
                        }
                    }
                });
                Logger.i("已 Hook CachedAppOptimizer.enableFreezer（"
                    + method.getParameterCount() + " 个参数，hasBool=" + hasBooleanParam + "）");
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

                // R10-m-7: 根据方法返回类型选择对应的"禁用"值，
                // 避免对 int 返回类型使用 setResult(Boolean.FALSE) 导致类型不匹配
                final Class<?> returnType = method.getReturnType();
                final Object disabledValue;
                if (returnType == boolean.class) {
                    disabledValue = Boolean.FALSE;
                } else if (returnType == int.class) {
                    disabledValue = 0;
                } else {
                    // R11-m-7: 对未知返回类型跳过 Hook 而非使用默认值，避免类型不匹配
                    Logger.w("getCachedAppsFreezerEnabled 返回类型未知: "
                        + returnType.getName() + "，跳过 Hook");
                    continue; // 跳过此候选
                }

                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            // 强制返回禁用值，表示系统 freezer 未启用
                            param.setResult(disabledValue);
                            Logger.d("已强制 getCachedAppsFreezerEnabled 返回禁用值 on " + clazz.getName());
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
     * Hook ActivityManagerConstants 中 freezer 开关相关方法（set*FreezerEnabled），
     * 在方法执行后强制 mCachedAppsFreezerEnabled 字段为 false，
     * 防止系统通过 onPropertiesChanged / updateXxxLocked 等路径重新启用 freezer。
     *
     * R8-M6: 跳过 setCachedAppsFreezerEnabled（已由 hookSetCachedAppsFreezerEnabled 独立 Hook）。
     * R8-m3: 仅精确匹配 freezer 开关方法（set*FreezerEnabled），对非开关方法仅记录。
     */
    private static void hookActivityManagerConstantsFreezerMethods(ClassLoader classLoader) {
        Class<?> clazz;
        try {
            clazz = XposedHelpers.findClass(AMC_CLASS, classLoader);
        } catch (Throwable t) {
            Logger.w("未找到 ActivityManagerConstants 类: " + t.getMessage());
            return;
        }

        // R8-M6: 跳过已独立 Hook 的 setCachedAppsFreezerEnabled，避免双重 Hook。
        // R8-m3: 精确匹配 freezer 开关方法名（set*FreezerEnabled），对非开关方法仅记录不强制字段。
        List<Method> freezerMethods = new ArrayList<>();
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                String nameLower = m.getName().toLowerCase();
                // R8-M6: 跳过已独立 Hook 的 setCachedAppsFreezerEnabled
                if (m.getName().equals("setCachedAppsFreezerEnabled")) {
                    Logger.d("跳过已独立 Hook 的方法: " + m.getName());
                    continue;
                }
                // R8-m3: 精确匹配 freezer 开关方法名
                if (nameLower.startsWith("set") && nameLower.contains("freezerenabled")) {
                    m.setAccessible(true);
                    freezerMethods.add(m);
                } else if (nameLower.contains("freezer")
                           && !nameLower.startsWith("get") && !nameLower.startsWith("is")) {
                    // R8-m3: 非 freezer 开关方法仅记录不强制字段
                    Logger.d("监控 freezer 相关方法（不强制字段）: " + m.getName());
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
            Logger.d("Class.forName 未找到 TimingsTraceLog，尝试 XposedHelpers.findClass 兜底: " + t.getMessage());
            // R10-m-9: Class.forName 失败时通过 XposedHelpers.findClass 兜底查找，
            // 覆盖部分 boot classloader 隔离场景，确保最常见的 2 参数 systemReady 签名能被尝试
            try {
                timingsTraceLogClass = XposedHelpers.findClass(
                    "com.android.server.utils.TimingsTraceLog", classLoader);
            } catch (Throwable t2) {
                Logger.d("XposedHelpers.findClass 也未找到 TimingsTraceLog，将跳过 (Runnable, TimingsTraceLog) 变体");
            }
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
     * M-9: Hook AMS.finishBooting（after-hook），作为 systemReady 之外的补充触发点。
     * finishBooting 在系统启动完成后调用，部分 OEM 可能在此阶段重新启用 freezer。
     * 与 systemReady 的 after-hook 共享 disableSystemFreezerOnAms 逻辑。
     */
    private static void hookAmsFinishBooting(ClassLoader classLoader) {
        Class<?> amsClass;
        try {
            amsClass = XposedHelpers.findClass(AMS_CLASS, classLoader);
        } catch (Throwable t) {
            Logger.d("未找到 AMS 类用于 finishBooting Hook: " + t.getMessage());
            return;
        }
        try {
            Method method = XposedHelpers.findMethodExact(amsClass, "finishBooting");
            if (method == null) {
                Logger.d("未找到 AMS.finishBooting 方法");
                return;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Logger.i("检测到 AMS finishBooting，正在补充禁用系统 freezer");
                        disableSystemFreezerOnAms(param.thisObject);
                    } catch (Throwable t) {
                        Logger.e("在 finishBooting 时禁用系统 freezer 失败", t);
                    }
                }
            });
            Logger.i("已 Hook AMS.finishBooting 用于补充禁用 freezer");
        } catch (Throwable e) {
            Logger.d("finishBooting Hook 变体失败: " + e.getMessage());
        }
    }

    /**
     * 在 AMS 实例上主动禁用系统 freezer：
     * 1. 调用 setCachedAppsFreezerEnabled(false)（若存在）
     * 2. 通过 ActivityManagerConstants 设置字段为 false
     * 3. 调用 CachedAppOptimizer.enableFreezer(false)（若存在）
     * 每步独立 try/catch，失败不影响后续步骤。
     *
     * R9-m4: 本方法通过反射直接调用 setCachedAppsFreezerEnabled / enableFreezer 等
     * 已被 Hook 的方法。由于 Hook 逻辑幂等（强制 boolean 参数为 false），即使经过
     * Hook 回调再次被强制为 false，结果与直接调用一致，不会产生副作用。可接受现状。
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
                // R10-m-8: 根据字段类型设置对应值
                // m-9: 根据字段类型记录正确的值，避免对 int 字段记录 "= false"
                if (f.getType() == boolean.class) {
                    f.set(constants, false);
                    Logger.d("已强制 " + FREEZER_ENABLED_FIELD + " = false on "
                        + constants.getClass().getName());
                } else if (f.getType() == int.class) {
                    f.setInt(constants, 0);
                    Logger.d("已强制 " + FREEZER_ENABLED_FIELD + " = 0 on "
                        + constants.getClass().getName());
                } else {
                    Logger.w("未知 freezer 字段类型: " + f.getType().getName());
                }
            }
        } catch (Throwable t) {
            Logger.d("forceFreezerFieldFalse 失败: " + t.getMessage());
        }
    }

    /**
     * 强制 CachedAppOptimizer 的 freezer 启用字段为 false。
     * R8-M5: 尝试 mFreezerEnabled（CachedAppOptimizer 常见字段名），
     * 降级尝试 mCachedAppsFreezerEnabled。
     * M-10: 添加模糊匹配降级——遍历类所有字段，对名称包含 "freezer" 且
     * 类型为 boolean/int 的字段强制设置，覆盖 OEM 定制字段名。
     */
    private static void forceFreezerEnabledFieldFalse(Object optimizer) {
        if (optimizer == null) return;
        // 尝试 mFreezerEnabled
        try {
            Field f = ReflectionUtils.findFieldRecursive(optimizer.getClass(), "mFreezerEnabled");
            if (f != null) {
                // R10-m-8: 根据字段类型设置对应值
                // m-9: 根据字段类型记录正确的值，避免对 int 字段记录 "= false"
                if (f.getType() == boolean.class) {
                    f.set(optimizer, false);
                    Logger.d("已强制 mFreezerEnabled = false on " + optimizer.getClass().getName());
                    return; // 成功设置，无需降级
                } else if (f.getType() == int.class) {
                    f.setInt(optimizer, 0);
                    Logger.d("已强制 mFreezerEnabled = 0 on " + optimizer.getClass().getName());
                    return; // 成功设置，无需降级
                } else {
                    // R11-m-4: 未知字段类型时不 return，继续到 forceFreezerFieldFalse 降级
                    Logger.w("未知 mFreezerEnabled 字段类型: " + f.getType().getName() + "，尝试降级字段");
                }
            }
        } catch (Throwable t) {
            Logger.d("forceFreezerEnabledFieldFalse (mFreezerEnabled) 失败: " + t.getMessage());
        }
        // 降级：尝试 mCachedAppsFreezerEnabled
        forceFreezerFieldFalse(optimizer);
        // M-10: 最终降级——模糊匹配所有包含 "freezer" 的 boolean/int 字段
        forceFreezerFieldsByFuzzyMatch(optimizer);
    }

    /**
     * M-10: 模糊匹配——遍历类所有字段（含父类），对名称包含 "freezer" 且类型为
     * boolean/int 的字段强制设置为禁用值。覆盖 OEM 定制字段名。
     * m-9: 根据字段类型记录正确的值。
     */
    private static void forceFreezerFieldsByFuzzyMatch(Object obj) {
        if (obj == null) return;
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                String nameLower = f.getName().toLowerCase();
                if (nameLower.contains("freezer")) {
                    try {
                        f.setAccessible(true);
                        if (f.getType() == boolean.class) {
                            f.set(obj, false);
                            Logger.d("已强制 (模糊匹配) " + f.getName()
                                + " = false on " + obj.getClass().getName());
                        } else if (f.getType() == int.class) {
                            f.setInt(obj, 0);
                            Logger.d("已强制 (模糊匹配) " + f.getName()
                                + " = 0 on " + obj.getClass().getName());
                        }
                    } catch (Throwable t) {
                        Logger.d("模糊匹配设置字段 " + f.getName() + " 失败: " + t.getMessage());
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    /** 安全获取对象字段值 */
    private static Object getFieldValue(Object obj, String fieldName) {
        if (obj == null) return null;
        Field field = ReflectionUtils.findFieldRecursive(obj.getClass(), fieldName);
        return field != null ? ReflectionUtils.getFieldValue(obj, field) : null;
    }
}
