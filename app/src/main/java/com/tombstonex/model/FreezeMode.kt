package com.tombstonex.model

/**
 * 冻结方式枚举。
 *
 * 注意：保持常量声明顺序与历史 [ordinal] 一致，因为配置文件与 IPC
 * 均以 [Enum.ordinal] 整数持久化/传输。
 *
 * 使用 Kotlin 枚举以便在 Kotlin 代码中使用现代的 [entries] 属性
 * （替代已弃用的 `values()`）；Java 侧仍可通过 `FreezeMode.values()`
 * 与各常量正常互操作。
 */
enum class FreezeMode {
    SIGNAL_19,
    SIGNAL_20,
    CGROUP_V1,
    CGROUP_V2,
    SYSTEM_API
}
