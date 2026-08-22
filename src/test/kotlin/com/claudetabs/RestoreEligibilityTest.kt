package com.claudetabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression pin for a duplicate-tab bug during plugin reload.
 *
 * Installing the plugin can reload the project without killing active sessions. A restore
 * file written just before the reload still lists those sessions; restoring them blindly
 * would create a duplicate beside each original tab.
 */
class RestoreEligibilityTest {

    private fun live(vararg pairs: Pair<String, String>) =
        pairs.map { ClaudeTabsHelpers.LiveSession(it.first, it.second) }

    @Test fun aDeadSessionIsRestorable() {
        assertTrue(ClaudeTabsHelpers.shouldRestoreSession("sid-gone", live("sid-other" to "sid-other")))
        assertTrue(ClaudeTabsHelpers.shouldRestoreSession("sid-gone", emptyList()))
    }

    @Test fun aRunningSessionIsNotRestorable() {
        assertFalse(ClaudeTabsHelpers.shouldRestoreSession("sid-live", live("sid-live" to "sid-live")))
    }

    @Test fun matchesAResumedSessionUnderEitherId() {
        // The restore file stores canonical ids; a process resumed with `claude --resume`
        // reports a rotated one. Comparing only the reported id would miss exactly the
        // sessions most likely to be duplicated — the ones already resumed once.
        val running = live("rotated-abc" to "canonical-xyz")
        assertFalse(ClaudeTabsHelpers.shouldRestoreSession("canonical-xyz", running))
        assertFalse(ClaudeTabsHelpers.shouldRestoreSession("rotated-abc", running))
        assertTrue(ClaudeTabsHelpers.shouldRestoreSession("unrelated", running))
    }

    @Test fun theObservedInstallScenarioSpawnsNothing() {
        val first = "80000001-0000-4000-8000-000000000001"
        val second = "80000002-0000-4000-8000-000000000002"
        val restoreFile = listOf(first, second)
        val running = live(
            first to first,
            second to second,
        )
        assertTrue(
            "a project reload must not duplicate tabs that never closed",
            restoreFile.none { ClaudeTabsHelpers.shouldRestoreSession(it, running) },
        )
    }

    @Test fun aPartiallyLiveRestoreFileOnlyRestoresTheDeadOnes() {
        val running = live("alive-1" to "alive-1")
        val restoreFile = listOf("alive-1", "dead-1", "dead-2")
        val toSpawn = restoreFile.filter { ClaudeTabsHelpers.shouldRestoreSession(it, running) }
        assertTrue(toSpawn == listOf("dead-1", "dead-2"))
    }
}
