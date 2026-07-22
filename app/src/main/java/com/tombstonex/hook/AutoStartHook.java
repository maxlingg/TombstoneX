package com.tombstonex.hook;

import android.content.Intent;
import android.os.Binder;
import com.tombstonex.manager.FreezeManager;
import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.manager.WhitelistManager;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 自启拦截 Hook
 * 拦截后台应用的自启动行为：
 * - Hook ActivityManagerService.startService（多签名兼容，system_server 端拦截）
 * 当被冻结应用尝试启动服务/Activity 时：
 * - 若目标应用已冻结，先解冻目标应用，3 秒后重新冻结
 * - 若发送方被冻结，拦截其 startService 调用（除非配置了 autoStartAllowed）
 * R8-m2: 已移除 ContextImpl.startService 和 BroadcastReceiver.onReceive 的无效 hook（死代码）。
 */
public class AutoStartHook {

    private static final int UNFREEZE_TEMP_SEC = 3;

    // 用于临时解冻后重新冻结
    private static final ScheduledThreadPoolExecutor scheduler =
        new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "TombstoneX-AutoStart");
            t.setDaemon(true);
            return t;
        });
    static {
        scheduler.setRemoveOnCancelPolicy(true);
    }

    // M3: 跟踪待重新冻结的任务（按包名去重），避免短时间内多次 startService 触发重复重新冻结
    private static final java.util.Map<String, java.util.concurrent.ScheduledFuture<?>> pendingRefreezes =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * R8-m1: 取消指定包名的待重新冻结任务（供 ProcessDeathHook.cleanupDeadProcess 调用）。
     * 进程死亡后清理残留的重新冻结任务，避免对已死亡进程执行无意义的冻结操作。
     */
    public static void cancelPendingRefreeze(String packageName) {
        if (packageName == null) return;
        java.util.concurrent.ScheduledFuture<?> future = pendingRefreezes.remove(packageName);
        if (future != null) {
            future.cancel(false);
            Logger.d("已取消待重新冻结任务: " + packageName);
        }
    }

    public static void init(ClassLoader classLoader) {
        hookAmsStartService(classLoader);
        // R8-m2: 已删除 hookContextImplStartService 与 hookBroadcastReceiverOnReceive（死代码）。
        // 这两个方法在 system_server 中无效（ContextImpl.startService 在调用方应用进程执行，
        // BroadcastReceiver.onReceive 在接收方进程执行），MainHook 仅在 system_server 中加载。
        // 真正的服务启动拦截在 hookAmsStartService（system_server 端）中完成；
        // 广播拦截由 BroadcastHook 负责。
    }

    /**
     * Hook ActivityManagerService.startService — 多签名兼容
     * 在 system_server 中拦截所有 startService 重载。
     * 通过 Binder.getCallingUid() 判断发送方；callerUid >= 10000 守卫确保只拦截用户应用，
     * 系统（uid=1000）发起的内部调用会被跳过，避免破坏系统服务。
     */
    private static void hookAmsStartService(ClassLoader classLoader) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);

            XC_MethodHook callback = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        int callerUid = Binder.getCallingUid();

                        // 发送方被冻结 → 拦截（setResult(null) 使调用方收到 null）
                        // R10-m-8: isUidFrozen 按 UID 级别检查，shared UID 场景下同一 UID
                        // 共享多个包名，任一包名进程被冻结即视为整个 UID 被冻结，
                        // 可能对共享 UID 中未冻结的包产生误拦截。当前保持 UID 级别检查
                        // （与系统冻结粒度一致），详见 isUidFrozen 方法注释。
                        if (callerUid >= 10000 && isUidFrozen(callerUid)
                            && !isAutoStartAllowed(callerUid)) {
                            Logger.i("拦截来自已冻结调用方的 startService uid=" + callerUid
                                + "（UID 级别检查，shared UID 场景下可能含未冻结包名）");
                            // M-7: startService 在 Android 8+ 返回 ComponentName，null 是合法返回值
                            // 表示启动失败。此处有意返回 null 使调用方感知服务未启动，不会导致 NPE。
                            param.setResult(null);
                            return;
                        }

                        // 检查目标应用是否被冻结，若被冻结则临时解冻
                        Intent intent = findIntentArg(param.args);
                        if (intent != null) {
                            handleTargetPackage(intent);
                        }
                    } catch (Throwable t) {
                        Logger.e("AMS.startService Hook 出错", t);
                    }
                }
            };

            int n = hookAllMethodsByName(amsClass, "startService", callback);
            Logger.i("已 Hook AMS startService (" + n + " 个重载)");
        } catch (Throwable t) {
            Logger.e("Hook AMS.startService 失败", t);
        }
    }

    /**
     * 检查目标应用是否被冻结，若被冻结则临时解冻并在 3 秒后重新冻结
     *
     * R11-m-7: 此处存在 TOCTOU 窗口：在 getAllByPackage 获取 targetProcesses 快照与
     * 后续 unfreezePackage / freezePackage 操作之间，目标进程可能已被系统杀死或状态变更。
     * 此窗口由 FreezeManager 内部的进程存活检查兜底：freezeProcess / unfreezeProcess
     * 在执行前会验证 pid 对应的进程是否仍存活、状态是否匹配，对已死亡或不匹配的进程
     * 跳过操作，不会产生功能性错误。
     */
    private static void handleTargetPackage(Intent intent) {
        String targetPkg = getTargetPackage(intent);
        if (targetPkg == null) return;

        // R10-m-9: 从 ProcessTracker 已注册进程中获取 isSystemApp 字段，
        // 旧代码硬编码为 false 导致系统应用无法走系统白名单分支。
        // 若目标包无已注册进程则保持 false（保守按用户应用处理）。
        List<AppInfo> targetProcesses = ProcessTracker.getInstance().getAllByPackage(targetPkg);
        boolean isSystemApp = !targetProcesses.isEmpty() ? targetProcesses.get(0).isSystemApp() : false;

        // 检查目标应用是否在白名单（白名单应用不会被冻结，无需处理）
        if (!WhitelistManager.getInstance().shouldFreeze(targetPkg, targetPkg, isSystemApp)) {
            return;
        }

        // 检查目标应用是否有冻结进程
        boolean targetFrozen = false;
        for (AppInfo info : targetProcesses) {
            if (info.getState() == AppState.FROZEN) {
                targetFrozen = true;
                break;
            }
        }

        if (targetFrozen) {
            Logger.i("目标应用已冻结，临时解冻以启动: " + targetPkg);
            // 解冻目标应用所有进程
            FreezeManager.getInstance().unfreezePackage(targetPkg);
            // 3 秒后重新冻结（按包名去重，短时间内多次 startService 只保留最后一次重新冻结）
            final String pkg = targetPkg;
            pendingRefreezes.compute(pkg, (k, oldFuture) -> {
                if (oldFuture != null) oldFuture.cancel(false);
                // M1: 捕获 ScheduledFuture 引用，finally 中使用条件 remove 避免误删新 future
                java.util.concurrent.ScheduledFuture<?>[] holder = new java.util.concurrent.ScheduledFuture<?>[1];
                holder[0] = scheduler.schedule(() -> {
                    try {
                        FreezeManager.getInstance().freezePackage(pkg);
                        Logger.d("临时解冻后已重新冻结目标应用: " + pkg);
                    } catch (Throwable t) {
                        Logger.e("AutoStartHook 重新冻结失败: " + pkg, t);
                    } finally {
                        pendingRefreezes.remove(pkg, holder[0]);
                    }
                }, UNFREEZE_TEMP_SEC, TimeUnit.SECONDS);
                return holder[0];
            });
        }
    }

    /**
     * 从参数数组中查找 Intent 参数
     */
    private static Intent findIntentArg(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof Intent) {
                return (Intent) arg;
            }
        }
        return null;
    }

    /**
     * 从 Intent 中提取目标包名
     */
    private static String getTargetPackage(Intent intent) {
        try {
            // 优先从 Component 获取（直接调用，无需反射）
            android.content.ComponentName component = intent.getComponent();
            if (component != null) {
                String pkg = component.getPackageName();
                if (pkg != null) return pkg;
            }
            // 其次从 Intent.getPackage() 获取
            Object pkg = XposedHelpers.callMethod(intent, "getPackage");
            if (pkg instanceof String && !((String) pkg).isEmpty()) {
                return (String) pkg;
            }
        } catch (Throwable t) {
            Logger.d("getTargetPackage 失败: " + t.getMessage());
        }
        return null;
    }

    /**
     * 检查指定 UID 是否已被冻结
     *
     * R10-m-8: 按 UID 级别检查冻结状态。shared UID 场景下（多个包名共享同一 UID，
     * 如 android:sharedUserId），该 UID 下任一包名的进程被冻结即返回 true。
     * 这意味着共享 UID 中未被冻结的包名也会被视为"已冻结"而被拦截 startService，
     * 可能产生误拦截。但系统 freezer 本身也以 UID 为粒度操作（cgroup v2 freezer
     * 按 UID 归类进程），因此此处保持 UID 级别检查与系统行为一致。
     * 若后续需精确到包名级别，需改为遍历该 UID 下所有进程并按包名分组判断。
     */
    private static boolean isUidFrozen(int uid) {
        try {
            List<AppInfo> processes = ProcessTracker.getInstance().getByUid(uid);
            for (AppInfo info : processes) {
                if (info.getState() == AppState.FROZEN) return true;
            }
        } catch (Throwable t) {
            Logger.d("isUidFrozen 失败 uid=" + uid + ": " + t.getMessage());
        }
        return false;
    }

    // R8-M7: 缓存反射 Method 引用，避免每次 startService 回调都执行 Class.forName + getMethod
    private static volatile Method cachedIsAutoStartAllowedMethod = null;
    // R9-m1: 同步缓存 getInstance Method，避免每次 isAutoStartAllowed 回调都反射查找
    private static volatile Method cachedGetInstanceMethod = null;
    private static volatile boolean methodLookupDone = false;

    /**
     * R8-M7: 线程安全地查找并缓存 isAutoStartAllowed Method 引用（仅查找一次）
     * R9-m1: 同时缓存 getInstance Method，避免每次回调都反射查找。
     */
    private static synchronized Method lookupIsAutoStartAllowedMethod() {
        if (methodLookupDone) return cachedIsAutoStartAllowedMethod;
        try {
            Class<?> clazz = Class.forName("com.tombstonex.manager.AppConfigManager");
            // R9-m1: 一并缓存 getInstance Method
            try {
                cachedGetInstanceMethod = clazz.getMethod("getInstance");
            } catch (NoSuchMethodException ignore) {
                cachedGetInstanceMethod = null;
            }
            try {
                cachedIsAutoStartAllowedMethod = clazz.getMethod("isAutoStartAllowed", String.class);
            } catch (NoSuchMethodException ignore) {
                try {
                    cachedIsAutoStartAllowedMethod = clazz.getMethod("isAutoStartAllowed");
                } catch (NoSuchMethodException ignore2) {
                    cachedIsAutoStartAllowedMethod = null;
                }
            }
        } catch (ClassNotFoundException e) {
            Logger.d("未找到 AppConfigManager，autoStartAllowed 默认为 false");
            cachedIsAutoStartAllowedMethod = null;
        }
        methodLookupDone = true;
        return cachedIsAutoStartAllowedMethod;
    }

    /**
     * 检查 AppConfigManager.autoStartAllowed 配置（通过反射调用，不存在则默认 false）
     * 实际签名为 isAutoStartAllowed(String packageName)；
     * autoStartAllowed=true 时允许被冻结应用自启动服务。
     *
     * R8-M7: 缓存 Method 引用到 static volatile 字段，避免每次 startService 回调
     * 都执行 Class.forName + getMethod 反射查找。
     * R8-m7: 对 shared uid 遍历所有包名，检查是否有任一配置了 autoStartAllowed，
     * 而非仅取首个包名。
     */
    private static boolean isAutoStartAllowed(int uid) {
        if (uid < 10000) return false;

        // R8-m7: 收集该 uid 下所有包名（shared uid 场景下可能有多个包名）
        java.util.Set<String> packageNames = new java.util.HashSet<>();
        try {
            List<AppInfo> processes = ProcessTracker.getInstance().getByUid(uid);
            for (AppInfo info : processes) {
                if (info.packageName != null) {
                    packageNames.add(info.packageName);
                }
            }
        } catch (Throwable t) {
            Logger.d("isAutoStartAllowed: 获取包名失败 uid=" + uid + ": " + t.getMessage());
        }
        if (packageNames.isEmpty()) return false;

        // R8-M7: 使用缓存的 Method 引用，避免每次反射查找
        Method m = cachedIsAutoStartAllowedMethod;
        if (!methodLookupDone) {
            m = lookupIsAutoStartAllowedMethod();
        }
        if (m == null) return false;

        try {
            // R9-m1: 使用缓存的 getInstance Method，避免每次回调都反射查找
            Method getInstance = cachedGetInstanceMethod;
            if (getInstance == null) return false;
            Object instance = getInstance.invoke(null);
            if (instance == null) return false;

            boolean hasParam = m.getParameterCount() >= 1;
            // R8-m7: 遍历所有包名，任一配置了 autoStartAllowed 即允许
            for (String pkg : packageNames) {
                Object result;
                if (hasParam) {
                    result = m.invoke(instance, pkg);
                } else {
                    result = m.invoke(instance);
                }
                if (result instanceof Boolean && (Boolean) result) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Logger.d("isAutoStartAllowed 检查失败: " + t.getMessage());
        }
        return false;
    }

    /**
     * 枚举类中所有指定名称的方法并逐一 hook（替代 hookAllMethods，stub 未提供该方法）
     * m-6: 过滤 synthetic/bridge 方法，避免 hook 编译器生成的方法（与 TimerHook 保持一致）
     */
    private static int hookAllMethodsByName(Class<?> clazz, String methodName, XC_MethodHook callback) {
        int count = 0;
        if (clazz == null) return 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName) && !method.isSynthetic()) {
                try {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, callback);
                    count++;
                } catch (Throwable t) {
                    Logger.d("hookMethod 失败 " + methodName + ": " + t.getMessage());
                }
            }
        }
        return count;
    }
}
