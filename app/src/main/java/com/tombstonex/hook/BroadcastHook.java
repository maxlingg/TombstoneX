package com.tombstonex.hook;

import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hook 广播分发事件
 * 屏蔽被冻结应用接收广播（动态+静态），避免触发 ANR
 */
public class BroadcastHook {

    // R8-M3: 频率限制，避免 processNextBroadcast 热路径上的全量扫描（O(n×m)）
    // R10-m-2: 改为 AtomicLong 并使用 CAS，消除 check-then-act 竞态
    // M-7: 使用 WeakHashMap<Object, AtomicLong> 以 BroadcastQueue 实例为 key，
    // 实现实例级频率限制，避免多 BroadcastQueue 实例共享全局频率限制导致互相抑制
    private static final Map<Object, AtomicLong> lastScanTimes =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final long SCAN_INTERVAL_MS = 5000L;

    // M-6: ResolveInfo 级冻结哨兵值，表示该接收器所属 uid 的所有进程均已冻结，
    // 但不返回具体 pid（因为 ResolveInfo 没有真实 pid），调用方据此决定是否跳过
    private static final int UID_FROZEN_SENTINEL = -2;

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

                                int owningPid;
                                try {
                                    owningPid = XposedHelpers.getIntField(filter, "owningPid");
                                } catch (Throwable fieldEx) {
                                    // M-1: Android 12+ 字段名可能改用 mOwningPid，回退尝试
                                    try {
                                        owningPid = XposedHelpers.getIntField(filter, "mOwningPid");
                                    } catch (Throwable fieldEx2) {
                                        // R8-M10: 字段读取失败时 fail-open（广播被投递），记录警告
                                        Logger.w("无法读取 owningPid/mOwningPid 字段，广播将被投递: " + fieldEx2.getMessage());
                                        return;
                                    }
                                }
                                AppInfo appInfo = ProcessTracker.getInstance().getByPid(owningPid);
                                if (appInfo != null && appInfo.getState() == AppState.FROZEN) {
                                    Logger.d("拦截已冻结应用的广播: "
                                        + appInfo.packageName + " pid=" + owningPid);
                                    // 在 beforeHookedMethod 中 setResult 跳过冻结接收器
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                Logger.e("广播分发 Hook 出错", t);
                            }
                        }
                    });
                    Logger.i("已 Hook deliverToRegisteredReceiver");
                    break;
                } catch (Throwable e) {
                    Logger.d("Hook 变体失败: " + e.getMessage());
                }
            }
        } catch (Throwable t) {
            Logger.e("Hook 广播分发失败", t);
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
                                // M-7: 以 BroadcastQueue 实例为 key 获取实例级频率限制器，
                                // 避免多 BroadcastQueue 实例共享全局频率限制导致互相抑制
                                AtomicLong lastScanTime;
                                synchronized (lastScanTimes) {
                                    lastScanTime = lastScanTimes.get(queue);
                                    if (lastScanTime == null) {
                                        lastScanTime = new AtomicLong(0);
                                        lastScanTimes.put(queue, lastScanTime);
                                    }
                                }
                                // R9-S2: 频率限制在 beforeHookedMethod 级别，通过后一次性扫描所有三个字段。
                                // 旧代码在 checkAndSkipFrozenReceivers 内部做频率限制，第一次调用
                                // （mParallelBroadcasts）更新 lastScanTime 后，同一 processNextBroadcast
                                // 调用中的第二、三次调用（mOrderedBroadcasts、mPendingBroadcasts）看到
                                // 时间差≈0 而被跳过，导致每5秒仅扫描 mParallelBroadcasts 一次。
                                long now = System.currentTimeMillis();
                                long oldTime = lastScanTime.get();
                                if (now - oldTime < SCAN_INTERVAL_MS) return;
                                // R10-m-2: CAS 保证仅一个线程通过频率限制窗口
                                if (!lastScanTime.compareAndSet(oldTime, now)) return;
                                // R11-m-6: CAS 成功后若扫描失败（如字段不存在或异常），
                                // 由于 lastScanTime 已更新，下一次扫描将延迟 5 秒。
                                // 此为可接受的行为：三字段同时失败的概率极低，
                                // 且 5 秒延迟不会导致冻结接收器长期滞留（下一窗口仍会重试）。
                                // 检查并行广播队列中的接收器
                                checkAndSkipFrozenReceivers(queue, "mParallelBroadcasts");
                                // 检查有序广播队列中的接收器
                                checkAndSkipFrozenReceivers(queue, "mOrderedBroadcasts");
                                // R8-m8: 检查待处理广播队列（部分版本存在此字段）
                                checkAndSkipFrozenReceivers(queue, "mPendingBroadcasts");
                            } catch (Throwable t) {
                                Logger.e("processNextBroadcast Hook 出错", t);
                            }
                        }
                    });
                    Logger.i("已 Hook processNextBroadcast (" + paramTypes.length + " 个参数)");
                    hooked = true;
                    break;
                } catch (Throwable e) {
                    Logger.d("Hook 变体失败: " + e.getMessage());
                }
            }

            if (!hooked) {
                Logger.w("未找到已知签名的 processNextBroadcast");
            }
        } catch (Throwable t) {
            Logger.e("Hook processNextBroadcast 失败", t);
        }
    }

    /**
     * 检查广播队列中的接收器，移除目标进程已冻结的接收器以避免 ANR
     * 兼容 ArrayList 和 CopyOnWriteArrayList 等不同 List 实现
     *
     * R8-M3: processNextBroadcast 是热路径，全量扫描复杂度 O(n×m)。
     * 添加频率限制（每 SCAN_INTERVAL_MS 最多扫描一次），避免高频调用下性能退化。
     * R9-S2: 频率限制已移至 beforeHookedMethod 级别，此处不再做频率检查，
     * 确保 mParallelBroadcasts、mOrderedBroadcasts、mPendingBroadcasts 在同一窗口内
     * 都能被扫描。
     */
    private static void checkAndSkipFrozenReceivers(Object queue, String fieldName) {
        // R10-M-5: 直接修改 BroadcastRecord.receivers 列表存在风险：
        // AMS 通过 receivers 列表跟踪投递进度（nextReceiver 索引、receiverCount 等），
        // 直接 removeAll 可能导致索引错位或计数字段不一致。
        // R11-m-5: 此操作在 processNextBroadcast 的 beforeHookedMethod 中执行。
        // 在多数 AOSP 版本上，processNextBroadcast 的调用方持有 BroadcastQueue 锁，
        // 但不能保证所有版本都在 beforeHookedMethod 时持锁。
        // 快照遍历 + try-catch 提供了防御性保护。
        // 替代方案：在 deliverToRegisteredReceiver 级别逐个拦截（已有 hookBroadcastDelivery），
        // 但无法覆盖静态注册接收器。当前方案为已知风险下的最优选择。
        // R9-S2: 频率限制已移至调用方（beforeHookedMethod），此处直接扫描
        try {
            List<?> rawBroadcastList = (List<?>) XposedHelpers.getObjectField(queue, fieldName);
            if (rawBroadcastList == null || rawBroadcastList.isEmpty()) return;

            // 创建快照副本，避免遍历 AMS 内部 List 时发生 ConcurrentModificationException
            List<?> broadcastList = Arrays.asList(rawBroadcastList.toArray());

            for (Object record : broadcastList) {
                Object receivers = XposedHelpers.getObjectField(record, "receivers");
                if (receivers == null) continue;
                @SuppressWarnings("unchecked")
                List<Object> rawReceiverList = (List<Object>) receivers;

                // 收集需要移除的接收器（先收集再批量移除，兼容所有 List 实现）
                List<Object> toRemove = new java.util.ArrayList<>();
                for (Object receiver : rawReceiverList) {
                    int pid = getReceiverPid(receiver);
                    if (pid == UID_FROZEN_SENTINEL) {
                        // M-6: ResolveInfo 级冻结哨兵，直接标记跳过（无具体 pid）
                        Logger.i("跳过已冻结的静态接收器（uid 级冻结）");
                        toRemove.add(receiver);
                    } else if (pid > 0) {
                        AppInfo appInfo = ProcessTracker.getInstance().getByPid(pid);
                        if (appInfo != null && appInfo.getState() == AppState.FROZEN) {
                            Logger.i("跳过已冻结的接收器: "
                                + appInfo.packageName + " pid=" + pid);
                            toRemove.add(receiver);
                        }
                    }
                }
                // m-8: removeAll 在 CopyOnWriteArrayList 上为 O(n²)（每次 remove 复制整个数组）。
                // 广播接收器列表通常较短（多数 < 20），此性能特性可接受。
                // 若列表为 ArrayList，removeAll 为 O(n)（使用 batchRemove）。
                if (!toRemove.isEmpty()) {
                    // S-5: 在 removeAll 前获取 nextReceiver 索引，
                    // 统计移除元素中位于 nextReceiver 之前的数量，移除后同步调整索引
                    int nextReceiver = -1;
                    try {
                        nextReceiver = XposedHelpers.getIntField(record, "nextReceiver");
                    } catch (Throwable ignore) {
                        // 部分版本无此字段
                        Logger.d("无法读取 nextReceiver 字段: " + ignore.getMessage());
                    }
                    int removedBeforeNext = 0;
                    if (nextReceiver >= 0) {
                        for (int i = 0; i < nextReceiver && i < rawReceiverList.size(); i++) {
                            if (toRemove.contains(rawReceiverList.get(i))) {
                                removedBeforeNext++;
                            }
                        }
                    }
                    // R8-M2: 批量移除，添加更细粒度的异常处理和诊断日志
                    try {
                        int beforeSize = rawReceiverList.size();
                        rawReceiverList.removeAll(toRemove);
                        if (beforeSize != rawReceiverList.size()) {
                            Logger.d("广播接收器过滤: " + beforeSize + " -> " + rawReceiverList.size());
                        }
                        // S-5: 移除后调整 nextReceiver 和 receiverCount，避免 AMS 索引错乱
                        if (removedBeforeNext > 0 && nextReceiver >= 0) {
                            try {
                                XposedHelpers.setIntField(record, "nextReceiver",
                                    nextReceiver - removedBeforeNext);
                            } catch (Throwable ignore) {
                                // 字段不存在或类型不匹配
                                Logger.d("无法设置 nextReceiver: " + ignore.getMessage());
                            }
                        }
                        try {
                            int receiverCount = XposedHelpers.getIntField(record, "receiverCount");
                            XposedHelpers.setIntField(record, "receiverCount",
                                receiverCount - toRemove.size());
                        } catch (Throwable ignore) {
                            // 部分版本无此字段
                            Logger.d("无法读取 receiverCount 字段: " + ignore.getMessage());
                        }
                    } catch (UnsupportedOperationException e) {
                        // List 不支持 remove（如 unmodifiableList），降级为逐个尝试
                        Logger.w("接收器列表不支持批量移除，尝试逐个移除");
                        for (Object r : toRemove) {
                            try {
                                rawReceiverList.remove(r);
                            } catch (Throwable t2) {
                                Logger.d("无法移除冻结接收器: " + t2.getMessage());
                            }
                        }
                    } catch (Throwable t) {
                        // R8-M2/R9-M4: 可能并发修改，记录详细诊断日志（当前 Throwable catch 已提供降级处理）
                        Logger.w("修改 receivers 列表失败（可能并发修改）: " + t.getMessage());
                    }
                }
            }
        } catch (Throwable t) {
            Logger.e("checkAndSkipFrozenReceivers 出错，字段 " + fieldName, t);
        }
    }

    /**
     * 从接收器对象获取 pid
     * 支持 BroadcastFilter (有 owningPid) 和 ResolveInfo (需嵌套获取 uid)
     *
     * M-6: 对 ResolveInfo 不返回任意 pid，而是返回哨兵值 UID_FROZEN_SENTINEL，
     * 调用方通过哨兵值判断是否跳过该接收器。
     * m-7: 改进日志消息，避免误导性的 "Hook 变体失败"。
     */
    private static int getReceiverPid(Object receiver) {
        // BroadcastFilter（动态注册接收器）：owningPid 即接收进程的真实 pid，
        // 直接返回，由调用方判断该 pid 是否被冻结。
        try {
            return XposedHelpers.getIntField(receiver, "owningPid");
        } catch (Throwable e) {
            // m-7: 不是 BroadcastFilter，尝试 mOwningPid（Android 12+ m-prefix）
            Logger.d("接收器无 owningPid 字段，尝试 mOwningPid: " + e.getMessage());
        }
        // S-1: Android 12+ 中字段名可能改用 mOwningPid
        try {
            return XposedHelpers.getIntField(receiver, "mOwningPid");
        } catch (Throwable e) {
            Logger.d("接收器无 mOwningPid 字段，尝试 ResolveInfo 路径: " + e.getMessage());
        }

        // ResolveInfo（静态接收器）：没有 pid，需通过 uid 查找运行中的进程
        try {
            Object activityInfo = XposedHelpers.getObjectField(receiver, "activityInfo");
            if (activityInfo != null) {
                Object appInfo = XposedHelpers.getObjectField(activityInfo, "applicationInfo");
                if (appInfo != null) {
                    int uid = XposedHelpers.getIntField(appInfo, "uid");
                    // R8-M4: 改为检查同 uid 所有进程是否都已冻结。
                    // 旧逻辑：任一进程冻结就返回其 pid，导致整个 uid 的接收器被移除（过于激进）。
                    // 新逻辑：仅当同 uid 所有进程都已冻结时才拦截，避免误移除部分活跃的接收器。
                    List<AppInfo> uidProcesses = ProcessTracker.getInstance().getByUid(uid);
                    boolean allFrozen = !uidProcesses.isEmpty();
                    for (AppInfo info : uidProcesses) {
                        if (info.getState() != AppState.FROZEN) {
                            allFrozen = false;
                            break;
                        }
                    }
                    // M-6: 不返回任意 pid，而是返回哨兵值，调用方通过哨兵判断是否跳过
                    return allFrozen ? UID_FROZEN_SENTINEL : -1;
                }
            }
        } catch (Throwable e) {
            // m-7: ResolveInfo 路径也失败，记录更清晰的日志
            Logger.d("无法从接收器获取 uid/pid（非 BroadcastFilter 且无 activityInfo）: " + e.getMessage());
        }

        return -1;
    }
}
