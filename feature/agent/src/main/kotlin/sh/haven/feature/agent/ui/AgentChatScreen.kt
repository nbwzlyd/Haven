package sh.haven.feature.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.feature.agent.R
import sh.haven.feature.agent.model.AgentChatItem

/**
 * The Catty Agent chat screen. A vertically scrolling chat list with a
 * text input at the bottom. Renders user messages, assistant replies
 * (streaming), tool-call cards (with approve/deny buttons in confirm
 * mode), and error banners.
 *
 * Ported from Netcatty's `AIChatSidePanel` + `ChatMessageList`, adapted
 * to Compose and Haven's MVVM pattern.
 */
@Composable
fun AgentChatScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: AgentChatViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val config by viewModel.config.collectAsState()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    // Auto-scroll to the bottom when new items arrive.
    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) {
            listState.animateScrollToItem(items.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.agent_title))
                        Text(
                            text = if (config.isConfigured) {
                                "${config.model} · ${config.permissionMode.name.lowercase()}"
                            } else {
                                stringResource(R.string.agent_not_configured)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearConversation() }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.agent_clear))
                        }
                    }
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.SmartToy, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            // Chat list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                if (items.isEmpty()) {
                    item { EmptyState(isConfigured = config.isConfigured, onOpenSettings = onOpenSettings) }
                }
                items(items, key = { it.id }) { item ->
                    ChatItemRow(
                        item = item,
                        onApprove = viewModel::approveToolCall,
                        onDeny = viewModel::denyToolCall,
                    )
                }
                if (isBusy) {
                    item { StreamingIndicator() }
                }
            }

            // Input bar
            ChatInputBar(
                text = input,
                onTextChange = { input = it },
                onSend = {
                    if (input.isNotBlank()) {
                        viewModel.sendMessage(input)
                        input = ""
                    }
                },
                onStop = viewModel::stopTurn,
                isBusy = isBusy,
                isEnabled = config.isConfigured,
            )
        }
    }
}

@Composable
private fun EmptyState(isConfigured: Boolean, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.SmartToy,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.agent_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.agent_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.agent_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isConfigured) {
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.agent_open_settings))
            }
        }
    }
}

@Composable
private fun StreamingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "Catty is thinking…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChatItemRow(
    item: AgentChatItem,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
) {
    when (item) {
        is AgentChatItem.User -> UserMessage(item)
        is AgentChatItem.Assistant -> AssistantMessage(item)
        is AgentChatItem.ToolCall -> ToolCallCard(item, onApprove, onDeny)
        is AgentChatItem.Error -> ErrorBanner(item)
    }
}
