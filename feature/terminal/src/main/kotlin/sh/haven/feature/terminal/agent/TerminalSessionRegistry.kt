package sh.haven.feature.terminal.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 终端会话注册器（对齐 Netcatty 的会话管理）
 *
 * 功能：
 * 1. 注册/注销终端会话
 * 2. 跟踪活跃会话
 * 3. 提供会话上下文给 AI Agent
 * 4. 不修改 Haven 原有终端代码，仅作为观察者
 *
 * 注意：这个类不直接操作终端，而是通过 Haven 的 TerminalViewModel 获取会话信息
 */
class TerminalSessionRegistry {
    companion object {
        private const val TAG = "TerminalSessionRegistry"
    }

    // 活跃会话列表
    private val _activeSessions = MutableStateFlow<List<TerminalSessionInfo>>(emptyList())
    val activeSessions: StateFlow<List<TerminalSessionInfo>> = _activeSessions.asStateFlow()

    // 当前活跃会话 ID
    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    /**
     * 注册终端会话（由 TerminalViewModel 调用）
     *
     * @param sessionInfo 会话信息
     */
    fun registerSession(sessionInfo: TerminalSessionInfo) {
        val current = _activeSessions.value.toMutableList()
        val existing = current.indexOfFirst { it.id == sessionInfo.id }
        if (existing >= 0) {
            current[existing] = sessionInfo
        } else {
            current.add(sessionInfo)
        }
        _activeSessions.value = current
        Log.d(TAG, "Registered session: ${sessionInfo.id}")
    }

    /**
     * 注销终端会话
     *
     * @param sessionId 会话 ID
     */
    fun unregisterSession(sessionId: String) {
        val current = _activeSessions.value.toMutableList()
        current.removeAll { it.id == sessionId }
        _activeSessions.value = current

        // 如果注销的是当前活跃会话，清空活跃状态
        if (_activeSessionId.value == sessionId) {
            _activeSessionId.value = current.firstOrNull()?.id
        }

        Log.d(TAG, "Unregistered session: $sessionId")
    }

    /**
     * 更新会话连接状态
     *
     * @param sessionId 会话 ID
     * @param connected 是否连接
     */
    fun updateConnectionStatus(sessionId: String, connected: Boolean) {
        val current = _activeSessions.value.toMutableList()
        val index = current.indexOfFirst { it.id == sessionId }
        if (index >= 0) {
            current[index] = current[index].copy(isConnected = connected)
            _activeSessions.value = current
        }
    }

    /**
     * 设置当前活跃会话
     *
     * @param sessionId 会话 ID
     */
    fun setActiveSession(sessionId: String?) {
        _activeSessionId.value = sessionId
        Log.d(TAG, "Active session set to: $sessionId")
    }

    /**
     * 获取当前活跃会话 ID
     */
    fun getActiveSessionId(): String? {
        return _activeSessionId.value
    }

    /**
     * 获取当前活跃会话信息
     */
    fun getActiveSession(): TerminalSessionInfo? {
        val sessionId = _activeSessionId.value ?: return null
        return _activeSessions.value.find { it.id == sessionId }
    }

    /**
     * 获取所有活跃会话
     */
    fun getActiveSessions(): List<TerminalSessionInfo> {
        return _activeSessions.value.filter { it.isConnected }
    }

    /**
     * 清除所有会话
     */
    fun clearAll() {
        _activeSessions.value = emptyList()
        _activeSessionId.value = null
        Log.d(TAG, "Cleared all sessions")
    }
}

/**
 * 终端会话信息（简化版，仅包含 AI Agent 需要的信息）
 *
 * 注意：这个类不操作终端，仅作为数据容器
 */
data class TerminalSessionInfo(
    val id: String,
    val hostname: String? = null,
    val label: String? = null,
    val os: String? = null,
    val username: String? = null,
    val protocol: String? = null,
    val shellType: String? = null,
    val deviceType: String? = null,
    val isConnected: Boolean = false,
    val currentWorkingDirectory: String? = null,
    val lastCommand: String? = null,
)
