package com.tombstonex.service;

import android.os.FileObserver;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;

/**
 * 文件系统 IPC 降级通信方案。
 *
 * 当 SELinux 阻止 ServiceManager.addService 时使用此方案。
 * system_server 监控 /data/system/TombstoneX/cmd.json 文件，
 * App 端通过 su 写入命令，system_server 处理后将结果写入 resp.json。
 *
 * 通信协议：
 * - App 写入 cmd.json: {"code": TX_XXX, "args": {...}}
 * - system_server 写入 resp.json: {"ok": true, "data": ...}
 * - App 轮询 resp.json 的修改时间判断是否完成
 */
public class FileIPC {

    private static final String IPC_DIR = "/data/system/TombstoneX";
    private static final String CMD_FILE = IPC_DIR + "/cmd.json";
    private static final String RESP_FILE = IPC_DIR + "/resp.json";
    private static final String READY_FILE = IPC_DIR + "/ipc_ready";

    private static FileObserver cmdObserver;
    private static volatile boolean running = false;
    private static volatile long lastProcessedCmdTime = 0;

    /**
     * 启动文件 IPC 服务（在 system_server 中调用）
     */
    public static void start() {
        if (running) return;
        running = true;

        File dir = new File(IPC_DIR);
        if (!dir.exists()) dir.mkdirs();

        // 写入就绪标记
        try {
            Files.write(new File(READY_FILE).toPath(), "1".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Logger.e("FileIPC: 写入就绪文件失败", e);
        }

        // 创建命令文件和响应文件
        File cmdFile = new File(CMD_FILE);
        File respFile = new File(RESP_FILE);
        try {
            if (!cmdFile.exists()) cmdFile.createNewFile();
            if (!respFile.exists()) respFile.createNewFile();
        } catch (IOException e) {
            Logger.e("FileIPC: 创建 IPC 文件失败", e);
        }

        // 设置文件权限，确保 App 通过 su 可读写
        try {
            new File(IPC_DIR).setReadable(true, false);
            new File(IPC_DIR).setWritable(true, false);
            new File(IPC_DIR).setExecutable(true, false);
            cmdFile.setReadable(true, false);
            cmdFile.setWritable(true, false);
            respFile.setReadable(true, false);
            respFile.setWritable(true, false);
            new File(READY_FILE).setReadable(true, false);
        } catch (Exception e) {
            Logger.e("FileIPC: 设置权限失败", e);
        }

        // 使用 FileObserver 监控命令文件修改
        cmdObserver = new FileObserver(cmdFile, FileObserver.MODIFY | FileObserver.CLOSE_WRITE) {
            @Override
            public void onEvent(int event, String path) {
                if (event == 0) return;
                // 防止重复处理（FileObserver 可能触发多次）
                File f = new File(CMD_FILE);
                long lastMod = f.lastModified();
                if (lastMod == lastProcessedCmdTime) return;
                lastProcessedCmdTime = lastMod;
                handleCommand();
            }
        };
        cmdObserver.startWatching();

        Logger.i("FileIPC 已启动，正在监控 " + CMD_FILE);
    }

    /**
     * 处理命令文件
     */
    private static void handleCommand() {
        try {
            File cmdFile = new File(CMD_FILE);
            String content = readFile(cmdFile);
            if (content == null || content.trim().isEmpty()) return;

            JSONObject cmd = new JSONObject(content);
            int code = cmd.optInt("code", -1);
            JSONObject args = cmd.optJSONObject("args");
            if (args == null) args = new JSONObject();

            Logger.d("FileIPC: 正在处理命令 code=" + code);

            JSONObject resp = new JSONObject();
            try {
                Object result = processCommand(code, args);
                resp.put("ok", true);
                if (result != null) {
                    resp.put("data", result);
                }
            } catch (Exception e) {
                resp.put("ok", false);
                resp.put("error", e.getMessage() != null ? e.getMessage() : e.toString());
                Logger.e("FileIPC: 命令 " + code + " 失败", e);
            }

            // 写入响应
            writeFile(new File(RESP_FILE), resp.toString());

        } catch (Exception e) {
            Logger.e("FileIPC: 处理命令出错", e);
        }
    }

    /**
     * 处理具体命令（与 TombstoneXService.onTransact 逻辑对应）
     */
    private static Object processCommand(int code, JSONObject args) throws Exception {
        switch (code) {
            case TombstoneXService.TX_GET_CONFIG: {
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
                return config;
            }
            case TombstoneXService.TX_SET_FREEZE_MODE: {
                int mode = args.optInt("mode");
                FreezeMode[] modes = FreezeMode.values();
                if (mode < 0 || mode >= modes.length) return false;
                ConfigManager.getInstance().setFreezeMode(modes[mode]);
                return true;
            }
            case TombstoneXService.TX_SET_FREEZE_DELAY: {
                ConfigManager.getInstance().setFreezeDelay(args.optInt("delay"));
                return true;
            }
            case TombstoneXService.TX_SET_DEBUG_ENABLED: {
                ConfigManager.getInstance().setDebugEnabled(args.optBoolean("enabled"));
                return true;
            }
            case TombstoneXService.TX_SET_HOOK_ENABLED: {
                int hookId = args.optInt("hookId");
                boolean enabled = args.optBoolean("enabled");
                ConfigManager cm = ConfigManager.getInstance();
                switch (hookId) {
                    case 0: cm.setHookANREnabled(enabled); break;
                    case 1: cm.setHookBroadcastEnabled(enabled); break;
                    case 2: cm.setHookWakeLockEnabled(enabled); break;
                    case 3: cm.setHookActivitySwitchEnabled(enabled); break;
                    case 4: cm.setHookScreenStateEnabled(enabled); break;
                    default: return false;
                }
                return true;
            }
            case TombstoneXService.TX_SET_GLOBAL_PAUSED: {
                ConfigManager.getInstance().setGlobalPaused(args.optBoolean("paused"));
                return true;
            }
            case TombstoneXService.TX_IS_GLOBAL_PAUSED: {
                return ConfigManager.getInstance().isGlobalPaused();
            }
            case TombstoneXService.TX_GET_WHITE_APPS: {
                Set<String> apps = WhitelistManager.getInstance().getWhiteApps();
                JSONArray arr = new JSONArray();
                for (String pkg : apps) arr.put(pkg);
                return arr;
            }
            case TombstoneXService.TX_ADD_WHITE_APP: {
                String pkg = args.optString("pkg");
                if (pkg == null || pkg.isEmpty()) return false;
                WhitelistManager.getInstance().addWhiteApp(pkg);
                return true;
            }
            case TombstoneXService.TX_REMOVE_WHITE_APP: {
                String pkg = args.optString("pkg");
                if (pkg == null || pkg.isEmpty()) return false;
                WhitelistManager.getInstance().removeWhiteApp(pkg);
                return true;
            }
            case TombstoneXService.TX_GET_WHITE_PROCESSES: {
                Set<String> procs = WhitelistManager.getInstance().getWhiteProcesses();
                JSONArray arr = new JSONArray();
                for (String proc : procs) arr.put(proc);
                return arr;
            }
            case TombstoneXService.TX_ADD_WHITE_PROCESS: {
                String proc = args.optString("proc");
                if (proc == null || proc.isEmpty()) return false;
                WhitelistManager.getInstance().addWhiteProcess(proc);
                return true;
            }
            case TombstoneXService.TX_REMOVE_WHITE_PROCESS: {
                String proc = args.optString("proc");
                if (proc == null || proc.isEmpty()) return false;
                WhitelistManager.getInstance().removeWhiteProcess(proc);
                return true;
            }
            case TombstoneXService.TX_GET_BLACK_SYSTEM_APPS: {
                Set<String> apps = WhitelistManager.getInstance().getBlackSystemApps();
                JSONArray arr = new JSONArray();
                for (String pkg : apps) arr.put(pkg);
                return arr;
            }
            case TombstoneXService.TX_ADD_BLACK_SYSTEM_APP: {
                String pkg = args.optString("pkg");
                if (pkg == null || pkg.isEmpty()) return false;
                WhitelistManager.getInstance().addBlackSystemApp(pkg);
                return true;
            }
            case TombstoneXService.TX_REMOVE_BLACK_SYSTEM_APP: {
                String pkg = args.optString("pkg");
                if (pkg == null || pkg.isEmpty()) return false;
                WhitelistManager.getInstance().removeBlackSystemApp(pkg);
                return true;
            }
            case TombstoneXService.TX_GET_ALL_PROCESSES: {
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
                return arr;
            }
            case TombstoneXService.TX_GET_FROZEN_COUNT: {
                int count = 0;
                for (AppInfo info : ProcessTracker.getInstance().getAllProcesses().values()) {
                    if (info.state == AppState.FROZEN) count++;
                }
                return count;
            }
            case TombstoneXService.TX_GET_CURRENT_FREEZER_NAME: {
                return FreezeManager.getInstance().getCurrentFreezerName();
            }
            case TombstoneXService.TX_FREEZE_PROCESS: {
                int pid = args.optInt("pid");
                int uid = args.optInt("uid");
                return FreezeManager.getInstance().freezeProcess(pid, uid);
            }
            case TombstoneXService.TX_UNFREEZE_PROCESS: {
                int pid = args.optInt("pid");
                int uid = args.optInt("uid");
                return FreezeManager.getInstance().unfreezeProcess(pid, uid);
            }
            case TombstoneXService.TX_PAUSE_ALL: {
                FreezeManager.getInstance().pauseAll();
                return true;
            }
            case TombstoneXService.TX_RESUME_ALL: {
                FreezeManager.getInstance().resumeAll();
                return true;
            }
            case TombstoneXService.TX_RESELECT_FREEZER: {
                FreezeManager.getInstance().reselectFreezer();
                return true;
            }
            case TombstoneXService.TX_READ_LOG: {
                int maxLines = args.optInt("maxLines", 500);
                if (maxLines < 0) maxLines = 0;
                String log = Logger.readLog(maxLines);
                return log != null ? log : "";
            }
            case TombstoneXService.TX_CLEAR_LOG: {
                Logger.clearLog();
                return true;
            }
            case TombstoneXService.TX_GET_APP_CONFIG: {
                String pkg = args.optString("pkg");
                return com.tombstonex.manager.AppConfigManager.getInstance().getAppConfig(pkg).toString();
            }
            case TombstoneXService.TX_SET_APP_CONFIG_ITEM: {
                String pkg = args.optString("pkg");
                String key = args.optString("key");
                String type = args.optString("type", "boolean");
                Object value;
                switch (type) {
                    case "int": value = args.optInt("value"); break;
                    case "string": value = args.optString("value"); break;
                    default: value = args.optBoolean("value"); break;
                }
                com.tombstonex.manager.AppConfigManager.getInstance().setConfig(pkg, key, value);
                return true;
            }
            case TombstoneXService.TX_GET_ROTATION_INTERVAL: {
                return com.tombstonex.manager.ConfigManager.getInstance().getRotationInterval();
            }
            case TombstoneXService.TX_SET_ROTATION_INTERVAL: {
                com.tombstonex.manager.ConfigManager.getInstance().setRotationInterval(args.optInt("interval"));
                return true;
            }
            case TombstoneXService.TX_GET_APP_PRIORITY: {
                String pkg = args.optString("pkg");
                return com.tombstonex.manager.OomAdjManager.getInstance().getAppPriority(pkg);
            }
            case TombstoneXService.TX_SET_APP_PRIORITY: {
                String pkg = args.optString("pkg");
                int priority = args.optInt("priority");
                com.tombstonex.manager.OomAdjManager.getInstance().setAppPriority(pkg, priority);
                return true;
            }
            case TombstoneXService.TX_GET_INIT_DATA: {
                // 批量返回首页所需全部数据
                JSONObject result = new JSONObject();
                ConfigManager cm = ConfigManager.getInstance();
                JSONObject config = new JSONObject();
                config.put("freezeMode", cm.getFreezeMode().ordinal());
                config.put("freezeDelay", cm.getFreezeDelay());
                config.put("debugEnabled", cm.isDebugEnabled());
                config.put("globalPaused", cm.isGlobalPaused());
                config.put("hookANR", cm.isHookANREnabled());
                config.put("hookBroadcast", cm.isHookBroadcastEnabled());
                config.put("hookWakeLock", cm.isHookWakeLockEnabled());
                config.put("hookActivitySwitch", cm.isHookActivitySwitchEnabled());
                config.put("hookScreenState", cm.isHookScreenStateEnabled());
                result.put("config", config);
                JSONArray whiteArr = new JSONArray();
                for (String pkg : WhitelistManager.getInstance().getWhiteApps()) whiteArr.put(pkg);
                result.put("whiteApps", whiteArr);
                JSONArray procArr = new JSONArray();
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
                    procArr.put(obj);
                }
                result.put("processes", procArr);
                return result;
            }
            case TombstoneXService.TX_GET_APP_CONFIG_FULL: {
                String pkg = args.optString("pkg");
                JSONObject result = new JSONObject();
                result.put("config", com.tombstonex.manager.AppConfigManager.getInstance()
                    .getAppConfig(pkg).toString());
                result.put("priority", com.tombstonex.manager.OomAdjManager.getInstance()
                    .getAppPriority(pkg));
                return result;
            }
            default:
                throw new IllegalArgumentException("Unknown command code: " + code);
        }
    }

    /**
     * App 端检查 FileIPC 是否就绪
     */
    public static boolean isReady() {
        return new File(READY_FILE).exists();
    }

    private static String readFile(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeFile(File file, String content) {
        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write(content);
            writer.flush();
        } catch (IOException e) {
            Logger.e("FileIPC: 写入文件出错", e);
        }
    }
}
