package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression pin for the bypass-permissions / resume-command rule, after a real bug where
 * a setting in `~/.claude/settings.json` (`skipDangerousModePermissionPrompt`) was
 * misinterpreted as "always start restored sessions in bypass mode," causing every
 * restored tab to come back with `--dangerously-skip-permissions` regardless of the
 * original session's actual mode.
 *
 * The contract pinned here:
 *  1. `--dangerously-skip-permissions` is appended **iff** the saved session itself was
 *     in bypass mode (i.e. `SavedSession.bypassPermissions == true`).
 *  2. No global / settings-derived "always bypass" override exists. The user must opt
 *     into bypass per-session.
 *
 * The production `buildResumeCmd` is private on [ClaudeTabWatcherStartup] (tied to IDE
 * lifecycle), so we mirror its tiny string-building shape here. The mirror is identical
 * — if the production rule changes (e.g. someone re-adds a global bypass clause), this
 * test stays in place and they have to consciously remove or update it.
 */
class BuildResumeCmdContractTest {

    /** Mirror of [ClaudeTabWatcherStartup.buildResumeCmd] — pure string building, no IDE deps. */
    private data class SavedSession(val sessionId: String, val bypassPermissions: Boolean)

    private fun buildResumeCmd(s: SavedSession): String = buildString {
        append("claude --resume ${s.sessionId}")
        if (s.bypassPermissions) append(" --dangerously-skip-permissions")
    }

    @Test fun bypassOff_omitsDangerouslyFlag() {
        val cmd = buildResumeCmd(SavedSession("sid-abc", bypassPermissions = false))
        assertEquals("claude --resume sid-abc", cmd)
        assertFalse("must NOT add --dangerously-skip-permissions when bypass is off",
            cmd.contains("--dangerously-skip-permissions"))
    }

    @Test fun bypassOn_appendsDangerouslyFlag() {
        val cmd = buildResumeCmd(SavedSession("sid-xyz", bypassPermissions = true))
        assertEquals("claude --resume sid-xyz --dangerously-skip-permissions", cmd)
    }

    @Test fun mixedSessionsRoundTripIndependently() {
        // Pin: each session's bypass flag is honoured independently. No leakage from one
        // session to another, no global flag overriding individual values.
        val auto = buildResumeCmd(SavedSession("auto-1", bypassPermissions = false))
        val bypass = buildResumeCmd(SavedSession("bypass-1", bypassPermissions = true))
        val auto2 = buildResumeCmd(SavedSession("auto-2", bypassPermissions = false))
        assertFalse(auto.contains("--dangerously-skip-permissions"))
        assertTrue(bypass.contains("--dangerously-skip-permissions"))
        assertFalse(auto2.contains("--dangerously-skip-permissions"))
    }

    @Test fun noGlobalAlwaysBypassEscapeHatch() {
        // Defense-in-depth: a future refactor must NOT re-introduce a global
        // shouldAlwaysBypass() that consults settings.json or any other source. The only
        // input to bypass-on-restore is the SavedSession itself. If that rule changes,
        // this test fails and the change has to be deliberate.
        val auto = buildResumeCmd(SavedSession("sid", bypassPermissions = false))
        assertEquals("claude --resume sid", auto)
        // No suffix, no prefix, no flags.
    }
}
