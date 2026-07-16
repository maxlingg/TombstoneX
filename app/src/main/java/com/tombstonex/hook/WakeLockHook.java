package com.tombstonex.hook;

import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hook 唤醒锁
 * 冻结应用不允许获取新的 WakeLock
 */
public class WakeLockHook {

    public static void init(ClassLoader classLoader) {
        hookAcquireWakeLock(classLoader);
    }

    private static void hookAcquireWakeLock(ClassLoader classLoader) {
        try {
            Class<?> pmsClass = XposedHelpers.findClass(
                "com.android.server.power.PowerManagerService", classLoader);

            XposedHelpers.findAndHookMethod(pmsClass,
                "acquireWakeLockInternal",
                "android.os.IBinder", int.class, String.class,
                String.class, "android.os.WorkSource", String.class,
                int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            // 动态查找 uid 参数（不硬编码索引）
                            int uid = -1;
                            // 尝试从参数中找到 uid（通常是倒数第一个或倒数第二个 int 参数）
                            for (int i = param.args.length - 1; i >= 0; i--) {
                                if (param.args[i] instanceof Integer) {
                                    int val = (int) param.args[i];
                                    // uid 通常在 10000-19999 范围（用户应用）
                                    if (val >= 10000 && val < 20000) {
                                        uid = val;
                                        break;
                                    }
                                    // 如果没找到用户 uid，取最后一个 int
                                    if (uid == -1) uid = val;
                                }
                            }
                            if (uid < 0) return;

                            // 使用 uid 直接查找，避免遍历全部进程
                            for (AppInfo info : ProcessTracker.getInstance().getByUid(uid)) {
                                if (info.state == AppState.FROZEN) {
                                    Logger.d("Blocking wakelock acquire for frozen app: " + info.packageName);
                                    param.setResult(null);
                                    return;
                                }
                            }
                        } catch (Throwable t) {
                            Logger.e("acquireWakeLockInternal hook error", t);
                        }
                    }
                });
            Logger.i("Hooked acquireWakeLockInternal");
        } catch (Throwable t) {
            Logger.e("Failed to hook acquireWakeLockInternal", t);
        }
    }
}