package com.tombstonex.hook;

import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
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
                {"com.android.server.am.BroadcastRecord",
                 "com.android.server.am.BroadcastFilter"},
            };

            for (String[] sig : signatures) {
                try {
                    // 构建 Class<?> 数组用于查找方法
                    Class<?>[] paramTypes = new Class[sig.length];
                    for (int i = 0; i < sig.length; i++) {
                        if (sig[i].equals("boolean")) paramTypes[i] = boolean.class;
                        else if (sig[i].equals("int")) paramTypes[i] = int.class;
                        else paramTypes[i] = XposedHelpers.findClass(sig[i], classLoader);
                    }

                    // P1: 使用 findMethodExact 先找到 Method 再 hook，避免 Class<?>[] 数组传参问题
                    Method method = XposedHelpers.findMethodExact(
                        broadcastQueueClass, "deliverToRegisteredReceiver", paramTypes);
                    if (method == null) continue;

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
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
                                    // 在 beforeHookedMethod 中 setResult 跳过冻结接收器
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                Logger.e("Broadcast delivery hook error", t);
                            }
                        }
                    });
                    Logger.i("Hooked deliverToRegisteredReceiver");
                    break;
                } catch (Throwable e) {
                    Logger.d("Hook variant failed: " + e.getMessage());
                }
            }
        } catch (Throwable t) {
            Logger.e("Failed to hook broadcast delivery", t);
        }
    }

    /**
     * Hook BroadcastQueue.processNextBroadcast
     * 支持多种签名兼容，在 beforeHookedMethod 中 setResult 跳过冻结接收器
     */
    private static void hookProcessNextBroadcast(ClassLoader classLoader) {
        try {
            Class<?> broadcastQueueClass = XposedHelpers.findClass(
                "com.android.server.am.BroadcastQueue", classLoader);

            // P1: 增加多种 processNextBroadcast 签名兼容
            Class<?>[][] paramTypeVariants = {
                {boolean.class},
                {},
                {boolean.class, boolean.class},
            };

            boolean hooked = false;
            for (Class<?>[] paramTypes : paramTypeVariants) {
                try {
                    Method method = XposedHelpers.findMethodExact(
                        broadcastQueueClass, "processNextBroadcast", paramTypes);
                    if (method == null) continue;

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object queue = param.thisObject;
                                // 检查并行广播队列中的接收器
                                checkAndSkipFrozenReceivers(queue, "mParallelBroadcasts");
                                // 检查有序广播队列中的接收器
                                checkAndSkipFrozenReceivers(queue, "mOrderedBroadcasts");
                            } catch (Throwable t) {
                                Logger.e("processNextBroadcast hook error", t);
                            }
                        }
                    });
                    Logger.i("Hooked processNextBroadcast with " + paramTypes.length + " params");
                    hooked = true;
                    break;
                } catch (Throwable e) {
                    Logger.d("Hook variant failed: " + e.getMessage());
                }
            }

            if (!hooked) {
                Logger.w("Could not find processNextBroadcast with known signatures");
            }
        } catch (Throwable t) {
            Logger.e("Failed to hook processNextBroadcast", t);
        }
    }

    /**
     * 检查广播队列中的接收器，如果目标进程被冻结则通过 setResult 跳过
     * 不直接修改 receiverList，而是在检测到冻结接收器时记录并跳过
     */
    private static void checkAndSkipFrozenReceivers(Object queue, String fieldName) {
        try {
            List<?> broadcastList = (List<?>) XposedHelpers.getObjectField(queue, fieldName);
            if (broadcastList == null || broadcastList.isEmpty()) return;

            for (Object record : broadcastList) {
                Object receivers = XposedHelpers.getObjectField(record, "receivers");
                if (receivers == null) continue;
                @SuppressWarnings("unchecked")
                List<Object> receiverList = (List<Object>) receivers;

                for (Object receiver : receiverList) {
                    int pid = getReceiverPid(receiver, queue.getClass().getClassLoader());
                    if (pid > 0) {
                        AppInfo appInfo = ProcessTracker.getInstance().getByPid(pid);
                        if (appInfo != null && appInfo.state == AppState.FROZEN) {
                            Logger.d("Frozen receiver detected (will be skipped by delivery hook): "
                                + appInfo.packageName + " pid=" + pid);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Logger.e("checkAndSkipFrozenReceivers error for field " + fieldName, t);
        }
    }

    /**
     * 从接收器对象获取 pid
     * 支持 BroadcastFilter (有 owningPid) 和 ResolveInfo (需嵌套获取 uid)
     */
    private static int getReceiverPid(Object receiver, ClassLoader classLoader) {
        // 尝试 BroadcastFilter.owningPid
        try {
            return XposedHelpers.getIntField(receiver, "owningPid");
        } catch (Throwable e) {
            Logger.d("Hook variant failed: " + e.getMessage());
        }

        // ResolveInfo: 需要分步获取 activityInfo -> applicationInfo -> uid
        try {
            Object activityInfo = XposedHelpers.getObjectField(receiver, "activityInfo");
            if (activityInfo != null) {
                Object appInfo = XposedHelpers.getObjectField(activityInfo, "applicationInfo");
                if (appInfo != null) {
                    int uid = XposedHelpers.getIntField(appInfo, "uid");
                    // 通过 uid 查找冻结的进程
                    for (AppInfo info : ProcessTracker.getInstance().getByUid(uid)) {
                        if (info.state == AppState.FROZEN) {
                            return info.pid;
                        }
                    }
                }
            }
        } catch (Throwable e) {
            Logger.d("Hook variant failed: " + e.getMessage());
        }

        return -1;
    }
}
