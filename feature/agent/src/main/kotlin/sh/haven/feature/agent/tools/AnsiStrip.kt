package sh.haven.feature.agent.tools

/**
 * Strip ANSI escape sequences, OSC sequences, and other control bytes
 * from terminal output so the LLM sees clean text. Ported from
 * Netcatty's scrollback-to-model fit step.
 */
internal fun stripAnsiEscapes(input: String): String {
    val sb = StringBuilder(input.length)
    var i = 0
    while (i < input.length) {
        val c = input[i]
        when {
            // ESC [ ... terminator (CSI — colors, cursor moves, etc.)
            c == 0x1b.toChar() && i + 1 < input.length && input[i + 1] == '[' -> {
                i += 2
                while (i < input.length) {
                    val ch = input[i]
                    i++
                    if (ch in 'A'..'Z' || ch in 'a'..'z') break
                }
            }
            // ESC ] ... BEL or ST (OSC — title set, clipboard, hyperlinks)
            c == 0x1b.toChar() && i + 1 < input.length && input[i + 1] == ']' -> {
                i += 2
                while (i < input.length) {
                    val ch = input[i]
                    i++
                    if (ch == 0x07.toChar()) break // BEL
                    if (ch == 0x1b.toChar() && i < input.length && input[i] == '\\') {
                        i++
                        break // ST = ESC \
                    }
                }
            }
            // Other ESC sequences (single-char: ESC + one byte)
            c == 0x1b.toChar() -> {
                i += 2
            }
            // Carriage return — skip (terminal newline normalisation)
            c == '\r' -> {
                i++
            }
            // Other control chars (except \n and \t) — skip
            c.code < 0x20 && c != '\n' && c != '\t' -> {
                i++
            }
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}
