package sh.haven.feature.agent.core

import org.json.JSONObject
import sh.haven.feature.agent.model.AgentChatItem
import sh.haven.feature.agent.model.ChatMessage
import sh.haven.feature.agent.model.ToolCall
import sh.haven.feature.agent.model.ToolCallStatus
import sh.haven.feature.agent.model.ToolResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Callbacks the agent loop uses to talk to the UI layer (ViewModel).
 * Kept as an interface so the agent has no Compose/lifecycle dependency
 * — the ViewModel implements this and the agent calls it from a
 * background coroutine.
 */
interface AgentUiCallbacks {
    /** A text delta arrived from the LLM (streaming the assistant reply). */
    fun onAssistantTextDelta(itemId: String, delta: String)

    /** A new tool call started (the assistant emitted a tool_call). */
    fun onToolCallStarted(itemId: String, toolCall: ToolCall)

    /** A tool call needs user approval (confirm mode). */
    fun onToolCallNeedsApproval(itemId: String, toolCall: ToolCall, summary: String)

    /** A tool call's status changed (running / completed / denied). */
    fun onToolCallStatusChanged(itemId: String, status: ToolCallStatus)

    /** A tool call completed with a result. */
    fun onToolCallResult(itemId: String, result: ToolResult)

    /** An assistant message finished streaming. */
    fun onAssistantMessageFinished(itemId: String)

    /** An error occurred. */
    fun onError(message: String)

    /** The agent turn finished (all iterations done or model stopped calling tools). */
    fun onTurnFinished()

    /** The agent turn was aborted (user cancelled or error). */
    fun onTurnAborted()
}

/**
 * The Catty Agent — the outbound AI agent loop. Ported from Netcatty's
 * `processCattyStream` (Path A — built-in agent).
 *
 * The loop:
 *  1. Build the request (system prompt + conversation history + tools).
 *  2. Stream the LLM turn, emitting text deltas + tool-call deltas to
 *     the UI as they arrive.
 *  3. If the LLM emitted tool calls, execute each one (with approval
 *     in confirm mode), feed the results back as role="tool" messages.
 *  4. Repeat from step 1 with the updated history, until the LLM
 *     stops calling tools or [maxIterations] is reached.
 *
 * This is the explicit-step variant of Netcatty's `stopWhen: stepCountIs(N)`
 * — the Vercel AI SDK hides the loop behind `streamText`, but on
 * Kotlin we drive it ourselves so we can interleave approval prompts
 * and UI updates between steps.
 *
 * Cancellation: the caller's coroutine cancellation propagates through
 * `streamTurn` (which checks `ensureActive` between SSE lines) and the
 * approval `await`, so cancelling the job aborts the turn cleanly.
 */
@Singleton
class CattyAgent @Inject constructor(
    private val llmClient: sh.haven.feature.agent.provider.LlmClient,
    private val agentTools: sh.haven.feature.agent.tools.AgentTools,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val approvalGate: ApprovalGate,
) {

    /**
     * Run one agent turn: process [userMessage] against [history],
     * calling the LLM and executing tools until the model stops or
     * [config.maxIterations] is reached. Updates the UI via [callbacks].
     *
     * @param userMessage The user's new message text.
     * @param history The conversation history before this turn (not
     *   including [userMessage]). The caller should append the user
     *   message + the assistant's new messages to this after the turn.
     * @param config Runtime agent configuration.
     * @param callbacks UI callbacks for streaming updates.
     * @return The updated history (original + user message + assistant
     *   messages + tool messages from this turn). The caller persists
     *   this for the next turn.
     */
    suspend fun runTurn(
        userMessage: String,
        history: List<ChatMessage>,
        config: sh.haven.feature.agent.model.AgentConfig,
        callbacks: AgentUiCallbacks,
    ): List<ChatMessage> {
        if (!config.isConfigured) {
            callbacks.onError("Agent is not configured — set API key, base URL, and model in Settings.")
            callbacks.onTurnAborted()
            return history
        }

        val systemPrompt = systemPromptBuilder.build(config.permissionMode)
        val tools = agentTools.definitions()

        // Working copy of the conversation — starts with history + the new user message.
        val messages = history.toMutableList()
        messages.add(ChatMessage(role = "user", content = userMessage))

        var iteration = 0
        try {
            while (iteration < config.maxIterations) {
                iteration++
                val assistantItemId = "assistant_${System.currentTimeMillis()}_${iteration}"

                val request = sh.haven.feature.agent.provider.LlmRequest(
                    baseUrl = config.baseUrl,
                    apiKey = config.apiKey,
                    model = config.model,
                    systemPrompt = systemPrompt,
                    messages = messages.toList(),
                    tools = tools,
                    maxIterations = config.maxIterations,
                )

                val result = llmClient.streamTurn(request) { chunk ->
                    when (chunk) {
                        is sh.haven.feature.agent.provider.LlmStreamChunk.TextDelta ->
                            callbacks.onAssistantTextDelta(assistantItemId, chunk.text)
                        is sh.haven.feature.agent.provider.LlmStreamChunk.Error ->
                            callbacks.onError(chunk.message)
                        else -> { /* tool-call deltas + done handled below */ }
                    }
                }

                // Append the assistant message (text + tool calls) to history.
                val assistantMessage = ChatMessage(
                    role = "assistant",
                    content = result.text.ifEmpty { null },
                    toolCalls = result.toolCalls.ifEmpty { null },
                )
                messages.add(assistantMessage)
                callbacks.onAssistantMessageFinished(assistantItemId)

                // No tool calls → the model is done talking, end the turn.
                if (result.toolCalls.isEmpty()) break

                // Execute each tool call, feed results back as role="tool" messages.
                for (toolCall in result.toolCalls) {
                    val toolItemId = "tool_${toolCall.id}"

                    // In observer mode, terminal_execute is refused.
                    if (config.permissionMode == sh.haven.core.data.preferences.UserPreferencesRepository.CattyAgentPermissionMode.OBSERVER &&
                        toolCall.function.name == "terminal_execute"
                    ) {
                        val deniedResult = ToolResult(
                            toolCallId = toolCall.id,
                            name = toolCall.function.name,
                            content = """{"error":"Observer mode: terminal_execute is not allowed. Ask the user to switch to Confirm or Autonomous mode in Settings."}""",
                            isError = true,
                        )
                        messages.add(
                            ChatMessage(
                                role = "tool",
                                content = deniedResult.content,
                                toolCallId = toolCall.id,
                                name = toolCall.function.name,
                            ),
                        )
                        callbacks.onToolCallResult(toolItemId, deniedResult)
                        continue
                    }

                    // In confirm mode, terminal_execute needs approval.
                    if (config.permissionMode == sh.haven.core.data.preferences.UserPreferencesRepository.CattyAgentPermissionMode.CONFIRM &&
                        toolCall.function.name == "terminal_execute"
                    ) {
                        callbacks.onToolCallStarted(toolItemId, toolCall)
                        val summary = summariseToolCall(toolCall)
                        callbacks.onToolCallNeedsApproval(toolItemId, toolCall, summary)
                        val approval = approvalGate.requestApproval(
                            toolCallId = toolCall.id,
                            toolName = toolCall.function.name,
                            argumentsJson = toolCall.function.arguments,
                            summary = summary,
                        )
                        val approved = approvalGate.await(approval)
                        if (!approved) {
                            val deniedResult = ToolResult(
                                toolCallId = toolCall.id,
                                name = toolCall.function.name,
                                content = """{"error":"User denied command execution."}""",
                                isError = true,
                            )
                            messages.add(
                                ChatMessage(
                                    role = "tool",
                                    content = deniedResult.content,
                                    toolCallId = toolCall.id,
                                    name = toolCall.function.name,
                                ),
                            )
                            callbacks.onToolCallResult(toolItemId, deniedResult)
                            continue
                        }
                    } else {
                        callbacks.onToolCallStarted(toolItemId, toolCall)
                    }

                    // Execute the tool.
                    callbacks.onToolCallStatusChanged(
                        toolItemId,
                        ToolCallStatus.RUNNING,
                    )
                    val toolResult = try {
                        agentTools.execute(
                            toolCall.function.name,
                            toolCall.function.arguments,
                            toolCall.id,
                        )
                    } catch (e: Exception) {
                        ToolResult(
                            toolCallId = toolCall.id,
                            name = toolCall.function.name,
                            content = """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                            isError = true,
                        )
                    }
                    messages.add(
                        ChatMessage(
                            role = "tool",
                            content = toolResult.content,
                            toolCallId = toolCall.id,
                            name = toolCall.function.name,
                        ),
                    )
                    callbacks.onToolCallResult(toolItemId, toolResult)
                }
                // Loop back: the LLM will see the tool results and either
                // call more tools or produce a final text reply.
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            callbacks.onTurnAborted()
            throw e
        } catch (e: Exception) {
            callbacks.onError("Agent error: ${e.message}")
            callbacks.onTurnAborted()
            return messages
        }

        callbacks.onTurnFinished()
        return messages
    }

    /** Short human-readable summary of a tool call, for the approval card. */
    private fun summariseToolCall(toolCall: ToolCall): String {
        return when (toolCall.function.name) {
            "terminal_execute" -> {
                val command = try {
                    JSONObject(toolCall.function.arguments).optString("command", "")
                } catch (_: Exception) {
                    ""
                }.takeIf { it.isNotEmpty() } ?: toolCall.function.arguments
                "Run: `$command`"
            }
            else -> "Call ${toolCall.function.name}"
        }
    }
}
