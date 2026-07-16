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
    private var binder: android.os.IBinder? = null

    /** 模块是否已激活（服务是否已注册） */
    val isAvailable: Boolean
        get() = getBinder() != null

    /** 通过反射 ServiceManager.getService 获取 Binder 代理 */
    private fun getBinder(): android.os.IBinder? {
        val cached = binder
        if (cached != null && cached.isBinderAlive) return cached
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

    /** 执行一次 transact 调用，返回 reply Parcel */
    private fun transact(code: Int, prepare: (Parcel) -> Unit = {}): Parcel? {
        val b = getBinder() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            prepare(data)
            b.transact(code, data, reply, 0)
            reply
        } catch (e: Throwable) {
            null
        } finally {
            data.recycle()
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
        val reply = transact(TX_GET_CONFIG) ?: return null
        return try {
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
        } finally { reply.recycle() }
    }

    fun setFreezeMode(modeOrdinal: Int): Boolean {
        val reply = transact(TX_SET_FREEZE_MODE) { it.writeInt(modeOrdinal) } ?: return false
        reply.recycle(); return true
    }

    fun setFreezeDelay(delay: Int): Boolean {
        val reply = transact(TX_SET_FREEZE_DELAY) { it.writeInt(delay) } ?: return false
        reply.recycle(); return true
    }

    fun setDebugEnabled(enabled: Boolean): Boolean {
        val reply = transact(TX_SET_DEBUG_ENABLED) { it.writeBoolean(enabled) } ?: return false
        reply.recycle(); return true
    }

    /** hookId: 0=ANR, 1=Broadcast, 2=WakeLock, 3=ActivitySwitch, 4=ScreenState */
    fun setHookEnabled(hookId: Int, enabled: Boolean): Boolean {
        val reply = transact(TX_SET_HOOK_ENABLED) {
            it.writeInt(hookId); it.writeBoolean(enabled)
        } ?: return false
        reply.recycle(); return true
    }

    fun setGlobalPaused(paused: Boolean): Boolean {
        val reply = transact(TX_SET_GLOBAL_PAUSED) { it.writeBoolean(paused) } ?: return false
        reply.recycle(); return true
    }

    fun isGlobalPaused(): Boolean {
        val reply = transact(TX_IS_GLOBAL_PAUSED) ?: return false
        return try { reply.readBoolean() } finally { reply.recycle() }
    }

    // ====== 白名单 ======

    fun getWhiteApps(): Set<String> {
        val reply = transact(TX_GET_WHITE_APPS) ?: return emptySet()
        return try { jsonToStringSet(reply.readString()) } finally { reply.recycle() }
    }

    fun addWhiteApp(pkg: String): Boolean {
        val reply = transact(TX_ADD_WHITE_APP) { it.writeString(pkg) } ?: return false
        reply.recycle(); return true
    }

    fun removeWhiteApp(pkg: String): Boolean {
        val reply = transact(TX_REMOVE_WHITE_APP) { it.writeString(pkg) } ?: return false
        reply.recycle(); return true
    }

    fun getWhiteProcesses(): Set<String> {
        val reply = transact(TX_GET_WHITE_PROCESSES) ?: return emptySet()
        return try { jsonToStringSet(reply.readString()) } finally { reply.recycle() }
    }

    fun addWhiteProcess(proc: String): Boolean {
        val reply = transact(TX_ADD_WHITE_PROCESS) { it.writeString(proc) } ?: return false
        reply.recycle(); return true
    }

    fun removeWhiteProcess(proc: String): Boolean {
        val reply = transact(TX_REMOVE_WHITE_PROCESS) { it.writeString(proc) } ?: return false
        reply.recycle(); return true
    }

    fun getBlackSystemApps(): Set<String> {
        val reply = transact(TX_GET_BLACK_SYSTEM_APPS) ?: return emptySet()
        return try { jsonToStringSet(reply.readString()) } finally { reply.recycle() }
    }

    fun addBlackSystemApp(pkg: String): Boolean {
        val reply = transact(TX_ADD_BLACK_SYSTEM_APP) { it.writeString(pkg) } ?: return false
        reply.recycle(); return true
    }

    fun removeBlackSystemApp(pkg: String): Boolean {
        val reply = transact(TX_REMOVE_BLACK_SYSTEM_APP) { it.writeString(pkg) } ?: return false
        reply.recycle(); return true
    }

    // ====== 进程与冻结 ======

    data class ProcessInfo(
        val pid: Int, val uid: Int,
        val packageName: String, val processName: String,
        val state: Int, val isSystemApp: Boolean,
        val isWhiteListed: Boolean, val oomAdj: Int,
    )

    fun getAllProcesses(): List<ProcessInfo> {
        val reply = transact(TX_GET_ALL_PROCESSES) ?: return emptyList()
        return try {
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
        } finally { reply.recycle() }
    }

    fun getFrozenCount(): Int {
        val reply = transact(TX_GET_FROZEN_COUNT) ?: return 0
        return try { reply.readInt() } finally { reply.recycle() }
    }

    fun freezeProcess(pid: Int, uid: Int): Boolean {
        val reply = transact(TX_FREEZE_PROCESS) {
            it.writeInt(pid); it.writeInt(uid)
        } ?: return false
        return try { reply.readBoolean() } finally { reply.recycle() }
    }

    fun unfreezeProcess(pid: Int, uid: Int): Boolean {
        val reply = transact(TX_UNFREEZE_PROCESS) {
            it.writeInt(pid); it.writeInt(uid)
        } ?: return false
        return try { reply.readBoolean() } finally { reply.recycle() }
    }

    fun getCurrentFreezerName(): String {
        val reply = transact(TX_GET_CURRENT_FREEZER_NAME) ?: return "未知"
        return try { reply.readString() ?: "未知" } finally { reply.recycle() }
    }

    fun pauseAll(): Boolean {
        val reply = transact(TX_PAUSE_ALL) ?: return false
        reply.recycle(); return true
    }

    fun resumeAll(): Boolean {
        val reply = transact(TX_RESUME_ALL) ?: return false
        reply.recycle(); return true
    }

    fun reselectFreezer(): Boolean {
        val reply = transact(TX_RESELECT_FREEZER) ?: return false
        reply.recycle(); return true
    }

    // ====== 日志 ======

    fun readLog(maxLines: Int): String {
        val reply = transact(TX_READ_LOG) { it.writeInt(maxLines) } ?: return ""
        return try { reply.readString() ?: "" } finally { reply.recycle() }
    }

    fun clearLog(): Boolean {
        val reply = transact(TX_CLEAR_LOG) ?: return false
        reply.recycle(); return true
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
