package com.tombstonex.manager;

import com.tombstonex.model.FreezeMode;
import com.tombstonex.util.FileUtils;
import com.tombstonex.util.Logger;
import java.io.File;

public class ConfigManager {
    private static final String CONFIG_DIR = "/data/system/TombstoneX";

    private static ConfigManager instance;
    private FreezeMode freezeMode = FreezeMode.SYSTEM_API;
    private boolean debugEnabled = false;

    private ConfigManager() {
        loadConfig();
    }

    public static synchronized ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    private void loadConfig() {
        if (FileUtils.exists("freezer.api")) {
            freezeMode = FreezeMode.SYSTEM_API;
        } else if (FileUtils.exists("freezer.v2")) {
            freezeMode = FreezeMode.CGROUP_V2;
        } else if (FileUtils.exists("freezer.v1")) {
            freezeMode = FreezeMode.CGROUP_V1;
        } else if (FileUtils.exists("kill.20")) {
            freezeMode = FreezeMode.SIGNAL_20;
        } else if (FileUtils.exists("kill.19")) {
            freezeMode = FreezeMode.SIGNAL_19;
        }

        debugEnabled = FileUtils.exists("debug");
        Logger.i("Config loaded: mode=" + freezeMode + " debug=" + debugEnabled);
    }

    public FreezeMode getFreezeMode() {
        return freezeMode;
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public void setFreezeMode(FreezeMode mode) {
        this.freezeMode = mode;
        String[] markers = {"freezer.api", "freezer.v2", "freezer.v1", "kill.19", "kill.20"};
        for (String marker : markers) {
            new File(CONFIG_DIR + "/" + marker).delete();
        }
        switch (mode) {
            case SYSTEM_API: FileUtils.appendLine("freezer.api", ""); break;
            case CGROUP_V2:  FileUtils.appendLine("freezer.v2", ""); break;
            case CGROUP_V1:  FileUtils.appendLine("freezer.v1", ""); break;
            case SIGNAL_19:  FileUtils.appendLine("kill.19", ""); break;
            case SIGNAL_20:  FileUtils.appendLine("kill.20", ""); break;
        }
    }

    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
        if (enabled) {
            FileUtils.appendLine("debug", "");
        } else {
            new File(CONFIG_DIR + "/debug").delete();
        }
    }
}