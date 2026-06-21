package sh.haven.feature.agent.tools

import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SessionStatus
import sh.haven.core.ssh.Transport
import sh.haven.feature.agent.model.ToolDefinition
import sh.haven.feature.agent.model.ToolFunctionDefinition
import sh.haven.feature.agent.model.ToolResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `workspace_get_info` and `workspace_get_session_info` — discovery
 * tools that let the agent see which terminal sessions are open before
 * it tries to run commands on them.
 *
 * Ported from Netcatty's `workspace_get_info` / `workspace_get_session_info`.
 * The agent should call `workspace_get_info` first to learn the
 * sessionIds, then pass one to `terminal_execute`.
 */
@Singleton
class WorkspaceInfoTool @Inject constructor(
    private val sessionManagerRegistry: SessionManagerRegistry,
) {

    fun getInfoDefinition(): ToolDefinition = ToolDefinition(
        function = ToolFunctionDefinition(
            name = "workspace_get_info",
            description = "List all open terminal sessions in the workspace. Returns each " +
                "session's sessionId, label, transport (SSH/Mosh/ET/Reticulum/Local), and " +
                "connection status. Call this first to discover sessionIds before using " +
                "terminal_execute. Only terminal-capable sessions are included (SSH, Mosh, " +
                "ET, Reticulum, Local shell) — not RDP/SMB/Mail.",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            },
        ),
    )

    fun getSessionInfoDefinition(): ToolDefinition = ToolDefinition(
        function = ToolFunctionDefinition(
            name = "workspace_get_session_info",
            description = "Get detailed information about a single terminal session: label, " +
                "transport, status, and whether it has an active shell. Use this when you " +
                "need to check a specific session before running a command on it.",
            parameters = JSONObject().apply {
                put("type", "object")
                put(
                    "properties",
                    JSONObject().apply {
                        put(
                            "sessionId",
                            JSONObject().apply {
                                put("type", "string")
                                put("description", "Session ID (from workspace_get_info).")
                            },
                        )
                    },
                )
                put("required", JSONArray().apply { put("sessionId") })
            },
        ),
    )

    suspend fun getInfo(argumentsJson: String, toolCallId: String): ToolResult {
        val sessions = sessionManagerRegistry.allSessions
            .filter { it.transport in TERMINAL_TRANSPORTS }
        val arr = JSONArray()
        for (s in sessions) {
            arr.put(JSONObject().apply {
                put("sessionId", s.sessionId)
                put("label", s.label)
                put("transport", s.transport.name)
                put("status", s.status.name)
                put("connected", s.status == SessionStatus.CONNECTED)
            })
        }
        val content = JSONObject().apply {
            put("count", sessions.size)
            put("sessions", arr)
        }.toString()
        return ToolResult(toolCallId, "workspace_get_info", content)
    }

    suspend fun getSessionInfo(argumentsJson: String, toolCallId: String): ToolResult {
        val args = try {
            JSONObject(argumentsJson)
        } catch (e: Exception) {
            return errorResult(toolCallId, "Invalid arguments JSON: ${e.message}")
        }
        val sessionId = args.optString("sessionId", "").takeIf { it.isNotEmpty() }
            ?: return errorResult(toolCallId, "Missing required argument: sessionId")

        val session = sessionManagerRegistry.allSessions.find { it.sessionId == sessionId }
            ?: return errorResult(toolCallId, "No session found with id: $sessionId")

        val content = JSONObject().apply {
            put("sessionId", session.sessionId)
            put("profileId", session.profileId)
            put("label", session.label)
            put("transport", session.transport.name)
            put("status", session.status.name)
            put("connected", session.status == SessionStatus.CONNECTED)
        }.toString()
        return ToolResult(toolCallId, "workspace_get_session_info", content)
    }

    private fun errorResult(toolCallId: String, message: String) = ToolResult(
        toolCallId = toolCallId,
        name = "workspace_get_session_info",
        content = JSONObject().apply { put("error", message) }.toString(),
        isError = true,
    )

    companion object {
        private val TERMINAL_TRANSPORTS = setOf(
            Transport.SSH,
            Transport.MOSH,
            Transport.ET,
            Transport.RETICULUM,
            Transport.LOCAL,
        )
    }
}
