package com.tombstonex.model

import android.util.Log

/**
 * 冻结方式枚举。
 *
 * 每个枚举常量显式指定稳定的 [id]，用于配置与 IPC 持久化/传输，
 * 避免因常量声明顺序变化（如新增/重排枚举）导致已持久化的整数失效。
 *
 * 注意：[id] 的取值与历史 ordinal 保持一致（0..4），保证向后兼容。
 *
 * 使用 Kotlin 枚举以便在 Kotlin 代码中使用现代的 [entries] 属性
 * （替代已弃用的 `values()`）；Java 侧仍可通过 `FreezeMode.values()`
 * 与各常量正常互操作。
 */
enum class FreezeMode(val id: Int) {
    SIGNAL_19(0),
    SIGNAL_20(1),
    CGROUP_V1(2),
    CGROUP_V2(3),
    SYSTEM_API(4);

    companion object {
        /**
         * M-34: 在类加载时验证所有枚举常量的 id 唯一性。
         * 如果出现重复 id，抛出 [IllegalStateException] 阻止启动，
         * 避免运行时因 id 冲突导致错误的冻结方式被选中。
         */
        init {
            val ids = entries.map { it.id }
            val duplicates = ids.groupBy { it }.filter { it.value.size > 1 }.keys
            if (duplicates.isNotEmpty()) {
                throw IllegalStateException(
                    "FreezeMode 枚举存在重复 id: $duplicates"
                )
            }
        }

        /**
         * 按 [id] 反查枚举常量。未匹配时回退到 [SYSTEM_API]。
         */
        @JvmStatic
        fun fromId(id: Int): FreezeMode {
            val mode = entries.firstOrNull { it.id == id }
            return if (mode != null) {
                mode
            } else {
                // m-7: 记录警告日志，便于排查配置错误或 IPC 数据损坏
                Log.w("TombstoneX", "FreezeMode.fromId($id): 未知 id，回退到 SYSTEM_API")
                SYSTEM_API
            }
        }
    }
}