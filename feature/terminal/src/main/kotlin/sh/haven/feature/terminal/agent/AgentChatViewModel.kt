package sh.haven.feature.terminal.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AgentChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val inputText: String = "",
    val isThinking: Boolean = false,
    val isSending: Boolean = false,
    val error: String? = null
)

data class UiMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isCode: Boolean = false,
    val commandOutput: String? = null
)

enum class MessageRole {
    USER, ASSISTANT, SYSTEM
}

@HiltViewModel
class AgentChatViewModel @Inject constructor(
    private val aiApiService: AiApiService,
    private val mcpToolsExecutor: McpToolsExecutor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentChatUiState())
    val uiState: StateFlow<AgentChatUiState> = _uiState.asStateFlow()

    init {
        // Add welcome message
        addMessage(
            role = MessageRole.SYSTEM,
            content = "Hello! I'm your Haven AI assistant. I can help you manage servers using natural language. What would you like to do?"
        )
    }

    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val inputText = _uiState.value.inputText.trim()
        if (inputText.isBlank() || _uiState.value.isSending) return

        // Add user message
        addMessage(role = MessageRole.USER, content = inputText)
        _uiState.update { it.copy(inputText = "", isSending = true, isThinking = true) }

        viewModelScope.launch {
            try {
                // Process with AI
                val response = aiApiService.processUserRequest(
                    userMessage = inputText,
                    conversationHistory = _uiState.value.messages
                )

                // Parse response and execute tools if needed
                val (assistantMessage, toolOutputs) = processAiResponse(response)

                // Add assistant message
                addMessage(
                    role = MessageRole.ASSISTANT,
                    content = assistantMessage,
                    commandOutput = toolOutputs?.takeIf { it.isNotBlank() }
                )

            } catch (e: Exception) {
                addMessage(
                    role = MessageRole.SYSTEM,
                    content = "Error: ${e.message ?: "Unknown error occurred"}"
                )
            } finally {
                _uiState.update { it.copy(isSending = false, isThinking = false) }
            }
        }
    }

    private suspend fun processAiResponse(response: String): Pair<String, String?> {
        // Check if AI response contains tool calls
        // This is a simplified version - in reality, you'd parse structured tool calls
        // For now, assume AI returns text that might include code blocks

        return if (response.contains("```bash") || response.contains("```sh")) {
            // Extract command and execute
            val command = extractCommand(response)
            if (command != null) {
                val output = mcpToolsExecutor.executeCommand(command)
                Pair(response, output)
            } else {
                Pair(response, null)
            }
        } else {
            Pair(response, null)
        }
    }

    private fun extractCommand(response: String): String? {
        // Simple extraction - look for bash/sh code blocks
        val regex = "```(?:bash|sh)\n([\\s\\S]*?)```".toRegex()
        return regex.find(response)?.groupValues?.get(1)?.trim()
    }

    fun clearChat() {
        _uiState.update {
            AgentChatUiState(
                messages = listOf(
                    UiMessage(
                        role = MessageRole.SYSTEM,
                        content = "Chat cleared. How can I help you?"
                    )
                )
            )
        }
    }

    private fun addMessage(
        role: MessageRole,
        content: String,
        commandOutput: String? = null
    ) {
        val message = UiMessage(
            role = role,
            content = content,
            isCode = content.contains("```"),
            commandOutput = commandOutput
        )
        _uiState.update { it.copy(messages = it.messages + message) }
    }
}
