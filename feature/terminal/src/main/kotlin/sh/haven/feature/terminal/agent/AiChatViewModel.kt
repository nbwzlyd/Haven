package sh.haven.feature.terminal.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.haven.feature.terminal.agent.model.*
import sh.haven.feature.terminal.agent.network.*
import sh.haven.feature.terminal.agent.prompt.*
import sh.haven.feature.terminal.agent.security.CommandSecurityChecker
import sh.haven.feature.terminal.agent.storage.AiSettingsRepository
import javax.inject.Inject

/**
 * AI 聊天 ViewModel（连接所有 AI Agent 组件）
 *
 * 功能：
 * 1. 管理聊天消息列表
 * 2. 调用 AI API 并流式接收响应
 * 3. 处理 Tool Calling（命令执行）
 * 4. 管理会话上下文
 * 5. 处理权限模式和命令安全检查
 */
class AiChatViewModel @Inject constructor(
    private val settingsRepository: AiSettingsRepository,
    private val streamingClient: OpenAiStreamingClient,
    private val securityChecker: CommandSecurityChecker,
    private val terminalSessionRegistry: TerminalSessionRegistry,
) : ViewModel() {

    companion object {
        private const val TAG = "AiChatViewModel"
        private const val MAX_CONVERSATION_TOKENS = 128_000 // 128K tokens
    }

    // UI 状态
    private val _uiState = MutableStateFlow(AiChatUiState())
    val uiState: StateFlow<AiChatUiState> = _uiState.asStateFlow()

    // 消息列表
    private val _messages = MutableStateFlow<List<AiMessage>>(emptyList())
    val messages: StateFlow<List<AiMessage>> = _messages.asStateFlow()

    // 对话历史（用于构建上下文）
    private val _conversationHistory = MutableStateFlow<List<ChatMessage>>(emptyList())

    init {
        // 添加欢迎消息
        addMessage(
            role = MessageRole.SYSTEM,
            content = "Hello! I'm Haven Agent. I can help you manage servers using natural language. What would you like to do?"
        )
    }

    /**
     * 发送用户消息
     */
    fun sendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isLoading) return

        // 1. 添加用户消息到列表
        addMessage(role = MessageRole.USER, content = text)
        _uiState.update { it.copy(isLoading = true, inputText = "") }

        // 2. 获取活跃提供商配置
        viewModelScope.launch {
            try {
                val provider = settingsRepository.getActiveProvider()
                if (provider == null) {
                    addMessage(
                        role = MessageRole.SYSTEM,
                        content = "Error: No AI provider configured. Please add a provider in Settings."
                    )
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }

                // 3. 构建系统提示词
                val systemPrompt = buildSystemPrompt()

                // 4. 构建消息列表
                val messages = buildMessages(systemPrompt, text)

                // 5. 调用 AI API（流式）
                streamingClient.sendMessage(
                    config = provider,
                    messages = messages
                ).collect { event ->
                    handleStreamEvent(event)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                addMessage(
                    role = MessageRole.SYSTEM,
                    content = "Error: ${e.message ?: "Unknown error"}"
                )
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * 处理流式事件
     */
    private suspend fun handleStreamEvent(event: ChatStreamEvent) {
        when (event) {
            is ChatStreamEvent.Text -> {
                // 追加文本内容到当前助手消息
                appendToAssistantMessage(event.content)
            }
            is ChatStreamEvent.ToolCall -> {
                // 处理 Tool Call（命令执行）
                handleToolCall(event)
            }
            is ChatStreamEvent.Error -> {
                addMessage(
                    role = MessageRole.SYSTEM,
                    content = "Error (${event.type}): ${event.message}"
                )
                _uiState.update { it.copy(isLoading = false) }
            }
            is ChatStreamEvent.Done -> {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * 处理 Tool Call（命令执行）
     */
    private suspend fun handleToolCall(toolCall: ChatStreamEvent.ToolCall) {
        when (toolCall.name) {
            "terminal_execute" -> {
                // 解析命令参数
                val args = parseJsonArguments(toolCall.arguments)
                val command = args["command"] as? String ?: return

                // 安全检查
                val permissionMode = settingsRepository.permissionMode.value
                val checkResult = securityChecker.checkCommand(command, permissionMode)

                if (!checkResult.allowed) {
                    // 命令被拒绝
                    addMessage(
                        role = MessageRole.ASSISTANT,
                        content = "Command blocked: ${checkResult.reason}"
                    )
                    return
                }

                // 根据权限模式处理
                when (permissionMode) {
                    CommandSecurityChecker.PermissionMode.OBSERVER -> {
                        // Observer 模式：仅展示命令，不执行
                        addMessage(
                            role = MessageRole.ASSISTANT,
                            content = "Command (read-only preview):\n```bash\n$command\n```"
                        )
                    }
                    CommandSecurityChecker.PermissionMode.CONFIRM -> {
                        // Confirm 模式：需要用户审批
                        _uiState.update {
                            it.copy(pendingApproval = PendingApproval(
                                toolCallId = toolCall.id,
                                command = command,
                                reason = checkResult.reason
                            ))
                        }
                    }
                    CommandSecurityChecker.PermissionMode.AUTONOMOUS -> {
                        // Autonomous 模式：自动执行
                        executeCommand(toolCall.id, command)
                    }
                }
            }
        }
    }

    /**
     * 执行命令
     */
    private suspend fun executeCommand(toolCallId: String, command: String) {
        try {
            // 获取当前活跃会话
            val sessionId = terminalSessionRegistry.getActiveSessionId()
            if (sessionId == null) {
                addMessage(
                    role = MessageRole.SYSTEM,
                    content = "Error: No active terminal session. Please connect to a server first."
                )
                return
            }

            // 执行命令（通过 Haven 的终端执行机制）
            // TODO: 集成 Haven 的 TerminalViewModel 执行命令
            val output = "Command execution not yet implemented.\nCommand: $command\nSession: $sessionId"

            // 添加命令执行结果到消息列表
            addMessage(
                role = MessageRole.ASSISTANT,
                content = "Command executed:\n```bash\n$command\n```\n\nOutput:\n```\n$output\n```"
            )

            // 将输出添加到对话历史，供 AI 继续分析
            addToConversationHistory(
                role = "tool",
                content = output,
                toolCallId = toolCallId
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command: $command", e)
            addMessage(
                role = MessageRole.SYSTEM,
                content = "Error executing command: ${e.message}"
            )
        }
    }

    /**
     * 用户批准命令执行
     */
    fun approveCommand() {
        val pending = _uiState.value.pendingApproval ?: return
        viewModelScope.launch {
            executeCommand(pending.toolCallId, pending.command)
            _uiState.update { it.copy(pendingApproval = null) }
        }
    }

    /**
     * 用户拒绝命令执行
     */
    fun denyCommand() {
        val pending = _uiState.value.pendingApproval ?: return
        addMessage(
            role = MessageRole.SYSTEM,
            content = "Command execution denied by user."
        )
        _uiState.update { it.copy(pendingApproval = null) }
    }

    /**
     * 构建系统提示词
     */
    private fun buildSystemPrompt(): String {
        val sessions = terminalSessionRegistry.getActiveSessions()
        val hosts = sessions.map { session ->
            HostInfo(
                sessionId = session.id,
                hostname = session.hostname ?: "localhost",
                label = session.label ?: session.id,
                os = session.os,
                username = session.username,
                protocol = session.protocol,
                shellType = session.shellType,
                deviceType = session.deviceType,
                connected = session.isConnected
            )
        }

        val context = SystemPromptContext(
            scopeType = ScopeType.TERMINAL,
            scopeLabel = sessions.firstOrNull()?.label,
            hosts = hosts,
            permissionMode = convertPermissionMode(settingsRepository.permissionMode.value)
        )

        return SystemPromptBuilder.build(context)
    }

    /**
     * 构建消息列表
     */
    private fun buildMessages(systemPrompt: String, userMessage: String): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        // 系统提示词
        messages.add(ChatMessage(role = "system", content = systemPrompt))

        // 对话历史（最近 10 条）
        val history = _conversationHistory.value.takeLast(10)
        messages.addAll(history)

        // 当前用户消息
        messages.add(ChatMessage(role = "user", content = userMessage))

        return messages
    }

    /**
     * 添加消息到列表
     */
    private fun addMessage(role: MessageRole, content: String) {
        val message = AiMessage(
            id = generateId(),
            role = role,
            content = content,
            timestamp = System.currentTimeMillis()
        )
        _messages.update { it + message }

        // 添加到对话历史
        addToConversationHistory(role = roleToStr(role), content = content)
    }

    /**
     * 追加内容到当前助手消息
     */
    private fun appendToAssistantMessage(content: String) {
        val current = _messages.value.toMutableList()
        if (current.isEmpty() || current.last().role != MessageRole.ASSISTANT) {
            // 创建新的助手消息
            current.add(
                AiMessage(
                    id = generateId(),
                    role = MessageRole.ASSISTANT,
                    content = content,
                    timestamp = System.currentTimeMillis()
                )
            )
        } else {
            // 追加到最后一个助手消息
            val last = current.last()
            current[current.size - 1] = last.copy(content = last.content + content)
        }
        _messages.value = current
    }

    /**
     * 添加到对话历史
     */
    private fun addToConversationHistory(role: String, content: String, toolCallId: String? = null) {
        val message = ChatMessage(
            role = role,
            content = content,
            toolCallId = toolCallId
        )
        _conversationHistory.update { it + message }

        // 限制历史长度（避免超出上下文窗口）
        val history = _conversationHistory.value
        if (history.size > 50) {
            _conversationHistory.value = history.takeLast(50)
        }
    }

    /**
     * 清空聊天记录
     */
    fun clearChat() {
        _messages.value = emptyList()
        _conversationHistory.value = emptyList()
        addMessage(
            role = MessageRole.SYSTEM,
            content = "Chat cleared. How can I help you?"
        )
    }

    /**
     * 更新输入文本
     */
    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    /**
     * 解析 JSON 参数
     */
    private fun parseJsonArguments(json: String): Map<String, Any> {
        return try {
            val obj = org.json.JSONObject(json)
            val map = mutableMapOf<String, Any>()
            obj.keys().forEach { key ->
                map[key] = obj.get(key)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * 转换权限模式
     */
    private fun convertPermissionMode(mode: CommandSecurityChecker.PermissionMode): PermissionMode {
        return when (mode) {
            CommandSecurityChecker.PermissionMode.OBSERVER -> PermissionMode.OBSERVER
            CommandSecurityChecker.PermissionMode.CONFIRM -> PermissionMode.CONFIRM
            CommandSecurityChecker.PermissionMode.AUTONOMOUS -> PermissionMode.AUTONOMOUS
        }
    }

    /**
     * 生成唯一 ID
     */
    private fun generateId(): String {
        return java.util.UUID.randomUUID().toString()
    }

    /**
     * 转换角色枚举到字符串
     */
    private fun roleToStr(role: MessageRole): String {
        return when (role) {
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            MessageRole.SYSTEM -> "system"
        }
    }
}

/**
 * UI 状态
 */
data class AiChatUiState(
    val inputText: String = "",
    val isLoading: Boolean = false,
    val pendingApproval: PendingApproval? = null,
    val error: String? = null
)

/**
 * 待审批命令
 */
data class PendingApproval(
    val toolCallId: String,
    val command: String,
    val reason: String? = null
)

/**
 * AI 消息
 */
data class AiMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val isCode: Boolean = false,
    val commandOutput: String? = null
)

/**
 * 消息角色
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}
