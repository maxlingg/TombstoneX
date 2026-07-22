package com.tombstonex.model;

import java.util.concurrent.atomic.AtomicReference;

public class AppInfo {
    public final String packageName;
    public final String processName;
    public final int pid;
    public final int uid;

    /** S-9: 使用 AtomicReference 保证 state 的 get/set 原子可见性，替代 volatile 降低复合操作风险 */
    private final AtomicReference<AppState> state = new AtomicReference<>(AppState.BACKGROUND);
    /** @deprecated 该字段将在后续版本中移除，请使用 {@link #isWhiteListed()} 替代。 */
    @Deprecated
    private volatile boolean isWhiteListed;
    private volatile boolean isSystemApp;
    private volatile int oomAdj;
    private volatile long freezeTimestamp;

    public AppInfo(String packageName, String processName, int pid, int uid) {
        this.packageName = packageName;
        this.processName = processName;
        this.pid = pid;
        this.uid = uid;
        this.state.set(AppState.BACKGROUND);
        this.isWhiteListed = false;
        this.isSystemApp = false;
        this.oomAdj = 0;
        this.freezeTimestamp = 0;
    }

    // ---- S-9: getter / setter ----

    public AppState getState() {
        return state.get();
    }

    public void setState(AppState newState) {
        state.set(newState);
    }

    /**
     * @deprecated 该字段将在后续版本中移除，请使用 {@link #isWhiteListed()} 替代。
     *             直接访问字段会导致复合操作非原子。
     */
    @Deprecated
    public boolean isWhiteListed() {
        return isWhiteListed;
    }

    public void setWhiteListed(boolean whiteListed) {
        this.isWhiteListed = whiteListed;
    }

    public boolean isSystemApp() {
        return isSystemApp;
    }

    public void setSystemApp(boolean systemApp) {
        this.isSystemApp = systemApp;
    }

    public int getOomAdj() {
        return oomAdj;
    }

    public void setOomAdj(int oomAdj) {
        this.oomAdj = oomAdj;
    }

    public long getFreezeTimestamp() {
        return freezeTimestamp;
    }

    public void setFreezeTimestamp(long freezeTimestamp) {
        this.freezeTimestamp = freezeTimestamp;
    }

    /**
     * M-32: equals() 有意仅使用 pid + uid 进行比较。
     * 设计理念：AppInfo 以「进程」为中心——同一进程（pid+uid）即为同一实体，
     * 即使 packageName 或 processName 因某种原因发生变化，该进程的身份仍不变。
     * 这意味着两个不同包名但共享同一 pid+uid 的 AppInfo 会被视为相等，
     * 这与 Android 进程模型的常见行为一致（如 sharedUserId 场景）。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppInfo)) return false;
        AppInfo appInfo = (AppInfo) o;
        return pid == appInfo.pid && uid == appInfo.uid;
    }

    /**
     * S2-修复: hashCode() 必须与 equals() 保持一致，仅使用 pid 和 uid。
     * 旧实现额外包含 packageName 和 processName，当两个 AppInfo 对象具有相同 pid+uid
     * 但不同 packageName/processName 时（如 sharedUserId 场景），equals() 返回 true
     * 但 hashCode() 不同，违反 Java equals/hashCode 契约，导致 HashMap/HashSet 行为异常。
     * 虽然仅使用 pid+uid 会降低哈希分布均匀性，但契约正确性优先于分布均匀性。
     */
    @Override
    public int hashCode() {
        return 31 * pid + uid;
    }

    /** M-33: 提供 toString() 便于调试和日志输出 */
    @Override
    public String toString() {
        return "AppInfo{pid=" + pid
                + ", uid=" + uid
                + ", packageName=" + packageName
                + ", processName=" + processName
                + ", state=" + state.get()
                + "}";
    }
}