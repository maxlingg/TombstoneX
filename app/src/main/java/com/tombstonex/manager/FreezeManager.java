package com.tombstonex.manager;

import com.tombstonex.freezer.*;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.model.FreezeMode;
import com.tombstonex.util.Logger;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FreezeManager {
    private static volatile FreezeManager instance;
    private volatile IFreezer currentFreezer;
    private final Object freezeLock = new Object();

    // M2: 冻结抑制集合 —— 轮换解冻窗口期间禁止任何调用方重新冻结该 pid。
    // 所有冻结调用都经过 freezeProcess，在其中统一检查此集合，从而覆盖
    // ScheduledFreezeManager、ScreenStateHook、ActivitySwitchHook 等所有冻结入口。
    private static final Set<Integer> freezeSuppressedPids = ConcurrentHashMap.newKeySet();

    private FreezeManager() {
        // M-14: selectFreezer() 在构造函数中调用 ConfigManager.getInstance()，
        // 存在类初始化循环依赖风险（ConfigManager 可能尚未初始化完成）。
        // 当前依赖双检锁的局部变量模式保证可见性，但建议后续重构时改为延迟初始化
        // （在首次 freezeProcess/unfreezeProcess 调用时懒加载 freezer）。
        selectFreezer();
    }

    // P3-R6: 使用双重检查锁定
    public static FreezeManager getInstance() {
        FreezeManager local = instance;
        if (local == null) {
            synchronized (FreezeManager.class) {
                local = instance;
                if (local == null) {
                    local = new FreezeManager();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * 判断指定 pid 是否处于冻结抑制状态（轮换解冻窗口期间）。
     *
     * <p>M2: 提供统一的冻结抑制查询入口，供所有冻结调用方在执行前检查。
     */
    public static boolean isFreezeSuppressed(int pid) {
        return freezeSuppressedPids.contains(pid);
    }

    /**
     * 抑制对指定 pid 的冻结（轮换解冻窗口期间禁止重新冻结）。
     *
     * <p>M2: 由 {@link RotationThawManager} 在解冻前调用，确保 3 秒解冻窗口期间
     * 不会被任何冻结入口（ScheduledFreezeManager / ScreenStateHook / ActivitySwitchHook 等）
     * 重新冻结。所有冻结入口最终都经过 {@link #freezeProcess}，在其中统一检查抑制集合。
     */
    public static void suppressFreeze(int pid) {
        freezeSuppressedPids.add(pid);
    }

    /**
     * 解除对指定 pid 的冻结抑制。
     *
     * <p>M2: 由 {@link RotationThawManager} 在重新冻结后或任何早返回/异常路径上调用，
     * 确保抑制不会永久残留。
     */
    public static void unsuppressFreeze(int pid) {
        freezeSuppressedPids.remove(pid);
    }

    /**
     * 清理死亡进程在冻结抑制集合中的残留条目。
     *
     * <p>R7: freezeSuppressedPids 是静态集合，进程死亡后 pid 可能被新进程复用，
     * 导致新进程被错误抑制冻结。由 {@link com.tombstonex.hook.ProcessDeathHook#cleanupDeadProcess}
     * 在 removeProcess 之后调用。注意：ScheduledFreezeManager.lastFreezeTime 的清理
     * 已由 {@link ProcessTracker#removeProcess} 内部调用 clearLastFreezeTime 完成，
     * 无需在此重复。
     */
    public static void cleanupSuppressedPid(int pid) {
        freezeSuppressedPids.remove(pid);
    }

    private void selectFreezer() {
        FreezeMode mode = ConfigManager.getInstance().getFreezeMode();
        Logger.i("正在选择冻结器: " + mode);

        switch (mode) {
            case SYSTEM_API:
                currentFreezer = new SystemApiFreezer();
                if (currentFreezer.isAvailable()) break;
                Logger.w("SystemApi 冻结器不可用，回退到 CgroupV2");
            case CGROUP_V2:
                currentFreezer = new CgroupFreezerV2();
                if (currentFreezer.isAvailable()) break;
                Logger.w("CgroupV2 不可用，回退到 CgroupV1");
            case CGROUP_V1:
                currentFreezer = new CgroupFreezerV1();
                if (currentFreezer.isAvailable()) break;
                Logger.w("CgroupV1 不可用，回退到 Signal");
            case SIGNAL_20:
                currentFreezer = new SignalFreezer(true);  // SIGTSTP=20
                if (currentFreezer.isAvailable()) break;
                // 回退到 SIGNAL_19
            case SIGNAL_19:
                currentFreezer = new SignalFreezer(false);  // SIGSTOP=19
                if (currentFreezer.isAvailable()) break;
                // 最终回退
            default:
                Logger.w("无可用冻结器");
                currentFreezer = null;
                break;
        }
        if (currentFreezer != null) {
            Logger.i("已选择冻结器: " + currentFreezer.getName()
                + " available=" + currentFreezer.isAvailable());
        } else {
            Logger.w("未选择冻结器");
        }
    }

    public boolean freezeProcess(int pid, int uid) {
        if (pid <= 0) return false;
        synchronized (freezeLock) {
            // 通过文件标记检查全局暂停状态（跨进程同步）
            if (ConfigManager.getInstance().isGlobalPaused()) {
                Logger.d("全局已暂停，跳过冻结: pid=" + pid);
                return false;
            }

            // 白名单检查作为防御层
            AppInfo info = ProcessTracker.getInstance().getByPid(pid);
            if (info == null) {
                // S8: 进程未注册则拒绝冻结，避免对未知进程执行"幽灵冻结"
                Logger.w("进程未注册，拒绝冻结: pid=" + pid);
                return false;
            }
            // M2: 冻结抑制检查 —— 轮换解冻窗口期间禁止任何调用方重新冻结该 pid，
            // 避免 ScreenStateHook/ActivitySwitchHook/ScheduledFreezeManager 等在解冻窗口内重新冻结。
            if (freezeSuppressedPids.contains(pid)) {
                Logger.d("进程冻结被抑制（轮换解冻窗口）: pid=" + pid);
                return false;
            }
            // M1: 校验 caller 传入的 uid 与 ProcessTracker 中刚获取的 info.uid 一致，
            // 避免因 pid 被复用导致对错误 uid 的进程执行冻结（cgroup 路径错乱等）。
            if (info.uid != uid) {
                Logger.w("UID 不匹配，拒绝冻结: pid=" + pid + " uid=" + uid
                    + " info.uid=" + info.uid);
                return false;
            }
            // M-13: 防御性检查 —— packageName 为 null 时 shouldFreeze 内部 contains() 会 NPE。
            // 虽理论上不应发生（registerProcess 始终传入 packageName），但作为防御层保留。
            if (info.packageName == null) {
                Logger.w("包名为空，拒绝冻结: pid=" + pid);
                return false;
            }
            if (!WhitelistManager.getInstance().shouldFreeze(
                    info.packageName, info.processName, info.isSystemApp())) {
                Logger.d("应用在白名单中，跳过冻结（防御性）: " + info.packageName);
                return false;
            }
            // 冻结去重：检查是否已冻结
            if (info.getState() == AppState.FROZEN) {
                Logger.d("进程已冻结，跳过: pid=" + pid);
                return true;
            }
            // P1-N1: 前台进程不冻结，防止竞态条件下冻结前台应用导致 ANR。
            // 延迟冻结任务在调度时检查过 FOREGROUND，但进入 freezeLock 前进程可能已切回前台。
            if (info.getState() == AppState.FOREGROUND) {
                Logger.d("进程为前台，跳过冻结: pid=" + pid);
                return false;
            }
            // 轻微-3: 已终止的进程不冻结，避免对已死亡进程执行无意义的 cgroup/信号操作。
            if (info.getState() == AppState.KILLED) {
                Logger.d("进程已终止，跳过冻结: pid=" + pid);
                return false;
            }

            if (currentFreezer == null) {
                Logger.w("无可用冻结器，无法冻结: pid=" + pid);
                return false;
            }
            // M1: 使用 info.uid（权威）而非 caller 传入的 uid，避免 pid 复用场景下的错乱。
            boolean result = currentFreezer.freeze(pid, info.uid);
            if (result) {
                ProcessTracker.getInstance().updateState(pid, AppState.FROZEN);
                // 通知 NetworkHook 断网
                try {
                    com.tombstonex.hook.NetworkHook.onProcessFrozen(info.uid, info.packageName);
                } catch (Exception e) {
                    // M6-修复: 仅捕获 Exception，Error 子类（如 NoClassDefFoundError）应向上传播
                    // NetworkHook 可能未初始化，忽略
                }
            }
            return result;
        }
    }

    /**
     * 冻结一个包名的所有进程（多进程支持）
     */
    public int freezePackage(String packageName) {
        int count = 0;
        List<AppInfo> processes = ProcessTracker.getInstance().getAllByPackage(packageName);
        for (AppInfo info : processes) {
            // M3: 移除 info.isWhiteListed() 短路检查，统一由 freezeProcess 内部调用
            // WhitelistManager.shouldFreeze() 进行权威判断，避免缓存过期导致的误冻结/漏冻结。
            if (info.getState() != AppState.FROZEN) {
                if (freezeProcess(info.pid, info.uid)) count++;
            }
        }
        return count;
    }

    public boolean unfreezeProcess(int pid, int uid) {
        if (pid <= 0) return false;
        synchronized (freezeLock) {
            // 检查进程注册信息
            AppInfo info = ProcessTracker.getInstance().getByPid(pid);
            // 轻微-4: info==null 时不应继续执行 unfreeze（会向未注册的 pid 发送信号/操作 cgroup）。
            if (info == null) {
                Logger.d("进程未注册，跳过解冻: pid=" + pid);
                return false;
            }
            // L1: 已终止的进程不解冻，避免对已死亡进程执行无意义的信号/cgroup 操作。
            if (info.getState() == AppState.KILLED) {
                Logger.d("进程已终止，跳过解冻: pid=" + pid);
                return false;
            }
            // 检查是否已解冻
            if (info.getState() != AppState.FROZEN) {
                return true;
            }
            // M1: 校验 uid 一致性，避免 pid 复用导致对错误 uid 的进程执行解冻。
            if (info.uid != uid) {
                Logger.w("UID 不匹配，拒绝解冻: pid=" + pid + " uid=" + uid
                    + " info.uid=" + info.uid);
                return false;
            }
            if (currentFreezer == null) {
                Logger.w("无可用冻结器，无法解冻: pid=" + pid);
                return false;
            }
            // M1: 使用 info.uid（权威）而非 caller 传入的 uid，避免 pid 复用场景下的错乱。
            boolean result = currentFreezer.unfreeze(pid, info.uid);
            if (result) {
                ProcessTracker.getInstance().updateState(pid, AppState.BACKGROUND);
                // 通知 NetworkHook 恢复网络
                try {
                    com.tombstonex.hook.NetworkHook.onProcessUnfrozen(info.uid, info.packageName);
                } catch (Exception e) {
                    // M6-修复: 仅捕获 Exception，Error 子类应向上传播
                    // NetworkHook 可能未初始化，忽略
                }
            }
            return result;
        }
    }

    /**
     * 解冻一个包名的所有进程
     */
    public int unfreezePackage(String packageName) {
        int count = 0;
        List<AppInfo> processes = ProcessTracker.getInstance().getAllByPackage(packageName);
        for (AppInfo info : processes) {
            if (info.getState() == AppState.FROZEN) {
                if (unfreezeProcess(info.pid, info.uid)) count++;
            }
        }
        return count;
    }

    /**
     * 全局暂停 — 解冻所有冻结的进程，并写入暂停标记文件
     */
    public void pauseAll() {
        ConfigManager.getInstance().setGlobalPaused(true);
        ProcessTracker.getInstance().unfreezeAll(this);
        Logger.i("全局已暂停，所有进程已解冻");
    }

    /**
     * 恢复全局冻结 — 清除暂停标记文件
     */
    public void resumeAll() {
        ConfigManager.getInstance().setGlobalPaused(false);
        Logger.i("全局已恢复，冻结将继续正常运行");
    }

    public boolean isGlobalPaused() {
        return ConfigManager.getInstance().isGlobalPaused();
    }

    public String getCurrentFreezerName() {
        return currentFreezer != null ? currentFreezer.getName() : "None";
    }

    public void reselectFreezer() {
        synchronized (freezeLock) {
            // 先解冻所有已冻结的进程，防止切换 freezer 后状态不一致
            unfreezeAllFrozen();
            selectFreezer();
        }
    }

    /**
     * 解冻所有已冻结的进程
     */
    private void unfreezeAllFrozen() {
        List<AppInfo> frozenList = new ArrayList<>(
            ProcessTracker.getInstance().getFrozenProcesses());
        for (AppInfo info : frozenList) {
            if (currentFreezer != null && currentFreezer.unfreeze(info.pid, info.uid)) {
                ProcessTracker.getInstance().updateState(info.pid, AppState.BACKGROUND);
                // M18: 通知 NetworkHook 恢复网络（与 unfreezeProcess 保持一致）
                try {
                    com.tombstonex.hook.NetworkHook.onProcessUnfrozen(info.uid, info.packageName);
                } catch (Exception e) {
                    // M6-修复: 仅捕获 Exception，Error 子类应向上传播
                    // NetworkHook 可能未初始化，忽略
                }
            } else {
                Logger.w("切换冻结器期间解冻 pid=" + info.pid + " 失败");
            }
        }
        Logger.i("切换冻结器前已解冻所有冻结进程: " + frozenList.size());
    }
}
