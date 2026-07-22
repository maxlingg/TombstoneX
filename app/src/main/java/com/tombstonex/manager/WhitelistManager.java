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
            // S-6: FileUtils.readLines() 可能返回 null，回退到空集合防止 NPE。
            Set<String> apps = FileUtils.readLines("whiteApp.conf");
            Set<String> procs = FileUtils.readLines("whiteProcess.conf");
            Set<String> blackApps = FileUtils.readLines("blackSystemApp.conf");
            whiteApps = (apps != null) ? apps : Collections.<String>emptySet();
            whiteProcesses = (procs != null) ? procs : Collections.<String>emptySet();
            blackSystemApps = (blackApps != null) ? blackApps : Collections.<String>emptySet();
        }
        Logger.d("白名单已重载: whiteApps=" + whiteApps.size()
            + " whiteProcesses=" + whiteProcesses.size()
            + " blackSystemApps=" + blackSystemApps.size());
    }

    /**
     * 判断一个应用/进程是否应该被冻结
     *
     * M-19: 本方法读取三个 volatile 字段（whiteApps / whiteProcesses / blackSystemApps）
     * 之间没有原子性保证，理论上可能读到混合状态（部分字段来自旧版本，部分来自新版本）。
     * 这是已知的设计权衡：三个字段由 reload() 在 synchronized(lock) 内同步替换，
     * 混合状态仅在 reload() 与 shouldFreeze() 并发时出现，概率极低且不影响正确性
     * （最坏情况是白名单/黑名单短暂不一致，但下次 reload 后会恢复一致）。
     *
     * @param packageName 包名
     * @param processName 进程名（可能与包名不同，如 :pushservice 子进程）
     * @param isSystemApp 是否是系统应用
     * @return true 表示应该冻结
     */
    public boolean shouldFreeze(String packageName, String processName, boolean isSystemApp) {
        // M6-修复: 防御性 null 检查，避免 NPE。调用方已保证非 null，但作为公共 API 应有防御。
        if (packageName == null) return false;
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

    // 重构建议: addWhiteApp / removeWhiteApp / addWhiteProcess / removeWhiteProcess /
    // addBlackSystemApp / removeBlackSystemApp 六个方法共享相同的"复制→修改→写文件→替换引用"模式，
    // 可抽取为泛型方法 updateWhitelist(String configFile, Consumer<Set<String>> modifier, Supplier<Set<String>> getter, Consumer<Set<String>> setter)，
    // 减少重复代码同时保持原子性保证。

    public void addWhiteApp(String packageName) {
        synchronized (lock) {
            Set<String> copy = new HashSet<>(whiteApps);
            copy.add(packageName);
            if (FileUtils.writeLines("whiteApp.conf", copy)) {
                whiteApps = copy;
            } else {
                Logger.e("WhitelistManager: 写入 whiteApp.conf 失败，内存未更新");
            }
        }
    }

    public void removeWhiteApp(String packageName) {
        synchronized (lock) {
            Set<String> copy = new HashSet<>(whiteApps);
            copy.remove(packageName);
            if (FileUtils.writeLines("whiteApp.conf", copy)) {
                whiteApps = copy;
            } else {
                Logger.e("WhitelistManager: 写入 whiteApp.conf 失败，内存未更新");
            }
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
            if (FileUtils.writeLines("whiteProcess.conf", copy)) {
                whiteProcesses = copy;
            } else {
                Logger.e("WhitelistManager: 写入 whiteProcess.conf 失败，内存未更新");
            }
        }
    }

    public void removeWhiteProcess(String processName) {
        synchronized (lock) {
            Set<String> copy = new HashSet<>(whiteProcesses);
            copy.remove(processName);
            if (FileUtils.writeLines("whiteProcess.conf", copy)) {
                whiteProcesses = copy;
            } else {
                Logger.e("WhitelistManager: 写入 whiteProcess.conf 失败，内存未更新");
            }
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
            if (FileUtils.writeLines("blackSystemApp.conf", copy)) {
                blackSystemApps = copy;
            } else {
                Logger.e("WhitelistManager: 写入 blackSystemApp.conf 失败，内存未更新");
            }
        }
    }

    public void removeBlackSystemApp(String packageName) {
        synchronized (lock) {
            Set<String> copy = new HashSet<>(blackSystemApps);
            copy.remove(packageName);
            if (FileUtils.writeLines("blackSystemApp.conf", copy)) {
                blackSystemApps = copy;
            } else {
                Logger.e("WhitelistManager: 写入 blackSystemApp.conf 失败，内存未更新");
            }
        }
    }

    public Set<String> getBlackSystemApps() {
        return Collections.unmodifiableSet(new HashSet<>(blackSystemApps));
    }
}
