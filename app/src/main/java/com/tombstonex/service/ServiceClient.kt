package com.tombstonex.service

import android.os.Parcel
import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * UI 侧客户端，通过两种方式与 system_server 中的 TombstoneXService 通信：
 *
 * 1. Binder（首选）：通过反射 ServiceManager.getService("tombstonex") 获取 Binder 代理
 * 2. FileIPC（降级）：当 SELinux 阻止 Binder 注册时，通过 /data/system/TombstoneX/ 下的
 *    文件系统进行通信，App 端通过 su 执行文件读写
 *
 * 所有方法在 IO 线程调用（非主线程），返回 null/默认值表示服务不可用。
 */
object ServiceClient {

    private const val SERVICE_NAME = "tombstonex"
    private const val DESCRIPTOR = "com.tombstonex.service.TombstoneXService"

    // FileIPC 文件路径
    private const val IPC_DIR = "/data/system/TombstoneX"
    private const val CMD_FILE = "$IPC_DIR/cmd.json"
    private const val RESP_FILE = "$IPC_DIR/resp.json"
    private const val READY_FILE = "$IPC_DIR/ipc_ready"

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
    private const val TX_SET_APP_CONFIG = 28
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

    /** 缓存的 IPC 模式：true=Binder，false=FileIPC */
    @Volatile
    private var useBinder: Boolean = true

    /** 缓存 FileIPC 就绪状态，避免每次调用都 spawn su */
    @Volatile
    private var fileIpcReadyCache: Boolean? = null

    /**
     * 模块是否已激活（Binder 或 FileIPC 任一可用即视为激活）
     */
    val isAvailable: Boolean
        get() = getBinder() != null || isFileIPCReady()

    /**
     * FileIPC 是否就绪（通过 su 检查，App 进程无权直接访问 /data/system/）
     * 结果缓存 5 秒避免重复 spawn su
     */
    private fun isFileIPCReady(): Boolean {
        // 仅缓存 true（就绪后不会变回 false），false 不缓存以便重试
        if (fileIpcReadyCache == true) return true
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "test -f $READY_FILE && echo 1 || echo 0"))
            val output = process.inputStream.bufferedReader().use { it.readLine() ?: "0" }
            process.waitFor()
            val ready = output.trim() == "1"
            if (ready) fileIpcReadyCache = true
            ready
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 获取 Binder 服务注册失败的诊断信息。
     */
    val regStatus: String
        get() = try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getMethod("get", String::class.java)
            getMethod.invoke(null, "persist.sys.tombstonex.regstatus") as? String ?: ""
        } catch (e: Throwable) {
            ""
        }

    /**
     * 模块是否已被 LSPosed 启用
     */
    val isModuleEnabled: Boolean
        get() = try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getMethod("get", String::class.java)
            val value = getMethod.invoke(null, "persist.sys.tombstonex.loaded") as? String
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
            val value = getMethod.invoke(null, "persist.sys.tombstonex.active") as? String
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
     * 通过 FileIPC 发送命令（降级方案）
     * 使用 su 写入命令文件，等待 system_server 的 FileObserver 处理后读取响应
     * 
     * 性能优化：用单个 su 命令完成"等待+读取"，避免轮询时反复 spawn su 进程
     */
    private fun fileTransact(code: Int, args: JSONObject = JSONObject()): JSONObject? {
        if (!isFileIPCReady()) return null

        val cmd = JSONObject()
        cmd.put("code", code)
        cmd.put("args", args)
        val cmdContent = cmd.toString()

        return try {
            // 1. 通过 su + stdin 写入命令文件（1 次 su spawn）
            val writeProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat > $CMD_FILE"))
            writeProcess.outputStream.use { os ->
                os.write(cmdContent.toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }
            writeProcess.waitFor()

            // 2. 单个 su 命令完成"轮询等待 + 读取响应"（1 次 su spawn，替代原来 60+ 次）
            // 脚本逻辑：记录当前 resp.json 修改时间，循环等待直到文件被更新，然后读取内容
            val waitAndReadCmd = "st=\$(stat -c %Y $RESP_FILE 2>/dev/null || echo 0); " +
                "i=0; while [ \$i -lt 100 ]; do " +
                "cur=\$(stat -c %Y $RESP_FILE 2>/dev/null || echo 0); " +
                "if [ \"\$cur\" -gt \"\$st\" ]; then sleep 0.03; cat $RESP_FILE; exit 0; fi; " +
                "sleep 0.03; i=\$((i+1)); " +
                "done; cat $RESP_FILE"
            val waitProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", waitAndReadCmd))
            val respContent = waitProcess.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            waitProcess.waitFor()

            if (respContent.trim().isEmpty()) {
                android.util.Log.e("TombstoneX", "FileIPC: empty response, code=$code")
                return null
            }

            val resp = JSONObject(respContent)
            if (!resp.optBoolean("ok", false)) {
                val error = resp.optString("error", "unknown error")
                android.util.Log.e("TombstoneX", "FileIPC command $code failed: $error")
                return null
            }
            resp
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            android.util.Log.e("TombstoneX", "FileIPC.transact failed: ${e.message}", e)
            null
        }
    }

    /**
     * 统一调用：优先使用 Binder，失败时降级到 FileIPC
     */
    private fun <T> call(
        code: Int,
        binderPrepare: (Parcel) -> Unit = {},
        binderParse: (Parcel) -> T?,
        fileArgs: JSONObject = JSONObject(),
        fileParse: (JSONObject) -> T?
    ): T? {
        // 优先使用 Binder
        if (useBinder) {
            val result = transact(code, binderPrepare, binderParse)
            if (result != null) return result
            // Binder 失败，切换到 FileIPC
            useBinder = false
        }

        // 降级到 FileIPC
        val resp = fileTransact(code, fileArgs) ?: return null
        return fileParse(resp)
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
            binderParse = { reply ->
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
            },
            fileParse = { resp ->
                val json = resp.optJSONObject("data") ?: JSONObject()
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

    fun setFreezeMode(modeOrdinal: Int): Boolean {
        return call(
            TX_SET_FREEZE_MODE,
            binderPrepare = { it.writeInt(modeOrdinal) },
            binderParse = { it.readBoolean() },
            fileArgs = JSONObject().put("mode", modeOrdinal),
            fileParse = { resp -> resp.opt("data") as? Boolean ?: false }
        ) ?: false
    }

    fun setFreezeDelay(delay: Int): Boolean {
        return call(
            TX_SET_FREEZE_DELAY,
            binderPrepare = { it.writeInt(delay) },
            binderParse = { true },
            fileArgs = JSONObject().put("delay", delay),
            fileParse = { resp -> resp.opt("data") as? Boolean ?: true }
        ) ?: false
    }

    fun setDebugEnabled(enabled: Boolean): Boolean {
        return call(
            TX_SET_DEBUG_ENABLED,
            binderPrepare = { it.writeBoolean(enabled) },
            binderParse = { true },
            fileArgs = JSONObject().put("enabled", enabled),
            fileParse = { resp -> resp.opt("data") as? Boolean ?: true }
        ) ?: false
    }

    /** hookId: 0=ANR, 1=Broadcast, 2=WakeLock, 3=ActivitySwitch, 4=ScreenState */
    fun setHookEnabled(hookId: Int, enabled: Boolean): Boolean {
        return call(
            TX_SET_HOOK_ENABLED,
            binderPrepare = { it.writeInt(hookId); it.writeBoolean(enabled) },
            binderParse = { it.readBoolean() },
            fileArgs = JSONObject().put("hookId", hookId).put("enabled", enabled),
            fileParse = { resp -> resp.opt("data") as? Boolean ?: false }
        ) ?: false
    }

    fun setGlobalPaused(paused: Boolean): Boolean {
        return call(
            TX_SET_GLOBAL_PAUSED,
            binderPrepare = { it.writeBoolean(paused) },
            binderParse = { true },
            fileArgs = JSONObject().put("paused", paused),
            fileParse = { resp -> resp.opt("data") as? Boolean ?: true }
        ) ?: false
    }

    fun isGlobalPaused(): Boolean {
        return call(
            TX_IS_GLOBAL_PAUSED,
            binderParse = { it.readBoolean() },
            fileParse = { resp -> resp.opt("data") as? Boolean ?: false }
        ) ?: false
    }

    // ====== 白名单 ======

    fun getWhiteApps(): Set<String> {
        return call(
            TX_GET_WHITE_APPS,
            binderParse = { reply -> jsonToStringSet(reply.readString()) },
            fileParse = { resp ->
                val arr = resp.optJSONArray("data")
                jsonArrayToStringSet(arr)
            }
        ) ?: emptySet()
    }

    fun addWhiteApp(pkg: String): Boolean {
        return call(
            TX_ADD_WHITE_APP,
            binderPrepare = { it.writeString(pkg) },
            binderParse = { it.readBoolean() },
            fileArgs = JSONObject().put("pkg", pkg),
            fileParse = { resp -> resp.opt("data") as? Boolean ?: false }
        ) ?: false
    }

    fun removeWhiteApp(pkg: String): Boolean {
        return call(
            TX_REMOVE_WHITE_APP,
            binderPrepare = { it.writeString(pkg) },
            binderParse = { it.readBoolean() },
            fileArgs = JSONObject().put("pkg", pkg),
            fileParse = { resp -> resp.opt("data") as? Boolean ?: false }
        ) ?: false
    }

    fun getWhiteProcesses(): Set<String> {
        return call(
            TX_GET_WHITE_PROCESSES,
            binderParse = { reply -> jsonToStringSet(reply.readString()) },
            fileParse = { resp ->
                val arr = resp.optJSONArray("data")
                jsonArrayToStringSet(arr)
            }
        ) ?: emptySet()
    }

    fun addWhiteProcess(proc: String): Boolean {
        return call(
            TX_ADD_WHITE_PROCESS,
            binderPrepare = { it.writeString(proc) },
            binderParse = { it.readBoolean() },
            fileArgs = JSONObject().put("proc", proc),
            fileParse = { resp -> resp.opt("data") as? Boolean ?: false }
        ) ?: false
    }

    fun removeWhiteProcess(proc: String): Boolean {
        return call(
            TX_REMOVE_WHITE_PROCESS,
            binderPrepare = { it.writeString(proc) },
            binderParse = { it.readBoolean() },
            fileArgs = JSONObject().put("proc", proc),
            fileParse = { resp -> resp.opt("data") as? Boolean ?: false }
        ) ?: false
    }

    fun getBlackSystemApps(): Set<String> {
        return call(
            TX_GET_BLACK_SYSTEM_APPS,
            binderParse = { reply -> jsonToStringSet(reply.readString()) },
            fileParse = { resp ->
                val arr = resp.optJSONArray("data")
                jsonArrayToStringSet(arr)
            }
        ) ?: emptySet()
    }

    fun addBlackSystemApp(pkg: String): Boolean {
        return call(
            TX_ADD_BLACK_SYSTEM_APP,
            binderPrepare = { it.writeString(pkg) },
            binderParse = { it.readBoolean() },
            fileArgs = JSONObject().put("pkg", pkg),
            fileParse = { resp -> resp.opt("data") as? Boolean ?: false }
        ) ?: false
    }

    fun removeBlackSystemApp(pkg: String): Boolean {
        return call(
            TX_REMOVE_BLACK_SYSTEM_APP,
            binderPrepare = { it.writeString(pkg) },
            binderParse = { it.readBoolean() },
            fileArgs = JSONObject().put("pkg", pkg),
            fileParse = { resp -> resp.opt("data") as? Boolean ?: false }
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
            binderParse = { reply ->
                val arr = JSONArray(reply.readString() ?: "[]")
                parseProcessArray(arr)
            },
            fileParse = { resp ->
                val arr = resp.optJSONArray("data") ?: JSONArray()
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
            binderParse = { it.readInt() },
            fileParse = { resp -> resp.optInt("data", 0) }
        ) ?: 0
    }

    fun freezeProcess(pid: Int, uid: Int): Boolean {
        return call(
            TX_FREEZE_PROCESS,
            binderPrepare = { it.writeInt(pid); it.writeInt(uid) },
            binderParse = { it.readBoolean() },
            fileArgs = JSONObject().put("pid", pid).put("uid", uid),
            fileParse = { resp -> resp.opt("data") as? Boolean ?: false }
        ) ?: false
    }

    fun unfreezeProcess(pid: Int, uid: Int): Boolean {
        return call(
            TX_UNFREEZE_PROCESS,
            binderPrepare = { it.writeInt(pid); it.writeInt(uid) },
            binderParse = { it.readBoolean() },
            fileArgs = JSONObject().put("pid", pid).put("uid", uid),
            fileParse = { resp -> resp.opt("data") as? Boolean ?: false }
        ) ?: false
    }

    fun getCurrentFreezerName(): String {
        return call(
            TX_GET_CURRENT_FREEZER_NAME,
            binderParse = { it.readString() ?: "未知" },
            fileParse = { resp -> resp.optString("data", "未知") }
        ) ?: "未知"
    }

    fun pauseAll(): Boolean {
        return call(
            TX_PAUSE_ALL,
            binderParse = { true },
            fileParse = { resp -> resp.opt("data") as? Boolean ?: true }
        ) ?: false
    }

    fun resumeAll(): Boolean {
        return call(
            TX_RESUME_ALL,
            binderParse = { true },
            fileParse = { resp -> resp.opt("data") as? Boolean ?: true }
        ) ?: false
    }

    fun reselectFreezer(): Boolean {
        return call(
            TX_RESELECT_FREEZER,
            binderParse = { true },
            fileParse = { resp -> resp.opt("data") as? Boolean ?: true }
        ) ?: false
    }

    // ====== 日志 ======

    fun readLog(maxLines: Int): String {
        return call(
            TX_READ_LOG,
            binderPrepare = { it.writeInt(maxLines) },
            binderParse = { it.readString() ?: "" },
            fileArgs = JSONObject().put("maxLines", maxLines),
            fileParse = { resp -> resp.optString("data", "") }
        ) ?: ""
    }

    fun clearLog(): Boolean {
        return call(
            TX_CLEAR_LOG,
            binderParse = { true },
            fileParse = { resp -> resp.opt("data") as? Boolean ?: true }
        ) ?: false
    }

    // ====== 应用级配置 ======

    fun getAppConfig(packageName: String): JSONObject {
        return call(
            TX_GET_APP_CONFIG,
            binderPrepare = { it.writeString(packageName) },
            binderParse = { reply ->
                JSONObject(reply.readString() ?: "{}")
            },
            fileArgs = JSONObject().put("pkg", packageName),
            fileParse = { resp ->
                JSONObject(resp.optString("data", "{}"))
            }
        ) ?: JSONObject()
    }

    fun setAppConfigItem(packageName: String, key: String, value: Boolean): Boolean {
        return call(
            TX_SET_APP_CONFIG_ITEM,
            binderPrepare = {
                it.writeString(packageName)
                it.writeString(key)
                it.writeInt(0) // type=boolean
                it.writeBoolean(value)
            },
            binderParse = { it.readBoolean() },
            fileArgs = JSONObject()
                .put("pkg", packageName)
                .put("key", key)
                .put("type", "boolean")
                .put("value", value),
            fileParse = { resp -> resp.opt("data") as? Boolean ?: false }
        ) ?: false
    }

    fun setAppConfigItem(packageName: String, key: String, value: Int): Boolean {
        return call(
            TX_SET_APP_CONFIG_ITEM,
            binderPrepare = {
                it.writeString(packageName)
                it.writeString(key)
                it.writeInt(1) // type=int
                it.writeInt(value)
            },
            binderParse = { it.readBoolean() },
            fileArgs = JSONObject()
                .put("pkg", packageName)
                .put("key", key)
                .put("type", "int")
                .put("value", value),
            fileParse = { resp -> resp.opt("data") as? Boolean ?: false }
        ) ?: false
    }

    fun setAppConfigItem(packageName: String, key: String, value: String): Boolean {
        return call(
            TX_SET_APP_CONFIG_ITEM,
            binderPrepare = {
                it.writeString(packageName)
                it.writeString(key)
                it.writeInt(2) // type=string
                it.writeString(value)
            },
            binderParse = { it.readBoolean() },
            fileArgs = JSONObject()
                .put("pkg", packageName)
                .put("key", key)
                .put("type", "string")
                .put("value", value),
            fileParse = { resp -> resp.opt("data") as? Boolean ?: false }
        ) ?: false
    }

    // ====== 轮番解冻 ======

    fun getRotationInterval(): Int {
        return call(
            TX_GET_ROTATION_INTERVAL,
            binderParse = { it.readInt() },
            fileParse = { resp -> resp.optInt("data", 360) }
        ) ?: 360
    }

    fun setRotationInterval(interval: Int): Boolean {
        return call(
            TX_SET_ROTATION_INTERVAL,
            binderPrepare = { it.writeInt(interval) },
            binderParse = { it.readBoolean() },
            fileArgs = JSONObject().put("interval", interval),
            fileParse = { resp -> resp.opt("data") as? Boolean ?: true }
        ) ?: false
    }

    // ====== OOM 优先级 ======

    fun getAppPriority(packageName: String): Int {
        return call(
            TX_GET_APP_PRIORITY,
            binderPrepare = { it.writeString(packageName) },
            binderParse = { it.readInt() },
            fileArgs = JSONObject().put("pkg", packageName),
            fileParse = { resp -> resp.optInt("data", 1) }
        ) ?: 1
    }

    fun setAppPriority(packageName: String, priority: Int): Boolean {
        return call(
            TX_SET_APP_PRIORITY,
            binderPrepare = { it.writeString(packageName); it.writeInt(priority) },
            binderParse = { it.readBoolean() },
            fileArgs = JSONObject().put("pkg", packageName).put("priority", priority),
            fileParse = { resp -> resp.opt("data") as? Boolean ?: false }
        ) ?: false
    }

    // ====== 批量事务（减少 IPC 往返次数）======

    /**
     * 批量获取首页初始化数据：配置 + 白名单 + 进程列表
     * 将 3 次 IPC 合并为 1 次，大幅减少 su 进程启动开销
     */
    data class InitData(
        val config: ConfigSnapshot,
        val whiteApps: Set<String>,
        val processes: List<ProcessInfo>,
    )

    fun getInitData(): InitData? {
        return call(
            TX_GET_INIT_DATA,
            binderParse = { reply ->
                val json = JSONObject(reply.readString() ?: "{}")
                parseInitData(json)
            },
            fileParse = { resp ->
                val json = resp.optJSONObject("data") ?: JSONObject()
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
            binderPrepare = { it.writeString(packageName) },
            binderParse = { reply ->
                val json = JSONObject(reply.readString() ?: "{}")
                AppConfigFull(
                    config = JSONObject(json.optString("config", "{}")),
                    priority = json.optInt("priority", 1),
                )
            },
            fileArgs = JSONObject().put("pkg", packageName),
            fileParse = { resp ->
                val json = resp.optJSONObject("data") ?: JSONObject()
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
