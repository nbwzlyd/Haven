package sh.haven.feature.terminal.agent.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.feature.terminal.agent.model.*
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容流式客户端（对齐 Netcatty 的流式请求封装）
 *
 * 支持：
 * - 标准 OpenAI /v1/chat/completions 接口
 * - 流式 SSE (Server-Sent Events) 响应
 * - Tool Calling（工具调用）
 * - 多轮对话
 */
class OpenAiStreamingClient(
    private val client: OkHttpClient = defaultClient(),
) {
    companion object {
        private const val TAG = "OpenAiStreamingClient"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    fun sendMessage(
        config: AiProviderConfig,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null,
        temperature: Float? = null,
        maxTokens: Int? = null,
    ): Flow<ChatStreamEvent> = callbackFlow {
        try {
            // 1. 构建请求 URL
            val baseUrl = config.baseUrl ?: config.providerId.defaultBaseUrl
            val url = "$baseUrl/chat/completions"

            // 2. 构建请求体（对齐 Netcatty 的 ChatParams）
            val requestBody = buildRequestBody(
                config = config,
                messages = messages,
                tools = tools,
                temperature = temperature ?: config.advancedParams?.temperature,
                maxTokens = maxTokens ?: config.advancedParams?.maxTokens
            )

            // 3. 构建 HTTP 请求
            val request = Request.Builder()
                .url(url)
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .apply {
                    // 添加 API Key（支持 enc:v1: 加密前缀）
                    val apiKey = config.apiKey
                    if (!apiKey.isNullOrBlank()) {
                        when (config.providerId.resolveStyle()) {
                            ProviderStyle.ANTHROPIC -> addHeader("x-api-key", apiKey)
                            ProviderStyle.GOOGLE -> {
                                // Google 使用 URL 参数
                            }
                            else -> addHeader("Authorization", "Bearer $apiKey")
                        }
                    }

                    // 添加自定义 Headers
                    config.customHeaders?.forEach { (key, value) ->
                        addHeader(key, value)
                    }
                }
                .build()

            // 4. 发起请求并处理 SSE 流式响应
            val response = client.newCall(request).execute()
            val responseBody = response.body

            if (!response.isSuccessful || responseBody == null) {
                trySend(ChatStreamEvent.Error(
                    type = "provider",
                    message = "API request failed: ${response.code} ${response.message}"
                ))
                close()
                return@callbackFlow
            }

            // 5. 解析 SSE 流
            val source = responseBody.source()
            val buffer = okio.Buffer()

            while (!source.exhausted()) {
                source.readUtf8Line()?.let { line ->
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()
                        if (data == "[DONE]") {
                            trySend(ChatStreamEvent.Done)
                            return@callbackFlow
                        }

                        try {
                            val json = JSONObject(data)
                            parseStreamEvent(json)?.let { event ->
                                trySend(event)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse SSE event: $data", e)
                        }
                    }
                }
            }

            trySend(ChatStreamEvent.Done)
            close()

        } catch (e: Exception) {
            Log.e(TAG, "Streaming request failed", e)
            trySend(ChatStreamEvent.Error(
                type = "unknown",
                message = e.message ?: "Unknown error"
            ))
            close(e)
        }

        awaitClose {
            Log.d(TAG, "Stream closed")
        }
    }

    private fun buildRequestBody(
        config: AiProviderConfig,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        temperature: Float?,
        maxTokens: Int?,
    ): String {
        val root = JSONObject()

        // 模型
        root.put("model", config.defaultModel ?: "gpt-4o")

        // 消息列表
        val messagesArray = JSONArray()
        messages.forEach { msg ->
            val msgObj = JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            }
            messagesArray.put(msgObj)
        }
        root.put("messages", messagesArray)

        // 流式响应
        root.put("stream", true)

        // Temperature
        if (temperature != null) {
            root.put("temperature", temperature)
        }

        // Max Tokens
        if (maxTokens != null) {
            root.put("max_tokens", maxTokens)
        }

        // Tools (Tool Calling)
        if (!tools.isNullOrEmpty()) {
            val toolsArray = JSONArray()
            tools.forEach { tool ->
                val toolObj = JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.parameters)
                    })
                }
                toolsArray.put(toolObj)
            }
            root.put("tools", toolsArray)
        }

        return root.toString()
    }

    private fun parseStreamEvent(json: JSONObject): ChatStreamEvent? {
        return try {
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return null
            }

            val choice = choices.getJSONObject(0)
            val delta = choice.optJSONObject("delta")

            if (delta != null) {
                // 文本内容
                val content = delta.optString("content", "")
                if (content.isNotEmpty()) {
                    return ChatStreamEvent.Text(content)
                }

                // Tool Calls
                val toolCalls = delta.optJSONArray("tool_calls")
                if (toolCalls != null && toolCalls.length() > 0) {
                    val toolCall = toolCalls.getJSONObject(0)
                    return ChatStreamEvent.ToolCall(
                        id = toolCall.optString("id", ""),
                        name = toolCall.optJSONObject("function")?.optString("name", "") ?: "",
                        arguments = toolCall.optJSONObject("function")?.optString("arguments", "") ?: ""
                    )
                }
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse stream event", e)
            null
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}

/**
 * 聊天消息（对齐 Netcatty 的 ChatMessage）
 */
data class ChatMessage(
    val role: String,  // "user" | "assistant" | "system"
    val content: String,
    val name: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null
)

/**
 * Tool 定义（对齐 Netcatty 的 ToolDefinition）
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>  // JSON Schema
)

/**
 * Tool Call（对齐 Netcatty 的 ToolCall）
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String  // JSON 字符串
)

/**
 * 流式事件（对齐 Netcatty 的 ChatStreamEvent）
 */
sealed class ChatStreamEvent {
    data class Text(val content: String) : ChatStreamEvent()
    data class Thinking(val content: String) : ChatStreamEvent()
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String
    ) : ChatStreamEvent()
    data class ToolResult(
        val toolCallId: String,
        val content: String,
        val isError: Boolean = false
    ) : ChatStreamEvent()
    data class Error(
        val type: String,  // "network" | "auth" | "timeout" | "provider" | "unknown"
        val message: String
    ) : ChatStreamEvent()
    object Done : ChatStreamEvent()
}
