package com.tombstonex.manager;

import com.tombstonex.util.Logger;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 应用级配置管理器。
 *
 * 为每个应用存储独立的 JSON 配置文件，路径：
 * <pre>
 *   /data/system/TombstoneX/app_config/&lt;包名&gt;.json
 * </pre>
 *
 * 支持的配置项（每个应用独立，均带默认值）：
 * <ul>
 *   <li>freezeEnabled        (boolean, 默认 true)  是否参与冻结</li>
 *   <li>backgroundLevel      (int, 默认 0)          后台级别 0=前台服务 1=可见窗口 2=强制冻结</li>
 *   <li>playAllowed          (boolean, 默认 false)  后台播放不冻结</li>
 *   <li>ongoingNotification  (boolean, 默认 false)  常驻通知不冻结</li>
 *   <li>netTransfer          (boolean, 默认 false)  网速识别不冻结</li>
 *   <li>autoStartAllowed     (boolean, 默认 false)  允许自启</li>
 *   <li>keepConnection       (boolean, 默认 false)  冻结后保持网络连接</li>
 *   <li>priority             (int, 默认 1)          优先级 0=高 1=中 2=低</li>
 * </ul>
 *
 * 使用 {@link ConcurrentHashMap} 作为内存缓存，文件写入采用原子操作
 * （先写 .tmp 再 {@link java.nio.file.StandardCopyOption#ATOMIC_MOVE}）。
 */
public class AppConfigManager {
    private static final String CONFIG_DIR = "/data/system/TombstoneX/app_config";

    // ---- 配置键 ----
    public static final String KEY_FREEZE_ENABLED = "freezeEnabled";
    public static final String KEY_BACKGROUND_LEVEL = "backgroundLevel";
    public static final String KEY_PLAY_ALLOWED = "playAllowed";
    public static final String KEY_ONGOING_NOTIFICATION = "ongoingNotification";
    public static final String KEY_NET_TRANSFER = "netTransfer";
    public static final String KEY_AUTO_START_ALLOWED = "autoStartAllowed";
    public static final String KEY_KEEP_CONNECTION = "keepConnection";
    public static final String KEY_PRIORITY = "priority";

    // ---- 默认值 ----
    private static final boolean DEFAULT_FREEZE_ENABLED = true;
    private static final int DEFAULT_BACKGROUND_LEVEL = 0;
    private static final boolean DEFAULT_PLAY_ALLOWED = false;
    private static final boolean DEFAULT_ONGOING_NOTIFICATION = false;
    private static final boolean DEFAULT_NET_TRANSFER = false;
    private static final boolean DEFAULT_AUTO_START_ALLOWED = false;
    private static final boolean DEFAULT_KEEP_CONNECTION = false;
    private static final int DEFAULT_PRIORITY = 1;

    private static volatile AppConfigManager instance;

    private final ConcurrentHashMap<String, JSONObject> cache = new ConcurrentHashMap<>();

    private AppConfigManager() {
        // 确保配置目录存在
        try {
            File dir = new File(CONFIG_DIR);
            if (!dir.exists()) dir.mkdirs();
        } catch (Throwable t) {
            Logger.e("AppConfigManager: failed to create config dir: " + t.getMessage());
        }
    }

    // P3-R6: 使用双重检查锁定
    public static AppConfigManager getInstance() {
        AppConfigManager local = instance;
        if (local == null) {
            synchronized (AppConfigManager.class) {
                local = instance;
                if (local == null) {
                    local = new AppConfigManager();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * 获取应用的完整配置。
     *
     * <p>优先读内存缓存；缓存未命中读文件；文件不存在或损坏则返回带默认值的配置。
     * 返回的是配置的<strong>副本</strong>，调用方修改不会影响内部缓存。
     *
     * @param packageName 包名
     * @return 完整配置 JSONObject（至少包含所有默认键）
     */
    public JSONObject getAppConfig(String packageName) {
        if (packageName == null) return defaultConfig();

        JSONObject cached = cache.get(packageName);
        if (cached != null) {
            return copy(cached);
        }

        JSONObject loaded = loadFromFile(packageName);
        if (loaded == null) {
            loaded = defaultConfig();
        } else {
            loaded = applyDefaults(loaded);
        }
        cache.put(packageName, loaded);
        return copy(loaded);
    }

    /**
     * 保存应用完整配置。会先补齐缺失的默认键，再原子写入文件并更新内存缓存。
     *
     * @param packageName 包名
     * @param config      配置内容
     */
    public void setAppConfig(String packageName, JSONObject config) {
        if (packageName == null || config == null) return;
        JSONObject toSave = applyDefaults(config);
        cache.put(packageName, toSave);
        saveToFile(packageName, toSave);
    }

    /**
     * 泛型方法：读取单个配置项，不存在或类型不匹配时返回 defaultValue。
     *
     * <p>支持 Boolean / Integer / Long / Double / String 等常见类型；
     * JSON 数字会根据 defaultValue 的类型做相应转换。
     *
     * @param packageName 包名
     * @param key         配置键
     * @param defaultValue 默认值（同时决定返回类型）
     * @return 配置值
     */
    @SuppressWarnings("unchecked")
    public <T> T getConfig(String packageName, String key, T defaultValue) {
        if (key == null || defaultValue == null) return defaultValue;
        JSONObject config = getAppConfig(packageName);
        if (!config.has(key)) return defaultValue;
        try {
            Object val = config.get(key);
            if (val == null) return defaultValue;

            // 类型适配：JSON 数字可能是 Integer / Long / Double
            if (defaultValue instanceof Integer && val instanceof Number) {
                return (T) Integer.valueOf(((Number) val).intValue());
            }
            if (defaultValue instanceof Long && val instanceof Number) {
                return (T) Long.valueOf(((Number) val).longValue());
            }
            if (defaultValue instanceof Double && val instanceof Number) {
                return (T) Double.valueOf(((Number) val).doubleValue());
            }
            if (defaultValue instanceof Boolean && val instanceof Boolean) {
                return (T) val;
            }
            if (defaultValue instanceof String && val instanceof String) {
                return (T) val;
            }
            return (T) val;
        } catch (JSONException e) {
            Logger.w("AppConfigManager: failed to get key " + key
                + " for " + packageName + ": " + e.getMessage());
            return defaultValue;
        }
    }

    /**
     * 修改单个配置项。内部读取当前完整配置 -> 更新单个键 -> 整体保存。
     *
     * @param packageName 包名
     * @param key         配置键
     * @param value       新值
     */
    public void setConfig(String packageName, String key, Object value) {
        if (packageName == null || key == null || value == null) return;
        JSONObject config = getAppConfig(packageName);
        try {
            config.put(key, value);
        } catch (JSONException e) {
            Logger.e("AppConfigManager: failed to set key " + key
                + " for " + packageName, e);
            return;
        }
        setAppConfig(packageName, config);
    }

    // ---- 便捷访问方法 ----

    public boolean isFreezeEnabled(String packageName) {
        return getConfig(packageName, KEY_FREEZE_ENABLED, DEFAULT_FREEZE_ENABLED);
    }

    public int getBackgroundLevel(String packageName) {
        return getConfig(packageName, KEY_BACKGROUND_LEVEL, DEFAULT_BACKGROUND_LEVEL);
    }

    public boolean isPlayAllowed(String packageName) {
        return getConfig(packageName, KEY_PLAY_ALLOWED, DEFAULT_PLAY_ALLOWED);
    }

    public boolean hasOngoingNotification(String packageName) {
        return getConfig(packageName, KEY_ONGOING_NOTIFICATION, DEFAULT_ONGOING_NOTIFICATION);
    }

    public boolean isNetTransfer(String packageName) {
        return getConfig(packageName, KEY_NET_TRANSFER, DEFAULT_NET_TRANSFER);
    }

    public boolean isAutoStartAllowed(String packageName) {
        return getConfig(packageName, KEY_AUTO_START_ALLOWED, DEFAULT_AUTO_START_ALLOWED);
    }

    public boolean isKeepConnection(String packageName) {
        return getConfig(packageName, KEY_KEEP_CONNECTION, DEFAULT_KEEP_CONNECTION);
    }

    public int getPriority(String packageName) {
        return getConfig(packageName, KEY_PRIORITY, DEFAULT_PRIORITY);
    }

    // ---- 缓存管理 ----

    /**
     * 清除指定应用的内存缓存，下次读取将从文件重新加载。
     */
    public void reload(String packageName) {
        if (packageName == null) return;
        cache.remove(packageName);
    }

    /**
     * 清空所有内存缓存。
     */
    public void clearCache() {
        cache.clear();
    }

    // ---- 内部实现 ----

    /**
     * 构造包含全部默认键值的配置。
     */
    private JSONObject defaultConfig() {
        JSONObject config = new JSONObject();
        try {
            config.put(KEY_FREEZE_ENABLED, DEFAULT_FREEZE_ENABLED);
            config.put(KEY_BACKGROUND_LEVEL, DEFAULT_BACKGROUND_LEVEL);
            config.put(KEY_PLAY_ALLOWED, DEFAULT_PLAY_ALLOWED);
            config.put(KEY_ONGOING_NOTIFICATION, DEFAULT_ONGOING_NOTIFICATION);
            config.put(KEY_NET_TRANSFER, DEFAULT_NET_TRANSFER);
            config.put(KEY_AUTO_START_ALLOWED, DEFAULT_AUTO_START_ALLOWED);
            config.put(KEY_KEEP_CONNECTION, DEFAULT_KEEP_CONNECTION);
            config.put(KEY_PRIORITY, DEFAULT_PRIORITY);
        } catch (JSONException e) {
            Logger.e("AppConfigManager: failed to build default config", e);
        }
        return config;
    }

    /**
     * 以默认配置为基底，用传入配置覆盖已存在的键，保证返回值包含全部默认键。
     */
    private JSONObject applyDefaults(JSONObject config) {
        JSONObject result = defaultConfig();
        java.util.Iterator<String> it = config.keys();
        while (it.hasNext()) {
            String key = it.next();
            try {
                result.put(key, config.get(key));
            } catch (JSONException e) {
                Logger.d("AppConfigManager: skip key " + key + ": " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * 返回 JSONObject 的深拷贝（通过序列化/反序列化）。
     * 避免调用方修改返回值污染内部缓存。
     */
    private JSONObject copy(JSONObject src) {
        try {
            return new JSONObject(src.toString());
        } catch (JSONException e) {
            Logger.e("AppConfigManager: failed to copy config", e);
            return defaultConfig();
        }
    }

    private File configFile(String packageName) {
        return new File(CONFIG_DIR, packageName + ".json");
    }

    private JSONObject loadFromFile(String packageName) {
        File file = configFile(packageName);
        if (!file.exists()) return null;
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (content.isEmpty()) return null;
            return new JSONObject(content);
        } catch (IOException e) {
            Logger.e("AppConfigManager: failed to read config for " + packageName, e);
            return null;
        } catch (JSONException e) {
            Logger.e("AppConfigManager: malformed JSON for " + packageName, e);
            return null;
        }
    }

    /**
     * 原子写入配置文件：先写 &lt;包名&gt;.json.tmp，再 ATOMIC_MOVE 替换原文件。
     */
    private void saveToFile(String packageName, JSONObject config) {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) dir.mkdirs();
        File file = configFile(packageName);
        File tmpFile = new File(CONFIG_DIR, packageName + ".json.tmp");
        try {
            Files.write(tmpFile.toPath(),
                config.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmpFile.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Logger.e("AppConfigManager: failed to save config for " + packageName, e);
            tmpFile.delete();
        }
    }
}
