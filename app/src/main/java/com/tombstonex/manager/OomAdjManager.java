package com.tombstonex.manager;

import com.tombstonex.model.AppInfo;
import com.tombstonex.util.FileUtils;
import com.tombstonex.util.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OOM adj 调优管理器。
 *
 * 为后台应用设置三档优先级，调整其 oom_adj 值以影响 LMK（Low Memory Killer）
 * 的杀进程顺序：优先级越高，oom_adj 越低，越不容易被杀。
 *
 * 优先级与 oom_adj 范围：
 * <pre>
 *   HIGH   : 主进程 600-649 / 子进程 650-699
 *   MEDIUM : 主进程 700-749 / 子进程 750-799   (默认)
 *   LOW    : 主进程 800-899 / 子进程 900-999
 * </pre>
 *
 * 主进程判断：processName 等于 packageName 或 processName 不包含 ":"
 * 子进程判断：processName 包含 ":"
 *
 * 配置文件：{@value #CONFIG_FILE}，格式 "包名=优先级"（每行一个），通过
 * {@link FileUtils} 原子写入。默认优先级为 {@link #PRIORITY_MEDIUM}。
 *
 * {@link #applyOomAdj(int, int, int)} 设计为在 ActivitySwitchHook 的
 * setOomAdj Hook 中调用，将系统计算的 oom_adj 钳制到对应优先级区间。
 */
public class OomAdjManager {
    public static final int PRIORITY_HIGH = 0;
    public static final int PRIORITY_MEDIUM = 1;
    public static final int PRIORITY_LOW = 2;

    private static final String CONFIG_FILE = "app_priority.conf";
    /** 仅当 oom_adj >= 此阈值（后台进程）时才进行调整，前台/可见进程保持原值 */
    private static final int BACKGROUND_OOMADJ_THRESHOLD = 600;

    private static volatile OomAdjManager instance;

    private final Object lock = new Object();
    private volatile Map<String, Integer> priorityMap = new ConcurrentHashMap<>();

    private OomAdjManager() {
        loadConfig();
    }

    // P3-R6: 使用双重检查锁定
    public static OomAdjManager getInstance() {
        OomAdjManager local = instance;
        if (local == null) {
            synchronized (OomAdjManager.class) {
                local = instance;
                if (local == null) {
                    local = new OomAdjManager();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * 从 {@value #CONFIG_FILE} 加载优先级配置。
     * 文件格式：每行 "包名=优先级"，# 开头或空行被 FileUtils.readLines 过滤。
     */
    private void loadConfig() {
        Set<String> lines = FileUtils.readLines(CONFIG_FILE);
        Map<String, Integer> map = new ConcurrentHashMap<>();
        for (String line : lines) {
            int idx = line.indexOf('=');
            if (idx <= 0 || idx >= line.length() - 1) continue;
            String pkg = line.substring(0, idx).trim();
            String valStr = line.substring(idx + 1).trim();
            try {
                int priority = Integer.parseInt(valStr);
                if (priority < PRIORITY_HIGH || priority > PRIORITY_LOW) {
                    Logger.w("OomAdjManager: invalid priority " + priority
                        + " for " + pkg + ", skip");
                    continue;
                }
                map.put(pkg, priority);
            } catch (NumberFormatException e) {
                Logger.w("OomAdjManager: failed to parse priority for "
                    + pkg + ": " + valStr);
            }
        }
        priorityMap = map;
        Logger.d("OomAdjManager loaded: " + map.size() + " entries");
    }

    /**
     * 设置应用优先级并持久化。
     *
     * @param packageName 包名
     * @param priority    {@link #PRIORITY_HIGH} / {@link #PRIORITY_MEDIUM} / {@link #PRIORITY_LOW}
     */
    public void setAppPriority(String packageName, int priority) {
        if (packageName == null) return;
        if (priority < PRIORITY_HIGH || priority > PRIORITY_LOW) {
            throw new IllegalArgumentException("Invalid priority: " + priority
                + ", must be PRIORITY_HIGH(0)/MEDIUM(1)/LOW(2)");
        }
        synchronized (lock) {
            // 复制当前映射 -> 修改 -> 原子写文件 -> 替换 volatile 引用
            Map<String, Integer> copy = new HashMap<>(priorityMap);
            copy.put(packageName, priority);

            Set<String> lines = new HashSet<>();
            for (Map.Entry<String, Integer> e : copy.entrySet()) {
                lines.add(e.getKey() + "=" + e.getValue());
            }
            if (FileUtils.writeLines(CONFIG_FILE, lines)) {
                priorityMap = new ConcurrentHashMap<>(copy);
                Logger.i("OomAdjManager: set priority " + priorityName(priority)
                    + " for " + packageName);
            }
        }
    }

    /**
     * 获取应用优先级，未配置时返回 {@link #PRIORITY_MEDIUM}。
     */
    public int getAppPriority(String packageName) {
        if (packageName == null) return PRIORITY_MEDIUM;
        Integer p = priorityMap.get(packageName);
        return p != null ? p : PRIORITY_MEDIUM;
    }

    /**
     * 根据应用优先级调整 oom_adj 值。
     *
     * 仅调整后台进程（oom_adj &gt;= {@value #BACKGROUND_OOMADJ_THRESHOLD}），
     * 前台/可见进程保持原值不动。调整方式为将 oom_adj 钳制到对应优先级与
     * 进程类型（主/子）的区间内。
     *
     * 设计在 ActivitySwitchHook 的 setOomAdj Hook 中调用：
     * <pre>
     *   int adjusted = OomAdjManager.getInstance().applyOomAdj(uid, pid, oomAdj);
     *   ProcessTracker.getInstance().updateOomAdj(pid, adjusted);
     * </pre>
     *
     * @param uid    进程 uid
     * @param pid    进程 pid
     * @param oomAdj 系统计算的原始 oom_adj
     * @return 调整后的 oom_adj
     */
    public int applyOomAdj(int uid, int pid, int oomAdj) {
        // 前台/可见进程不调整
        if (oomAdj < BACKGROUND_OOMADJ_THRESHOLD) {
            return oomAdj;
        }

        AppInfo info = ProcessTracker.getInstance().getByPid(pid);
        if (info == null) return oomAdj;

        int priority = getAppPriority(info.packageName);
        boolean isMain = isMainProcess(info.packageName, info.processName);

        int minAdj;
        int maxAdj;
        switch (priority) {
            case PRIORITY_HIGH:
                minAdj = isMain ? 600 : 650;
                maxAdj = isMain ? 649 : 699;
                break;
            case PRIORITY_LOW:
                minAdj = isMain ? 800 : 900;
                maxAdj = isMain ? 899 : 999;
                break;
            case PRIORITY_MEDIUM:
            default:
                minAdj = isMain ? 700 : 750;
                maxAdj = isMain ? 749 : 799;
                break;
        }

        // 钳制到目标区间
        if (oomAdj < minAdj) return minAdj;
        if (oomAdj > maxAdj) return maxAdj;
        return oomAdj;
    }

    /**
     * 主进程判断：processName 等于 packageName 或 processName 不包含 ":"。
     * 子进程判断：processName 包含 ":"。
     *
     * @param packageName 包名
     * @param processName 进程名（可能与包名不同，如 com.example.app:pushservice）
     * @return true 表示主进程
     */
    public static boolean isMainProcess(String packageName, String processName) {
        if (processName == null) return true;
        if (packageName != null && packageName.equals(processName)) return true;
        return !processName.contains(":");
    }

    private static String priorityName(int priority) {
        switch (priority) {
            case PRIORITY_HIGH:   return "HIGH";
            case PRIORITY_LOW:    return "LOW";
            case PRIORITY_MEDIUM:
            default:              return "MEDIUM";
        }
    }
}
