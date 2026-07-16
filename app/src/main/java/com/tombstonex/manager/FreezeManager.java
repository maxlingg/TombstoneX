package com.tombstonex.manager;

import com.tombstonex.freezer.*;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.model.FreezeMode;
import com.tombstonex.util.Logger;
import java.util.List;

public class FreezeManager {
    private static FreezeManager instance;
    private IFreezer currentFreezer;
    private boolean globalPaused = false;

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
                currentFreezer = new SignalFreezer(true);
                break;
            case SIGNAL_19:
            default:
                currentFreezer = new SignalFreezer(false);
                break;
        }
        Logger.i("Selected freezer: " + currentFreezer.getName()
            + " available=" + currentFreezer.isAvailable());
    }

    public boolean freezeProcess(int pid, int uid) {
        if (pid <= 0) return false;
        // 通过文件标记检查全局暂停状态（跨进程同步）
        if (ConfigManager.getInstance().isGlobalPaused()) {
            globalPaused = true;
            Logger.d("Global paused, skip freeze: pid=" + pid);
            return false;
        }
        globalPaused = false;
        // 冻结去重：检查是否已冻结
        AppInfo info = ProcessTracker.getInstance().getByPid(pid);
        if (info != null && info.state == AppState.FROZEN) {
            Logger.d("Process already frozen, skip: pid=" + pid);
            return true;
        }
        boolean result = currentFreezer.freeze(pid, uid);
        if (result) {
            ProcessTracker.getInstance().updateState(pid, AppState.FROZEN);
        }
        return result;
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
        globalPaused = true;
        ConfigManager.getInstance().setGlobalPaused(true);
        ProcessTracker.getInstance().unfreezeAll(this);
        Logger.i("Global paused, all processes unfrozen");
    }

    /**
     * 恢复全局冻结 — 清除暂停标记文件
     */
    public void resumeAll() {
        globalPaused = false;
        ConfigManager.getInstance().setGlobalPaused(false);
        Logger.i("Global resumed, freeze will continue normally");
    }

    public boolean isGlobalPaused() {
        return globalPaused;
    }

    public String getCurrentFreezerName() {
        return currentFreezer != null ? currentFreezer.getName() : "None";
    }

    public void reselectFreezer() {
        selectFreezer();
    }
}