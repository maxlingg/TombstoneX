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
                                        Logger.d("Blocking wakelock acquire for frozen app: "
                                            + info.packageName + " uid=" + uid);
                                        param.setResult(null);
                                        return;
                                    }
                                }
                            } catch (Throwable t) {
                                Logger.e("acquireWakeLockInternal hook error", t);
                            }
                        }
                    });
                    Logger.i("Hooked acquireWakeLockInternal (" + paramTypes.length + " params, uidIdx=" + uidIndex + ")");
                    hooked = true;
                    break;
                } catch (Throwable ignored) {}
            }

            if (!hooked) {
                Logger.w("Could not find acquireWakeLockInternal with known signatures");
            }
        } catch (Throwable t) {
            Logger.e("Failed to hook acquireWakeLockInternal", t);
        }
    }

    /**
     * Hook releaseWakeLockInternal — 释放唤醒锁时也检查
     */
    private static void hookReleaseWakeLock(ClassLoader classLoader) {
        try {
            Class<?> pmsClass = XposedHelpers.findClass(
                "com.android.server.power.PowerManagerService", classLoader);

            Class<?> iBinderClass = XposedHelpers.findClass("android.os.IBinder", classLoader);

            // 多版本 releaseWakeLockInternal 签名兼容
            Class<?>[][] paramTypeVariants = {
                // (IBinder, int)
                {iBinderClass, int.class},
                // (IBinder, int, int)
                {iBinderClass, int.class, int.class},
            };

            boolean hooked = false;
            for (Class<?>[] paramTypes : paramTypeVariants) {
                try {
                    Method method = XposedHelpers.findMethodExact(
                        pmsClass, "releaseWakeLockInternal", paramTypes);
                    if (method == null) continue;

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // 释放 WakeLock 时不需要阻止（即使进程被冻结也应允许释放）
                                // 仅记录日志用于调试
                                if (param.args.length >= 1 && param.args[0] != null) {
                                    Logger.d("releaseWakeLockInternal called");
                                }
                            } catch (Throwable t) {
                                Logger.e("releaseWakeLockInternal hook error", t);
                            }
                        }
                    });
                    Logger.i("Hooked releaseWakeLockInternal (" + paramTypes.length + " params)");
                    hooked = true;
                    break;
                } catch (Throwable ignored) {}
            }

            if (!hooked) {
                Logger.w("Could not find releaseWakeLockInternal with known signatures");
            }
        } catch (Throwable t) {
            Logger.e("Failed to hook releaseWakeLockInternal", t);
        }
    }
}
