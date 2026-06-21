package sh.haven.feature.agent.tools

import sh.haven.feature.agent.model.ToolDefinition
import sh.haven.feature.agent.model.ToolResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The set of tools the Catty Agent can call. Each tool has:
 *  - a [ToolDefinition] (sent to the LLM so it knows the schema)
 *  - an [execute] suspend function that runs the tool and returns a
 *    [ToolResult] (the content string the LLM sees in the role="tool"
 *    response message).
 *
 * Ported from Netcatty's `createCattyTools` (Path A — built-in agent).
 * The terminal tools reach the live SSH/local sessions through the
 * same [SshSessionManager] / [LocalSessionManager] the MCP transport
 * uses, so the agent drives the exact same primitives a human does.
 *
 * Tools:
 *  - [terminal_execute] — run a shell command on a session, return output
 *  - [workspace_get_info] — list all in-scope sessions
 *  - [workspace_get_session_info] — details about one session
 *  - [url_fetch] — fetch HTTPS URL content (read-only)
 */
@Singleton
class AgentTools @Inject constructor(
    private val terminalExecuteTool: TerminalExecuteTool,
    private val workspaceInfoTool: WorkspaceInfoTool,
    private val urlFetchTool: UrlFetchTool,
) {

    /** All tool definitions, sent to the LLM as the `tools` request field. */
    fun definitions(): List<ToolDefinition> = listOf(
        terminalExecuteTool.definition(),
        workspaceInfoTool.getInfoDefinition(),
        workspaceInfoTool.getSessionInfoDefinition(),
        urlFetchTool.definition(),
    )

    /**
     * Dispatch a tool call by name. Returns the [ToolResult] to feed
     * back to the LLM. Throws [IllegalArgumentException] for unknown
     * tool names (the agent loop surfaces this as an error tool result).
     */
    suspend fun execute(
        name: String,
        argumentsJson: String,
        toolCallId: String,
    ): ToolResult = when (name) {
        "terminal_execute" -> terminalExecuteTool.execute(argumentsJson, toolCallId)
        "workspace_get_info" -> workspaceInfoTool.getInfo(argumentsJson, toolCallId)
        "workspace_get_session_info" -> workspaceInfoTool.getSessionInfo(argumentsJson, toolCallId)
        "url_fetch" -> urlFetchTool.execute(argumentsJson, toolCallId)
        else -> ToolResult(
            toolCallId = toolCallId,
            name = name,
            content = """{"error":"Unknown tool: $name"}""",
            isError = true,
        )
    }
}
