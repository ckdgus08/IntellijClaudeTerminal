package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The status indicator's tab map is shared by every open project window. These pin the two
 * rules that keeps that safe — see [ClaudeTabsHelpers.sidsToUntrack].
 */
class StatusTrackingScopeTest {

    private val projA = "-Users-x-projects-RiderClaudeTabs"
    private val projB = "-Users-x-projects-other-project"

    @Test fun untracksOwnSessionsThatDisappeared() {
        val tracked = mapOf("sid-1" to projA, "sid-2" to projA)
        assertEquals(
            setOf("sid-2"),
            ClaudeTabsHelpers.sidsToUntrack(tracked, projA, seenThisPoll = setOf("sid-1"), tabWalkFoundTabs = true),
        )
    }

    @Test fun neverUntracksAnotherWindowsSessions() {
        // The regression: with two projects open, A's poll doesn't see B's sids (they're in
        // B's window) and would otherwise drop them — then B's poll would drop A's, and the
        // two would delete each other's tracking every 5s.
        val tracked = mapOf("a-1" to projA, "b-1" to projB, "b-2" to projB)
        assertEquals(
            emptySet<String>(),
            ClaudeTabsHelpers.sidsToUntrack(tracked, projA, seenThisPoll = setOf("a-1"), tabWalkFoundTabs = true),
        )
    }

    @Test fun bothWindowsPollingConvergesInsteadOfOscillating() {
        val tracked = mapOf("a-1" to projA, "b-1" to projB)
        val aSees = setOf("a-1")
        val bSees = setOf("b-1")
        // Whichever order they run in, and however many times, nothing is dropped.
        repeat(3) {
            assertEquals(emptySet<String>(), ClaudeTabsHelpers.sidsToUntrack(tracked, projA, aSees, true))
            assertEquals(emptySet<String>(), ClaudeTabsHelpers.sidsToUntrack(tracked, projB, bSees, true))
        }
    }

    @Test fun anEmptyTabWalkIsNotEvidenceThatTabsClosed() {
        // getAllTabs returns nothing when the reworked terminal withholds shell PIDs — the
        // documented Rider 2026.1 failure mode that STEP 6d exists to work around. Treating
        // it as "all tabs gone" would blank every glyph on those polls.
        val tracked = mapOf("sid-1" to projA, "sid-2" to projA)
        assertEquals(
            emptySet<String>(),
            ClaudeTabsHelpers.sidsToUntrack(tracked, projA, seenThisPoll = emptySet(), tabWalkFoundTabs = false),
        )
    }

    @Test fun sessionsAreTrackedAcrossProjectsIndependentOfCwd() {
        // A session started at ~/projects but hosted in the RiderClaudeTabs window belongs
        // to that window. Ownership here is by which tab-walk found it, never by cwd — the
        // status indicator registers before any cwd or transcript filtering runs.
        val tracked = mapOf("root-session" to projA)
        assertEquals(
            emptySet<String>(),
            ClaudeTabsHelpers.sidsToUntrack(tracked, projA, seenThisPoll = setOf("root-session"), tabWalkFoundTabs = true),
        )
    }

    @Test fun emptyInputsAreNoOps() {
        assertEquals(emptySet<String>(), ClaudeTabsHelpers.sidsToUntrack(emptyMap(), projA, emptySet(), true))
    }
}
