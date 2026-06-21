package sh.haven.feature.agent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.feature.agent.core.AgentUiCallbacks
import sh.haven.feature.agent.core.ApprovalGate
import sh.haven.feature.agent.core.CattyAgent
import sh.haven.feature.agent.model.AgentChatItem
import sh.haven.feature.agent.model.AgentConfig
import sh.haven.feature.agent.model.ChatMessage
import sh.haven.feature.agent.model.ToolCall
import sh.haven.feature.agent.model.ToolCallStatus
import sh.haven.feature.agent.model.ToolResult
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for the Catty Agent chat screen. Holds the chat item list
 * (the UI-facing representation) and the conversation history (the
 * wire shape sent to the LLM), and drives the [CattyAgent] loop.
 *
 * The chat items and the LLM history are kept in parallel: every
 * assistant text message and tool call in the UI has a corresponding
 * entry in [history], so a turn can be replayed into the next LLM
 * request without translation.
 */
@HiltViewModel
class AgentChatViewModel @Inject constructor(
    private val cattyAgent: CattyAgent,
    private val approvalGate: ApprovalGate,
    private val preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    /** UI-facing chat items (user messages, assistant replies, tool cards). */
    private val _items = MutableStateFlow<List<AgentChatItem>>(emptyList())
    val items: StateFlow<List<AgentChatItem>> = _items.asStateFlow()

    /** LLM conversation history (wire shape). */
    private val history = mutableListOf<ChatMessage>()

    /** True while the agent is processing a turn. */
    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    /** The current agent configuration, reactively updated from preferences. */
    val config: StateFlow<AgentConfig> = combine(
        preferencesRepository.cattyAgentApiKey,
        preferencesRepository.cattyAgentBaseUrl,
        preferencesRepository.cattyAgentModel,
        preferencesRepository.cattyAgentPermissionMode,
    ) { apiKey, baseUrl, model, mode ->
        AgentConfigPartial(apiKey, baseUrl, model, mode)
    }.combine(
        preferencesRepository.cattyAgentMaxIterations,
        preferencesRepository.cattyAgentCommandTimeout,
    ) { partial, maxIter, timeout ->
        AgentConfig(
            apiKey = partial.apiKey,
            baseUrl = partial.baseUrl,
            model = partial.model,
            permissionMode = partial.permissionMode,
            maxIterations = maxIter,
            commandTimeoutSeconds = timeout,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentConfig("", "", "", UserPreferencesRepository.CattyAgentPermissionMode.CONFIRM, 20, 60))

    /** Intermediate holder for the first 4 config flows (combine caps at 5). */
    private data class AgentConfigPartial(
        val apiKey: String,
        val baseUrl: String,
        val model: String,
        val permissionMode: UserPreferencesRepository.CattyAgentPermissionMode,
    )

    /** The active turn job, so we can cancel it. */
    private var turnJob: Job? = null

    /** Send a user message and run the agent turn. */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isBusy.value) return

        val userItem = AgentChatItem.User(id = UUID.randomUUID().toString(), text = trimmed)
        _items.value = _items.value + userItem

        _isBusy.value = true
        turnJob = viewModelScope.launch {
            val callbacks = object : AgentUiCallbacks {
                override fun onAssistantTextDelta(itemId: String, delta: String) {
                    _items.value = _items.value.map { item ->
                        if (item is AgentChatItem.Assistant && item.id == itemId) {
                            item.copy(text = item.text + delta, isStreaming = true)
                        } else item
                    }
                }

                override fun onToolCallStarted(itemId: String, toolCall: ToolCall) {
                    val toolItem = AgentChatItem.ToolCall(
                        id = itemId,
                        toolCallId = toolCall.id,
                        name = toolCall.function.name,
                        arguments = toolCall.function.arguments,
                        status = ToolCallStatus.RUNNING,
                    )
                    _items.value = _items.value + toolItem
                }

                override fun onToolCallNeedsApproval(itemId: String, toolCall: ToolCall, summary: String) {
                    _items.value = _items.value.map { item ->
                        if (item is AgentChatItem.ToolCall && item.id == itemId) {
                            item.copy(status = ToolCallStatus.PENDING_APPROVAL, result = summary)
                        } else item
                    }
                }

                override fun onToolCallStatusChanged(itemId: String, status: ToolCallStatus) {
                    _items.value = _items.value.map { item ->
                        if (item is AgentChatItem.ToolCall && item.id == itemId) {
                            item.copy(status = status)
                        } else item
                    }
                }

                override fun onToolCallResult(itemId: String, result: ToolResult) {
                    _items.value = _items.value.map { item ->
                        if (item is AgentChatItem.ToolCall && item.id == itemId) {
                            item.copy(
                                result = result.content,
                                isError = result.isError,
                                status = ToolCallStatus.COMPLETED,
                            )
                        } else item
                    }
                }

                override fun onAssistantMessageFinished(itemId: String) {
                    _items.value = _items.value.map { item ->
                        if (item is AgentChatItem.Assistant && item.id == itemId) {
                            item.copy(isStreaming = false)
                        } else item
                    }
                }

                override fun onError(message: String) {
                    _items.value = _items.value + AgentChatItem.Error(
                        id = UUID.randomUUID().toString(),
                        text = message,
                    )
                }

                override fun onTurnFinished() {
                    _isBusy.value = false
                }

                override fun onTurnAborted() {
                    _isBusy.value = false
                }
            }

            // Ensure there's an assistant item to stream into before the turn starts.
            // The agent loop creates the real itemId, but we pre-create a placeholder
            // that onAssistantTextDelta will target. We handle this by having the
            // callbacks create the assistant item on the first text delta.
            val updatedHistory = cattyAgent.runTurn(trimmed, history.toList(), config.value, object : AgentUiCallbacks by callbacks {
                override fun onAssistantTextDelta(itemId: String, delta: String) {
                    // Create the assistant item on first delta if it doesn't exist.
                    val exists = _items.value.any { it.id == itemId }
                    if (!exists) {
                        _items.value = _items.value + AgentChatItem.Assistant(
                            id = itemId,
                            text = "",
                            isStreaming = true,
                        )
                    }
                    callbacks.onAssistantTextDelta(itemId, delta)
                }

                override fun onAssistantMessageFinished(itemId: String) {
                    // If the assistant produced no text (pure tool-call turn), still
                    // create an empty item so the UI shows the turn happened.
                    val exists = _items.value.any { it.id == itemId }
                    if (!exists) {
                        _items.value = _items.value + AgentChatItem.Assistant(
                            id = itemId,
                            text = "",
                            isStreaming = false,
                        )
                    }
                    callbacks.onAssistantMessageFinished(itemId)
                }
            })
            history.clear()
            history.addAll(updatedHistory)
            _isBusy.value = false
        }
    }

    /** Approve a pending tool call. */
    fun approveToolCall(itemId: String) {
        val item = _items.value.find { it.id == itemId } as? AgentChatItem.ToolCall ?: return
        approvalGate.resolve(item.toolCallId, approved = true)
    }

    /** Deny a pending tool call. */
    fun denyToolCall(itemId: String) {
        val item = _items.value.find { it.id == itemId } as? AgentChatItem.ToolCall ?: return
        approvalGate.resolve(item.toolCallId, approved = false)
        _items.value = _items.value.map {
            if (it.id == itemId) (it as AgentChatItem.ToolCall).copy(status = ToolCallStatus.DENIED) else it
        }
    }

    /** Cancel the active turn. */
    fun stopTurn() {
        turnJob?.cancel()
        turnJob = null
        _isBusy.value = false
    }

    /** Clear the conversation. */
    fun clearConversation() {
        if (_isBusy.value) stopTurn()
        _items.value = emptyList()
        history.clear()
    }
}
