package com.tombstonex.service

import android.os.Parcel
import org.json.JSONArray
import org.json.JSONObject

/**
 * UI 侧客户端，通过反射 ServiceManager.getService("tombstonex") 与 system_server 中的
 * TombstoneXService 通信。解决 App 进程无权限访问 /data/system/ 的问题。
 *
 * 所有方法在 IO 线程调用（非主线程），返回 null/默认值表示服务不可用。
 */
object ServiceClient {

    private const val SERVICE_NAME = "tombstonex"

    /** Binder 接口描述符，必须与 TombstoneXService.java 中的 DESCRIPTOR 一致 */
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

    /** 缓存的 IBinder 代理 */
    @Volatile
    private var binder: android.os.IBinder? = null

    /** 模块是否已激活（服务是否已注册） */
    val isAvailable: Boolean
        get() = getBinder() != null

    /** 通过反射 ServiceManager.getService 获取 Binder 代理 */
    private fun getBinder(): android.os.IBinder? {
        val cached = binder
        if (cached != null) {
            if (cached.isBinderAlive) return cached
            // P3-03: binder 已死亡，清除缓存的死引用，避免持有无用对象，
            // 下面的逻辑会重新通过 ServiceManager.getService 查找
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
     * 执行一次 transact 调用，自动处理 Parcel 回收和异常检查。
     * 服务端必须以 writeNoException() 开头写入 reply，此方法会先调用 readException() 消费标记，
     * 再通过 parse 回调解析实际数据。
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
            // P1-05: 不再静默吞掉 Binder 异常
            // 协程取消异常必须重新抛出，避免破坏协程结构化并发
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("TombstoneX", "ServiceClient.transact failed: ${e.message}")
            // DeadObjectException 表示服务端进程已死，清空缓存的 binder 引用，
            // 下次调用时会重新通过 ServiceManager.getService 查找
            if (e is android.os.DeadObjectException) {
                binder = null
            }
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // ====== 配置 ======

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
        return transact(TX_GET_CONFIG) { reply ->
            val json = JSONObject(reply.readString() ?: "{}")
            ConfigSnapshot(
                freezeMode = json.optInt("freezeMode", 0),
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
    }

    fun setFreezeMode(modeOrdinal: Int): Boolean {
        return transact(TX_SET_FREEZE_MODE, { it.writeInt(modeOrdinal) }, { it.readBoolean() }) ?: false
    }

    fun setFreezeDelay(delay: Int): Boolean {
        return transact(TX_SET_FREEZE_DELAY, { it.writeInt(delay) }, { true }) ?: false
    }

    fun setDebugEnabled(enabled: Boolean): Boolean {
        return transact(TX_SET_DEBUG_ENABLED, { it.writeBoolean(enabled) }, { true }) ?: false
    }

    /** hookId: 0=ANR, 1=Broadcast, 2=WakeLock, 3=ActivitySwitch, 4=ScreenState */
    fun setHookEnabled(hookId: Int, enabled: Boolean): Boolean {
        return transact(TX_SET_HOOK_ENABLED, {
            it.writeInt(hookId); it.writeBoolean(enabled)
        }, { it.readBoolean() }) ?: false
    }

    fun setGlobalPaused(paused: Boolean): Boolean {
        return transact(TX_SET_GLOBAL_PAUSED, { it.writeBoolean(paused) }, { true }) ?: false
    }

    fun isGlobalPaused(): Boolean {
        return transact(TX_IS_GLOBAL_PAUSED, {}, { it.readBoolean() }) ?: false
    }

    // ====== 白名单 ======

    fun getWhiteApps(): Set<String> {
        return transact(TX_GET_WHITE_APPS, {}, { reply -> jsonToStringSet(reply.readString()) }) ?: emptySet()
    }

    fun addWhiteApp(pkg: String): Boolean {
        return transact(TX_ADD_WHITE_APP, { it.writeString(pkg) }, { true }) ?: false
    }

    fun removeWhiteApp(pkg: String): Boolean {
        return transact(TX_REMOVE_WHITE_APP, { it.writeString(pkg) }, { true }) ?: false
    }

    fun getWhiteProcesses(): Set<String> {
        return transact(TX_GET_WHITE_PROCESSES, {}, { reply -> jsonToStringSet(reply.readString()) }) ?: emptySet()
    }

    fun addWhiteProcess(proc: String): Boolean {
        return transact(TX_ADD_WHITE_PROCESS, { it.writeString(proc) }, { true }) ?: false
    }

    fun removeWhiteProcess(proc: String): Boolean {
        return transact(TX_REMOVE_WHITE_PROCESS, { it.writeString(proc) }, { true }) ?: false
    }

    fun getBlackSystemApps(): Set<String> {
        return transact(TX_GET_BLACK_SYSTEM_APPS, {}, { reply -> jsonToStringSet(reply.readString()) }) ?: emptySet()
    }

    fun addBlackSystemApp(pkg: String): Boolean {
        return transact(TX_ADD_BLACK_SYSTEM_APP, { it.writeString(pkg) }, { true }) ?: false
    }

    fun removeBlackSystemApp(pkg: String): Boolean {
        return transact(TX_REMOVE_BLACK_SYSTEM_APP, { it.writeString(pkg) }, { true }) ?: false
    }

    // ====== 进程与冻结 ======

    data class ProcessInfo(
        val pid: Int, val uid: Int,
        val packageName: String, val processName: String,
        val state: Int, val isSystemApp: Boolean,
        val isWhiteListed: Boolean, val oomAdj: Int,
    )

    fun getAllProcesses(): List<ProcessInfo> {
        return transact(TX_GET_ALL_PROCESSES, {}, { reply ->
            val arr = JSONArray(reply.readString() ?: "[]")
            (0 until arr.length()).map { i ->
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
        }) ?: emptyList()
    }

    fun getFrozenCount(): Int {
        return transact(TX_GET_FROZEN_COUNT, {}, { it.readInt() }) ?: 0
    }

    fun freezeProcess(pid: Int, uid: Int): Boolean {
        return transact(TX_FREEZE_PROCESS, {
            it.writeInt(pid); it.writeInt(uid)
        }, { it.readBoolean() }) ?: false
    }

    fun unfreezeProcess(pid: Int, uid: Int): Boolean {
        return transact(TX_UNFREEZE_PROCESS, {
            it.writeInt(pid); it.writeInt(uid)
        }, { it.readBoolean() }) ?: false
    }

    fun getCurrentFreezerName(): String {
        return transact(TX_GET_CURRENT_FREEZER_NAME, {}, { it.readString() ?: "未知" }) ?: "未知"
    }

    fun pauseAll(): Boolean {
        return transact(TX_PAUSE_ALL, {}, { true }) ?: false
    }

    fun resumeAll(): Boolean {
        return transact(TX_RESUME_ALL, {}, { true }) ?: false
    }

    fun reselectFreezer(): Boolean {
        return transact(TX_RESELECT_FREEZER, {}, { true }) ?: false
    }

    // ====== 日志 ======

    fun readLog(maxLines: Int): String {
        return transact(TX_READ_LOG, { it.writeInt(maxLines) }, { it.readString() ?: "" }) ?: ""
    }

    fun clearLog(): Boolean {
        return transact(TX_CLEAR_LOG, {}, { true }) ?: false
    }

    // ====== 辅助 ======

    private fun jsonToStringSet(json: String?): Set<String> {
        if (json.isNullOrEmpty()) return emptySet()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) { emptySet() }
    }
}
