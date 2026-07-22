package com.tombstonex.manager;

import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ProcessTracker {
    private static volatile ProcessTracker instance;
    private final Map<Integer, AppInfo> processMap = new ConcurrentHashMap<>();
    private final Map<String, List<Integer>> packageToPids = new ConcurrentHashMap<>();
    private final Map<Integer, List<Integer>> uidToPids = new ConcurrentHashMap<>();

    private ProcessTracker() {}

    // P3-R6: 使用双重检查锁定
    public static ProcessTracker getInstance() {
        ProcessTracker local = instance;
        if (local == null) {
            synchronized (ProcessTracker.class) {
                local = instance;
                if (local == null) {
                    local = new ProcessTracker();
                    instance = local;
                }
            }
        }
        return local;
    }

    public synchronized void registerProcess(String packageName, String processName, int pid, int uid, boolean isSystemApp) {
        // M-7/M-8/M-9: synchronized 块内调用外部方法（WhitelistManager.shouldFreeze、
        // ScheduledFreezeManager.clearLastFreezeTime）。当前调用链安全：被调用方法
        // 只操作 ConcurrentHashMap，不获取任何锁。但未来若这些方法引入锁，可能产生
        // 锁顺序依赖，需注意 ProcessTracker → WhitelistManager / ScheduledFreezeManager
        // 的锁顺序。
        // 先检查 processMap 是否已有该 pid，若有则先清理旧映射
        AppInfo oldInfo = processMap.get(pid);
        if (oldInfo != null) {
            removeProcessFromMaps(pid, oldInfo);
            // 中等-1: pid 复用检测 —— 只要该 pid 曾被任何进程占用（无论 uid 是否相同），
            // 都清除 ScheduledFreezeManager 中残留的 lastFreezeTime 条目。
            // 同一应用（相同 uid）崩溃后以相同 pid 重启时，oldInfo.uid == uid，
            // 旧的 lastFreezeTime 仍会残留，导致新进程在 MIN_REFREEZE_INTERVAL_MS
            // 窗口内被误跳过冻结，因此条件从 oldInfo.uid != uid 放宽为 oldInfo != null。
            ScheduledFreezeManager.getInstance().clearLastFreezeTime(pid);
        }

        AppInfo info = new AppInfo(packageName, processName, pid, uid);
        info.setSystemApp(isSystemApp);
        // @Deprecated: isWhiteListed 字段已弃用，请使用 WhitelistManager.shouldFreeze()
        info.setWhiteListed(!WhitelistManager.getInstance().shouldFreeze(
            packageName, processName, isSystemApp));
        processMap.put(pid, info);

        // 多进程支持 — 使用 CopyOnWriteArrayList 保证并发安全
        packageToPids.computeIfAbsent(packageName, k -> new CopyOnWriteArrayList<>()).add(pid);
        uidToPids.computeIfAbsent(uid, k -> new CopyOnWriteArrayList<>()).add(pid);

        Logger.d("进程已注册: " + processName + " pid=" + pid + " uid=" + uid
            + " system=" + isSystemApp + " white=" + info.isWhiteListed());
    }

    public synchronized void updateState(int pid, AppState state) {
        AppInfo info = processMap.get(pid);
        if (info != null) {
            info.setState(state);
            if (state == AppState.FROZEN) {
                info.setFreezeTimestamp(System.currentTimeMillis());
            }
        }
    }

    public synchronized void updateOomAdj(int pid, int oomAdj) {
        AppInfo info = processMap.get(pid);
        if (info != null) {
            info.setOomAdj(oomAdj);
        }
    }

    /**
     * 从三个映射表中移除指定 pid（内部辅助方法）
     */
    private void removeProcessFromMaps(int pid, AppInfo info) {
        // 清理 package 映射
        if (info.packageName != null) {
            List<Integer> pids = packageToPids.get(info.packageName);
            if (pids != null) {
                pids.remove(Integer.valueOf(pid));
                if (pids.isEmpty()) packageToPids.remove(info.packageName);
            }
        }
        // 清理 uid 映射
        List<Integer> uidPids = uidToPids.get(info.uid);
        if (uidPids != null) {
            uidPids.remove(Integer.valueOf(pid));
            if (uidPids.isEmpty()) uidToPids.remove(info.uid);
        }
    }

    public synchronized void removeProcess(int pid) {
        AppInfo info = processMap.remove(pid);
        if (info != null) {
            removeProcessFromMaps(pid, info);
            // 中等-2: 进程死亡时同步清除 ScheduledFreezeManager 中的 lastFreezeTime 条目，
            // 避免新进程在下次 scan 之前复用该 pid 时，retainAll 反而保留了残留条目。
            // clearLastFreezeTime 仅执行 ConcurrentHashMap.remove，不获取任何锁，不会死锁。
            ScheduledFreezeManager.getInstance().clearLastFreezeTime(pid);
            Logger.d("进程已移除: " + info.processName + " pid=" + pid);
        } else {
            // P3-R3: processMap 中已无此 pid（可能双重清理），直接返回避免 O(n) 全表扫描。
            // registerProcess 和 removeProcess 都在 synchronized 块内维护映射一致性，
            // 正常情况下不会出现残留条目。
            Logger.d("在 processMap 中未找到进程，跳过清理: pid=" + pid);
        }
    }

    public AppInfo getByPid(int pid) {
        return processMap.get(pid);
    }

    public AppInfo getByPackage(String packageName) {
        List<Integer> pids = packageToPids.get(packageName);
        if (pids == null || pids.isEmpty()) return null;
        // M3-修复: pids.get(0) 与 isEmpty() 之间非原子，removeProcessFromMaps 可能
        // 在检查后清空列表，导致 IndexOutOfBoundsException。捕获异常并返回 null。
        try {
            return processMap.get(pids.get(0));
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    /**
     * 获取一个包名的所有进程（多进程支持）
     */
    public List<AppInfo> getAllByPackage(String packageName) {
        List<AppInfo> result = new ArrayList<>();
        List<Integer> pids = packageToPids.get(packageName);
        if (pids != null) {
            for (int pid : pids) {
                AppInfo info = processMap.get(pid);
                if (info != null) result.add(info);
            }
        }
        return result;
    }

    /**
     * 获取一个 UID 的所有进程
     */
    public List<AppInfo> getByUid(int uid) {
        List<AppInfo> result = new ArrayList<>();
        List<Integer> pids = uidToPids.get(uid);
        if (pids != null) {
            for (int pid : pids) {
                AppInfo info = processMap.get(pid);
                if (info != null) result.add(info);
            }
        }
        return result;
    }

    public Map<Integer, AppInfo> getAllProcesses() {
        return new ConcurrentHashMap<>(processMap);
    }

    public int getFrozenCount() {
        int count = 0;
        for (AppInfo info : processMap.values()) {
            if (info.getState() == AppState.FROZEN) count++;
        }
        return count;
    }

    public int getTotalCount() {
        return processMap.size();
    }

    /**
     * 获取所有冻结的进程
     */
    public List<AppInfo> getFrozenProcesses() {
        List<AppInfo> result = new ArrayList<>();
        for (AppInfo info : processMap.values()) {
            if (info.getState() == AppState.FROZEN) result.add(info);
        }
        return result;
    }

    /**
     * 解冻所有冻结的进程
     */
    public void unfreezeAll(FreezeManager freezer) {
        for (AppInfo info : new ArrayList<>(processMap.values())) {
            if (info.getState() == AppState.FROZEN) {
                freezer.unfreezeProcess(info.pid, info.uid);
            }
        }
    }

    /**
     * 清空所有进程记录
     */
    public synchronized void clear() {
        processMap.clear();
        packageToPids.clear();
        uidToPids.clear();
        // 轻微-1: 同步清理 ScheduledFreezeManager 中的全部 lastFreezeTime 条目，
        // 避免清空后残留的时间戳对新注册进程造成误判。
        ScheduledFreezeManager.getInstance().clearAllLastFreezeTime();
        Logger.i("ProcessTracker 已清空所有记录");
    }
}
