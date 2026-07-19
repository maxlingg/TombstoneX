package com.tombstonex.hook;

import android.os.IBinder;
import android.os.SystemClock;
import com.tombstonex.util.Logger;
import com.tombstonex.util.ReflectionUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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

    public static void init(ClassLoader classLoader) {
        Logger.i("SmartStateHook 已初始化（可查询 API: isAppActive，缓存 TTL=" + CACHE_TTL_MS + "ms）");
        // 预加载系统服务引用，单次失败不影响后续懒加载兜底
        preloadServices();
    }

    private static void preloadServices() {
        try { telephonyService = getService("phone", "com.android.internal.telephony.ITelephony$Stub"); } catch (Throwable ignored) {}
        try { audioService = getService("audio", "android.media.IAudioService$Stub"); } catch (Throwable ignored) {}
        try { appOpsService = getService("appops", "com.android.internal.app.IAppOpsService$Stub"); } catch (Throwable ignored) {}
        try { connectivityService = getService("connectivity", "android.net.IConnectivityManager$Stub"); } catch (Throwable ignored) {}
        try { accessibilityService = getService("accessibility", "android.accessibilityservice.IAccessibilityManager$Stub"); } catch (Throwable ignored) {}
        try { notificationService = getService("notification", "android.app.INotificationManager$Stub"); } catch (Throwable ignored) {}
        try { windowService = getService("window", "android.view.IWindowManager$Stub"); } catch (Throwable ignored) {}
        try { autofillService = getService("autofill", "android.view.autofill.IAutoFillManager$Stub"); } catch (Throwable ignored) {}
        // InputMethod stub 类名跨版本不同，单独处理
        try {
            inputMethodService = getServiceWithFallbacks("input_method",
                "com.android.internal.inputmethod.IInputMethodManager$Stub",
                "android.view.inputmethod.IInputMethodManager$Stub");
        } catch (Throwable ignored) {}
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
     * 综合判定应用是否活跃。每项检测独立，任一命中即返回 true。
     * 顺序按"系统服务查询成本/命中率"粗略排列，命中后短路返回。
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
        if (hasOngoingNotification(packageName)) return true;
        // 11. 媒体播放 + 音频会话
        if (isMusicActive() && hasAudioFocus(uid)) return true;
        // 12. 后台播放（持有音频焦点）
        if (hasAudioFocus(uid)) return true;
        return false;
    }

    // ==================== 各项独立检测方法 ====================
    // 每个方法独立 try/catch，异常时返回 false（按"未活跃"处理，不阻塞冻结）

    /** 通话中：TelephonyManager.getCallState() != CALL_STATE_IDLE（全局状态） */
    private static boolean isInCall() {
        try {
            Object ts = getTelephonyService();
            if (ts == null) return false;
            // 尝试无参 getCallState()
            Method m = ReflectionUtils.findMethodRecursive(ts.getClass(), "getCallState");
            if (m == null) {
                m = ReflectionUtils.findMethodRecursive(ts.getClass(), "getCallState", String.class);
                if (m != null) {
                    Object r = m.invoke(ts, "com.tombstonex");
                    if (r instanceof Integer) return ((Integer) r) != CALL_STATE_IDLE;
                }
                m = ReflectionUtils.findMethodRecursive(ts.getClass(), "getCallState", int.class);
                if (m != null) {
                    Object r = m.invoke(ts, -1);
                    if (r instanceof Integer) return ((Integer) r) != CALL_STATE_IDLE;
                }
                return false;
            }
            Object r = m.invoke(ts);
            if (r instanceof Integer) return ((Integer) r) != CALL_STATE_IDLE;
        } catch (Throwable t) {
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
            Logger.d("isCameraInUse 检查失败: " + t.getMessage());
        }
        return false;
    }

    /** 音频模式为 MODE_IN_COMMUNICATION（VoIP/对讲等，全局状态） */
    private static boolean isAudioInCommunication() {
        try {
            Object as = getAudioService();
            if (as == null) return false;
            Method m = ReflectionUtils.findMethodRecursive(as.getClass(), "getMode");
            if (m != null) {
                Object r = m.invoke(as);
                if (r instanceof Integer) return ((Integer) r) == MODE_IN_COMMUNICATION;
            }
        } catch (Throwable t) {
            Logger.d("isAudioInCommunication 检查失败: " + t.getMessage());
        }
        return false;
    }

    /** 媒体播放中：AudioManager.isMusicActive()（全局状态） */
    private static boolean isMusicActive() {
        try {
            Object as = getAudioService();
            if (as == null) return false;
            Method m = ReflectionUtils.findMethodRecursive(as.getClass(), "isMusicActive");
            if (m != null) {
                Object r = m.invoke(as);
                return Boolean.TRUE.equals(r);
            }
        } catch (Throwable t) {
            Logger.d("isMusicActive 检查失败: " + t.getMessage());
        }
        return false;
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
        } catch (Throwable t) {
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
                    if (result == null) return false;
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
            Logger.d("isAutofillServiceActive 检查失败: " + t.getMessage());
        }
        return false;
    }

    /** 可见窗口：该 uid 拥有可见窗口 */
    private static boolean hasVisibleWindow(int uid) {
        try {
            Object ws = getWindowService();
            if (ws == null) return false;
            // 优先尝试 getVisibleWindowsForUid(int)（部分版本提供）
            Method m = ReflectionUtils.findMethodRecursive(
                ws.getClass(), "getVisibleWindowsForUid", int.class);
            if (m != null) {
                Object r = m.invoke(ws, uid);
                if (r instanceof Boolean) return (boolean) r;
                if (r instanceof List) return !((List<?>) r).isEmpty();
            }
            // 降级：遍历 getWindowList() 匹配 uid 且窗口可见
            m = ReflectionUtils.findMethodRecursive(ws.getClass(), "getWindowList");
            if (m == null) return false;
            Object result = m.invoke(ws);
            if (!(result instanceof List)) return false;
            for (Object windowState : (List<?>) result) {
                Integer windowUid = getWindowUid(windowState);
                if (windowUid != null && windowUid == uid) {
                    if (isWindowVisible(windowState)) return true;
                }
            }
        } catch (Throwable t) {
            Logger.d("hasVisibleWindow 检查失败: " + t.getMessage());
        }
        return false;
    }

    /** 常驻通知：packageName 存在 FLAG_ONGOING_EVENT 通知 */
    private static boolean hasOngoingNotification(String packageName) {
        try {
            Object ns = getNotificationService();
            if (ns == null) return false;
            // getActiveNotificationsForPackage(String pkg, int uid) — hidden
            Method m = ReflectionUtils.findMethodRecursive(
                ns.getClass(), "getActiveNotificationsForPackage", String.class, int.class);
            Object result = null;
            if (m != null) {
                result = m.invoke(ns, packageName, -1);
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

    private static Object getWindowService() {
        if (windowService == null) {
            windowService = getService("window", "android.view.IWindowManager$Stub");
        }
        return windowService;
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

    // ==================== 反射辅助方法 ====================

    /** 调用 IAppOpsService.isOperationActive(op, uid, packageName) */
    private static Boolean callIsOperationActive(Object appOps, int op, int uid, String packageName) {
        try {
            Method m = ReflectionUtils.findMethodRecursive(
                appOps.getClass(), "isOperationActive", int.class, int.class, String.class);
            if (m == null) return null;
            Object r = m.invoke(appOps, op, uid, packageName);
            return (r instanceof Boolean) ? (Boolean) r : null;
        } catch (Throwable t) {
            Logger.d("isOperationActive 调用失败: op=" + op + " - " + t.getMessage());
            return null;
        }
    }

    /** 通过反射读取 AppOpsManager 常量值，失败时返回默认值 */
    private static int getOpCode(String constantName, int defaultValue) {
        try {
            Class<?> appOpsClass = Class.forName("android.app.AppOpsManager");
            Field f = appOpsClass.getDeclaredField(constantName);
            return f.getInt(null);
        } catch (Throwable t) {
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
        } catch (Throwable ignored) {}
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
        } catch (Throwable ignored) {}
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
        } catch (Throwable ignored) {}
        return false;
    }

    /** 获取 VPN 拥有者包名（通过 VpnConfig 反射，best-effort） */
    private static String getVpnOwnerPackage(Object cs) {
        try {
            // 尝试 getVpnConfig() / getVpnInfo()
            String[] methodNames = {"getVpnConfig", "getVpnInfo"};
            for (String name : methodNames) {
                Method m = ReflectionUtils.findMethodRecursive(cs.getClass(), name);
                if (m == null) continue;
                Object config = m.invoke(cs);
                if (config == null) continue;
                Object pkg = getFieldValue(config, "packageName");
                if (pkg == null) pkg = getFieldValue(config, "user");
                if (pkg instanceof String) return (String) pkg;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 通过 PackageManager 将包名解析为 uid */
    private static Integer getPackageUid(String packageName) {
        try {
            Class<?> appGlobals = Class.forName("android.app.AppGlobals");
            Method getInitialApp = appGlobals.getMethod("getInitialApplication");
            Object context = getInitialApp.invoke(null);
            if (context == null) return null;
            Method getPackageManager = context.getClass().getMethod("getPackageManager");
            Object pm = getPackageManager.invoke(context);
            if (pm == null) return null;
            // getPackageUid(String, int) — Android 12+ 推荐使用 PackageManager.PackageInfoFlags，
            // 这里先尝试旧签名 (String, int)，失败再尝试 (String, long) 变体
            Method m = ReflectionUtils.findMethodRecursive(pm.getClass(), "getPackageUid", String.class, int.class);
            if (m != null) {
                Object r = m.invoke(pm, packageName, 0);
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
        } catch (Throwable ignored) {}
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
        } catch (Throwable ignored) {}
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
        } catch (Throwable ignored) {}
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

    /** 从 WindowState 提取拥有者 uid */
    private static Integer getWindowUid(Object windowState) {
        try {
            Method m = ReflectionUtils.findMethodRecursive(windowState.getClass(), "getOwningUid");
            if (m != null) {
                Object r = m.invoke(windowState);
                if (r instanceof Integer) return (Integer) r;
            }
            Object uid = getFieldValue(windowState, "mOwnerUid");
            if (uid == null) uid = getFieldValue(windowState, "ownerUid");
            if (uid instanceof Integer) return (Integer) uid;
        } catch (Throwable ignored) {}
        return null;
    }

    /** 判断 WindowState 是否可见（best-effort，无法判定时返回 false） */
    private static boolean isWindowVisible(Object windowState) {
        try {
            Method m = ReflectionUtils.findMethodRecursive(windowState.getClass(), "isVisible");
            if (m != null && m.getParameterCount() == 0) {
                Object r = m.invoke(windowState);
                return Boolean.TRUE.equals(r);
            }
            // WindowState.isVisible(boolean) 变体
            m = ReflectionUtils.findMethodRecursive(windowState.getClass(), "isVisible", boolean.class);
            if (m != null) {
                Object r = m.invoke(windowState, false);
                return Boolean.TRUE.equals(r);
            }
            // mHasSurface 字段：有 surface 通常意味着可见
            Object hasSurface = getFieldValue(windowState, "mHasSurface");
            if (hasSurface instanceof Boolean) return (boolean) hasSurface;
        } catch (Throwable ignored) {}
        // 无法判定可见性时返回 false（按"未活跃"处理，不阻塞冻结）
        return false;
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
        } catch (Throwable ignored) {}
        return false;
    }
}
