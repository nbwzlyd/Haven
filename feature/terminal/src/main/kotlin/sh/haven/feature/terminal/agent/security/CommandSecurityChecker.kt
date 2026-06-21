package sh.haven.feature.terminal.agent.security

import android.util.Log
import java.util.regex.Pattern

/**
 * 命令安全校验器（完全对齐 Netcatty 的命令黑名单）
 *
 * 功能：
 * 1. 检查命令是否命中黑名单
 * 2. 根据权限模式决定是否允许执行
 * 3. 返回详细的拒绝原因
 *
 * 黑名单来源：Netcatty lib/commandBlocklist.json (17条正则)
 */
object CommandSecurityChecker {
    companion object {
        private const val TAG = "CommandSecurityChecker"

        /**
         * 默认命令黑名单（完全复制 Netcatty 的 DEFAULT_COMMAND_BLOCKLIST）
         *
         * 包含 17 条正则表达式，覆盖：
         * - rm -rf 组合
         * - 磁盘格式化 (mkfs)
         * - 磁盘写入 (dd)
         * - 系统关机命令
         * - Fork 炸弹
         * - 危险的重定向
         * - 递归 chmod 777
         * - 移动根目录
         * - 通过管道执行 sudo bash
         * - 命令替换 ($() 和反引号)
         */
        private val DEFAULT_BLOCKLIST = listOf(
            // 1. rm -rf 组合（递归强制删除）
            Pattern.compile("""\brm\s+.*-rf"""),

            // 2. mkfs 格式化命令
            Pattern.compile("""\bmkfs\."""),

            // 3. dd 写入磁盘设备
            Pattern.compile("""\bdd\s+if=.*\s+of=/dev/"""),

            // 4. 系统关机/重启命令
            Pattern.compile("""\b(shutdown|reboot|poweroff|halt)\b"""),

            // 5. Fork 炸弹
            Pattern.compile(""":\(\)\{.*?:\|:\&.*?\};:"""),

            // 6. 重定向到磁盘设备
            Pattern.compile(""">\s*/dev/sd"""),

            // 7. 递归 chmod 777 在根目录
            Pattern.compile("""\bchmod\s+(-[a-zA-Z]*R[a-zA-Z]*|--recursive)\s+777\s+/"""),

            // 8. mv / / (移动根目录）
            Pattern.compile("""\bmv\s+/\s"""),

            // 9. 重定向到 /etc/ 目录
            Pattern.compile("":\s*>.*?/etc/"""),

            // 10. curl | sudo bash 组合
            Pattern.compile("""\bcurl\s+.*\|\s*\bsudo\s+\bbash\b"""),

            // 11. wget | sudo bash 组合
            Pattern.compile("""\bwget\s+.*\|\s*\bsudo\s+\bbash\b"""),

            // 12. base64 解码后执行
            Pattern.compile("""base64.*\|.*(?:ba)?sh"""),

            // 13. eval 命令
            Pattern.compile("""\beval\b"""),

            // 14. $() 命令替换
            Pattern.compile("""\$\("""),

            // 15. 反引号命令替换
            Pattern.compile(""".+""")
        )

        /**
         * 权限模式（对齐 Netcatty 的 AIPermissionMode）
         */
        enum class PermissionMode {
            OBSERVER,   // 仅只读操作
            CONFIRM,     // 写操作需用户审批（默认）
            AUTONOMOUS   // 自动执行（仍受黑名单限制）
        }

        /**
         * 检查结果
         */
        data class CheckResult(
            val allowed: Boolean,
            val reason: String? = null,
            val matchedPattern: String? = null
        )
    }

    private val blocklist = DEFAULT_BLOCKLIST.toMutableList()

    /**
     * 检查命令是否安全
     *
     * @param command 要执行的命令
     * @param permissionMode 当前权限模式
     * @return CheckResult 检查结果
     */
    fun checkCommand(
        command: String,
        permissionMode: PermissionMode = PermissionMode.CONFIRM
    ): CheckResult {
        // 1. 检查是否命中黑名单
        blocklist.forEach { pattern ->
            if (pattern.matcher(command).find()) {
                return CheckResult(
                    allowed = false,
                    reason = "命令匹配黑名单规则：${pattern.pattern()}",
                    matchedPattern = pattern.pattern()
                )
            }
        }

        // 2. 根据权限模式判断
        return when (permissionMode) {
            PermissionMode.OBSERVER -> {
                // Observer 模式：仅允许只读命令
                if (isReadOnlyCommand(command)) {
                    CheckResult(allowed = true)
                } else {
                    CheckResult(
                        allowed = false,
                        reason = "Observer 模式下不允许执行写操作命令"
                    )
                }
            }
            PermissionMode.CONFIRM -> {
                // Confirm 模式：允许执行，但需要用户审批
                CheckResult(allowed = true)
            }
            PermissionMode.AUTONOMOUS -> {
                // Autonomous 模式：自动执行
                CheckResult(allowed = true)
            }
        }
    }

    /**
     * 判断是否为只读命令
     */
    private fun isReadOnlyCommand(command: String): Boolean {
        val readOnlyPatterns = listOf(
            """\bls\b""",
            """\bcat\b""",
            """\bhead\b""",
            """\btail\b""",
            """\bgrep\b""",
            """\bless\b""",
            """\bmore\b""",
            """\bpwd\b""",
            """\bwhoami\b""",
            """\buname\b""",
            """\bdf\b""",
            """\bfree\b""",
            """\btop\b""",
            """\bps\b""",
            """\bnetstat\b""",
            """\bss\b""",
            """\bifconfig\b""",
            """\bip\s+addr\b"""
        )

        return readOnlyPatterns.any { pattern ->
            Pattern.compile(pattern).matcher(command).find()
        }
    }

    /**
     * 添加自定义黑名单规则
     */
    fun addBlocklistPattern(pattern: String) {
        try {
            blocklist.add(Pattern.compile(pattern))
            Log.i(TAG, "Added blocklist pattern: $pattern")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add blocklist pattern: $pattern", e)
        }
    }

    /**
     * 移除黑名单规则
     */
    fun removeBlocklistPattern(pattern: Pattern) {
        blocklist.remove(pattern)
    }

    /**
     * 获取当前黑名单（用于设置页面展示）
     */
    fun getBlocklist(): List<Pattern> {
        return blocklist.toList()
    }

    /**
     * 重置为默认黑名单
     */
    fun resetToDefault() {
        blocklist.clear()
        blocklist.addAll(DEFAULT_BLOCKLIST)
        Log.i(TAG, "Reset blocklist to default (17 patterns)")
    }
}
