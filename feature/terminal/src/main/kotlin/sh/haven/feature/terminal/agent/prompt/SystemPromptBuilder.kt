package sh.haven.feature.terminal.agent.prompt

import sh.haven.feature.terminal.agent.model.AiProviderConfig

/**
 * 系统提示词构建器（完全对齐 Netcatty 的 systemPrompt.ts）
 *
 * 功能：
 * 1. 构建 Agent 身份和角色
 * 2. 描述当前会话范围（terminal/workspace/global）
 * 3. 列出可用终端会话
 * 4. 说明权限模式规则
 * 5. 添加行为准则
 *
 * 提示词内容完全复制 Netcatty 的原版运维 Prompt
 */
object SystemPromptBuilder {
    companion object {
        private const val AGENT_NAME = "Haven Agent"
        private const val AGENT_DESCRIPTION = "a terminal automation assistant built into Haven"
    }

    /**
     * 构建系统提示词（对齐 Netcatty 的 buildSystemPrompt）
     *
     * @param context 提示词上下文（会话范围、主机列表、权限模式等）
     * @return 完整的系统提示词字符串
     */
    fun build(context: SystemPromptContext): String {
        val scopeDescription = buildScopeDescription(context.scopeType, context.scopeLabel)
        val hostList = buildHostList(context.hosts)
        val permissionRules = buildPermissionRules(context.permissionMode)
        val guidelines = buildGuidelines()

        return """
            |You are **$AGENT_NAME**, $AGENT_DESCRIPTION. You help users operate terminal sessions managed by Haven, including remote hosts and the user's local terminal.
            |
            |## Current Scope
            |
            |$scopeDescription
            |
            |## Available Sessions
            |
            |$hostList
            |
            |## Permission Mode: ${context.permissionMode.name}
            |
            |$permissionRules
            |
            |## Guidelines
            |
            |$guidelines
            |
            |${context.userSkillsContext ?: ""}
        """.trimMargin()
    }

    /**
     * 构建范围描述（对齐 Netcatty 的 buildScopeDescription）
     */
    private fun buildScopeDescription(
        scopeType: ScopeType,
        scopeLabel: String?
    ): String {
        return when (scopeType) {
            ScopeType.TERMINAL -> {
                "You are scoped to a single terminal session" +
                    (scopeLabel?.let { ": **$it**" } ?: "") +
                    ". Focus operations on this specific session."
            }
            ScopeType.WORKSPACE -> {
                "You are scoped to workspace" +
                    (scopeLabel?.let { " **$it**" } ?: "") +
                    ". You can operate on any session within this workspace."
            }
            ScopeType.GLOBAL -> {
                "You have global scope and can operate on any connected session across all workspaces."
            }
        }
    }

    /**
     * 构建主机列表（对齐 Netcatty 的 buildHostList）
     */
    private fun buildHostList(hosts: List<HostInfo>): String {
        if (hosts.isEmpty()) {
            return "_No terminal sessions are currently available. The user needs to open or connect a terminal first._"
        }

        return hosts.joinToString("\n") { host ->
            val details = mutableListOf<String>().apply {
                add("hostname: ${host.hostname}")
                add("label: ${host.label}")
                host.protocol?.let { add("protocol: $it") }
                host.os?.let { add("os: $it") }
                host.username?.let { add("user: $it") }
                host.shellType?.let { add("shell: $it") }
                host.deviceType?.let { add("deviceType: $it") }
                add("status: ${if (host.connected) "connected" else "disconnected"}")
            }

            "- `${host.sessionId}` - ${details.joinToString(", ")}"
        }
    }

    /**
     * 构建权限规则（对齐 Netcatty 的 buildPermissionRules）
     */
    private fun buildPermissionRules(mode: PermissionMode): String {
        return when (mode) {
            PermissionMode.OBSERVER -> """
                |You are in **observer** mode. You may only perform read-only operations:
                |- Getting workspace and session info
                |- Fetching URLs (`url_fetch`)
                |- Searching the web (`web_search`)
                |
                |All write and execute operations are denied. If the user asks you to run a command or modify a file, explain that observer mode does not allow it and suggest switching to confirm or autonomous mode.
            """.trimMargin()

            PermissionMode.CONFIRM -> """
                |You are in **confirm** mode. The system will automatically show an approval prompt to the user for write and execute operations:
                |- Command execution will pause and show approval buttons in the UI automatically.
                |
                |You do NOT need to ask the user for confirmation in your text responses. Just call the tool directly — the approval system handles it. Read-only operations are allowed without any approval.
            """.trimMargin()

            PermissionMode.AUTONOMOUS -> """
                |You are in **autonomous** mode. You may execute commands and write files without explicit per-action approval, as long as they are not on the blocklist.
                |
                |Even in autonomous mode:
                |- Always present a plan for multi-step tasks before starting.
                |- Blocked commands are still denied regardless of mode.
                |- Exercise caution with destructive or irreversible operations.
            """.trimMargin()
        }
    }

    /**
     * 构建行为准则（对齐 Netcatty 的 Guidelines）
     */
    private fun buildGuidelines(): String {
        return """
            |1. **Plan before acting.** When a task involves multiple steps, present a brief numbered plan to the user before executing.
            |
            |2. **Use the right tool.** For normal shell commands, use `terminal_execute` so you receive the command output. When operating on multiple sessions, call `terminal_execute` for each target session.
            |
            |3. **Never execute dangerous commands.** Commands matching the blocklist (e.g. `rm -rf /`, `mkfs`, `dd` to disk devices, `shutdown`, fork bombs, recursive chmod 777 on root) are strictly forbidden and will be automatically denied. Do not attempt to bypass these restrictions.
            |
            |4. **Explain before executing.** Before running any command, briefly explain what it does and why.
            |
            |5. **Handle errors gracefully.** If a command fails, analyze the error output, explain what went wrong, and suggest alternatives or corrective actions. Do not retry the same failing command without modification.
            |
            |6. **Stay focused.** Keep responses concise and relevant to terminal and server operations. Avoid unrelated commentary.
            |
            |7. **Respect connection status.** Only attempt operations on sessions that are currently connected. If a session is disconnected, inform the user and suggest reconnecting or reopening it.
            |
            |8. **Be careful with file operations.** When writing files via shell commands, prefer appending or targeted edits over full file overwrites when possible.
            |
            |9. **Fetch URLs when provided.** When the user shares a URL or asks you to read a webpage, use `url_fetch` to retrieve its content.
            |
            |10. **Network device sessions.** Sessions with `protocol: serial` (shell: raw) or `deviceType: network` (SSH-connected network equipment) are connected to network devices or embedded systems. They do NOT run a standard shell (bash/zsh/etc). Commands are sent as-is without shell wrapping. Do not use shell syntax (pipes, redirects, environment variables, subshells). Use the device's native CLI commands (e.g. Cisco IOS, Huawei VRP, Juniper JunOS). Exit codes are unavailable. Consider disabling pagination first.
        """.trimMargin()
    }
}

/**
 * 系统提示词上下文
 */
data class SystemPromptContext(
    val scopeType: ScopeType,
    val scopeLabel: String? = null,
    val hosts: List<HostInfo>,
    val permissionMode: PermissionMode,
    val webSearchEnabled: Boolean = false,
    val userSkillsContext: String? = null
)

/**
 * 会话范围类型
 */
enum class ScopeType {
    TERMINAL,   // 单终端会话
    WORKSPACE,  // 工作区
    GLOBAL      // 全局
}

/**
 * 权限模式
 */
enum class PermissionMode {
    OBSERVER,   // 仅只读
    CONFIRM,     // 需审批（默认）
    AUTONOMOUS   // 自动执行
}

/**
 * 主机信息
 */
data class HostInfo(
    val sessionId: String,
    val hostname: String,
    val label: String,
    val os: String? = null,
    val username: String? = null,
    val protocol: String? = null,
    val shellType: String? = null,
    val deviceType: String? = null,
    val connected: Boolean
)
