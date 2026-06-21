package sh.haven.feature.agent.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Promise-based approval gate for the Catty Agent, ported from
 * Netcatty's `approvalGate.ts`. In confirm mode, each `terminal_execute`
 * call pauses here until the user approves or denies it in the UI (or
 * the timeout expires).
 *
 * The flow:
 * 1. The agent loop calls [requestApproval] with the tool name + args.
 *    It returns a [PendingApproval] containing a [CompletableDeferred].
 * 2. The agent loop emits the [PendingApproval] to the UI (via the
 *    ViewModel's approval flow), then `await()`s the deferred.
 * 3. The UI shows an approval card with Approve/Deny buttons.
 * 4. The user taps a button → the UI calls [resolve] with the decision.
 * 5. The deferred completes, the agent loop proceeds (or aborts the tool).
 *
 * If the user doesn't respond within [TIMEOUT_MS], the request
 * auto-denies (same as Netcatty's 5-minute timeout).
 */
@Singleton
class ApprovalGate @Inject constructor() {

    private val pending = ConcurrentHashMap<String, PendingApproval>()

    /**
     * One pending approval request. The [deferred] completes with `true`
     * when approved, `false` when denied or timed out.
     */
    data class PendingApproval(
        val id: String,
        val toolName: String,
        val argumentsJson: String,
        val summary: String,
        val deferred: CompletableDeferred<Boolean>,
    )

    /**
     * Create a pending approval request. The caller should emit the
     * returned [PendingApproval] to the UI, then `await()` its deferred.
     *
     * @param toolCallId The LLM's tool-call id — used as the key so the
     *   UI can resolve the approval by the same id it already has.
     */
    fun requestApproval(
        toolCallId: String,
        toolName: String,
        argumentsJson: String,
        summary: String,
    ): PendingApproval {
        val deferred = CompletableDeferred<Boolean>()
        val approval = PendingApproval(toolCallId, toolName, argumentsJson, summary, deferred)
        pending[toolCallId] = approval
        return approval
    }

    /**
     * Resolve a pending approval. Safe to call from the UI thread.
     * Returns true if the approval was found and resolved, false if it
     * was already gone (timed out or duplicate resolve).
     */
    fun resolve(id: String, approved: Boolean): Boolean {
        val approval = pending.remove(id) ?: return false
        return approval.deferred.complete(approved)
    }

    /**
     * Suspend until the user resolves the approval or the timeout
     * expires. Returns `true` when approved, `false` when denied or
     * timed out. Always cleans up the pending entry.
     */
    suspend fun await(approval: PendingApproval): Boolean {
        val result = withTimeoutOrNull(TIMEOUT_MS) { approval.deferred.await() }
        pending.remove(approval.id)
        return result ?: false
    }

    /** Drop a pending approval without resolving it (e.g. on cancel). */
    fun cancel(id: String) {
        pending.remove(id)
    }

    /** All currently-pending approvals (for UI display / debugging). */
    fun pendingApprovals(): List<PendingApproval> = pending.values.toList()

    companion object {
        /** 5-minute auto-deny timeout, matching Netcatty. */
        const val TIMEOUT_MS = 5L * 60 * 1000
    }
}
