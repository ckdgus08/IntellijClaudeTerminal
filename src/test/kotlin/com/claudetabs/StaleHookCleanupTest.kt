package com.claudetabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hooks left behind by a previous version's script directory must be removed, not just
 * out-numbered.
 *
 * `ensureHooks` only ever adds, which is correct for keeping an install current but leaves
 * nothing to undo a path change. When this plugin's state directory was renamed, the entries
 * pointing at the old one stayed in `settings.json` — and a hook whose script has moved does
 * not fail quietly: Claude runs `bash <missing path>` on every event, in every session on the
 * machine, forever.
 */
class StaleHookCleanupTest {

    private val stale = """
        {
          "hooks": {
            "Stop": [
              {"hooks": [{"type": "command", "command": "bash ~/.claude/rider-plugin/status-hook.sh Stop", "timeout": 5}]}
            ],
            "SessionStart": [
              {"hooks": [{"type": "command", "command": "bash ~/.claude/rider-plugin/session-start-hook.sh", "timeout": 5}]}
            ]
          }
        }
    """.trimIndent()

    @Test fun removesHooksPointingAtTheOldDirectory() {
        val out = ClaudeSettingsPatcher.patch(stale, listOf("Bash(x)"))
        assertNotNull("the file had stale entries, so something must change", out)
        assertFalse("no hook may still point at the old directory", out!!.contains("rider-plugin"))
    }

    @Test fun installsTheCurrentHooksInTheirPlace() {
        val out = ClaudeSettingsPatcher.patch(stale, listOf("Bash(x)"))!!
        for (event in ClaudeSettingsPatcher.STATUS_EVENTS) {
            assertTrue("$event must be registered", out.contains("status-hook.sh $event"))
        }
        assertTrue(out.contains("intellij-claude-terminal/session-start-hook.sh"))
    }

    /** A hook someone else installed is not ours to delete. */
    @Test fun leavesUnrelatedHooksAlone() {
        val other = """
            {"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "bash ~/scripts/my-own.sh"}]}]}}
        """.trimIndent()
        val out = ClaudeSettingsPatcher.patch(other, emptyList())!!
        assertTrue("an unrelated hook must survive", out.contains("my-own.sh"))
    }

    /** Nothing stale and nothing missing means nothing written. */
    @Test fun doesNotRewriteAFileThatIsAlreadyCorrect() {
        val once = ClaudeSettingsPatcher.patch(null, listOf("Bash(x)"))!!
        assertNull(ClaudeSettingsPatcher.patch(once, listOf("Bash(x)")))
    }

    private fun assertNull(v: Any?) = org.junit.Assert.assertNull(v)
}
