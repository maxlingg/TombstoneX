package com.tombstonex.util

import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 通过 root 权限手动安装 SELinux 策略模块。
 *
 * 绕过 KernelSU/Magisk Manager 的模块安装器（某些版本会闪退），
 * 直接用 root 创建 /data/adb/modules/tombstonex/ 目录结构。
 *
 * 重启后 Magisk/KernelSU 会自动读取 sepolicy.rule 并注入内核 SELinux 策略，
 * 时机早于 system_server 启动，确保 addService 调用时规则已生效。
 */
object RootModuleInstaller {

    private const val TAG = "TombstoneX-Installer"
    private const val MODULE_DIR = "/data/adb/modules/tombstonex"

    /** 进程等待超时时间（秒） */
    private const val PROCESS_TIMEOUT_SECONDS = 30L

    /** 进程执行结果：退出码 + stdout + stderr */
    private data class ProcResult(val exitCode: Int, val stdout: String, val stderr: String)

    /**
     * 等待进程结束，并行消费 stdout/stderr 避免管道缓冲区满导致死锁。
     *
     * 严重-1 修复：原实现顺序读取两路流（先 stdout 再 stderr），readText() 会阻塞到 stdout EOF，
     * 若子进程 stderr 输出填满管道缓冲区（通常 64KB）会与 stdout 互锁，导致进程挂起。
     * 且原超时在 readText() 之后，对死锁无效。
     *
     * 现改为用两个线程并行消费 stdout 和 stderr，超时 waitFor 与流读取并发进行，
     * 超时后强制销毁进程并中断流读取线程。
     *
     * 中等-1 修复：
     * (1) 两个 reader 线程的 lambda 包裹 try-catch(IOException)，进程被 destroyForcibly
     *     时管道关闭抛出的 IOException 静默处理，避免线程未捕获异常导致日志噪音。
     * (2) 将 StringBuilder 改为 StringBuffer（线程安全），避免 join 超时后 builder
     *     仍在被读取线程写入时的数据竞争。
     * (3) join 超时从 1000ms 提升至 5000ms，给足时间让管道关闭后线程自然退出，
     *     减少输出截断的概率。
     *
     * @return 进程执行结果
     */
    private fun waitForProcess(process: Process): ProcResult {
        val stdoutBuilder = StringBuffer()
        val stderrBuilder = StringBuffer()

        val stdoutThread = Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        stdoutBuilder.append(line).append('\n')
                    }
                }
            } catch (e: IOException) {
                // 进程被 destroyForcibly 时管道关闭，静默处理
            }
        }
        val stderrThread = Thread {
            try {
                process.errorStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        stderrBuilder.append(line).append('\n')
                    }
                }
            } catch (e: IOException) {
                // 进程被 destroyForcibly 时管道关闭，静默处理
            }
        }

        stdoutThread.start()
        stderrThread.start()

        val exitCode = if (process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.exitValue()
        } else {
            process.destroyForcibly()
            -1
        }

        // 等待流读取线程结束（最多 5 秒），避免输出截断
        stdoutThread.join(5000)
        stderrThread.join(5000)

        return ProcResult(exitCode, stdoutBuilder.toString(), stderrBuilder.toString())
    }

    /** SELinux 规则内容 */
    private val SEPOLICY_RULE = """
        allow system_server servicemanager binder call
        allow system_server servicemanager binder transfer
        allow system_server * service_manager add
        allow system_server * service_manager find
        allow untrusted_app servicemanager binder call
        allow untrusted_app * service_manager find
        allow untrusted_app system_server binder call
        allow untrusted_app system_server binder transfer
        allow priv_app * service_manager find
        allow priv_app system_server binder call
        allow priv_app system_server binder transfer
    """.trimIndent()

    /** 系统属性内容 */
    private val SYSTEM_PROP = "persist.sys.cached_apps_freezer=disabled\n"

    /** module.prop 内容 */
    private val MODULE_PROP = """
        id=tombstonex
        name=TombstoneX Helper
        version=v1.3.0
        versionCode=4
        author=TombstoneX
        description=SELinux policy helper for TombstoneX
    """.trimIndent() + "\n"

    /** post-fs-data.sh 内容 */
    private val POST_FS_DATA = """
        #!/system/bin/sh
        MODDIR=${'$'}{0%/*}
        mkdir -p /data/system/TombstoneX
        echo "1" > /data/system/TombstoneX/selinux_injected
    """.trimIndent() + "\n"

    /**
     * 检查 root 是否可用
     */
    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val result = waitForProcess(process)
            result.stdout.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查模块是否已安装
     */
    fun isModuleInstalled(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "test -f $MODULE_DIR/module.prop && echo 1 || echo 0"))
            val result = waitForProcess(process)
            result.stdout.trim() == "1"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查模块是否被禁用（disable 文件存在）
     */
    fun isModuleDisabled(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "test -f $MODULE_DIR/disable && echo 1 || echo 0"))
            val result = waitForProcess(process)
            result.stdout.trim() == "1"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 安装模块（通过 root 手动创建文件）
     * 安装后会立即尝试注入 SELinux 规则（如果当前环境支持），
     * 这样无需重启就能生效；如果不支持，则写入模块文件，重启后由 Magisk/KernelSU 自动加载。
     * @return 安装结果
     */
    fun install(): InstallResult {
        if (!isRootAvailable()) {
            return InstallResult(false, "未获取到 root 权限，请确保设备已 root")
        }

        return try {
            // 1. 创建模块目录
            execRoot("mkdir -p $MODULE_DIR")

            // 2. 写入 module.prop
            writeFile("$MODULE_DIR/module.prop", MODULE_PROP)

            // 3. 写入 sepolicy.rule
            writeFile("$MODULE_DIR/sepolicy.rule", SEPOLICY_RULE)

            // 4. 写入 system.prop
            writeFile("$MODULE_DIR/system.prop", SYSTEM_PROP)

            // 5. 写入 post-fs-data.sh
            writeFile("$MODULE_DIR/post-fs-data.sh", POST_FS_DATA)

            // 6. 设置权限
            execRoot("chmod 644 $MODULE_DIR/module.prop $MODULE_DIR/sepolicy.rule $MODULE_DIR/system.prop")
            execRoot("chmod 755 $MODULE_DIR/post-fs-data.sh")

            // 7. 移除 disable 标记（如果存在）
            execRoot("rm -f $MODULE_DIR/disable $MODULE_DIR/remove")

            // 8. 验证安装
            if (!isModuleInstalled()) {
                return InstallResult(false, "模块文件写入完成但验证失败，请检查 root 权限")
            }

            Log.i(TAG, "Module files installed at $MODULE_DIR")

            // 9. 立即尝试注入 SELinux 规则（无需重启即可生效）
            val liveInjected = tryInjectLiveSepolicy()

            // 10. 立即设置系统属性（移除 2>/dev/null，让 execRoot 收集错误信息）
            execRoot("resetprop persist.sys.cached_apps_freezer disabled || setprop persist.sys.cached_apps_freezer disabled")

            if (liveInjected) {
                InstallResult(
                    true,
                    "SELinux 策略已安装并立即生效！\n" +
                        "请重新打开 TombstoneX App，Binder 通道应已就绪。\n" +
                        "（如果仍显示未激活，请重启设备后再试）",
                )
            } else {
                InstallResult(
                    true,
                    "SELinux 策略模块安装成功！\n" +
                        "当前环境不支持运行时注入，请重启设备使其生效。\n" +
                        "重启后打开 TombstoneX App，Binder 通道将自动就绪。",
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            InstallResult(false, "安装失败: ${e.message}")
        }
    }

    /**
     * 尝试运行时注入 SELinux 规则（无需重启）
     * 依次尝试：magiskpolicy → ksud sepolicy patch → setenforce 0（最后兜底）
     * @return true 表示成功注入
     */
    private fun tryInjectLiveSepolicy(): Boolean {
        // 方案 1: Magisk 的 magiskpolicy --live
        val magiskPolicyPaths = listOf(
            "/data/adb/magisk/magiskpolicy",
            "/system/bin/magiskpolicy",
            "/system/xbin/magiskpolicy",
        )
        for (path in magiskPolicyPaths) {
            if (tryMagiskPolicy(path)) return true
        }

        // 方案 2: KernelSU 的 ksud sepolicy patch
        if (tryKsudSepolicy()) return true

        // 方案 3: APatch 的 apd sepolicy
        if (tryApdSepolicy()) return true

        return false
    }

    /**
     * 用 magiskpolicy --live 注入规则
     */
    private fun tryMagiskPolicy(binary: String): Boolean {
        return try {
            // 先检查二进制是否存在且可执行
            val checkProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", "test -x $binary && echo 1 || echo 0"))
            val checkResult = waitForProcess(checkProcess)
            if (checkResult.stdout.trim() != "1") return false

            val rules = SEPOLICY_RULE.lines().filter { it.isNotBlank() }
            val args = rules.joinToString(" ") { "\"$it\"" }
            val cmd = "$binary --live $args"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val result = waitForProcess(process)
            if (result.exitCode == 0) {
                Log.i(TAG, "SELinux rules injected via $binary --live")
                true
            } else {
                Log.w(TAG, "$binary --live failed (exit=${result.exitCode}): ${result.stderr}")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "magiskpolicy injection failed: ${e.message}")
            false
        }
    }

    /**
     * 用 KernelSU 的 ksud sepolicy apply 注入规则
     * 命令格式: ksud sepolicy apply <file>
     * 参考: https://deepwiki.com/tiann/KernelSU/3.4-boot-image-patching
     */
    private fun tryKsudSepolicy(): Boolean {
        return try {
            val checkProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", "command -v ksud && echo 1 || echo 0"))
            val checkResult = waitForProcess(checkProcess)
            if (checkResult.stdout.trim() != "1") return false

            // 正确命令: ksud sepolicy apply <file>（不是 patch --apply）
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "ksud sepolicy apply $MODULE_DIR/sepolicy.rule"))
            val result = waitForProcess(process)
            if (result.exitCode == 0) {
                Log.i(TAG, "SELinux rules injected via ksud sepolicy apply")
                true
            } else {
                Log.w(TAG, "ksud sepolicy apply failed (exit=${result.exitCode}): ${result.stderr}")
                // 备用方案：逐条 patch
                tryKsudSepolicyPatch()
            }
        } catch (e: Exception) {
            Log.w(TAG, "ksud injection failed: ${e.message}")
            false
        }
    }

    /**
     * 备用方案：用 ksud sepolicy patch 逐条注入规则
     * 命令格式: ksud sepolicy patch "<statement>"
     */
    private fun tryKsudSepolicyPatch(): Boolean {
        return try {
            val rules = SEPOLICY_RULE.lines().filter { it.isNotBlank() }
            var allSuccess = true
            for (rule in rules) {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "ksud sepolicy patch \"$rule\""))
                val result = waitForProcess(process)
                if (result.exitCode != 0) {
                    allSuccess = false
                    Log.w(TAG, "ksud sepolicy patch '$rule' failed (exit=${result.exitCode}): ${result.stderr}")
                }
            }
            if (allSuccess) {
                Log.i(TAG, "SELinux rules injected via ksud sepolicy patch (individual)")
            }
            allSuccess
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 用 APatch 的 apd sepolicy apply 注入规则
     * 命令格式: apd sepolicy apply <file>
     */
    private fun tryApdSepolicy(): Boolean {
        return try {
            val checkProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", "command -v apd && echo 1 || echo 0"))
            val checkResult = waitForProcess(checkProcess)
            if (checkResult.stdout.trim() != "1") return false

            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "apd sepolicy apply $MODULE_DIR/sepolicy.rule"))
            val result = waitForProcess(process)
            if (result.exitCode == 0) {
                Log.i(TAG, "SELinux rules injected via apd sepolicy apply")
                true
            } else {
                Log.w(TAG, "apd sepolicy apply failed (exit=${result.exitCode}): ${result.stderr}")
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 卸载模块
     */
    fun uninstall(): Boolean {
        return try {
            // 设置 remove 标记，下次重启后 KernelSU/Magisk 自动清理
            execRoot("touch $MODULE_DIR/remove")
            // 也可以直接删除
            execRoot("rm -rf $MODULE_DIR")
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 通过 root 写入文件内容
     * 先关闭 outputStream 再等待进程，捕获 IOException 并读取 stderr 获取准确错误信息
     */
    private fun writeFile(path: String, content: String) {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat > $path"))
        var writeError: java.io.IOException? = null
        try {
            process.outputStream.use { os ->
                os.write(content.toByteArray())
                os.flush()
            }
        } catch (e: java.io.IOException) {
            writeError = e
        }
        // 消费 stdout/stderr 并等待进程结束（带超时）
        val result = waitForProcess(process)
        if (result.exitCode != 0 || writeError != null) {
            val errMsg = if (writeError != null) "${writeError.message} | ${result.stderr}" else result.stderr
            throw RuntimeException("Failed to write $path: $errMsg", writeError)
        }
    }

    /**
     * 执行 root 命令
     * 消费 stdout/stderr 避免管道缓冲区满导致死锁，带超时保护
     *
     * M-41: 当前所有调用方均传入常量字符串（如固定路径、固定命令），因此不存在 shell 注入风险。
     * 但 execRoot 本身未对 cmd 参数进行任何转义或过滤。如果未来有动态参数需求（如拼接用户输入
     * 或外部数据到命令中），必须先实现 shell 转义（例如使用 ProcessBuilder 传参或白名单校验），
     * 否则存在命令注入风险。
     */
    private fun execRoot(cmd: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val result = waitForProcess(process)
            if (result.exitCode != 0) {
                // 非致命错误，仅记录
                Log.w(TAG, "Command '$cmd' exited with ${result.exitCode}: ${result.stderr}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Command '$cmd' failed: ${e.message}")
        }
    }

    data class InstallResult(
        val success: Boolean,
        val message: String,
    )
}
