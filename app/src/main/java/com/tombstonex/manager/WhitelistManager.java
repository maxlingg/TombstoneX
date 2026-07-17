package com.tombstonex.manager;

import com.tombstonex.util.FileUtils;
import com.tombstonex.util.Logger;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class WhitelistManager {
    private static volatile WhitelistManager instance;
    private volatile Set<String> whiteApps = new HashSet<>();
    private volatile Set<String> whiteProcesses = new HashSet<>();
    private volatile Set<String> blackSystemApps = new HashSet<>();
    private final Object lock = new Object();

    /**
     * 默认不可冻结的系统应用包名
     * 这些应用一旦冻结会导致系统功能异常
     */
    private static final Set<String> DEFAULT_SYSTEM_WHITE;
    static {
        Set<String> tmp = new HashSet<>();
        // 核心系统
        tmp.add("android");
        tmp.add("com.android.systemui");
        tmp.add("com.android.phone");
        tmp.add("com.android.settings");
        // 启动器
        tmp.add("com.android.launcher");
        tmp.add("com.android.launcher3");
        tmp.add("com.google.android.googlequicksearchbox"); // 桌面搜索
        // 输入法
        tmp.add("com.android.inputmethod");
        tmp.add("com.google.android.inputmethod.latin");
        // 无障碍服务
        tmp.add("com.android.accessibilityservice");
        // NFC 与蓝牙
        tmp.add("com.android.nfc");
        tmp.add("com.android.bluetooth");
        // 壁纸与主题
        tmp.add("com.android.wallpaper");
        tmp.add("com.android.wallpaperbackup");
        // 设备管理
        tmp.add("com.android.deviceadmin");
        // 系统更新
        tmp.add("com.android.systemupdate");
        tmp.add("com.google.android.gms"); // Google 服务
        tmp.add("com.google.android.gsf"); // Google 服务框架
        DEFAULT_SYSTEM_WHITE = Collections.unmodifiableSet(tmp);
    }

    private WhitelistManager() {
        reload();
    }

    // P3-R6: 使用双重检查锁定
    public static WhitelistManager getInstance() {
        WhitelistManager local = instance;
        if (local == null) {
            synchronized (WhitelistManager.class) {
                local = instance;
                if (local == null) {
                    local = new WhitelistManager();
                    instance = local;
                }
            }
        }
        return local;
    }

    public void reload() {
        synchronized (lock) {
            whiteApps = FileUtils.readLines("whiteApp.conf");
            whiteProcesses = FileUtils.readLines("whiteProcess.conf");
            blackSystemApps = FileUtils.readLines("blackSystemApp.conf");
        }
        Logger.d("Whitelist reloaded: whiteApps=" + whiteApps.size()
            + " whiteProcesses=" + whiteProcesses.size()
            + " blackSystemApps=" + blackSystemApps.size());
    }

    /**
     * 判断一个应用/进程是否应该被冻结
     *
     * @param packageName 包名
     * @param processName 进程名（可能与包名不同，如 :pushservice 子进程）
     * @param isSystemApp 是否是系统应用
     * @return true 表示应该冻结
     */
    public boolean shouldFreeze(String packageName, String processName, boolean isSystemApp) {
        // 系统应用：默认不冻结
        if (isSystemApp) {
            // 默认白名单的系统应用永不可冻结
            if (DEFAULT_SYSTEM_WHITE.contains(packageName)) {
                return false;
            }
            // 非默认白名单的系统应用，需要在黑名单中才冻结
            return blackSystemApps.contains(packageName);
        }

        // 用户应用：检查白名单
        if (whiteApps.contains(packageName)) {
            return false;
        }
        // 检查进程白名单（子进程如 :pushservice）
        if (processName != null && whiteProcesses.contains(processName)) {
            return false;
        }
        return true;
    }

    /**
     * 判断一个包名是否是默认系统白名单成员
     */
    public boolean isDefaultWhite(String packageName) {
        return DEFAULT_SYSTEM_WHITE.contains(packageName);
    }

    // ---- 应用白名单 ----

    public void addWhiteApp(String packageName) {
        synchronized (lock) {
            Set<String> copy = new HashSet<>(whiteApps);
            copy.add(packageName);
            FileUtils.writeLines("whiteApp.conf", copy);
            whiteApps = copy;
        }
    }

    public void removeWhiteApp(String packageName) {
        synchronized (lock) {
            Set<String> copy = new HashSet<>(whiteApps);
            copy.remove(packageName);
            FileUtils.writeLines("whiteApp.conf", copy);
            whiteApps = copy;
        }
    }

    public Set<String> getWhiteApps() {
        return Collections.unmodifiableSet(new HashSet<>(whiteApps));
    }

    // ---- 进程白名单 ----

    public void addWhiteProcess(String processName) {
        synchronized (lock) {
            Set<String> copy = new HashSet<>(whiteProcesses);
            copy.add(processName);
            FileUtils.writeLines("whiteProcess.conf", copy);
            whiteProcesses = copy;
        }
    }

    public void removeWhiteProcess(String processName) {
        synchronized (lock) {
            Set<String> copy = new HashSet<>(whiteProcesses);
            copy.remove(processName);
            FileUtils.writeLines("whiteProcess.conf", copy);
            whiteProcesses = copy;
        }
    }

    public Set<String> getWhiteProcesses() {
        return Collections.unmodifiableSet(new HashSet<>(whiteProcesses));
    }

    // ---- 系统应用冻结名单（黑名单）----

    public void addBlackSystemApp(String packageName) {
        synchronized (lock) {
            Set<String> copy = new HashSet<>(blackSystemApps);
            copy.add(packageName);
            FileUtils.writeLines("blackSystemApp.conf", copy);
            blackSystemApps = copy;
        }
    }

    public void removeBlackSystemApp(String packageName) {
        synchronized (lock) {
            Set<String> copy = new HashSet<>(blackSystemApps);
            copy.remove(packageName);
            FileUtils.writeLines("blackSystemApp.conf", copy);
            blackSystemApps = copy;
        }
    }

    public Set<String> getBlackSystemApps() {
        return Collections.unmodifiableSet(new HashSet<>(blackSystemApps));
    }
}
