package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `~/.claude/settings.json` is the user's file and Claude Code refuses to start if it
 * can't be parsed, so these tests are mostly about what the patcher must *not* do.
 */
class ClaudeSettingsPatcherTest {

    private val perms = listOf("Bash(bash ~/.claude/rider-plugin/rename-tab.sh *)")

    @Suppress("UNCHECKED_CAST")
    private fun reparse(text: String) = MiniJson.parse(text) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun commandsFor(text: String, event: String): List<String> {
        val hooks = reparse(text)["hooks"] as? Map<String, Any?> ?: return emptyList()
        val groups = hooks[event] as? List<Any?> ?: return emptyList()
        return groups.flatMap { group ->
            ((group as Map<String, Any?>)["hooks"] as List<Any?>).map {
                (it as Map<String, Any?>)["command"] as String
            }
        }
    }

    // ── Install ───────────────────────────────────────────────────

    @Test fun emptyFile_getsEveryStatusEventPlusPermissions() {
        val out = ClaudeSettingsPatcher.patch(null, perms)!!
        for (event in ClaudeSettingsPatcher.STATUS_EVENTS) {
            assertTrue("missing hook for $event", commandsFor(out, event).any { it.endsWith("status-hook.sh $event") })
        }
        assertTrue(out.contains("rename-tab.sh"))
    }

    @Test fun sessionStart_keepsTheLegacySessionMapHookToo() {
        // The rename path depends on TERM_SESSION_ID → sessionId being written at start.
        val cmds = commandsFor(ClaudeSettingsPatcher.patch(null, perms)!!, "SessionStart")
        assertTrue(cmds.any { it.contains("session-start-hook.sh") })
        assertTrue(cmds.any { it.contains("status-hook.sh SessionStart") })
    }

    @Test fun preservesUnrelatedSettingsVerbatim() {
        val before = """
            {
              "statusLine": { "type": "command", "command": "bash ~/.claude/statusline-command.sh" },
              "theme": "dark",
              "editorMode": "vim",
              "enabledPlugins": { "swift-lsp@claude-plugins-official": true }
            }
        """.trimIndent()
        val after = reparse(ClaudeSettingsPatcher.patch(before, perms)!!)

        assertEquals("dark", after["theme"])
        assertEquals("vim", after["editorMode"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("command", (after["statusLine"] as Map<String, Any?>)["type"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(true, (after["enabledPlugins"] as Map<String, Any?>)["swift-lsp@claude-plugins-official"])
    }

    @Test fun preservesTheUsersOwnHooksOnTheSameEvents() {
        val before = """
            {
              "hooks": {
                "Stop": [
                  { "hooks": [ { "type": "command", "command": "say done" } ] }
                ],
                "PreToolUse": [
                  { "matcher": "Bash", "hooks": [ { "type": "command", "command": "audit.sh" } ] }
                ]
              }
            }
        """.trimIndent()
        val out = ClaudeSettingsPatcher.patch(before, perms)!!

        assertTrue(commandsFor(out, "Stop").contains("say done"))
        assertTrue(commandsFor(out, "Stop").any { it.contains("status-hook.sh Stop") })
        assertEquals(listOf("audit.sh"), commandsFor(out, "PreToolUse"))
        // Matchers on untouched events survive.
        assertTrue(out.contains("\"matcher\": \"Bash\""))
    }

    @Test fun isIdempotent_reRunningInstallsNothingTwice() {
        val once = ClaudeSettingsPatcher.patch(null, perms)!!
        assertNull("second patch should report no change", ClaudeSettingsPatcher.patch(once, perms))

        val twice = ClaudeSettingsPatcher.patch(once, perms) ?: once
        assertEquals(1, commandsFor(twice, "Stop").count { it.contains("status-hook.sh") })
    }

    @Test fun idempotentEvenIfTheUserEditedTheTimeoutOrPrefix() {
        // Matching on the script path + argument, not the whole command string, so a hand
        // edit doesn't get a duplicate installed alongside it on the next IDE start.
        val edited = """
            {
              "hooks": {
                "Stop": [
                  { "hooks": [ { "type": "command", "command": "/bin/bash ~/.claude/rider-plugin/status-hook.sh Stop", "timeout": 30 } ] }
                ]
              }
            }
        """.trimIndent()
        assertEquals(1, commandsFor(ClaudeSettingsPatcher.patch(edited, perms)!!, "Stop").size)
    }

    @Test fun permissionsAreDedupedAgainstExistingEntries() {
        val before = """{ "permissions": { "allow": ["Bash(ls)", "${perms[0]}"], "deny": [] } }"""
        // Nothing to add on the permissions side; hooks still get installed.
        val out = ClaudeSettingsPatcher.patch(before, perms)!!
        @Suppress("UNCHECKED_CAST")
        val allow = (reparse(out)["permissions"] as Map<String, Any?>)["allow"] as List<Any?>
        assertEquals(listOf("Bash(ls)", perms[0]), allow)
    }

    @Test fun malformedJson_isLeftStrictlyAlone() {
        // Rewriting a file we failed to parse would take Claude Code down.
        assertNull(ClaudeSettingsPatcher.patch("{ not json at all", perms))
        assertNull(ClaudeSettingsPatcher.patch("[1, 2, 3]", perms))
    }

    @Test fun outputIsValidJsonWithATrailingNewline() {
        val out = ClaudeSettingsPatcher.patch(null, perms)!!
        assertNotNull(MiniJson.parse(out))
        assertTrue(out.endsWith("\n"))
    }

    // ── Uninstall ─────────────────────────────────────────────────

    @Test fun unpatchRemovesOnlyOurHooksAndPermissions() {
        val installed = ClaudeSettingsPatcher.patch(
            """{ "hooks": { "Stop": [ { "hooks": [ { "type": "command", "command": "say done" } ] } ] }, "theme": "dark" }""",
            perms,
        )!!
        val cleaned = ClaudeSettingsPatcher.unpatch(installed, perms)!!

        assertFalse(cleaned.contains("status-hook.sh"))
        assertFalse(cleaned.contains("session-start-hook.sh"))
        assertFalse(cleaned.contains("rename-tab.sh"))
        assertEquals(listOf("say done"), commandsFor(cleaned, "Stop"))
        assertEquals("dark", reparse(cleaned)["theme"])
    }

    @Test fun unpatchDropsHookEventsItEmptied() {
        val installed = ClaudeSettingsPatcher.patch(null, perms)!!
        val cleaned = ClaudeSettingsPatcher.unpatch(installed, perms)!!
        @Suppress("UNCHECKED_CAST")
        val hooks = reparse(cleaned)["hooks"] as? Map<String, Any?>
        assertTrue("empty hook events should be removed, got $hooks", hooks == null || hooks.isEmpty())
    }

    @Test fun unpatchOnACleanFileReportsNoChange() {
        assertNull(ClaudeSettingsPatcher.unpatch("""{ "theme": "dark" }""", perms))
        assertNull(ClaudeSettingsPatcher.unpatch(null, perms))
        assertNull(ClaudeSettingsPatcher.unpatch("{ not json", perms))
    }
}
