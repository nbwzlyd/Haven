package sh.haven.feature.terminal.agent.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.ui.R
import sh.haven.feature.terminal.agent.AiSettingsViewModel
import sh.haven.feature.terminal.agent.model.AiProviderConfig
import sh.haven.feature.terminal.agent.model.AiProviderId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    viewModel: AiSettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Provider selection
            item {
                Text(
                    text = stringResource(R.string.agent_provider),
                    style = MaterialTheme.typography.labelLarge
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Provider dropdown
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = uiState.selectedProvider?.name ?: "Select provider",
                        onValueChange = { },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        uiState.providers.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.name) },
                                onClick = {
                                    viewModel.selectProvider(provider)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            // API Key
            item {
                Text(
                    text = stringResource(R.string.agent_api_key),
                    style = MaterialTheme.typography.labelLarge
                )

                Spacer(modifier = Modifier.height(8.dp))

                var apiKeyVisible by remember { mutableStateOf(false) }

                OutlinedTextField(
                    value = uiState.selectedProvider?.apiKey ?: "",
                    onValueChange = { viewModel.updateApiKey(it) },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (apiKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle API key visibility"
                            )
                        }
                    },
                    placeholder = { Text("Enter API key") }
                )
            }

            // Base URL (for custom providers)
            item {
                Text(
                    text = stringResource(R.string.agent_base_url),
                    style = MaterialTheme.typography.labelLarge
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = uiState.selectedProvider?.baseUrl ?: "",
                    onValueChange = { viewModel.updateBaseUrl(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://api.openai.com/v1") }
                )
            }

            // Model
            item {
                Text(
                    text = stringResource(R.string.agent_model),
                    style = MaterialTheme.typography.labelLarge
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = uiState.selectedProvider?.defaultModel ?: "",
                    onValueChange = { viewModel.updateModel(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("gpt-4, deepseek-chat, etc.") }
                )
            }

            // Temperature
            item {
                Text(
                    text = stringResource(R.string.agent_temperature),
                    style = MaterialTheme.typography.labelLarge
                )

                Spacer(modifier = Modifier.height(8.dp))

                Slider(
                    value = uiState.selectedProvider?.advancedParams?.temperature ?: 0.7f,
                    onValueChange = { viewModel.updateTemperature(it) },
                    valueRange = 0f..2f,
                    steps = 20
                )

                Text(
                    text = "%.2f".format(uiState.selectedProvider?.advancedParams?.temperature ?: 0.7f),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Permission mode
            item {
                Text(
                    text = stringResource(R.string.agent_permission_mode),
                    style = MaterialTheme.typography.labelLarge
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = uiState.permissionMode == PermissionMode.OBSERVER,
                            onClick = { viewModel.updatePermissionMode(PermissionMode.OBSERVER) }
                        )
                        Text(stringResource(R.string.agent_permission_observer))
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = uiState.permissionMode == PermissionMode.CONFIRM,
                            onClick = { viewModel.updatePermissionMode(PermissionMode.CONFIRM) }
                        )
                        Text(stringResource(R.string.agent_permission_confirm))
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = uiState.permissionMode == PermissionMode.AUTONOMOUS,
                            onClick = { viewModel.updatePermissionMode(PermissionMode.AUTONOMOUS) }
                        )
                        Text(stringResource(R.string.agent_permission_autonomous))
                    }
                }
            }

            // Save button
            item {
                Button(
                    onClick = { viewModel.saveSettings() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.common_save))
                }
            }
        }
    }
}

enum class PermissionMode {
    OBSERVER,
    CONFIRM,
    AUTONOMOUS
}
