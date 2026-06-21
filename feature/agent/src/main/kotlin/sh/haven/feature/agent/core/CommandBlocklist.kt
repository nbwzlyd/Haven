package sh.haven.feature.agent.core

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Regex-based command blocklist — defense-in-depth against the most
 * destructive shell commands. Ported from Netcatty's
 * `lib/commandBlocklist.json` + `safety.ts`.
 *
 * This is NOT a security boundary (a determined model can obfuscate
 * past regex), but it catches the obvious foot-guns before they reach
 * the terminal. The real boundary is the user's approval in confirm
 * mode.
 *
 * Returns the human-readable reason when a command matches, or null
 * when the command is allowed.
 */
@Singleton
class CommandBlocklist @Inject constructor() {

    private val patterns: List<Pair<Regex, String>> = listOf(
        // rm -rf / (and variants like rm -rf /*, rm -fr /)
        Regex("""\brm\s+(-[a-zA-Z]*r[a-zA-Z]*f?|--recursive)\s+(/|/\*|/\s|~|~\/|\$\{?HOME\}?)""") to
            "recursive delete of root/home directory",
        Regex("""\brm\s+(-[a-zA-Z]*f[a-zA-Z]*r?|--force)\s+(/|/\*|/\s|~|~\/|\$\{?HOME\}?)""") to
            "force-recursive delete of root/home directory",
        // mkfs — format a filesystem
        Regex("""\bmkfs(\.\w+)?\s+/dev/""") to "filesystem format (mkfs on /dev/)",
        // dd to /dev/ — overwrite a block device
        Regex("""\bdd\s+.*of=/dev/(sd|nvme|hd|vd|mmcblk)""") to "raw write to block device (dd to /dev/)",
        // shutdown / reboot / poweroff / halt
        Regex("""\b(shutdown|reboot|poweroff|halt)\b""") to "system shutdown/reboot",
        // fork bombs
        Regex(""":\(\)\{.*\|.*&\s*\};\s*:""") to "fork bomb",
        Regex("""\bsudo\s+.*\b(shutdown|reboot|poweroff|halt|init\s+0|init\s+6)\b""") to "system shutdown/reboot",
        // chmod -R 777 / — world-writable everything
        Regex("""\bchmod\s+(-R\s+)?777\s+(/|/.*\s)\s*$""") to "world-writable chmod on root",
        // curl/wget piped to shell (remote code execution)
        Regex("""\b(curl|wget)\s+.*\|\s*(sudo\s+)?(sh|bash|zsh|fish)\b""") to "remote script execution (curl|sh)",
        // eval of remote content
        Regex("""\beval\s+.*\$\(""") to "eval of command substitution",
    )

    /**
     * Check [command] against the blocklist. Returns the reason string
     * when blocked, or null when allowed.
     */
    fun check(command: String): String? {
        for ((regex, reason) in patterns) {
            if (regex.containsMatchIn(command)) return reason
        }
        return null
    }
}
