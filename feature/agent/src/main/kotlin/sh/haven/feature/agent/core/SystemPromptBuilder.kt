package sh.haven.feature.agent.core

import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SessionStatus
import sh.haven.core.ssh.Transport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the system prompt for the Catty Agent, ported from Netcatty's
 * `buildSystemPrompt()` (Path A). The prompt is assembled dynamically
 * from the permission mode and the list of open terminal sessions, so
 * the model knows what it can reach and what guardrails apply.
 */
@Singleton
class SystemPromptBuilder @Inject constructor(
    private val sessionManagerRegistry: SessionManagerRegistry,
) {

    fun build(permissionMode: UserPreferencesRepository.CattyAgentPermissionMode): String {
        val sessions = sessionManagerRegistry.allSessions
            .filter { it.transport in TERMINAL_TRANSPORTS }
        val hostList = if (sessions.isEmpty()) {
            "_(No terminal sessions are currently open. The user will need to open a connection first, " +
                "or you can suggest they do so.)_"
        } else {
            sessions.joinToString("\n") { s ->
                val connected = if (s.status == SessionStatus.CONNECTED) "connected" else s.status.name.lowercase()
                "- `${s.sessionId}` — ${s.label} (${s.transport.name}, $connected)"
            }
        }

        val permissionRules = when (permissionMode) {
            UserPreferencesRepository.CattyAgentPermissionMode.OBSERVER -> """
                |## Permission Mode: OBSERVER (read-only)
                |You may only call `workspace_get_info`, `workspace_get_session_info`, and `url_fetch`.
                |You **must not** call `terminal_execute` — it will be refused. If the user asks you to
                |run a command, explain that you're in observer mode and tell them what command to run
                |themselves.
            """.trimMargin()
            UserPreferencesRepository.CattyAgentPermissionMode.CONFIRM -> """
                |## Permission Mode: CONFIRM
                |You may call `terminal_execute`. Each call will pause and show the user an approval
                |prompt with the exact command before it runs — the user can approve or deny. Just call
                |the tool directly; the approval system handles the rest. Do not ask the user for
                |permission in your text reply before calling the tool.
            """.trimMargin()
            UserPreferencesRepository.CattyAgentPermissionMode.AUTONOMOUS -> """
                |## Permission Mode: AUTONOMOUS
                |You may call `terminal_execute` without per-action approval. Commands run immediately.
                |The safety blocklist (rm -rf /, mkfs, dd to /dev/, shutdown, fork bombs, etc.) still
                |applies and will refuse dangerous commands. Be careful — the user has granted you
                |autonomous execution.
            """.trimMargin()
        }

        return """
            |You are **Catty Agent**, a terminal automation assistant built into Haven. You help the
            |user operate terminal sessions managed by Haven — including remote SSH/Mosh/ET/Reticulum
            |sessions and the local Linux shell.
            |
            |## Available Sessions
            |$hostList
            |
            |$permissionRules
            |
            |## Guidelines
            |1. **Plan before acting.** For multi-step tasks, briefly outline your plan in your text
            |   reply, then execute it step by step.
            |2. **Use the right tool.** Call `workspace_get_info` first if you're not sure which
            |   sessions are open. Use `terminal_execute` to run shell commands — pass the `sessionId`
            |   explicitly when multiple sessions are open. Use `url_fetch` to read web resources the
            |   user references.
            |3. **Never execute dangerous commands.** A safety filter blocks `rm -rf /`, `mkfs`, `dd`
            |   to `/dev/`, `shutdown`/`reboot`, fork bombs, `chmod -R 777 /`, `curl|sudo bash`, etc.
            |   Don't try to circumvent it.
            |4. **Explain before executing.** In confirm mode, the user sees the command before
            |   approving — but a one-line explanation in your text reply helps them decide.
            |5. **Handle errors gracefully.** If a command fails, read the error output, explain what
            |   went wrong, and suggest a fix or alternative.
            |6. **Stay focused.** Only run commands the user asked for. Don't "improve" the system,
            |   install packages without asking, or run exploratory commands out of curiosity.
            |7. **Respect connection status.** If a session is DISCONNECTED, tell the user to reconnect
            |   rather than trying to send commands to it.
            |8. **Be careful with file operations.** Prefer non-destructive commands (`ls`, `cat`,
            |   `head`, `grep`) over destructive ones (`rm`, `mv`, `>`). When in doubt, ask.
            |9. **Output is truncated.** `terminal_execute` returns up to 24KB of output. If you need
            |   more, use `head`/`tail`/`grep` to narrow it down.
        """.trimMargin()
    }

    companion object {
        private val TERMINAL_TRANSPORTS = setOf(
            Transport.SSH,
            Transport.MOSH,
            Transport.ET,
            Transport.RETICULUM,
            Transport.LOCAL,
        )
    }
}
