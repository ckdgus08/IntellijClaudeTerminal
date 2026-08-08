package com.claudetabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the restore spawn is allowed to fire.
 *
 * Restore waits for the IDE to finish putting back the tabs it remembered from
 * `workspace.xml`, so the empty leftovers can be closed before fresh ones are spawned. That
 * was a flat five-second sleep; measured against a real start, five of the nine seconds
 * between the plugin starting and the first tab appearing were this wait, and the tool
 * window had actually been stable almost the whole time.
 */
class RestoreSettleTest {

    private fun fire(ageMs: Long, lastChangeMs: Long) =
        ClaudeTabsHelpers.shouldFireRestore(ageMs, lastChangeMs, quietMs = 800, ceilingMs = 5_000)

    @Test fun firesOnceTheToolWindowGoesQuiet() {
        assertTrue(fire(ageMs = 900, lastChangeMs = 800))
        assertTrue(fire(ageMs = 1_200, lastChangeMs = 1_000))
    }

    @Test fun waitsWhileTabsAreStillArriving() {
        // The whole point: a tab that appears at 700ms must not be missed.
        assertFalse(fire(ageMs = 700, lastChangeMs = 100))
        assertFalse(fire(ageMs = 2_000, lastChangeMs = 400))
    }

    @Test fun theCeilingStillFiresWhenNothingEverSettles() {
        // A tool window that keeps churning must not block restore forever.
        assertTrue(fire(ageMs = 5_000, lastChangeMs = 0))
        assertTrue(fire(ageMs = 9_999, lastChangeMs = 10))
    }

    @Test fun isFasterThanTheOldFixedWaitInTheNormalCase() {
        // Old behaviour was `ageMs >= 5000` regardless. Anything quiet before that is a win.
        val quietAt = 1_000L
        assertTrue("new path fires early", fire(ageMs = quietAt, lastChangeMs = 900))
        assertFalse("old path would still be waiting", quietAt >= 5_000)
    }

    @Test fun aChurningWindowIsNotMistakenForAQuietOne() {
        // lastChangeMs resets on every content-count change, so a window adding a tab every
        // half second never reaches the quiet threshold — it waits for the ceiling instead.
        for (age in listOf(500L, 1_500L, 2_500L, 3_500L, 4_500L)) {
            assertFalse("age=$age", fire(ageMs = age, lastChangeMs = 500))
        }
        assertTrue(fire(ageMs = 5_000, lastChangeMs = 500))
    }
}

/**
 * Closing the empty terminal the IDE opens for itself.
 *
 * When the terminal tool window is active at startup the IDE creates a default tab —
 * observed 68ms after the plugin starts and three seconds before restore fires. Restore
 * then adds the saved sessions on top, so reopening leaves one more terminal than there
 * were on close.
 *
 * Closing someone's terminal on a guess is the failure mode to avoid here, so every guard
 * gets a test.
 */
class DefaultTerminalCleanupTest {

    private fun disposable(
        restoredAny: Boolean = true,
        isGenericName: Boolean = true,
        hasClaude: Boolean = false,
        childProcessCount: Int = 0,
        isPluginSpawned: Boolean = false,
    ) = ClaudeTabsHelpers.isDisposableDefaultTerminal(
        restoredAny, isGenericName, hasClaude, childProcessCount, isPluginSpawned,
    )

    @Test fun closesTheUntouchedDefaultTerminalAfterARestore() {
        assertTrue(disposable())
    }

    @Test fun keepsATerminalWithSomethingRunningInIt() {
        // A build, an ssh session, a paused editor — whatever it is, it is in use.
        assertFalse(disposable(childProcessCount = 1))
    }

    @Test fun keepsARenamedTerminal() {
        // Someone bothered to name it, so it is theirs.
        assertFalse(disposable(isGenericName = false))
    }

    @Test fun keepsATerminalRunningClaude() {
        assertFalse(disposable(hasClaude = true))
    }

    @Test fun neverClosesOurOwnRestoredTabs() {
        assertFalse(disposable(isPluginSpawned = true))
    }

    @Test fun doesNothingWhenNoRestoreHappened() {
        // With nothing restored a lone empty terminal is just the terminal; closing it
        // would leave an empty tool window.
        assertFalse(disposable(restoredAny = false))
    }

    @Test fun everyGuardIsIndependentlySufficient() {
        // None of them may be skippable — each alone must be able to save a tab.
        assertFalse(disposable(restoredAny = false, isGenericName = true, hasClaude = false, childProcessCount = 0))
        assertFalse(disposable(isGenericName = false, childProcessCount = 0))
        assertFalse(disposable(hasClaude = true, childProcessCount = 0))
        assertFalse(disposable(childProcessCount = 3))
        assertFalse(disposable(isPluginSpawned = true, childProcessCount = 0))
    }
}
