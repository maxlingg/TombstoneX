package com.tombstonex.ui

import kotlinx.coroutines.CancellationException

/**
 * 安全的 runCatching：不吞没 CancellationException，保持协程取消传播。
 *
 * 标准库的 [kotlin.runCatching] 会捕获所有 [Throwable]（包括 [CancellationException]），
 * 这会破坏协程的取消传播语义。在协程上下文中应使用本函数替代。
 *
 * 用法与 [kotlin.runCatching] 完全一致，返回 [Result]：
 * ```
 * val value = safeRunCatching { ServiceClient.isAvailable }.getOrDefault(false)
 * ```
 */
inline fun <T> safeRunCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
