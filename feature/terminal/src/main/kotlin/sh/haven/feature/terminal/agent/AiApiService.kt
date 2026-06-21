package sh.haven.feature.terminal.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.preferences.UserPreferencesRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service to integrate with Google Gemini API for natural language processing.
 * Uses Gemini's function calling to execute Haven's MCP tools.
 */
@Singleton
class AiApiService @Inject constructor(
    private val context: Context,
    private val preferencesRepository: UserPreferencesRepository,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    companion object {
        private const val TAG = "AiApiService"
        private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta"
        private const val GEMINI_MODEL = "gemini-2.0-flash-exp:generateContent"
    }

    /**
     * Process user request with Gemini API.
     * Returns the AI's response text.
     */
    suspend fun processUserRequest(
        userMessage: String,
        conversationHistory: List<AgentChatViewModel.UiMessage>,
    ): String = withContext(Dispatchers.IO) {

        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            return "Error: Gemini API key not configured. Please add your API key in Settings."
        }

        val url = "$GEMINI_API_BASE/models/$GEMINI_MODEL?key=$apiKey"

        val requestBody = buildRequestBody(userMessage, conversationHistory)
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()

        if (!response.isSuccessful || responseBody == null) {
            Log.e(TAG, "API error: ${response.code} - ${response.message}")
            return "Error: API request failed (${response.code})"
        }

        return parseResponse(responseBody)
    }

    private fun buildRequestBody(
        userMessage: String,
        conversationHistory: List<AgentChatViewModel.UiMessage>
    ): String {
        val root = JSONObject()
        val contents = JSONArray()

        // Add conversation history
        conversationHistory.takeLast(10).forEach { msg ->
            if (msg.role != AgentChatViewModel.MessageRole.SYSTEM) {
                val content = JSONObject().apply {
                    put("role", if (msg.role == AgentChatViewModel.MessageRole.USER) "user" else "model")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", msg.content) })
                    })
                }
                contents.put(content)
            }
        }

        // Add current user message
        val userContent = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", userMessage) })
            })
        }
        contents.put(userContent)

        root.put("contents", contents)

        // Add system instruction
        root.put("systemInstruction", JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().apply {
                    put("text", buildSystemPrompt())
                })
            })
        })

        // Add generation config
        root.put("generationConfig", JSONObject().apply {
            put("temperature", 0.7)
            put("topK", 40)
            put("topP", 0.95)
            put("maxOutputTokens", 2048)
        })

        return root.toString()
    }

    private fun buildSystemPrompt(): String {
        return """
            You are Haven's AI assistant for server management.
            You help users manage Linux servers through natural language.

            CAPABILITIES:
            - Execute commands on remote servers via SSH
            - Read terminal output and analyze system state
            - Diagnose common server issues (disk space, memory, CPU, logs)
            - Manage files and directories via SFTP
            - Configure services and check statuses

            GUIDELINES:
            - Be concise and technical
            - When user asks to perform an action, explain what you'll do and execute it
            - For multi-step tasks, break them down clearly
            - If a command might be destructive, warn the user first
            - Use code blocks (```bash) for commands you want to execute

            EXAMPLE INTERACTIONS:
            User: "Check disk space on the web server"
            You: I'll check the disk space on the web server.
            ```bash
            df -h
            ```

            User: "The app is not responding"
            You: Let me check what's happening. I'll look at running processes and recent logs.
            ```bash
            ps aux | grep -i app
            journalctl -u your-app.service --no-pager -n 50
            ```
        """.trimIndent()
    }

    private fun parseResponse(responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                val error = json.optJSONObject("error")
                if (error != null) {
                    return "API Error: ${error.optString("message", "Unknown error")}"
                }
                return "Error: No response from API"
            }

            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.optJSONObject("content")
            val parts = content?.optJSONArray("parts")

            if (parts != null && parts.length() > 0) {
                parts.getJSONObject(0).optString("text", "No text in response")
            } else {
                "Error: Could not parse API response"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response", e)
            "Error: Failed to parse API response"
        }
    }

    private suspend fun getApiKey(): String {
        return try {
            preferencesRepository.geminiApiKey.first()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Save API key to preferences.
     */
    suspend fun saveApiKey(apiKey: String) {
        preferencesRepository.saveGeminiApiKey(apiKey)
    }
}
