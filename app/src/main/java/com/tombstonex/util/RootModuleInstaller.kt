package com.tombstonex.util

import android.util.Log
import java.io.File

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
            val output = process.inputStream.bufferedReader().use { it.readLine() ?: "" }
            process.waitFor()
            output.contains("uid=0")
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
            val output = process.inputStream.bufferedReader().use { it.readLine() ?: "0" }
            process.waitFor()
            output.trim() == "1"
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
            val output = process.inputStream.bufferedReader().use { it.readLine() ?: "0" }
            process.waitFor()
            output.trim() == "1"
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

            // 10. 立即设置系统属性
            execRoot("resetprop persist.sys.cached_apps_freezer disabled 2>/dev/null || setprop persist.sys.cached_apps_freezer disabled 2>/dev/null")

            if (liveInjected) {
                InstallResult(
                    true,
                    "SELinux 策略已安装并立即生效！\n" +
                        "请重新打开 TombstoneX App，状态应显示 Binder 通道。\n" +
                        "（如果仍显示 FileIPC，请重启设备后再试）",
                )
            } else {
                InstallResult(
                    true,
                    "SELinux 策略模块安装成功！\n" +
                        "当前环境不支持运行时注入，请重启设备使其生效。\n" +
                        "重启后打开 TombstoneX App，状态应显示 Binder 通道。",
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
            val checkOut = checkProcess.inputStream.bufferedReader().use { it.readLine() ?: "0" }
            checkProcess.waitFor()
            if (checkOut.trim() != "1") return false

            val rules = SEPOLICY_RULE.lines().filter { it.isNotBlank() }
            val args = rules.joinToString(" ") { "\"$it\"" }
            val cmd = "$binary --live $args"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Log.i(TAG, "SELinux rules injected via $binary --live")
                true
            } else {
                val err = process.errorStream.bufferedReader().use { it.readText() }
                Log.w(TAG, "$binary --live failed (exit=$exitCode): $err")
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
            val checkOut = checkProcess.inputStream.bufferedReader().use { it.readLine() ?: "0" }
            checkProcess.waitFor()
            if (checkOut.trim() != "1") return false

            // 正确命令: ksud sepolicy apply <file>（不是 patch --apply）
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "ksud sepolicy apply $MODULE_DIR/sepolicy.rule"))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Log.i(TAG, "SELinux rules injected via ksud sepolicy apply")
                true
            } else {
                val err = process.errorStream.bufferedReader().use { it.readText() }
                Log.w(TAG, "ksud sepolicy apply failed (exit=$exitCode): $err")
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
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    allSuccess = false
                    Log.w(TAG, "ksud sepolicy patch '$rule' failed (exit=$exitCode)")
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
            val checkOut = checkProcess.inputStream.bufferedReader().use { it.readLine() ?: "0" }
            checkProcess.waitFor()
            if (checkOut.trim() != "1") return false

            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "apd sepolicy apply $MODULE_DIR/sepolicy.rule"))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Log.i(TAG, "SELinux rules injected via apd sepolicy apply")
                true
            } else {
                val err = process.errorStream.bufferedReader().use { it.readText() }
                Log.w(TAG, "apd sepolicy apply failed (exit=$exitCode): $err")
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
     */
    private fun writeFile(path: String, content: String) {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat > $path"))
        process.outputStream.use { os ->
            os.write(content.toByteArray())
            os.flush()
        }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val err = process.errorStream.bufferedReader().use { it.readText() }
            throw RuntimeException("Failed to write $path: $err")
        }
    }

    /**
     * 执行 root 命令
     */
    private fun execRoot(cmd: String) {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val err = process.errorStream.bufferedReader().use { it.readText() }
            // 非致命错误，仅记录
            Log.w(TAG, "Command '$cmd' exited with $exitCode: $err")
        }
    }

    data class InstallResult(
        val success: Boolean,
        val message: String,
    )
}
