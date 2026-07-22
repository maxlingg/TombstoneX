package com.tombstonex.hook;

import android.os.IBinder;
import android.os.SystemClock;
import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import com.tombstonex.util.ReflectionUtils;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 智能状态识别 Hook
 *
 * 检测应用是否正在使用关键功能（通话/定位/录音/相机/VPN/无障碍/输入法/自动填充/
 * 可见窗口/常驻通知/媒体播放/音频焦点），避免 TombstoneX 误冻结正在使用关键功能的应用。
 *
 * 设计要点：
 * 1. 运行于 system_server，通过 ServiceManager 获取系统服务（与 ActivitySwitchHook.isAnyAudioPlaying 一致）。
 * 2. 每个状态检测方法独立，异常时返回 false（表示"未检测到活跃状态，可冻结"），
 *    遵循安全优先原则——单个检测失败不阻塞整体冻结流程。
 * 3. 检测结果使用 ConcurrentHashMap 缓存，500ms TTL，避免频繁查询系统服务。
 *
 * 对外提供静态方法 isAppActive(int uid, String packageName) 供其他 Hook 调用，
 * 返回 true 表示该应用正在使用关键功能，不应冻结。
 *
 * 已知限制：
 * - 部分系统服务方法为 hidden API，跨版本签名可能变化；反射失败时按"未活跃"处理。
 * - 全局状态（通话/音频通信模式/媒体播放）会与 per-uid 音频焦点联合判断，
 *   以尽量缩小影响范围，但仍可能比理想情况保守。
 */
public class SmartStateHook {

    /** 检测结果缓存 TTL（毫秒） */
    private static final long CACHE_TTL_MS = 500L;
    /** M-7: 全局状态缓存 TTL（毫秒），批量冻结期间复用全局检测结果 */
    private static final long GLOBAL_STATE_CACHE_TTL_MS = 2000L;
    /** 定期清理阈值：条目超过此时间未刷新则视为过期残留并移除（保留原有阈值） */
    private static final long CLEANUP_THRESHOLD_MS = 5000; // 10x TTL

    // Audio 音频模式常量（AudioManager.MODE_IN_COMMUNICATION）
    private static final int MODE_IN_COMMUNICATION = 3;
    // Audio 通话状态常量（TelephonyManager.CALL_STATE_IDLE = 0）
    private static final int CALL_STATE_IDLE = 0;
    // Notification.FLAG_ONGOING_EVENT
    private static final int NOTIFICATION_FLAG_ONGOING_EVENT = 0x40;
    // NetworkCapabilities.TRANSPORT_VPN
    private static final int TRANSPORT_VPN = 4;

    // AppOps 操作码默认值（运行时优先通过反射读取 AppOpsManager 常量，失败时使用此默认值）
    private static final int OP_FINE_LOCATION_DEFAULT = 1;
    private static final int OP_COARSE_LOCATION_DEFAULT = 0;
    private static final int OP_CAMERA_DEFAULT = 26;
    private static final int OP_RECORD_AUDIO_DEFAULT = 27;

    /** 缓存的系统服务引用（懒加载，volatile 保证可见性） */
    private static volatile Object telephonyService;
    private static volatile Object audioService;
    private static volatile Object appOpsService;
    private static volatile Object connectivityService;
    private static volatile Object accessibilityService;
    private static volatile Object inputMethodService;
    private static volatile Object autofillService;
    private static volatile Object windowService;
    private static volatile Object notificationService;

    /** 缓存条目：保存单个 (uid, packageName) 的检测结果与时间戳 */
    private static final class CacheEntry {
        final boolean active;
        final long timestamp;

        CacheEntry(boolean active, long timestamp) {
            this.active = active;
            this.timestamp = timestamp;
        }
    }

    /** 检测结果缓存：key = uid + ":" + packageName */
    private static final ConcurrentHashMap<String, CacheEntry> activeCache = new ConcurrentHashMap<>();

    // R10-m-7: 缓存已解析的 AppOpsManager 常量值，避免每次 getOpCode 都反射获取字段。
    // AppOpsManager 常量在同一进程内稳定不变，缓存安全。
    private static final ConcurrentHashMap<String, Integer> opCodeCache = new ConcurrentHashMap<>();

    // M-7: 全局状态缓存（不依赖于 uid/packageName 的检测结果）。
    // 批量冻结开始时通过 precacheGlobalState() 预填充，批量冻结期间各进程复用，
    // 避免逐进程重复执行全局 Binder IPC 查询（isInCall / isMusicActive / isAudioInCommunication）。
    private static volatile long globalStateCacheTime = 0;
    private static volatile boolean cachedIsInCall = false;
    private static volatile boolean cachedIsMusicActive = false;
    private static volatile boolean cachedIsAudioInCommunication = false;

    // L2: 定期清理 activeCache 中过期条目的调度器（单线程守护线程）
    // R9-m9-8: cacheCleanupExecutor 设计上永不关闭。其线程为守护线程（setDaemon(true)），
    // 不会阻止 JVM/system_server 退出。运行于 system_server 生命周期内，关闭调度器无实际收益，
    // 当前现状可接受。
    private static final ScheduledThreadPoolExecutor cacheCleanupExecutor = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "TombstoneX-SmartState");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean cleanupTaskStarted = new AtomicBoolean(false);

    public static void init(ClassLoader classLoader) {
        Logger.i("SmartStateHook 已初始化（可查询 API: isAppActive，缓存 TTL=" + CACHE_TTL_MS + "ms）");
        // 预加载系统服务引用，单次失败不影响后续懒加载兜底
        preloadServices();
        // L2: 定期清理 activeCache 中过期条目，避免无界增长
        // 正常情况下条目 TTL=500ms 会自然过期（isAppActive 命中时刷新），
        // 但若某应用仅查询一次后不再被查询，其条目会永久驻留，因此每 5 分钟兜底清理一次。
        if (cleanupTaskStarted.compareAndSet(false, true)) {
            cacheCleanupExecutor.scheduleAtFixedRate(() -> {
                try {
                    long now = SystemClock.elapsedRealtime();
                    activeCache.entrySet().removeIf(e -> (now - e.getValue().timestamp) >= CLEANUP_THRESHOLD_MS);
                } catch (Throwable t) {
                    Logger.e("activeCache 定期清理出错", t);
                }
            }, 5, 5, TimeUnit.MINUTES);
        }
    }

    private static void preloadServices() {
        try { telephonyService = getService("phone", "com.android.internal.telephony.ITelephony$Stub"); } catch (Throwable ignored) {
            Logger.d("预加载 telephonyService 失败: " + ignored.getMessage());
        }
        try { audioService = getService("audio", "android.media.IAudioService$Stub"); } catch (Throwable ignored) {
            Logger.d("预加载 audioService 失败: " + ignored.getMessage());
        }
        try { appOpsService = getService("appops", "com.android.internal.app.IAppOpsService$Stub"); } catch (Throwable ignored) {
            Logger.d("预加载 appOpsService 失败: " + ignored.getMessage());
        }
        try { connectivityService = getService("connectivity", "android.net.IConnectivityManager$Stub"); } catch (Throwable ignored) {
            Logger.d("预加载 connectivityService 失败: " + ignored.getMessage());
        }
        try { accessibilityService = getService("accessibility", "android.accessibilityservice.IAccessibilityManager$Stub"); } catch (Throwable ignored) {
            Logger.d("预加载 accessibilityService 失败: " + ignored.getMessage());
        }
        try { notificationService = getService("notification", "android.app.INotificationManager$Stub"); } catch (Throwable ignored) {
            Logger.d("预加载 notificationService 失败: " + ignored.getMessage());
        }
        try { windowService = getService("window", "android.view.IWindowManager$Stub"); } catch (Throwable ignored) {
            Logger.d("预加载 windowService 失败: " + ignored.getMessage());
        }
        try { autofillService = getService("autofill", "android.view.autofill.IAutoFillManager$Stub"); } catch (Throwable ignored) {
            Logger.d("预加载 autofillService 失败: " + ignored.getMessage());
        }
        // InputMethod stub 类名跨版本不同，单独处理
        try {
            inputMethodService = getServiceWithFallbacks("input_method",
                "com.android.internal.inputmethod.IInputMethodManager$Stub",
                "android.view.inputmethod.IInputMethodManager$Stub");
        } catch (Throwable ignored) {
            Logger.d("预加载 inputMethodService 失败: " + ignored.getMessage());
        }
    }

    /**
     * 判断指定应用是否正在使用关键功能（不应冻结）。
     * 结果缓存 500ms，避免频繁查询系统服务。
     *
     * @param uid         目标应用 uid
     * @param packageName 目标应用包名
     * @return true 表示该应用正在使用关键功能，不应冻结；false 表示可安全冻结
     */
    public static boolean isAppActive(int uid, String packageName) {
        if (packageName == null || packageName.isEmpty() || uid < 0) return false;
        String key = uid + ":" + packageName;
        long now = SystemClock.elapsedRealtime();
        CacheEntry entry = activeCache.get(key);
        if (entry != null && (now - entry.timestamp) < CACHE_TTL_MS) {
            return entry.active;
        }
        boolean active = computeAppActive(uid, packageName);
        activeCache.put(key, new CacheEntry(active, now));
        return active;
    }

    /** 清除全部缓存（配置变更或手动触发时调用） */
    public static void invalidateCache() {
        activeCache.clear();
    }

    /** 清除指定应用的缓存条目 */
    public static void invalidateCache(int uid, String packageName) {
        if (packageName == null) return;
        activeCache.remove(uid + ":" + packageName);
    }

    /**
     * M-7: 预缓存全局状态检测结果，供批量冻结期间各进程复用。
     * 在 ScreenStateHook.batchFreezeAll 开始时调用，一次性执行全局 Binder IPC 查询
     * （isInCall / isMusicActive / isAudioInCommunication），后续 computeAppActive
     * 中这些全局检查将命中缓存，避免逐进程重复查询。
     */
    public static void precacheGlobalState() {
        try {
            cachedIsInCall = isInCallInternal();
            cachedIsMusicActive = isMusicActiveInternal();
            cachedIsAudioInCommunication = isAudioInCommunicationInternal();
            globalStateCacheTime = SystemClock.elapsedRealtime();
            Logger.d("M-7: 全局状态预缓存完成 inCall=" + cachedIsInCall
                + " musicActive=" + cachedIsMusicActive
                + " audioComm=" + cachedIsAudioInCommunication);
        } catch (Throwable t) {
            Logger.d("M-7: 全局状态预缓存失败: " + t.getMessage());
        }
    }

    /** M-7: 检查全局状态缓存是否有效 */
    private static boolean isGlobalStateCacheValid() {
        return globalStateCacheTime > 0
            && (SystemClock.elapsedRealtime() - globalStateCacheTime) < GLOBAL_STATE_CACHE_TTL_MS;
    }

    /**
     * 综合判定应用是否活跃。每项检测独立，任一命中即返回 true。
     * 顺序按"系统服务查询成本/命中率"粗略排列，命中后短路返回。
     *
     * M-7: 性能限制——每次调用 computeAppActive 会触发多次 Binder IPC（通话状态、
     * AppOps 查询、音频服务查询等）。在批量冻结中逐进程调用时，全局状态检测
     * （isInCall / isMusicActive / isAudioInCommunication）会被重复执行，
     * 尽管它们不依赖于具体 uid/packageName。ScreenStateHook.batchFreezeAll 开始时
     * 应调用 precacheGlobalState() 预缓存全局状态，减少重复 Binder IPC。
     * 当前 activeCache 的 500ms TTL 已部分缓解此问题（同一 uid+pkg 的重复调用命中缓存），
     * 但不同 uid 的全局检测仍会重复执行。
     */
    private static boolean computeAppActive(int uid, String packageName) {
        // 1. 通话中（电话/拨号相关包）
        if (isInCall() && isPhoneRelatedPackage(packageName)) return true;
        // 2. 正在定位
        if (isLocating(uid, packageName)) return true;
        // 3. 正在录音（AppOps 录音 op 活跃，或处于通信模式且持有音频焦点）
        if (isRecordingAudio(uid, packageName)) return true;
        if (isAudioInCommunication() && hasAudioFocus(uid)) return true;
        // 4. 相机使用
        if (isCameraInUse(uid, packageName)) return true;
        // 5. VPN 连接（VPN 拥有者应用）
        if (isVpnConnectedForUid(uid)) return true;
        // 6. 无障碍服务
        if (isAccessibilityServiceActive(packageName)) return true;
        // 7. 输入法服务（当前默认输入法）
        if (isInputMethodActive(packageName)) return true;
        // 8. 自动填充服务
        if (isAutofillServiceActive(packageName)) return true;
        // 9. 可见窗口
        if (hasVisibleWindow(uid)) return true;
        // 10. 常驻通知（ongoing）
        if (hasOngoingNotification(uid, packageName)) return true;
        // 11. 媒体播放 + 音频会话
        // R8-M8-3: isMusicActive 失败时 fail-closed（返回 true 保守认为有播放），
        // hasAudioFocus 失败时 fail-open（返回 false）。旧代码 "isMusicActive() && hasAudioFocus(uid)"
        // 组合导致 isMusicActive 失败（返回 true）时若 hasAudioFocus 也失败（返回 false），
        // 整体返回 false（fail-open），可能误冻结正在播放的应用。
        // R10-M-3: isMusicActive() 已由 fail-closed 改为 fail-open（失败时返回 false）。
        // R11-M-3: hasAudioFocus 失败时同样 fail-open，不提供兜底保护。
        // M-5: 旧代码 isMusicActive() 全局检查过于保守——只要有任意应用播放音乐，
        // 所有后台应用都会被跳过冻结。改为仅在 hasAudioFocus(uid) 也为 true 时才返回 true，
        // 即仅跳过持有音频焦点的应用，而非全局阻断所有冻结。
        // 这样在应用 A 播放音乐时，不持有音频焦点的应用 B 仍可被冻结。
        if (isMusicActive() && hasAudioFocus(uid)) return true;
        // M3 修复：移除原 item 12 "if (hasAudioFocus(uid)) return true;"
        // 该检查过于保守——许多应用持有音频焦点但并未播放（如暂停的音乐播放器），
        // item 11 的 "isMusicActive() && hasAudioFocus(uid)" 已覆盖真实播放场景。
        return false;
    }

    // ==================== 各项独立检测方法 ====================
    // 每个方法独立 try/catch，异常时返回 false（按"未活跃"处理，不阻塞冻结）

    /**
     * 通话中：TelephonyManager.getCallState() != CALL_STATE_IDLE（全局状态）
     * M-7: 优先使用全局状态缓存，缓存未命中时执行实际查询。
     */
    private static boolean isInCall() {
        if (isGlobalStateCacheValid()) return cachedIsInCall;
        return isInCallInternal();
    }

    /** M-7: isInCall 的实际查询逻辑（不检查缓存） */
    private static boolean isInCallInternal() {
        try {
            Object ts = getTelephonyService();
            if (ts == null) return false;
            // 优先尝试无参 getCallState()
            Method m = ReflectionUtils.findMethodRecursive(ts.getClass(), "getCallState");
            if (m != null) {
                Object r = m.invoke(ts);
                // R8-m8-3: 无参返回 Integer 时直接判定；返回非 Integer 时回退到带参变体
                if (r instanceof Integer) return ((Integer) r) != CALL_STATE_IDLE;
            }
            // R8-m8-3: 带参变体作为回退（无参方法不存在或返回非 Integer 时）
            m = ReflectionUtils.findMethodRecursive(ts.getClass(), "getCallState", String.class);
            if (m != null) {
                // R8-m8-8: 使用空字符串替代 "com.tombstonex"，在 system_server 上下文中更合适
                Object r = m.invoke(ts, "");
                if (r instanceof Integer) return ((Integer) r) != CALL_STATE_IDLE;
            }
            m = ReflectionUtils.findMethodRecursive(ts.getClass(), "getCallState", int.class);
            if (m != null) {
                Object r = m.invoke(ts, -1);
                if (r instanceof Integer) return ((Integer) r) != CALL_STATE_IDLE;
            }
        } catch (InvocationTargetException e) {
            // R8-M8-4: 解包反射调用目标异常，记录真实 cause
            Throwable cause = e.getCause();
            // R11-M-1: 检测 Binder 代理失效，重置缓存
            if (isDeadObject(e)) {
                telephonyService = null;
            }
            Logger.d("isInCall 检查失败: " + (cause != null ? cause : e).getMessage());
        } catch (Throwable t) {
            // R11-M-1: 检测 Binder 代理失效，重置缓存
            if (isDeadObject(t)) {
                telephonyService = null;
            }
            Logger.d("isInCall 检查失败: " + t.getMessage());
        }
        return false;
    }

    /** 包名是否为电话/拨号/电信相关 */
    private static boolean isPhoneRelatedPackage(String packageName) {
        if (packageName == null) return false;
        if ("com.android.phone".equals(packageName)
            || "com.android.dialer".equals(packageName)
            || "com.android.server.telecom".equals(packageName)
            || "com.google.android.dialer".equals(packageName)) {
            return true;
        }
        return packageName.contains("dialer")
            || packageName.contains("incallui")
            || packageName.contains("telecom");
    }

    /** 正在定位：AppOps OP_FINE_LOCATION 对该 uid 处于 active 状态 */
    private static boolean isLocating(int uid, String packageName) {
        try {
            Object appOps = getAppOpsService();
            if (appOps == null) return false;
            int op = getOpCode("OP_FINE_LOCATION", OP_FINE_LOCATION_DEFAULT);
            Boolean r = callIsOperationActive(appOps, op, uid, packageName);
            if (r == null) {
                // 降级尝试 OP_COARSE_LOCATION
                r = callIsOperationActive(appOps, getOpCode("OP_COARSE_LOCATION", OP_COARSE_LOCATION_DEFAULT), uid, packageName);
            }
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            // R11-M-1: 检测 Binder 代理失效，重置缓存
            if (isDeadObject(t)) {
                appOpsService = null;
            }
            Logger.d("isLocating 检查失败: " + t.getMessage());
        }
        return false;
    }

    /** 正在录音：AppOps OP_RECORD_AUDIO 对该 uid 处于 active 状态 */
    private static boolean isRecordingAudio(int uid, String packageName) {
        try {
            Object appOps = getAppOpsService();
            if (appOps == null) return false;
            int op = getOpCode("OP_RECORD_AUDIO", OP_RECORD_AUDIO_DEFAULT);
            return Boolean.TRUE.equals(callIsOperationActive(appOps, op, uid, packageName));
        } catch (Throwable t) {
            // R11-M-1: 检测 Binder 代理失效，重置缓存
            if (isDeadObject(t)) {
                appOpsService = null;
            }
            Logger.d("isRecordingAudio 检查失败: " + t.getMessage());
        }
        return false;
    }

    /** 相机使用：AppOps OP_CAMERA 对该 uid 处于 active 状态 */
    private static boolean isCameraInUse(int uid, String packageName) {
        try {
            Object appOps = getAppOpsService();
            if (appOps == null) return false;
            int op = getOpCode("OP_CAMERA", OP_CAMERA_DEFAULT);
            return Boolean.TRUE.equals(callIsOperationActive(appOps, op, uid, packageName));
        } catch (Throwable t) {
            // R11-M-1: 检测 Binder 代理失效，重置缓存
            if (isDeadObject(t)) {
                appOpsService = null;
            }
            Logger.d("isCameraInUse 检查失败: " + t.getMessage());
        }
        return false;
    }

    /**
     * 音频模式为 MODE_IN_COMMUNICATION（VoIP/对讲等，全局状态）
     * M-7: 优先使用全局状态缓存，缓存未命中时执行实际查询。
     */
    private static boolean isAudioInCommunication() {
        if (isGlobalStateCacheValid()) return cachedIsAudioInCommunication;
        return isAudioInCommunicationInternal();
    }

    /** M-7: isAudioInCommunication 的实际查询逻辑（不检查缓存） */
    private static boolean isAudioInCommunicationInternal() {
        try {
            Object as = getAudioService();
            if (as == null) return false;
            Method m = ReflectionUtils.findMethodRecursive(as.getClass(), "getMode");
            if (m != null) {
                Object r = m.invoke(as);
                if (r instanceof Integer) return ((Integer) r) == MODE_IN_COMMUNICATION;
            }
        } catch (Throwable t) {
            // R11-M-1: 检测 Binder 代理失效，重置缓存
            if (isDeadObject(t)) {
                audioService = null;
            }
            Logger.d("isAudioInCommunication 检查失败: " + t.getMessage());
        }
        return false;
    }

    /**
     * 媒体播放中：AudioManager.isMusicActive()（全局状态）
     *
     * R10-M-3: 改为 fail-open（失败返回 false），避免音频服务不可用时所有应用被跳过冻结。
     * R11-M-3: 同步更新 JavaDoc。
     * M-7: 优先使用全局状态缓存，缓存未命中时执行实际查询。
     */
    private static boolean isMusicActive() {
        if (isGlobalStateCacheValid()) return cachedIsMusicActive;
        return isMusicActiveInternal();
    }

    /**
     * M-7: isMusicActive 的实际查询逻辑（不检查缓存）
     *
     * R10-M-3: 改为 fail-open（失败返回 false），避免音频服务不可用时所有应用被跳过冻结。
     * R11-M-3: 同步更新 JavaDoc。
     */
    private static boolean isMusicActiveInternal() {
        try {
            Object as = getAudioService();
            // R10-M-3: 将 fail-closed 改为 fail-open。
            // 旧代码在 getAudioService() 返回 null 或反射失败时返回 true（保守认为有媒体播放），
            // 导致所有应用被认为活跃而跳过冻结，批量冻结可能完全失效。
            // 现改为返回 false（按"未检测到媒体播放"处理），仅在成功调用 isMusicActive() 且
            // 返回 true 时才返回 true。其他 per-uid 检测（hasAudioFocus / isAudioInCommunication）
            // 仍提供细粒度保护。
            if (as == null) {
                Logger.w("isMusicActive: AudioService 不可用，fail-open 返回 false");
                return false;
            }
            Method m = ReflectionUtils.findMethodRecursive(as.getClass(), "isMusicActive");
            if (m != null) {
                Object r = m.invoke(as);
                return Boolean.TRUE.equals(r);
            }
            Logger.w("isMusicActive: 未找到 isMusicActive 方法，fail-open 返回 false");
            return false;
        } catch (InvocationTargetException e) {
            // R8-M8-4: 解包反射调用目标异常，记录真实 cause
            Throwable cause = e.getCause();
            // R11-M-1: 检测 Binder 代理失效，重置缓存
            if (isDeadObject(e)) {
                audioService = null;
            }
            Logger.w("isMusicActive 检查失败，fail-open 返回 false: "
                + (cause != null ? cause : e).getMessage());
            return false; // R10-M-3: fail-open，失败时按"无媒体播放"处理
        } catch (Throwable t) {
            // R11-M-1: 检测 Binder 代理失效，重置缓存
            if (isDeadObject(t)) {
                audioService = null;
            }
            Logger.w("isMusicActive 检查失败，fail-open 返回 false: " + t.getMessage());
            return false; // R10-M-3: fail-open，失败时按"无媒体播放"处理
        }
    }

    /** 持有音频焦点：该 uid 为当前音频焦点持有者 */
    private static boolean hasAudioFocus(int uid) {
        try {
            Object as = getAudioService();
            if (as == null) return false;
            // 优先尝试 getAudioFocusOwners()（返回 List<AudioFocusInfo>）
            Method m = ReflectionUtils.findMethodRecursive(as.getClass(), "getAudioFocusOwners");
            if (m != null) {
                Object r = m.invoke(as);
                if (r instanceof List) {
                    for (Object info : (List<?>) r) {
                        Integer ownerUid = getAudioFocusOwnerUid(info);
                        if (ownerUid != null && ownerUid == uid) return true;
                    }
                }
                return false;
            }
            // 降级尝试 getAudioFocusOwner()（单个 AudioFocusInfo）
            m = ReflectionUtils.findMethodRecursive(as.getClass(), "getAudioFocusOwner");
            if (m != null) {
                Object info = m.invoke(as);
                if (info != null) {
                    Integer ownerUid = getAudioFocusOwnerUid(info);
                    return ownerUid != null && ownerUid == uid;
                }
                return false;
            }
        } catch (InvocationTargetException e) {
            // R8-M8-4: 解包反射调用目标异常，记录真实 cause
            Throwable cause = e.getCause();
            // R11-M-1: 检测 Binder 代理失效，重置缓存
            if (isDeadObject(e)) {
                audioService = null;
            }
            Logger.d("hasAudioFocus 检查失败: " + (cause != null ? cause : e).getMessage());
        } catch (Throwable t) {
            // R11-M-1: 检测 Binder 代理失效，重置缓存
            if (isDeadObject(t)) {
                audioService = null;
            }
            Logger.d("hasAudioFocus 检查失败: " + t.getMessage());
        }
        return false;
    }

    /** VPN 连接：存在 VPN 传输网络，且目标 uid 为 VPN 拥有者应用 */
    private static boolean isVpnConnectedForUid(int uid) {
        try {
            Object cs = getConnectivityService();
            if (cs == null) return false;
            if (!hasAnyVpnNetwork(cs)) return false;
            // 尝试获取 VPN 拥有者包名并解析为 uid 比较
            String vpnPkg = getVpnOwnerPackage(cs);
            if (vpnPkg == null) {
                // 无法确定 VPN 拥有者：VPN 应用通常持有前台服务，已被 hasForegroundService 保护，
                // 这里按"未活跃"返回 false，不阻塞冻结流程。
                return false;
            }
            Integer vpnUid = getPackageUid(vpnPkg);
            return vpnUid != null && vpnUid == uid;
        } catch (Throwable t) {
            // R11-M-1: 检测 Binder 代理失效，重置缓存
            if (isDeadObject(t)) {
                connectivityService = null;
            }
            Logger.d("isVpnConnectedForUid 检查失败: " + t.getMessage());
        }
        return false;
    }

    /** 无障碍服务：packageName 为已启用的无障碍服务 */
    private static boolean isAccessibilityServiceActive(String packageName) {
        try {
            Object as = getAccessibilityService();
            if (as == null) return false;
            // getEnabledAccessibilityServiceList(int feedbackType) — feedbackType=-1 表示全部
            Method m = ReflectionUtils.findMethodRecursive(
                as.getClass(), "getEnabledAccessibilityServiceList", int.class);
            if (m == null) {
                // 部分版本为 (int, int) 签名
                m = ReflectionUtils.findMethodRecursive(
                    as.getClass(), "getEnabledAccessibilityServiceList", int.class, int.class);
            }
            if (m == null) return false;
            Object result = (m.getParameterCount() == 1)
                ? m.invoke(as, -1)
                : m.invoke(as, -1, 0);
            if (result instanceof List) {
                for (Object info : (List<?>) result) {
                    String pn = getServiceInfoPackageName(info);
                    if (packageName.equals(pn)) return true;
                }
            }
        } catch (Throwable t) {
            // R11-M-1: 检测 Binder 代理失效，重置缓存
            if (isDeadObject(t)) {
                accessibilityService = null;
            }
            Logger.d("isAccessibilityServiceActive 检查失败: " + t.getMessage());
        }
        return false;
    }

    /** 输入法服务：packageName 为当前默认输入法 */
    private static boolean isInputMethodActive(String packageName) {
        // 优先通过 Settings.Secure.default_input_method 获取当前输入法包名（最准确）
        String currentIme = getCurrentImePackage();
        if (currentIme != null) {
            return currentIme.equals(packageName);
        }
        // 降级：检查已启用输入法列表是否包含该包（保守判定）
        try {
            Object im = getInputMethodService();
            if (im == null) return false;
            Method m = ReflectionUtils.findMethodRecursive(im.getClass(), "getEnabledInputMethodList");
            if (m == null) {
                m = ReflectionUtils.findMethodRecursive(im.getClass(), "getEnabledInputMethodList", int.class);
            }
            if (m == null) return false;
            Object result = (m.getParameterCount() == 0) ? m.invoke(im) : m.invoke(im, 0);
            if (result instanceof List) {
                for (Object imi : (List<?>) result) {
                    String pn = getInputMethodInfoPackageName(imi);
                    if (packageName.equals(pn)) return true;
                }
            }
        } catch (Throwable t) {
            // R11-M-1: 检测 Binder 代理失效，重置缓存
            if (isDeadObject(t)) {
                inputMethodService = null;
            }
            Logger.d("isInputMethodActive 检查失败: " + t.getMessage());
        }
        return false;
    }

    /** 自动填充服务：packageName 为当前自动填充服务 */
    private static boolean isAutofillServiceActive(String packageName) {
        try {
            Object af = getAutofillService();
            if (af != null) {
                // 尝试多种方法名获取当前 Autofill 服务组件
                String[] methodNames = {
                    "getAutofillServiceComponentName",
                    "getAutofillServiceInfo",
                    "getAutofillService"};
                for (String name : methodNames) {
                    Method m = ReflectionUtils.findMethodRecursive(af.getClass(), name);
                    if (m == null) continue;
                    Object result = m.invoke(af);
                    if (result == null) continue;
                    String pn = getComponentNamePackage(result);
                    if (pn != null) return packageName.equals(pn);
                }
            }
            // 降级：读取 Settings.Secure.autofill_service
            String settingPkg = getSettingSecureString("autofill_service");
            if (settingPkg != null) {
                // 值可能是 ComponentName.flatten 格式 "pkg/cls"
                int idx = settingPkg.indexOf('/');
                String pkg = idx > 0 ? settingPkg.substring(0, idx) : settingPkg;
                return packageName.equals(pkg);
            }
        } catch (Throwable t) {
            // R11-M-1: 检测 Binder 代理失效，重置缓存
            if (isDeadObject(t)) {
                autofillService = null;
            }
            Logger.d("isAutofillServiceActive 检查失败: " + t.getMessage());
        }
        return false;
    }

    /**
     * 可见窗口：该 uid 拥有可见窗口
     *
     * S-4: IWindowManager AIDL 接口（通过 ServiceManager.getService("window") 获取）
     * 不提供 getVisibleWindowsForUid / getWindowList 等方法——这些是 WindowManagerService
     * 内部方法，未通过 AIDL 暴露。旧代码通过 IWindowManager 代理反射查找这些方法恒失败，
     * 实际上始终返回 false。
     *
     * 修复：直接返回 false 并添加注释。可见窗口检测依赖 FOREGROUND 状态检查兜底——
     * ScreenStateHook.batchFreezeAll 中已跳过 AppState.FOREGROUND 进程，
     * 且 hasForegroundService / hasActiveContentProvider 提供前台保护，
     * 故可见窗口检测缺失不会导致误冻结前台应用。
     *
     * 如需精确的可见窗口检测，应 Hook WindowManagerService 内部方法
     * （需在 init() 阶段通过 classLoader Hook WMS 类），而非通过 AIDL 代理反射。
     */
    private static boolean hasVisibleWindow(int uid) {
        // S-4: IWindowManager AIDL 接口不暴露窗口列表查询方法。
        // 作为兜底，使用 FOREGROUND 状态检查代替精确的可见窗口检测。
        // 可见性保护由 FOREGROUND 状态检查和 hasForegroundService 兜底。
        return isForeground(uid);
    }

    /**
     * S-1: 检查指定 uid 是否有前台进程。
     * 作为 hasVisibleWindow 的兜底实现，通过 ProcessTracker 查询 uid 下所有进程，
     * 判断是否有处于 FOREGROUND 状态的进程。
     *
     * @param uid 目标应用 uid
     * @return true 表示该 uid 有前台进程
     */
    private static boolean isForeground(int uid) {
        List<AppInfo> processes = ProcessTracker.getInstance().getByUid(uid);
        if (processes == null || processes.isEmpty()) return false;
        for (AppInfo info : processes) {
            if (info.getState() == AppState.FOREGROUND) return true;
        }
        return false;
    }

    /**
     * 常驻通知：packageName 存在 FLAG_ONGOING_EVENT 通知
     *
     * M7 修复：getActiveNotificationsForPackage(String, int) 需要 uid 来精确过滤，
     * 旧代码传入 -1 导致无法匹配通知。改为接受真实 uid 并传入。
     */
    private static boolean hasOngoingNotification(int uid, String packageName) {
        try {
            Object ns = getNotificationService();
            if (ns == null) return false;
            // getActiveNotificationsForPackage(String pkg, int uid) — hidden
            Method m = ReflectionUtils.findMethodRecursive(
                ns.getClass(), "getActiveNotificationsForPackage", String.class, int.class);
            Object result = null;
            if (m != null) {
                result = m.invoke(ns, packageName, uid);
            } else {
                m = ReflectionUtils.findMethodRecursive(
                    ns.getClass(), "getActiveNotifications", String.class);
                if (m != null) result = m.invoke(ns, packageName);
            }
            if (result == null) return false;
            if (!result.getClass().isArray()) return false;
            Object[] arr = (Object[]) result;
            for (Object n : arr) {
                if (isOngoingNotification(n)) return true;
            }
        } catch (Throwable t) {
            // R11-M-1: 检测 Binder 代理失效，重置缓存
            if (isDeadObject(t)) {
                notificationService = null;
            }
            Logger.d("hasOngoingNotification 检查失败: " + t.getMessage());
        }
        return false;
    }

    // ==================== 系统服务获取（懒加载 + ServiceManager） ====================

    private static Object getTelephonyService() {
        if (telephonyService == null) {
            telephonyService = getService("phone", "com.android.internal.telephony.ITelephony$Stub");
        }
        return telephonyService;
    }

    private static Object getAudioService() {
        if (audioService == null) {
            audioService = getService("audio", "android.media.IAudioService$Stub");
        }
        return audioService;
    }

    private static Object getAppOpsService() {
        if (appOpsService == null) {
            appOpsService = getService("appops", "com.android.internal.app.IAppOpsService$Stub");
        }
        return appOpsService;
    }

    private static Object getConnectivityService() {
        if (connectivityService == null) {
            connectivityService = getService("connectivity", "android.net.IConnectivityManager$Stub");
        }
        return connectivityService;
    }

    private static Object getAccessibilityService() {
        if (accessibilityService == null) {
            accessibilityService = getService("accessibility", "android.accessibilityservice.IAccessibilityManager$Stub");
        }
        return accessibilityService;
    }

    private static Object getInputMethodService() {
        if (inputMethodService == null) {
            inputMethodService = getServiceWithFallbacks("input_method",
                "com.android.internal.inputmethod.IInputMethodManager$Stub",
                "android.view.inputmethod.IInputMethodManager$Stub");
        }
        return inputMethodService;
    }

    private static Object getAutofillService() {
        if (autofillService == null) {
            autofillService = getService("autofill", "android.view.autofill.IAutoFillManager$Stub");
        }
        return autofillService;
    }

    private static Object getNotificationService() {
        if (notificationService == null) {
            notificationService = getService("notification", "android.app.INotificationManager$Stub");
        }
        return notificationService;
    }

    /**
     * 通过 ServiceManager 获取系统服务代理对象。
     * 与 ActivitySwitchHook.isAnyAudioPlaying 的实现保持一致。
     */
    private static Object getService(String name, String stubClassName) {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getServiceMethod = smClass.getMethod("getService", String.class);
            Object binder = getServiceMethod.invoke(null, name);
            if (binder == null) return null;
            Class<?> stubClass = Class.forName(stubClassName);
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            return asInterface.invoke(null, binder);
        } catch (Throwable t) {
            Logger.d("getService 失败: " + name + " via " + stubClassName + " - " + t.getMessage());
            return null;
        }
    }

    /** 同 getService，但尝试多个候选 stub 类名（适配跨版本包路径变化） */
    private static Object getServiceWithFallbacks(String name, String... stubClassNames) {
        for (String stubClassName : stubClassNames) {
            Object obj = getService(name, stubClassName);
            if (obj != null) return obj;
        }
        return null;
    }

    /**
     * R11-M-1: 检测异常链中是否包含 DeadObjectException（Binder 代理失效）。
     * 通过 instanceof 和类名匹配双重检测，兼容不同 Android 版本的异常包装。
     * 返回 true 时调用方应重置对应服务缓存字段为 null，下次重新获取。
     */
    private static boolean isDeadObject(Throwable t) {
        while (t != null) {
            if (t instanceof android.os.DeadObjectException) return true;
            if (t.getClass().getName().contains("DeadObjectException")) return true;
            t = t.getCause();
        }
        return false;
    }

    // ==================== 反射辅助方法 ====================

    /** 调用 IAppOpsService.isOperationActive(op, uid, packageName) */
    private static Boolean callIsOperationActive(Object appOps, int op, int uid, String packageName) {
        try {
            Method m = ReflectionUtils.findMethodRecursive(
                appOps.getClass(), "isOperationActive", int.class, int.class, String.class);
            if (m == null) return null;
            Object r = m.invoke(appOps, op, uid, packageName);
            return (r instanceof Boolean) ? (Boolean) r : null;
        } catch (InvocationTargetException e) {
            // R9-m9-7: 解包反射调用目标异常，记录真实 cause
            Throwable cause = e.getCause();
            Logger.d("isOperationActive 调用失败: op=" + op + " - "
                + (cause != null ? cause : e).getMessage());
            return null;
        } catch (Throwable t) {
            Logger.d("isOperationActive 调用失败: op=" + op + " - " + t.getMessage());
            return null;
        }
    }

    /**
     * 通过反射读取 AppOpsManager 常量值，失败时返回默认值。
     *
     * R10-m-7: 使用 ConcurrentHashMap 缓存已解析的常量值，避免每次调用都反射获取字段。
     * AppOpsManager 常量在同一进程内稳定不变，缓存命中后零反射开销。
     * m-4: 引入负面缓存——反射失败时将 (constantName, -1) 作为"已查找但未找到"的标记缓存，
     * 避免每次调用都重复执行注定失败的反射查找。当缓存值为 -1 时返回调用方传入的 defaultValue。
     */
    private static int getOpCode(String constantName, int defaultValue) {
        Integer cached = opCodeCache.get(constantName);
        if (cached != null) {
            // m-4: -1 表示"已查找但未找到"，返回调用方的 defaultValue
            return cached == -1 ? defaultValue : cached;
        }
        try {
            Class<?> appOpsClass = Class.forName("android.app.AppOpsManager");
            Field f = appOpsClass.getDeclaredField(constantName);
            int value = f.getInt(null);
            opCodeCache.put(constantName, value);
            return value;
        } catch (Throwable t) {
            // m-4: 负面缓存，避免重复反射查找
            opCodeCache.put(constantName, -1);
            return defaultValue;
        }
    }

    /** 安全获取对象字段值 */
    private static Object getFieldValue(Object obj, String fieldName) {
        if (obj == null) return null;
        Field field = ReflectionUtils.findFieldRecursive(obj.getClass(), fieldName);
        return field != null ? ReflectionUtils.getFieldValue(obj, field) : null;
    }

    /** 从 AudioFocusInfo 提取持有者 uid */
    private static Integer getAudioFocusOwnerUid(Object audioFocusInfo) {
        try {
            Method m = ReflectionUtils.findMethodRecursive(audioFocusInfo.getClass(), "getClientUid");
            if (m != null) {
                Object r = m.invoke(audioFocusInfo);
                if (r instanceof Integer) return (Integer) r;
            }
            Object uid = getFieldValue(audioFocusInfo, "mClientUid");
            if (uid == null) uid = getFieldValue(audioFocusInfo, "clientUid");
            if (uid instanceof Integer) return (Integer) uid;
        } catch (InvocationTargetException e) {
            // R9-m9-7: 解包反射调用目标异常，记录真实 cause
            Throwable cause = e.getCause();
            Logger.d("getAudioFocusOwnerUid 失败: " + (cause != null ? cause : e).getMessage());
        } catch (Throwable ignored) {
            Logger.d("getAudioFocusOwnerUid 失败: " + ignored.getMessage());
        }
        return null;
    }

    /** 检查 connectivity service 中是否存在 VPN 传输网络 */
    private static boolean hasAnyVpnNetwork(Object cs) {
        try {
            // 优先 getAllNetworkState()，遍历 NetworkState 的 capabilities 检查 VPN 传输
            Method m = ReflectionUtils.findMethodRecursive(cs.getClass(), "getAllNetworkState");
            if (m == null) {
                m = ReflectionUtils.findMethodRecursive(cs.getClass(), "getAllNetworks");
                if (m == null) return false;
                Object[] networks = (Object[]) m.invoke(cs);
                if (networks == null) return false;
                for (Object net : networks) {
                    if (networkHasVpnTransport(cs, net)) return true;
                }
                return false;
            }
            Object[] states = (Object[]) m.invoke(cs);
            if (states == null) return false;
            for (Object state : states) {
                Object caps = getFieldValue(state, "networkCapabilities");
                if (caps == null) caps = getFieldValue(state, "capabilities");
                if (caps != null && capabilitiesHasTransport(caps, TRANSPORT_VPN)) return true;
            }
        } catch (Throwable t) {
            Logger.d("hasAnyVpnNetwork 检查失败: " + t.getMessage());
        }
        return false;
    }

    /** 通过 getNetworkCapabilities(Network) 查询指定 Network 是否为 VPN 传输 */
    private static boolean networkHasVpnTransport(Object cs, Object network) {
        try {
            Method m = ReflectionUtils.findMethodRecursive(
                cs.getClass(), "getNetworkCapabilities", network.getClass());
            if (m == null) return false;
            Object caps = m.invoke(cs, network);
            return caps != null && capabilitiesHasTransport(caps, TRANSPORT_VPN);
        } catch (Throwable ignored) {
            Logger.d("hasVpnNetwork 检查失败: " + ignored.getMessage());
        }
        return false;
    }

    /** NetworkCapabilities.hasTransport(TRANSPORT_VPN) */
    private static boolean capabilitiesHasTransport(Object capabilities, int transport) {
        try {
            Method m = ReflectionUtils.findMethodRecursive(
                capabilities.getClass(), "hasTransport", int.class);
            if (m != null) {
                Object r = m.invoke(capabilities, transport);
                return Boolean.TRUE.equals(r);
            }
        } catch (Throwable ignored) {
            Logger.d("capabilitiesHasTransport 失败: " + ignored.getMessage());
        }
        return false;
    }

    /**
     * 获取 VPN 拥有者包名（通过 VpnInfo 反射，best-effort）
     *
     * R9-m9-5: getVpnConfig 在 AOSP ConnectivityService 中签名为 getVpnConfig(int userId)，
     * 需要一个 int 参数，当前无参查找（findMethodRecursive 无参数类型）不会命中该方法，
     * 故 getVpnConfig 路径实际上为死代码。getVpnInfo() 无参版本存在，是该路径的有效入口。
     * 当前 best-effort 行为可接受：VPN 应用通常持有前台服务，已被 hasForegroundService 保护，
     * 此处仅作为补充检测。
     * R11-m-3: 移除 getVpnConfig 死代码路径，仅保留 getVpnInfo() 无参版本。
     * M-6: 移除 user 字段的 String 回退——VpnInfo.user 为 int 类型（userId），
     * getFieldValue(info, "user") 返回 Integer 而非 String，`instanceof String` 恒为 false，
     * 属于死代码。仅保留 packageName 字段提取。
     */
    private static String getVpnOwnerPackage(Object cs) {
        try {
            // 尝试 getVpnInfo()
            String[] methodNames = {"getVpnInfo"};
            for (String name : methodNames) {
                Method m = ReflectionUtils.findMethodRecursive(cs.getClass(), name);
                if (m == null) continue;
                Object result = m.invoke(cs);
                if (result == null) continue;
                // R8-M8-6: getVpnInfo() 返回 VpnInfo[] 数组，需遍历每个元素；
                // 旧代码将数组当单个对象处理，对数组调用 getFieldValue 取不到 packageName，
                // 导致 VPN 拥有者判定失效。
                if (result.getClass().isArray()) {
                    Object[] arr = (Object[]) result;
                    for (Object info : arr) {
                        if (info == null) continue;
                        Object pkg = getFieldValue(info, "packageName");
                        if (pkg instanceof String) return (String) pkg;
                    }
                } else {
                    Object pkg = getFieldValue(result, "packageName");
                    if (pkg instanceof String) return (String) pkg;
                }
            }
        } catch (Throwable ignored) {
            Logger.d("getVpnOwnerPackage 失败: " + ignored.getMessage());
        }
        return null;
    }

    /** 通过 PackageManager 将包名解析为 uid */
    private static Integer getPackageUid(String packageName) {
        try {
            Class<?> appGlobals = Class.forName("android.app.AppGlobals");
            Method getInitialApp = appGlobals.getMethod("getInitialApplication");
            Object context = getInitialApp.invoke(null);
            // S-4: AppGlobals.getInitialApplication() 可能返回 null（Xposed 环境早期阶段），
            // 添加回退路径通过 IPackageManager$Stub.asInterface 获取 PackageManager 接口
            Object pm = null;
            if (context != null) {
                try {
                    Method getPackageManager = context.getClass().getMethod("getPackageManager");
                    pm = getPackageManager.invoke(context);
                } catch (Throwable t) {
                    Logger.d("getPackageManager 失败: " + t.getMessage());
                }
            }
            if (pm == null) {
                // S-4: 回退路径：通过 IPackageManager$Stub.asInterface 直接获取 PMS 接口
                try {
                    Class<?> spmClass = Class.forName("android.content.pm.IPackageManager$Stub");
                    Method asInterface = spmClass.getMethod("asInterface", android.os.IBinder.class);
                    Class<?> smClass = Class.forName("android.os.ServiceManager");
                    Method getService = smClass.getMethod("getService", String.class);
                    Object binder = getService.invoke(null, "package");
                    if (binder != null) {
                        pm = asInterface.invoke(null, binder);
                    }
                } catch (Throwable t) {
                    Logger.d("IPackageManager 回退路径失败: " + t.getMessage());
                }
            }
            if (pm == null) return null;
            // L7 修复：getPackageUid 跨版本签名
            //  - 旧签名 (String, int)
            //  - Android 14+ (String, long) — 配合 PackageManager.PackageInfoFlags
            // 旧代码注释提到"失败再尝试 (String, long) 变体"但实际未实现，此处补全。
            Method m = ReflectionUtils.findMethodRecursive(
                pm.getClass(), "getPackageUid", String.class, int.class);
            if (m != null) {
                Object r = m.invoke(pm, packageName, 0);
                if (r instanceof Integer) return (Integer) r;
            }
            // Android 14+: getPackageUid(String, long) — PackageInfoFlags.of(0)
            Method mLong = ReflectionUtils.findMethodRecursive(
                pm.getClass(), "getPackageUid", String.class, long.class);
            if (mLong != null) {
                Object r = mLong.invoke(pm, packageName, 0L);
                if (r instanceof Integer) return (Integer) r;
            }
        } catch (Throwable t) {
            Logger.d("getPackageUid 失败: " + packageName + " - " + t.getMessage());
        }
        return null;
    }

    /** 从 AccessibilityServiceInfo / ResolveInfo 提取包名 */
    private static String getServiceInfoPackageName(Object serviceInfoOrResolveInfo) {
        try {
            Method getResolveInfo = ReflectionUtils.findMethodRecursive(
                serviceInfoOrResolveInfo.getClass(), "getResolveInfo");
            Object resolveInfo = getResolveInfo != null
                ? getResolveInfo.invoke(serviceInfoOrResolveInfo)
                : serviceInfoOrResolveInfo;
            if (resolveInfo == null) return null;
            Object serviceInfo = getFieldValue(resolveInfo, "serviceInfo");
            if (serviceInfo == null) serviceInfo = getFieldValue(resolveInfo, "activityInfo");
            if (serviceInfo != null) {
                Object pn = getFieldValue(serviceInfo, "packageName");
                return pn != null ? pn.toString() : null;
            }
        } catch (Throwable ignored) {
            Logger.d("getServiceInfoPackageName 提取失败: " + ignored.getMessage());
        }
        return null;
    }

    /** 从 InputMethodInfo 提取包名 */
    private static String getInputMethodInfoPackageName(Object inputMethodInfo) {
        try {
            Method getPackageName = ReflectionUtils.findMethodRecursive(
                inputMethodInfo.getClass(), "getPackageName");
            if (getPackageName != null) {
                Object r = getPackageName.invoke(inputMethodInfo);
                return r != null ? r.toString() : null;
            }
            Object serviceInfo = getFieldValue(inputMethodInfo, "mService");
            if (serviceInfo == null) serviceInfo = getFieldValue(inputMethodInfo, "serviceInfo");
            if (serviceInfo != null) {
                Object pn = getFieldValue(serviceInfo, "packageName");
                return pn != null ? pn.toString() : null;
            }
        } catch (Throwable ignored) {
            Logger.d("getInputMethodPackageName 提取失败: " + ignored.getMessage());
        }
        return null;
    }

    /** 从 ComponentName 对象提取包名 */
    private static String getComponentNamePackage(Object componentName) {
        try {
            Method m = ReflectionUtils.findMethodRecursive(componentName.getClass(), "getPackageName");
            if (m != null) {
                Object r = m.invoke(componentName);
                return r != null ? r.toString() : null;
            }
        } catch (Throwable ignored) {
            Logger.d("getComponentNamePackage 失败: " + ignored.getMessage());
        }
        // 若为 String 形式 "pkg/cls"
        String s = componentName.toString();
        int idx = s.indexOf('/');
        return idx > 0 ? s.substring(0, idx) : s;
    }

    /** 读取 Settings.Secure 字符串（通过 system_server 上下文） */
    private static String getSettingSecureString(String key) {
        try {
            Class<?> appGlobals = Class.forName("android.app.AppGlobals");
            Object context = appGlobals.getMethod("getInitialApplication").invoke(null);
            if (context == null) return null;
            Object cr = context.getClass().getMethod("getContentResolver").invoke(context);
            if (cr == null) return null;
            Class<?> settingsSecure = Class.forName("android.provider.Settings$Secure");
            Method getString = settingsSecure.getMethod("getString",
                Class.forName("android.content.ContentResolver"), String.class);
            Object value = getString.invoke(null, cr, key);
            return value != null ? value.toString() : null;
        } catch (Throwable t) {
            Logger.d("getSettingSecureString 失败: " + key + " - " + t.getMessage());
            return null;
        }
    }

    /** 读取当前默认输入法包名（Settings.Secure.default_input_method） */
    private static String getCurrentImePackage() {
        String value = getSettingSecureString("default_input_method");
        if (value == null || value.isEmpty()) return null;
        int idx = value.indexOf('/');
        return idx > 0 ? value.substring(0, idx) : value;
    }

    /** 判断通知是否为 ongoing 通知 */
    private static boolean isOngoingNotification(Object sbnOrRecord) {
        try {
            // StatusBarNotification.getNotification()
            Method getNotification = ReflectionUtils.findMethodRecursive(
                sbnOrRecord.getClass(), "getNotification");
            Object notification = getNotification != null ? getNotification.invoke(sbnOrRecord) : null;
            if (notification == null) {
                // NotificationRecord.sbn.getNotification()
                Object sbn = getFieldValue(sbnOrRecord, "sbn");
                if (sbn != null) {
                    getNotification = ReflectionUtils.findMethodRecursive(sbn.getClass(), "getNotification");
                    notification = getNotification != null ? getNotification.invoke(sbn) : null;
                }
            }
            if (notification == null) return false;
            Field flagsField = ReflectionUtils.findFieldRecursive(notification.getClass(), "flags");
            if (flagsField != null) {
                int flags = flagsField.getInt(notification);
                return (flags & NOTIFICATION_FLAG_ONGOING_EVENT) != 0;
            }
        } catch (Throwable ignored) {
            Logger.d("isOngoingNotification 检查失败: " + ignored.getMessage());
        }
        return false;
    }
}
