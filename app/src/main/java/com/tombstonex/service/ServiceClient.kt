package com.tombstonex.service

import android.os.Parcel
import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * UI 侧客户端，通过 Binder 与 system_server 中的 TombstoneXService 通信。
 *
 * 通过反射 ServiceManager.getService("tombstonex") 获取 Binder 代理。
 * 所有方法在 IO 线程调用（非主线程），返回 null/默认值表示服务不可用。
 */
object ServiceClient {

    private const val SERVICE_NAME = "tombstonex"
    private const val DESCRIPTOR = "com.tombstonex.service.TombstoneXService"

    // 事务码 — 必须与 TombstoneXService.java 保持一致
    private const val TX_GET_CONFIG = 1
    private const val TX_SET_FREEZE_MODE = 2
    private const val TX_SET_FREEZE_DELAY = 3
    private const val TX_SET_DEBUG_ENABLED = 4
    private const val TX_SET_HOOK_ENABLED = 5
    private const val TX_SET_GLOBAL_PAUSED = 6
    private const val TX_IS_GLOBAL_PAUSED = 7
    private const val TX_GET_WHITE_APPS = 8
    private const val TX_ADD_WHITE_APP = 9
    private const val TX_REMOVE_WHITE_APP = 10
    private const val TX_GET_WHITE_PROCESSES = 11
    private const val TX_ADD_WHITE_PROCESS = 12
    private const val TX_REMOVE_WHITE_PROCESS = 13
    private const val TX_GET_BLACK_SYSTEM_APPS = 14
    private const val TX_ADD_BLACK_SYSTEM_APP = 15
    private const val TX_REMOVE_BLACK_SYSTEM_APP = 16
    private const val TX_FREEZE_PROCESS = 17
    private const val TX_UNFREEZE_PROCESS = 18
    private const val TX_GET_ALL_PROCESSES = 19
    private const val TX_GET_FROZEN_COUNT = 20
    private const val TX_GET_CURRENT_FREEZER_NAME = 21
    private const val TX_READ_LOG = 22
    private const val TX_CLEAR_LOG = 23
    private const val TX_PAUSE_ALL = 24
    private const val TX_RESUME_ALL = 25
    private const val TX_RESELECT_FREEZER = 26
    private const val TX_GET_APP_CONFIG = 27
    // S4 修复：TX_SET_APP_CONFIG (28) 服务端无 onTransact 处理且客户端无调用方，
    // 属于无用常量，已删除（同步删除 TombstoneXService.java 中的声明）。
    private const val TX_SET_APP_CONFIG_ITEM = 29
    private const val TX_GET_ROTATION_INTERVAL = 30
    private const val TX_SET_ROTATION_INTERVAL = 31
    private const val TX_GET_APP_PRIORITY = 32
    private const val TX_SET_APP_PRIORITY = 33
    // 批量事务（减少 IPC 往返次数）
    private const val TX_GET_INIT_DATA = 34
    private const val TX_GET_APP_CONFIG_FULL = 35

    /** 缓存的 IBinder 代理 */
    @Volatile
    private var binder: android.os.IBinder? = null

    /**
     * 模块是否已激活（Binder 服务可用即视为激活）。
     */
    val isAvailable: Boolean
        get() = getBinder() != null

    /**
     * 获取 Binder 服务注册失败的诊断信息。
     */
    val regStatus: String
        get() = try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getMethod("get", String::class.java)
            getMethod.invoke(null, "sys.tombstonex.regstatus") as? String ?: ""
        } catch (e: Throwable) {
            ""
        }

    /**
     * 模块是否已被 LSPosed 启用
     *
     * M5 关联修复：读取非持久属性 sys.tombstonex.loaded（与 MainHook.initZygote 写入一致）。
     * 旧属性 persist.sys.tombstonex.loaded 跨重启持久化，禁用模块后仍为 1，导致误判。
     */
    val isModuleEnabled: Boolean
        get() = try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getMethod("get", String::class.java)
            val value = getMethod.invoke(null, "sys.tombstonex.loaded") as? String
            value == "1"
        } catch (e: Throwable) {
            false
        }

    /**
     * 模块是否已加载到 system_server
     */
    val isModuleLoaded: Boolean
        get() = try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getMethod("get", String::class.java)
            // R9-M3: 读取非持久属性 sys.tombstonex.active（与 MainHook 写入侧一致）。
            // 旧属性 persist.sys.tombstonex.active 跨重启持久化，模块禁用后重启仍为 "1"，
            // 导致误判模块已加载到 system_server。
            val value = getMethod.invoke(null, "sys.tombstonex.active") as? String
            value == "1"
        } catch (e: Throwable) {
            false
        }

    /** 通过反射 ServiceManager.getService 获取 Binder 代理 */
    private fun getBinder(): android.os.IBinder? {
        val cached = binder
        if (cached != null) {
            if (cached.isBinderAlive) return cached
            binder = null
        }
        return try {
            val clazz = Class.forName("android.os.ServiceManager")
            val method = clazz.getMethod("getService", String::class.java)
            val b = method.invoke(null, SERVICE_NAME) as? android.os.IBinder
            if (b != null) binder = b
            b
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 执行一次 Binder transact 调用
     */
    private fun <T> transact(
        code: Int,
        prepare: (Parcel) -> Unit = {},
        parse: (Parcel) -> T?
    ): T? {
        val b = getBinder() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            prepare(data)
            val ok = b.transact(code, data, reply, 0)
            if (!ok) return null
            reply.readException()
            parse(reply)
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            android.util.Log.e("TombstoneX", "ServiceClient.transact failed: ${e.message}", e)
            if (e is android.os.DeadObjectException) {
                binder = null
            }
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * 统一调用入口：通过 Binder 与 system_server 通信。
     */
    private fun <T> call(
        code: Int,
        prepare: (Parcel) -> Unit = {},
        parse: (Parcel) -> T?
    ): T? {
        return transact(code, prepare, parse)
    }

    // ====== 配置 ======

    @Immutable
    data class ConfigSnapshot(
        val freezeMode: Int,
        val freezeDelay: Int,
        val debugEnabled: Boolean,
        val globalPaused: Boolean,
        val hookANR: Boolean,
        val hookBroadcast: Boolean,
        val hookWakeLock: Boolean,
        val hookActivitySwitch: Boolean,
        val hookScreenState: Boolean,
    )

    fun getConfig(): ConfigSnapshot? {
        return call(
            TX_GET_CONFIG,
            parse = { reply ->
                val json = JSONObject(reply.readString() ?: "{}")
                ConfigSnapshot(
                    freezeMode = json.optInt("freezeMode", 4),
                    freezeDelay = json.optInt("freezeDelay", 3),
                    debugEnabled = json.optBoolean("debugEnabled", false),
                    globalPaused = json.optBoolean("globalPaused", false),
                    hookANR = json.optBoolean("hookANR", true),
                    hookBroadcast = json.optBoolean("hookBroadcast", true),
                    hookWakeLock = json.optBoolean("hookWakeLock", true),
                    hookActivitySwitch = json.optBoolean("hookActivitySwitch", true),
                    hookScreenState = json.optBoolean("hookScreenState", true),
                )
            }
        )
    }

    fun setFreezeMode(modeId: Int): Boolean {
        return call(
            TX_SET_FREEZE_MODE,
            prepare = { it.writeInt(modeId) },
            parse = { it.readBoolean() }
        ) ?: false
    }

    fun setFreezeDelay(delay: Int): Boolean {
        return call(
            TX_SET_FREEZE_DELAY,
            prepare = { it.writeInt(delay) },
            parse = { true }
        ) ?: false
    }

    fun setDebugEnabled(enabled: Boolean): Boolean {
        return call(
            TX_SET_DEBUG_ENABLED,
            prepare = { it.writeBoolean(enabled) },
            parse = { true }
        ) ?: false
    }

    /** hookId: 0=ANR, 1=Broadcast, 2=WakeLock, 3=ActivitySwitch, 4=ScreenState */
    fun setHookEnabled(hookId: Int, enabled: Boolean): Boolean {
        return call(
            TX_SET_HOOK_ENABLED,
            prepare = { it.writeInt(hookId); it.writeBoolean(enabled) },
            parse = { it.readBoolean() }
        ) ?: false
    }

    fun setGlobalPaused(paused: Boolean): Boolean {
        return call(
            TX_SET_GLOBAL_PAUSED,
            prepare = { it.writeBoolean(paused) },
            parse = { true }
        ) ?: false
    }

    fun isGlobalPaused(): Boolean {
        return call(
            TX_IS_GLOBAL_PAUSED,
            parse = { it.readBoolean() }
        ) ?: false
    }

    // ====== 白名单 ======

    fun getWhiteApps(): Set<String> {
        return call(
            TX_GET_WHITE_APPS,
            parse = { reply -> jsonToStringSet(reply.readString()) }
        ) ?: emptySet()
    }

    fun addWhiteApp(pkg: String): Boolean {
        return call(
            TX_ADD_WHITE_APP,
            prepare = { it.writeString(pkg) },
            parse = { it.readBoolean() }
        ) ?: false
    }

    fun removeWhiteApp(pkg: String): Boolean {
        return call(
            TX_REMOVE_WHITE_APP,
            prepare = { it.writeString(pkg) },
            parse = { it.readBoolean() }
        ) ?: false
    }

    fun getWhiteProcesses(): Set<String> {
        return call(
            TX_GET_WHITE_PROCESSES,
            parse = { reply -> jsonToStringSet(reply.readString()) }
        ) ?: emptySet()
    }

    fun addWhiteProcess(proc: String): Boolean {
        return call(
            TX_ADD_WHITE_PROCESS,
            prepare = { it.writeString(proc) },
            parse = { it.readBoolean() }
        ) ?: false
    }

    fun removeWhiteProcess(proc: String): Boolean {
        return call(
            TX_REMOVE_WHITE_PROCESS,
            prepare = { it.writeString(proc) },
            parse = { it.readBoolean() }
        ) ?: false
    }

    fun getBlackSystemApps(): Set<String> {
        return call(
            TX_GET_BLACK_SYSTEM_APPS,
            parse = { reply -> jsonToStringSet(reply.readString()) }
        ) ?: emptySet()
    }

    fun addBlackSystemApp(pkg: String): Boolean {
        return call(
            TX_ADD_BLACK_SYSTEM_APP,
            prepare = { it.writeString(pkg) },
            parse = { it.readBoolean() }
        ) ?: false
    }

    fun removeBlackSystemApp(pkg: String): Boolean {
        return call(
            TX_REMOVE_BLACK_SYSTEM_APP,
            prepare = { it.writeString(pkg) },
            parse = { it.readBoolean() }
        ) ?: false
    }

    // ====== 进程与冻结 ======

    @Immutable
    data class ProcessInfo(
        val pid: Int, val uid: Int,
        val packageName: String, val processName: String,
        val state: Int, val isSystemApp: Boolean,
        val isWhiteListed: Boolean, val oomAdj: Int,
    )

    fun getAllProcesses(): List<ProcessInfo> {
        return call(
            TX_GET_ALL_PROCESSES,
            parse = { reply ->
                val arr = JSONArray(reply.readString() ?: "[]")
                parseProcessArray(arr)
            }
        ) ?: emptyList()
    }

    private fun parseProcessArray(arr: JSONArray): List<ProcessInfo> {
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ProcessInfo(
                pid = o.optInt("pid", -1),
                uid = o.optInt("uid", -1),
                packageName = o.optString("packageName"),
                processName = o.optString("processName"),
                state = o.optInt("state", 0),
                isSystemApp = o.optBoolean("isSystemApp", false),
                isWhiteListed = o.optBoolean("isWhiteListed", false),
                oomAdj = o.optInt("oomAdj", 0),
            )
        }
    }

    fun getFrozenCount(): Int {
        return call(
            TX_GET_FROZEN_COUNT,
            parse = { it.readInt() }
        ) ?: 0
    }

    fun freezeProcess(pid: Int, uid: Int): Boolean {
        return call(
            TX_FREEZE_PROCESS,
            prepare = { it.writeInt(pid); it.writeInt(uid) },
            parse = { it.readBoolean() }
        ) ?: false
    }

    fun unfreezeProcess(pid: Int, uid: Int): Boolean {
        return call(
            TX_UNFREEZE_PROCESS,
            prepare = { it.writeInt(pid); it.writeInt(uid) },
            parse = { it.readBoolean() }
        ) ?: false
    }

    fun getCurrentFreezerName(): String {
        return call(
            TX_GET_CURRENT_FREEZER_NAME,
            parse = { it.readString() ?: "未知" }
        ) ?: "未知"
    }

    fun pauseAll(): Boolean {
        return call(
            TX_PAUSE_ALL,
            parse = { true }
        ) ?: false
    }

    fun resumeAll(): Boolean {
        return call(
            TX_RESUME_ALL,
            parse = { true }
        ) ?: false
    }

    fun reselectFreezer(): Boolean {
        return call(
            TX_RESELECT_FREEZER,
            parse = { true }
        ) ?: false
    }

    // ====== 日志 ======

    fun readLog(maxLines: Int): String {
        return call(
            TX_READ_LOG,
            prepare = { it.writeInt(maxLines) },
            parse = { it.readString() ?: "" }
        ) ?: ""
    }

    fun clearLog(): Boolean {
        return call(
            TX_CLEAR_LOG,
            parse = { true }
        ) ?: false
    }

    // ====== 应用级配置 ======

    fun getAppConfig(packageName: String): JSONObject {
        return call(
            TX_GET_APP_CONFIG,
            prepare = { it.writeString(packageName) },
            parse = { reply ->
                JSONObject(reply.readString() ?: "{}")
            }
        ) ?: JSONObject()
    }

    fun setAppConfigItem(packageName: String, key: String, value: Boolean): Boolean {
        return call(
            TX_SET_APP_CONFIG_ITEM,
            prepare = {
                it.writeString(packageName)
                it.writeString(key)
                it.writeInt(0) // type=boolean
                it.writeBoolean(value)
            },
            parse = { it.readBoolean() }
        ) ?: false
    }

    fun setAppConfigItem(packageName: String, key: String, value: Int): Boolean {
        return call(
            TX_SET_APP_CONFIG_ITEM,
            prepare = {
                it.writeString(packageName)
                it.writeString(key)
                it.writeInt(1) // type=int
                it.writeInt(value)
            },
            parse = { it.readBoolean() }
        ) ?: false
    }

    fun setAppConfigItem(packageName: String, key: String, value: String): Boolean {
        return call(
            TX_SET_APP_CONFIG_ITEM,
            prepare = {
                it.writeString(packageName)
                it.writeString(key)
                it.writeInt(2) // type=string
                it.writeString(value)
            },
            parse = { it.readBoolean() }
        ) ?: false
    }

    // ====== 轮番解冻 ======

    fun getRotationInterval(): Int {
        return call(
            TX_GET_ROTATION_INTERVAL,
            parse = { it.readInt() }
        ) ?: 360
    }

    fun setRotationInterval(interval: Int): Boolean {
        return call(
            TX_SET_ROTATION_INTERVAL,
            prepare = { it.writeInt(interval) },
            parse = { it.readBoolean() }
        ) ?: false
    }

    // ====== OOM 优先级 ======

    fun getAppPriority(packageName: String): Int {
        return call(
            TX_GET_APP_PRIORITY,
            prepare = { it.writeString(packageName) },
            parse = { it.readInt() }
        ) ?: 1
    }

    fun setAppPriority(packageName: String, priority: Int): Boolean {
        return call(
            TX_SET_APP_PRIORITY,
            prepare = { it.writeString(packageName); it.writeInt(priority) },
            parse = { it.readBoolean() }
        ) ?: false
    }

    // ====== 批量事务（减少 IPC 往返次数）======

    /**
     * 批量获取首页初始化数据：配置 + 白名单 + 进程列表
     * 将 3 次 IPC 合并为 1 次
     */
    data class InitData(
        val config: ConfigSnapshot,
        val whiteApps: Set<String>,
        val processes: List<ProcessInfo>,
    )

    fun getInitData(): InitData? {
        return call(
            TX_GET_INIT_DATA,
            parse = { reply ->
                val json = JSONObject(reply.readString() ?: "{}")
                parseInitData(json)
            }
        )
    }

    private fun parseInitData(json: JSONObject): InitData {
        val cfg = json.optJSONObject("config") ?: JSONObject()
        val config = ConfigSnapshot(
            freezeMode = cfg.optInt("freezeMode", 4),
            freezeDelay = cfg.optInt("freezeDelay", 3),
            debugEnabled = cfg.optBoolean("debugEnabled", false),
            globalPaused = cfg.optBoolean("globalPaused", false),
            hookANR = cfg.optBoolean("hookANR", true),
            hookBroadcast = cfg.optBoolean("hookBroadcast", true),
            hookWakeLock = cfg.optBoolean("hookWakeLock", true),
            hookActivitySwitch = cfg.optBoolean("hookActivitySwitch", true),
            hookScreenState = cfg.optBoolean("hookScreenState", true),
        )
        val whiteApps = jsonArrayToStringSet(json.optJSONArray("whiteApps"))
        val processes = parseProcessArray(json.optJSONArray("processes") ?: JSONArray())
        return InitData(config, whiteApps, processes)
    }

    /**
     * 批量获取应用配置 + 优先级
     * 将 2 次 IPC 合并为 1 次
     */
    data class AppConfigFull(
        val config: JSONObject,
        val priority: Int,
    )

    fun getAppConfigFull(packageName: String): AppConfigFull? {
        return call(
            TX_GET_APP_CONFIG_FULL,
            prepare = { it.writeString(packageName) },
            parse = { reply ->
                val json = JSONObject(reply.readString() ?: "{}")
                AppConfigFull(
                    config = JSONObject(json.optString("config", "{}")),
                    priority = json.optInt("priority", 1),
                )
            }
        )
    }

    // ====== 辅助 ======

    private fun jsonToStringSet(json: String?): Set<String> {
        if (json.isNullOrEmpty()) return emptySet()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) { emptySet() }
    }

    private fun jsonArrayToStringSet(arr: JSONArray?): Set<String> {
        if (arr == null) return emptySet()
        return try {
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) { emptySet() }
    }
}
