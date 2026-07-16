package com.tombstonex.hook;

import com.tombstonex.manager.ConfigManager;
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
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Hook 锁屏/息屏事件，批量冻结后台应用
 */
public class ScreenStateHook {

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static final Object batchFreezeLock = new Object();
    private static ScheduledFuture<?> pendingBatchFreeze;
    private static volatile Object amsInstance;
    private static volatile boolean hasAmsHook = false;

    public static void init(ClassLoader classLoader) {
        hookScreenOff(classLoader);
        hookScreenOn(classLoader);
    }

    private static void hookScreenOff(ClassLoader classLoader) {
        boolean hooked = false;

        // 1. 尝试在 AMS 上 hook
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);

            String[] methodNames = {"screenOff", "goingToSleep"};
            for (String methodName : methodNames) {
                // 尝试带 int 参数和无参数两种签名
                Class<?>[][] paramVariants = {{int.class}, {}};
                for (Class<?>[] params : paramVariants) {
                    try {
                        Method method = XposedHelpers.findMethodExact(amsClass, methodName, params);
                        if (method == null) continue;

                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                amsInstance = param.thisObject;
                                scheduleBatchFreeze();
                            }
                        });
                        Logger.i("Hooked " + methodName + " on AMS");
                        hasAmsHook = true;
                        hooked = true;
                        break;
                    } catch (Throwable ignored) {}
                }
                if (hooked) break;
            }
        } catch (Throwable t) {
            Logger.w("Failed to hook screen off on AMS: " + t.getMessage());
        }

        // 2. 尝试在 PowerManagerService 上 hook（备选目标）
        if (!hooked) {
            try {
                Class<?> pmsClass = XposedHelpers.findClass(
                    "com.android.server.power.PowerManagerService", classLoader);

                String[] methodNames = {"goingToSleep", "screenOff"};
                for (String methodName : methodNames) {
                    Class<?>[][] paramVariants = {{int.class}, {}};
                    for (Class<?>[] params : paramVariants) {
                        try {
                            Method method = XposedHelpers.findMethodExact(pmsClass, methodName, params);
                            if (method == null) continue;

                            XposedBridge.hookMethod(method, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    scheduleBatchFreeze();
                                }
                            });
                            Logger.i("Hooked " + methodName + " on PMS");
                            hooked = true;
                            break;
                        } catch (Throwable ignored) {}
                    }
                    if (hooked) break;
                }
            } catch (Throwable t) {
                Logger.w("Failed to hook screen off on PMS: " + t.getMessage());
            }
        }

        if (!hooked) {
            Logger.w("Failed to hook screen off on any known class");
        }
    }

    private static void hookScreenOn(ClassLoader classLoader) {
        boolean hooked = false;

        // 1. 尝试在 AMS 上 hook
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);

            String[] methodNames = {"screenOn", "wakingUp"};
            for (String methodName : methodNames) {
                Class<?>[][] paramVariants = {{int.class}, {}};
                for (Class<?>[] params : paramVariants) {
                    try {
                        Method method = XposedHelpers.findMethodExact(amsClass, methodName, params);
                        if (method == null) continue;

                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                amsInstance = param.thisObject;
                                cancelBatchFreeze();
                                Logger.i("Screen on, cancelled pending batch freeze");
                            }
                        });
                        Logger.i("Hooked " + methodName + " on AMS");
                        hasAmsHook = true;
                        hooked = true;
                        break;
                    } catch (Throwable ignored) {}
                }
                if (hooked) break;
            }
        } catch (Throwable t) {
            Logger.w("Failed to hook screen on on AMS: " + t.getMessage());
        }

        // 2. 尝试在 PowerManagerService 上 hook（备选目标）
        if (!hooked) {
            try {
                Class<?> pmsClass = XposedHelpers.findClass(
                    "com.android.server.power.PowerManagerService", classLoader);

                String[] methodNames = {"wakingUp", "screenOn"};
                for (String methodName : methodNames) {
                    Class<?>[][] paramVariants = {{int.class}, {}};
                    for (Class<?>[] params : paramVariants) {
                        try {
                            Method method = XposedHelpers.findMethodExact(pmsClass, methodName, params);
                            if (method == null) continue;

                            XposedBridge.hookMethod(method, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    cancelBatchFreeze();
                                    Logger.i("Screen on (PMS), cancelled pending batch freeze");
                                }
                            });
                            Logger.i("Hooked " + methodName + " on PMS");
                            hooked = true;
                            break;
                        } catch (Throwable ignored) {}
                    }
                    if (hooked) break;
                }
            } catch (Throwable t) {
                Logger.w("Failed to hook screen on on PMS: " + t.getMessage());
            }
        }

        if (!hooked) {
            Logger.w("Failed to hook screen on on any known class");
        }
    }

    /**
     * 安排批量冻结任务（使用 ScheduledExecutorService 替代裸 Thread）
     */
    private static void scheduleBatchFreeze() {
        int delaySec = ConfigManager.getInstance().getScreenOffDelay();
        Logger.i("Screen off, batch freezing in " + delaySec + "s");

        synchronized (batchFreezeLock) {
            // 先取消之前的待执行任务（防竞态）
            if (pendingBatchFreeze != null) {
                pendingBatchFreeze.cancel(false);
            }
            pendingBatchFreeze = scheduler.schedule(() -> {
                try {
                    batchFreezeAll();
                } catch (Throwable t) {
                    Logger.e("Batch freeze error", t);
                }
            }, delaySec, TimeUnit.SECONDS);
        }
    }

    /**
     * 取消待执行的批量冻结任务（亮屏时调用）
     */
    private static void cancelBatchFreeze() {
        synchronized (batchFreezeLock) {
            if (pendingBatchFreeze != null) {
                pendingBatchFreeze.cancel(false);
                pendingBatchFreeze = null;
            }
        }
    }

    /**
     * 批量冻结所有后台非白名单应用
     * 增加保护性检查（前台服务、ContentProvider、音频播放）
     */
    private static void batchFreezeAll() {
        int frozen = 0;
        int skipped = 0;
        for (Map.Entry<Integer, AppInfo> entry :
                ProcessTracker.getInstance().getAllProcesses().entrySet()) {
            AppInfo info = entry.getValue();

            // 跳过 KILLED 和 FROZEN 状态
            if (info.state == AppState.KILLED || info.state == AppState.FROZEN) {
                skipped++;
                continue;
            }
            // 跳过前台进程
            if (info.state == AppState.FOREGROUND) {
                skipped++;
                continue;
            }
            // 跳过白名单
            if (info.isWhiteListed) {
                skipped++;
                continue;
            }
            if (!WhitelistManager.getInstance().shouldFreeze(
                    info.packageName, info.processName, info.isSystemApp)) {
                skipped++;
                continue;
            }

            // 保护性检查：前台服务、ContentProvider、音频播放
            // 尝试获取 ProcessRecord 对象进行检查
            Object processRecord = getProcessRecordByPid(info.pid);
            // 如果无法获取 ProcessRecord，跳过该进程的冻结（安全优先）
            if (processRecord == null && hasAmsHook) {
                Logger.d("Skip freezing " + info.packageName + ": cannot verify foreground status");
                skipped++;
                continue;
            }
            if (processRecord != null) {
                if (ActivitySwitchHook.hasForegroundService(processRecord)) {
                    Logger.d("Batch freeze: skip (foreground service) " + info.packageName);
                    skipped++;
                    continue;
                }
                if (ActivitySwitchHook.hasActiveContentProvider(processRecord)) {
                    Logger.d("Batch freeze: skip (ContentProvider) " + info.packageName);
                    skipped++;
                    continue;
                }
            }
            if (ActivitySwitchHook.isAudioPlaying(info.uid)) {
                Logger.d("Batch freeze: skip (audio playing) " + info.packageName);
                skipped++;
                continue;
            }

            boolean result = FreezeManager.getInstance().freezeProcess(info.pid, info.uid);
            if (result) frozen++;
            else skipped++;
        }
        Logger.i("Batch freeze complete: frozen=" + frozen + " skipped=" + skipped);
    }

    /**
     * 通过 pid 从 AMS 获取 ProcessRecord 对象
     */
    private static Object getProcessRecordByPid(int pid) {
        if (amsInstance == null) return null;
        try {
            // 尝试 mPidsSelfLocked.get(pid)
            Object pidsSelfLocked = XposedHelpers.getObjectField(amsInstance, "mPidsSelfLocked");
            if (pidsSelfLocked != null) {
                try {
                    return XposedHelpers.callMethod(pidsSelfLocked, "get", pid);
                } catch (Throwable ignored) {}
            }
            // 尝试 findProcessLocked(pid)
            try {
                return XposedHelpers.callMethod(amsInstance, "findProcessLocked", pid);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Logger.d("Failed to get ProcessRecord for pid=" + pid + ": " + t.getMessage());
        }
        return null;
    }
}
