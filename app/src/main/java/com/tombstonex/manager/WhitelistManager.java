package com.tombstonex.manager;

import com.tombstonex.util.FileUtils;
import com.tombstonex.util.Logger;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class WhitelistManager {
    private static WhitelistManager instance;
    private Set<String> whiteApps = new HashSet<>();
    private Set<String> whiteProcesses = new HashSet<>();
    private Set<String> blackSystemApps = new HashSet<>();

    /**
     * 默认不可冻结的系统应用包名
     * 这些应用一旦冻结会导致系统功能异常
     */
    private static final Set<String> DEFAULT_SYSTEM_WHITE = new HashSet<>();
    static {
        // 核心系统
        DEFAULT_SYSTEM_WHITE.add("android");
        DEFAULT_SYSTEM_WHITE.add("com.android.systemui");
        DEFAULT_SYSTEM_WHITE.add("com.android.phone");
        DEFAULT_SYSTEM_WHITE.add("com.android.settings");
        // 启动器
        DEFAULT_SYSTEM_WHITE.add("com.android.launcher");
        DEFAULT_SYSTEM_WHITE.add("com.android.launcher3");
        DEFAULT_SYSTEM_WHITE.add("com.google.android.googlequicksearchbox"); // 桌面搜索
        // 输入法
        DEFAULT_SYSTEM_WHITE.add("com.android.inputmethod");
        DEFAULT_SYSTEM_WHITE.add("com.google.android.inputmethod.latin");
        // 无障碍服务
        DEFAULT_SYSTEM_WHITE.add("com.android.accessibilityservice");
        // NFC 与蓝牙
        DEFAULT_SYSTEM_WHITE.add("com.android.nfc");
        DEFAULT_SYSTEM_WHITE.add("com.android.bluetooth");
        // 壁纸与主题
        DEFAULT_SYSTEM_WHITE.add("com.android.wallpaper");
        DEFAULT_SYSTEM_WHITE.add("com.android.wallpaperbackup");
        // 设备管理
        DEFAULT_SYSTEM_WHITE.add("com.android.deviceadmin");
        // 系统更新
        DEFAULT_SYSTEM_WHITE.add("com.android.systemupdate");
        DEFAULT_SYSTEM_WHITE.add("com.google.android.gms"); // Google 服务
        DEFAULT_SYSTEM_WHITE.add("com.google.android.gsf"); // Google 服务框架
    }

    private WhitelistManager() {
        reload();
    }

    public static synchronized WhitelistManager getInstance() {
        if (instance == null) {
            instance = new WhitelistManager();
        }
        return instance;
    }

    public void reload() {
        whiteApps = FileUtils.readLines("whiteApp.conf");
        whiteProcesses = FileUtils.readLines("whiteProcess.conf");
        blackSystemApps = FileUtils.readLines("blackSystemApp.conf");
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
        // 系统应用：默认不冻结，除非在黑名单中
        if (isSystemApp) {
            if (DEFAULT_SYSTEM_WHITE.contains(packageName)) {
                // 默认白名单的系统应用，仅当显式加入黑名单时才冻结
                return blackSystemApps.contains(packageName);
            }
            // 非默认白名单的系统应用，也需要在黑名单中才冻结
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
        whiteApps.add(packageName);
        FileUtils.writeLines("whiteApp.conf", whiteApps);
    }

    public void removeWhiteApp(String packageName) {
        whiteApps.remove(packageName);
        FileUtils.writeLines("whiteApp.conf", whiteApps);
    }

    public Set<String> getWhiteApps() {
        return Collections.unmodifiableSet(whiteApps);
    }

    // ---- 进程白名单 ----

    public void addWhiteProcess(String processName) {
        whiteProcesses.add(processName);
        FileUtils.writeLines("whiteProcess.conf", whiteProcesses);
    }

    public void removeWhiteProcess(String processName) {
        whiteProcesses.remove(processName);
        FileUtils.writeLines("whiteProcess.conf", whiteProcesses);
    }

    public Set<String> getWhiteProcesses() {
        return Collections.unmodifiableSet(whiteProcesses);
    }

    // ---- 系统应用冻结名单（黑名单）----

    public void addBlackSystemApp(String packageName) {
        blackSystemApps.add(packageName);
        FileUtils.writeLines("blackSystemApp.conf", blackSystemApps);
    }

    public void removeBlackSystemApp(String packageName) {
        blackSystemApps.remove(packageName);
        FileUtils.writeLines("blackSystemApp.conf", blackSystemApps);
    }

    public Set<String> getBlackSystemApps() {
        return Collections.unmodifiableSet(blackSystemApps);
    }
}
