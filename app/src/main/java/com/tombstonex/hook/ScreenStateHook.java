package com.tombstonex.hook;

import com.tombstonex.manager.FreezeManager;
import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.manager.WhitelistManager;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.Map;

/**
 * Hook 锁屏/息屏事件，批量冻结后台应用
 */
public class ScreenStateHook {

    private static final int SCREEN_OFF_DELAY = 60; // 锁屏后 60 秒批量冻结

    public static void init(ClassLoader classLoader) {
        hookScreenOff(classLoader);
        hookScreenOn(classLoader);
    }

    private static void hookScreenOff(ClassLoader classLoader) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);

            // Hook screenOff 方法
            String[] methodNames = {"screenOff", "goingToSleep"};
            for (String methodName : methodNames) {
                try {
                    XposedHelpers.findAndHookMethod(amsClass,
                        methodName, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Logger.i("Screen off, batch freezing in " + SCREEN_OFF_DELAY + "s");
                                    new Thread(() -> {
                                        try {
                                            Thread.sleep(SCREEN_OFF_DELAY * 1000L);
                                            batchFreezeAll();
                                        } catch (InterruptedException ignored) {}
                                    }).start();
                                } catch (Throwable t) {
                                    Logger.e("screenOff hook error", t);
                                }
                            }
                        });
                    Logger.i("Hooked " + methodName);
                    break;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Logger.e("Failed to hook screen off", t);
        }
    }

    private static void hookScreenOn(ClassLoader classLoader) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);

            String[] methodNames = {"screenOn", "wakingUp"};
            for (String methodName : methodNames) {
                try {
                    XposedHelpers.findAndHookMethod(amsClass,
                        methodName,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Logger.i("Screen on, keeping frozen apps frozen");
                                    // 屏幕亮起时不自动解冻，等用户主动打开应用时再解冻
                                } catch (Throwable t) {
                                    Logger.e("screenOn hook error", t);
                                }
                            }
                        });
                    Logger.i("Hooked " + methodName);
                    break;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Logger.e("Failed to hook screen on", t);
        }
    }

    /**
     * 批量冻结所有后台非白名单应用
     */
    private static void batchFreezeAll() {
        int frozen = 0;
        int skipped = 0;
        for (Map.Entry<Integer, AppInfo> entry :
                ProcessTracker.getInstance().getAllProcesses().entrySet()) {
            AppInfo info = entry.getValue();
            if (info.state == AppState.FOREGROUND) {
                skipped++;
                continue;
            }
            if (info.isWhiteListed) {
                skipped++;
                continue;
            }
            if (!WhitelistManager.getInstance().shouldFreeze(
                    info.packageName, info.processName, info.isSystemApp)) {
                skipped++;
                continue;
            }
            boolean result = FreezeManager.getInstance().freezeProcess(info.pid, info.uid);
            if (result) frozen++;
        }
        Logger.i("Batch freeze complete: frozen=" + frozen + " skipped=" + skipped);
    }
}