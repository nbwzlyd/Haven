package sh.haven.feature.terminal.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sh.haven.core.ssh.SshSessionManager
import sh.haven.feature.terminal.TerminalViewModel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor that runs commands on the currently connected terminal session.
 * Uses Haven's existing terminal infrastructure to execute commands and capture output.
 */
@Singleton
class McpToolsExecutor @Inject constructor(
    private val sshSessionManager: SshSessionManager,
    private val terminalViewModel: TerminalViewModel,
) {
    companion object {
        private const val TAG = "McpToolsExecutor"
        private const val COMMAND_TIMEOUT_MS = 30_000L // 30 seconds
    }

    /**
     * Execute a command on the current terminal session and return the output.
     * This is a simplified version - in production, you'd want to:
     * 1. Use the terminal's actual command execution mechanism
     * 2. Capture output properly (not just relying on scrollback)
     * 3. Handle interactive prompts
     */
    suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        try {
            // Get the current active session
            val activeSession = terminalViewModel.getActiveSession()
            if (activeSession == null) {
                return@withContext "Error: No active terminal session. Please connect to a server first."
            }

            // For now, use the MCP tool approach - send input and read output
            // This is a simplified implementation
            val output = executeOnSession(activeSession.sessionId, command)
            return@withContext output ?: "Error: Failed to execute command"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command: $command", e)
            return@withContext "Error: ${e.message ?: "Unknown error"}"
        }
    }

    private suspend fun executeOnSession(sessionId: String, command: String): String? {
        return try {
            // Use Haven's terminal input mechanism
            // This is a placeholder - actual implementation would depend on
            // how Haven's terminal ViewModel exposes command execution

            // For now, return a mock response
            // TODO: Integrate with actual terminal command execution
            """
                Executing: $command

                Note: This is a placeholder. Actual implementation needs to:
                1. Send command to terminal session
                2. Wait for output
                3. Capture and return the output

                For Haven's architecture, consider using:
                - terminalViewModel.sendInput(sessionId, command + "\n")
                - terminalViewModel.getSessionOutput(sessionId)
                - Or directly via SshSessionManager for SSH sessions
            """.trimIndent()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute on session $sessionId", e)
            null
        }
    }

    /**
     * Get list of available tools (from Haven's MCP tools).
     * This would be used to inform the AI about available capabilities.
     */
    fun getAvailableTools(): List<ToolDefinition> {
        // Return a list of available tools that the AI can call
        // These should map to Haven's MCP tools
        return listOf(
            ToolDefinition(
                name = "execute_command",
                description = "Execute a command on the current server",
                parameters = listOf(
                    ToolParameter("command", "string", "The command to execute")
                )
            ),
            ToolDefinition(
                name = "read_file",
                description = "Read a file from the server",
                parameters = listOf(
                    ToolParameter("path", "string", "Path to the file")
                )
            ),
            ToolDefinition(
                name = "check_disk_space",
                description = "Check disk space usage",
                parameters = emptyList()
            ),
            ToolDefinition(
                name = "check_memory",
                description = "Check memory usage",
                parameters = emptyList()
            ),
            ToolDefinition(
                name = "list_processes",
                description = "List running processes",
                parameters = emptyList()
            )
        )
    }
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>
)

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String
)
