package sh.haven.feature.agent.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.feature.agent.model.ChatMessage
import sh.haven.feature.agent.model.ToolCall
import sh.haven.feature.agent.model.ToolDefinition
import sh.haven.feature.agent.model.ToolFunction
import sh.haven.feature.agent.provider.LlmStreamChunk.Done
import sh.haven.feature.agent.provider.LlmStreamChunk.Error
import sh.haven.feature.agent.provider.LlmStreamChunk.TextDelta
import sh.haven.feature.agent.provider.LlmStreamChunk.ToolCallDelta
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Default [LlmClient] that talks to any OpenAI-compatible Chat
 * Completions endpoint (`POST {baseUrl}/chat/completions`) with
 * `stream: true` and `tools` function-calling.
 *
 * Covers OpenAI, DeepSeek, Qwen, Zhipu, OpenRouter, Ollama
 * (`/v1`), and any compatible gateway. The provider differences that
 * matter (auth header shape, model id spelling) are all on the request
 * side; the SSE response shape is standardised across all of them.
 *
 * Streaming: reads the SSE `data:` lines one by one, parses each
 * `choices[0].delta` chunk, and emits [TextDelta] /
 * [ToolCallDelta] chunks to [onChunk]. The `[DONE]` sentinel ends the
 * stream.
 *
 * Uses `org.json` (Android's built-in JSON library) for consistency
 * with the rest of the Haven codebase (McpTools.kt).
 */
@Singleton
class OpenAiCompatibleClient @Inject constructor(
    private val client: OkHttpClient,
) : LlmClient {

    override suspend fun streamTurn(
        request: LlmRequest,
        onChunk: (LlmStreamChunk) -> Unit,
    ): LlmTurnResult = withContext(Dispatchers.IO) {
        val body = buildRequestBody(request)
        val httpRequest = Request.Builder()
            .url("${request.baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer ${request.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            client.newCall(httpRequest).execute()
        } catch (e: IOException) {
            onChunk(Error("Network error: ${e.message}"))
            return@withContext LlmTurnResult(text = "", toolCalls = emptyList())
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty().take(500)
                onChunk(Error("HTTP ${resp.code}: $errBody"))
                return@withContext LlmTurnResult(text = "", toolCalls = emptyList())
            }
            readStream(resp, onChunk)
        }
    }

    private suspend fun readStream(
        response: Response,
        onChunk: (LlmStreamChunk) -> Unit,
    ): LlmTurnResult {
        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream(), Charsets.UTF_8))
        val text = StringBuilder()
        // Tool calls arrive as deltas across multiple chunks, indexed by
        // `index`. We accumulate id / name / arguments per index, then
        // assemble them into ToolCalls at the end.
        val toolCallIds = mutableMapOf<Int, String>()
        val toolCallNames = mutableMapOf<Int, String>()
        val toolCallArgs = mutableMapOf<Int, StringBuilder>()

        try {
            while (true) {
                coroutineContext.ensureActive()
                val line = reader.readLine() ?: break
                if (line.isEmpty()) continue
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") {
                    onChunk(Done)
                    break
                }
                val chunk = try {
                    JSONObject(data)
                } catch (e: Exception) {
                    continue
                }
                val choices = chunk.optJSONArray("choices")
                if (choices == null || choices.length() == 0) continue
                val choice = choices.optJSONObject(0) ?: continue
                val delta = choice.optJSONObject("delta")

                // Text delta
                val textDelta = delta?.optString("content", "") ?: ""
                if (textDelta.isNotEmpty()) {
                    text.append(textDelta)
                    onChunk(TextDelta(textDelta))
                }

                // Tool-call deltas
                val toolCallsDelta = delta?.optJSONArray("tool_calls")
                if (toolCallsDelta != null) {
                    for (i in 0 until toolCallsDelta.length()) {
                        val tc = toolCallsDelta.optJSONObject(i) ?: continue
                        val index = tc.optInt("index", 0)
                        val id = tc.optString("id", "").takeIf { it.isNotEmpty() }
                        val function = tc.optJSONObject("function")
                        val name = function?.optString("name", "")?.takeIf { it.isNotEmpty() }
                        val argsDelta = function?.optString("arguments", "") ?: ""

                        if (id != null) toolCallIds[index] = id
                        if (name != null) toolCallNames[index] = name
                        if (argsDelta.isNotEmpty()) {
                            val sb = toolCallArgs.getOrPut(index) { StringBuilder() }
                            sb.append(argsDelta)
                        }
                        onChunk(ToolCallDelta(index, id, name, argsDelta))
                    }
                }
            }
        } catch (e: Exception) {
            onChunk(Error("Stream read error: ${e.message}"))
        }

        val assembledToolCalls = toolCallIds.keys.sorted().map { index ->
            ToolCall(
                id = toolCallIds[index] ?: "call_$index",
                type = "function",
                function = ToolFunction(
                    name = toolCallNames[index] ?: "",
                    arguments = toolCallArgs[index]?.toString() ?: "{}",
                ),
            )
        }
        return LlmTurnResult(text = text.toString(), toolCalls = assembledToolCalls)
    }

    private fun buildRequestBody(request: LlmRequest): String {
        val messages = JSONArray()
        // System prompt first
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", request.systemPrompt)
        })
        // Conversation history
        for (msg in request.messages) {
            messages.put(messageToJson(msg))
        }
        val toolsArray = JSONArray()
        for (tool in request.tools) {
            toolsArray.put(toolDefinitionToJson(tool))
        }
        val obj = JSONObject().apply {
            put("model", request.model)
            put("messages", messages)
            put("stream", true)
            if (request.tools.isNotEmpty()) {
                put("tools", toolsArray)
                put("tool_choice", "auto")
            }
        }
        return obj.toString()
    }

    private fun messageToJson(msg: ChatMessage): JSONObject = JSONObject().apply {
        put("role", msg.role)
        if (msg.content != null) put("content", msg.content)
        if (msg.toolCalls != null) {
            val arr = JSONArray()
            for (tc in msg.toolCalls) {
                arr.put(JSONObject().apply {
                    put("id", tc.id)
                    put("type", tc.type)
                    put("function", JSONObject().apply {
                        put("name", tc.function.name)
                        put("arguments", tc.function.arguments)
                    })
                })
            }
            put("tool_calls", arr)
        }
        if (msg.toolCallId != null) put("tool_call_id", msg.toolCallId)
        if (msg.name != null) put("name", msg.name)
    }

    private fun toolDefinitionToJson(tool: ToolDefinition): JSONObject = JSONObject().apply {
        put("type", tool.type)
        put("function", JSONObject().apply {
            put("name", tool.function.name)
            put("description", tool.function.description)
            put("parameters", tool.function.parameters)
        })
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /**
         * Factory for a fresh OkHttpClient with the timeouts the agent
         * needs (long read timeout for streaming, short connect). Used
         * by the Hilt module so the agent's OkHttp instance is isolated
         * from the rest of the app.
         */
        fun createOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
