package sh.haven.feature.agent.model

import androidx.compose.runtime.Immutable

/**
 * One rendered row in the agent chat list. This is the UI-facing
 * representation — distinct from [ChatMessage] (the wire shape sent to
 * the LLM) because a single assistant turn may produce text deltas,
 * tool calls, and tool results that the UI wants to render as separate
 * cards, while the LLM only needs the final consolidated message.
 */
@Immutable
sealed interface AgentChatItem {
    val id: String

    /** A user-authored message. */
    data class User(override val id: String, val text: String) : AgentChatItem

    /** An assistant text reply (possibly streaming). */
    data class Assistant(
        override val id: String,
        var text: String,
        var thinking: String = "",
        var isStreaming: Boolean = false,
    ) : AgentChatItem

    /** A tool call + its result, rendered as one card. */
    data class ToolCall(
        override val id: String,
        val toolCallId: String,
        val name: String,
        val arguments: String,
        var result: String = "",
        var isError: Boolean = false,
        var status: ToolCallStatus = ToolCallStatus.PENDING_APPROVAL,
    ) : AgentChatItem

    /** A transient error banner. */
    data class Error(override val id: String, val text: String) : AgentChatItem
}

enum class ToolCallStatus {
    /** Waiting for user approval (confirm mode). */
    PENDING_APPROVAL,

    /** User denied the call. */
    DENIED,

    /** Running. */
    RUNNING,

    /** Completed with a result. */
    COMPLETED,
}
