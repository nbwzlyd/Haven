package sh.haven.feature.agent.tools

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SshSessionManager
import sh.haven.feature.agent.core.CommandBlocklist
import sh.haven.feature.agent.model.ToolDefinition
import sh.haven.feature.agent.model.ToolFunctionDefinition
import sh.haven.feature.agent.model.ToolResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `terminal_execute` — run a shell command on a terminal session and
 * return the captured output.
 *
 * Ported from Netcatty's `terminal_execute` (Path A). The execution
 * model: write `command + "\n"` to the session's PTY (exactly as if
 * the user typed it), wait for the command to produce output, then
 * read the scrollback ring and return the new bytes.
 *
 * This is the "visible in terminal" execution path (Netcatty's
 * `execViaPty`), not the invisible SSH exec-channel path. The user
 * sees the command run in their terminal tab — which is the right
 * default for a mobile app where the terminal is the primary surface.
 *
 * Safety: the [CommandBlocklist] is checked before execution; matched
 * commands are refused with an error result. The approval gate
 * (confirm mode) is handled by the agent loop, not here — this tool
 * runs the command once it's been approved.
 */
@Singleton
class TerminalExecuteTool @Inject constructor(
    private val sshSessionManager: SshSessionManager,
    private val localSessionManager: LocalSessionManager,
    private val sessionManagerRegistry: SessionManagerRegistry,
    private val commandBlocklist: CommandBlocklist,
) {

    fun definition(): ToolDefinition = ToolDefinition(
        function = ToolFunctionDefinition(
            name = "terminal_execute",
            description = "Execute a shell command on a terminal session and return the output. " +
                "The command runs in the session's shell (visible in the terminal tab), " +
                "and the captured stdout/stderr is returned. Use workspace_get_info first " +
                "to discover sessionIds. The command is sent as if the user typed it, so " +
                "shell features (pipes, redirects, variables) work. Output is truncated " +
                "to 24KB. Dangerous commands (rm -rf /, mkfs, dd to /dev/, shutdown, " +
                "fork bombs) are blocked by a safety filter.",
            parameters = JSONObject().apply {
                put("type", "object")
                put(
                    "properties",
                    JSONObject().apply {
                        put(
                            "sessionId",
                            JSONObject().apply {
                                put("type", "string")
                                put(
                                    "description",
                                    "Terminal session ID (from workspace_get_info). " +
                                        "Optional — defaults to the sole open terminal session.",
                                )
                            },
                        )
                        put(
                            "command",
                            JSONObject().apply {
                                put("type", "string")
                                put("description", "The shell command to execute.")
                            },
                        )
                    },
                )
                put("required", JSONArray().apply { put("command") })
            },
        ),
    )

    suspend fun execute(argumentsJson: String, toolCallId: String): ToolResult {
        val args = try {
            JSONObject(argumentsJson)
        } catch (e: Exception) {
            return errorResult(toolCallId, "Invalid arguments JSON: ${e.message}")
        }
        val command = args.optString("command", "").takeIf { it.isNotEmpty() }
            ?: return errorResult(toolCallId, "Missing required argument: command")
        val sessionId = args.optString("sessionId", "").takeIf { it.isNotEmpty() }
            ?: resolveDefaultSessionId()
            ?: return errorResult(
                toolCallId,
                "No sessionId provided and no single open terminal session found. " +
                    "Call workspace_get_info to see available sessions.",
            )

        // Safety: blocklist check
        val blockedReason = commandBlocklist.check(command)
        if (blockedReason != null) {
            return errorResult(toolCallId, "Command blocked by safety filter: $blockedReason")
        }

        // Snapshot the scrollback length before sending the command, so we
        // can return only the new output (the command's output), not the
        // entire scrollback.
        val beforeBytes = readScrollback(sessionId, 256 * 1024)?.size ?: 0

        // Send the command + Enter, exactly as the user would type it.
        try {
            sendInput(sessionId, "$command\r")
        } catch (e: IllegalStateException) {
            return errorResult(toolCallId, e.message ?: "Failed to send input to session $sessionId")
        }

        // Wait for output to accumulate. We poll the scrollback a few times
        // with short delays, stopping early once the output stabilises (no
        // new bytes between polls) or the timeout is hit.
        val output = waitForOutput(sessionId, beforeBytes)

        return ToolResult(
            toolCallId = toolCallId,
            name = "terminal_execute",
            content = JSONObject().apply {
                put("sessionId", sessionId)
                put("command", command)
                put("output", output)
                put("truncated", output.length >= MAX_OUTPUT_CHARS)
            }.toString(),
        )
    }

    /**
     * Send [text] to the session's PTY. Tries SSH first, then the
     * local-shell manager — mirrors McpTools.sendRawInput.
     */
    private fun sendInput(sessionId: String, text: String) {
        val sshErr = try {
            sshSessionManager.sendInput(sessionId, text)
            null
        } catch (e: IllegalStateException) {
            e.message
        }
        if (sshErr != null) {
            localSessionManager.sendInput(sessionId, text)
        }
    }

    /** Read up to [maxBytes] of scrollback, trying SSH then local. */
    private fun readScrollback(sessionId: String, maxBytes: Int): ByteArray? {
        return sshSessionManager.readAgentScrollback(sessionId, maxBytes)
            ?: localSessionManager.readAgentScrollback(sessionId, maxBytes)
    }

    /**
     * Poll the scrollback until output stabilises or the timeout is hit.
     * Returns the new bytes (after [beforeBytes]) as a UTF-8 string,
     * truncated to [MAX_OUTPUT_CHARS].
     */
    private suspend fun waitForOutput(sessionId: String, beforeBytes: Int): String {
        var lastSize = beforeBytes
        var stableCount = 0
        val maxIterations = 30 // ~15s at 500ms per poll
        var iteration = 0
        while (iteration < maxIterations) {
            delay(500)
            iteration++
            val current = readScrollback(sessionId, 256 * 1024)
            val currentSize = current?.size ?: 0
            if (currentSize == lastSize) {
                stableCount++
                // Require 2 consecutive stable polls (~1s of quiet) before
                // declaring the command done — avoids cutting off a slow
                // command that's between writes.
                if (stableCount >= 2 && currentSize > beforeBytes) break
            } else {
                stableCount = 0
                lastSize = currentSize
            }
        }
        val full = readScrollback(sessionId, 256 * 1024) ?: return ""
        val newBytes = if (full.size > beforeBytes) {
            full.copyOfRange(beforeBytes, full.size)
        } else {
            ByteArray(0)
        }
        val raw = String(newBytes, Charsets.UTF_8)
        return stripAnsiEscapes(raw).take(MAX_OUTPUT_CHARS)
    }

    /** Resolve the sessionId when the caller didn't pass one: the sole open session. */
    private fun resolveDefaultSessionId(): String? {
        val sessions = sessionManagerRegistry.allSessions
            .filter {
                it.status == sh.haven.core.ssh.SessionStatus.CONNECTED &&
                    it.transport in TERMINAL_TRANSPORTS
            }
        return if (sessions.size == 1) sessions[0].sessionId else null
    }

    private fun errorResult(toolCallId: String, message: String) = ToolResult(
        toolCallId = toolCallId,
        name = "terminal_execute",
        content = JSONObject().apply { put("error", message) }.toString(),
        isError = true,
    )

    companion object {
        private val TERMINAL_TRANSPORTS = setOf(
            sh.haven.core.ssh.Transport.SSH,
            sh.haven.core.ssh.Transport.MOSH,
            sh.haven.core.ssh.Transport.ET,
            sh.haven.core.ssh.Transport.RETICULUM,
            sh.haven.core.ssh.Transport.LOCAL,
        )
        private const val MAX_OUTPUT_CHARS = 24_000
    }
}
