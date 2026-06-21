package sh.haven.feature.terminal.agent.model

import android.util.Log
import org.json.JSONObject

/**
 * Tool Call (aligned with Netcatty).
 *
 * Netcatty interface:
 * interface ToolCall {
 *   id: string;
 *   name: string;
 *   arguments: Record<string, unknown>;
 * }
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>
)

/**
 * Tool Result (aligned with Netcatty).
 *
 * Netcatty interface:
 * interface ToolResult {
 *   toolCallId: string;
 *   content: string;
 *   isError?: boolean;
 * }
 */
data class ToolResult(
    val toolCallId: String,
    val content: String,
    val isError: Boolean = false
)

/**
 * Tool Definition (OpenAI function calling format).
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any?>
) {
    fun toOpenAiFormat(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", JSONObject(parameters))
            })
        }
    }
}

/**
 * Terminal Tools (1:1 aligned with Netcatty's 5 tools).
 *
 * Netcatty tools:
 * 1. terminal_execute
 * 2. workspace_get_info
 * 3. workspace_get_session_info
 * 4. web_search
 * 5. url_fetch
 */
object TerminalTools {

    private const val TAG = "TerminalTools"

    /**
     * Get all tool definitions (aligned with Netcatty).
     */
    fun getAllTools(): List<ToolDefinition> {
        return listOf(
            getTerminalExecuteTool(),
            getWorkspaceGetInfoTool(),
            getWorkspaceGetSessionInfoTool(),
            getWebSearchTool(),
            getUrlFetchTool()
        )
    }

    /**
     * Tool 1: terminal_execute
     *
     * Execute a command on the remote server via SSH.
     */
    fun getTerminalExecuteTool(): ToolDefinition {
        return ToolDefinition(
            name = "terminal_execute",
            description = "Execute a command on the remote server via SSH. " +
                "Returns stdout, stderr, and exit code. " +
                "Use this to run Linux commands, check system status, read logs, etc.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "sessionId" to mapOf(
                        "type" to "string",
                        "description" to "The SSH session ID to execute the command on"
                    ),
                    "command" to mapOf(
                        "type" to "string",
                        "description" to "The Linux command to execute"
                    )
                ),
                "required" to listOf("sessionId", "command")
            )
        )
    }

    /**
     * Tool 2: workspace_get_info
     *
     * Get information about the current workspace and all sessions.
     */
    fun getWorkspaceGetInfoTool(): ToolDefinition {
        return ToolDefinition(
            name = "workspace_get_info",
            description = "Get information about the current workspace, including all active SSH sessions. " +
                "Returns workspace ID, name, and list of sessions with their status.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf<String, Any?>()
            )
        )
    }

    /**
     * Tool 3: workspace_get_session_info
     *
     * Get detailed information about a specific session.
     */
    fun getWorkspaceGetSessionInfoTool(): ToolDefinition {
        return ToolDefinition(
            name = "workspace_get_session_info",
            description = "Get detailed information about a specific SSH session, " +
                "including hostname, username, OS, shell type, and connection status.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "sessionId" to mapOf(
                        "type" to "string",
                        "description" to "The SSH session ID to get info for"
                    )
                ),
                "required" to listOf("sessionId")
            )
        )
    }

    /**
     * Tool 4: web_search
     *
     * Search the web for information (read-only, no permission check).
     */
    fun getWebSearchTool(): ToolDefinition {
        return ToolDefinition(
            name = "web_search",
            description = "Search the web for information. " +
                "Use this when you need to look up documentation, error messages, or general knowledge. " +
                "Returns a list of search results with titles, URLs, and content snippets.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf(
                        "type" to "string",
                        "description" to "The search query"
                    ),
                    "maxResults" to mapOf(
                        "type" to "number",
                        "description" to "Maximum number of results to return (default: 5)"
                    )
                ),
                "required" to listOf("query")
            )
        )
    }

    /**
     * Tool 5: url_fetch
     *
     * Fetch content from a URL (read-only, no permission check).
     */
    fun getUrlFetchTool(): ToolDefinition {
        return ToolDefinition(
            name = "url_fetch",
            description = "Fetch content from a URL. " +
                "Use this to read documentation pages, API responses, or any web content. " +
                "Only HTTPS URLs are allowed.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "url" to mapOf(
                        "type" to "string",
                        "description" to "The HTTPS URL to fetch"
                    ),
                    "maxLength" to mapOf(
                        "type" to "number",
                        "description" to "Maximum content length to return (default: 50000)"
                    )
                ),
                "required" to listOf("url")
            )
        )
    }
}

/**
 * Format tool execution result (aligned with Netcatty).
 *
 * Netcatty format for terminal_execute:
 * STDOUT:
 * <stdout content>
 *
 * STDERR:
 * <stderr content>
 *
 * Exit code: <exitCode>
 */
fun formatToolResult(
    stdout: String,
    stderr: String,
    exitCode: Int?
): String {
    return listOf(
        if (stdout.isNotBlank()) "STDOUT:\n$stdout" else "",
        if (stderr.isNotBlank()) "STDERR:\n$stderr" else "",
        "Exit code: ${if (exitCode == null) "unknown" else exitCode}"
    ).filter { it.isNotBlank() }.joinToString("\n\n")
}

/**
 * Chat Message (OpenAI format, aligned with Netcatty's ChatMessage).
 */
data class ChatMessage(
    val role: String,  // "user" | "assistant" | "system" | "tool"
    val content: String?,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,  // for role="tool" messages
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toOpenAiFormat(): Map<String, Any?> {
        val msg = mutableMapOf<String, Any?>(
            "role" to role,
            "content" to content
        )

        // Add tool_calls for assistant messages
        if (role == "assistant" && toolCalls != null) {
            msg["tool_calls"] = toolCalls.map { tc ->
                mapOf(
                    "id" to tc.id,
                    "type" to "function",
                    "function" to mapOf(
                        "name" to tc.name,
                        "arguments" to JSONObject(tc.arguments).toString()
                    )
                )
            }
        }

        // Add tool_call_id for tool messages
        if (role == "tool" && toolCallId != null) {
            msg["tool_call_id"] to toolCallId
        }

        return msg
    }

    companion object {
        fun fromOpenAiFormat(msg: Map<String, Any?>): ChatMessage {
            val role = msg["role"] as? String ?: "user"
            val content = msg["content"] as? String

            // Parse tool_calls
            val toolCalls = if (role == "assistant" && msg.containsKey("tool_calls")) {
                val tcList = msg["tool_calls"] as? List<Map<String, Any?>>
                tcList?.map { tc ->
                    val function = tc["function"] as? Map<String, Any?>
                    val argsStr = function?.get("arguments") as? String ?: "{}"
                    val argsMap = mutableMapOf<String, Any?>()

                    try {
                        val json = JSONObject(argsStr)
                        json.keys().forEach { key ->
                            argsMap[key] = json.get(key)
                        }
                    } catch (e: Exception) {
                        Log.e("ChatMessage", "Failed to parse tool arguments: $argsStr", e)
                    }

                    ToolCall(
                        id = tc["id"] as? String ?: "unknown",
                        name = function?.get("name") as? String ?: "unknown",
                        arguments = argsMap
                    )
                }
            } else null

            val toolCallId = if (role == "tool") msg["tool_call_id"] as? String else null

            return ChatMessage(
                role = role,
                content = content,
                toolCalls = toolCalls,
                toolCallId = toolCallId
            )
        }
    }
}
