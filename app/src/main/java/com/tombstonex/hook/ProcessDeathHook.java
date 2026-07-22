package com.tombstonex.hook;

import com.tombstonex.manager.FreezeManager;
import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import com.tombstonex.util.ReflectionUtils;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Hook 进程死亡事件，清理 ProcessTracker 中的残留记录
 */
public class ProcessDeathHook {

    public static void init(ClassLoader classLoader) {
        hookHandleAppDied(classLoader);
        hookCleanUpApplicationRecord(classLoader);
    }

    /**
     * Hook 进程死亡相关方法
     *
     * M9 修复：旧代码仅覆盖 ProcessRecord.onCleanupApplicationRecord（失败回退 handleAppDied），
     * 未覆盖 Android 14+ 的 ProcessRecord.onAppDeath 以及 AMS.appDied / handleAppDiedLocked。
     * 现对 ProcessRecord 与 AMS 上的多个死亡入口逐一尝试 hook，
     * ProcessTracker.removeProcess 幂等，重复触发安全。
     */
    private static void hookHandleAppDied(ClassLoader classLoader) {
        Class<?> processRecordClass = null;
        Class<?> amsClass = null;
        try {
            processRecordClass = XposedHelpers.findClass(
                "com.android.server.am.ProcessRecord", classLoader);
        } catch (Throwable t) {
            Logger.w("未找到 ProcessRecord: " + t.getMessage());
        }
        try {
            amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);
        } catch (Throwable t) {
            Logger.w("未找到 AMS: " + t.getMessage());
        }

        boolean anyHooked = false;

        // 1. ProcessRecord 上的无参方法（thisObject 即 ProcessRecord）
        if (processRecordClass != null) {
            String[] prMethods = {"onCleanupApplicationRecord", "handleAppDied", "onAppDeath"};
            for (String methodName : prMethods) {
                try {
                    final String src = methodName;
                    XposedHelpers.findAndHookMethod(processRecordClass, methodName,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    int pid = getPidFromProcessRecord(param.thisObject);
                                    // P2-06: pid <= 0 时无法定位进程，跳过清理避免无意义的全量遍历
                                    if (pid > 0) {
                                        cleanupDeadProcess(pid, src);
                                    }
                                } catch (Throwable t) {
                                    Logger.w(src + " Hook 出错: " + t.getMessage());
                                }
                            }
                        });
                    Logger.i("已 Hook " + methodName);
                    anyHooked = true;
                } catch (Throwable t) {
                    Logger.d(methodName + " 不可用: " + t.getMessage());
                }
            }
        }

        // 2. AMS 上的方法（args[0] 为 ProcessRecord）
        if (amsClass != null && processRecordClass != null) {
            // handleAppDiedLocked 可有多种签名
            Class<?>[][] diedLockedVariants = {
                {processRecordClass, boolean.class},
                {processRecordClass},
            };
            for (Class<?>[] params : diedLockedVariants) {
                try {
                    Method method = XposedHelpers.findMethodExact(
                        amsClass, "handleAppDiedLocked", params);
                    if (method == null) continue;
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object pr = param.args[0];
                                if (pr == null) return;
                                int pid = getPidFromProcessRecord(pr);
                                if (pid > 0) cleanupDeadProcess(pid, "handleAppDiedLocked");
                            } catch (Throwable t) {
                                Logger.w("handleAppDiedLocked Hook 出错: " + t.getMessage());
                            }
                        }
                    });
                    Logger.i("已 Hook handleAppDiedLocked (" + params.length + " 个参数)");
                    anyHooked = true;
                } catch (Throwable e) {
                    Logger.d("handleAppDiedLocked 变体失败: " + e.getMessage());
                }
            }

            // appDied — 多签名，首参可能是 ProcessRecord 或 int pid
            Class<?>[][] appDiedVariants = {
                {processRecordClass},
                {processRecordClass, int.class, String.class},
                {int.class, int.class},
                {android.os.IBinder.class, int.class, int.class},
                {android.os.IBinder.class, int.class, int.class, String.class},  // M2: Android 10+ with reason
            };
            // final 副本供匿名内部类捕获（processRecordClass 在 try 块中赋值，非 effectively final）
            final Class<?> prClass = processRecordClass;
            for (Class<?>[] params : appDiedVariants) {
                try {
                    Method method = XposedHelpers.findMethodExact(
                        amsClass, "appDied", params);
                    if (method == null) continue;
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object first = param.args[0];
                                int pid = -1;
                                if (first != null && prClass.isInstance(first)) {
                                    pid = getPidFromProcessRecord(first);
                                } else if (first instanceof Integer) {
                                    // R8-M11: (int,int) 签名首参可能是 uid 而非 pid（顺序可能为 uid,pid），
                                    // 验证候选值是否在 ProcessTracker 中存在，不存在则尝试 args[1]
                                    int candidate = (int) first;
                                    if (ProcessTracker.getInstance().getByPid(candidate) != null) {
                                        pid = candidate;
                                    } else if (param.args.length >= 2 && param.args[1] instanceof Integer) {
                                        int candidate2 = (int) param.args[1];
                                        if (ProcessTracker.getInstance().getByPid(candidate2) != null) {
                                            pid = candidate2;
                                        }
                                    }
                                } else if (param.args.length >= 2 && param.args[1] instanceof Integer) {
                                    // appDied(IBinder, int pid, int uid) 变体
                                    // m-4: 验证 pid 是否在 ProcessTracker 中存在，与 (int,int) 变体保持一致
                                    int candidate = (int) param.args[1];
                                    if (ProcessTracker.getInstance().getByPid(candidate) != null) {
                                        pid = candidate;
                                    } else if (param.args.length >= 3 && param.args[2] instanceof Integer) {
                                        int candidate2 = (int) param.args[2];
                                        if (ProcessTracker.getInstance().getByPid(candidate2) != null) {
                                            pid = candidate2;
                                        }
                                    }
                                }
                                if (pid > 0) cleanupDeadProcess(pid, "appDied");
                            } catch (Throwable t) {
                                Logger.w("appDied Hook 出错: " + t.getMessage());
                            }
                        }
                    });
                    Logger.i("已 Hook appDied (" + params.length + " 个参数)");
                    anyHooked = true;
                } catch (Throwable e) {
                    Logger.d("appDied 变体失败: " + e.getMessage());
                }
            }
        }

        if (!anyHooked) {
            Logger.w("Hook 进程死亡失败：所有已知方法均不可用");
        }
    }

    /**
     * 统一清理死亡进程的残留记录（幂等，可被多个入口重复触发）
     *
     * S1 修复：冻结进程死亡后需恢复防火墙规则，否则 NetworkHook 设置的
     * DENY 规则会残留，导致该 UID 在 PID 复用后仍被断网。
     *
     * R7 修复：旧实现先调用 NetworkHook.onProcessUnfrozen 再 updateState(KILLED)，
     * 导致 isUidFrozen 仍将死亡进程视为 FROZEN，跳过网络规则恢复。
     * 现先记录 wasFrozen，然后先调用 updateState(KILLED) 标记进程为死亡状态，
     * 再调用 onProcessUnfrozen 恢复网络规则。同时清理 FreezeManager.freezeSuppressedPids，
     * 避免 pid 复用后被错误抑制冻结。
     * 注意：ScheduledFreezeManager.lastFreezeTime 由 ProcessTracker.removeProcess 内部
     * 调用 clearLastFreezeTime 清理，无需在此重复。
     */
    private static void cleanupDeadProcess(int pid, String source) {
        // R11-M-3: 此处存在 TOCTOU 窗口：在 getByPid(pid) 获取 info 快照与后续
        // updateState(KILLED) / NetworkHook.onProcessUnfrozen / removeProcess 操作之间，
        // 另一线程可能同时操作同一 pid。此窗口已被以下机制兜底：
        // 1) NetworkHook.onProcessUnfrozen 内部使用 UID 级锁 + isUidFrozen 检查，
        //    若进程已不在 FROZEN 状态则跳过网络规则恢复，避免对非冻结进程误操作；
        // 2) setUidNetworkPolicy 操作本身幂等，重复调用无副作用；
        // 3) updateState(KILLED) 与 removeProcess 均幂等。
        // 因此重复触发无功能影响，仅产生冗余日志。
        // R8-M8: 幂等检查，避免多个死亡回调（onCleanupApplicationRecord /
        // cleanUpApplicationRecord 等）重复触发清理。若进程已被标记为 KILLED，
        // 说明已被其他回调清理，直接返回。
        // R9-m2: 此处与下方 updateState(KILLED) 之间存在 TOCTOU 窗口：另一线程可能
        // 在此检查之后、updateState 之前也通过幂等检查。但由于所有后续操作（cancelPendingFreeze、
        // updateState、removeProcess 等）均幂等，重复触发无功能影响，仅产生冗余日志。
        AppInfo info = ProcessTracker.getInstance().getByPid(pid);
        if (info != null && info.getState() == AppState.KILLED) {
            return; // 已被其他回调清理
        }
        ActivitySwitchHook.cancelPendingFreeze(pid);
        ProcessStartHook.cancelPendingFreeze(pid);
        // R8-m1: 清理 AutoStartHook 中该包名的待重新冻结任务，避免进程死亡后残留
        if (info != null && info.packageName != null) {
            try {
                AutoStartHook.cancelPendingRefreeze(info.packageName);
            } catch (Throwable t) {
                Logger.d("cancelPendingRefreeze 失败: " + t.getMessage());
            }
        }
        boolean wasFrozen = (info != null && info.getState() == AppState.FROZEN);
        // 先标记为 KILLED，确保 isUidFrozen 不再将此进程视为冻结
        try {
            ProcessTracker.getInstance().updateState(pid, AppState.KILLED);
        } catch (Throwable ignored) {
            Logger.d("updateState(KILLED) 失败 pid=" + pid + ": " + ignored.getMessage());
        }
        // 然后恢复网络规则
        if (wasFrozen && info != null) {
            try {
                NetworkHook.onProcessUnfrozen(info.uid, info.packageName);
            } catch (Throwable t) {
                Logger.e("cleanupDeadProcess: 恢复网络失败 pid=" + pid, t);
            }
        }
        // R8-M10: 先清理冻结抑制集合再 removeProcess，避免 removeProcess 之后
        // 到 cleanupSuppressedPid 之间的窄窗口竞态（pid 复用后新进程被错误抑制冻结）
        FreezeManager.cleanupSuppressedPid(pid);
        // R11-M-2: 清理 ReKernel 相关的抑制状态，避免 pid 复用后抑制残留
        // S-2: 包裹 try-catch，避免 ReKernelHook 异常中断后续 removeProcess 清理链
        try {
            ReKernelHook.onProcessDied(pid);
        } catch (Throwable t) {
            Logger.d("ReKernelHook.onProcessDied 失败: " + t.getMessage());
        }
        ProcessTracker.getInstance().removeProcess(pid);
        Logger.i("进程已死亡 (" + source + ")，已清理: pid=" + pid);
    }

    /**
     * Hook AMS.cleanUpApplicationRecord 和 handleAppCrashLocked
     * 进程被系统杀死或崩溃时也清理
     */
    private static void hookCleanUpApplicationRecord(ClassLoader classLoader) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);
            Class<?> processRecordClass = XposedHelpers.findClass(
                "com.android.server.am.ProcessRecord", classLoader);

            // Hook cleanUpApplicationRecord（不仅仅是 onCleanupApplicationRecord）
            try {
                // 尝试多种签名
                Class<?>[][] cleanUpVariants = {
                    {processRecordClass, boolean.class, boolean.class, boolean.class},
                    {processRecordClass, boolean.class},
                    {processRecordClass},
                };

                for (Class<?>[] paramTypes : cleanUpVariants) {
                    try {
                        Method method = XposedHelpers.findMethodExact(
                            amsClass, "cleanUpApplicationRecord", paramTypes);
                        if (method == null) continue;

                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object processRecord = param.args[0];
                                    if (processRecord == null) return;
                                    int pid = getPidFromProcessRecord(processRecord);
                                    // P2-06: pid <= 0 时无法定位进程，跳过清理避免无意义的全量遍历
                                    if (pid > 0) {
                                        // S1: 统一走 cleanupDeadProcess，确保冻结进程死亡后恢复防火墙规则
                                        cleanupDeadProcess(pid, "cleanUpApplicationRecord");
                                    }
                                } catch (Throwable t) {
                                    Logger.w("cleanUpApplicationRecord Hook 出错: " + t.getMessage());
                                }
                            }
                        });
                        Logger.i("已 Hook cleanUpApplicationRecord");
                        break;
                    } catch (Throwable e) {
                        Logger.d("Hook 变体失败: " + e.getMessage());
                    }
                }
            } catch (Throwable t) {
                Logger.w("Hook cleanUpApplicationRecord 失败: " + t.getMessage());
            }

            // Hook handleAppCrashLocked — 支持带 String 参数的签名
            Class<?>[][] crashVariants = {
                {processRecordClass, String.class},
                {processRecordClass},
            };

            boolean crashHooked = false;
            for (Class<?>[] paramTypes : crashVariants) {
                try {
                    Method method = XposedHelpers.findMethodExact(
                        amsClass, "handleAppCrashLocked", paramTypes);
                    if (method == null) continue;

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object processRecord = param.args[0];
                                if (processRecord == null) return;
                                // R8-S1: AOSP 中 handleAppCrashLocked 不一定杀死进程
                                // （persistent 进程保护等）。通过反射检查 killed/killedByAm 字段，
                                // 仅在进程实际被杀死时才清理，避免误杀存活进程。
                                // R9-M4: 使用独立的 try-catch 分别检查 killed 与 killedByAm，
                                // 避免 || 短路求值在 getBooleanField("killed") 抛异常时跳过 killedByAm 检查。
                                // R11-S-2: AOSP 12+ 中 killed/killedByAm 字段名变为 mKilled/mKilledByAm，
                                // 各自添加 fallback 链式尝试。
                                boolean k1 = false, k2 = false;
                                try {
                                    k1 = XposedHelpers.getBooleanField(processRecord, "killed");
                                } catch (Throwable ignored) {
                                    try {
                                        k1 = XposedHelpers.getBooleanField(processRecord, "mKilled");
                                    } catch (Throwable ignored2) {
                                        Logger.d("getBooleanField(mKilled) 失败: " + ignored2.getMessage());
                                    }
                                }
                                try {
                                    k2 = XposedHelpers.getBooleanField(processRecord, "killedByAm");
                                } catch (Throwable ignored) {
                                    try {
                                        k2 = XposedHelpers.getBooleanField(processRecord, "mKilledByAm");
                                    } catch (Throwable ignored2) {
                                        Logger.d("getBooleanField(mKilledByAm) 失败: " + ignored2.getMessage());
                                    }
                                }
                                boolean killed = k1 || k2;
                                if (!killed) return;
                                // R9-m4: pid 初始值改为 -1，与其他位置（getPidFromProcessRecord 返回 -1）约定一致
                                // R10-m-4: 统一使用 getPidFromProcessRecord 提取 pid，与其他位置保持一致，
                                // 避免直接使用 XposedHelpers.getIntField 在字段名变更/私有字段场景下失败。
                                int pid = getPidFromProcessRecord(processRecord);
                                if (pid > 0) {
                                    cleanupDeadProcess(pid, "handleAppCrashLocked");
                                }
                            } catch (Throwable t) {
                                Logger.w("handleAppCrashLocked Hook 出错: " + t.getMessage());
                            }
                        }
                    });
                    Logger.i("已 Hook handleAppCrashLocked (" + paramTypes.length + " 个参数)");
                    crashHooked = true;
                    break;
                } catch (Throwable e) {
                    Logger.d("Hook 变体失败: " + e.getMessage());
                }
            }

            if (!crashHooked) {
                Logger.w("未找到已知签名的 handleAppCrashLocked");
            }
        } catch (Throwable t) {
            Logger.e("Hook 崩溃清理失败", t);
        }
    }

    /**
     * 使用 ReflectionUtils 安全获取 ProcessRecord 的 pid
     *
     * R11-S-1: AOSP 12+ 中 ProcessRecord.pid 字段改名为 mPid，导致 pid 提取失败。
     * 现先尝试 "pid" 字段，失败则尝试 "mPid"，若仍失败则尝试调用 getPid() 方法作为最后手段。
     */
    private static int getPidFromProcessRecord(Object processRecord) {
        try {
            // R11-S-1: 先尝试旧字段名 "pid"
            Field pidField = ReflectionUtils.findFieldRecursive(
                processRecord.getClass(), "pid");
            if (pidField == null) {
                // R11-S-1: AOSP 12+ 字段名变为 "mPid"
                pidField = ReflectionUtils.findFieldRecursive(
                    processRecord.getClass(), "mPid");
            }
            if (pidField != null) {
                Object val = ReflectionUtils.getFieldValue(processRecord, pidField);
                if (val instanceof Number) {
                    return ((Number) val).intValue();
                }
            }
            // R11-S-1: 两个字段名均未找到，尝试调用 getPid() 方法作为最后手段
            Method getPidMethod = ReflectionUtils.findMethodRecursive(
                processRecord.getClass(), "getPid");
            if (getPidMethod != null) {
                Object val = getPidMethod.invoke(processRecord);
                if (val instanceof Number) {
                    return ((Number) val).intValue();
                }
            }
        } catch (Throwable t) {
            Logger.w("从 ProcessRecord 获取 pid 失败: " + t.getMessage());
        }
        return -1;
    }
}
