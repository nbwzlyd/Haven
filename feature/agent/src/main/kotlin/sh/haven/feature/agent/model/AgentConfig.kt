package sh.haven.feature.agent.model

import sh.haven.core.data.preferences.UserPreferencesRepository

/**
 * Runtime configuration for the Catty Agent, snapshot from
 * [UserPreferencesRepository] at the start of a turn. Immutable so the
 * agent loop can read it without racing preference edits.
 */
data class AgentConfig(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val permissionMode: UserPreferencesRepository.CattyAgentPermissionMode,
    val maxIterations: Int,
    val commandTimeoutSeconds: Int,
) {
    /** True when the agent is configured enough to make an LLM call. */
    val isConfigured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
}
