package com.tombstonex.hook;

import com.tombstonex.manager.FreezeManager;
import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hook 进程死亡事件，清理 ProcessTracker 中的残留记录
 */
public class ProcessDeathHook {

    public static void init(ClassLoader classLoader) {
        hookHandleAppDied(classLoader);
        hookCleanUpApplicationRecord(classLoader);
    }

    /**
     * Hook ProcessRecord.handleAppDied()
     */
    private static void hookHandleAppDied(ClassLoader classLoader) {
        try {
            Class<?> processRecordClass = XposedHelpers.findClass(
                "com.android.server.am.ProcessRecord", classLoader);

            XposedHelpers.findAndHookMethod(processRecordClass,
                "onCleanupApplicationRecord",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            int pid = XposedHelpers.getIntField(param.thisObject, "pid");
                            ProcessTracker.getInstance().removeProcess(pid);
                            Logger.i("Process died, cleaned up: pid=" + pid);
                        } catch (Throwable t) {
                            Logger.e("onCleanupApplicationRecord hook error", t);
                        }
                    }
                });
            Logger.i("Hooked onCleanupApplicationRecord");
        } catch (Throwable t) {
            // 尝试备用方法名
            try {
                Class<?> processRecordClass = XposedHelpers.findClass(
                    "com.android.server.am.ProcessRecord", classLoader);
                XposedHelpers.findAndHookMethod(processRecordClass,
                    "handleAppDied",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                int pid = XposedHelpers.getIntField(param.thisObject, "pid");
                                ProcessTracker.getInstance().removeProcess(pid);
                                Logger.i("Process died (handleAppDied), cleaned up: pid=" + pid);
                            } catch (Throwable ignored) {}
                        }
                    });
                Logger.i("Hooked handleAppDied (fallback)");
            } catch (Throwable t2) {
                Logger.e("Failed to hook process death", t2);
            }
        }
    }

    /**
     * Hook AMS.cleanUpApplicationRecord — 进程被系统杀死时也清理
     */
    private static void hookCleanUpApplicationRecord(ClassLoader classLoader) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);

            XposedHelpers.findAndHookMethod(amsClass,
                "handleAppCrashLocked",
                "com.android.server.am.ProcessRecord",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object processRecord = param.args[0];
                            if (processRecord == null) return;
                            int pid = XposedHelpers.getIntField(processRecord, "pid");
                            ProcessTracker.getInstance().removeProcess(pid);
                            Logger.w("App crashed, cleaned up: pid=" + pid);
                        } catch (Throwable ignored) {}
                    }
                });
            Logger.i("Hooked handleAppCrashLocked");
        } catch (Throwable t) {
            Logger.e("Failed to hook crash cleanup", t);
        }
    }
}