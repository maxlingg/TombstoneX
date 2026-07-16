package com.tombstonex.manager;

import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    public void registerProcess(String packageName, String processName, int pid, int uid, boolean isSystemApp) {
        AppInfo info = new AppInfo(packageName, processName, pid, uid);
        info.isSystemApp = isSystemApp;
        info.isWhiteListed = !WhitelistManager.getInstance().shouldFreeze(
            packageName, processName, isSystemApp);
        processMap.put(pid, info);

        // 多进程支持
        packageToPids.computeIfAbsent(packageName, k -> new ArrayList<>()).add(pid);
        uidToPids.computeIfAbsent(uid, k -> new ArrayList<>()).add(pid);

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

    public void removeProcess(int pid) {
        AppInfo info = processMap.remove(pid);
        if (info != null) {
            // 清理 package 映射
            List<Integer> pids = packageToPids.get(info.packageName);
            if (pids != null) {
                pids.remove(Integer.valueOf(pid));
                if (pids.isEmpty()) packageToPids.remove(info.packageName);
            }
            // 清理 uid 映射
            List<Integer> uidPids = uidToPids.get(info.uid);
            if (uidPids != null) {
                uidPids.remove(Integer.valueOf(pid));
                if (uidPids.isEmpty()) uidToPids.remove(info.uid);
            }
            Logger.d("Process removed: " + info.processName + " pid=" + pid);
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
}