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
    // P2-N4: 批量冻结取消标志，亮屏时置为 true 中断正在执行的 batchFreezeAll
    private static volatile boolean batchFreezeCancelled = false;

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
                        Logger.i("已 Hook AMS." + methodName);
                        hasAmsHook = true;
                        hooked = true;
                        break;
                    } catch (Throwable e) {
                        Logger.d("Hook 变体失败: " + e.getMessage());
                    }
                }
                if (hooked) break;
            }
        } catch (Throwable t) {
            Logger.w("Hook AMS 屏幕关闭失败: " + t.getMessage());
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
                            Logger.i("已 Hook PMS." + methodName);
                            hooked = true;
                            break;
                        } catch (Throwable e) {
                            Logger.d("Hook 变体失败: " + e.getMessage());
                        }
                    }
                    if (hooked) break;
                }
            } catch (Throwable t) {
                Logger.w("Hook PMS 屏幕关闭失败: " + t.getMessage());
            }
        }

        if (!hooked) {
            Logger.w("未在任何已知类上 Hook 到屏幕关闭");
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
                                Logger.i("亮屏，已取消待执行的批量冻结");
                            }
                        });
                        Logger.i("已 Hook AMS." + methodName);
                        hasAmsHook = true;
                        hooked = true;
                        break;
                    } catch (Throwable e) {
                        Logger.d("Hook 变体失败: " + e.getMessage());
                    }
                }
                if (hooked) break;
            }
        } catch (Throwable t) {
            Logger.w("Hook AMS 屏幕点亮失败: " + t.getMessage());
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
                                    Logger.i("亮屏 (PMS)，已取消待执行的批量冻结");
                                }
                            });
                            Logger.i("已 Hook PMS." + methodName);
                            hooked = true;
                            break;
                        } catch (Throwable e) {
                            Logger.d("Hook 变体失败: " + e.getMessage());
                        }
                    }
                    if (hooked) break;
                }
            } catch (Throwable t) {
                Logger.w("Hook PMS 屏幕点亮失败: " + t.getMessage());
            }
        }

        if (!hooked) {
            Logger.w("未在任何已知类上 Hook 到屏幕点亮");
        }
    }

    /**
     * 安排批量冻结任务（使用 ScheduledExecutorService 替代裸 Thread）
     */
    private static void scheduleBatchFreeze() {
        int delaySec = ConfigManager.getInstance().getScreenOffDelay();
        Logger.i("息屏，" + delaySec + " 秒后批量冻结");

        synchronized (batchFreezeLock) {
            // 重置取消标志
            batchFreezeCancelled = false;
            // 先取消之前的待执行任务（防竞态）
            if (pendingBatchFreeze != null) {
                pendingBatchFreeze.cancel(false);
            }
            pendingBatchFreeze = scheduler.schedule(() -> {
                try {
                    batchFreezeAll();
                } catch (Throwable t) {
                    Logger.e("批量冻结出错", t);
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
            // P2-N4: 设置取消标志，中断正在执行的 batchFreezeAll
            batchFreezeCancelled = true;
        }
    }

    /**
     * 批量冻结所有后台非白名单应用
     * 增加保护性检查（前台服务、ContentProvider、音频播放）
     */
    private static void batchFreezeAll() {
        int frozen = 0;
        int skipped = 0;
        // 已知限制：isAnyAudioPlaying() 是全局检查（AudioManager.isMusicActive()），
        // 无法区分具体是哪个应用在播放音频。因此只要有任意应用播放音乐，
        // 所有后台应用都会被跳过冻结。这里放在循环外只调用一次以减少反射开销，
        // 不移除该检查以避免误冻结正在播放音频的应用。
        boolean audioPlaying = ActivitySwitchHook.isAnyAudioPlaying();
        for (Map.Entry<Integer, AppInfo> entry :
                ProcessTracker.getInstance().getAllProcesses().entrySet()) {
            // P2-N4: 亮屏时取消批量冻结，避免亮屏后仍继续冻结应用
            if (batchFreezeCancelled) {
                Logger.i("批量冻结已被亮屏取消，已处理=" + frozen + "+" + skipped);
                break;
            }
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
            // 注意：无论 hasAmsHook 是否为 true，只要拿不到 ProcessRecord 就跳过，
            // 避免在缺少 AMS 引用或反射失败时误冻结前台进程。
            if (processRecord == null) {
                Logger.d("跳过冻结 " + info.packageName + "：无法验证前台状态");
                skipped++;
                continue;
            }
            if (ActivitySwitchHook.hasForegroundService(processRecord)) {
                Logger.d("批量冻结：跳过（前台服务）" + info.packageName);
                skipped++;
                continue;
            }
            if (ActivitySwitchHook.hasActiveContentProvider(processRecord)) {
                Logger.d("批量冻结：跳过（ContentProvider）" + info.packageName);
                skipped++;
                continue;
            }
            if (audioPlaying) {
                Logger.d("批量冻结：跳过（音频播放中）" + info.packageName);
                skipped++;
                continue;
            }

            boolean result = FreezeManager.getInstance().freezeProcess(info.pid, info.uid);
            if (result) frozen++;
            else skipped++;
        }
        Logger.i("批量冻结完成：已冻结=" + frozen + " 已跳过=" + skipped);
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
                } catch (Throwable e) {
                    Logger.d("Hook 变体失败: " + e.getMessage());
                }
            }
            // 尝试 findProcessLocked(pid)
            try {
                return XposedHelpers.callMethod(amsInstance, "findProcessLocked", pid);
            } catch (Throwable e) {
                Logger.d("Hook 变体失败: " + e.getMessage());
            }
        } catch (Throwable t) {
            Logger.d("获取 pid=" + pid + " 的 ProcessRecord 失败: " + t.getMessage());
        }
        return null;
    }
}
