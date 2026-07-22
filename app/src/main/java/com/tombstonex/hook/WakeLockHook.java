package com.tombstonex.hook;

import android.os.Build;
import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;

/**
 * Hook 唤醒锁
 * 冻结应用不允许获取新的 WakeLock。
 *
 * R8-M8-8: 设计限制——仅拦截新获取的 WakeLock，不处理冻结前已持有的。
 * 主动释放已持有 WakeLock 需要访问 PMS 内部 mWakeLocks 列表，复杂度高且风险大。
 * 当前依赖应用解冻后自行释放。如未来需要，可在 FreezeManager.freezeProcess 后遍历释放。
 *
 * R8-m8-5: hookReleaseWakeLock 已移除（旧实现仅打 debug 日志，无拦截/修改，纯性能开销）。
 */
public class WakeLockHook {

    public static void init(ClassLoader classLoader) {
        hookAcquireWakeLock(classLoader);
        // R8-m8-5: 不再调用 hookReleaseWakeLock（空方法，无实际作用）
    }

    /**
     * 根据 SDK 版本确定 uid 参数在 acquireWakeLockInternal 中的索引。
     *
     * R10-m-6: 此索引为基于 AOSP 签名的硬编码假设，OEM 修改可能导致偏移。返回的索引仅为
     * 候选值，调用方（hookAcquireWakeLock 的 beforeHookedMethod）应在运行时通过参数值范围
     * 验证（uid >= 10000）确认，若候选索引处的值不在用户应用 UID 范围内，则回退扫描所有
     * int 参数寻找 >= 10000 的值。此启发式判断有局限性：若某非 uid 的 int 参数恰好 >= 10000
     * （如大 timeout 值），可能误判；但 acquireWakeLockInternal 中 uid 是唯一通常 >= 10000
     * 的 int 参数（levelAndFlags < 10000，timeout 通常为 -1 或小值），故实践中可靠。
     *
     * M-10: 此 UID 启发式检测可能将 PID 误判为 UID——在高负载系统中 PID 可超过 10000，
     * 与用户应用 UID 范围重叠。更可靠的替代方案是优先通过 Binder.getCallingUid() 获取
     * 真实调用方 UID，将参数推断作为回退。但 acquireWakeLockInternal 在 system_server
     * 内部调用时 Binder.getCallingUid() 可能返回 SYSTEM_UID（1000），需结合调用上下文判断。
     * 当前仍以参数推断为主，Binder.getCallingUid() 作为未来改进方向。
     */
    private static int getUidParamIndexForAcquire(int paramCount) {
        // R8-m8-4: 6 参数变体 (IBinder, int, String, WorkSource, String, int) 为 Android 10 签名，
        // 因 minSdk=32 (Android 12)，此变体不会被命中，特判保留仅为防御性兼容。
        if (paramCount == 6) return 5;  // uid 是最后一个参数（index 5）
        if (paramCount == 4) return -1; // 4 参数变体无 uid 参数
        // 根据 SDK 版本和参数个数硬编码 uid 索引
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: acquireWakeLockInternal(IBinder, int, String, String, WorkSource, String, int, int)
            // uid 通常在倒数第二个 int 参数 (index = paramCount - 2)
            return paramCount >= 2 ? paramCount - 2 : -1;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12-13: acquireWakeLockInternal(IBinder, int, String, String, WorkSource, String, int, int)
            // uid 在 index 6 或 7
            return paramCount >= 8 ? 6 : (paramCount >= 2 ? paramCount - 2 : -1);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11: acquireWakeLockInternal(IBinder, int, String, WorkSource, String, int, int)
            // uid 在 index 5 或 6
            return paramCount >= 7 ? 5 : (paramCount >= 2 ? paramCount - 2 : -1);
        } else {
            // Android 10 及以下
            return paramCount >= 2 ? paramCount - 2 : -1;
        }
    }

    /**
     * Hook acquireWakeLockInternal（多版本签名兼容）
     *
     * L3 修复：旧代码在首个命中签名后即 break，仅 hook 一个重载。
     * 某些 Android 版本 PowerManagerService 同时存在多个 acquireWakeLockInternal 重载，
     * 仅 hook 第一个可能导致部分调用路径绕过拦截。现对所有命中重载都 hook，不提前 break。
     */
    private static void hookAcquireWakeLock(ClassLoader classLoader) {
        try {
            Class<?> pmsClass = XposedHelpers.findClass(
                "com.android.server.power.PowerManagerService", classLoader);

            Class<?> iBinderClass = XposedHelpers.findClass("android.os.IBinder", classLoader);
            Class<?> workSourceClass = XposedHelpers.findClass("android.os.WorkSource", classLoader);

            // 多版本 acquireWakeLockInternal 签名兼容
            Class<?>[][] paramTypeVariants = {
                // Android 12+: (IBinder, int, String, String, WorkSource, String, int, int)
                {iBinderClass, int.class, String.class, String.class, workSourceClass,
                 String.class, int.class, int.class},
                // Android 11: (IBinder, int, String, WorkSource, String, int, int)
                {iBinderClass, int.class, String.class, workSourceClass,
                 String.class, int.class, int.class},
                // Android 10: (IBinder, int, String, WorkSource, String, int)
            // R8-m8-4: 因 minSdk=32 (Android 12) 此签名不会被命中，保留仅为防御性兼容。
            {iBinderClass, int.class, String.class, workSourceClass,
             String.class, int.class},
                // 旧版本: (IBinder, int, String, String)
                {iBinderClass, int.class, String.class, String.class},
            };

            int hookCount = 0;
            for (Class<?>[] paramTypes : paramTypeVariants) {
                try {
                    Method method = XposedHelpers.findMethodExact(
                        pmsClass, "acquireWakeLockInternal", paramTypes);
                    if (method == null) continue;

                    // 根据 SDK 版本确定 uid 参数索引
                    final int uidIndex = getUidParamIndexForAcquire(paramTypes.length);

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                int uid = -1;
                                // M-10: 已知限制——硬编码的 UID 参数索引在 OEM 修改签名后可能失效。
                                // 运行时验证：检查参数是否为 Integer 且值 >= 10000（用户应用 UID 范围）。
                                // 若候选索引处的值不在用户应用 UID 范围内，则回退扫描所有 int 参数寻找 >= 10000 的值。
                                // 此启发式判断有局限性：若某非 uid 的 int 参数恰好 >= 10000（如大 timeout 值），
                                // 可能误判；但 acquireWakeLockInternal 中 uid 是唯一通常 >= 10000 的 int 参数。
                                // R10-m-6: 优先使用硬编码候选索引，但需验证其值 >= 10000（用户应用 UID 范围）
                                if (uidIndex >= 0 && uidIndex < param.args.length
                                    && param.args[uidIndex] instanceof Integer) {
                                    int val = (int) param.args[uidIndex];
                                    if (val >= 10000) {
                                        uid = val;
                                    }
                                }
                                // R10-m-6: 候选索引未命中（值不在用户应用 UID 范围）时，
                                // 回退扫描所有 int 参数寻找 >= 10000 的值，应对 OEM 签名偏移
                                if (uid < 0) {
                                    for (int i = 0; i < param.args.length; i++) {
                                        if (i == uidIndex) continue;
                                        if (param.args[i] instanceof Integer) {
                                            int val = (int) param.args[i];
                                            if (val >= 10000) {
                                                uid = val;
                                                break;
                                            }
                                        }
                                    }
                                }

                                if (uid < 0) return;

                                // 使用 getByUid 查找冻结进程
                                for (AppInfo info : ProcessTracker.getInstance().getByUid(uid)) {
                                    if (info.getState() == AppState.FROZEN) {
                                        Logger.d("已拦截已冻结应用的 WakeLock 获取: "
                                            + info.packageName + " uid=" + uid);
                                        param.setResult(null);
                                        return;
                                    }
                                }
                            } catch (Throwable t) {
                                Logger.e("acquireWakeLockInternal Hook 出错", t);
                            }
                        }
                    });
                    Logger.i("已 Hook acquireWakeLockInternal（" + paramTypes.length + " 个参数，uidIdx=" + uidIndex + "）");
                    hookCount++;
                    // 不 break：对每个命中的重载都 hook
                } catch (Throwable e) {
                    Logger.d("Hook 变体失败: " + e.getMessage());
                }
            }

            if (hookCount == 0) {
                // M-9: 使用 Logger.e 而非 Logger.w，精确签名匹配无容错是功能性问题。
                // acquireWakeLockInternal 的精确签名匹配在 OEM 修改或 Android 版本更新后
                // 可能全部失败，导致 WakeLock 拦截功能完全失效。
                // 替代方案：可通过 Hook PMS 内部调用链中更上层的方法，在回调中使用
                // Binder.getCallingUid() 获取调用方 UID，不依赖参数签名匹配。
                // 但 Binder.getCallingUid() 在 system_server 内部调用路径中可能返回
                // SYSTEM_UID（1000）而非应用 UID，需结合调用上下文判断。
                Logger.e("M-9: 未找到已知签名的 acquireWakeLockInternal，WakeLock 拦截功能失效。" +
                    "可考虑通过 Binder.getCallingUid() 替代参数推断获取 UID");
            }
        } catch (Throwable t) {
            Logger.e("Hook acquireWakeLockInternal 失败", t);
        }
    }

    /**
     * R8-m8-5: hookReleaseWakeLock 已移除。
     *
     * 旧代码 hook 了 releaseWakeLockInternal 但只在 beforeHookedMethod 中打 debug 日志，
     * 没有任何拦截或修改。releaseWakeLockInternal 是高频调用路径，每次 Hook 都额外走
     * Xposed 回调链 + 反射 + Logger.d 判断，纯性能开销无收益。释放 WakeLock 时不应阻止
     * （即使进程被冻结也应允许释放），所以此 Hook 无存在必要，故从 init() 中移除调用并删除方法体。
     */
}
