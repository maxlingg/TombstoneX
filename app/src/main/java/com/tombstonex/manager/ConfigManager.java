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
    private int freezeDelay = 3; // 秒
    private boolean hookANREnabled = true;
    private boolean hookBroadcastEnabled = true;
    private boolean hookWakeLockEnabled = true;
    private boolean hookActivitySwitchEnabled = true;
    private boolean hookScreenStateEnabled = true;

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

        // 冻结延迟
        try {
            String delayStr = readFileContent("freeze_delay");
            if (delayStr != null && !delayStr.isEmpty()) {
                freezeDelay = Math.max(1, Math.min(10, Integer.parseInt(delayStr.trim())));
            }
        } catch (Exception ignored) {}

        // Hook 开关
        hookANREnabled = !FileUtils.exists("disable_anr");
        hookBroadcastEnabled = !FileUtils.exists("disable_broadcast");
        hookWakeLockEnabled = !FileUtils.exists("disable_wakelock");
        hookActivitySwitchEnabled = !FileUtils.exists("disable_activity");
        hookScreenStateEnabled = !FileUtils.exists("disable_screen");

        Logger.init(debugEnabled);
        Logger.i("Config loaded: mode=" + freezeMode + " debug=" + debugEnabled
            + " delay=" + freezeDelay + "s"
            + " anr=" + hookANREnabled + " broadcast=" + hookBroadcastEnabled
            + " wakelock=" + hookWakeLockEnabled + " activity=" + hookActivitySwitchEnabled
            + " screen=" + hookScreenStateEnabled);
    }

    private String readFileContent(String filename) {
        File file = new File(CONFIG_DIR, filename);
        if (!file.exists()) return null;
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath())).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private void writeFileContent(String filename, String content) {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, filename);
        try {
            java.nio.file.Files.write(file.toPath(), content.getBytes());
        } catch (Exception e) {
            Logger.e("Failed to write config: " + filename, e);
        }
    }

    public FreezeMode getFreezeMode() { return freezeMode; }

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
        FreezeManager.getInstance().reselectFreezer();
    }

    public boolean isDebugEnabled() { return debugEnabled; }

    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
        Logger.init(enabled);
        if (enabled) {
            FileUtils.appendLine("debug", "");
        } else {
            new File(CONFIG_DIR + "/debug").delete();
        }
    }

    public int getFreezeDelay() { return freezeDelay; }

    public void setFreezeDelay(int seconds) {
        this.freezeDelay = Math.max(1, Math.min(10, seconds));
        writeFileContent("freeze_delay", String.valueOf(this.freezeDelay));
    }

    public boolean isHookANREnabled() { return hookANREnabled; }
    public void setHookANREnabled(boolean enabled) {
        this.hookANREnabled = enabled;
        toggleConfig("disable_anr", !enabled);
    }

    public boolean isHookBroadcastEnabled() { return hookBroadcastEnabled; }
    public void setHookBroadcastEnabled(boolean enabled) {
        this.hookBroadcastEnabled = enabled;
        toggleConfig("disable_broadcast", !enabled);
    }

    public boolean isHookWakeLockEnabled() { return hookWakeLockEnabled; }
    public void setHookWakeLockEnabled(boolean enabled) {
        this.hookWakeLockEnabled = enabled;
        toggleConfig("disable_wakelock", !enabled);
    }

    public boolean isHookActivitySwitchEnabled() { return hookActivitySwitchEnabled; }
    public void setHookActivitySwitchEnabled(boolean enabled) {
        this.hookActivitySwitchEnabled = enabled;
        toggleConfig("disable_activity", !enabled);
    }

    public boolean isHookScreenStateEnabled() { return hookScreenStateEnabled; }
    public void setHookScreenStateEnabled(boolean enabled) {
        this.hookScreenStateEnabled = enabled;
        toggleConfig("disable_screen", !enabled);
    }

    private void toggleConfig(String filename, boolean create) {
        File file = new File(CONFIG_DIR, filename);
        if (create) {
            FileUtils.appendLine(filename, "");
        } else {
            file.delete();
        }
    }

    // ---- 全局暂停 ----

    /**
     * 检查是否处于全局暂停状态（通过文件标记，跨进程同步）
     * 暂停时 FreezeManager 不执行新的冻结操作
     */
    public boolean isGlobalPaused() {
        return FileUtils.exists("paused");
    }

    /**
     * 设置全局暂停状态
     * @param paused true=暂停（停止冻结），false=恢复
     */
    public void setGlobalPaused(boolean paused) {
        if (paused) {
            FileUtils.appendLine("paused", "");
        } else {
            new File(CONFIG_DIR, "paused").delete();
        }
        Logger.i("Global paused: " + paused);
    }
}