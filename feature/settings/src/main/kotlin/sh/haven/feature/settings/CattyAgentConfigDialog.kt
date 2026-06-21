package sh.haven.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.haven.core.data.preferences.UserPreferencesRepository

/**
 * Configuration dialog for the Catty Agent (outbound AI agent). Lets the
 * user set the LLM provider (API key, base URL, model), permission
 * mode, and execution limits. Rendered from the Settings screen when
 * the user taps the "Catty Agent" row.
 */
@Composable
fun CattyAgentConfigDialog(
    apiKey: String,
    baseUrl: String,
    model: String,
    permissionMode: UserPreferencesRepository.CattyAgentPermissionMode,
    maxIterations: Int,
    commandTimeout: Int,
    onApiKeyChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onPermissionModeChange: (UserPreferencesRepository.CattyAgentPermissionMode) -> Unit,
    onMaxIterationsChange: (Int) -> Unit,
    onCommandTimeoutChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var apiKeyText by remember(apiKey) { mutableStateOf(apiKey) }
    var baseUrlText by remember(baseUrl) { mutableStateOf(baseUrl) }
    var modelText by remember(model) { mutableStateOf(model) }
    var maxIterText by remember(maxIterations) { mutableStateOf(maxIterations.toString()) }
    var timeoutText by remember(commandTimeout) { mutableStateOf(commandTimeout.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agent_config_section)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.agent_config_section_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = apiKeyText,
                    onValueChange = { apiKeyText = it; onApiKeyChange(it) },
                    label = { Text(stringResource(R.string.agent_config_api_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = baseUrlText,
                    onValueChange = { baseUrlText = it; onBaseUrlChange(it) },
                    label = { Text(stringResource(R.string.agent_config_base_url)) },
                    singleLine = true,
                    supportingText = {
                        Text("OpenAI-compatible: https://api.openai.com/v1, https://api.deepseek.com/v1, http://localhost:11434/v1 (Ollama)")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = modelText,
                    onValueChange = { modelText = it; onModelChange(it) },
                    label = { Text(stringResource(R.string.agent_config_model)) },
                    singleLine = true,
                    supportingText = { Text("e.g. gpt-4o-mini, deepseek-chat, qwen-plus") },
                    modifier = Modifier.fillMaxWidth(),
                )

                // Permission mode chips
                Text(
                    stringResource(R.string.agent_config_permission_mode),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UserPreferencesRepository.CattyAgentPermissionMode.entries.forEach { mode ->
                        FilterChip(
                            selected = permissionMode == mode,
                            onClick = { onPermissionModeChange(mode) },
                            label = { Text(modeLabel(mode)) },
                        )
                    }
                }

                OutlinedTextField(
                    value = maxIterText,
                    onValueChange = {
                        maxIterText = it
                        it.toIntOrNull()?.let { v -> onMaxIterationsChange(v) }
                    },
                    label = { Text(stringResource(R.string.agent_config_max_iterations)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = timeoutText,
                    onValueChange = {
                        timeoutText = it
                        it.toIntOrNull()?.let { v -> onCommandTimeoutChange(v) }
                    },
                    label = { Text(stringResource(R.string.agent_config_command_timeout)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_done))
            }
        },
    )
}

@Composable
private fun modeLabel(mode: UserPreferencesRepository.CattyAgentPermissionMode): String = when (mode) {
    UserPreferencesRepository.CattyAgentPermissionMode.OBSERVER -> stringResource(R.string.agent_mode_observer)
    UserPreferencesRepository.CattyAgentPermissionMode.CONFIRM -> stringResource(R.string.agent_mode_confirm)
    UserPreferencesRepository.CattyAgentPermissionMode.AUTONOMOUS -> stringResource(R.string.agent_mode_autonomous)
}
