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
    private static ProcessTracker instance;
    private final Map<Integer, AppInfo> processMap = new ConcurrentHashMap<>();
    private final Map<String, List<Integer>> packageToPids = new ConcurrentHashMap<>();
    private final Map<Integer, List<Integer>> uidToPids = new ConcurrentHashMap<>();

    private ProcessTracker() {}

    public static synchronized ProcessTracker getInstance() {
        if (instance == null) {
            instance = new ProcessTracker();
        }
        return instance;
    }

    public synchronized void registerProcess(String packageName, String processName, int pid, int uid, boolean isSystemApp) {
        // 先检查 processMap 是否已有该 pid，若有则先清理旧映射
        AppInfo oldInfo = processMap.get(pid);
        if (oldInfo != null) {
            removeProcessFromMaps(pid, oldInfo);
        }

        AppInfo info = new AppInfo(packageName, processName, pid, uid);
        info.isSystemApp = isSystemApp;
        info.isWhiteListed = !WhitelistManager.getInstance().shouldFreeze(
            packageName, processName, isSystemApp);
        processMap.put(pid, info);

        // 多进程支持 — 使用 CopyOnWriteArrayList 保证并发安全
        packageToPids.computeIfAbsent(packageName, k -> new CopyOnWriteArrayList<>()).add(pid);
        uidToPids.computeIfAbsent(uid, k -> new CopyOnWriteArrayList<>()).add(pid);

        Logger.d("Process registered: " + processName + " pid=" + pid + " uid=" + uid
            + " system=" + isSystemApp + " white=" + info.isWhiteListed);
    }

    public void updateState(int pid, AppState state) {
        AppInfo info = processMap.get(pid);
        if (info != null) {
            info.state = state;
            if (state == AppState.FROZEN) {
                info.freezeTimestamp = System.currentTimeMillis();
            }
        }
    }

    public void updateOomAdj(int pid, int oomAdj) {
        AppInfo info = processMap.get(pid);
        if (info != null) {
            info.oomAdj = oomAdj;
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

    public void removeProcess(int pid) {
        AppInfo info = processMap.remove(pid);
        if (info != null) {
            removeProcessFromMaps(pid, info);
            Logger.d("Process removed: " + info.processName + " pid=" + pid);
        } else {
            // 无条件扫描清理三个映射表，防止残留
            for (Map.Entry<String, List<Integer>> entry : packageToPids.entrySet()) {
                if (entry.getValue().remove(Integer.valueOf(pid)) && entry.getValue().isEmpty()) {
                    packageToPids.remove(entry.getKey());
                }
            }
            for (Map.Entry<Integer, List<Integer>> entry : uidToPids.entrySet()) {
                if (entry.getValue().remove(Integer.valueOf(pid)) && entry.getValue().isEmpty()) {
                    uidToPids.remove(entry.getKey());
                }
            }
        }
    }

    public AppInfo getByPid(int pid) {
        return processMap.get(pid);
    }

    public AppInfo getByPackage(String packageName) {
        List<Integer> pids = packageToPids.get(packageName);
        if (pids == null || pids.isEmpty()) return null;
        return processMap.get(pids.get(0));
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
            if (info.state == AppState.FROZEN) count++;
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
            if (info.state == AppState.FROZEN) result.add(info);
        }
        return result;
    }

    /**
     * 解冻所有冻结的进程
     */
    public void unfreezeAll(FreezeManager freezer) {
        for (AppInfo info : new ArrayList<>(processMap.values())) {
            if (info.state == AppState.FROZEN) {
                freezer.unfreezeProcess(info.pid, info.uid);
            }
        }
    }

    /**
     * 清空所有进程记录
     */
    public void clear() {
        processMap.clear();
        packageToPids.clear();
        uidToPids.clear();
        Logger.i("ProcessTracker cleared all records");
    }
}
