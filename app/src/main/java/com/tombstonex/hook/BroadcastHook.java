package com.tombstonex.hook;

import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.List;

public class BroadcastHook {

    public static void init(ClassLoader classLoader) {
        hookBroadcastDelivery(classLoader);
        hookScheduleBroadcasts(classLoader);
    }

    private static void hookBroadcastDelivery(ClassLoader classLoader) {
        try {
            Class<?> broadcastQueueClass = XposedHelpers.findClass(
                "com.android.server.am.BroadcastQueue", classLoader);

            XposedHelpers.findAndHookMethod(broadcastQueueClass,
                "deliverToRegisteredReceiver",
                "com.android.server.am.BroadcastRecord",
                "com.android.server.am.BroadcastFilter",
                boolean.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object filter = param.args[1];
                            if (filter == null) return;

                            int owningUid = XposedHelpers.getIntField(filter, "owningUid");
                            int owningPid = XposedHelpers.getIntField(filter, "owningPid");

                            AppInfo appInfo = ProcessTracker.getInstance().getByPid(owningPid);
                            if (appInfo != null && appInfo.state == AppState.FROZEN) {
                                Logger.d("Blocking broadcast to frozen app: "
                                    + appInfo.packageName + " pid=" + owningPid);
                                param.setResult(null);
                            }
                        } catch (Throwable t) {
                            Logger.e("Broadcast delivery hook error", t);
                        }
                    }
                });
            Logger.i("Hooked deliverToRegisteredReceiver");
        } catch (Throwable t) {
            Logger.e("Failed to hook broadcast delivery", t);
        }
    }

    private static void hookScheduleBroadcasts(ClassLoader classLoader) {
        try {
            Class<?> broadcastQueueClass = XposedHelpers.findClass(
                "com.android.server.am.BroadcastQueue", classLoader);

            XposedHelpers.findAndHookMethod(broadcastQueueClass,
                "processNextBroadcast", boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object queue = param.thisObject;
                            List<?> parallelBroadcasts = (List<?>)
                                XposedHelpers.getObjectField(queue, "mParallelBroadcasts");
                            List<?> orderedBroadcasts = (List<?>)
                                XposedHelpers.getObjectField(queue, "mOrderedBroadcasts");

                            filterFrozenReceivers(parallelBroadcasts);
                            filterFrozenReceivers(orderedBroadcasts);
                        } catch (Throwable t) {
                            Logger.e("processNextBroadcast hook error", t);
                        }
                    }
                });
            Logger.i("Hooked processNextBroadcast");
        } catch (Throwable t) {
            Logger.e("Failed to hook processNextBroadcast", t);
        }
    }

    private static void filterFrozenReceivers(List<?> broadcastList) {
        if (broadcastList == null) return;
        try {
            for (Object record : broadcastList) {
                List<?> receivers = (List<?>) XposedHelpers.getObjectField(record, "receivers");
                if (receivers == null) continue;

                for (Object receiver : receivers) {
                    try {
                        int pid = XposedHelpers.getIntField(receiver, "owningPid");
                        AppInfo appInfo = ProcessTracker.getInstance().getByPid(pid);
                        if (appInfo != null && appInfo.state == AppState.FROZEN) {
                            Logger.d("Found frozen receiver in broadcast queue: " + appInfo.packageName);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            Logger.e("filterFrozenReceivers error", t);
        }
    }
}