package com.claudetabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression pin for the duplicate-tab bug seen on 1.0.20's first install.
 *
 * What happened, from idea.log: installing the plugin reloaded the project without killing
 * anything (18:58:12 "Project closing", 18:58:16 project restarts). The restore file that
 * the previous window's poll had written five seconds earlier still listed both live
 * sessions, so at 18:58:25 the plugin spawned a tab for each — including
 * `claude --resume c0a4f05e-…`, a duplicate of the conversation already open in the tab
 * beside it. The original tabs never closed, so the user ended up with both.
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
        // Both entries from the real restore file, both still alive at restore time.
        val restoreFile = listOf("c0a4f05e-76b6-48c7-8b7e-ba7b01302f47", "eb442e0b-642f-40a9-8c1a-b074830e6d1d")
        val running = live(
            "c0a4f05e-76b6-48c7-8b7e-ba7b01302f47" to "c0a4f05e-76b6-48c7-8b7e-ba7b01302f47",
            "eb442e0b-642f-40a9-8c1a-b074830e6d1d" to "eb442e0b-642f-40a9-8c1a-b074830e6d1d",
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
