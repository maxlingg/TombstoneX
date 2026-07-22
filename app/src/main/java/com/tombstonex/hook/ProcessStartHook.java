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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    // R8-S4: 保留作为默认值/fallback 参考，实际冻结延迟通过 ConfigManager.getFreezeDelay() 动态读取
    private static final int FREEZE_DELAY_SEC = 3;

    // P2: 2 个核心线程，处理新进程的延迟冻结
    private static final ScheduledThreadPoolExecutor freezeExecutor = new ScheduledThreadPoolExecutor(2, r -> {
        Thread t = new Thread(r, "TombstoneX-ProcStart");
        t.setDaemon(true);
        return t;
    });
    // 跟踪待冻结的新进程：pid -> 待执行任务
    private static final Map<Integer, ScheduledFuture<?>> pendingFreezes = new ConcurrentHashMap<>();
    // 定期清理任务的幂等标志，避免并发 init() 重复调度
    private static final AtomicBoolean cleanupTaskStarted = new AtomicBoolean(false);
    static {
        // 已取消的任务立即从工作队列移除，避免驻留队列造成内存泄漏与重复触发
        freezeExecutor.setRemoveOnCancelPolicy(true);
    }

    public static void init(ClassLoader classLoader) {
        // 定期清理 pendingFreezes 中已不存在于 ProcessTracker 的残留条目（兜底，每小时一次）。
        // 正常情况下 cancelPendingFreeze 会清理，但若进程未走死亡回调，条目会永久残留。
        if (cleanupTaskStarted.compareAndSet(false, true)) {
            freezeExecutor.scheduleAtFixedRate(() -> {
                try {
                    for (Integer pid : pendingFreezes.keySet()) {
                        if (ProcessTracker.getInstance().getByPid(pid) == null) {
                            ScheduledFuture<?> f = pendingFreezes.remove(pid);
                            if (f != null) f.cancel(false);
                        }
                    }
                } catch (Throwable t) {
                    Logger.e("pendingFreezes 定期清理出错", t);
                }
            }, 1, 1, TimeUnit.HOURS);
        }
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

            // S-2: 查找 IApplicationThread 类（AOSP 实际类型，非 Runnable）
            Class<?> iApplicationThreadClass = null;
            try {
                iApplicationThreadClass = XposedHelpers.findClass(
                    "android.app.IApplicationThread", classLoader);
            } catch (Throwable t) {
                Logger.d("未找到 IApplicationThread 类: " + t.getMessage());
            }

            // 多版本 startProcess 签名兼容
            // S-2: 将原本的 Runnable.class 替换为 IApplicationThread（Binder 接口）
            // 若找不到 IApplicationThread 则跳过含该参数的变体，而非使用错误的 Runnable
            Class<?>[][] paramTypeVariants;
            if (iApplicationThreadClass != null) {
                paramTypeVariants = new Class<?>[][] {
                    // Android 12+ (含 startSeq): 14 参数
                    {String.class, appInfoClass, boolean.class, int.class, String.class, componentNameClass,
                     boolean.class, boolean.class, boolean.class, String.class, String.class, String[].class,
                     iApplicationThreadClass, long.class},
                    // Android 12-13 (无 startSeq): 13 参数
                    {String.class, appInfoClass, boolean.class, int.class, String.class, componentNameClass,
                     boolean.class, boolean.class, boolean.class, String.class, String.class, String[].class,
                     iApplicationThreadClass},
                    // Android 14+ 简化变体: (String, ApplicationInfo, boolean, boolean, String, long, String, String)
                    {String.class, appInfoClass, boolean.class, boolean.class, String.class,
                     long.class, String.class, String.class},
                    // 旧版本: 8 参数
                    {String.class, appInfoClass, boolean.class, int.class, String.class, componentNameClass,
                     boolean.class, boolean.class},
                };
            } else {
                paramTypeVariants = new Class<?>[][] {
                    // Android 14+ 简化变体
                    {String.class, appInfoClass, boolean.class, boolean.class, String.class,
                     long.class, String.class, String.class},
                    // 旧版本: 8 参数
                    {String.class, appInfoClass, boolean.class, int.class, String.class, componentNameClass,
                     boolean.class, boolean.class},
                };
            }

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
     * AOSP 签名: start(String processClass, String niceName, int uid, int gid, ...)
     *   args[0] = processClass (如 "android.app.ActivityThread")
     *   args[1] = niceName (如 "com.example.app" — 进程名/包名)
     *   args[2] = uid
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
        // R8-m3: 此方法仅做日志输出，非 debug 模式下直接返回，减少 AMS 线程开销
        if (!Logger.isDebug()) return;
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
            if (info.getState() == AppState.FROZEN) {
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
     *
     * AOSP Process.start 签名变体：
     *   1. 标准（双 String）: start(String processClass, String niceName, int uid, int gid, ...)
     *        args[0] = processClass (如 "android.app.ActivityThread")
     *        args[1] = niceName (如 "com.example.app" — 这才是进程名/包名)
     *        args[2] = uid
     *   2. 单 String 重载: start(String niceName, int uid, int gid, ...)
     *        args[0] = niceName (进程名/包名)
     *        args[1] = uid
     *
     * S1 修复：旧代码只在 stringCount == 2 时设置 processName，单 String 重载下
     * stringCount 只到 1，processName 保持 null，进程注册被跳过。
     * 改为优先取第一个 String 作为 processName，仅当存在第二个 String 时才覆盖。
     *
     * R8-M2 修复：旧代码对所有签名一律用启发式扫描（取第一个正 int 作为 uid），
     * 可能选错参数（如 gid/其他正整数被误认为 uid）。改为对已知签名优先使用
     * 位置索引精确提取，仅未知签名回退到启发式扫描。
     */
    private static void handleProcessStartResult(Object[] args, Object result) {
        if (args == null || args.length < 3) return;

        String processName = null;
        int uid = -1;

        // R8-M2: 优先按已知签名位置索引提取，避免启发式误选
        // 已知签名 1：双 String 变体 — args[1]=niceName, args[2]=uid
        if (args.length >= 3
            && args[0] instanceof String
            && args[1] instanceof String
            && args[2] instanceof Integer) {
            processName = (String) args[1];
            uid = (int) args[2];
        }
        // 已知签名 2：单 String 变体 — args[0]=niceName, args[1]=uid
        else if (args.length >= 2
            && args[0] instanceof String
            && args[1] instanceof Integer) {
            processName = (String) args[0];
            uid = (int) args[1];
        } else {
            // R8-M2: 未知签名回退到启发式扫描
            int stringCount = 0;
            for (Object arg : args) {
                if (arg instanceof String) {
                    stringCount++;
                    // m-6: 合并 stringCount==1 和 stringCount==2 分支，统一取 String 作为 processName
                    if (stringCount >= 1) processName = (String) arg;
                } else if (arg instanceof Integer && uid < 0) {
                    int v = (int) arg;
                    if (v > 0) {
                        uid = v;
                    }
                }
            }
        }

        if (processName == null || uid < 0) {
            Logger.d("Process.start 参数解析失败: args.length=" + args.length
                + " processName=" + processName + " uid=" + uid);
            return;
        }

        int pid = getPidFromResult(result);
        if (pid <= 0) {
            Logger.d("Process.start 未获取到有效 pid: processName=" + processName);
            return;
        }

        Logger.i("检测到进程启动: " + processName + " uid=" + uid + " pid=" + pid);
        performFreezeDecision(pid, uid, processName);
    }

    /**
     * 处理 Zygote.forkAndSpecialize 的返回结果
     *
     * R8-M3 修复：旧代码硬编码 args[7] 为 niceName，参数布局可能随 Android 版本变化。
     * 改为扫描参数寻找第一个 String 作为 niceName，第一个 Integer 作为 uid，
     * 类似 handleProcessStartResult 的方式。
     */
    private static void handleZygoteForkResult(Object[] args, Object result) {
        if (args == null || args.length < 2) return;

        // R8-M3: 扫描参数提取 uid（第一个 Integer）和 niceName
        // R9-M2: Zygote.forkAndSpecialize 的第一个 String 是 seInfo（SELinux 上下文），
        // 第二个 String 才是 niceName。旧代码选取第一个 String 会错误使用 seInfo。
        // R10-M-1: 旧代码在找到第二个 String 后立即 break，若 uid（第一个 Integer）
        // 出现在第二个 String 之后，uid 提取会被跳过导致方法提前返回。改为不立即 break，
        // 仅在 uid 与 processName 均已找到时才结束遍历。
        int uid = -1;
        String processName = null;
        int stringCount = 0;
        for (Object arg : args) {
            if (arg instanceof String) {
                stringCount++;
                if (stringCount == 2) {
                    processName = (String) arg;
                }
            } else if (uid < 0 && arg instanceof Integer) {
                int v = (int) arg;
                // R11-m-1: uid=0（root）进程的 uid 被误选为 gid，v > 0 会跳过 uid=0。
                // 改为 v >= 0 以正确识别 root 进程的 uid。
                if (v >= 0) uid = v;
            }
            // 两者均已找到，提前结束遍历
            if (processName != null && uid >= 0) {
                break;
            }
        }

        if (processName == null || uid < 0) return;

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
        // R11-M-4: 仅在 system_server 中执行冻结逻辑。
        // Zygote.forkAndSpecialize 的回调在 Zygote 进程中执行，此时单例管理器
        // （ProcessTracker、FreezeManager 等）尚未初始化，访问会导致 NPE。
        // S-1: Android 中 pid 1 是 init 进程，system_server pid 通常在数百至数千范围，
        // 不能用 myPid() == 1 判断 system_server，改用进程名判断。
        // 检查是否在 system_server 中运行
        String myProcessName = android.os.Process.myProcessName();
        if (!"system_server".equals(myProcessName)) return;
        if (pid <= 0 || processName == null) return;
        String packageName = extractPackageName(processName);
        if (packageName == null) return;

        boolean isSystemApp = uid < 10000;
        if (isCriticalProcess(packageName, processName, uid, isSystemApp)) return;

        // 注册前先检查该应用是否已有运行进程（用于判断"从未被手动打开"）
        // R10-m-3: existingBefore 快照与 registerProcess 之间存在 TOCTOU 窗口
        // （另一线程可能在快照后、注册前启动并注册同包进程）。此窗口已被兜底：
        // 1) FreezeManager.freezeProcess 内部会检查 FOREGROUND 状态，前台进程不会被冻结；
        // 2) ScheduledFreezeManager 每分钟扫描会重新评估所有进程的冻结状态。
        // 因此即使快照与实际状态短暂不一致，也不会导致功能性错误。
        List<AppInfo> existingBefore = ProcessTracker.getInstance().getAllByPackage(packageName);

        // 注册进程到 ProcessTracker，以便 FreezeManager 能管理它
        ProcessTracker.getInstance().registerProcess(packageName, processName, pid, uid, isSystemApp);

        // 检查该应用是否已有其他进程被冻结
        boolean hasFrozen = false;
        for (AppInfo info : existingBefore) {
            if (info.pid != pid && info.getState() == AppState.FROZEN) {
                hasFrozen = true;
                break;
            }
        }

        boolean isMainProcess = processName.equals(packageName);

        if (hasFrozen && !isMainProcess) {
            // 已冻结应用启动新进程（非主进程）— 延迟冻结
            // R8-S4: 动态读取用户配置的冻结延迟，而非硬编码 FREEZE_DELAY_SEC
            int delaySec = ConfigManager.getInstance().getFreezeDelay();
            Logger.i("已冻结应用启动新进程，将在 " + delaySec + " 秒后冻结: "
                + packageName + " proc=" + processName + " pid=" + pid);
            scheduleFreeze(pid, uid, delaySec);
        } else if (existingBefore.isEmpty()) {
            // 该应用此前无运行进程（从未被手动打开）— 直接冻结
            // R8-S4: 动态读取用户配置的冻结延迟
            int delaySec = ConfigManager.getInstance().getFreezeDelay();
            Logger.i("应用从未被手动打开，直接冻结: " + packageName
                + " proc=" + processName + " pid=" + pid);
            scheduleFreeze(pid, uid, delaySec);
        }
    }

    /**
     * 安排延迟冻结任务（带去重与前台检查）
     */
    private static void scheduleFreeze(int pid, int uid, int delaySec) {
        pendingFreezes.compute(pid, (k, oldFuture) -> {
            if (oldFuture != null) oldFuture.cancel(false);
            // M1: 捕获 ScheduledFuture 引用，finally 中使用条件 remove 避免误删新 future
            ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
            holder[0] = freezeExecutor.schedule(() -> {
                try {
                    AppInfo info = ProcessTracker.getInstance().getByPid(pid);
                    // R7: 进程已死亡（KILLED）或未注册时跳过冻结，避免对死亡进程执行无意义操作
                    if (info == null || info.getState() == AppState.KILLED) {
                        pendingFreezes.remove(pid, holder[0]);
                        return;
                    }
                    // 前台进程不冻结，防止竞态条件下冻结前台应用
                    if (info.getState() == AppState.FOREGROUND) {
                        Logger.d("新进程已变为前台，取消冻结: pid=" + pid);
                        return;
                    }
                    if (info.getState() == AppState.FROZEN) return;
                    FreezeManager.getInstance().freezeProcess(pid, uid);
                } catch (Throwable t) {
                    Logger.e("新进程延迟冻结出错 pid=" + pid, t);
                } finally {
                    // R11-m-2: holder[0] 理论上可能为 null（schedule 抛异常时未赋值），
                    // 添加 null 检查避免 ConcurrentMap.remove(pid, null) 的非预期行为
                    if (holder[0] != null) {
                        pendingFreezes.remove(pid, holder[0]);
                    }
                }
            }, delaySec, TimeUnit.SECONDS);
            return holder[0];
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
        // m-1: 冒号在首位（如 ":process"）时返回 null，避免返回无效包名
        if (colonIdx == 0) return null;
        // 无冒号时，进程名即包名
        if (colonIdx < 0) return processName;
        return processName.substring(0, colonIdx);
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
        // M-6: 某些重载直接返回 Integer 类型的 pid，优先处理
        if (result instanceof Integer) return (int) result;
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
