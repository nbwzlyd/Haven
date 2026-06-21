package sh.haven.feature.terminal.agent.model

import androidx.annotation.Keep

/**
 * AI Provider 配置（完全对齐 Netcatty 的 ProviderConfig）
 *
 * 支持 OpenAI 标准接口（DeepSeek、Ollama、OpenRouter、通义等）
 * 字段：BaseURL / API Key / model / temperature / max_ctx
 */
@Keep
data class AiProviderConfig(
    val id: String,
    val providerId: AiProviderId,
    val name: String,
    val style: ProviderStyle? = null,
    val iconId: String? = null,
    val iconDataUrl: String? = null,
    val apiKey: String? = null,  // 加密存储，enc:v1: 前缀
    val baseUrl: String? = null,
    val defaultModel: String? = null,
    val customHeaders: Map<String, String>? = null,
    val enabled: Boolean = true,
    val skipTlsVerify: Boolean = false,
    val contextWindow: Int? = null,
    val modelContextWindows: Map<String, Int>? = null,
    val advancedParams: ProviderAdvancedParams? = null
)

/**
 * AI 提供商类型（对齐 Netcatty）
 */
@Keep
enum class AiProviderId(
    val displayName: String,
    val defaultBaseUrl: String,
    val modelsEndpoint: String? = null,
    val defaultModels: List<String> = emptyList()
) {
    OPENAI("OpenAI", "https://api.openai.com/v1", "/models"),
    ANTHROPIC("Anthropic", "https://api.anthropic.com", "/v1/models"),
    GOOGLE("Google AI", "https://generativelanguage.googleapis.com/v1beta"),
    OLLAMA("Ollama", "http://localhost:11434/v1", "/models", listOf(
        "llama3.3",
        "llama3.2",
        "qwen2.5",
        "deepseek-r1",
        "mistral-nemo"
    )),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "/models"),
    QWEN("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "/models", listOf(
        "qwen3.7-plus",
        "qwen3.7-max",
        "qwen3.6-plus",
        "qwen3.6-flash",
        "qwen3.5-plus",
        "qwen-plus",
        "qwen-max"
    )),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "/models", listOf(
        "deepseek-v4-flash",
        "deepseek-v4-pro",
        "deepseek-chat",
        "deepseek-reasoner"
    )),
    KIMI("Kimi", "https://api.moonshot.ai/v1", "/models", listOf(
        "kimi-k2.6",
        "kimi-k2.5",
        "moonshot-v1-128k",
        "moonshot-v1-32k"
    )),
    ZHIPU("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "/models", listOf(
        "glm-5.1",
        "glm-5",
        "glm-5-turbo",
        "glm-4.7",
        "glm-4.7-flash"
    )),
    DOUBAO("豆包", "https://ark.cn-beijing.volces.com/api/v3", "/models", listOf(
        "doubao-seed-2-0-pro-260215",
        "doubao-seed-2-0-lite-260215",
        "doubao-seed-2-0-mini-260215"
    )),
    MIMO("小米 MiMo", "https://api.xiaomimimo.com/v1", "/models", listOf(
        "mimo-v2.5-pro",
        "mimo-v2.5"
    )),
    CUSTOM("Custom", "", null, emptyList())
    ;

    /**
     * 解析协议类型（对齐 Netcatty 的 resolveProviderStyle）
     */
    fun resolveStyle(): ProviderStyle {
        return when (this) {
            ANTHROPIC -> ProviderStyle.ANTHROPIC
            GOOGLE -> ProviderStyle.GOOGLE
            else -> ProviderStyle.OPENAI
        }
    }
}

/**
 * 协议类型（对齐 Netcatty 的 ProviderStyle）
 */
@Keep
enum class ProviderStyle {
    OPENAI,     // OpenAI 兼容接口
    ANTHROPIC,  // Anthropic 接口
    GOOGLE      // Google AI 接口
}

/**
 * 高级参数（对齐 Netcatty 的 ProviderAdvancedParams）
 */
@Keep
data class ProviderAdvancedParams(
    val maxTokens: Int? = null,
    val temperature: Float? = null,      // 0–2
    val topP: Float? = null,            // 0–1
    val frequencyPenalty: Float? = null, // -2–2
    val presencePenalty: Float? = null   // -2–2
)

/**
 * 模型信息（对齐 Netcatty 的 ModelInfo）
 */
@Keep
data class ModelInfo(
    val id: String,
    val name: String,
    val providerId: AiProviderId,
    val contextWindow: Int? = null,
    val supportsTools: Boolean = true,
    val supportsStreaming: Boolean = true
)
