package sh.haven.feature.terminal.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.ui.R
import sh.haven.feature.terminal.agent.AgentChatViewModel.UiMessage

@Composable
fun AgentChatScreen(
    viewModel: AgentChatViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to latest message
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top App Bar
        AgentChatTopBar(
            onNavigateBack = onNavigateBack,
            onClearChat = { viewModel.clearChat() }
        )

        // Messages List
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(uiState.messages) { message ->
                MessageBubble(message = message)
            }

            // Thinking indicator
            if (uiState.isThinking) {
                item {
                    AgentThinkingIndicator()
                }
            }
        }

        // Input Bar
        AgentChatInputBar(
            inputText = uiState.inputText,
            onInputChange = { viewModel.updateInputText(it) },
            onSendMessage = { viewModel.sendMessage() },
            isSending = uiState.isSending
        )
    }
}
