package com.tombstonex.model;

public class AppInfo {
    public String packageName;
    public String processName;
    public int pid;
    public int uid;
    public AppState state;
    public boolean isWhiteListed;
    public boolean isSystemApp;
    public int oomAdj;
    public long freezeTimestamp;

    public AppInfo(String packageName, String processName, int pid, int uid) {
        this.packageName = packageName;
        this.processName = processName;
        this.pid = pid;
        this.uid = uid;
        this.state = AppState.BACKGROUND;
        this.isWhiteListed = false;
        this.isSystemApp = false;
        this.oomAdj = 0;
        this.freezeTimestamp = 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppInfo)) return false;
        AppInfo appInfo = (AppInfo) o;
        return pid == appInfo.pid && uid == appInfo.uid;
    }

    @Override
    public int hashCode() {
        return 31 * pid + uid;
    }
}