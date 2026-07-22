package com.tombstonex.service;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import com.tombstonex.manager.ConfigManager;
import com.tombstonex.manager.FreezeManager;
import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.manager.WhitelistManager;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.model.FreezeMode;
import com.tombstonex.util.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Binder 服务，运行在 system_server 中，通过 ServiceManager 注册。
 * UI 进程通过 ServiceClient 反射调用 ServiceManager.getService("tombstonex") 获取代理。
 *
 * 所有 UI ↔ Hook 的通信都通过此服务中转，解决 App 进程无权限访问 /data/system/ 的问题。
 */
public class TombstoneXService extends Binder {

    public static final String SERVICE_NAME = "tombstonex";

    /** Binder 接口描述符，用于 enforceInterface 校验 */
    private static final String DESCRIPTOR = "com.tombstonex.service.TombstoneXService";

    // 事务码
    public static final int TX_GET_CONFIG = 1;
    public static final int TX_SET_FREEZE_MODE = 2;
    public static final int TX_SET_FREEZE_DELAY = 3;
    public static final int TX_SET_DEBUG_ENABLED = 4;
    public static final int TX_SET_HOOK_ENABLED = 5;
    public static final int TX_SET_GLOBAL_PAUSED = 6;
    public static final int TX_IS_GLOBAL_PAUSED = 7;
    public static final int TX_GET_WHITE_APPS = 8;
    public static final int TX_ADD_WHITE_APP = 9;
    public static final int TX_REMOVE_WHITE_APP = 10;
    public static final int TX_GET_WHITE_PROCESSES = 11;
    public static final int TX_ADD_WHITE_PROCESS = 12;
    public static final int TX_REMOVE_WHITE_PROCESS = 13;
    public static final int TX_GET_BLACK_SYSTEM_APPS = 14;
    public static final int TX_ADD_BLACK_SYSTEM_APP = 15;
    public static final int TX_REMOVE_BLACK_SYSTEM_APP = 16;
    public static final int TX_FREEZE_PROCESS = 17;
    public static final int TX_UNFREEZE_PROCESS = 18;
    public static final int TX_GET_ALL_PROCESSES = 19;
    public static final int TX_GET_FROZEN_COUNT = 20;
    public static final int TX_GET_CURRENT_FREEZER_NAME = 21;
    public static final int TX_READ_LOG = 22;
    public static final int TX_CLEAR_LOG = 23;
    public static final int TX_PAUSE_ALL = 24;
    public static final int TX_RESUME_ALL = 25;
    public static final int TX_RESELECT_FREEZER = 26;
    // 应用级配置
    public static final int TX_GET_APP_CONFIG = 27;
    // S4 修复：TX_SET_APP_CONFIG (28) 服务端无 onTransact 处理且客户端无调用方，
    // 属于无用常量，已从服务端与 ServiceClient.kt 中删除。
    public static final int TX_SET_APP_CONFIG_ITEM = 29;
    // 轮番解冻间隔
    public static final int TX_GET_ROTATION_INTERVAL = 30;
    public static final int TX_SET_ROTATION_INTERVAL = 31;
    // OOM 优先级
    public static final int TX_GET_APP_PRIORITY = 32;
    public static final int TX_SET_APP_PRIORITY = 33;
    // 批量事务（减少 IPC 往返次数）
    public static final int TX_GET_INIT_DATA = 34;
    public static final int TX_GET_APP_CONFIG_FULL = 35;

    // M4-修复: 缓存反射 Method 对象，避免每次 onTransact 调用都执行 Class.forName + getMethod
    private static volatile java.lang.reflect.Method cachedGetPackageManagerMethod;
    private static volatile java.lang.reflect.Method cachedGetPackagesForUidMethod;
    private static volatile Object cachedPackageManager;

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        // 安全：仅允许 system uid 或模块自身 UI 进程调用
        int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID && callingUid != 0) {
            // 允许模块自身 UI 进程调用（反射调用隐藏 API AppGlobals，Method 对象已缓存）
            String[] packages = null;
            try {
                java.lang.reflect.Method getPackageManager = cachedGetPackageManagerMethod;
                if (getPackageManager == null) {
                    Class<?> appGlobals = Class.forName("android.app.AppGlobals");
                    getPackageManager = appGlobals.getMethod("getPackageManager");
                    cachedGetPackageManagerMethod = getPackageManager;
                }
                Object pm = cachedPackageManager;
                if (pm == null) {
                    pm = getPackageManager.invoke(null);
                    cachedPackageManager = pm;
                }
                java.lang.reflect.Method getPackagesForUid = cachedGetPackagesForUidMethod;
                if (getPackagesForUid == null) {
                    getPackagesForUid = pm.getClass().getMethod("getPackagesForUid", int.class);
                    cachedGetPackagesForUidMethod = getPackagesForUid;
                }
                packages = (String[]) getPackagesForUid.invoke(pm, callingUid);
            } catch (Throwable t) {
                Logger.e("获取 uid 的包名失败", t);
            }
            boolean isModuleCaller = false;
            if (packages != null) {
                for (String pkg : packages) {
                    if ("com.tombstonex".equals(pkg)) {
                        isModuleCaller = true;
                        break;
                    }
                }
            }
            if (!isModuleCaller) {
                reply.writeException(new SecurityException("Permission denied"));
                return true;
            }
        }
        // P2-02: 跟踪 reply 是否已被写入（writeNoException 已调用）。
        // 异常发生时仅在 reply 未被写入时才 writeException，避免在 writeNoException
        // 之后再次写入异常标记导致 reply 数据错位。声明在 try 之外以便 catch 块可访问。
        boolean replied = false;
        try {
            // 验证接口描述符
            data.enforceInterface(DESCRIPTOR);
            switch (code) {
                case TX_GET_CONFIG: {
                    JSONObject config = new JSONObject();
                    ConfigManager cm = ConfigManager.getInstance();
                    config.put("freezeMode", cm.getFreezeMode().getId());
                    config.put("freezeDelay", cm.getFreezeDelay());
                    config.put("debugEnabled", cm.isDebugEnabled());
                    config.put("globalPaused", cm.isGlobalPaused());
                    config.put("hookANR", cm.isHookANREnabled());
                    config.put("hookBroadcast", cm.isHookBroadcastEnabled());
                    config.put("hookWakeLock", cm.isHookWakeLockEnabled());
                    config.put("hookActivitySwitch", cm.isHookActivitySwitchEnabled());
                    config.put("hookScreenState", cm.isHookScreenStateEnabled());
                    reply.writeNoException();
                    replied = true;
                    reply.writeString(config.toString());
                    return true;
                }
                case TX_SET_FREEZE_MODE: {
                    int mode = data.readInt();
                    FreezeMode freezeMode = FreezeMode.fromId(mode);
                    // fromId 对未知 id 回退到 SYSTEM_API；通过比对 id 判断是否真正匹配
                    if (mode != freezeMode.getId()) {
                        reply.writeNoException();
                        replied = true;
                        reply.writeBoolean(false);
                        return true;
                    }
                    ConfigManager.getInstance().setFreezeMode(freezeMode);
                    reply.writeNoException();
                    replied = true;
                    reply.writeBoolean(true);
                    return true;
                }
                case TX_SET_FREEZE_DELAY: {
                    int delay = data.readInt();
                    ConfigManager.getInstance().setFreezeDelay(delay);
                    reply.writeNoException();
                    replied = true;
                    return true;
                }
                case TX_SET_DEBUG_ENABLED: {
                    boolean enabled = data.readBoolean();
                    ConfigManager.getInstance().setDebugEnabled(enabled);
                    reply.writeNoException();
                    replied = true;
                    return true;
                }
                case TX_SET_HOOK_ENABLED: {
                    int hookId = data.readInt();
                    boolean enabled = data.readBoolean();
                    ConfigManager cm = ConfigManager.getInstance();
                    switch (hookId) {
                        case 0: cm.setHookANREnabled(enabled); break;
                        case 1: cm.setHookBroadcastEnabled(enabled); break;
                        case 2: cm.setHookWakeLockEnabled(enabled); break;
                        case 3: cm.setHookActivitySwitchEnabled(enabled); break;
                        case 4: cm.setHookScreenStateEnabled(enabled); break;
                        default:
                            reply.writeNoException();
                            replied = true;
                            reply.writeBoolean(false);
                            return true;
                    }
                    reply.writeNoException();
                    replied = true;
                    reply.writeBoolean(true);
                    return true;
                }
                case TX_SET_GLOBAL_PAUSED: {
                    boolean paused = data.readBoolean();
                    // P7-R7: 跟踪底层持久化结果，避免无条件返回 true
                    boolean success = ConfigManager.getInstance().setGlobalPaused(paused);
                    reply.writeNoException();
                    replied = true;
                    reply.writeBoolean(success);
                    return true;
                }
                case TX_IS_GLOBAL_PAUSED: {
                    reply.writeNoException();
                    replied = true;
                    reply.writeBoolean(ConfigManager.getInstance().isGlobalPaused());
                    return true;
                }
                case TX_GET_WHITE_APPS: {
                    JSONArray arr = new JSONArray();
                    for (String pkg : WhitelistManager.getInstance().getWhiteApps()) {
                        arr.put(pkg);
                    }
                    reply.writeNoException();
                    replied = true;
                    reply.writeString(arr.toString());
                    return true;
                }
                case TX_ADD_WHITE_APP: {
                    String pkg = data.readString();
                    if (pkg == null || pkg.isEmpty()) {
                        reply.writeNoException();
                        replied = true;
                        reply.writeBoolean(false);
                        return true;
                    }
                    WhitelistManager.getInstance().addWhiteApp(pkg);
                    reply.writeNoException();
                    replied = true;
                    reply.writeBoolean(true);
                    return true;
                }
                case TX_REMOVE_WHITE_APP: {
                    String pkg = data.readString();
                    if (pkg == null || pkg.isEmpty()) {
                        reply.writeNoException();
                        replied = true;
                        reply.writeBoolean(false);
                        return true;
                    }
                    WhitelistManager.getInstance().removeWhiteApp(pkg);
                    reply.writeNoException();
                    replied = true;
                    reply.writeBoolean(true);
                    return true;
                }
                case TX_GET_WHITE_PROCESSES: {
                    JSONArray arr = new JSONArray();
                    for (String proc : WhitelistManager.getInstance().getWhiteProcesses()) {
                        arr.put(proc);
                    }
                    reply.writeNoException();
                    replied = true;
                    reply.writeString(arr.toString());
                    return true;
                }
                case TX_ADD_WHITE_PROCESS: {
                    String proc = data.readString();
                    if (proc == null || proc.isEmpty()) {
                        reply.writeNoException();
                        replied = true;
                        reply.writeBoolean(false);
                        return true;
                    }
                    WhitelistManager.getInstance().addWhiteProcess(proc);
                    reply.writeNoException();
                    replied = true;
                    reply.writeBoolean(true);
                    return true;
                }
                case TX_REMOVE_WHITE_PROCESS: {
                    String proc = data.readString();
                    if (proc == null || proc.isEmpty()) {
                        reply.writeNoException();
                        replied = true;
                        reply.writeBoolean(false);
                        return true;
                    }
                    WhitelistManager.getInstance().removeWhiteProcess(proc);
                    reply.writeNoException();
                    replied = true;
                    reply.writeBoolean(true);
                    return true;
                }
                case TX_GET_BLACK_SYSTEM_APPS: {
                    JSONArray arr = new JSONArray();
                    for (String pkg : WhitelistManager.getInstance().getBlackSystemApps()) {
                        arr.put(pkg);
                    }
                    reply.writeNoException();
                    replied = true;
                    reply.writeString(arr.toString());
                    return true;
                }
                case TX_ADD_BLACK_SYSTEM_APP: {
                    String pkg = data.readString();
                    if (pkg == null || pkg.isEmpty()) {
                        reply.writeNoException();
                        replied = true;
                        reply.writeBoolean(false);
                        return true;
                    }
                    WhitelistManager.getInstance().addBlackSystemApp(pkg);
                    reply.writeNoException();
                    replied = true;
                    reply.writeBoolean(true);
                    return true;
                }
                case TX_REMOVE_BLACK_SYSTEM_APP: {
                    String pkg = data.readString();
                    if (pkg == null || pkg.isEmpty()) {
                        reply.writeNoException();
                        replied = true;
                        reply.writeBoolean(false);
                        return true;
                    }
                    WhitelistManager.getInstance().removeBlackSystemApp(pkg);
                    reply.writeNoException();
                    replied = true;
                    reply.writeBoolean(true);
                    return true;
                }
                case TX_FREEZE_PROCESS: {
                    int pid = data.readInt();
                    int uid = data.readInt();
                    boolean result = FreezeManager.getInstance().freezeProcess(pid, uid);
                    reply.writeNoException();
                    replied = true;
                    reply.writeBoolean(result);
                    return true;
                }
                case TX_UNFREEZE_PROCESS: {
                    int pid = data.readInt();
                    int uid = data.readInt();
                    boolean result = FreezeManager.getInstance().unfreezeProcess(pid, uid);
                    reply.writeNoException();
                    replied = true;
                    reply.writeBoolean(result);
                    return true;
                }
                case TX_GET_ALL_PROCESSES: {
                    JSONArray arr = new JSONArray();
                    for (Map.Entry<Integer, AppInfo> entry :
                            ProcessTracker.getInstance().getAllProcesses().entrySet()) {
                        AppInfo info = entry.getValue();
                        JSONObject obj = new JSONObject();
                        obj.put("pid", info.pid);
                        obj.put("uid", info.uid);
                        obj.put("packageName", info.packageName);
                        obj.put("processName", info.processName);
                        obj.put("state", info.getState().ordinal());
                        obj.put("isSystemApp", info.isSystemApp());
                        // 轻微-2: 实时查询 WhitelistManager 而非使用 info.isWhiteListed()，
                        // 后者在注册时设置一次且不再刷新，可能因白名单变更而过时。
                        obj.put("isWhiteListed", !WhitelistManager.getInstance().shouldFreeze(
                            info.packageName, info.processName, info.isSystemApp()));
                        obj.put("oomAdj", info.getOomAdj());
                        arr.put(obj);
                    }
                    reply.writeNoException();
                    replied = true;
                    reply.writeString(arr.toString());
                    return true;
                }
                case TX_GET_FROZEN_COUNT: {
                    int count = 0;
                    for (AppInfo info : ProcessTracker.getInstance().getAllProcesses().values()) {
                        if (info.getState() == AppState.FROZEN) count++;
                    }
                    reply.writeNoException();
                    replied = true;
                    reply.writeInt(count);
                    return true;
                }
                case TX_GET_CURRENT_FREEZER_NAME: {
                    reply.writeNoException();
                    replied = true;
                    reply.writeString(FreezeManager.getInstance().getCurrentFreezerName());
                    return true;
                }
                case TX_READ_LOG: {
                    int maxLines = data.readInt();
                    if (maxLines < 0) maxLines = 0;
                    String log = Logger.readLog(maxLines);
                    reply.writeNoException();
                    replied = true;
                    reply.writeString(log != null ? log : "");
                    return true;
                }
                case TX_CLEAR_LOG: {
                    Logger.clearLog();
                    reply.writeNoException();
                    replied = true;
                    return true;
                }
                case TX_PAUSE_ALL: {
                    FreezeManager.getInstance().pauseAll();
                    reply.writeNoException();
                    replied = true;
                    return true;
                }
                case TX_RESUME_ALL: {
                    FreezeManager.getInstance().resumeAll();
                    reply.writeNoException();
                    replied = true;
                    return true;
                }
                case TX_RESELECT_FREEZER: {
                    FreezeManager.getInstance().reselectFreezer();
                    reply.writeNoException();
                    replied = true;
                    return true;
                }
                case TX_GET_APP_CONFIG: {
                    String pkg = data.readString();
                    org.json.JSONObject config = com.tombstonex.manager.AppConfigManager.getInstance().getAppConfig(pkg);
                    // M5: config 可能为 null（应用无配置），null.toString() 会 NPE
                    String configStr = (config != null) ? config.toString() : "{}";
                    reply.writeNoException();
                    replied = true;
                    reply.writeString(configStr);
                    return true;
                }
                case TX_SET_APP_CONFIG_ITEM: {
                    String pkg = data.readString();
                    String key = data.readString();
                    int type = data.readInt(); // 0=boolean, 1=int, 2=string
                    // M6: 旧代码 type 非 0/1/2 时仍 writeBoolean(true)，改为跟踪 success
                    boolean success = false;
                    switch (type) {
                        case 0: com.tombstonex.manager.AppConfigManager.getInstance().setConfig(pkg, key, data.readBoolean()); success = true; break;
                        case 1: com.tombstonex.manager.AppConfigManager.getInstance().setConfig(pkg, key, data.readInt()); success = true; break;
                        case 2: com.tombstonex.manager.AppConfigManager.getInstance().setConfig(pkg, key, data.readString()); success = true; break;
                    }
                    reply.writeNoException();
                    replied = true;
                    reply.writeBoolean(success);
                    return true;
                }
                case TX_GET_ROTATION_INTERVAL: {
                    int interval = ConfigManager.getInstance().getRotationInterval();
                    reply.writeNoException();
                    replied = true;
                    reply.writeInt(interval);
                    return true;
                }
                case TX_SET_ROTATION_INTERVAL: {
                    int interval = data.readInt();
                    // P7-R7: 跟踪底层持久化结果，避免无条件返回 true
                    boolean success = ConfigManager.getInstance().setRotationInterval(interval);
                    reply.writeNoException();
                    replied = true;
                    reply.writeBoolean(success);
                    return true;
                }
                case TX_GET_APP_PRIORITY: {
                    String pkg = data.readString();
                    int priority = com.tombstonex.manager.OomAdjManager.getInstance().getAppPriority(pkg);
                    reply.writeNoException();
                    replied = true;
                    reply.writeInt(priority);
                    return true;
                }
                case TX_SET_APP_PRIORITY: {
                    String pkg = data.readString();
                    int priority = data.readInt();
                    // P7-R7: 跟踪底层持久化结果，避免无条件返回 true
                    boolean success = com.tombstonex.manager.OomAdjManager.getInstance().setAppPriority(pkg, priority);
                    reply.writeNoException();
                    replied = true;
                    reply.writeBoolean(success);
                    return true;
                }
                case TX_GET_INIT_DATA: {
                    // 批量返回首页所需全部数据：配置 + 白名单 + 进程列表
                    JSONObject result = new JSONObject();
                    // 配置
                    ConfigManager cm = ConfigManager.getInstance();
                    JSONObject config = new JSONObject();
                    config.put("freezeMode", cm.getFreezeMode().getId());
                    config.put("freezeDelay", cm.getFreezeDelay());
                    config.put("debugEnabled", cm.isDebugEnabled());
                    config.put("globalPaused", cm.isGlobalPaused());
                    config.put("hookANR", cm.isHookANREnabled());
                    config.put("hookBroadcast", cm.isHookBroadcastEnabled());
                    config.put("hookWakeLock", cm.isHookWakeLockEnabled());
                    config.put("hookActivitySwitch", cm.isHookActivitySwitchEnabled());
                    config.put("hookScreenState", cm.isHookScreenStateEnabled());
                    result.put("config", config);
                    // 白名单
                    JSONArray whiteArr = new JSONArray();
                    for (String pkg : WhitelistManager.getInstance().getWhiteApps()) whiteArr.put(pkg);
                    result.put("whiteApps", whiteArr);
                    // 进程列表
                    JSONArray procArr = new JSONArray();
                    for (Map.Entry<Integer, AppInfo> entry :
                            ProcessTracker.getInstance().getAllProcesses().entrySet()) {
                        AppInfo info = entry.getValue();
                        JSONObject obj = new JSONObject();
                        obj.put("pid", info.pid);
                        obj.put("uid", info.uid);
                        obj.put("packageName", info.packageName);
                        obj.put("processName", info.processName);
                        obj.put("state", info.getState().ordinal());
                        obj.put("isSystemApp", info.isSystemApp());
                        // 轻微-2: 实时查询 WhitelistManager 而非使用 info.isWhiteListed()，
                        // 后者在注册时设置一次且不再刷新，可能因白名单变更而过时。
                        obj.put("isWhiteListed", !WhitelistManager.getInstance().shouldFreeze(
                            info.packageName, info.processName, info.isSystemApp()));
                        obj.put("oomAdj", info.getOomAdj());
                        procArr.put(obj);
                    }
                    result.put("processes", procArr);
                    reply.writeNoException();
                    replied = true;
                    reply.writeString(result.toString());
                    return true;
                }
                case TX_GET_APP_CONFIG_FULL: {
                    // 批量返回应用配置 + 优先级，避免两次 IPC 往返
                    String pkg = data.readString();
                    JSONObject result = new JSONObject();
                    // M5: getAppConfig 可能返回 null，需 null-safe 处理
                    JSONObject appConfig = com.tombstonex.manager.AppConfigManager.getInstance()
                        .getAppConfig(pkg);
                    result.put("config", (appConfig != null) ? appConfig.toString() : "{}");
                    result.put("priority", com.tombstonex.manager.OomAdjManager.getInstance()
                        .getAppPriority(pkg));
                    reply.writeNoException();
                    replied = true;
                    reply.writeString(result.toString());
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        } catch (RuntimeException e) {
            Logger.e("TombstoneXService 事务出错: " + code, e);
            // P2-02: 仅在 reply 尚未被写入（writeNoException 未调用）时才写入异常，
            // 避免在 writeNoException 之后再 writeException 导致 reply 数据错位
            if (!replied) {
                reply.writeException(e);
            }
            return true;
        }
    }

    /**
     * 注册服务到 ServiceManager（在 system_server 中调用）
     * 使用反射调用隐藏 API android.os.ServiceManager，避免编译期依赖。
     * 尝试多种方法签名以兼容不同 Android 版本。
     */
    public static void register() {
        Logger.i("TombstoneXService.register() 开始...");
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Logger.i("ServiceManager 类已加载: " + smClass.getName());

            java.lang.reflect.Method getService = smClass.getMethod("getService", String.class);
            Logger.i("ServiceManager.getService 已解析");

            // P3-04: 防止重复注册。若服务已注册则跳过
            Object existing = getService.invoke(null, SERVICE_NAME);
            if (existing != null) {
                Logger.i("TombstoneXService 已注册，跳过");
                setRegStatus("already_registered");
                return;
            }

            TombstoneXService serviceInstance = new TombstoneXService();

            // 尝试多种 addService 方法签名，兼容不同 Android 版本
            boolean registered = false;
            String lastError = "";

            // 尝试 1: addService(String, IBinder)
            try {
                java.lang.reflect.Method addService = smClass.getMethod("addService", String.class, IBinder.class);
                addService.invoke(null, SERVICE_NAME, serviceInstance);
                registered = true;
                Logger.i("addService(String, IBinder) 成功");
            } catch (NoSuchMethodException e) {
                Logger.i("未找到 addService(String, IBinder)，尝试其他签名");
                lastError = "NoSuchMethod: addService(String,IBinder)";
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                lastError = (cause != null ? cause.getClass().getSimpleName() : "ITE") + ": " + (cause != null ? cause.getMessage() : "null");
                Logger.e("addService(String, IBinder) InvocationTargetException 异常，原因: " + lastError, cause != null ? cause : e);
            } catch (Throwable e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                Logger.e("addService(String, IBinder) 失败: " + lastError, e);
            }

            // 尝试 2: addService(String, IBinder, boolean allowIsolated)
            if (!registered) {
                try {
                    java.lang.reflect.Method addService = smClass.getMethod("addService", String.class, IBinder.class, boolean.class);
                    addService.invoke(null, SERVICE_NAME, serviceInstance, false);
                    registered = true;
                    Logger.i("addService(String, IBinder, boolean) 成功");
                } catch (NoSuchMethodException e) {
                    Logger.i("未找到 addService(String, IBinder, boolean)");
                    if (lastError.isEmpty()) lastError = "NoSuchMethod: addService(String,IBinder,boolean)";
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    String causeStr = (cause != null ? cause.getClass().getSimpleName() : "ITE") + ": " + (cause != null ? cause.getMessage() : "null");
                    Logger.e("addService(String, IBinder, boolean) InvocationTargetException 异常，原因: " + causeStr, cause != null ? cause : e);
                    // 如果是相同错误则不覆盖第一次的错误信息
                    if (lastError.isEmpty() || lastError.startsWith("ITE") || lastError.startsWith("InvocationTargetException")) {
                        lastError = causeStr;
                    }
                } catch (Throwable e) {
                    String errStr = e.getClass().getSimpleName() + ": " + e.getMessage();
                    Logger.e("addService(String, IBinder, boolean) 失败: " + errStr, e);
                    if (lastError.isEmpty()) lastError = errStr;
                }
            }

            // 尝试 3: 使用 getDeclaredMethod + setAccessible
            if (!registered) {
                try {
                    java.lang.reflect.Method addService = smClass.getDeclaredMethod("addService", String.class, IBinder.class);
                    addService.setAccessible(true);
                    addService.invoke(null, SERVICE_NAME, serviceInstance);
                    registered = true;
                    Logger.i("getDeclaredMethod addService(String, IBinder) 成功");
                } catch (NoSuchMethodException e) {
                    if (lastError.isEmpty()) lastError = "NoSuchMethod (declared): addService(String,IBinder)";
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    String causeStr = (cause != null ? cause.getClass().getSimpleName() : "ITE") + ": " + (cause != null ? cause.getMessage() : "null");
                    Logger.e("getDeclaredMethod addService InvocationTargetException 异常，原因: " + causeStr, cause != null ? cause : e);
                    if (lastError.isEmpty() || lastError.startsWith("ITE") || lastError.startsWith("InvocationTargetException")) {
                        lastError = causeStr;
                    }
                } catch (Throwable e) {
                    String errStr = e.getClass().getSimpleName() + ": " + e.getMessage();
                    Logger.e("getDeclaredMethod addService 失败: " + errStr, e);
                    if (lastError.isEmpty()) lastError = errStr;
                }
            }

            // 验证注册是否成功
            if (registered) {
                Object verify = getService.invoke(null, SERVICE_NAME);
                if (verify != null) {
                    Logger.i("TombstoneXService 注册已验证通过");
                    setRegStatus("ok");
                } else {
                    Logger.e("TombstoneXService 注册验证失败 - addService 后 getService 返回 null");
                    setRegStatus("verify_failed_null");
                }
            } else {
                Logger.e("所有 addService 尝试均失败。最后错误: " + lastError);
                setRegStatus("failed:" + lastError);
            }
        } catch (Throwable t) {
            Logger.e("TombstoneXService 注册失败", t);
            setRegStatus("exception:" + t.getClass().getSimpleName());
        }
    }

    /**
     * 将注册状态写入系统属性，供 App 端读取诊断信息。
     *
     * L10 修复：系统属性值限制 92 字节（含 null 终止符），旧代码按字符数截断，
     * 多字节 UTF-8 字符（如中文）可能导致实际字节数超限被系统截断为乱码。
     * 改为按 UTF-8 字节长度截断到 91 字节。
     */
    private static void setRegStatus(String status) {
        if (status == null) status = "";
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method setMethod = spClass.getMethod("set", String.class, String.class);
            // 按 UTF-8 字节长度截断到 91 字节（系统属性值限制 92 字节，含 null 终止符）
            byte[] bytes = status.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 91) {
                int end = 91;
                // 回退到最后一个完整字符边界，避免截断多字节 UTF-8 字符产生乱码
                while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;
                status = new String(bytes, 0, end, StandardCharsets.UTF_8);
            }
            setMethod.invoke(null, "sys.tombstonex.regstatus", status);
        } catch (Throwable t) {
            Logger.e("设置注册状态属性失败", t);
        }
    }

    /**
     * 供 MainHook 重试线程调用的 public 包装方法。
     */
    public static void setRegStatusPublic(String status) {
        setRegStatus(status);
    }
}
