package com.claudetabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Not painting `✕` on a restored tab whose Claude hasn't started yet.
 *
 * On every IDE start the restore spawns the tabs first and `claude --resume` starts inside
 * them a few seconds later. In that window the only session file on disk for the id is the
 * dead one from before the restart, so the status resolves to EXITED — measured on a real
 * start as a 4-second `✕` across the whole tab strip:
 *
 *   21:21:12  c0a4f05e - → EXITED ('plugin')
 *   21:21:16  c0a4f05e EXITED → FINISHED ('plugin')
 *
 * The suppression is deliberately narrow. A session that genuinely died must still reach
 * `✕`, so the hold only applies while all three conditions hold at once.
 */
class ExitedGraceTest {

    private val grace = ClaudeTabsHelpers.EXITED_GRACE_MS

    @Test fun holdsBackTheFlashOnAFreshlyRestoredTab() {
        assertFalse(
            ClaudeTabsHelpers.shouldPaintExited(
                spawnedAtMs = 1_000L,
                everSeenRunning = false,
                now = 1_000L + grace / 2,
            )
        )
    }

    /** The hold is a delay, not a veto — a tab that never comes up still ends on `✕`. */
    @Test fun believesExitedOnceTheGraceExpires() {
        assertTrue(
            ClaudeTabsHelpers.shouldPaintExited(
                spawnedAtMs = 1_000L,
                everSeenRunning = false,
                now = 1_000L + grace,
            )
        )
    }

    /**
     * The whole point of the indicator: a session that was running and then died is real
     * news, and must show immediately rather than waiting out a grace it doesn't need.
     */
    @Test fun neverDelaysARealDeath() {
        assertTrue(
            ClaudeTabsHelpers.shouldPaintExited(
                spawnedAtMs = 1_000L,
                everSeenRunning = true,
                now = 1_001L,
            )
        )
    }

    /** A tab the plugin didn't spawn had no restore to be mid-flight, so nothing to wait for. */
    @Test fun believesExitedForTabsWeDidNotSpawn() {
        assertTrue(
            ClaudeTabsHelpers.shouldPaintExited(
                spawnedAtMs = null,
                everSeenRunning = false,
                now = 1_000L,
            )
        )
    }

    @Test fun graceIsLongEnoughForAResumeToBoot() {
        assertTrue("a long transcript takes several seconds to resume", grace >= 10_000L)
    }
}
