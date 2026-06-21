package sh.haven.feature.agent.provider

import sh.haven.feature.agent.model.ChatMessage
import sh.haven.feature.agent.model.ToolCall
import sh.haven.feature.agent.model.ToolDefinition

/**
 * One streaming chunk emitted by the LLM. The agent loop collects these
 * into assistant messages and tool-call dispatches.
 */
sealed interface LlmStreamChunk {
    /** A text delta (partial assistant reply). */
    data class TextDelta(val text: String) : LlmStreamChunk

    /** The model emitted a (possibly partial) tool call. */
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val argumentsDelta: String = "",
    ) : LlmStreamChunk

    /** Stream finished normally. */
    data object Done : LlmStreamChunk

    /** An error mid-stream. */
    data class Error(val message: String) : LlmStreamChunk
}

/**
 * Result of a completed (non-streaming portion of a) LLM turn: the
 * assembled assistant text + any tool calls. The streaming chunks are
 * an implementation detail of how we get here.
 */
data class LlmTurnResult(
    val text: String,
    val toolCalls: List<ToolCall>,
)

/** Configuration for a single LLM request. */
data class LlmRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>,
    val maxIterations: Int,
)

/**
 * Abstraction over an OpenAI-compatible Chat Completions endpoint.
 * The default implementation ([OpenAiCompatibleClient]) talks to any
 * provider that speaks the `POST /chat/completions` wire format with
 * `stream: true` and `tools`/`tool_choice` function-calling.
 */
interface LlmClient {
    /**
     * Stream a single LLM turn. Calls [onChunk] for each
     * [LlmStreamChunk] as it arrives (text deltas, tool-call deltas,
     * done, error). Returns the assembled [LlmTurnResult].
     *
     * Cancellation: the caller may cancel the coroutine — the
     * underlying HTTP connection is closed and the call aborts.
     */
    suspend fun streamTurn(
        request: LlmRequest,
        onChunk: (LlmStreamChunk) -> Unit,
    ): LlmTurnResult
}
