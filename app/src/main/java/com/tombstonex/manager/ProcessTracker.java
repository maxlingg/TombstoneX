package com.tombstonex.manager;

import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ProcessTracker {
    private static ProcessTracker instance;
    private final Map<Integer, AppInfo> processMap = new ConcurrentHashMap<>();
    private final Map<String, Integer> packageToPid = new ConcurrentHashMap<>();

    private ProcessTracker() {}

    public static synchronized ProcessTracker getInstance() {
        if (instance == null) {
            instance = new ProcessTracker();
        }
        return instance;
    }

    public void registerProcess(String packageName, int pid, int uid, boolean isSystemApp) {
        AppInfo info = new AppInfo(packageName, pid, uid);
        info.isWhiteListed = !WhitelistManager.getInstance().shouldFreeze(
            packageName, packageName, isSystemApp);
        processMap.put(pid, info);
        packageToPid.put(packageName, pid);
        Logger.d("Process registered: " + packageName + " pid=" + pid);
    }

    public void updateState(int pid, AppState state) {
        AppInfo info = processMap.get(pid);
        if (info != null) {
            info.state = state;
            if (state == AppState.FROZEN) {
                info.freezeTimestamp = System.currentTimeMillis();
            }
            Logger.d("Process state updated: pid=" + pid + " state=" + state);
        }
    }

    public void removeProcess(int pid) {
        AppInfo info = processMap.remove(pid);
        if (info != null) {
            packageToPid.remove(info.packageName);
            Logger.d("Process removed: " + info.packageName + " pid=" + pid);
        }
    }

    public AppInfo getByPid(int pid) {
        return processMap.get(pid);
    }

    public AppInfo getByPackage(String packageName) {
        Integer pid = packageToPid.get(packageName);
        return pid != null ? processMap.get(pid) : null;
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
}