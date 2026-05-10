package sh.haven.feature.terminal.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.connectbot.terminal.ScrollController
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.TerminalEmulator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton index of live terminal handles by sessionId. Populated by
 * [sh.haven.feature.terminal.TerminalViewModel] when a tab is created
 * and by [sh.haven.feature.terminal.TerminalScreen] when its Compose
 * scope acquires the per-Composition controllers (selection, scroll).
 *
 * Exists so the MCP transport (`app/.../agent/McpTools.kt`) can read
 * and drive the terminal without going through a Compose scope: the
 * registry is the bridge between the agent thread and the controllers
 * that only exist while the Terminal composable is in the tree.
 *
 * Entries persist while the tab is open; they are cleared when the
 * tab is closed (TerminalViewModel) and the controller fields are
 * cleared/repopulated as the Composable enters/leaves the tree.
 */
@Singleton
class TerminalSessionRegistry @Inject constructor() {

    /**
     * Snapshot of one tab's agent-reachable handles. The emulator is
     * always present (created with the tab); selection/scroll are null
     * until the tab's Composable mounts and its callbacks fire.
     */
    data class Entry(
        val emulator: TerminalEmulator,
        val selectionController: SelectionController? = null,
        val scrollController: ScrollController? = null,
    )

    private val _sessions = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val sessions: StateFlow<Map<String, Entry>> = _sessions

    fun register(sessionId: String, emulator: TerminalEmulator) {
        _sessions.value = _sessions.value + (sessionId to Entry(emulator))
    }

    fun setSelectionController(sessionId: String, controller: SelectionController?) {
        val current = _sessions.value[sessionId] ?: return
        _sessions.value = _sessions.value + (sessionId to current.copy(selectionController = controller))
    }

    fun setScrollController(sessionId: String, controller: ScrollController?) {
        val current = _sessions.value[sessionId] ?: return
        _sessions.value = _sessions.value + (sessionId to current.copy(scrollController = controller))
    }

    fun unregister(sessionId: String) {
        _sessions.value = _sessions.value - sessionId
    }

    fun get(sessionId: String): Entry? = _sessions.value[sessionId]
}
