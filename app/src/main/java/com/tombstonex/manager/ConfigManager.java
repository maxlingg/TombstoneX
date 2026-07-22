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

    private static volatile ConfigManager instance;
    private volatile FreezeMode freezeMode = FreezeMode.SYSTEM_API;
    private volatile boolean debugEnabled = false;
    private volatile int freezeDelay = 3; // 秒
    private volatile boolean hookANREnabled = true;
    private volatile boolean hookBroadcastEnabled = true;
    private volatile boolean hookWakeLockEnabled = true;
    private volatile boolean hookActivitySwitchEnabled = true;
    private volatile boolean hookScreenStateEnabled = true;
    // L5: 缓存 screenOffDelay / rotationInterval，避免每次读取都读文件
    private volatile int screenOffDelay = 60; // 秒
    private volatile int rotationInterval = 360; // 秒
    // M-16: 缓存全局暂停状态，避免 isGlobalPaused() 每次调用都读文件。
    private volatile boolean cachedGlobalPaused = false;

    private ConfigManager() {
        loadConfig();
    }

    // P3-R6: 使用双重检查锁定，避免每次调用都获取类锁
    public static ConfigManager getInstance() {
        ConfigManager local = instance;
        if (local == null) {
            synchronized (ConfigManager.class) {
                local = instance;
                if (local == null) {
                    local = new ConfigManager();
                    instance = local;
                }
            }
        }
        return local;
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
        } catch (NumberFormatException e) {
            Logger.d("ConfigManager: 解析 freezeDelay 失败: " + e.getMessage());
        }

        // L5: 锁屏后批量冻结延迟（缓存）
        try {
            String delayStr = readFileContent("screen_off_delay");
            if (delayStr != null && !delayStr.isEmpty()) {
                screenOffDelay = Math.max(10, Math.min(300, Integer.parseInt(delayStr.trim())));
            }
        } catch (NumberFormatException e) {
            Logger.d("ConfigManager: 解析 screenOffDelay 失败: " + e.getMessage());
        }

        // L5: 轮番解冻间隔（缓存）
        try {
            String intervalStr = readFileContent("rotation_interval");
            if (intervalStr != null && !intervalStr.isEmpty()) {
                rotationInterval = Math.max(60, Math.min(3600, Integer.parseInt(intervalStr.trim())));
            }
        } catch (NumberFormatException e) {
            Logger.d("ConfigManager: 解析 rotationInterval 失败: " + e.getMessage());
        }

        // Hook 开关
        hookANREnabled = !FileUtils.exists("disable_anr");
        hookBroadcastEnabled = !FileUtils.exists("disable_broadcast");
        hookWakeLockEnabled = !FileUtils.exists("disable_wakelock");
        hookActivitySwitchEnabled = !FileUtils.exists("disable_activity");
        hookScreenStateEnabled = !FileUtils.exists("disable_screen");

        // M-16: 初始化全局暂停缓存
        cachedGlobalPaused = FileUtils.exists("paused");

        Logger.init(debugEnabled);
        Logger.i("配置已加载: mode=" + freezeMode + " debug=" + debugEnabled
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
            Logger.w("读取配置文件失败: " + filename + ": " + e.getMessage());
            return null;
        }
    }

    // 轻微-7: 加 synchronized 防止并发写同一 .tmp 文件时的竞态
    // （两个线程同时写 filename.tmp 再 move，可能导致内容错乱或 move 失败）。
    private synchronized boolean writeFileContent(String filename, String content) {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, filename);
        File tmpFile = new File(dir, filename + ".tmp");
        try {
            Files.write(tmpFile.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // 原子替换
            Files.move(tmpFile.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            Logger.e("写入配置失败: " + filename);
            if (!tmpFile.delete()) {
                Logger.w("清理临时文件失败: " + tmpFile.getPath());
            }
            return false;
        }
    }

    public FreezeMode getFreezeMode() { return freezeMode; }

    public synchronized void setFreezeMode(FreezeMode mode) {
        // P7-R7: markers 数组顺序与 loadConfig 优先级一致（freezer.api > freezer.v2
        // > freezer.v1 > kill.20 > kill.19）。一致性可避免在高→低优先级切换时
        // 因旧 marker 残留导致重启后 loadConfig 误读为高优先级旧模式。
        String[] markers = {"freezer.api", "freezer.v2", "freezer.v1", "kill.20", "kill.19"};
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

        // P2-N1 / L5: 先写新 marker 到临时文件，原子 rename 为目标 marker，
        // 然后再删除旧 marker。这样即使删除旧 marker 部分失败，也只是遗留旧文件，
        // loadConfig() 按优先级顺序读取，新 marker 优先级正确即可；不会出现
        // "旧 marker 已删、新 marker 未写入" 的真空状态导致回退到默认模式。
        File tmpFile = new File(dir, targetMarker + ".tmp");
        File targetFile = new File(dir, targetMarker);
        try {
            Files.write(tmpFile.toPath(), new byte[0]);
        } catch (IOException e) {
            Logger.e("写入 marker 临时文件失败: " + targetMarker);
            return;
        }

        // L5: 先 rename 临时文件为目标 marker（原子操作，确保新模式立即生效）
        try {
            Files.move(tmpFile.toPath(), targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Logger.e("重命名 marker 文件失败: " + targetMarker);
            tmpFile.delete();
            return;
        }

        // P7-R7: 再删除旧 markers。
        // 注意：仅靠"新 marker 优先级正确"不能完全保证正确性——若高→低优先级切换时
        // 旧的高优先级 marker 未删除成功，重启后 loadConfig 会优先读到旧的高优先级
        // marker 而加载错误模式。因此删除失败时需重试一次，仍失败才记录 warning。
        for (String marker : markers) {
            if (marker.equals(targetMarker)) continue;
            File oldMarker = new File(CONFIG_DIR + "/" + marker);
            if (oldMarker.exists() && !oldMarker.delete()) {
                // P7-R7: 删除失败时重试一次，避免瞬时 IO 故障导致旧 marker 残留
                Logger.w("setFreezeMode: 删除旧 marker 文件失败，重试: " + marker);
                if (!oldMarker.delete()) {
                    Logger.e("setFreezeMode: 重试删除旧 marker 仍失败: " + marker
                        + "，重启后可能加载错误模式");
                }
            }
        }

        // 文件操作全部成功后更新内存
        this.freezeMode = mode;
        // M-15 锁顺序: ConfigManager 锁 → FreezeManager.freezeLock。
        // 调用方持有 ConfigManager 锁（本方法为 synchronized），
        // reselectFreezer() 内部会获取 FreezeManager.freezeLock。
        // 警告：不得反向获取（先 freezeLock 再 ConfigManager），否则死锁。
        FreezeManager.getInstance().reselectFreezer();
    }

    public boolean isDebugEnabled() { return debugEnabled; }

    // S-5: synchronized 保证文件写入与 volatile 字段更新在同一临界区内完成，避免非原子竞态。
    public synchronized void setDebugEnabled(boolean enabled) {
        boolean success;
        if (enabled) {
            success = writeFileContent("debug", "");
        } else {
            File debugFile = new File(CONFIG_DIR, "debug");
            success = !debugFile.exists() || debugFile.delete();
        }
        if (success) {
            this.debugEnabled = enabled;
            Logger.init(enabled);
        }
    }

    public int getFreezeDelay() { return freezeDelay; }

    // S-5: synchronized 保证文件写入与 volatile 字段更新在同一临界区内完成。
    public synchronized void setFreezeDelay(int seconds) {
        int clamped = Math.max(1, Math.min(10, seconds));
        if (writeFileContent("freeze_delay", String.valueOf(clamped))) {
            // 文件写入成功后再更新内存
            this.freezeDelay = clamped;
        }
    }

    /**
     * 获取锁屏后批量冻结延迟（秒）
     * L5: 返回内存缓存，启动时从文件加载，避免每次读文件。
     */
    public int getScreenOffDelay() {
        return screenOffDelay;
    }

    /**
     * 获取轮番解冻间隔（秒），默认 360 秒（6 分钟）。
     * 供 {@link RotationThawManager} 使用。
     * L5: 返回内存缓存，启动时从文件加载，避免每次读文件。
     */
    public int getRotationInterval() {
        return rotationInterval;
    }

    /**
     * 设置轮番解冻间隔（秒），范围 [60, 3600]。
     * L5: 文件写入成功后同步更新内存缓存。
     *
     * P7-R7: 返回值改为 boolean，供服务端（TombstoneXService）跟踪持久化结果。
     *
     * @return true 表示文件写入成功并已更新内存缓存；false 表示写入失败
     */
    // S-5: synchronized 保证文件写入与 volatile 字段更新在同一临界区内完成。
    public synchronized boolean setRotationInterval(int seconds) {
        int clamped = Math.max(60, Math.min(3600, seconds));
        if (writeFileContent("rotation_interval", String.valueOf(clamped))) {
            rotationInterval = clamped;
            Logger.i("轮番解冻间隔已设置为 " + clamped + "s");
            return true;
        }
        return false;
    }

    public boolean isHookANREnabled() { return hookANREnabled; }
    // S-5: synchronized 保证文件写入与 volatile 字段更新在同一临界区内完成。
    public synchronized void setHookANREnabled(boolean enabled) {
        if (toggleConfig("disable_anr", !enabled)) {
            this.hookANREnabled = enabled;
        }
    }

    public boolean isHookBroadcastEnabled() { return hookBroadcastEnabled; }
    // S-5: synchronized 保证文件写入与 volatile 字段更新在同一临界区内完成。
    public synchronized void setHookBroadcastEnabled(boolean enabled) {
        if (toggleConfig("disable_broadcast", !enabled)) {
            this.hookBroadcastEnabled = enabled;
        }
    }

    public boolean isHookWakeLockEnabled() { return hookWakeLockEnabled; }
    // S-5: synchronized 保证文件写入与 volatile 字段更新在同一临界区内完成。
    public synchronized void setHookWakeLockEnabled(boolean enabled) {
        if (toggleConfig("disable_wakelock", !enabled)) {
            this.hookWakeLockEnabled = enabled;
        }
    }

    public boolean isHookActivitySwitchEnabled() { return hookActivitySwitchEnabled; }
    // S-5: synchronized 保证文件写入与 volatile 字段更新在同一临界区内完成。
    public synchronized void setHookActivitySwitchEnabled(boolean enabled) {
        if (toggleConfig("disable_activity", !enabled)) {
            this.hookActivitySwitchEnabled = enabled;
        }
    }

    public boolean isHookScreenStateEnabled() { return hookScreenStateEnabled; }
    // S-5: synchronized 保证文件写入与 volatile 字段更新在同一临界区内完成。
    public synchronized void setHookScreenStateEnabled(boolean enabled) {
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
            // P3-N2: 使用 writeFileContent 创建 marker 文件，而非 appendLine。
            // marker 文件只需"存在/不存在"语义，appendLine 会追加空行导致文件增长。
            return writeFileContent(filename, "");
        } else {
            if (file.exists()) {
                return file.delete();
            }
            return true;
        }
    }

    // ---- 全局暂停 ----

    /**
     * 检查是否处于全局暂停状态（通过内存缓存，启动时加载）。
     * 文件标记作为跨进程同步的兜底：当缓存值变化时同步读取文件确认。
     */
    public boolean isGlobalPaused() {
        // M-16: 优先返回内存缓存，避免每次调用都读文件。
        // 文件读取作为跨进程同步的兜底（当缓存值变化时同步读取文件）。
        boolean cached = cachedGlobalPaused;
        boolean fileState = FileUtils.exists("paused");
        // 缓存与文件不一致时（如另一进程修改了文件），同步缓存并返回文件状态
        if (cached != fileState) {
            cachedGlobalPaused = fileState;
            return fileState;
        }
        return cached;
    }

    /**
     * 设置全局暂停状态
     * @param paused true=暂停（停止冻结），false=恢复
     *
     * P7-R7: 返回值改为 boolean，供服务端（TombstoneXService）跟踪持久化结果。
     *
     * @return true 表示文件操作成功；false 表示失败
     */
    // S-5 / M-16: synchronized 保证文件写入与字段更新原子性，并更新缓存。
    public synchronized boolean setGlobalPaused(boolean paused) {
        boolean success;
        if (paused) {
            success = writeFileContent("paused", "1");
        } else {
            File pausedFile = new File(CONFIG_DIR, "paused");
            success = !pausedFile.exists() || pausedFile.delete();
        }
        if (success) {
            cachedGlobalPaused = paused;
            Logger.i("全局暂停: " + paused);
        }
        return success;
    }
}
