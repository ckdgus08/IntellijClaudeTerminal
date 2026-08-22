package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentResumeCommandTest {
    @Test fun buildsClaudeCommandsWithoutChangingExistingPermissionSemantics() {
        assertEquals("claude --resume abc", AgentResumeCommand.build(AgentKind.CLAUDE, "abc", false))
        assertEquals(
            "claude --resume abc --dangerously-skip-permissions",
            AgentResumeCommand.build(AgentKind.CLAUDE, "abc", true),
        )
    }

    @Test fun buildsCodexResumeUsingTheExternalSessionId() {
        assertEquals(
            "codex resume 0198-cafe",
            AgentResumeCommand.build(AgentKind.CODEX, "codex--0198-cafe", false),
        )
        // Claude-only bypass mode must never leak into a Codex invocation.
        assertEquals(
            "codex resume 0198-cafe",
            AgentResumeCommand.build(AgentKind.CODEX, "codex--0198-cafe", true),
        )
    }

    @Test fun rejectsShellMetacharactersForBothProviders() {
        assertNull(AgentResumeCommand.build(AgentKind.CLAUDE, "x;whoami", false))
        assertNull(AgentResumeCommand.build(AgentKind.CODEX, "codex--x$(whoami)", false))
    }
}
