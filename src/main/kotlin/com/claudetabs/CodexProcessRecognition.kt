package com.claudetabs

/** Pure command-line recognition for an interactive Codex CLI process. */
internal object CodexProcessRecognition {
    data class ProcessInfo(val command: String, val commandLine: String)

    private val NON_INTERACTIVE_SUBCOMMANDS = setOf(
        "exec", "e", "review", "app-server", "mcp-server", "remote-control", "cloud",
    )

    fun looksLikeCodex(info: ProcessInfo): Boolean {
        val command = info.command.replace('\\', '/').substringAfterLast('/').lowercase()
        val line = info.commandLine.lowercase()
        return command == "codex" || command == "codex.exe" || command == "codex.cmd" ||
            line.contains("@openai/codex") || line.contains("/codex/bin/codex")
    }

    fun isInteractive(info: ProcessInfo): Boolean {
        if (!looksLikeCodex(info)) return false
        val tokens = info.commandLine
            .split(Regex("\\s+"))
            .map { it.trim('"', '\'', ' ') }
            .filter { it.isNotBlank() }
        val executableIndex = tokens.indexOfFirst { token ->
            val lower = token.replace('\\', '/').lowercase()
            lower.substringAfterLast('/').let { it == "codex" || it == "codex.exe" || it == "codex.cmd" || it == "codex.js" } ||
                lower.contains("@openai/codex") || lower.contains("/codex/bin/codex")
        }
        if (executableIndex < 0) return true
        val subcommand = tokens.drop(executableIndex + 1).firstOrNull { !it.startsWith('-') }?.lowercase()
        return subcommand !in NON_INTERACTIVE_SUBCOMMANDS
    }
}
