package com.tombstonex.hook;

import android.os.FileObserver;
import com.tombstonex.manager.FreezeManager;
import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReKernel 集成 Hook
 * 处理与 ReKernel 内核模块的网络数据包 Binder 通知：
 * - 检测 ReKernel 模块是否存在（/dev/rekernel 或 /dev/rekernel_x 或 /proc/rekernel）
 * - 若存在，使用 FileObserver 监控 /proc/rekernel，后台线程阻塞读取 /dev/rekernel
 * - 收到通知后解析 UID/包名，临时解冻相关应用 3 秒，3 秒后重新冻结
 * - 若不存在，记录日志并跳过（不影响其他功能）
 * 注意：本 Hook 不 Hook Java 方法，init() 不需要 ClassLoader。
 * 本功能为可选增强，ReKernel 不存在时所有逻辑均安全跳过。
 */
public class ReKernelHook {

    private static final int REFREEZE_DELAY_SEC = 3;
    /** 连续读取失败达到此阈值后停止后台读取线程，避免无意义的轮询 */
    private static final int MAX_CONSECUTIVE_ERRORS = 10;

    private static final String[] REKERNEL_PATHS = {
        "/dev/rekernel", "/dev/rekernel_x", "/proc/rekernel"
    };
    // 解析通知内容中的 uid 与 包名
    private static final Pattern UID_PATTERN = Pattern.compile("uid=(\\d+)");
    private static final Pattern PKG_PATTERN = Pattern.compile("(?:pkg|package)=(\\S+)");

    // P1: 单线程足够处理临时解冻/重新冻结任务
    private static final ScheduledThreadPoolExecutor scheduler =
        new ScheduledThreadPoolExecutor(1);
    // 跟踪待重新冻结的任务：key=uid（或 packageName.hashCode）
    private static final Map<Integer, ScheduledFuture<?>> pendingRefreezes =
        new ConcurrentHashMap<>();
    static {
        scheduler.setRemoveOnCancelPolicy(true);
    }

    private static volatile FileObserver fileObserver;
    private static volatile Thread readerThread;
    private static volatile boolean running = false;
    private static volatile String monitoredPath;

    /**
     * 初始化 ReKernel 监控（不需要 ClassLoader，因为不 Hook Java 方法）
     */
    public static void init() {
        if (!isReKernelAvailable()) {
            Logger.i("ReKernel not installed, skipping ReKernelHook");
            return;
        }
        startMonitoring();
    }

    /**
     * 检测 ReKernel 模块是否存在
     */
    public static boolean isReKernelAvailable() {
        for (String path : REKERNEL_PATHS) {
            if (new File(path).exists()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 查找首个存在的 ReKernel 路径
     */
    private static String findReKernelPath() {
        for (String path : REKERNEL_PATHS) {
            if (new File(path).exists()) {
                return path;
            }
        }
        return null;
    }

    /**
     * 启动监控：FileObserver（适用于 /proc 文件）+ 后台阻塞读取线程（适用于 /dev 字符设备）
     */
    private static void startMonitoring() {
        monitoredPath = findReKernelPath();
        if (monitoredPath == null) {
            Logger.w("ReKernel path disappeared, cannot start monitoring");
            return;
        }
        running = true;
        Logger.i("ReKernelHook monitoring: " + monitoredPath);

        // 1) FileObserver 监控文件变化（/proc/rekernel 等常规文件场景）
        try {
            fileObserver = new FileObserver(monitoredPath,
                FileObserver.MODIFY | FileObserver.CLOSE_WRITE) {
                @Override
                public void onEvent(int event, String path) {
                    if (!running) return;
                    try {
                        readAndHandle();
                    } catch (Throwable t) {
                        Logger.d("ReKernel FileObserver onEvent error: " + t.getMessage());
                    }
                }
            };
            fileObserver.startWatching();
            Logger.i("ReKernel FileObserver started on " + monitoredPath);
        } catch (Throwable t) {
            Logger.w("ReKernel FileObserver setup failed: " + t.getMessage());
        }

        // 2) 后台线程阻塞读取字符设备（/dev/rekernel 场景）
        readerThread = new Thread(() -> {
            int consecutiveErrors = 0;
            while (running) {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new FileReader(monitoredPath));
                    String line;
                    while (running && (line = reader.readLine()) != null) {
                        consecutiveErrors = 0; // 成功读取，重置错误计数
                        handleNotification(line);
                    }
                } catch (Throwable t) {
                    if (!running) break;
                    consecutiveErrors++;
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        Logger.w("ReKernel reader stopped after " + consecutiveErrors
                            + " consecutive errors: " + t.getMessage());
                        break;
                    }
                    Logger.d("ReKernel reader error (" + consecutiveErrors + "): "
                        + t.getMessage());
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } finally {
                    if (reader != null) {
                        try { reader.close(); } catch (Throwable ignore) {}
                    }
                }
            }
        }, "TombstoneX-ReKernel");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * 读取 ReKernel 文件并逐行处理通知
     */
    private static void readAndHandle() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(monitoredPath));
            String line;
            while ((line = reader.readLine()) != null) {
                handleNotification(line);
            }
        } catch (Throwable t) {
            Logger.d("readAndHandle error: " + t.getMessage());
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Throwable ignore) {}
            }
        }
    }

    /**
     * 解析通知内容，提取 UID/包名并临时解冻相关应用
     * 通知格式示例：uid=10042 pkg=com.example.app
     */
    private static void handleNotification(String content) {
        if (content == null || content.isEmpty()) return;

        int uid = -1;
        String pkg = null;

        Matcher uidMatcher = UID_PATTERN.matcher(content);
        if (uidMatcher.find()) {
            try {
                uid = Integer.parseInt(uidMatcher.group(1));
            } catch (NumberFormatException ignore) {
                // uid 解析失败，忽略
            }
        }
        Matcher pkgMatcher = PKG_PATTERN.matcher(content);
        if (pkgMatcher.find()) {
            pkg = pkgMatcher.group(1);
        }

        // 既无有效 uid 也无包名，忽略
        if (uid < 10000 && pkg == null) return;

        Logger.i("ReKernel notification: uid=" + uid + " pkg=" + pkg);

        if (pkg != null) {
            temporarilyUnfreezePackage(pkg);
        } else if (uid > 0) {
            temporarilyUnfreezeUid(uid);
        }
    }

    /**
     * 临时解冻指定包名的所有冻结进程
     */
    private static void temporarilyUnfreezePackage(String packageName) {
        List<AppInfo> processes = ProcessTracker.getInstance().getAllByPackage(packageName);
        if (processes.isEmpty()) return;

        boolean anyFrozen = false;
        for (AppInfo info : processes) {
            if (info.state == AppState.FROZEN) {
                anyFrozen = true;
                break;
            }
        }
        if (!anyFrozen) return;

        Logger.i("ReKernel: temporarily unfreezing package " + packageName);
        FreezeManager.getInstance().unfreezePackage(packageName);
        scheduleRefreeze(packageName, -1);
    }

    /**
     * 临时解冻指定 UID 的所有冻结进程
     */
    private static void temporarilyUnfreezeUid(int uid) {
        List<AppInfo> processes = ProcessTracker.getInstance().getByUid(uid);
        if (processes.isEmpty()) return;

        boolean anyFrozen = false;
        for (AppInfo info : processes) {
            if (info.state == AppState.FROZEN) {
                anyFrozen = true;
                break;
            }
        }
        if (!anyFrozen) return;

        Logger.i("ReKernel: temporarily unfreezing uid=" + uid);
        for (AppInfo info : processes) {
            if (info.state == AppState.FROZEN) {
                FreezeManager.getInstance().unfreezeProcess(info.pid, info.uid);
            }
        }
        scheduleRefreeze(null, uid);
    }

    /**
     * 安排 3 秒后重新冻结
     */
    private static void scheduleRefreeze(String packageName, int uid) {
        int key = uid > 0 ? uid : packageName.hashCode();
        pendingRefreezes.compute(key, (k, oldFuture) -> {
            if (oldFuture != null) oldFuture.cancel(false);
            return scheduler.schedule(() -> {
                try {
                    if (packageName != null) {
                        FreezeManager.getInstance().freezePackage(packageName);
                        Logger.d("ReKernel: re-frozen package " + packageName);
                    } else if (uid > 0) {
                        for (AppInfo info : ProcessTracker.getInstance().getByUid(uid)) {
                            if (info.state != AppState.FOREGROUND
                                && info.state != AppState.FROZEN) {
                                FreezeManager.getInstance().freezeProcess(info.pid, info.uid);
                            }
                        }
                        Logger.d("ReKernel: re-frozen uid=" + uid);
                    }
                } catch (Throwable t) {
                    Logger.e("ReKernel re-freeze error", t);
                }
            }, REFREEZE_DELAY_SEC, TimeUnit.SECONDS);
        });
    }

    /**
     * 停止监控（供模块卸载/重载时调用）
     */
    public static void stop() {
        running = false;
        if (fileObserver != null) {
            try {
                fileObserver.stopWatching();
            } catch (Throwable ignore) {
                // 忽略停止异常
            }
            fileObserver = null;
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        Logger.i("ReKernelHook stopped");
    }
}
