package sh.haven.feature.terminal.agent.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.util.Log
import java.io.IOException

/**
 * 加密偏好设置存储（对齐 Netcatty 的 credentialBridge）
 *
 * 功能：
 * 1. API Key 加密存储（使用 Android EncryptedSharedPreferences）
 * 2. 支持多个 AI 提供商配置
 * 3. 密钥使用 enc:v1: 前缀标识（对齐 Netcatty）
 */
class EncryptedPreferenceStore(context: Context) {
    companion object {
        private const val TAG = "EncryptedPreferenceStore"
        private const val PREF_NAME = "ai_secure_prefs"
        private const val KEY_PREFIX = "enc:v1:"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = try {
        EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create EncryptedSharedPreferences", e)
        null
    }

    /**
     * 保存 API Key（加密）
     *
     * @param providerId 提供商 ID
     * @param apiKey 原始 API Key
     * @return 是否保存成功
     */
    fun saveApiKey(providerId: String, apiKey: String): Boolean {
        return try {
            val prefs = encryptedPrefs ?: return false
            prefs.edit().putString(
                "api_key_$providerId",
                "$KEY_PREFIX$apiKey"  // 添加 enc:v1: 前缀（对齐 Netcatty）
            ).apply()
            Log.i(TAG, "Saved API key for provider: $providerId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save API key for provider: $providerId", e)
            false
        }
    }

    /**
     * 获取 API Key（解密）
     *
     * @param providerId 提供商 ID
     * @return 解密的 API Key，如果不存在则返回 null
     */
    fun getApiKey(providerId: String): String? {
        return try {
            val prefs = encryptedPrefs ?: return null
            val encryptedKey = prefs.getString("api_key_$providerId", null)
            if (encryptedKey.isNullOrBlank()) {
                null
            } else {
                // 移除 enc:v1: 前缀（对齐 Netcatty）
                encryptedKey.removePrefix(KEY_PREFIX)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get API key for provider: $providerId", e)
            null
        }
    }

    /**
     * 删除 API Key
     *
     * @param providerId 提供商 ID
     * @return 是否删除成功
     */
    fun deleteApiKey(providerId: String): Boolean {
        return try {
            val prefs = encryptedPrefs ?: return false
            prefs.edit().remove("api_key_$providerId").apply()
            Log.i(TAG, "Deleted API key for provider: $providerId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete API key for provider: $providerId", e)
            false
        }
    }

    /**
     * 检查指定提供商是否已配置 API Key
     */
    fun hasApiKey(providerId: String): Boolean {
        return !getApiKey(providerId).isNullOrBlank()
    }

    /**
     * 清除所有加密数据（用于重置或注销）
     */
    fun clearAll(): Boolean {
        return try {
            val prefs = encryptedPrefs ?: return false
            prefs.edit().clear().apply()
            Log.i(TAG, "Cleared all encrypted data")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all data", e)
            false
        }
    }
}
