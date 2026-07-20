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
 * 冻结应用不允许获取新的 WakeLock，释放时也需检查
 */
public class WakeLockHook {

    public static void init(ClassLoader classLoader) {
        hookAcquireWakeLock(classLoader);
        hookReleaseWakeLock(classLoader);
    }

    /**
     * 根据 SDK 版本确定 uid 参数在 acquireWakeLockInternal 中的索引
     */
    private static int getUidParamIndexForAcquire(int paramCount) {
        // 特判：6 参数变体 uid 是最后一个参数（index 5）；4 参数变体无 uid 参数
        if (paramCount == 6) return 5;  // uid 是最后一个参数
        if (paramCount == 4) return -1; // 无 uid 参数
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
                {iBinderClass, int.class, String.class, workSourceClass,
                 String.class, int.class},
                // 旧版本: (IBinder, int, String, String)
                {iBinderClass, int.class, String.class, String.class},
            };

            boolean hooked = false;
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
                                if (uidIndex >= 0 && uidIndex < param.args.length
                                    && param.args[uidIndex] instanceof Integer) {
                                    uid = (int) param.args[uidIndex];
                                }

                                if (uid < 0) return;

                                // 使用 getByUid 查找冻结进程
                                for (AppInfo info : ProcessTracker.getInstance().getByUid(uid)) {
                                    if (info.state == AppState.FROZEN) {
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
                    hooked = true;
                    break;
                } catch (Throwable e) {
                    Logger.d("Hook 变体失败: " + e.getMessage());
                }
            }

            if (!hooked) {
                Logger.w("未找到已知签名的 acquireWakeLockInternal");
            }
        } catch (Throwable t) {
            Logger.e("Hook acquireWakeLockInternal 失败", t);
        }
    }

    /**
     * hookReleaseWakeLock 已移除
     *
     * 旧代码 hook 了 releaseWakeLockInternal 但只在 beforeHookedMethod 中打 debug 日志，
     * 没有任何拦截或修改。releaseWakeLockInternal 是高频调用路径，
     * 每次 Hook 都额外走 Xposed 回调链 + 反射 + Logger.d 判断，纯性能开销无收益。
     * 释放 WakeLock 时不应阻止（即使进程被冻结也应允许释放），所以此 Hook 无存在必要。
     */
    private static void hookReleaseWakeLock(ClassLoader classLoader) {
        // 已移除：无实际作用的 Hook
    }
}
