package com.tombstonex.manager;

import com.tombstonex.freezer.*;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.model.FreezeMode;
import com.tombstonex.util.Logger;
import java.util.List;
import java.util.ArrayList;

public class FreezeManager {
    private static FreezeManager instance;
    private volatile IFreezer currentFreezer;
    private final Object freezeLock = new Object();

    private FreezeManager() {
        selectFreezer();
    }

    public static synchronized FreezeManager getInstance() {
        if (instance == null) {
            instance = new FreezeManager();
        }
        return instance;
    }

    private void selectFreezer() {
        FreezeMode mode = ConfigManager.getInstance().getFreezeMode();
        Logger.i("Selecting freezer: " + mode);

        switch (mode) {
            case SYSTEM_API:
                currentFreezer = new SystemApiFreezer();
                if (currentFreezer.isAvailable()) break;
                Logger.w("SystemApi freezer not available, falling back to CgroupV2");
            case CGROUP_V2:
                currentFreezer = new CgroupFreezerV2();
                if (currentFreezer.isAvailable()) break;
                Logger.w("CgroupV2 not available, falling back to CgroupV1");
            case CGROUP_V1:
                currentFreezer = new CgroupFreezerV1();
                if (currentFreezer.isAvailable()) break;
                Logger.w("CgroupV1 not available, falling back to Signal");
            case SIGNAL_20:
                currentFreezer = new SignalFreezer(true);  // SIGTSTP=20
                if (currentFreezer.isAvailable()) break;
                // 回退到 SIGNAL_19
            case SIGNAL_19:
                currentFreezer = new SignalFreezer(false);  // SIGSTOP=19
                if (currentFreezer.isAvailable()) break;
                // 最终回退
            default:
                Logger.w("No freezer available");
                currentFreezer = null;
                break;
        }
        if (currentFreezer != null) {
            Logger.i("Selected freezer: " + currentFreezer.getName()
                + " available=" + currentFreezer.isAvailable());
        } else {
            Logger.w("No freezer selected");
        }
    }

    public boolean freezeProcess(int pid, int uid) {
        if (pid <= 0) return false;
        synchronized (freezeLock) {
            // 通过文件标记检查全局暂停状态（跨进程同步）
            if (ConfigManager.getInstance().isGlobalPaused()) {
                Logger.d("Global paused, skip freeze: pid=" + pid);
                return false;
            }

            // 白名单检查作为防御层
            AppInfo info = ProcessTracker.getInstance().getByPid(pid);
            if (info != null) {
                if (!WhitelistManager.getInstance().shouldFreeze(
                        info.packageName, info.processName, info.isSystemApp)) {
                    Logger.d("App in whitelist, skip freeze (defensive): " + info.packageName);
                    return false;
                }
                // 冻结去重：检查是否已冻结
                if (info.state == AppState.FROZEN) {
                    Logger.d("Process already frozen, skip: pid=" + pid);
                    return true;
                }
            }

            boolean result = currentFreezer.freeze(pid, uid);
            if (result) {
                ProcessTracker.getInstance().updateState(pid, AppState.FROZEN);
            }
            return result;
        }
    }

    /**
     * 冻结一个包名的所有进程（多进程支持）
     */
    public int freezePackage(String packageName) {
        int count = 0;
        List<AppInfo> processes = ProcessTracker.getInstance().getAllByPackage(packageName);
        for (AppInfo info : processes) {
            if (info.state != AppState.FROZEN && !info.isWhiteListed) {
                if (freezeProcess(info.pid, info.uid)) count++;
            }
        }
        return count;
    }

    public boolean unfreezeProcess(int pid, int uid) {
        if (pid <= 0) return false;
        synchronized (freezeLock) {
            // 检查是否已解冻
            AppInfo info = ProcessTracker.getInstance().getByPid(pid);
            if (info != null && info.state != AppState.FROZEN) {
                return true;
            }
            boolean result = currentFreezer.unfreeze(pid, uid);
            if (result) {
                ProcessTracker.getInstance().updateState(pid, AppState.BACKGROUND);
            }
            return result;
        }
    }

    /**
     * 解冻一个包名的所有进程
     */
    public int unfreezePackage(String packageName) {
        int count = 0;
        List<AppInfo> processes = ProcessTracker.getInstance().getAllByPackage(packageName);
        for (AppInfo info : processes) {
            if (info.state == AppState.FROZEN) {
                if (unfreezeProcess(info.pid, info.uid)) count++;
            }
        }
        return count;
    }

    /**
     * 全局暂停 — 解冻所有冻结的进程，并写入暂停标记文件
     */
    public void pauseAll() {
        ConfigManager.getInstance().setGlobalPaused(true);
        ProcessTracker.getInstance().unfreezeAll(this);
        Logger.i("Global paused, all processes unfrozen");
    }

    /**
     * 恢复全局冻结 — 清除暂停标记文件
     */
    public void resumeAll() {
        ConfigManager.getInstance().setGlobalPaused(false);
        Logger.i("Global resumed, freeze will continue normally");
    }

    public boolean isGlobalPaused() {
        return ConfigManager.getInstance().isGlobalPaused();
    }

    public String getCurrentFreezerName() {
        return currentFreezer != null ? currentFreezer.getName() : "None";
    }

    public void reselectFreezer() {
        synchronized (freezeLock) {
            // 先解冻所有已冻结的进程，防止切换 freezer 后状态不一致
            unfreezeAllFrozen();
            selectFreezer();
        }
    }

    /**
     * 解冻所有已冻结的进程
     */
    private void unfreezeAllFrozen() {
        List<AppInfo> frozenList = new ArrayList<>(
            ProcessTracker.getInstance().getFrozenProcesses());
        for (AppInfo info : frozenList) {
            if (currentFreezer != null) {
                currentFreezer.unfreeze(info.pid, info.uid);
                ProcessTracker.getInstance().updateState(info.pid, AppState.BACKGROUND);
            }
        }
        Logger.i("Unfroze all frozen processes before reselect: " + frozenList.size());
    }
}
