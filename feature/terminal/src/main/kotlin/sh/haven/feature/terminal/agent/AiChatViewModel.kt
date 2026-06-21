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
import org.json.JSONObject
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
 * Key differences from previous version:
 * 1. Uses 5 tools (same as Netcatty)
 * 2. Correctly handles OpenAI function calling format
 * 3. Formats tool results as "STDOUT:/STDERR:/Exit code:" (aligned with Netcatty)
 * 4. Manages multi-turn tool calling loop
 */
@HiltViewModel
class AiChatViewModel @Inject constructor(
    private val settingsRepository: AiSettingsRepository,
    private val encryptedPrefs: EncryptedPreferenceStore,
    private val sessionRegistry: TerminalSessionRegistry
) : ViewModel() {

    companion object {
        private const val TAG = "AiChatViewModel"
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
                // Add user message
                val userMsg = ChatMessage(role = "user", content = userMessage)
                _messages.add(userMsg)
                _uiState.update { it.copy(messages = _messages.toList(), isAiThinking = true) }

                // Get AI config
                val config = settingsRepository.getActiveConfig()
                if (config == null) {
                    addAssistantMessage("Please configure an AI provider in Settings first.")
                    return@launch
                }

                // Build messages for API (OpenAI format)
                val apiMessages = buildApiMessages()

                // Send to AI (with tools)
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
                            // Add assistant message (with tool calls)
                            val assistantMsg = ChatMessage(
                                role = "assistant",
                                content = assistantResponse.toString().ifBlank { null },
                                toolCalls = if (toolCallsAccumulated.isNotEmpty()) toolCallsAccumulated else null
                            )
                            _messages.add(assistantMsg)

                            // If there are tool calls, execute them
                            if (toolCallsAccumulated.isNotEmpty()) {
                                handleToolCalls(toolCallsAccumulated, config)
                            } else {
                                // No tool calls, just text response
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
     * Handle tool calls (execute tools and send results back to AI).
     */
    private suspend fun handleToolCalls(toolCalls: List<ToolCall>, config: AiProviderConfig) {
        val toolResults = mutableListOf<ToolResult>()

        for (toolCall in toolCalls) {
            val result = executeTool(toolCall)
            toolResults.add(result)
        }

        // Add tool messages to conversation
        for (toolResult in toolResults) {
            val toolMsg = ChatMessage(
                role = "tool",
                content = toolResult.content,
                toolCallId = toolResult.toolCallId
            )
            _messages.add(toolMsg)
        }

        // Send updated conversation back to AI (continue tool calling loop)
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
                    // Add assistant message
                    val assistantMsg = ChatMessage(
                        role = "assistant",
                        content = assistantResponse.toString().ifBlank { null },
                        toolCalls = if (toolCallsAccumulated.isNotEmpty()) toolCallsAccumulated else null
                    )
                    _messages.add(assistantMsg)

                    // If there are more tool calls, execute them
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
     * Execute terminal command (aligned with Netcatty's executeTerminalExecute).
     */
    private suspend fun executeTerminalCommand(toolCall: ToolCall): ToolResult {
        val sessionId = toolCall.arguments["sessionId"] as? String ?: ""
        val command = toolCall.arguments["command"] as? String ?: ""

        if (sessionId.isBlank() || command.isBlank()) {
            return ToolResult(toolCall.id, "Missing sessionId or command", isError = true)
        }

        // Check session scope
        val sessions = sessionRegistry.getActiveSessions()
        if (!sessions.any { it.sessionId == sessionId }) {
            return ToolResult(
                toolCall.id,
                "Session \"$sessionId\" is not in the current scope. Available sessions: ${sessions.joinToString(", ") { it.sessionId }}",
                isError = true
            )
        }

        // Check permission mode
        val permissionMode = settingsRepository.getPermissionMode()
        if (permissionMode == PermissionMode.OBSERVER) {
            return ToolResult(
                toolCall.id,
                "Observer mode: command execution is disabled. Switch to Confirm or Auto mode to execute commands.",
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

        // TODO: Execute command via TerminalViewModel
        // For now, return placeholder result
        val stdout = "Command executed: $command\n(Execution not yet implemented)"
        val stderr = ""
        val exitCode = 0

        val formattedResult = formatToolResult(stdout, stderr, exitCode)
        return ToolResult(toolCall.id, formattedResult)
    }

    /**
     * Execute workspace_get_info tool.
     */
    private fun executeWorkspaceGetInfo(toolCall: ToolCall): ToolResult {
        val sessions = sessionRegistry.getActiveSessions()
        val sessionInfo = sessions.joinToString("\n") { session ->
            "- ${session.sessionId}: ${session.hostname} (${session.label}) - ${if (session.isConnected) "connected" else "disconnected"}"
        }

        val content = "Workspace Info:\n" +
            "Active sessions: ${sessions.size}\n" +
            "Sessions:\n$sessionInfo"

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
        return ToolResult(
            toolCall.id,
            "Web search is not yet implemented. Query: $query"
        )
    }

    /**
     * Execute url_fetch tool (placeholder).
     */
    private fun executeUrlFetch(toolCall: ToolCall): ToolResult {
        val url = toolCall.arguments["url"] as? String ?: ""
        return ToolResult(
            toolCall.id,
            "URL fetch is not yet implemented. URL: $url"
        )
    }

    /**
     * Build API messages (OpenAI format).
     */
    private fun buildApiMessages(): List<ChatMessage> {
        // Always start with system prompt
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

        // Add conversation history (last 20 messages)
        val history = _messages.takeLast(20)
        messages.addAll(history)

        return messages
    }

    /**
     * Update assistant message in UI (streaming).
     */
    private fun updateAssistantMessage(content: String) {
        val messages = _uiState.value.messages.toMutableList()

        // Find or create assistant message
        val lastMsg = messages.lastOrNull()
        if (lastMsg?.role == "assistant" && lastMsg.toolCalls == null) {
            // Update existing assistant message
            messages[messages.size - 1] = lastMsg.copy(content = content)
        } else {
            // Add new assistant message
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
