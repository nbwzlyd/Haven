package sh.haven.feature.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.rclone.ProviderOption
import sh.haven.core.rclone.RcloneClient
import javax.inject.Inject

/**
 * Drives credential entry for NON-OAuth rclone providers (#181). OAuth
 * providers (see [sh.haven.core.rclone.RCLONE_OAUTH_PROVIDERS]) obtain their
 * token via the browser flow on connect and need no fields here; everything
 * else (Filen, s3, b2, mega, sftp, webdav, ftp) declares required config
 * options that this view-model surfaces and then writes via `config/create`.
 *
 * Kept separate from the heavyweight ConnectionsViewModel so the editor can
 * `hiltViewModel()` it cheaply (mirrors how the Cloudflare fields use
 * TunnelViewModel).
 */
@HiltViewModel
class RcloneConfigViewModel @Inject constructor(
    private val rcloneClient: RcloneClient,
) : ViewModel() {

    /** Status of the most recent [configure] attempt, for inline UI feedback. */
    sealed interface Status {
        data object Idle : Status
        data object Working : Status
        data object Success : Status
        data class Error(val message: String) : Status
    }

    private val _options = MutableStateFlow<List<ProviderOption>>(emptyList())

    /** Required, non-advanced credential fields for the selected provider. */
    val options: StateFlow<List<ProviderOption>> = _options.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var loadedProvider: String? = null

    /**
     * Load the basic config fields for [provider] (all non-advanced options).
     * Not just the `required` ones: rclone marks many essential fields optional
     * because they have defaults (e.g. FTP's user/port/pass), so a required-only
     * filter would hide them and leave the provider unconfigurable. Safe to call
     * repeatedly; only re-queries rclone when the provider changes.
     */
    fun loadOptions(provider: String) {
        if (provider == loadedProvider) return
        loadedProvider = provider
        _status.value = Status.Idle
        if (provider.isBlank()) {
            _options.value = emptyList()
            return
        }
        viewModelScope.launch {
            val opts = withContext(Dispatchers.IO) {
                runCatching {
                    rcloneClient.initialize()
                    rcloneClient.listProviders()
                        .firstOrNull { it.name == provider }
                        ?.options
                        .orEmpty()
                        .filter { !it.advanced }
                }.getOrDefault(emptyList())
            }
            // Ignore a stale result if the user changed provider mid-load.
            if (provider == loadedProvider) _options.value = opts
        }
    }

    /**
     * Create/replace the rclone remote [remoteName] of type [provider] with the
     * collected [params] (rclone obscures password fields server-side), then
     * verify it by listing the root. On success the remote is persisted in
     * rclone.conf and a normal connect just finds it.
     */
    fun configure(remoteName: String, provider: String, params: Map<String, String>) {
        _status.value = Status.Working
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    rcloneClient.initialize()
                    if (remoteName in rcloneClient.listRemotes()) {
                        rcloneClient.deleteRemote(remoteName)
                    }
                    val state = rcloneClient.createRemote(remoteName, provider, params)
                    if (state.error.isNotEmpty()) error(state.error)
                    // A non-OAuth create with all params supplied should finish
                    // (option == null). A lingering prompt means a field is
                    // still missing — surface it rather than silently half-config.
                    state.option?.let { error("rclone still needs '${it.name}'") }
                    // Verify the credentials actually work.
                    rcloneClient.listDirectory(remoteName, "")
                }
            }
            _status.value = result.fold(
                onSuccess = { Status.Success },
                onFailure = { Status.Error(it.message ?: "Configuration failed") },
            )
        }
    }

    /** Reset status (e.g. when the user edits a field after an error). */
    fun clearStatus() {
        if (_status.value != Status.Working) _status.value = Status.Idle
    }
}
