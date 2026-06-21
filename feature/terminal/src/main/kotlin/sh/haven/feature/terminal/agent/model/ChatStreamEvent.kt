package sh.haven.feature.terminal.agent.model

/**
 * Chat Stream Event (aligned with OpenAI streaming format).
 */
sealed class ChatStreamEvent {
    /**
     * Text delta (streamed content).
     */
    data class TextDelta(val delta: String) : ChatStreamEvent()

    /**
     * Thinking delta (for reasoning models like DeepSeek-R1).
     */
    data class ThinkingDelta(val delta: String) : ChatStreamEvent()

    /**
     * Tool call (function calling).
     *
     * Note: OpenAI streams tool_calls in chunks.
     * The client accumulates them and emits this event when complete.
     */
    data class ToolCall(val toolCalls: List<ToolCall>) : ChatStreamEvent()

    /**
     * Stream complete.
     */
    object Done : ChatStreamEvent()

    /**
     * Error.
     */
    data class Error(val message: String) : ChatStreamEvent()
}
