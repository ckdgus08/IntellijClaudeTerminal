package com.claudetabs

/** The terminal AI whose session is represented by a persisted tab. */
internal enum class AgentKind(val wireName: String, private val internalPrefix: String) {
    CLAUDE("claude", ""),
    CODEX("codex", "codex--");

    fun toInternalSessionId(rawSessionId: String): String =
        if (this == CLAUDE || rawSessionId.startsWith(internalPrefix)) rawSessionId
        else internalPrefix + rawSessionId

    fun toExternalSessionId(sessionId: String): String =
        if (this == CODEX && sessionId.startsWith(internalPrefix)) sessionId.removePrefix(internalPrefix)
        else sessionId

    companion object {
        /** Files written before multi-agent support had no provider and are Claude sessions. */
        fun fromWire(value: String?): AgentKind =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: CLAUDE

        fun fromInternalSessionId(sessionId: String): AgentKind =
            if (sessionId.startsWith(CODEX.internalPrefix)) CODEX else CLAUDE
    }
}
