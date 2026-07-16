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

    private static final Set<String> DEFAULT_SYSTEM_WHITE = new HashSet<>();
    static {
        DEFAULT_SYSTEM_WHITE.add("android");
        DEFAULT_SYSTEM_WHITE.add("com.android.systemui");
        DEFAULT_SYSTEM_WHITE.add("com.android.phone");
        DEFAULT_SYSTEM_WHITE.add("com.android.launcher");
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

    public boolean shouldFreeze(String packageName, String processName, boolean isSystemApp) {
        if (isSystemApp && DEFAULT_SYSTEM_WHITE.contains(packageName)) {
            return blackSystemApps.contains(packageName);
        }
        if (isSystemApp) {
            return blackSystemApps.contains(packageName);
        }
        if (whiteApps.contains(packageName)) {
            return false;
        }
        if (whiteProcesses.contains(processName)) {
            return false;
        }
        return true;
    }

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

    public Set<String> getBlackSystemApps() {
        return Collections.unmodifiableSet(blackSystemApps);
    }
}