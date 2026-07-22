package com.tombstonex.hook;

import android.os.FileObserver;
import com.tombstonex.manager.FreezeManager;
import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    // R8-M8: 线程名改为 TombstoneX-ReKernel-Sched，与 readerThread 区分。
    // R8-S9: 改为非 final，stop() shutdown 后可在重启时通过 newScheduler() 重建。
    // R9-M2: 添加 volatile，保证 startMonitoring() 中重新赋值的跨线程可见性。
    // M-3: 改为懒初始化，避免类加载时创建线程池
    private static volatile ScheduledThreadPoolExecutor scheduler = null;

    // 跟踪待重新冻结的任务
    // L4 修复：旧代码 key = uid > 0 ? uid : packageName.hashCode()，当 uid 缺失时使用包名哈希，
    // 存在哈希冲突风险（不同包名可能哈希相同，导致任务互相覆盖）。
    // 改为统一使用 String key，前缀区分 uid 与 pkg，避免冲突。
    private static final Map<String, ScheduledFuture<?>> pendingRefreezes =
        new ConcurrentHashMap<>();

    // R10-M-3: ReKernel 自身抑制冻结的 pid 集合。
    // stop() 时仅清除这些 pid 的抑制，避免破坏 RotationThawManager 的活跃抑制窗口。
    private static final Set<Integer> rekernelSuppressedPids = ConcurrentHashMap.newKeySet();

    /**
     * 创建新的调度线程池。
     * R8-S9: stop() shutdown 旧 scheduler 后，重启时通过此方法重建。
     */
    private static ScheduledThreadPoolExecutor newScheduler() {
        ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "TombstoneX-ReKernel-Sched");
            t.setDaemon(true);
            return t;
        });
        exec.setRemoveOnCancelPolicy(true);
        return exec;
    }

    private static volatile FileObserver fileObserver;
    private static volatile Thread readerThread;
    // R8-M7: 存储 reader 引用，stop() 时关闭以中断 native I/O 阻塞
    private static volatile BufferedReader currentReader;
    private static volatile boolean running = false;
    private static volatile String monitoredPath;
    // R8-S8: 使用 AtomicBoolean.compareAndSet 替代 volatile boolean，保证检查与启动的原子性
    private static final AtomicBoolean initFlag = new AtomicBoolean(false);

    // R11-M-3: 序列化 init()/stop() 关键段，避免并发时共享可变状态竞态
    private static final Object lifecycleLock = new Object();

    /**
     * 初始化 ReKernel 监控（不需要 ClassLoader，因为不 Hook Java 方法）
     *
     * R8-S8 修复：使用 AtomicBoolean.compareAndSet 保证 initialized 标志检查与
     * startMonitoring() 调用的原子性，消除 TOCTOU 竞态导致重复监控。
     * R8-S11 修复：startMonitoring 失败时重置 initFlag，允许后续重试。
     */
    public static void init() {
        // R11-M-3: 用 lifecycleLock 序列化 init/stop 关键段，避免并发时共享可变状态竞态
        synchronized (lifecycleLock) {
            // R8-S8: 原子的检查并设置，避免并发 init() 导致多个监控线程
            if (!initFlag.compareAndSet(false, true)) {
                Logger.d("ReKernelHook 已初始化，跳过");
                return;
            }
            if (!isReKernelAvailable()) {
                Logger.i("ReKernel 未安装，跳过 ReKernelHook");
                initFlag.set(false);
                return;
            }
            startMonitoring();
            // R10-M-4: startMonitoring 成功后（running=true）显式确保 initFlag=true，
            // 避免 stop() 并发清除 initFlag 后 init 无法恢复的竞态。
            if (running) {
                initFlag.set(true); // 确保 stop 并发清除后恢复
            } else {
                initFlag.set(false);
            }
        }
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
     * 启动监控。
     *
     * R8-m6 修复：根据文件类型选择单一读取策略，避免 FileObserver 与 readerThread
     * 对同一文件重复处理：
     * - /dev/ 字符设备：使用后台阻塞读取线程（readLine 阻塞等待数据）
     * - /proc/ 等常规文件：使用 FileObserver 监控变化事件
     */
    private static void startMonitoring() {
        monitoredPath = findReKernelPath();
        if (monitoredPath == null) {
            Logger.w("ReKernel 路径已消失，无法启动监控");
            return;
        }
        // R8-S9: 重启时若 scheduler 已 shutdown，重建之
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = newScheduler();
        }
        // R9-m5: running=true 在此设置，早于实际启动 reader/FileObserver。
        // 若启动失败，由 R9-M1 修复在 reader 线程连续错误退出时重置 running/initFlag 兜底。
        running = true;
        Logger.i("ReKernelHook 监控中: " + monitoredPath);

        // R8-m6: 根据路径类型选择单一读取策略
        if (monitoredPath.startsWith("/dev/")) {
            startReaderThread();
        } else {
            startFileObserver();
        }
    }

    /**
     * 使用 FileObserver 监控常规文件（如 /proc/rekernel）的变化事件。
     *
     * S-4 修复：/proc/ 虚拟文件系统不支持 FileObserver 的 inotify 机制，
     * inotify 对 /proc 下的文件不产生事件。检测到 /proc/ 路径时降级为
     * startReaderThread 轮询模式（定时读取文件内容）。
     */
    private static void startFileObserver() {
        // S-4: /proc/ 虚拟文件系统不支持 FileObserver（inotify），降级为轮询读取
        if (monitoredPath != null && monitoredPath.startsWith("/proc/")) {
            Logger.i("检测到 /proc/ 路径，FileObserver 不支持，降级为轮询模式: " + monitoredPath);
            startReaderThread();
            return;
        }
        try {
            fileObserver = new FileObserver(monitoredPath,
                FileObserver.MODIFY | FileObserver.CLOSE_WRITE) {
                @Override
                public void onEvent(int event, String path) {
                    if (!running) return;
                    try {
                        readAndHandle();
                    } catch (Throwable t) {
                        Logger.d("ReKernel FileObserver onEvent 出错: " + t.getMessage());
                    }
                }
            };
            fileObserver.startWatching();
            Logger.i("ReKernel FileObserver 已启动于 " + monitoredPath);
        } catch (Throwable t) {
            Logger.w("ReKernel FileObserver 设置失败: " + t.getMessage());
            running = false; // R8-S11: 启动失败，允许后续重试
        }
    }

    /**
     * 后台线程阻塞读取字符设备（/dev/rekernel）。
     * 对于字符设备，readLine() 会阻塞直到有数据。
     * R8-M7: 存储 reader 引用，stop() 时关闭以中断 native I/O 阻塞。
     * R8-M8: 线程名改为 TombstoneX-ReKernel-Reader，与 scheduler 线程区分。
     */
    private static void startReaderThread() {
        readerThread = new Thread(() -> {
            int consecutiveErrors = 0;
            while (running) {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new InputStreamReader(
                        new FileInputStream(monitoredPath), StandardCharsets.UTF_8));
                    currentReader = reader; // R8-M7: 存储引用供 stop() 关闭
                    String line;
                    // m-2: readLine() 在无换行符时会永久阻塞（对于字符设备如 /dev/rekernel，
                    // 内核模块写入数据时通常附带换行符，此为已知限制）。
                    // 若需处理无换行符的场景，可改用 read(char[]) 配合手动缓冲区拼接。
                    while (running && (line = reader.readLine()) != null) {
                        consecutiveErrors = 0; // 成功读取，重置错误计数
                        // R10-M-2: handleNotification 异常不应触发读错误路径，
                        // 独立 try-catch 隔离通知处理异常，不递增 consecutiveErrors，继续读取下一条
                        try {
                            handleNotification(line);
                        } catch (Throwable t) {
                            Logger.e("ReKernel 通知处理出错", t);
                        }
                    }
                    // readLine 返回 null（EOF）：常规文件已读完
                    // sleep 后重试，避免忙等待
                    if (running) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (Throwable t) {
                    if (!running) break;
                    consecutiveErrors++;
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        Logger.w("ReKernel 读取线程在连续 " + consecutiveErrors
                            + " 次错误后停止: " + t.getMessage());
                        // R9-M1: 重置 running 和 initFlag，避免监控静默死亡
                        running = false;
                        initFlag.set(false); // 允许后续重试
                        // M-4: 错误退出时清理 ReKernel 自身的冻结抑制，避免抑制残留
                        try {
                            for (Integer suppressedPid : rekernelSuppressedPids) {
                                FreezeManager.unsuppressFreeze(suppressedPid);
                            }
                        } catch (Throwable ignore) {
                            Logger.d("ReKernel 错误退出清理 unsuppressFreeze 出错: " + ignore.getMessage());
                        }
                        rekernelSuppressedPids.clear();
                        break;
                    }
                    Logger.d("ReKernel 读取出错 (第 " + consecutiveErrors + " 次): "
                        + t.getMessage());
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } finally {
                    // M-2: 使用条件清除，仅当 currentReader 仍指向当前 reader 时清除，
                    // 避免清除其他迭代设置的 currentReader 引用
                    if (currentReader == reader) {
                        currentReader = null;
                    }
                    if (reader != null) {
                        try { reader.close(); } catch (Throwable ignore) {
                            Logger.d("ReKernel readerThread 关闭 reader 出错: " + ignore.getMessage());
                        }
                    }
                }
            }
        }, "TombstoneX-ReKernel-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * 读取 ReKernel 文件并逐行处理通知
     */
    private static void readAndHandle() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(monitoredPath), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                // R10-M-2: handleNotification 异常隔离，避免单条通知异常导致整个读取终止
                try {
                    handleNotification(line);
                } catch (Throwable t) {
                    Logger.e("ReKernel 通知处理出错", t);
                }
            }
        } catch (Throwable t) {
            Logger.d("readAndHandle 出错: " + t.getMessage());
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Throwable ignore) {
                    Logger.d("readAndHandle 关闭 reader 出错: " + ignore.getMessage());
                }
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

        // R10-M-1: R9-m3 回归修复——uid < 10000 仅在 uid 成功解析（>=0）时跳过系统应用；
        // uid 解析失败（-1）时不跳过，继续通过 pkg 路径处理。
        // R9-m3: uid < 10000 为系统应用或无效 uid，跳过所有系统应用
        if (uid >= 0 && uid < 10000) return;

        Logger.i("ReKernel 通知: uid=" + uid + " pkg=" + pkg);

        if (pkg != null) {
            temporarilyUnfreezePackage(pkg);
        } else if (uid > 0) {
            temporarilyUnfreezeUid(uid);
        }
    }

    /**
     * 临时解冻指定包名的所有冻结进程
     *
     * R8-S12 修复：解冻后调用 FreezeManager.suppressFreeze 抑制冻结，
     * 防止 3 秒窗口内被 ScheduledFreezeManager 等重新冻结。
     */
    private static void temporarilyUnfreezePackage(String packageName) {
        List<AppInfo> processes = ProcessTracker.getInstance().getAllByPackage(packageName);
        if (processes.isEmpty()) return;

        boolean anyFrozen = false;
        for (AppInfo info : processes) {
            if (info.getState() == AppState.FROZEN) {
                anyFrozen = true;
                FreezeManager.suppressFreeze(info.pid); // R8-S12: 抑制冻结
                rekernelSuppressedPids.add(info.pid); // R10-M-3: 记录 ReKernel 自身抑制的 pid
            }
        }
        if (!anyFrozen) return;

        Logger.i("ReKernel: 临时解冻包 " + packageName);
        FreezeManager.getInstance().unfreezePackage(packageName);
        scheduleRefreeze(packageName, -1);
    }

    /**
     * 临时解冻指定 UID 的所有冻结进程
     *
     * R8-S12 修复：解冻后调用 FreezeManager.suppressFreeze 抑制冻结，
     * 防止 3 秒窗口内被 ScheduledFreezeManager 等重新冻结。
     */
    private static void temporarilyUnfreezeUid(int uid) {
        List<AppInfo> processes = ProcessTracker.getInstance().getByUid(uid);
        if (processes.isEmpty()) return;

        boolean anyFrozen = false;
        // R9-M3: 在第一次遍历时收集需要解冻的 pid 列表，第二次直接使用列表，
        // 避免两次遍历间 TOCTOU 竞态（进程状态可能在两次遍历间变化）。
        List<AppInfo> toUnfreeze = new ArrayList<>();
        for (AppInfo info : processes) {
            if (info.getState() == AppState.FROZEN) {
                anyFrozen = true;
                FreezeManager.suppressFreeze(info.pid); // R8-S12: 抑制冻结
                rekernelSuppressedPids.add(info.pid); // R10-M-3: 记录 ReKernel 自身抑制的 pid
                toUnfreeze.add(info);
            }
        }
        if (!anyFrozen) return;

        Logger.i("ReKernel: 临时解冻 uid=" + uid);
        for (AppInfo info : toUnfreeze) {
            FreezeManager.getInstance().unfreezeProcess(info.pid, info.uid);
        }
        scheduleRefreeze(null, uid);
    }

    /**
     * 安排 3 秒后重新冻结
     *
     * R9-S1 修复：unsuppressFreeze 必须在 freezePackage/freezeProcess 之前调用。
     * FreezeManager.freezeProcess 会检查 freezeSuppressedPids.contains(pid)，
     * 若 pid 仍在抑制集合中则直接返回 false 跳过冻结。旧代码先 freeze 再 unsuppressFreeze，
     * 由于 temporarilyUnfreeze 已调用 suppressFreeze，3 秒后定时任务执行时抑制仍然有效，
     * 导致重新冻结永远不会执行。对比 RotationThawManager 的正确实现：先 unsuppressFreeze 再 freeze。
     * finally 块中兜底再次清除抑制，确保异常路径下抑制也不会残留。
     *
     * R9-S4 修复：scheduler 被 shutdownNow() 关闭后，scheduler.schedule() 抛出
     * RejectedExecutionException。该异常未被捕获时，suppressFreeze 已在
     * scheduleRefreeze 之前调用（于 temporarilyUnfreeze 中），抑制标志已写入却
     * 永远不会被清除。此处用 try/catch 包裹 compute 调用，失败时回滚抑制。
     */
    private static void scheduleRefreeze(String packageName, int uid) {
        String key = uid > 0 ? "uid:" + uid : "pkg:" + packageName;
        try {
            pendingRefreezes.compute(key, (k, oldFuture) -> {
                if (oldFuture != null) oldFuture.cancel(false);
                // M1: 捕获 ScheduledFuture 引用，finally 中使用条件 remove 避免误删新 future
                ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
                holder[0] = scheduler.schedule(() -> {
                    try {
                        // R9-S1: 先解除冻结抑制，再执行冻结
                        // R11-M-1: 仅清除 rekernelSuppressedPids 中的 pid，
                        // 避免破坏 RotationThawManager 的活跃抑制窗口。
                        if (packageName != null) {
                            for (AppInfo info : ProcessTracker.getInstance().getAllByPackage(packageName)) {
                                if (rekernelSuppressedPids.remove(info.pid)) {
                                    FreezeManager.unsuppressFreeze(info.pid);
                                }
                            }
                            FreezeManager.getInstance().freezePackage(packageName);
                            Logger.d("ReKernel: 已重新冻结包 " + packageName);
                        } else if (uid > 0) {
                            // R11-m-2: 先收集快照列表再遍历，避免双次 getByUid() 调用存在 TOCTOU 窗口
                            List<AppInfo> toRefreeze = ProcessTracker.getInstance().getByUid(uid);
                            for (AppInfo info : toRefreeze) {
                                if (rekernelSuppressedPids.remove(info.pid)) {
                                    FreezeManager.unsuppressFreeze(info.pid);
                                }
                            }
                            for (AppInfo info : toRefreeze) {
                                if (info.getState() != AppState.FOREGROUND
                                    && info.getState() != AppState.FROZEN) {
                                    FreezeManager.getInstance().freezeProcess(info.pid, info.uid);
                                }
                            }
                            Logger.d("ReKernel: 已重新冻结 uid=" + uid);
                        }
                    } catch (Throwable t) {
                        Logger.e("ReKernel 重新冻结出错", t);
                    } finally {
                        // R10-m-1/R10-M-3: 仅清除 ReKernel 自身抑制的 pid（rekernelSuppressedPids），
                        // 而非整个包/uid 的所有 pid，避免破坏 RotationThawManager 的活跃抑制窗口。
                        // 同时从 rekernelSuppressedPids 移除已清除的 pid。
                        try {
                            if (packageName != null) {
                                for (AppInfo info : ProcessTracker.getInstance().getAllByPackage(packageName)) {
                                    if (rekernelSuppressedPids.remove(info.pid)) {
                                        FreezeManager.unsuppressFreeze(info.pid);
                                    }
                                }
                            } else if (uid > 0) {
                                for (AppInfo info : ProcessTracker.getInstance().getByUid(uid)) {
                                    if (rekernelSuppressedPids.remove(info.pid)) {
                                        FreezeManager.unsuppressFreeze(info.pid);
                                    }
                                }
                            }
                        } catch (Throwable ignore) {
                            Logger.d("ReKernel scheduleRefreeze finally 清理 unsuppressFreeze 出错: " + ignore.getMessage());
                        }
                        pendingRefreezes.remove(key, holder[0]);
                    }
                }, REFREEZE_DELAY_SEC, TimeUnit.SECONDS);
                return holder[0];
            });
        } catch (Throwable t) {
            // R9-S4: scheduler 已关闭（RejectedExecutionException），回滚冻结抑制
            // R11-M-1: 仅清除 rekernelSuppressedPids 中的 pid，
            // 避免破坏 RotationThawManager 的活跃抑制窗口。
            Logger.w("scheduleRefreeze 失败，回滚冻结抑制: " + t.getMessage());
            try {
                if (packageName != null) {
                    for (AppInfo info : ProcessTracker.getInstance().getAllByPackage(packageName)) {
                        if (rekernelSuppressedPids.remove(info.pid)) {
                            FreezeManager.unsuppressFreeze(info.pid);
                        }
                    }
                } else if (uid > 0) {
                    for (AppInfo info : ProcessTracker.getInstance().getByUid(uid)) {
                        if (rekernelSuppressedPids.remove(info.pid)) {
                            FreezeManager.unsuppressFreeze(info.pid);
                        }
                    }
                }
            } catch (Throwable ignore) {
                Logger.d("scheduleRefreeze 回滚抑制清理 unsuppressFreeze 出错: " + ignore.getMessage());
            }
            // m-3: compute 抛异常时移除 pendingRefreezes 中的过期条目，
            // 避免残留的旧 ScheduledFuture 引用导致后续 compute 误取消
            pendingRefreezes.remove(key);
        }
    }

    /**
     * 进程死亡时清理 ReKernel 相关的抑制状态和待执行任务。
     * 供 ProcessDeathHook.cleanupDeadProcess 调用。
     *
     * R11-M-2: 进程死亡时无清理机制，可能导致 pid 复用后抑制状态残留。
     */
    public static void onProcessDied(int pid) {
        rekernelSuppressedPids.remove(pid);
        // m-4: 调用 unsuppressFreeze 清理该 pid 的冻结抑制状态（幂等操作，
        // 即使 pid 不在抑制集合中也安全调用）
        FreezeManager.unsuppressFreeze(pid);
        // 取消该 pid 可能关联的 pending refreeze 任务
        // 由于 pendingRefreezes 按 key（uid:xxx 或 pkg:xxx）索引，无法直接按 pid 取消
        // 但 rekernelSuppressedPids 的清理已足够避免 pid 复用问题
    }

    /**
     * 停止监控（供模块卸载/重载时调用）
     *
     * R8-S9 修复：取消所有待执行的 refreeze 任务并关闭 scheduler。
     * R8-S10 修复：重置 initFlag，允许后续重新 init()。
     * R8-M7 修复：关闭 currentReader 以中断 native I/O 阻塞。
     * R8-m4 修复：清理 pendingRefreezes。
     */
    public static void stop() {
        // R11-M-3: 用 lifecycleLock 序列化 init/stop 关键段，避免并发时共享可变状态竞态
        synchronized (lifecycleLock) {
            running = false;
            // R8-S9/R8-m4: 取消所有待执行的 refreeze 任务并清理
            for (ScheduledFuture<?> f : pendingRefreezes.values()) {
                f.cancel(false);
            }
            // R9-S3: 清除冻结抑制（refreeze 任务被取消，需手动清除）。
            // R10-M-3: 仅清除 ReKernel 自身抑制的 pid（rekernelSuppressedPids），
            // 而非所有进程，避免破坏 RotationThawManager 的活跃抑制窗口。
            // suppressFreeze 已在 temporarilyUnfreeze 中调用，原本应由 3 秒后的 refreeze
            // 任务清除。任务被取消后抑制标志会永久残留，因此在此遍历 rekernelSuppressedPids 清除。
            try {
                for (Integer pid : rekernelSuppressedPids) {
                    FreezeManager.unsuppressFreeze(pid);
                }
            } catch (Throwable ignore) {
                Logger.d("ReKernel stop() unsuppressFreeze 清理出错: " + ignore.getMessage());
            }
            rekernelSuppressedPids.clear();
            pendingRefreezes.clear();
            // R8-S9: 关闭 scheduler
            // M-3: scheduler 可能为 null（懒初始化，stop 前未 start）
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            // R8-M7: 关闭 reader 以中断 native I/O 阻塞（须在 interrupt 之前）
            if (currentReader != null) {
                try { currentReader.close(); } catch (Throwable ignore) {
                    Logger.d("ReKernel stop() 关闭 currentReader 出错: " + ignore.getMessage());
                }
                currentReader = null;
            }
            if (fileObserver != null) {
                try {
                    fileObserver.stopWatching();
                } catch (Throwable ignore) {
                    // 忽略停止异常
                    Logger.d("ReKernel stop() 停止 FileObserver 出错: " + ignore.getMessage());
                }
                fileObserver = null;
            }
            if (readerThread != null) {
                readerThread.interrupt();
                readerThread = null;
            }
            // R8-S10: 重置 initFlag，允许后续重新 init()
            initFlag.set(false);
            Logger.i("ReKernelHook 已停止");
        }
    }
}
