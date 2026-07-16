package com.tombstonex.hook;

import com.tombstonex.manager.FreezeManager;
import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.manager.WhitelistManager;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import com.tombstonex.util.ReflectionUtils;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class ActivitySwitchHook {

    private static final int PROCESS_STATE_TOP = 2;

    public static void init(ClassLoader classLoader) {
        hookActivityPaused(classLoader);
        hookProcessStateChanged(classLoader);
        hookSetProcessImportant(classLoader);
        hookUidImportance(classLoader);
    }

    private static void hookActivityPaused(ClassLoader classLoader) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);

            XposedHelpers.findAndHookMethod(amsClass,
                "activityPaused",
                "android.os.IBinder",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            handleActivityPaused(param);
                        } catch (Throwable t) {
                            Logger.e("activityPaused hook error", t);
                        }
                    }
                });
            Logger.i("Hooked activityPaused");
        } catch (Throwable t) {
            Logger.e("Failed to hook activityPaused", t);
        }
    }

    private static void handleActivityPaused(XC_MethodHook.MethodHookParam param) {
        try {
            Object token = param.args[0];
            if (token == null) return;

            Object processRecord = getProcessRecordFromActivity(param.thisObject, token);
            if (processRecord == null) return;

            String processName = (String) getFieldValue(processRecord, "processName");
            int pid = (int) getFieldValue(processRecord, "pid");
            int uid = (int) getFieldValue(processRecord, "uid");
            String packageName = extractPackageName(processName);

            if (pid <= 0 || packageName == null) return;

            boolean isSystemApp = uid < 10000;

            Logger.d("App paused: " + packageName + " pid=" + pid + " uid=" + uid);

            if (!WhitelistManager.getInstance().shouldFreeze(packageName, processName, isSystemApp)) {
                Logger.d("App in whitelist, skip freeze: " + packageName);
                return;
            }

            ProcessTracker.getInstance().registerProcess(packageName, pid, uid, isSystemApp);

            final int targetPid = pid;
            final int targetUid = uid;
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    FreezeManager.getInstance().freezeProcess(targetPid, targetUid);
                } catch (InterruptedException ignored) {}
            }).start();

        } catch (Throwable t) {
            Logger.e("handleActivityPaused error", t);
        }
    }

    private static void hookProcessStateChanged(ClassLoader classLoader) {
        try {
            Class<?> processRecordClass = XposedHelpers.findClass(
                "com.android.server.am.ProcessRecord", classLoader);

            XposedHelpers.findAndHookMethod(processRecordClass,
                "setCurrentSchedulingGroup", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            int schedulingGroup = (int) param.args[0];
                            if (schedulingGroup == PROCESS_STATE_TOP) {
                                Object processRecord = param.thisObject;
                                int pid = (int) getFieldValue(processRecord, "pid");
                                int uid = (int) getFieldValue(processRecord, "uid");
                                if (pid > 0) {
                                    FreezeManager.getInstance().unfreezeProcess(pid, uid);
                                    ProcessTracker.getInstance().updateState(pid, AppState.FOREGROUND);
                                }
                            }
                        } catch (Throwable t) {
                            Logger.e("processStateChanged hook error", t);
                        }
                    }
                });
            Logger.i("Hooked setCurrentSchedulingGroup");
        } catch (Throwable t) {
            Logger.e("Failed to hook setCurrentSchedulingGroup", t);
        }
    }

    private static void hookSetProcessImportant(ClassLoader classLoader) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);

            XposedHelpers.findAndHookMethod(amsClass,
                "setProcessImportant",
                "android.os.IBinder", int.class, boolean.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            boolean important = (boolean) param.args[2];
                            if (!important) {
                                Logger.d("Process set not important: " + param.args[3]);
                            }
                        } catch (Throwable t) {
                            Logger.e("setProcessImportant hook error", t);
                        }
                    }
                });
            Logger.i("Hooked setProcessImportant");
        } catch (Throwable t) {
            Logger.e("Failed to hook setProcessImportant", t);
        }
    }

    private static void hookUidImportance(ClassLoader classLoader) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService", classLoader);

            XposedHelpers.findAndHookMethod(amsClass,
                "reportUidImportanceChanged",
                int.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            int uid = (int) param.args[0];
                            boolean important = (boolean) param.args[1];
                            Logger.d("UID importance changed: uid=" + uid + " important=" + important);
                        } catch (Throwable t) {
                            Logger.e("reportUidImportanceChanged hook error", t);
                        }
                    }
                });
            Logger.i("Hooked reportUidImportanceChanged");
        } catch (Throwable t) {
            Logger.e("Failed to hook reportUidImportanceChanged", t);
        }
    }

    private static Object getProcessRecordFromActivity(Object ams, Object token) {
        try {
            Object atms = getFieldValue(ams, "mAtmService");
            if (atms == null) return null;

            Method getActivityRecord = ReflectionUtils.findMethodRecursive(
                atms.getClass(), "getActivityRecord", android.os.IBinder.class);
            if (getActivityRecord == null) return null;

            Object activityRecord = null;
            try {
                activityRecord = getActivityRecord.invoke(atms, token);
            } catch (Exception e) {
                return null;
            }

            if (activityRecord == null) return null;
            return getFieldValue(activityRecord, "app");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String extractPackageName(String processName) {
        if (processName == null) return null;
        int colonIdx = processName.indexOf(':');
        return colonIdx > 0 ? processName.substring(0, colonIdx) : processName;
    }

    private static Object getFieldValue(Object obj, String fieldName) {
        Field field = ReflectionUtils.findFieldRecursive(obj.getClass(), fieldName);
        return field != null ? ReflectionUtils.getFieldValue(obj, field) : null;
    }
}