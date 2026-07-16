package com.tombstonex.service;

import android.os.Binder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;

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

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        // 安全：仅允许 system uid 调用
        int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID && callingUid != 0) {
            reply.writeException(new SecurityException("Permission denied"));
            return true;
        }
        try {
            // 验证接口描述符
            data.enforceInterface(DESCRIPTOR);
            switch (code) {
                case TX_GET_CONFIG: {
                    JSONObject config = new JSONObject();
                    ConfigManager cm = ConfigManager.getInstance();
                    config.put("freezeMode", cm.getFreezeMode().ordinal());
                    config.put("freezeDelay", cm.getFreezeDelay());
                    config.put("debugEnabled", cm.isDebugEnabled());
                    config.put("globalPaused", cm.isGlobalPaused());
                    config.put("hookANR", cm.isHookANREnabled());
                    config.put("hookBroadcast", cm.isHookBroadcastEnabled());
                    config.put("hookWakeLock", cm.isHookWakeLockEnabled());
                    config.put("hookActivitySwitch", cm.isHookActivitySwitchEnabled());
                    config.put("hookScreenState", cm.isHookScreenStateEnabled());
                    reply.writeNoException();
                    reply.writeString(config.toString());
                    return true;
                }
                case TX_SET_FREEZE_MODE: {
                    int mode = data.readInt();
                    FreezeMode[] modes = FreezeMode.values();
                    if (mode < 0 || mode >= modes.length) {
                        reply.writeNoException();
                        reply.writeBoolean(false);
                        return true;
                    }
                    ConfigManager.getInstance().setFreezeMode(modes[mode]);
                    reply.writeNoException();
                    reply.writeBoolean(true);
                    return true;
                }
                case TX_SET_FREEZE_DELAY: {
                    int delay = data.readInt();
                    ConfigManager.getInstance().setFreezeDelay(delay);
                    reply.writeNoException();
                    return true;
                }
                case TX_SET_DEBUG_ENABLED: {
                    boolean enabled = data.readBoolean();
                    ConfigManager.getInstance().setDebugEnabled(enabled);
                    reply.writeNoException();
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
                            reply.writeBoolean(false);
                            return true;
                    }
                    reply.writeNoException();
                    reply.writeBoolean(true);
                    return true;
                }
                case TX_SET_GLOBAL_PAUSED: {
                    boolean paused = data.readBoolean();
                    ConfigManager.getInstance().setGlobalPaused(paused);
                    reply.writeNoException();
                    return true;
                }
                case TX_IS_GLOBAL_PAUSED: {
                    reply.writeNoException();
                    reply.writeBoolean(ConfigManager.getInstance().isGlobalPaused());
                    return true;
                }
                case TX_GET_WHITE_APPS: {
                    JSONArray arr = new JSONArray();
                    for (String pkg : WhitelistManager.getInstance().getWhiteApps()) {
                        arr.put(pkg);
                    }
                    reply.writeNoException();
                    reply.writeString(arr.toString());
                    return true;
                }
                case TX_ADD_WHITE_APP: {
                    String pkg = data.readString();
                    WhitelistManager.getInstance().addWhiteApp(pkg);
                    reply.writeNoException();
                    return true;
                }
                case TX_REMOVE_WHITE_APP: {
                    String pkg = data.readString();
                    WhitelistManager.getInstance().removeWhiteApp(pkg);
                    reply.writeNoException();
                    return true;
                }
                case TX_GET_WHITE_PROCESSES: {
                    JSONArray arr = new JSONArray();
                    for (String proc : WhitelistManager.getInstance().getWhiteProcesses()) {
                        arr.put(proc);
                    }
                    reply.writeNoException();
                    reply.writeString(arr.toString());
                    return true;
                }
                case TX_ADD_WHITE_PROCESS: {
                    String proc = data.readString();
                    WhitelistManager.getInstance().addWhiteProcess(proc);
                    reply.writeNoException();
                    return true;
                }
                case TX_REMOVE_WHITE_PROCESS: {
                    String proc = data.readString();
                    WhitelistManager.getInstance().removeWhiteProcess(proc);
                    reply.writeNoException();
                    return true;
                }
                case TX_GET_BLACK_SYSTEM_APPS: {
                    JSONArray arr = new JSONArray();
                    for (String pkg : WhitelistManager.getInstance().getBlackSystemApps()) {
                        arr.put(pkg);
                    }
                    reply.writeNoException();
                    reply.writeString(arr.toString());
                    return true;
                }
                case TX_ADD_BLACK_SYSTEM_APP: {
                    String pkg = data.readString();
                    WhitelistManager.getInstance().addBlackSystemApp(pkg);
                    reply.writeNoException();
                    return true;
                }
                case TX_REMOVE_BLACK_SYSTEM_APP: {
                    String pkg = data.readString();
                    WhitelistManager.getInstance().removeBlackSystemApp(pkg);
                    reply.writeNoException();
                    return true;
                }
                case TX_FREEZE_PROCESS: {
                    int pid = data.readInt();
                    int uid = data.readInt();
                    boolean result = FreezeManager.getInstance().freezeProcess(pid, uid);
                    reply.writeNoException();
                    reply.writeBoolean(result);
                    return true;
                }
                case TX_UNFREEZE_PROCESS: {
                    int pid = data.readInt();
                    int uid = data.readInt();
                    boolean result = FreezeManager.getInstance().unfreezeProcess(pid, uid);
                    reply.writeNoException();
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
                        obj.put("state", info.state.ordinal());
                        obj.put("isSystemApp", info.isSystemApp);
                        obj.put("isWhiteListed", info.isWhiteListed);
                        obj.put("oomAdj", info.oomAdj);
                        arr.put(obj);
                    }
                    reply.writeNoException();
                    reply.writeString(arr.toString());
                    return true;
                }
                case TX_GET_FROZEN_COUNT: {
                    int count = 0;
                    for (AppInfo info : ProcessTracker.getInstance().getAllProcesses().values()) {
                        if (info.state == AppState.FROZEN) count++;
                    }
                    reply.writeNoException();
                    reply.writeInt(count);
                    return true;
                }
                case TX_GET_CURRENT_FREEZER_NAME: {
                    reply.writeNoException();
                    reply.writeString(FreezeManager.getInstance().getCurrentFreezerName());
                    return true;
                }
                case TX_READ_LOG: {
                    int maxLines = data.readInt();
                    String log = Logger.readLog(maxLines);
                    reply.writeNoException();
                    reply.writeString(log != null ? log : "");
                    return true;
                }
                case TX_CLEAR_LOG: {
                    Logger.clearLog();
                    reply.writeNoException();
                    return true;
                }
                case TX_PAUSE_ALL: {
                    FreezeManager.getInstance().pauseAll();
                    reply.writeNoException();
                    return true;
                }
                case TX_RESUME_ALL: {
                    FreezeManager.getInstance().resumeAll();
                    reply.writeNoException();
                    return true;
                }
                case TX_RESELECT_FREEZER: {
                    FreezeManager.getInstance().reselectFreezer();
                    reply.writeNoException();
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        } catch (Exception e) {
            Logger.e("TombstoneXService transaction error: " + code, e);
            reply.writeException(e);
            return true;
        }
    }

    /**
     * 注册服务到 ServiceManager（在 system_server 中调用）
     */
    public static void register() {
        try {
            ServiceManager.addService(SERVICE_NAME, new TombstoneXService());
            Logger.i("TombstoneXService registered as '" + SERVICE_NAME + "'");
        } catch (Throwable t) {
            Logger.e("Failed to register TombstoneXService", t);
        }
    }
}
