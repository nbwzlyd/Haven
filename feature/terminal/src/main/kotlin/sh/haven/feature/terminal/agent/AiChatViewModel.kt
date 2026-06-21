package sh.haven.feature.terminal.agent

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.nio.charset.StandardCharsets
import sh.haven.core.ssh.SshSessionManager
import sh.haven.feature.terminal.agent.model.*
import sh.haven.feature.terminal.agent.model.ToolCall
import sh.haven.feature.terminal.agent.model.ToolResult
import sh.haven.feature.terminal.agent.network.OpenAiStreamingClient
import sh.haven.feature.terminal.agent.security.CommandSecurityChecker
import sh.haven.feature.terminal.agent.storage.AiSettingsRepository
import sh.haven.feature.terminal.agent.storage.EncryptedPreferenceStore
import javax.inject.Inject

/**
 * AI Chat ViewModel (1:1 aligned with Netcatty's AI agent flow).
 *
 * Executes terminal commands via SshSessionManager:
 * 1. sendInput() - sends command to SSH session
 * 2. readAgentScrollback() - reads terminal output
 * 3. Polls for command completion (heuristic-based)
 */
@HiltViewModel
class AiChatViewModel @Inject constructor(
    private val settingsRepository: AiSettingsRepository,
    private val encryptedPrefs: EncryptedPreferenceStore,
    private val sessionRegistry: TerminalSessionRegistry,
    private val sshSessionManager: SshSessionManager,
) : ViewModel() {

    companion object {
        private const val TAG = "AiChatViewModel"
        private const val COMMAND_TIMEOUT_MS = 30_000L  // 30 seconds max
        private const val POLL_INTERVAL_MS = 500L       // Poll every 500ms
        private const val QUIET_THRESHOLD_MS = 1_500L   // 1.5s no new output = done
        private const val MAX_SCROLLBACK_BYTES = 16_384    // 16KB tail
    }

    private val _uiState = MutableStateFlow(AiChatUiState())
    val uiState: StateFlow<AiChatUiState> = _uiState.asStateFlow()

    private val streamingClient = OpenAiStreamingClient()
    private val _messages = mutableListOf<ChatMessage>()

    /**
     * Send message to AI (with tool calling support).
     */
    fun sendMessage(userMessage: String) {
        if (userMessage.isBlank()) return

        viewModelScope.launch {
            try {
                val userMsg = ChatMessage(role = "user", content = userMessage)
                _messages.add(userMsg)
                _uiState.update { it.copy(messages = _messages.toList(), isAiThinking = true) }

                val config = settingsRepository.getActiveConfig()
                if (config == null) {
                    addAssistantMessage("Please configure an AI provider in Settings first.")
                    return@launch
                }

                val apiMessages = buildApiMessages()
                val tools = TerminalTools.getAllTools()
                var assistantResponse = StringBuilder()
                var toolCallsAccumulated = mutableListOf<ToolCall>()

                streamingClient.sendMessage(
                    config = config,
                    messages = apiMessages,
                    tools = tools
                ) { event ->
                    when (event) {
                        is ChatStreamEvent.TextDelta -> {
                            assistantResponse.append(event.delta)
                            updateAssistantMessage(assistantResponse.toString())
                        }
                        is ChatStreamEvent.ToolCall -> {
                            toolCallsAccumulated.addAll(event.toolCalls)
                        }
                        is ChatStreamEvent.Done -> {
                            val assistantMsg = ChatMessage(
                                role = "assistant",
                                content = assistantResponse.toString().ifBlank { null },
                                toolCalls = if (toolCallsAccumulated.isNotEmpty()) toolCallsAccumulated else null
                            )
                            _messages.add(assistantMsg)
                            if (toolCallsAccumulated.isNotEmpty()) {
                                handleToolCalls(toolCallsAccumulated, config)
                            } else {
                                _uiState.update { it.copy(isAiThinking = false) }
                            }
                        }
                        is ChatStreamEvent.Error -> {
                            addAssistantMessage("Error: ${event.message}")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Send message failed", e)
                addAssistantMessage("Failed to send message: ${e.message}")
            }
        }
    }

    /**
     * Handle tool calls: execute and send results back to AI.
     */
    private suspend fun handleToolCalls(toolCalls: List<ToolCall>, config: AiProviderConfig) {
        val toolResults = mutableListOf<ToolResult>()

        for (toolCall in toolCalls) {
            val result = executeTool(toolCall)
            toolResults.add(result)
        }

        for (toolResult in toolResults) {
            val toolMsg = ChatMessage(
                role = "tool",
                content = toolResult.content,
                toolCallId = toolResult.toolCallId
            )
            _messages.add(toolMsg)
        }

        continueConversation(config)
    }

    /**
     * Continue conversation after tool execution.
     */
    private suspend fun continueConversation(config: AiProviderConfig) {
        val apiMessages = buildApiMessages()
        val tools = TerminalTools.getAllTools()

        var assistantResponse = StringBuilder()
        var toolCallsAccumulated = mutableListOf<ToolCall>()

        streamingClient.sendMessage(
            config = config,
            messages = apiMessages,
            tools = tools
        ) { event ->
            when (event) {
                is ChatStreamEvent.TextDelta -> {
                    assistantResponse.append(event.delta)
                    updateAssistantMessage(assistantResponse.toString())
                }
                is ChatStreamEvent.ToolCall -> {
                    toolCallsAccumulated.addAll(event.toolCalls)
                }
                is ChatStreamEvent.Done -> {
                    val assistantMsg = ChatMessage(
                        role = "assistant",
                        content = assistantResponse.toString().ifBlank { null },
                        toolCalls = if (toolCallsAccumulated.isNotEmpty()) toolCallsAccumulated else null
                    )
                    _messages.add(assistantMsg)
                    if (toolCallsAccumulated.isNotEmpty()) {
                        handleToolCalls(toolCallsAccumulated, config)
                    } else {
                        _uiState.update { it.copy(isAiThinking = false) }
                    }
                }
                is ChatStreamEvent.Error -> {
                    addAssistantMessage("Error: ${event.message}")
                }
            }
        }
    }

    /**
     * Execute a tool call.
     */
    private suspend fun executeTool(toolCall: ToolCall): ToolResult {
        return when (toolCall.name) {
            "terminal_execute" -> executeTerminalCommand(toolCall)
            "workspace_get_info" -> executeWorkspaceGetInfo(toolCall)
            "workspace_get_session_info" -> executeWorkspaceGetSessionInfo(toolCall)
            "web_search" -> executeWebSearch(toolCall)
            "url_fetch" -> executeUrlFetch(toolCall)
            else -> ToolResult(
                toolCallId = toolCall.id,
                content = "Unknown tool: ${toolCall.name}",
                isError = true
            )
        }
    }

    /**
     * Execute terminal command via SSH (aligned with Netcatty's executeTerminalExecute).
     *
     * Flow:
     * 1. Validate session and permissions
     * 2. Check command against blacklist
     * 3. Send command via sshSessionManager.sendInput()
     * 4. Poll for output completion
     * 5. Read scrollback and extract output
     */
    private suspend fun executeTerminalCommand(toolCall: ToolCall): ToolResult {
        val sessionId = toolCall.arguments["sessionId"] as? String ?: ""
        val command = toolCall.arguments["command"] as? String ?: ""

        if (sessionId.isBlank() || command.isBlank()) {
            return ToolResult(toolCall.id, "Missing sessionId or command", isError = true)
        }

        // Check session exists and is connected
        val sessionState = sshSessionManager.getSession(sessionId)
        if (sessionState == null) {
            val available = sessionRegistry.getActiveSessions().joinToString(", ") { it.sessionId }
            return ToolResult(
                toolCall.id,
                "Session \"$sessionId\" not found. Available: $available",
                isError = true
            )
        }
        if (sessionState.status != SshSessionManager.SessionState.Status.CONNECTED) {
            return ToolResult(toolCall.id, "Session \"$sessionId\" is not connected.", isError = true)
        }

        // Check permission mode
        val permissionMode = settingsRepository.getPermissionMode()
        if (permissionMode == PermissionMode.OBSERVER) {
            return ToolResult(
                toolCall.id,
                "Observer mode: command execution is disabled. Switch to Confirm or Auto mode.",
                isError = true
            )
        }

        // Check command safety (blacklist)
        val safetyResult = CommandSecurityChecker.checkCommand(command)
        if (safetyResult.isBlocked) {
            return ToolResult(
                toolCall.id,
                "Command blocked by safety policy. Matched pattern: ${safetyResult.matchedPattern}",
                isError = true
            )
        }

        return try {
            val output = executeCommandAndCaptureOutput(sessionId, command)
            val formatted = formatToolResult(output.stdout, output.stderr, output.exitCode)
            ToolResult(toolCall.id, formatted)
        } catch (e: Exception) {
            Log.e(TAG, "executeTerminalCommand failed: $command", e)
            ToolResult(toolCall.id, "Execution failed: ${e.message}", isError = true)
        }
    }

    /**
     * Execute command and capture output.
     *
     * Strategy (aligned with Netcatty):
     * 1. Record baseline scrollback size
     * 2. Send command (append "\n" to submit)
     * 3. Poll for new output
     * 4. Wait for quiet period (no new output for 1.5s)
     * 5. Read scrollback tail and extract new output
     */
    private suspend fun executeCommandAndCaptureOutput(
        sessionId: String,
        command: String
    ): CommandOutput = withContext(Dispatchers.IO) {

        // Step 1: Record baseline
        val baselineBytes = sshSessionManager.agentScrollbackTotalBytes(sessionId) ?: 0L

        // Step 2: Send command
        Log.d(TAG, "Sending command to $sessionId: $command")
        sshSessionManager.sendInput(sessionId, command + "\n")

        // Step 3: Poll for output
        val startTime = System.currentTimeMillis()
        var lastTotalBytes = baselineBytes
        var lastNewOutputTime = System.currentTimeMillis()
        var commandCompleted = false

        while (System.currentTimeMillis() - startTime < COMMAND_TIMEOUT_MS) {
            delay(POLL_INTERVAL_MS)

            val currentTotalBytes = sshSessionManager.agentScrollbackTotalBytes(sessionId) ?: baselineBytes

            if (currentTotalBytes > lastTotalBytes) {
                // New output arrived
                lastNewOutputTime = System.currentTimeMillis()
                lastTotalBytes = currentTotalBytes
            } else {
                // No new output - check if quiet period reached
                if (System.currentTimeMillis() - lastNewOutputTime > QUIET_THRESHOLD_MS) {
                    commandCompleted = true
                    break
                }
            }
        }

        // Step 4: Read scrollback and extract output
        val scrollbackBytes = sshSessionManager.readAgentScrollback(sessionId, MAX_SCROLLBACK_BYTES)
        val fullOutput = if (scrollbackBytes != null) {
            String(scrollbackBytes, StandardCharsets.UTF_8)
        } else {
            ""
        }

        // Extract new output (everything after baseline)
        // Since we only have the tail, we return the full tail and let the AI parse it
        // The AI is smart enough to ignore old output and focus on new results
        val stdout = fullOutput.trim()
        val stderr = ""  // SSH doesn't separate stdout/stderr in scrollback
        val exitCode = 0  // Exit code unavailable from scrollback

        Log.d(TAG, "Command output (${stdout.length} chars): ${stdout.take(200)}")

        CommandOutput(stdout, stderr, exitCode)
    }

    /**
     * Command execution result.
     */
    private data class CommandOutput(
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    )

    /**
     * Execute workspace_get_info tool.
     */
    private fun executeWorkspaceGetInfo(toolCall: ToolCall): ToolResult {
        val sessions = sessionRegistry.getActiveSessions()
        val sessionInfo = sessions.joinToString("\n") { session ->
            "- ${session.sessionId}: ${session.hostname} (${session.label}) - ${if (session.isConnected) "connected" else "disconnected"}"
        }
        val content = "Workspace Info:\nActive sessions: ${sessions.size}\nSessions:\n$sessionInfo"
        return ToolResult(toolCall.id, content)
    }

    /**
     * Execute workspace_get_session_info tool.
     */
    private fun executeWorkspaceGetSessionInfo(toolCall: ToolCall): ToolResult {
        val sessionId = toolCall.arguments["sessionId"] as? String ?: ""
        val sessions = sessionRegistry.getActiveSessions()
        val session = sessions.find { it.sessionId == sessionId }

        if (session == null) {
            return ToolResult(toolCall.id, "Session not found: $sessionId", isError = true)
        }

        val content = "Session Info:\n" +
            "Session ID: ${session.sessionId}\n" +
            "Host: ${session.hostname}\n" +
            "Label: ${session.label}\n" +
            "OS: ${session.os ?: "unknown"}\n" +
            "Username: ${session.username ?: "unknown"}\n" +
            "Status: ${if (session.isConnected) "connected" else "disconnected"}"

        return ToolResult(toolCall.id, content)
    }

    /**
     * Execute web_search tool (placeholder).
     */
    private fun executeWebSearch(toolCall: ToolCall): ToolResult {
        val query = toolCall.arguments["query"] as? String ?: ""
        return ToolResult(toolCall.id, "Web search is not yet implemented. Query: $query")
    }

    /**
     * Execute url_fetch tool (placeholder).
     */
    private fun executeUrlFetch(toolCall: ToolCall): ToolResult {
        val url = toolCall.arguments["url"] as? String ?: ""
        return ToolResult(toolCall.id, "URL fetch is not yet implemented. URL: $url")
    }

    /**
     * Build API messages (OpenAI format).
     */
    private fun buildApiMessages(): List<ChatMessage> {
        val systemPrompt = SystemPromptBuilder.build(
            SystemPromptContext(
                scopeType = ScopeType.TERMINAL,
                scopeLabel = "Haven SSH Terminal",
                hosts = sessionRegistry.getActiveSessions().map {
                    HostInfo(it.sessionId, it.hostname, it.label, it.isConnected)
                },
                permissionMode = settingsRepository.getPermissionMode()
            )
        )

        val messages = mutableListOf(ChatMessage(role = "system", content = systemPrompt))
        messages.addAll(_messages.takeLast(20))
        return messages
    }

    /**
     * Update assistant message in UI (streaming).
     */
    private fun updateAssistantMessage(content: String) {
        val messages = _uiState.value.messages.toMutableList()
        val lastMsg = messages.lastOrNull()
        if (lastMsg?.role == "assistant" && lastMsg.toolCalls == null) {
            messages[messages.size - 1] = lastMsg.copy(content = content)
        } else {
            messages.add(ChatMessage(role = "assistant", content = content))
        }
        _uiState.update { it.copy(messages = messages) }
    }

    /**
     * Add assistant message to UI.
     */
    private fun addAssistantMessage(content: String) {
        _messages.add(ChatMessage(role = "assistant", content = content))
        _uiState.update { it.copy(messages = _messages.toList(), isAiThinking = false) }
    }

    fun clearChat() {
        _messages.clear()
        _uiState.update { AiChatUiState() }
    }

    fun openSettings() {
        // TODO: Navigate to settings
    }
}
