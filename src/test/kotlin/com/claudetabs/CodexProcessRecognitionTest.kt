package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexProcessRecognitionTest {
    private fun info(command: String, line: String) = CodexProcessRecognition.ProcessInfo(command, line)

    @Test fun recognisesNativeAndNpmCodex() {
        assertTrue(CodexProcessRecognition.isInteractive(info("/opt/bin/codex", "codex")))
        assertTrue(CodexProcessRecognition.isInteractive(info("node", "node /npm/@openai/codex/bin/codex.js resume abc")))
        assertTrue(CodexProcessRecognition.isInteractive(info("C:\\bin\\codex.exe", "codex.exe --sandbox workspace-write")))
    }

    @Test fun excludesAutomationAndServers() {
        for (subcommand in listOf("exec", "review", "app-server", "mcp-server", "remote-control", "cloud")) {
            assertFalse(subcommand, CodexProcessRecognition.isInteractive(info("/bin/codex", "codex $subcommand")))
        }
    }

    @Test fun doesNotMistakeArbitraryCodexTextForTheCli() {
        assertFalse(CodexProcessRecognition.looksLikeCodex(info("bash", "bash -lc echo codex")))
        assertFalse(CodexProcessRecognition.looksLikeCodex(info("node", "node server.js --label codex")))
    }

    @Test fun rawPromptNamingIsCompactAndSecretSafe() {
        assertEquals("Codex 탭 이름을 자동으로…", ClaudeTabsHelpers.promptName("Codex 탭 이름을 자동으로 만들어 줘"))
        val secretFixture = "sk-proj-" + "abcdefghijklmnopqrstuvwxyz0123456789"
        assertEquals(null, ClaudeTabsHelpers.promptName(secretFixture))
    }
}
