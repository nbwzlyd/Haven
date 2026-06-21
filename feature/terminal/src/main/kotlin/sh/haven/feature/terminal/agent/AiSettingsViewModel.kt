package sh.haven.feature.terminal.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.haven.feature.terminal.agent.model.AiProviderConfig
import sh.haven.feature.terminal.agent.model.AiProviderId
import sh.haven.feature.terminal.agent.model.ProviderAdvancedParams
import sh.haven.feature.terminal.agent.storage.AiSettingsRepository
import javax.inject.Inject

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val repository: AiSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiSettingsUiState())
    val uiState: StateFlow<AiSettingsUiState> = _uiState.asStateFlow()

    init {
        loadProviders()
    }

    private fun loadProviders() {
        viewModelScope.launch {
            val providers = repository.getAllProviders()
            val activeProvider = repository.getActiveProvider()
            val permissionMode = repository.getPermissionMode()

            _uiState.value = _uiState.value.copy(
                providers = providers,
                selectedProvider = activeProvider,
                permissionMode = permissionMode
            )
        }
    }

    fun selectProvider(provider: AiProviderConfig) {
        _uiState.value = _uiState.value.copy(selectedProvider = provider)
    }

    fun updateApiKey(apiKey: String) {
        val currentProvider = _uiState.value.selectedProvider ?: return
        val updatedProvider = currentProvider.copy(apiKey = apiKey)
        _uiState.value = _uiState.value.copy(selectedProvider = updatedProvider)
    }

    fun updateBaseUrl(baseUrl: String) {
        val currentProvider = _uiState.value.selectedProvider ?: return
        val updatedProvider = currentProvider.copy(baseUrl = baseUrl)
        _uiState.value = _uiState.value.copy(selectedProvider = updatedProvider)
    }

    fun updateModel(model: String) {
        val currentProvider = _uiState.value.selectedProvider ?: return
        val updatedProvider = currentProvider.copy(defaultModel = model)
        _uiState.value = _uiState.value.copy(selectedProvider = updatedProvider)
    }

    fun updateTemperature(temperature: Float) {
        val currentProvider = _uiState.value.selectedProvider ?: return
        val currentAdvanced = currentProvider.advancedParams ?: ProviderAdvancedParams()
        val updatedAdvanced = currentAdvanced.copy(temperature = temperature)
        val updatedProvider = currentProvider.copy(advancedParams = updatedAdvanced)
        _uiState.value = _uiState.value.copy(selectedProvider = updatedProvider)
    }

    fun updatePermissionMode(mode: PermissionMode) {
        _uiState.value = _uiState.value.copy(permissionMode = mode)
    }

    fun saveSettings() {
        viewModelScope.launch {
            _uiState.value.selectedProvider?.let { provider ->
                repository.saveProvider(provider)
                repository.setActiveProvider(provider.id)
            }
            repository.setPermissionMode(_uiState.value.permissionMode)
            _uiState.value = _uiState.value.copy(saveSuccess = true)
        }
    }

    fun dismissSaveSuccess() {
        _uiState.value = _uiState.value.copy(saveSuccess = false)
    }
}

data class AiSettingsUiState(
    val providers: List<AiProviderConfig> = emptyList(),
    val selectedProvider: AiProviderConfig? = null,
    val permissionMode: PermissionMode = PermissionMode.CONFIRM,
    val saveSuccess: Boolean = false,
    val errorMessage: String? = null
)

enum class PermissionMode {
    OBSERVER,
    CONFIRM,
    AUTONOMOUS
}
