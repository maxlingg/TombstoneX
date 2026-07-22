package com.tombstonex.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hook 锁屏/息屏事件，批量冻结后台应用
 */
public class ScreenStateHook {

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "TombstoneX-Screen");
        t.setDaemon(true);
        return t;
    });
    private static final Object batchFreezeLock = new Object();
    private static ScheduledFuture<?> pendingBatchFreeze;
    private static volatile Object amsInstance;
    private static volatile boolean hasAmsHook = false;
    // S-1: 保存 init() 传入的 classLoader，供 resolveAmsInstance 使用正确的 ClassLoader
    // 加载 AMS 类，避免 Class.forName 不指定 classLoader 时使用调用方 ClassLoader
    // 在某些定制 ROM 上可能找不到 system_server 内部类的问题。
    private static ClassLoader systemClassLoader = null;
    // S-1: 在已有 AMS hook 回调中缓存的 AMS 实例，作为 resolveAmsInstance 的回退来源。
    // 当 self() 反射失败但此前 AMS 方法已被 Hook 且回调已触发时，此字段保存 param.thisObject。
    private static volatile Object cachedAmsFromHook = null;
    // M-2: 预检 hasForegroundServices 方法是否存在于 ProcessRecord 类上。
    // 若方法不存在，hasForegroundService 可能恒返回 true（fail-safe），导致批量冻结静默全跳过。
    private static volatile boolean hasForegroundServicesMethodChecked = false;
    private static volatile boolean hasForegroundServicesMethodExists = false;
    // R10-m-4: 缓存 mPidsSelfLocked 对象引用，避免每次 getProcessRecordByPid 都反射获取字段。
    // amsInstance 为 AMS 单例，mPidsSelfLocked 字段在 AMS 生命周期内稳定，可安全缓存。
    private static volatile Object cachedPidsSelfLocked = null;
    // R8-M8-1: 代际计数器替代旧的布尔标志 batchFreezeCancelled。
    // 每次安排新一轮批量冻结或取消时递增，正在执行的 batchFreezeAll 通过比对自身代际
    // 与当前代际判断是否已被新一轮/亮屏作废，避免布尔标志无法区分不同批次的问题。
    private static final AtomicLong freezeGeneration = new AtomicLong(0);
    // R8-S6: BroadcastReceiver 兜底是否已注册（防止 init 中 off/on 两次重复注册）
    private static volatile boolean receiverFallbackRegistered = false;
    // R9-m9-1: 保存 Receiver 引用以便后续注销（完善资源清理）
    private static volatile BroadcastReceiver screenReceiver = null;
    // R9-M9-2: 息屏/亮屏方向是否已通过 PMS/AMS Hook 捕获（独立标志）。
    // 旧代码使用单一局部变量 hooked，无法在 BroadcastReceiver 回调中判断对应方向是否已 Hook，
    // 导致非对称降级（息屏 Hook 成功但亮屏失败）时 Receiver 同时监听两方向，息屏事件触发两次 scheduleBatchFreeze。
    private static volatile boolean screenOffHooked = false;
    private static volatile boolean screenOnHooked = false;

    public static void init(ClassLoader classLoader) {
        // S-1: 保存 classLoader 供 resolveAmsInstance 使用
        systemClassLoader = classLoader;
        // M-2: 预检 ProcessRecord.hasForegroundServices() 方法是否存在。
        // 若方法不存在，ActivitySwitchHook.hasForegroundService 可能 fail-safe 返回 true，
        // 导致批量冻结中所有进程被误判为有前台服务而静默跳过。
        precheckForegroundServicesMethod(classLoader);
        hookScreenOff(classLoader);
        hookScreenOn(classLoader);
    }

    /**
     * M-2: 预检 ProcessRecord 类上 hasForegroundServices 方法是否存在。
     * 若方法不存在，记录 Logger.e 告警，后续 batchFreezeAll 中会据此检测异常跳过模式。
     */
    private static void precheckForegroundServicesMethod(ClassLoader classLoader) {
        hasForegroundServicesMethodChecked = true;
        try {
            Class<?> processRecordClass = XposedHelpers.findClass(
                "com.android.server.am.ProcessRecord", classLoader);
            try {
                processRecordClass.getDeclaredMethod("hasForegroundServices");
                hasForegroundServicesMethodExists = true;
                Logger.i("预检：ProcessRecord.hasForegroundServices 方法存在");
            } catch (NoSuchMethodException e) {
                hasForegroundServicesMethodExists = false;
                Logger.e("预检：ProcessRecord.hasForegroundServices 方法不存在，" +
                    "hasForegroundService 可能 fail-safe 返回 true 导致批量冻结静默全跳过", null);
            }
        } catch (Throwable t) {
            hasForegroundServicesMethodExists = false;
            Logger.w("预检 hasForegroundServices 失败（无法加载 ProcessRecord 类）: " + t.getMessage());
        }
    }

    private static void hookScreenOff(ClassLoader classLoader) {
        boolean hooked = false;

        // R8-S6: 优先 Hook AOSP 标准 PMS.goToSleep（原生 AOSP 息屏实际入口）。
        // 旧代码仅 Hook screenOff/goingToSleep（参数 int.class/{}），这些方法名/签名
        // 在原生 AOSP 上不存在，导致息屏冻结功能完全失效。
        // AOSP 实际方法：PowerManagerService.goToSleep(long eventTime, int reason, int flags)
        try {
            Class<?> pmsClass = XposedHelpers.findClass(
                "com.android.server.power.PowerManagerService", classLoader);
            Class<?>[][] paramVariants = {
                {long.class, int.class, int.class},
                {long.class, int.class, int.class, int.class},
            };
            for (Class<?>[] params : paramVariants) {
                try {
                    Method method;
                    try {
                        method = XposedHelpers.findMethodExact(pmsClass, "goToSleep", params);
                    } catch (NoSuchMethodError e) {
                        Logger.d("goToSleep 方法未找到，params=" + params.length + ": " + e.getMessage());
                        continue;
                    }

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            scheduleBatchFreeze();
                        }
                    });
                    Logger.i("已 Hook PMS.goToSleep (AOSP 标准息屏入口)");
                    hooked = true;
                    break;
                } catch (Throwable e) {
                    Logger.d("goToSleep 变体失败: " + e.getMessage());
                }
            }
        } catch (Throwable t) {
            Logger.w("Hook PMS goToSleep 失败: " + t.getMessage());
        }

        // R8-S6: 保留原有 screenOff/goingToSleep 作为 OEM ROM 兼容（fallback）
        if (!hooked) {
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
                            Method method;
                            try {
                                method = XposedHelpers.findMethodExact(amsClass, methodName, params);
                            } catch (NoSuchMethodError e) {
                                Logger.d("AMS." + methodName + " 方法未找到: " + e.getMessage());
                                continue;
                            }

                            XposedBridge.hookMethod(method, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    amsInstance = param.thisObject;
                                    // S-1: 缓存 AMS 实例作为 resolveAmsInstance 的回退来源
                                    cachedAmsFromHook = param.thisObject;
                                    scheduleBatchFreeze();
                                }
                            });
                            Logger.i("已 Hook AMS." + methodName + " (OEM ROM 兼容)");
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
                                Method method;
                                try {
                                    method = XposedHelpers.findMethodExact(pmsClass, methodName, params);
                                } catch (NoSuchMethodError e) {
                                    Logger.d("PMS " + methodName + " 方法未找到: " + e.getMessage());
                                    continue;
                                }

                                XposedBridge.hookMethod(method, new XC_MethodHook() {
                                    @Override
                                    protected void afterHookedMethod(MethodHookParam param) {
                                        scheduleBatchFreeze();
                                    }
                                });
                                Logger.i("已 Hook PMS." + methodName + " (OEM ROM 兼容)");
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
        }

        // R9-M9-2: 记录息屏方向是否已 Hook，供 BroadcastReceiver 回调判断是否跳过，
        // 避免非对称降级（息屏 Hook 成功但亮屏失败）时 Receiver 重复触发 scheduleBatchFreeze。
        screenOffHooked = hooked;
        // R8-S6: 如果都没 Hook 到，使用 BroadcastReceiver 兜底监听 ACTION_SCREEN_OFF
        if (!hooked) {
            Logger.w("PMS/AMS 息屏 Hook 均失败，尝试 BroadcastReceiver 兜底");
            registerScreenReceiverFallback();
        }
    }

    private static void hookScreenOn(ClassLoader classLoader) {
        boolean hooked = false;

        // R8-S6: 优先 Hook AOSP 标准 PMS.wakeUp（原生 AOSP 亮屏实际入口）。
        // AOSP 实际方法：PowerManagerService.wakeUp(long eventTime, int reason, String details)
        try {
            Class<?> pmsClass = XposedHelpers.findClass(
                "com.android.server.power.PowerManagerService", classLoader);
            Class<?>[][] paramVariants = {
                {long.class, int.class, String.class},
                {long.class, int.class},
            };
            for (Class<?>[] params : paramVariants) {
                try {
                    Method method;
                    try {
                        method = XposedHelpers.findMethodExact(pmsClass, "wakeUp", params);
                    } catch (NoSuchMethodError e) {
                        Logger.d("wakeUp 方法未找到，params=" + params.length + ": " + e.getMessage());
                        continue;
                    }

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            cancelBatchFreeze();
                            Logger.i("亮屏 (PMS.wakeUp AOSP 标准)，已取消待执行的批量冻结");
                        }
                    });
                    Logger.i("已 Hook PMS.wakeUp (AOSP 标准亮屏入口)");
                    hooked = true;
                    break;
                } catch (Throwable e) {
                    Logger.d("wakeUp 变体失败: " + e.getMessage());
                }
            }
        } catch (Throwable t) {
            Logger.w("Hook PMS wakeUp 失败: " + t.getMessage());
        }

        // R8-S6: 保留原有 screenOn/wakingUp 作为 OEM ROM 兼容（fallback）
        if (!hooked) {
            // 1. 尝试在 AMS 上 hook
            try {
                Class<?> amsClass = XposedHelpers.findClass(
                    "com.android.server.am.ActivityManagerService", classLoader);

                String[] methodNames = {"screenOn", "wakingUp"};
                for (String methodName : methodNames) {
                    Class<?>[][] paramVariants = {{int.class}, {}};
                    for (Class<?>[] params : paramVariants) {
                        try {
                            Method method;
                            try {
                                method = XposedHelpers.findMethodExact(amsClass, methodName, params);
                            } catch (NoSuchMethodError e) {
                                Logger.d("AMS " + methodName + " 方法未找到: " + e.getMessage());
                                continue;
                            }

                            XposedBridge.hookMethod(method, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    amsInstance = param.thisObject;
                                    // S-1: 缓存 AMS 实例作为 resolveAmsInstance 的回退来源
                                    cachedAmsFromHook = param.thisObject;
                                    cancelBatchFreeze();
                                    Logger.i("亮屏 (AMS)，已取消待执行的批量冻结");
                                }
                            });
                            Logger.i("已 Hook AMS." + methodName + " (OEM ROM 兼容)");
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
                                Method method;
                                try {
                                    method = XposedHelpers.findMethodExact(pmsClass, methodName, params);
                                } catch (NoSuchMethodError e) {
                                    Logger.d("PMS " + methodName + " 方法未找到: " + e.getMessage());
                                    continue;
                                }

                                XposedBridge.hookMethod(method, new XC_MethodHook() {
                                    @Override
                                    protected void afterHookedMethod(MethodHookParam param) {
                                        cancelBatchFreeze();
                                        Logger.i("亮屏 (PMS)，已取消待执行的批量冻结");
                                    }
                                });
                                Logger.i("已 Hook PMS." + methodName + " (OEM ROM 兼容)");
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
        }

        // R9-M9-2: 记录亮屏方向是否已 Hook，供 BroadcastReceiver 回调判断是否跳过。
        screenOnHooked = hooked;
        // R8-S6: 如果都没 Hook 到，依赖 BroadcastReceiver 兜底监听 ACTION_SCREEN_ON
        if (!hooked) {
            Logger.w("PMS/AMS 亮屏 Hook 均失败，尝试 BroadcastReceiver 兜底");
            registerScreenReceiverFallback();
        }
    }

    /**
     * 安排批量冻结任务（使用 ScheduledExecutorService 替代裸 Thread）
     */
    private static void scheduleBatchFreeze() {
        int delaySec = ConfigManager.getInstance().getScreenOffDelay();
        Logger.i("息屏，" + delaySec + " 秒后批量冻结");

        synchronized (batchFreezeLock) {
            // R8-M8-1: 递增代际计数器，使上一轮正在执行的 batchFreezeAll 失效
            long gen = freezeGeneration.incrementAndGet();
            // 先取消之前的待执行任务（防竞态）
            if (pendingBatchFreeze != null) {
                pendingBatchFreeze.cancel(false);
            }
            final long myGeneration = gen;
            pendingBatchFreeze = scheduler.schedule(() -> {
                try {
                    batchFreezeAll(myGeneration);
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
            // R8-M8-1: 递增代际计数器，使正在执行的 batchFreezeAll 检测到代际不匹配后中断
            freezeGeneration.incrementAndGet();
        }
    }

    /**
     * 批量冻结所有后台非白名单应用
     * 增加保护性检查（前台服务、ContentProvider、音频播放）
     *
     * R8-M8-1: 接收 myGeneration 代际参数，循环中比对 freezeGeneration.get() 判断本批次
     * 是否已被新一轮息屏或亮屏作废，作废则立即中断，避免布尔标志无法区分批次的问题。
     */
    private static void batchFreezeAll(long myGeneration) {
        int frozen = 0;
        int skipped = 0;
        // M-2: 连续 hasForegroundService=true 计数器。
        // 如果 hasForegroundServices 方法不存在（预检未通过），hasForegroundService 可能
        // fail-safe 恒返回 true，导致批量冻结静默跳过所有进程。当连续 N 个进程都返回 true 时，
        // 记录 Logger.e 告警提示可能存在方法缺失问题。
        final int FROZEN_SERVICE_CONSECUTIVE_THRESHOLD = 5;
        int consecutiveForegroundServiceSkips = 0;
        // M-7: 在批量冻结开始时预缓存全局状态（通话/音乐/音频通信模式），
        // 避免逐进程重复执行全局 Binder IPC 查询。SmartStateHook.isAppActive 中的
        // 全局检测将命中此缓存，减少批量冻结期间的总 Binder IPC 次数。
        try {
            com.tombstonex.hook.SmartStateHook.precacheGlobalState();
        } catch (Throwable ignored) {
            Logger.d("precacheGlobalState 失败: " + ignored.getMessage());
        }
        // R11-m-1: 移除 isAnyAudioPlaying() 全局调用及相关日志。
        // R11-S-1 已在循环内逐进程添加 SmartStateHook.isAppActive 检查，该检查包含
        // per-uid 音频检测（isMusicActive / hasAudioFocus 等），无需在循环外再做全局检查，
        // 消除不必要的反射开销。
        // R10-m-2: getAllProcesses() 返回 ConcurrentHashMap 副本，弱一致性遍历在批量冻结场景下可接受。
        // 批量冻结在息屏后延迟执行，期间进程列表的短暂不一致不影响整体安全性——每个进程仍会经过
        // freezeProcess 内部检查。改 ProcessTracker 需修改 Manager 层，当前保持现状不改代码。
        for (Map.Entry<Integer, AppInfo> entry :
                ProcessTracker.getInstance().getAllProcesses().entrySet()) {
            // R8-M8-1: 代际不匹配说明已被新一轮息屏/亮屏作废，立即中断
            if (freezeGeneration.get() != myGeneration) {
                Logger.i("批量冻结已被新一轮息屏/亮屏作废（代际不匹配），已处理=" + frozen + "+" + skipped);
                break;
            }
            AppInfo info = entry.getValue();
            if (info == null) {
                skipped++;
                continue;
            }
            // R10-M-4: 为每个进程的处理逻辑添加 try-catch，避免单个进程异常导致整个批量冻结中断
            try {
                // 跳过 KILLED 和 FROZEN 状态
                if (info.getState() == AppState.KILLED || info.getState() == AppState.FROZEN) {
                    skipped++;
                    continue;
                }
                // 跳过前台进程
                if (info.getState() == AppState.FOREGROUND) {
                    skipped++;
                    continue;
                }
                // M3: 移除 info.isWhiteListed() 短路检查，统一走 WhitelistManager.shouldFreeze()。
                // info.isWhiteListed() 在注册时设置一次且不再刷新，可能因白名单变更而过时；
                // shouldFreeze() 直接查询内存中的 HashSet，始终是权威结果。
                if (!WhitelistManager.getInstance().shouldFreeze(
                        info.packageName, info.processName, info.isSystemApp())) {
                    skipped++;
                    continue;
                }

                // 保护性检查：前台服务、ContentProvider
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
                    // M-2: 连续 hasForegroundService=true 计数，检测可能的 fail-safe 全跳过
                    consecutiveForegroundServiceSkips++;
                    if (consecutiveForegroundServiceSkips >= FROZEN_SERVICE_CONSECUTIVE_THRESHOLD
                        && !hasForegroundServicesMethodExists) {
                        Logger.e("批量冻结：连续 " + consecutiveForegroundServiceSkips
                            + " 个进程被 hasForegroundService 跳过，且预检表明 hasForegroundServices "
                            + "方法不存在，可能 fail-safe 导致批量冻结静默全跳过", null);
                    }
                    Logger.d("批量冻结：跳过（前台服务）" + info.packageName);
                    skipped++;
                    continue;
                }
                // M-2: 重置连续计数器——此进程未被前台服务跳过，说明检测正常工作
                consecutiveForegroundServiceSkips = 0;
                if (ActivitySwitchHook.hasActiveContentProvider(processRecord)) {
                    Logger.d("批量冻结：跳过（ContentProvider）" + info.packageName);
                    skipped++;
                    continue;
                }
                // R11-S-1: 恢复批量冻结中的活跃状态检查
                try {
                    if (com.tombstonex.hook.SmartStateHook.isAppActive(info.uid, info.packageName)) {
                        Logger.d("批量冻结：跳过活跃状态 " + info.packageName);
                        skipped++;
                        continue;
                    }
                } catch (Throwable ignored) {
                        Logger.d("SmartStateHook.isAppActive 检查失败: " + ignored.getMessage());
                    }

                boolean result = FreezeManager.getInstance().freezeProcess(info.pid, info.uid);
                if (result) frozen++;
                else skipped++;
            } catch (Throwable t) {
                // R10-M-4: 单个进程冻结出错不影响其他进程
                Logger.e("冻结单个进程出错 pid=" + info.pid, t);
                skipped++;
            }
        }
        Logger.i("批量冻结完成：已冻结=" + frozen + " 已跳过=" + skipped);
    }

    /**
     * 通过 pid 从 AMS 获取 ProcessRecord 对象
     *
     * R11-m-2: mPidsSelfLocked.get(pid) 调用无 AMS 锁保护。理论上应在持有
     * ActivityManagerService.mLock 等 AMS 内部锁的情况下访问 mPidsSelfLocked，
     * 否则可能因并发修改导致返回过时数据或 ConcurrentModificationException。
     * 此为已知限制：system_server 中难以安全获取并持有 AMS 内部锁，
     * 当前 try-catch + null 检查的防御性设计在实践中可接受（批量冻结为 best-effort）。
     */
    private static Object getProcessRecordByPid(int pid) {
        // R8-S6: 当息屏事件经 PMS.goToSleep 捕获（而非 AMS.screenOff）时，amsInstance
        // 不会被 Hook 回调设置，需在此独立解析，供 batchFreezeAll 获取 ProcessRecord。
        if (amsInstance == null) {
            amsInstance = resolveAmsInstance();
        }
        if (amsInstance == null) return null;
        try {
            // R10-m-4: 优先使用缓存的 mPidsSelfLocked 引用，避免每次反射获取字段开销。
            // amsInstance 为 AMS 单例，mPidsSelfLocked 字段引用在其生命周期内稳定。
            Object pidsSelfLocked = cachedPidsSelfLocked;
            if (pidsSelfLocked == null) {
                pidsSelfLocked = XposedHelpers.getObjectField(amsInstance, "mPidsSelfLocked");
                if (pidsSelfLocked != null) {
                    cachedPidsSelfLocked = pidsSelfLocked;
                }
            }
            // 尝试 mPidsSelfLocked.get(pid)
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

    /**
     * R8-S6: 通过反射获取 ActivityManagerService 实例。
     * 当息屏事件经 PMS.goToSleep 捕获（而非 AMS.screenOff）时，amsInstance 不会被
     * Hook 回调设置，需在此独立解析，供 batchFreezeAll 获取 ProcessRecord。
     * AOSP 中 ActivityManagerService.self() 返回单例实例。
     *
     * S-1: 使用 init() 传入的 systemClassLoader 加载 AMS 类，避免 Class.forName
     * 不指定 classLoader 时使用调用方 ClassLoader 在定制 ROM 上找不到类的问题。
     * 增加 cachedAmsFromHook 回退：当 self() 反射失败时，尝试使用此前 AMS hook
     * 回调中缓存的 param.thisObject 作为 AMS 实例。
     */
    private static Object resolveAmsInstance() {
        // S-1: 优先使用 Hook 回调中缓存的 AMS 实例（来自 hookScreenOff/hookScreenOn 回调）
        Object cached = cachedAmsFromHook;
        if (cached != null) {
            return cached;
        }
        try {
            // S-1: 使用 systemClassLoader 加载 AMS 类，而非默认的调用方 ClassLoader
            ClassLoader cl = systemClassLoader != null ? systemClassLoader : ClassLoader.getSystemClassLoader();
            Class<?> amsClass = Class.forName("com.android.server.am.ActivityManagerService", false, cl);
            try {
                Method self = amsClass.getMethod("self");
                Object obj = self.invoke(null);
                if (obj != null) {
                    // R9-m9-6: 此处仅通过 self() 解析 AMS 实例，并未 Hook 任何 AMS 方法，
                    // 故不应设置 hasAmsHook=true。hasAmsHook 仅在 hookScreenOff/hookScreenOn
                    // 成功 Hook AMS 方法时设置，表示"通过 Hook 回调获取了 amsInstance"。
                    return obj;
                }
            } catch (Throwable ignored) {
                Logger.d("AMS.self() 失败: " + ignored.getMessage());
            }
            // S-1: self() 失败后的回退——尝试使用 Hook 回调中缓存的 AMS 实例
            // （cachedAmsFromHook 在上面的检查中可能为 null，但此分支作为防御性保留）
        } catch (Throwable t) {
            Logger.d("resolveAmsInstance 失败: " + t.getMessage());
        }
        // S-1: 最终回退——再次检查 Hook 回调缓存（可能在上述 try 块执行期间被设置）
        return cachedAmsFromHook;
    }

    /**
     * R8-S6: BroadcastReceiver 兜底——当 PMS/AMS 方法 Hook 均失败时，通过注册
     * ACTION_SCREEN_OFF / ACTION_SCREEN_ON 广播监听息屏/亮屏事件。
     * ACTION_SCREEN_OFF/ON 为保护广播，仅能通过 Context.registerReceiver 动态注册
     * （system_server 上下文可注册）。该方法同时监听息屏与亮屏，故用
     * receiverFallbackRegistered 标志防止 hookScreenOff/hookScreenOn 重复注册。
     *
     * R9-M9-2: Receiver 同时监听息屏与亮屏两个方向，但可能只有一个方向 Hook 失败
     * （非对称降级）。回调中通过 screenOffHooked/screenOnHooked 独立标志判断对应方向
     * 是否已通过 PMS/AMS Hook 捕获，若已 Hook 则跳过 Receiver 处理，避免重复触发。
     *
     * R9-m9-1: Receiver 引用保存为 screenReceiver 静态字段，提供 unregisterFallback()
     * 完善资源清理。当前运行于 system_server 中无实际注销时机，但完善接口以备未来需求。
     */
    private static void registerScreenReceiverFallback() {
        if (receiverFallbackRegistered) return;
        synchronized (batchFreezeLock) {
            if (receiverFallbackRegistered) return;
            try {
                Class<?> appGlobals = Class.forName("android.app.AppGlobals");
                Object context = appGlobals.getMethod("getInitialApplication").invoke(null);
                if (context == null || !(context instanceof Context)) {
                    Logger.w("BroadcastReceiver 兜底失败：无法获取系统 Context");
                    return;
                }
                Context ctx = (Context) context;
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                        String action = intent != null ? intent.getAction() : null;
                        if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                            // R9-M9-2: 若息屏方向已通过 PMS/AMS Hook 捕获，跳过 Receiver 处理，
                            // 避免非对称降级时 scheduleBatchFreeze 被重复触发。
                            if (!screenOffHooked) {
                                scheduleBatchFreeze();
                            }
                        } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                            // R9-M9-2: 若亮屏方向已通过 PMS/AMS Hook 捕获，跳过 Receiver 处理。
                            if (!screenOnHooked) {
                                cancelBatchFreeze();
                                Logger.i("亮屏 (BroadcastReceiver)，已取消待执行的批量冻结");
                            }
                        }
                    }
                };
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                filter.addAction(Intent.ACTION_SCREEN_ON);
                ctx.registerReceiver(receiver, filter);
                screenReceiver = receiver; // R9-m9-1: 保存引用以便后续注销
                receiverFallbackRegistered = true;
                Logger.i("已注册 BroadcastReceiver 兜底监听息屏/亮屏事件");
            } catch (Throwable t) {
                Logger.w("注册 BroadcastReceiver 兜底失败: " + t.getMessage());
            }
        }
    }

    /**
     * R9-m9-1: 注销 BroadcastReceiver 兜底监听，完善资源清理。
     * 当前运行于 system_server，进程生命周期与系统一致，实际无注销时机，
     * 但提供此接口以备未来需求（如动态启停监控）。
     */
    public static void unregisterFallback() {
        if (!receiverFallbackRegistered) return;
        synchronized (batchFreezeLock) {
            if (!receiverFallbackRegistered) return;
            if (screenReceiver != null) {
                try {
                    Class<?> appGlobals = Class.forName("android.app.AppGlobals");
                    Object context = appGlobals.getMethod("getInitialApplication").invoke(null);
                    if (context instanceof Context) {
                        ((Context) context).unregisterReceiver(screenReceiver);
                        Logger.i("已注销 BroadcastReceiver 兜底监听");
                    }
                } catch (Throwable t) {
                    Logger.w("注销 BroadcastReceiver 兜底失败: " + t.getMessage());
                }
                screenReceiver = null;
            }
            receiverFallbackRegistered = false;
        }
    }
}
