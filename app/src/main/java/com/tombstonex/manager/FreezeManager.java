package com.tombstonex.manager;

import com.tombstonex.freezer.*;
import com.tombstonex.model.AppState;
import com.tombstonex.model.FreezeMode;
import com.tombstonex.util.Logger;

public class FreezeManager {
    private static FreezeManager instance;
    private IFreezer currentFreezer;

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
                Logger.w("SystemApi freezer not available, falling back to Signal");
                // fall through
            case CGROUP_V2:
                currentFreezer = new CgroupFreezerV2();
                if (currentFreezer.isAvailable()) break;
                Logger.w("CgroupV2 not available, falling back to Signal");
                // fall through
            case CGROUP_V1:
                currentFreezer = new CgroupFreezerV1();
                if (currentFreezer.isAvailable()) break;
                Logger.w("CgroupV1 not available, falling back to Signal");
                // fall through
            case SIGNAL_20:
                currentFreezer = new SignalFreezer(true);
                break;
            case SIGNAL_19:
            default:
                currentFreezer = new SignalFreezer(false);
                break;
        }
        Logger.i("Selected freezer: " + currentFreezer.getName());
    }

    public boolean freezeProcess(int pid, int uid) {
        if (pid <= 0) return false;
        boolean result = currentFreezer.freeze(pid, uid);
        if (result) {
            ProcessTracker.getInstance().updateState(pid, AppState.FROZEN);
        }
        return result;
    }

    public boolean unfreezeProcess(int pid, int uid) {
        if (pid <= 0) return false;
        boolean result = currentFreezer.unfreeze(pid, uid);
        if (result) {
            ProcessTracker.getInstance().updateState(pid, AppState.BACKGROUND);
        }
        return result;
    }

    public String getCurrentFreezerName() {
        return currentFreezer != null ? currentFreezer.getName() : "None";
    }

    public void reselectFreezer() {
        selectFreezer();
    }
}