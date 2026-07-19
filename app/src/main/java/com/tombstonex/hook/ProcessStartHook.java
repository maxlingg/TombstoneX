package com.tombstonex.hook;

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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 冻结新进程 Hook
 * 拦截后台应用启动新进程：
 * - Hook ActivityManagerService.startProcess（多签名兼容，3-4 种变体）
 * - Hook android.os.Process.start / Zygote.forkAndSpecialize
 * 当新进程启动时，若该应用已有其他进程被冻结且新进程不是主进程，延迟 3 秒冻结；
 * 若该应用此前无运行进程（从未被手动打开），直接冻结。
 * 注意不冻结 system_server、launcher、输入法等关键进程。
 */
public class ProcessStartHook {

    private static final int FREEZE_DELAY_SEC = 3;

    // P2: 2 个核心线程，处理新进程的延迟冻结
    private static final ScheduledThreadPoolExecutor freezeExecutor = new ScheduledThreadPoolExecutor(2);
    // 跟踪待冻结的新进程：pid -> 待执行任务
    private static final Map<Integer, ScheduledFuture<?>> pendingFreezes = new ConcurrentHashMap<>();
    static {
        // 已取消的任务立即从工作队列移除，避免驻留队列造成内存泄漏与重复触发
        freezeExecutor.setRemoveOnCancelPolicy(true);
    }

    public static void init(ClassLoader classLoader) {
        hookStartProcess(classLoader);
        hookProcessStart(classLoader);
        hookZygoteForkAndSpecialize(classLoader);
    }

    /**
     * Hook ActivityManagerService.startProcess — 多签名兼容（3-4 种变体）
     * startProcess 在不同 Android 版本参数个数不同，但 processName 始终是 arg[0]，
     * ApplicationInfo 始终是 arg[1]，从中可获取 packageName 与 uid。
     * 此处尚无 pid（进程尚未 fork），仅做检测与日志；真正的冻结在 Process.start 拿到 pid 后执行。
     */
    private static void hookStartProcess(ClassLoader classLoader) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);
            Class<?> appInfoClass = XposedHelpers.findClass(
                "android.app.ApplicationInfo", classLoader);
            Class<?> componentNameClass = XposedHelpers.findClass(
                "android.content.ComponentName", classLoader);

            // 多版本 startProcess 签名兼容
            Class<?>[][] paramTypeVariants = {
                // Android 12+ (含 startSeq): 14 参数
                {String.class, appInfoClass, boolean.class, int.class, String.class, componentNameClass,
                 boolean.class, boolean.class, boolean.class, String.class, String.class, String[].class,
                 Runnable.class, long.class},
                // Android 12-13 (无 startSeq): 13 参数
                {String.class, appInfoClass, boolean.class, int.class, String.class, componentNameClass,
                 boolean.class, boolean.class, boolean.class, String.class, String.class, String[].class,
                 Runnable.class},
                // Android 14+ 简化变体: (String, ApplicationInfo, boolean, boolean, String, long, String, String)
                {String.class, appInfoClass, boolean.class, boolean.class, String.class,
                 long.class, String.class, String.class},
                // 旧版本: 8 参数
                {String.class, appInfoClass, boolean.class, int.class, String.class, componentNameClass,
                 boolean.class, boolean.class},
            };

            boolean hooked = false;
            for (Class<?>[] paramTypes : paramTypeVariants) {
                try {
                    Method method = XposedHelpers.findMethodExact(
                        amsClass, "startProcess", paramTypes);
                    if (method == null) continue;

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                handleProcessStartFromArgs(param.args);
                            } catch (Throwable t) {
                                Logger.e("startProcess Hook 出错", t);
                            }
                        }
                    });
                    Logger.i("已 Hook startProcess (" + paramTypes.length + " 个参数)");
                    hooked = true;
                    break;
                } catch (Throwable e) {
                    Logger.d("Hook 变体失败: " + e.getMessage());
                }
            }

            // 兜底：若已知签名均未命中，则枚举 startProcess 的所有重载逐一 hook
            if (!hooked) {
                int n = hookAllMethodsByName(amsClass, "startProcess", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            handleProcessStartFromArgs(param.args);
                        } catch (Throwable t) {
                            Logger.e("startProcess (枚举) Hook 出错", t);
                        }
                    }
                });
                if (n > 0) {
                    Logger.i("通过方法枚举已 Hook startProcess (" + n + " 个重载)");
                    hooked = true;
                }
            }

            if (!hooked) {
                Logger.w("未找到已知签名的 startProcess");
            }
        } catch (Throwable t) {
            Logger.e("Hook startProcess 失败", t);
        }
    }

    /**
     * Hook android.os.Process.start — 静态方法，在 afterHookedMethod 中获取返回的 pid
     * niceName 在 arg[0]（String），uid 在 arg[1]（int，现代签名）；
     * 旧签名 arg[1] 可能为 String（entryPoint），此时通过类型检查跳过 uid 提取。
     */
    private static void hookProcessStart(ClassLoader classLoader) {
        try {
            Class<?> processClass = XposedHelpers.findClass("android.os.Process", classLoader);
            int n = hookAllMethodsByName(processClass, "start", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        handleProcessStartResult(param.args, param.getResult());
                    } catch (Throwable t) {
                        Logger.e("Process.start Hook 出错", t);
                    }
                }
            });
            if (n > 0) {
                Logger.i("已 Hook Process.start (" + n + " 个重载)");
            } else {
                Logger.w("未找到 Process.start");
            }
        } catch (Throwable t) {
            Logger.e("Hook Process.start 失败", t);
        }
    }

    /**
     * Hook com.android.internal.os.Zygote.forkAndSpecialize — 返回值为 pid（int）
     * 注意：此方法在 zygote 进程中执行，system_server 中 hook 可能不生效，
     * 这里仍注册 hook 以兼容 LSPosed 在 zygote 注入的场景。
     * 参数布局：uid(arg0), gid(arg1), gids(arg2), runtimeFlags(arg3), rlimits(arg4),
     *          mountExternal(arg5), seInfo(arg6), niceName(arg7, String), ...
     */
    private static void hookZygoteForkAndSpecialize(ClassLoader classLoader) {
        try {
            Class<?> zygoteClass = XposedHelpers.findClass(
                "com.android.internal.os.Zygote", classLoader);
            int n = hookAllMethodsByName(zygoteClass, "forkAndSpecialize", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        handleZygoteForkResult(param.args, param.getResult());
                    } catch (Throwable t) {
                        Logger.e("Zygote.forkAndSpecialize Hook 出错", t);
                    }
                }
            });
            if (n > 0) {
                Logger.i("已 Hook Zygote.forkAndSpecialize (" + n + " 个重载)");
            } else {
                Logger.w("未找到 Zygote.forkAndSpecialize");
            }
        } catch (Throwable t) {
            Logger.w("Hook Zygote.forkAndSpecialize 失败: " + t.getMessage());
        }
    }

    /**
     * 从 startProcess 参数中提取 processName / ApplicationInfo，进行冻结检测（仅日志）。
     */
    private static void handleProcessStartFromArgs(Object[] args) {
        if (args == null || args.length < 2) return;
        Object processNameObj = args[0];
        Object appInfoObj = args[1];
        if (!(processNameObj instanceof String) || appInfoObj == null) return;

        String processName = (String) processNameObj;
        String packageName = extractPackageName(processName);
        if (packageName == null) return;

        int uid = getIntField(appInfoObj, "uid");
        if (uid <= 0) return;

        boolean isSystemApp = uid < 10000;
        if (isCriticalProcess(packageName, processName, uid, isSystemApp)) return;

        // 检查该应用是否已有进程被冻结
        boolean hasFrozen = false;
        for (AppInfo info : ProcessTracker.getInstance().getAllByPackage(packageName)) {
            if (info.state == AppState.FROZEN) {
                hasFrozen = true;
                break;
            }
        }

        if (hasFrozen) {
            boolean isMainProcess = processName.equals(packageName);
            if (!isMainProcess) {
                Logger.i("发现已冻结应用启动新进程: " + packageName
                    + " proc=" + processName + " uid=" + uid);
            }
        }
    }

    /**
     * 处理 Process.start 的返回结果，获取 pid 后执行冻结决策
     */
    private static void handleProcessStartResult(Object[] args, Object result) {
        if (args == null || args.length < 2) return;
        if (!(args[0] instanceof String)) return;

        String processName = (String) args[0];
        int uid = -1;
        if (args[1] instanceof Integer) {
            uid = (int) args[1];
        }
        if (uid < 0) return;

        int pid = getPidFromResult(result);
        if (pid <= 0) return;

        performFreezeDecision(pid, uid, processName);
    }

    /**
     * 处理 Zygote.forkAndSpecialize 的返回结果
     */
    private static void handleZygoteForkResult(Object[] args, Object result) {
        if (args == null || args.length < 8) return;
        if (!(args[0] instanceof Integer) || !(args[7] instanceof String)) return;

        int uid = (int) args[0];
        String processName = (String) args[7];
        int pid = -1;
        if (result instanceof Integer) {
            pid = (int) result;
        }
        if (pid <= 0) return;

        performFreezeDecision(pid, uid, processName);
    }

    /**
     * 核心冻结决策逻辑
     */
    private static void performFreezeDecision(int pid, int uid, String processName) {
        if (pid <= 0 || processName == null) return;
        String packageName = extractPackageName(processName);
        if (packageName == null) return;

        boolean isSystemApp = uid < 10000;
        if (isCriticalProcess(packageName, processName, uid, isSystemApp)) return;

        // 注册前先检查该应用是否已有运行进程（用于判断“从未被手动打开”）
        List<AppInfo> existingBefore = ProcessTracker.getInstance().getAllByPackage(packageName);

        // 注册进程到 ProcessTracker，以便 FreezeManager 能管理它
        ProcessTracker.getInstance().registerProcess(packageName, processName, pid, uid, isSystemApp);

        // 检查该应用是否已有其他进程被冻结
        boolean hasFrozen = false;
        for (AppInfo info : existingBefore) {
            if (info.pid != pid && info.state == AppState.FROZEN) {
                hasFrozen = true;
                break;
            }
        }

        boolean isMainProcess = processName.equals(packageName);

        if (hasFrozen && !isMainProcess) {
            // 已冻结应用启动新进程（非主进程）— 延迟 3 秒冻结
            Logger.i("已冻结应用启动新进程，将在 " + FREEZE_DELAY_SEC + " 秒后冻结: "
                + packageName + " proc=" + processName + " pid=" + pid);
            scheduleFreeze(pid, uid, FREEZE_DELAY_SEC);
        } else if (existingBefore.isEmpty()) {
            // 该应用此前无运行进程（从未被手动打开）— 直接冻结
            Logger.i("应用从未被手动打开，直接冻结: " + packageName
                + " proc=" + processName + " pid=" + pid);
            scheduleFreeze(pid, uid, FREEZE_DELAY_SEC);
        }
    }

    /**
     * 安排延迟冻结任务（带去重与前台检查）
     */
    private static void scheduleFreeze(int pid, int uid, int delaySec) {
        pendingFreezes.compute(pid, (k, oldFuture) -> {
            if (oldFuture != null) oldFuture.cancel(false);
            return freezeExecutor.schedule(() -> {
                try {
                    AppInfo info = ProcessTracker.getInstance().getByPid(pid);
                    if (info == null) return;
                    // 前台进程不冻结，防止竞态条件下冻结前台应用
                    if (info.state == AppState.FOREGROUND) {
                        Logger.d("新进程已变为前台，取消冻结: pid=" + pid);
                        return;
                    }
                    if (info.state == AppState.FROZEN) return;
                    FreezeManager.getInstance().freezeProcess(pid, uid);
                } catch (Throwable t) {
                    Logger.e("新进程延迟冻结出错 pid=" + pid, t);
                }
            }, delaySec, TimeUnit.SECONDS);
        });
    }

    /**
     * 取消待冻结任务（供 ProcessDeathHook 调用）
     */
    public static void cancelPendingFreeze(int pid) {
        ScheduledFuture<?> future = pendingFreezes.remove(pid);
        if (future != null) {
            future.cancel(false);
            Logger.d("已取消新进程的待冻结任务 pid=" + pid);
        }
    }

    /**
     * 判断是否为关键进程（system_server / launcher / 输入法等），不可冻结
     * DEFAULT_SYSTEM_WHITE 已覆盖 launcher / IME / systemui / phone / 蓝牙 / NFC 等。
     */
    private static boolean isCriticalProcess(String packageName, String processName,
                                             int uid, boolean isSystemApp) {
        // system_server (uid=1000) 及 root 进程
        if (uid == 0 || uid == 1000) return true;
        // 通过白名单检查（系统应用默认不冻结）
        if (!WhitelistManager.getInstance().shouldFreeze(packageName, processName, isSystemApp)) {
            return true;
        }
        return false;
    }

    private static String extractPackageName(String processName) {
        if (processName == null) return null;
        int colonIdx = processName.indexOf(':');
        return colonIdx >= 0 ? processName.substring(0, colonIdx) : processName;
    }

    private static int getIntField(Object obj, String fieldName) {
        try {
            return XposedHelpers.getIntField(obj, fieldName);
        } catch (Throwable t) {
            Logger.d("getIntField 失败: " + fieldName + " - " + t.getMessage());
            return -1;
        }
    }

    private static int getPidFromResult(Object result) {
        if (result == null) return -1;
        // ProcessStartResult 含 public int pid 字段
        try {
            return XposedHelpers.getIntField(result, "pid");
        } catch (Throwable t) {
            Logger.d("getPidFromResult 失败: " + t.getMessage());
            return -1;
        }
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
