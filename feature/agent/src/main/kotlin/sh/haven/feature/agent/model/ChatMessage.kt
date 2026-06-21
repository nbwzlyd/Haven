package sh.haven.feature.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * One message in a Catty Agent chat conversation. Mirrors the OpenAI
 * Chat Completions message shape so the history can be replayed directly
 * into the next request without translation.
 *
 * @property role "system" | "user" | "assistant" | "tool"
 * @property content Text content (may be empty when the message is purely
 *   a tool-call assistant turn).
 * @property toolCalls Tool calls emitted by the assistant. Null for
 *   non-assistant messages and assistant turns with no tool calls.
 * @property toolCallId Set on role="tool" messages — the id of the
 *   [ToolCall] this result answers.
 * @property name Optional tool name on role="tool" messages.
 */
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val name: String? = null,
)

/** A single tool/function call emitted by the assistant. */
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolFunction,
)

/** The function name + raw-JSON arguments inside a [ToolCall]. */
data class ToolFunction(
    val name: String,
    val arguments: String,
)

/**
 * A tool's JSON-Schema definition, sent to the LLM as part of the
 * `tools` request field so the model knows what it can call.
 * Uses [JSONObject] for the parameters schema (consistent with the
 * existing MCP tools in McpTools.kt).
 */
data class ToolDefinition(
    val type: String = "function",
    val function: ToolFunctionDefinition,
)

data class ToolFunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JSONObject,
)

/**
 * The result of executing a tool call. [content] is the string the LLM
 * sees in the role="tool" response message (typically JSON).
 */
data class ToolResult(
    val toolCallId: String,
    val name: String,
    val content: String,
    val isError: Boolean = false,
)
