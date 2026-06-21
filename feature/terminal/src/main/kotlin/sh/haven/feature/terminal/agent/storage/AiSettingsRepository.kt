package sh.haven.feature.terminal.agent.storage

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.feature.terminal.agent.model.*
import java.io.File

/**
 * AI 设置仓库（对齐 Netcatty 的配置管理）
 *
 * 功能：
 * 1. 管理多个 AI 提供商配置（增删改查）
 * 2. 保存/加载配置到本地文件
 * 3. 管理当前活跃的提供商和模型
 * 4. 管理权限模式和命令黑名单
 *
 * 配置文件格式（对齐 Netcatty 的 localStorage 格式）：
 * - providers.json: 提供商配置列表
 * - ai_settings.json: 全局 AI 设置
 */
class AiSettingsRepository(context: Context) {
    companion object {
        private const val TAG = "AiSettingsRepository"
        private const val PROVIDERS_FILE = "ai_providers.json"
        private const val SETTINGS_FILE = "ai_settings.json"
    }

    private val filesDir = context.filesDir
    private val encryptedStore = EncryptedPreferenceStore(context)

    // 内存缓存（避免频繁读取文件）
    private val _providers = MutableStateFlow<List<AiProviderConfig>>(emptyList())
    val providers: Flow<List<AiProviderConfig>> = _providers

    private val _activeProviderId = MutableStateFlow("")
    val activeProviderId: Flow<String> = _activeProviderId

    private val _activeModelId = MutableStateFlow("")
    val activeModelId: Flow<String> = _activeModelId

    private val _permissionMode = MutableStateFlow(PermissionMode.CONFIRM)
    val permissionMode: Flow<PermissionMode> = _permissionMode

    init {
        // 初始化时加载配置
        loadProviders()
        loadSettings()
    }

    /**
     * 加载提供商配置
     */
    private fun loadProviders() {
        try {
            val file = File(filesDir, PROVIDERS_FILE)
            if (!file.exists()) {
                _providers.value = emptyList()
                return
            }

            val jsonArray = JSONArray(file.readText())
            val providers = mutableListOf<AiProviderConfig>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val provider = parseProviderFromJson(obj)
                providers.add(provider)
            }

            _providers.value = providers
            Log.i(TAG, "Loaded ${providers.size} AI providers")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load providers", e)
            _providers.value = emptyList()
        }
    }

    /**
     * 保存提供商配置
     */
    private fun saveProviders() {
        try {
            val file = File(filesDir, PROVIDERS_FILE)
            val jsonArray = JSONArray()

            _providers.value.forEach { provider ->
                jsonArray.put(provider.toJson())
            }

            file.writeText(jsonArray.toString(2))
            Log.i(TAG, "Saved ${_providers.value.size} AI providers")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save providers", e)
        }
    }

    /**
     * 添加提供商配置
     */
    suspend fun addProvider(provider: AiProviderConfig): Boolean {
        return withContext(Dispatchers.IO) {
            val current = _providers.value.toMutableList()
            current.add(provider)
            _providers.value = current
            saveProviders()

            // 保存 API Key 到加密存储
            provider.apiKey?.let { key ->
                encryptedStore.saveApiKey(provider.id, key)
            }

            true
        }
    }

    /**
     * 更新提供商配置
     */
    suspend fun updateProvider(id: String, updates: (AiProviderConfig) -> AiProviderConfig): Boolean {
        return withContext(Dispatchers.IO) {
            val current = _providers.value.toMutableList()
            val index = current.indexOfFirst { it.id == id }
            if (index == -1) return@withContext false

            current[index] = updates(current[index])
            _providers.value = current
            saveProviders()
            true
        }
    }

    /**
     * 删除提供商配置
     */
    suspend fun deleteProvider(id: String): Boolean {
        return withContext(Dispatchers.IO) {
            val current = _providers.value.toMutableList()
            current.removeAll { it.id == id }
            _providers.value = current
            saveProviders()

            // 从加密存储中删除 API Key
            encryptedStore.deleteApiKey(id)

            // 如果删除的是当前活跃提供商，清空活跃状态
            if (_activeProviderId.value == id) {
                _activeProviderId.value = ""
                _activeModelId.value = ""
            }

            true
        }
    }

    /**
     * 设置活跃提供商
     */
    suspend fun setActiveProvider(providerId: String, modelId: String? = null) {
        _activeProviderId.value = providerId
        _activeModelId.value = modelId ?: ""
        saveSettings()
    }

    /**
     * 加载全局设置
     */
    private fun loadSettings() {
        try {
            val file = File(filesDir, SETTINGS_FILE)
            if (!file.exists()) return

            val json = JSONObject(file.readText())

            _activeProviderId.value = json.optString("activeProviderId", "")
            _activeModelId.value = json.optString("activeModelId", "")
            _permissionMode.value = try {
                PermissionMode.valueOf(json.optString("permissionMode", "CONFIRM"))
            } catch (e: Exception) {
                PermissionMode.CONFIRM
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load settings", e)
        }
    }

    /**
     * 保存全局设置
     */
    private fun saveSettings() {
        try {
            val file = File(filesDir, SETTINGS_FILE)
            val json = JSONObject().apply {
                put("activeProviderId", _activeProviderId.value)
                put("activeModelId", _activeModelId.value)
                put("permissionMode", _permissionMode.value.name)
            }
            file.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save settings", e)
        }
    }

    /**
     * 获取活跃的提供商配置
     */
    suspend fun getActiveProvider(): AiProviderConfig? {
        val providerId = _activeProviderId.value
        if (providerId.isBlank()) return null
        return _providers.value.find { it.id == providerId }
    }

    /**
     * 解析 JSON 到 AiProviderConfig
     */
    private fun parseProviderFromJson(obj: JSONObject): AiProviderConfig {
        return AiProviderConfig(
            id = obj.getString("id"),
            providerId = AiProviderId.valueOf(obj.getString("providerId")),
            name = obj.getString("name"),
            style = obj.optString("style", null)?.let { ProviderStyle.valueOf(it) },
            apiKey = null, // API Key 从加密存储读取
            baseUrl = obj.optString("baseUrl", null),
            defaultModel = obj.optString("defaultModel", null),
            enabled = obj.optBoolean("enabled", true),
            skipTlsVerify = obj.optBoolean("skipTlsVerify", false),
            contextWindow = if (obj.has("contextWindow")) obj.getInt("contextWindow") else null,
            advancedParams = parseAdvancedParams(obj.optJSONObject("advancedParams"))
        )
    }

    /**
     * 解析高级参数
     */
    private fun parseAdvancedParams(obj: JSONObject?): ProviderAdvancedParams? {
        if (obj == null) return null
        return ProviderAdvancedParams(
            maxTokens = if (obj.has("maxTokens")) obj.getInt("maxTokens") else null,
            temperature = if (obj.has("temperature")) obj.getDouble("temperature").toFloat() else null,
            topP = if (obj.has("topP")) obj.getDouble("topP").toFloat() else null,
            frequencyPenalty = if (obj.has("frequencyPenalty")) obj.getDouble("frequencyPenalty").toFloat() else null,
            presencePenalty = if (obj.has("presencePenalty")) obj.getDouble("presencePenalty").toFloat() else null
        )
    }

    /**
     * 扩展函数：AiProviderConfig 转 JSON
     */
    private fun AiProviderConfig.toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("providerId", providerId.name)
            put("name", name)
            style?.let { put("style", it.name) }
            put("baseUrl", baseUrl)
            put("defaultModel", defaultModel)
            put("enabled", enabled)
            put("skipTlsVerify", skipTlsVerify)
            contextWindow?.let { put("contextWindow", it) }
            advancedParams?.let { put("advancedParams", it.toJson()) }
        }
    }

    /**
     * 扩展函数：ProviderAdvancedParams 转 JSON
     */
    private fun ProviderAdvancedParams.toJson(): JSONObject {
        return JSONObject().apply {
            maxTokens?.let { put("maxTokens", it) }
            temperature?.let { put("temperature", it) }
            topP?.let { put("topP", it) }
            frequencyPenalty?.let { put("frequencyPenalty", it) }
            presencePenalty?.let { put("presencePenalty", it) }
        }
    }
}
