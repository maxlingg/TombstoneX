package com.tombstonex.manager;

import com.tombstonex.model.FreezeMode;
import com.tombstonex.util.FileUtils;
import com.tombstonex.util.Logger;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class ConfigManager {
    private static final String CONFIG_DIR = "/data/system/TombstoneX";

    private static ConfigManager instance;
    private volatile FreezeMode freezeMode = FreezeMode.SYSTEM_API;
    private volatile boolean debugEnabled = false;
    private volatile int freezeDelay = 3; // 秒
    private volatile boolean hookANREnabled = true;
    private volatile boolean hookBroadcastEnabled = true;
    private volatile boolean hookWakeLockEnabled = true;
    private volatile boolean hookActivitySwitchEnabled = true;
    private volatile boolean hookScreenStateEnabled = true;

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
            return new String(Files.readAllBytes(file.toPath())).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private void writeFileContent(String filename, String content) {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, filename);
        File tmpFile = new File(dir, filename + ".tmp");
        try {
            Files.write(tmpFile.toPath(), content.getBytes());
            // 原子替换
            Files.move(tmpFile.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Logger.e("Failed to write config: " + filename);
        }
    }

    public FreezeMode getFreezeMode() { return freezeMode; }

    public void setFreezeMode(FreezeMode mode) {
        String[] markers = {"freezer.api", "freezer.v2", "freezer.v1", "kill.19", "kill.20"};
        String targetMarker;
        switch (mode) {
            case SYSTEM_API: targetMarker = "freezer.api"; break;
            case CGROUP_V2:  targetMarker = "freezer.v2"; break;
            case CGROUP_V1:  targetMarker = "freezer.v1"; break;
            case SIGNAL_19:  targetMarker = "kill.19"; break;
            case SIGNAL_20:  targetMarker = "kill.20"; break;
            default: targetMarker = "freezer.api"; break;
        }

        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) dir.mkdirs();

        // 先写新 marker 到临时文件，再原子替换
        File tmpFile = new File(dir, targetMarker + ".tmp");
        File targetFile = new File(dir, targetMarker);
        try {
            Files.write(tmpFile.toPath(), new byte[0]);
            Files.move(tmpFile.toPath(), targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Logger.e("Failed to write marker file: " + targetMarker);
            return;
        }

        // 删除旧 markers（除了刚写入的 targetMarker）
        for (String marker : markers) {
            if (marker.equals(targetMarker)) continue;
            new File(CONFIG_DIR + "/" + marker).delete();
        }

        // 文件写入成功后更新内存
        this.freezeMode = mode;
        FreezeManager.getInstance().reselectFreezer();
    }

    public boolean isDebugEnabled() { return debugEnabled; }

    public void setDebugEnabled(boolean enabled) {
        if (enabled) {
            FileUtils.appendLine("debug", "");
        } else {
            new File(CONFIG_DIR + "/debug").delete();
        }
        // 文件写入成功后再更新内存字段
        this.debugEnabled = enabled;
        Logger.init(enabled);
    }

    public int getFreezeDelay() { return freezeDelay; }

    public void setFreezeDelay(int seconds) {
        int clamped = Math.max(1, Math.min(10, seconds));
        writeFileContent("freeze_delay", String.valueOf(clamped));
        // 文件写入成功后再更新内存
        this.freezeDelay = clamped;
    }

    /**
     * 获取锁屏后批量冻结延迟（秒）
     */
    public int getScreenOffDelay() {
        try {
            String delayStr = readFileContent("screen_off_delay");
            if (delayStr != null && !delayStr.isEmpty()) {
                return Math.max(10, Math.min(300, Integer.parseInt(delayStr.trim())));
            }
        } catch (Exception ignored) {}
        return 60; // 默认 60 秒
    }

    public boolean isHookANREnabled() { return hookANREnabled; }
    public void setHookANREnabled(boolean enabled) {
        if (toggleConfig("disable_anr", !enabled)) {
            this.hookANREnabled = enabled;
        }
    }

    public boolean isHookBroadcastEnabled() { return hookBroadcastEnabled; }
    public void setHookBroadcastEnabled(boolean enabled) {
        if (toggleConfig("disable_broadcast", !enabled)) {
            this.hookBroadcastEnabled = enabled;
        }
    }

    public boolean isHookWakeLockEnabled() { return hookWakeLockEnabled; }
    public void setHookWakeLockEnabled(boolean enabled) {
        if (toggleConfig("disable_wakelock", !enabled)) {
            this.hookWakeLockEnabled = enabled;
        }
    }

    public boolean isHookActivitySwitchEnabled() { return hookActivitySwitchEnabled; }
    public void setHookActivitySwitchEnabled(boolean enabled) {
        if (toggleConfig("disable_activity", !enabled)) {
            this.hookActivitySwitchEnabled = enabled;
        }
    }

    public boolean isHookScreenStateEnabled() { return hookScreenStateEnabled; }
    public void setHookScreenStateEnabled(boolean enabled) {
        if (toggleConfig("disable_screen", !enabled)) {
            this.hookScreenStateEnabled = enabled;
        }
    }

    /**
     * 切换配置文件标记
     * @param filename 配置文件名
     * @param create true=创建文件，false=删除文件
     * @return true 如果文件操作成功
     */
    private boolean toggleConfig(String filename, boolean create) {
        File file = new File(CONFIG_DIR, filename);
        if (create) {
            FileUtils.appendLine(filename, "");
            return file.exists();
        } else {
            if (file.exists()) {
                file.delete();
            }
            return !file.exists();
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
            writeFileContent("paused", "1");
        } else {
            new File(CONFIG_DIR, "paused").delete();
        }
        Logger.i("Global paused: " + paused);
    }
}
