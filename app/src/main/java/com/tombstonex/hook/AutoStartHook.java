package com.tombstonex.hook;

import android.content.Intent;
import android.os.Binder;
import android.os.Process;
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
 * - Hook ActivityManagerService.startService（多签名兼容）
 * - Hook ContextImpl.startService / startForegroundService
 * - Hook BroadcastReceiver.onReceive，检查发送方是否被冻结
 * 当被冻结应用尝试启动服务/Activity 时：
 * - 若目标应用已冻结，先解冻目标应用，3 秒后重新冻结
 * - 若发送方被冻结，拦截其 startService 调用（除非配置了 autoStartAllowed）
 */
public class AutoStartHook {

    private static final int UNFREEZE_TEMP_SEC = 3;

    // 用于临时解冻后重新冻结
    private static final ScheduledThreadPoolExecutor scheduler =
        new ScheduledThreadPoolExecutor(1);
    static {
        scheduler.setRemoveOnCancelPolicy(true);
    }

    public static void init(ClassLoader classLoader) {
        hookAmsStartService(classLoader);
        hookContextImplStartService(classLoader);
        hookBroadcastReceiverOnReceive(classLoader);
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
                        if (callerUid >= 10000 && isUidFrozen(callerUid)
                            && !isAutoStartAllowed(callerUid)) {
                            Logger.i("拦截来自已冻结调用方的 startService uid=" + callerUid);
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
     * Hook ContextImpl.startService / startForegroundService
     * 注意：这些方法在应用进程中执行，system_server 中 hook 仅覆盖系统自身的调用；
     * 若模块注入到应用进程则可拦截应用自身的 startService 调用。
     */
    private static void hookContextImplStartService(ClassLoader classLoader) {
        try {
            Class<?> contextImplClass = XposedHelpers.findClass(
                "android.app.ContextImpl", classLoader);

            XC_MethodHook callback = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        // 通过 Binder.getCallingUid() 或当前进程 uid 判断发送方
                        int callerUid = Binder.getCallingUid();
                        if (callerUid < 10000) return; // 系统应用不拦截

                        // 检查发送方是否被冻结
                        if (isUidFrozen(callerUid) && !isAutoStartAllowed(callerUid)) {
                            Logger.i("拦截来自已冻结应用的 startService uid=" + callerUid);
                            param.setResult(null);
                            return;
                        }

                        // 从参数中查找 Intent
                        Intent intent = findIntentArg(param.args);
                        if (intent != null) {
                            handleTargetPackage(intent);
                        }
                    } catch (Throwable t) {
                        Logger.e("ContextImpl.startService Hook 出错", t);
                    }
                }
            };

            int n1 = hookAllMethodsByName(contextImplClass, "startService", callback);
            int n2 = hookAllMethodsByName(contextImplClass, "startForegroundService", callback);
            Logger.i("已 Hook ContextImpl (startService=" + n1
                + " startForegroundService=" + n2 + ")");
        } catch (Throwable t) {
            Logger.w("Hook ContextImpl.startService 失败: " + t.getMessage());
        }
    }

    /**
     * Hook BroadcastReceiver.onReceive — 此方法在接收方进程执行
     *
     * 已知限制：MainHook 只在 system_server (android 包) 中加载此 Hook，
     * 但 onReceive 在接收方应用进程中执行。因此此 Hook 在 system_server 中
     * 几乎不会触发（system_server 极少有 BroadcastReceiver 子类）。
     *
     * 广播拦截的真正实现在 BroadcastHook 中（通过 hook BroadcastQueue.processNextBroadcast）。
     * 此处保留 Hook 以兼容未来可能的应用进程注入场景，
     * 并修正 uid 获取逻辑：使用 Process.myUid() 而非 Binder.getCallingUid()。
     */
    private static void hookBroadcastReceiverOnReceive(ClassLoader classLoader) {
        try {
            Class<?> receiverClass = XposedHelpers.findClass(
                "android.content.BroadcastReceiver", classLoader);

            XC_MethodHook callback = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        // Process.myUid() 返回当前进程（接收方）的 uid
                        // 旧代码用 Binder.getCallingUid() 获取的是发送方 uid，逻辑错误
                        int uid = Process.myUid();
                        if (uid < 10000) return; // 跳过系统进程
                        if (isUidFrozen(uid) && !isAutoStartAllowed(uid)) {
                            Logger.i("跳过已冻结 uid 的 BroadcastReceiver.onReceive uid=" + uid);
                            param.setResult(null);
                        }
                    } catch (Throwable t) {
                        Logger.e("BroadcastReceiver.onReceive Hook 出错", t);
                    }
                }
            };

            int n = hookAllMethodsByName(receiverClass, "onReceive", callback);
            Logger.i("已 Hook BroadcastReceiver.onReceive (" + n + " 个重载)");
        } catch (Throwable t) {
            Logger.w("Hook BroadcastReceiver.onReceive 失败: " + t.getMessage());
        }
    }

    /**
     * 检查目标应用是否被冻结，若被冻结则临时解冻并在 3 秒后重新冻结
     */
    private static void handleTargetPackage(Intent intent) {
        String targetPkg = getTargetPackage(intent);
        if (targetPkg == null) return;

        // 检查目标应用是否在白名单（白名单应用不会被冻结，无需处理）
        boolean isSystemApp = false; // 目标包未知 uid，保守按用户应用处理
        if (!WhitelistManager.getInstance().shouldFreeze(targetPkg, targetPkg, isSystemApp)) {
            return;
        }

        // 检查目标应用是否有冻结进程
        List<AppInfo> targetProcesses = ProcessTracker.getInstance().getAllByPackage(targetPkg);
        boolean targetFrozen = false;
        for (AppInfo info : targetProcesses) {
            if (info.state == AppState.FROZEN) {
                targetFrozen = true;
                break;
            }
        }

        if (targetFrozen) {
            Logger.i("目标应用已冻结，临时解冻以启动: " + targetPkg);
            // 解冻目标应用所有进程
            FreezeManager.getInstance().unfreezePackage(targetPkg);
            // 3 秒后重新冻结
            final String pkg = targetPkg;
            scheduler.schedule(() -> {
                try {
                    FreezeManager.getInstance().freezePackage(pkg);
                    Logger.d("临时解冻后已重新冻结目标应用: " + pkg);
                } catch (Throwable t) {
                    Logger.e("重新冻结目标应用出错: " + pkg, t);
                }
            }, UNFREEZE_TEMP_SEC, TimeUnit.SECONDS);
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
            // 优先从 Component 获取
            Object component = XposedHelpers.callMethod(intent, "getComponent");
            if (component != null) {
                Object pkg = XposedHelpers.callMethod(component, "getPackageName");
                if (pkg instanceof String) return (String) pkg;
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
     */
    private static boolean isUidFrozen(int uid) {
        try {
            List<AppInfo> processes = ProcessTracker.getInstance().getByUid(uid);
            for (AppInfo info : processes) {
                if (info.state == AppState.FROZEN) return true;
            }
        } catch (Throwable t) {
            Logger.d("isUidFrozen 失败 uid=" + uid + ": " + t.getMessage());
        }
        return false;
    }

    /**
     * 检查 AppConfigManager.autoStartAllowed 配置（通过反射调用，不存在则默认 false）
     * 实际签名为 isAutoStartAllowed(String packageName)；
     * autoStartAllowed=true 时允许被冻结应用自启动服务。
     */
    private static boolean isAutoStartAllowed(int uid) {
        if (uid < 10000) return false;
        String packageName = getPackageNameForUid(uid);
        if (packageName == null) return false;
        try {
            Class<?> clazz = Class.forName("com.tombstonex.manager.AppConfigManager");
            Method getInstance = clazz.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            if (instance == null) return false;
            // 优先尝试带 packageName 的签名
            try {
                Method m = clazz.getMethod("isAutoStartAllowed", String.class);
                Object result = m.invoke(instance, packageName);
                if (result instanceof Boolean) return (Boolean) result;
            } catch (NoSuchMethodException ignore) {
                // 兼容：尝试无参变体
                try {
                    Method m = clazz.getMethod("isAutoStartAllowed");
                    Object result = m.invoke(instance);
                    if (result instanceof Boolean) return (Boolean) result;
                } catch (NoSuchMethodException ignore2) {
                    // 方法不存在，默认 false
                }
            }
        } catch (ClassNotFoundException e) {
            // AppConfigManager 不存在，默认 false（即拦截被冻结应用的自启）
            Logger.d("未找到 AppConfigManager，autoStartAllowed 默认为 false");
        } catch (Throwable t) {
            Logger.d("isAutoStartAllowed 检查失败: " + t.getMessage());
        }
        return false;
    }

    /**
     * 通过 UID 获取对应的包名（取首个进程的 packageName）
     */
    private static String getPackageNameForUid(int uid) {
        try {
            List<AppInfo> processes = ProcessTracker.getInstance().getByUid(uid);
            for (AppInfo info : processes) {
                if (info.packageName != null) return info.packageName;
            }
        } catch (Throwable t) {
            Logger.d("getPackageNameForUid 失败 uid=" + uid + ": " + t.getMessage());
        }
        return null;
    }

    /**
     * 枚举类中所有指定名称的方法并逐一 hook（替代 hookAllMethods，stub 未提供该方法）
     */
    private static int hookAllMethodsByName(Class<?> clazz, String methodName, XC_MethodHook callback) {
        int count = 0;
        if (clazz == null) return 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
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
