package sh.haven.feature.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.feature.agent.model.ToolDefinition
import sh.haven.feature.agent.model.ToolFunctionDefinition
import sh.haven.feature.agent.model.ToolResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `url_fetch` — fetch the text content of an HTTPS URL. Read-only,
 * always available (no approval needed). The agent uses this to pull
 * documentation, API responses, or any web resource the user references.
 *
 * Ported from Netcatty's `url_fetch`. Truncates to [MAX_CHARS] to keep
 * the tool result within the LLM's context window.
 */
@Singleton
class UrlFetchTool @Inject constructor(
    private val client: OkHttpClient,
) {

    fun definition(): ToolDefinition = ToolDefinition(
        function = ToolFunctionDefinition(
            name = "url_fetch",
            description = "Fetch the text content of an HTTPS URL. Read-only — always " +
                "available, no approval needed. Use this to pull documentation, API " +
                "responses, or any web resource. HTML tags are stripped; content is " +
                "truncated to 20KB. Only HTTPS URLs are supported.",
            parameters = JSONObject().apply {
                put("type", "object")
                put(
                    "properties",
                    JSONObject().apply {
                        put(
                            "url",
                            JSONObject().apply {
                                put("type", "string")
                                put("description", "The HTTPS URL to fetch.")
                            },
                        )
                        put(
                            "maxLength",
                            JSONObject().apply {
                                put("type", "integer")
                                put("description", "Maximum characters to return. Default 20000.")
                            },
                        )
                    },
                )
                put("required", JSONArray().apply { put("url") })
            },
        ),
    )

    suspend fun execute(argumentsJson: String, toolCallId: String): ToolResult =
        withContext(Dispatchers.IO) {
            val args = try {
                JSONObject(argumentsJson)
            } catch (e: Exception) {
                return@withContext errorResult(toolCallId, "Invalid arguments JSON: ${e.message}")
            }
            val url = args.optString("url", "").takeIf { it.isNotEmpty() }
                ?: return@withContext errorResult(toolCallId, "Missing required argument: url")
            if (!url.startsWith("https://")) {
                return@withContext errorResult(toolCallId, "Only HTTPS URLs are supported.")
            }
            val maxLength = args.optInt("maxLength", MAX_CHARS)

            val request = Request.Builder().url(url).get().build()
            try {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext errorResult(toolCallId, "HTTP ${resp.code}")
                    }
                    val body = resp.body?.string().orEmpty()
                    val cleaned = stripHtml(body).take(maxLength)
                    val content = JSONObject().apply {
                        put("url", url)
                        put("content", cleaned)
                        put("truncated", body.length > maxLength)
                    }.toString()
                    ToolResult(toolCallId, "url_fetch", content)
                }
            } catch (e: Exception) {
                errorResult(toolCallId, "Fetch failed: ${e.message}")
            }
        }

    /** Naive HTML tag stripper — good enough for the LLM to read page text. */
    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun errorResult(toolCallId: String, message: String) = ToolResult(
        toolCallId = toolCallId,
        name = "url_fetch",
        content = JSONObject().apply { put("error", message) }.toString(),
        isError = true,
    )

    companion object {
        private const val MAX_CHARS = 20_000
    }
}
