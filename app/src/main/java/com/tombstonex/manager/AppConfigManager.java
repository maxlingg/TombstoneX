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
    // S9: per-package 锁，保护 setConfig 的读-改-写原子性
    //
    // P7-R7 设计决策：packageLocks 随应用数量增长但永不清理。
    // 每个 Entry 的 key 为包名（约 30 字节 String + Entry 节点开销），
    // value 为一个 Object（约 16 字节）。200 个应用约 3.2KB + 包名字符串开销，
    // 总量 < 10KB，远小于配置 JSON 缓存与文件本身。清理逻辑（如 reload 时
    // 比对当前配置包名并 evict）会引入额外复杂度与并发竞态（删除某 package 的
    // 锁时该 package 可能正被另一线程持有），收益甚微。故保留当前实现不做清理。
    private final ConcurrentHashMap<String, Object> packageLocks = new ConcurrentHashMap<>();
    // M-17 锁顺序: packageLock → AppConfigManager 实例锁（saveToFile 的 synchronized）。
    // setConfig / setAppConfig 先获取 packageLock，再调用 setAppConfig → saveToFile
    // （synchronized 方法）。不得反向获取，否则死锁。

    private AppConfigManager() {
        // 确保配置目录存在
        try {
            File dir = new File(CONFIG_DIR);
            if (!dir.exists()) dir.mkdirs();
        } catch (Throwable t) {
            Logger.e("AppConfigManager: 创建配置目录失败: " + t.getMessage());
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
        // M-18: 改用 get() + putIfAbsent 替代 computeIfAbsent，避免在
        // ConcurrentHashMap 内部锁持有期间执行文件 IO。
        JSONObject existing = cache.get(packageName);
        if (existing != null) {
            return copy(existing);
        }
        JSONObject loaded = loadFromFile(packageName);
        JSONObject result = (loaded != null) ? applyDefaults(loaded) : defaultConfig();
        JSONObject winner = cache.putIfAbsent(packageName, result);
        return copy(winner != null ? winner : result);
    }

    /**
     * 保存应用完整配置。会先补齐缺失的默认键，再原子写入文件并更新内存缓存。
     *
     * @param packageName 包名
     * @param config      配置内容
     */
    public void setAppConfig(String packageName, JSONObject config) {
        if (packageName == null || config == null) return;
        // 轻微-5: 与 setConfig 一样获取 per-package 锁，保证 setAppConfig 的
        // "applyDefaults→写缓存→写文件"序列与 getAppConfig/setConfig 之间的原子性。
        Object lock = packageLocks.computeIfAbsent(packageName, k -> new Object());
        synchronized (lock) {
            JSONObject toSave = applyDefaults(config);
            // M1: 先写文件，仅写入成功后才更新缓存，避免文件写入失败导致缓存与文件不一致。
            if (saveToFile(packageName, toSave)) {
                cache.put(packageName, toSave);
            } else {
                Logger.w("配置文件写入失败，缓存未更新: " + packageName);
            }
        }
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
            // 类型不匹配时返回默认值，而非强转（避免 ClassCastException）
            Logger.w("AppConfigManager: 配置 " + key + " 类型不匹配"
                + "（期望 " + defaultValue.getClass().getSimpleName()
                + "，实际 " + val.getClass().getSimpleName() + "）");
            return defaultValue;
        } catch (JSONException e) {
            Logger.w("AppConfigManager: 获取键 " + key
                + " 失败（" + packageName + "）: " + e.getMessage());
            return defaultValue;
        }
    }

    /**
     * 修改单个配置项。内部读取当前完整配置 -> 更新单个键 -> 整体保存。
     *
     * <p>S9: 使用 per-package 锁保证读-改-写原子性，避免并发 setConfig
     * 互相覆盖。
     *
     * @param packageName 包名
     * @param key         配置键
     * @param value       新值
     */
    public void setConfig(String packageName, String key, Object value) {
        if (packageName == null || key == null || value == null) return;
        Object lock = packageLocks.computeIfAbsent(packageName, k -> new Object());
        synchronized (lock) {
            JSONObject config = getAppConfig(packageName);
            try {
                config.put(key, value);
            } catch (JSONException e) {
                Logger.e("AppConfigManager: 设置键 " + key
                    + " 失败（" + packageName + "）", e);
                return;
            }
            setAppConfig(packageName, config);
        }
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
            Logger.e("AppConfigManager: 构建默认配置失败", e);
            // 兜底：确保所有键都存在，即使 JSONException 发生于部分键写入后
            ensureDefaultKey(config, KEY_FREEZE_ENABLED, DEFAULT_FREEZE_ENABLED);
            ensureDefaultKey(config, KEY_BACKGROUND_LEVEL, DEFAULT_BACKGROUND_LEVEL);
            ensureDefaultKey(config, KEY_PLAY_ALLOWED, DEFAULT_PLAY_ALLOWED);
            ensureDefaultKey(config, KEY_ONGOING_NOTIFICATION, DEFAULT_ONGOING_NOTIFICATION);
            ensureDefaultKey(config, KEY_NET_TRANSFER, DEFAULT_NET_TRANSFER);
            ensureDefaultKey(config, KEY_AUTO_START_ALLOWED, DEFAULT_AUTO_START_ALLOWED);
            ensureDefaultKey(config, KEY_KEEP_CONNECTION, DEFAULT_KEEP_CONNECTION);
            ensureDefaultKey(config, KEY_PRIORITY, DEFAULT_PRIORITY);
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
                Logger.d("AppConfigManager: 跳过键 " + key + ": " + e.getMessage());
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
            Logger.e("AppConfigManager: 复制配置失败", e);
            return defaultConfig();
        }
    }

    /**
     * 确保 JSONObject 中存在指定键，若不存在则写入默认值。
     * 用于 defaultConfig() 的 JSONException 兜底，保证所有键最终都存在。
     */
    private void ensureDefaultKey(JSONObject config, String key, Object defaultValue) {
        if (!config.has(key)) {
            try {
                config.put(key, defaultValue);
            } catch (JSONException ignored) {
                Logger.d("AppConfigManager: 确保默认键 " + key + " 失败", ignored);
            }
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
            Logger.e("AppConfigManager: 读取配置失败（" + packageName + "）", e);
            return null;
        } catch (JSONException e) {
            Logger.e("AppConfigManager: JSON 格式错误（" + packageName + "）", e);
            return null;
        }
    }

    /**
     * 原子写入配置文件：先写 &lt;包名&gt;.json.tmp，再 ATOMIC_MOVE 替换原文件。
     * M9: 加 synchronized 保证并发写文件的线程安全。
     *
     * <p>M1: 返回值改为 boolean，写入成功返回 true，失败返回 false。
     * 调用方（setAppConfig）据此决定是否更新内存缓存，避免缓存与文件不一致。
     *
     * @return true 表示写入成功；false 表示写入失败（已记录日志并清理临时文件）
     */
    private synchronized boolean saveToFile(String packageName, JSONObject config) {
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
            return true;
        } catch (IOException e) {
            Logger.e("AppConfigManager: 保存配置失败（" + packageName + "）", e);
            if (!tmpFile.delete()) {
                Logger.w("清理临时文件失败: " + tmpFile.getPath());
            }
            return false;
        }
    }
}
