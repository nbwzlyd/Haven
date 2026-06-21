package sh.haven.feature.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import sh.haven.feature.agent.R
import sh.haven.feature.agent.model.AgentChatItem
import sh.haven.feature.agent.model.ToolCallStatus

/** A user-authored message, right-aligned with a primary-tinted bubble. */
@Composable
fun UserMessage(item: AgentChatItem.User) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                Text(
                    text = item.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** An assistant reply, left-aligned. Shows a typing cursor while streaming. */
@Composable
fun AssistantMessage(item: AgentChatItem.Assistant) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                Text(
                    text = if (item.text.isEmpty() && item.isStreaming) "…" else item.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * A tool-call card: shows the tool name, arguments, status, and result.
 * In confirm mode, shows Approve/Deny buttons while pending approval.
 * The result is collapsible (long command output shouldn't dominate the chat).
 */
@Composable
fun ToolCallCard(
    item: AgentChatItem.ToolCall,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
) {
    var resultExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header: tool name + status chip
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Filled.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                StatusChip(item.status)
            }

            // Arguments (the command for terminal_execute)
            if (item.arguments.isNotBlank()) {
                Spacer(Modifier.size(8.dp))
                val displayArgs = formatArguments(item.name, item.arguments)
                SelectionContainer {
                    Text(
                        text = displayArgs,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(6.dp),
                            )
                            .padding(8.dp),
                    )
                }
            }

            // Approval summary (shown while pending)
            if (item.status == ToolCallStatus.PENDING_APPROVAL && item.result.isNotBlank()) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = item.result,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Approve / Deny buttons
            if (item.status == ToolCallStatus.PENDING_APPROVAL) {
                Spacer(Modifier.size(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { onDeny(item.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.agent_tool_deny))
                    }
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = { onApprove(item.id) }) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.agent_tool_approve))
                    }
                }
            }

            // Result (collapsible)
            if (item.status == ToolCallStatus.COMPLETED && item.result.isNotBlank()) {
                Spacer(Modifier.size(8.dp))
                TextButton(
                    onClick = { resultExpanded = !resultExpanded },
                    modifier = Modifier.padding(start = 0.dp),
                ) {
                    Text(
                        if (resultExpanded) stringResource(R.string.agent_tool_hide_result)
                        else stringResource(R.string.agent_tool_show_result),
                    )
                }
                if (resultExpanded) {
                    SelectionContainer {
                        Text(
                            text = item.result,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (item.isError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surface,
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: ToolCallStatus) {
    val (text, color) = when (status) {
        ToolCallStatus.PENDING_APPROVAL -> stringResource(R.string.agent_tool_pending_approval) to MaterialTheme.colorScheme.tertiary
        ToolCallStatus.RUNNING -> stringResource(R.string.agent_tool_running) to MaterialTheme.colorScheme.primary
        ToolCallStatus.COMPLETED -> stringResource(R.string.agent_tool_completed) to MaterialTheme.colorScheme.secondary
        ToolCallStatus.DENIED -> stringResource(R.string.agent_tool_denied) to MaterialTheme.colorScheme.error
    }
    AssistChip(
        onClick = {},
        label = { Text(text, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(labelColor = color),
        modifier = Modifier.alpha(0.9f),
    )
}

/** Format the raw JSON arguments for display — extract the command for terminal_execute. */
private fun formatArguments(toolName: String, argumentsJson: String): String {
    if (toolName == "terminal_execute") {
        // Extract the "command" field from the JSON for a cleaner display.
        val commandMatch = Regex(""""command"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(argumentsJson)
        if (commandMatch != null) {
            return "$ " + commandMatch.groupValues[1]
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\\\", "\\")
        }
    }
    return argumentsJson
}

/** A red-tinted error banner. */
@Composable
fun ErrorBanner(item: AgentChatItem.Error) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = item.text,
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The bottom input bar with a text field and send/stop button. */
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isBusy: Boolean,
    isEnabled: Boolean,
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.agent_input_hint)) },
                enabled = isEnabled && !isBusy,
                maxLines = 5,
                keyboardOptions = KeyboardOptions.Default,
            )
            Spacer(Modifier.size(8.dp))
            if (isBusy) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.agent_stop))
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = isEnabled && text.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.agent_send))
                }
            }
        }
    }
}
