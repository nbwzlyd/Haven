package sh.haven.app.agent.mailrules

import org.json.JSONObject
import sh.haven.app.agent.McpServer
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.db.entities.MailRulePendingAction
import sh.haven.core.data.mailrule.ImapFilterOp
import sh.haven.core.data.mailrule.MailRuleAction
import sh.haven.core.data.mailrule.MailRuleJson
import sh.haven.core.data.mailrule.staticDestructive
import sh.haven.core.data.repository.MailRuleRepository
import sh.haven.core.mail.MailSessionManager
import sh.haven.feature.mail.MimeParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real [MailRuleActionRunner]. Curated tool-backed actions (save / run / notify /
 * send-to-agent / invoke-any-tool) route through [McpServer.callToolUnconsented] — reusing
 * every existing MCP tool, with the enabled rule as the standing authorization (the
 * interactive consent gate is bypassed; the tool's own audit still records the call). IMAP
 * filter ops and the pending-action queue go direct.
 *
 * Background-safety posture: a SAFE action always runs; a DESTRUCTIVE one (move/delete/
 * forward/run-command, or an `invoke_mcp_tool` whose target tool is non-NEVER) runs only when
 * the app is foreground — otherwise it is queued for foreground approval + a notification.
 */
@Singleton
class MailRuleActionExecutor @Inject constructor(
    // Lazy breaks the Dagger cycle McpServer → MailWatchManager → executor → McpServer:
    // the executor only needs McpServer at fire time, long after construction.
    private val mcpServerLazy: dagger.Lazy<McpServer>,
    private val mailSessionManager: MailSessionManager,
    private val repo: MailRuleRepository,
) : MailRuleActionRunner {

    private val mcpServer: McpServer get() = mcpServerLazy.get()

    override suspend fun run(action: MailRuleAction, ctx: ActionContext, foreground: Boolean): ActionOutcome {
        if (isDestructive(action) && !foreground) {
            repo.queuePendingAction(
                MailRulePendingAction(
                    ruleId = ctx.ruleId, profileId = ctx.profileId, folderId = ctx.folderId,
                    uid = ctx.uid, messageId = ctx.messageId, messageSubject = ctx.message.subject,
                    actionJson = MailRuleJson.actionsToJson(listOf(action)),
                ),
            )
            notify("Mail rule: action queued", "${ctx.ruleName} — approve a destructive action for \"${ctx.message.subject}\"")
            return ActionOutcome(ok = true, summary = "queued for foreground approval", queued = true)
        }
        return try {
            executeNow(action, ctx)
        } catch (e: Exception) {
            ActionOutcome(false, "error: ${e.message}")
        }
    }

    override suspend fun notifyRuleFired(ruleName: String, subject: String) {
        notify("Mail rule fired: $ruleName", subject)
    }

    /** A curated action's static class, or — for invoke_mcp_tool — the target tool's consent level. */
    private fun isDestructive(action: MailRuleAction): Boolean = when (val d = action.staticDestructive()) {
        true -> true
        false -> false
        null -> {
            val name = (action as MailRuleAction.InvokeMcpTool).toolName
            (mcpServer.toolConsentLevel(name) ?: ConsentLevel.EVERY_CALL) != ConsentLevel.NEVER
        }
    }

    private suspend fun executeNow(action: MailRuleAction, ctx: ActionContext): ActionOutcome = when (action) {
        is MailRuleAction.Notify -> call(
            "raise_notification",
            JSONObject().put("title", subst(action.titleTemplate, ctx)).put("body", subst(action.bodyTemplate, ctx)),
        )
        is MailRuleAction.SendToAgent -> call(
            "send_to_agent",
            JSONObject().put("message", subst(action.messageTemplate, ctx))
                .apply { action.targetSessionId?.let { put("sessionId", it) } },
        )
        is MailRuleAction.RunCommand -> call(
            "run_in_proot",
            JSONObject().put("command", subst(action.template, ctx, shell = true)),
        )
        is MailRuleAction.InvokeMcpTool -> {
            val args = runCatching { JSONObject(subst(action.argsTemplateJson, ctx, json = true)) }.getOrDefault(JSONObject())
            call(action.toolName, args)
        }
        is MailRuleAction.SaveAttachments -> saveAttachments(action, ctx)
        is MailRuleAction.ImapFilter -> imapFilter(action, ctx)
        is MailRuleAction.Forward ->
            // Proper RFC forwarding (re-MIME the original) is a deferred follow-up.
            ActionOutcome(false, "forward not implemented yet")
    }

    private suspend fun saveAttachments(a: MailRuleAction.SaveAttachments, ctx: ActionContext): ActionOutcome {
        val client = mailSessionManager.clientForProfile(ctx.profileId)
            ?: return ActionOutcome(false, "save: account not connected")
        val sid = mailSessionManager.getSessionIdForProfile(ctx.profileId)
            ?: return ActionOutcome(false, "save: no session")
        val raw = client.getMessageRaw(sid, ctx.messageId)
        val matching = MimeParser.listAttachments(raw).filter {
            globMatches(it.filename, a.nameGlob) && globMatches(it.mimeType, a.mimeGlob)
        }
        if (matching.isEmpty()) return ActionOutcome(true, "no matching attachments")
        var saved = 0
        for (att in matching) {
            val res = call(
                "save_mail_attachment",
                JSONObject()
                    .put("profileId", ctx.profileId).put("messageId", ctx.messageId)
                    .put("attachmentIndex", att.index)
                    .put("destProfileId", a.destProfileId).put("destPath", a.destDir),
            )
            if (res.ok) saved++
        }
        return ActionOutcome(saved > 0, "saved $saved/${matching.size} attachment(s)")
    }

    private suspend fun imapFilter(a: MailRuleAction.ImapFilter, ctx: ActionContext): ActionOutcome {
        val client = mailSessionManager.clientForProfile(ctx.profileId)
            ?: return ActionOutcome(false, "imap: account not connected")
        val sid = mailSessionManager.getSessionIdForProfile(ctx.profileId)
            ?: return ActionOutcome(false, "imap: no session")
        return try {
            when (a.op) {
                ImapFilterOp.MARK_READ -> client.setSeen(sid, ctx.messageId, true)
                ImapFilterOp.MARK_UNREAD -> client.setSeen(sid, ctx.messageId, false)
                ImapFilterOp.SET_FLAGGED -> client.setFlagged(sid, ctx.messageId, true)
                ImapFilterOp.UNSET_FLAGGED -> client.setFlagged(sid, ctx.messageId, false)
                ImapFilterOp.MOVE -> {
                    val dest = a.destFolderId ?: return ActionOutcome(false, "move: no destination folder")
                    client.moveMessage(sid, ctx.messageId, dest)
                }
                ImapFilterOp.DELETE -> client.deleteMessage(sid, ctx.messageId)
            }
            ActionOutcome(true, "imap:${a.op}")
        } catch (e: Exception) {
            ActionOutcome(false, "imap:${a.op} failed: ${e.message}")
        }
    }

    private suspend fun call(name: String, args: JSONObject): ActionOutcome = try {
        val res = mcpServer.callToolUnconsented(name, args)
        if (res.optBoolean("isError", false)) ActionOutcome(false, "$name returned an error") else ActionOutcome(true, name)
    } catch (e: Exception) {
        ActionOutcome(false, "$name failed: ${e.message}")
    }

    private suspend fun notify(title: String, body: String) {
        runCatching { mcpServer.callToolUnconsented("raise_notification", JSONObject().put("title", title).put("body", body)) }
    }

    /** Substitute the email-derived placeholders into a template. Shell- or JSON-escapes values. */
    private fun subst(template: String, ctx: ActionContext, shell: Boolean = false, json: Boolean = false): String {
        fun esc(v: String) = when {
            shell -> "'" + v.replace("'", "'\\''") + "'"
            json -> v.replace("\\", "\\\\").replace("\"", "\\\"")
            else -> v
        }
        val m = ctx.message
        return template
            .replace("{from}", esc(m.fromAddress))
            .replace("{fromName}", esc(m.fromName))
            .replace("{subject}", esc(m.subject))
            .replace("{to}", esc(m.toAddresses.joinToString(", ")))
            .replace("{uid}", ctx.uid.toString())
    }

    /** Null/blank glob matches everything; `*`/`?` wildcards, case-insensitive, anchored. */
    private fun globMatches(value: String, glob: String?): Boolean {
        if (glob.isNullOrBlank()) return true
        val sb = StringBuilder()
        for (ch in glob) when (ch) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            else -> sb.append(Regex.escape(ch.toString()))
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE).matches(value)
    }
}
