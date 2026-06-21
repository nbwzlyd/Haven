package sh.haven.feature.terminal.agent.network

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.feature.terminal.agent.model.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers

/**
 * OpenAI-compatible streaming client.
 *
 * Key design notes (1:1 aligned with Netcatty):
 * 1. Supports 5 tools (same as Netcatty):
 *    - terminal_execute
 *    - workspace_get_info
 *    - workspace_get_session_info
 *    - web_search
 *    - url_fetch
 * 2. Tool result format matches Netcatty exactly:
 *    STDOUT:\n...\n\nSTDERR:\n...\n\nExit code: x
 * 3. Uses OpenAI /v1/chat/completions API with function calling.
 */
class OpenAiStreamingClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "OpenAiStreamingClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Send messages to AI and get streaming response.
     *
     * @param config Provider config
     * @param messages Chat messages (OpenAI format)
     * @param tools Tool definitions (OpenAI format)
     * @param onEvent Callback for streaming events
     */
    suspend fun sendMessage(
        config: AiProviderConfig,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = TerminalTools.getAllTools(),
        onEvent: (ChatStreamEvent) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val baseUrl = config.baseUrl?.trimEnd('/') ?: "https://api.openai.com"
            val url = "$baseUrl/v1/chat/completions"

            // Build request body (OpenAI format)
            val requestJson = JSONObject().apply {
                put("model", config.defaultModel ?: "gpt-4o")
                put("messages", JSONArray(messages.map { it.toOpenAiFormat() }))
                put("stream", true)

                // Add tools (function calling)
                if (tools.isNotEmpty()) {
                    put("tools", JSONArray(tools.map { it.toOpenAiFormat() }))
                    put("tool_choice", "auto")
                }

                // Advanced params
                config.advancedParams?.let { params ->
                    params.temperature?.let { put("temperature", it) }
                    params.maxTokens?.let { put("max_tokens", it) }
                    params.topP?.let { put("top_p", it) }
                }
            }

            val request = Request.Builder()
                .url(url)
                .post(requestJson.toString().toRequestBody(JSON))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer ${config.getDecryptedApiKey()}")
                .build()

            Log.d(TAG, "Sending request to: $url")
            Log.d(TAG, "Request body: ${requestJson.toString(2)}")

            val call = client.newCall(request)
            val response = call.execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "API error: ${response.code}, body: $errorBody")
                onEvent(ChatStreamEvent.Error("API error: ${response.code}, $errorBody"))
                return@withContext
            }

            // Parse SSE stream
            parseSseStream(response.body!!.source(), onEvent)

        } catch (e: Exception) {
            Log.e(TAG, "Streaming request failed", e)
            onEvent(ChatStreamEvent.Error("Request failed: ${e.message}"))
        }
    }

    /**
     * Parse SSE (Server-Sent Events) stream.
     *
     * OpenAI streaming format:
     * data: {"choices":[{"delta":{"content":"Hello"}}]}
     * data: {"choices":[{"delta":{"tool_calls":[{"id":"call_xxx","function":{"name":"terminal_execute","arguments":"{\"sessionId\""}}]}}]}
     * data: [DONE]
     */
    private suspend fun parseSseStream(
        source: okio.BufferedSource,
        onEvent: (ChatStreamEvent) -> Unit
    ) {
        var textAccumulator = StringBuilder()
        var thinkingContent = StringBuilder()

        // Tool call accumulators (OpenAI streams tool_calls in chunks)
        val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()

        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: continue

            if (line.startsWith("data: ")) {
                val data = line.substring(6).trim()

                if (data == "[DONE]") {
                    // Stream complete
                    if (textAccumulator.isNotEmpty()) {
                        onEvent(ChatStreamEvent.TextDelta(textAccumulator.toString()))
                    }
                    if (thinkingContent.isNotEmpty()) {
                        onEvent(ChatStreamEvent.ThinkingDelta(thinkingContent.toString()))
                    }

                    // Emit accumulated tool calls
                    if (toolCallAccumulators.isNotEmpty()) {
                        val toolCalls = toolCallAccumulators.values.map { it.toToolCall() }
                        onEvent(ChatStreamEvent.ToolCall(toolCalls))
                    }

                    onEvent(ChatStreamEvent.Done)
                    break
                }

                try {
                    val json = JSONObject(data)
                    val choices = json.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue

                    val choice = choices.getJSONObject(0)
                    val delta = choice.optJSONObject("delta") ?: continue

                    // Text content
                    val content = delta.optString("content", null)
                    if (content != null) {
                        textAccumulator.append(content)
                        onEvent(ChatStreamEvent.TextDelta(content))
                    }

                    // Tool calls (function calling)
                    val toolCallsJson = delta.optJSONArray("tool_calls")
                    if (toolCallsJson != null) {
                        for (i in 0 until toolCallsJson.length()) {
                            val tc = toolCallsJson.getJSONObject(i)
                            val index = tc.optInt("index", 0)

                            // Get or create accumulator
                            val acc = toolCallAccumulators.getOrPut(index) { ToolCallAccumulator() }

                            // ID (only in first chunk)
                            if (tc.has("id")) {
                                acc.id = tc.getString("id")
                            }

                            // Function
                            val function = tc.optJSONObject("function")
                            if (function != null) {
                                // Name (only in first chunk)
                                if (function.has("name")) {
                                    acc.name = function.getString("name")
                                }

                                // Arguments (streamed in chunks)
                                if (function.has("arguments")) {
                                    acc.arguments.append(function.getString("arguments"))
                                }
                            }
                        }
                    }

                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse SSE line: $line", e)
                }
            }
        }
    }

    /**
     * Tool call accumulator (for streaming parsing).
     */
    private data class ToolCallAccumulator(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder()
    ) {
        fun toToolCall(): ToolCall {
            val argsStr = arguments.toString()
            val argsMap = mutableMapOf<String, Any>()

            // Parse arguments JSON
            if (argsStr.isNotBlank()) {
                try {
                    val json = JSONObject(argsStr)
                    json.keys().forEach { key ->
                        argsMap[key] = json.get(key)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse tool arguments: $argsStr", e)
                }
            }

            return ToolCall(
                id = id ?: "unknown",
                name = name ?: "unknown",
                arguments = argsMap
            )
        }
    }
}
