package com.tombstonex.hook;

import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public class WakeLockHook {

    public static void init(ClassLoader classLoader) {
        hookReleaseWakeLock(classLoader);
    }

    private static void hookReleaseWakeLock(ClassLoader classLoader) {
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
                            int uid = (int) param.args[6];
                            for (AppInfo info : ProcessTracker.getInstance()
                                    .getAllProcesses().values()) {
                                if (info.uid == uid && info.state == AppState.FROZEN) {
                                    Logger.d("Blocking wakelock acquire for frozen app: "
                                        + info.packageName);
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