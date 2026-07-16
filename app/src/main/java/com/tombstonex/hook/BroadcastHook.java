package com.tombstonex.hook;

import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.List;

/**
 * Hook 广播分发事件
 * 屏蔽被冻结应用接收广播（动态+静态），避免触发 ANR
 */
public class BroadcastHook {

    public static void init(ClassLoader classLoader) {
        hookBroadcastDelivery(classLoader);
        hookProcessNextBroadcast(classLoader);
    }

    /**
     * Hook BroadcastQueue.deliverToRegisteredReceiver
     * 拦截被冻结应用的动态注册广播
     */
    private static void hookBroadcastDelivery(ClassLoader classLoader) {
        try {
            Class<?> broadcastQueueClass = XposedHelpers.findClass(
                "com.android.server.am.BroadcastQueue", classLoader);

            // 尝试多种参数签名
            String[][] signatures = {
                {"com.android.server.am.BroadcastRecord",
                 "com.android.server.am.BroadcastFilter",
                 "boolean", "int"},
                {"com.android.server.am.BroadcastRecord",
                 "com.android.server.am.BroadcastFilter",
                 "int"},
            };

            for (String[] sig : signatures) {
                try {
                    Class<?>[] paramTypes = new Class[sig.length];
                    for (int i = 0; i < sig.length; i++) {
                        if (sig[i].equals("boolean")) paramTypes[i] = boolean.class;
                        else if (sig[i].equals("int")) paramTypes[i] = int.class;
                        else paramTypes[i] = XposedHelpers.findClass(sig[i], classLoader);
                    }
                    XposedHelpers.findAndHookMethod(broadcastQueueClass,
                        "deliverToRegisteredReceiver", paramTypes,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    Object filter = param.args[1];
                                    if (filter == null) return;

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
                    break;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Logger.e("Failed to hook broadcast delivery", t);
        }
    }

    /**
     * Hook BroadcastQueue.processNextBroadcast
     * 过滤被冻结应用的静态广播接收器
     */
    private static void hookProcessNextBroadcast(ClassLoader classLoader) {
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
                            // 检查并行广播
                            List<?> parallelBroadcasts = (List<?>)
                                XposedHelpers.getObjectField(queue, "mParallelBroadcasts");
                            filterBroadcasts(parallelBroadcasts);
                            // 检查有序广播
                            List<?> orderedBroadcasts = (List<?>)
                                XposedHelpers.getObjectField(queue, "mOrderedBroadcasts");
                            filterBroadcasts(orderedBroadcasts);
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

    /**
     * 实际过滤冻结应用的广播接收器
     */
    @SuppressWarnings("unchecked")
    private static void filterBroadcasts(List<?> broadcastList) {
        if (broadcastList == null || broadcastList.isEmpty()) return;
        try {
            for (Object record : broadcastList) {
                Object receivers = XposedHelpers.getObjectField(record, "receivers");
                if (receivers == null) continue;
                List<Object> receiverList = (List<Object>) receivers;

                // 从后往前遍历，移除冻结应用的接收器
                for (int i = receiverList.size() - 1; i >= 0; i--) {
                    Object receiver = receiverList.get(i);
                    int pid = -1;
                    try {
                        // BroadcastFilter 有 owningPid
                        pid = XposedHelpers.getIntField(receiver, "owningPid");
                    } catch (Throwable ignored) {
                        // ResolveInfo 没有 owningPid，获取 uid
                        try {
                            int uid = XposedHelpers.getIntField(receiver, "activityInfo.applicationInfo.uid");
                            // 检查该 uid 是否有冻结的进程
                            for (AppInfo info : ProcessTracker.getInstance().getAllProcesses().values()) {
                                if (info.uid == uid && info.state == AppState.FROZEN) {
                                    pid = info.pid;
                                    break;
                                }
                            }
                        } catch (Throwable ignored2) {}
                    }

                    if (pid > 0) {
                        AppInfo appInfo = ProcessTracker.getInstance().getByPid(pid);
                        if (appInfo != null && appInfo.state == AppState.FROZEN) {
                            receiverList.remove(i);
                            Logger.d("Removed frozen receiver from broadcast: " + appInfo.packageName);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Logger.e("filterBroadcasts error", t);
        }
    }
}