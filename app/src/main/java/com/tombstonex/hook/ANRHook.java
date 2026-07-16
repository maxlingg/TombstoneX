package com.tombstonex.hook;

import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public class ANRHook {

    public static void init(ClassLoader classLoader) {
        hookAppNotResponding(classLoader);
        hookInputDispatchingTimedOut(classLoader);
    }

    private static void hookAppNotResponding(ClassLoader classLoader) {
        try {
            Class<?> processRecordClass = XposedHelpers.findClass(
                "com.android.server.am.ProcessRecord", classLoader);

            XposedHelpers.findAndHookMethod(processRecordClass,
                "appNotResponding",
                String.class, "android.app.ApplicationExitInfo",
                "com.android.server.am.ProcessRecord$ProcessErrorStateRecord",
                boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object processRecord = param.thisObject;
                            int pid = XposedHelpers.getIntField(processRecord, "pid");

                            AppInfo appInfo = ProcessTracker.getInstance().getByPid(pid);
                            if (appInfo != null && appInfo.state == AppState.FROZEN) {
                                Logger.d("Blocking ANR for frozen app: "
                                    + appInfo.packageName + " pid=" + pid);
                                param.setResult(null);
                            }
                        } catch (Throwable t) {
                            Logger.e("appNotResponding hook error", t);
                        }
                    }
                });
            Logger.i("Hooked appNotResponding");
        } catch (Throwable t) {
            Logger.e("Failed to hook appNotResponding", t);
        }
    }

    private static void hookInputDispatchingTimedOut(ClassLoader classLoader) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);

            XposedHelpers.findAndHookMethod(amsClass,
                "inputDispatchingTimedOut",
                "com.android.server.am.ProcessRecord",
                "com.android.server.wm.ActivityRecord",
                "com.android.server.wm.ActivityRecord",
                boolean.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object processRecord = param.args[0];
                            if (processRecord == null) return;

                            int pid = XposedHelpers.getIntField(processRecord, "pid");
                            AppInfo appInfo = ProcessTracker.getInstance().getByPid(pid);

                            if (appInfo != null && appInfo.state == AppState.FROZEN) {
                                Logger.d("Blocking input ANR for frozen app: "
                                    + appInfo.packageName);
                                param.setResult(null);
                            }
                        } catch (Throwable t) {
                            Logger.e("inputDispatchingTimedOut hook error", t);
                        }
                    }
                });
            Logger.i("Hooked inputDispatchingTimedOut");
        } catch (Throwable t) {
            Logger.e("Failed to hook inputDispatchingTimedOut", t);
        }
    }
}