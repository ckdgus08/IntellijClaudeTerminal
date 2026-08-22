package com.claudetabs

/** Shell command typed into a fresh Rider terminal when restoring a saved session. */
internal object AgentResumeCommand {
    fun build(provider: AgentKind, sessionId: String, bypassPermissions: Boolean): String? {
        val external = provider.toExternalSessionId(sessionId)
        if (!ClaudeTabsHelpers.isSafeSessionId(external)) return null
        return when (provider) {
            AgentKind.CLAUDE -> buildString {
                append("claude --resume $external")
                if (bypassPermissions) append(" --dangerously-skip-permissions")
            }
            AgentKind.CODEX -> "codex resume $external"
        }
    }
}
