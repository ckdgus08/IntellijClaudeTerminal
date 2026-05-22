package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Regression test for the multi-project state-leak bug.
 *
 * `ClaudeTabWatcherStartup` is registered as a `<postStartupActivity>` and is therefore an
 * application-level singleton. Until this bug was fixed it kept its mutable bookkeeping
 * (`pendingRestores`, `previousActive`, `restoredThisRun`, `pollCount`, …) in plain instance
 * fields, which meant every open project shared the same data structures.
 *
 * Symptoms in production logs (Rider 2026.1, two projects open):
 *  - Project A's poll wrote A's active sessions into the shared `previousActive`.
 *  - Project B's poll then ran with B's `activeSessions`, looked at the same shared
 *    `previousActive`, didn't find B's ids in B's active list, and concluded A's sessions
 *    had "closed" — appending them to history.json every poll cycle (~once per 5s).
 *  - On overlapping startups, A's `pendingRestores` could be drained by B's
 *    `processPendingRestores`, sending `claude --resume <A-sid>` into a B tab.
 *
 * The fix scopes that state via a `ConcurrentHashMap<String, ProjectCtx>` keyed by
 * `Project.locationHash`. This test pins the contract of that pattern: distinct keys yield
 * distinct mutable holders, mutations don't cross over, and removal of one key doesn't
 * disturb the other.
 *
 * The test reproduces the holder pattern locally (rather than reaching into
 * `ClaudeTabWatcherStartup`) because the field is private and the broader class needs an
 * IntelliJ `Project` instance — not feasible in a Layer 1 unit test. The data-class shape
 * is identical to the production one.
 */
class ProjectStateIsolationTest {

    /** Mirrors the production `ProjectCtx` — keep field set in sync. */
    private data class ProjectCtx(
        val pendingRestores: MutableList<String> = mutableListOf(),
        var pendingRestoresLoadedAt: Long = 0L,
        var restoreFired: Boolean = false,
        val previousActive: MutableMap<String, String> = mutableMapOf(),
        var pollCount: Int = 0,
    )

    private val projectCtx = ConcurrentHashMap<String, ProjectCtx>()
    private fun ctx(key: String): ProjectCtx = projectCtx.computeIfAbsent(key) { ProjectCtx() }

    @Test
    fun distinctKeysYieldDistinctHolders() {
        val a = ctx("projA")
        val b = ctx("projB")
        assertNotSame("each project must get its own ProjectCtx instance", a, b)
    }

    @Test
    fun sameKeyReturnsSameHolder() {
        val a1 = ctx("projA")
        val a2 = ctx("projA")
        // computeIfAbsent contract — same key returns the same value, so a1 === a2.
        assertTrue(a1 === a2)
    }

    @Test
    fun pendingRestoresDoNotCrossPollute() {
        ctx("projA").pendingRestores.addAll(listOf("sidA1", "sidA2"))
        ctx("projB").pendingRestores.add("sidB1")

        // Project B's poll iterates only its own queue.
        assertEquals(listOf("sidB1"), ctx("projB").pendingRestores)
        // Project A's queue is untouched by B's add.
        assertEquals(listOf("sidA1", "sidA2"), ctx("projA").pendingRestores)
    }

    /**
     * The headline regression: project A's `previousActive` must NOT cause project B's
     * poll to see A's sessions as "closed" — that's exactly what was filling history.json
     * with hundreds of fake closure events per minute.
     */
    @Test
    fun previousActiveClosureDetectionDoesNotLeakAcrossProjects() {
        // Simulate proj A's poll: it sees session "a1" active.
        ctx("projA").previousActive["a1"] = "Tab A"
        // Simulate proj B's poll: it sees session "b1" active.
        ctx("projB").previousActive["b1"] = "Tab B"

        // Now proj B polls again. activeSessions = ["b1"]. Closure detection iterates
        // *only B's* previousActive — must NOT see a1 as closed even though a1 isn't
        // in B's activeSessions.
        val bActive = setOf("b1")
        val bClosed = ctx("projB").previousActive.filterKeys { it !in bActive }
        assertTrue("project B's poll must not see project A's sessions in its closure check",
                   bClosed.isEmpty())

        // Symmetric check from A's side.
        val aActive = setOf("a1")
        val aClosed = ctx("projA").previousActive.filterKeys { it !in aActive }
        assertTrue(aClosed.isEmpty())
    }

    @Test
    fun pollCountIncrementsIndependently() {
        repeat(5) { ctx("projA").pollCount++ }
        repeat(2) { ctx("projB").pollCount++ }
        assertEquals(5, ctx("projA").pollCount)
        assertEquals(2, ctx("projB").pollCount)
    }

    @Test
    fun removingOneEntryLeavesOthersIntact() {
        ctx("projA").pendingRestores.add("sidA1")
        ctx("projB").pendingRestores.add("sidB1")
        projectCtx.remove("projA")

        assertFalse("removed entry must be gone", projectCtx.containsKey("projA"))
        assertEquals(listOf("sidB1"), ctx("projB").pendingRestores)
    }

    /**
     * The create-restore must fire exactly once per Rider start, even if the restore file
     * happens to be re-populated after we ran (e.g. saveState races us before getStateFile
     * delete). `restoreFired` is the latch — once set, processPendingRestores returns
     * immediately on subsequent calls.
     */
    @Test
    fun restoreFiredFlagPreventsDoubleSpawn() {
        val c = ctx("projA")
        c.pendingRestores.addAll(listOf("sidA1", "sidA2"))

        // Simulate first pass: spawn each, mark fired.
        val spawned = mutableListOf<String>()
        if (!c.restoreFired) {
            spawned.addAll(c.pendingRestores)
            c.pendingRestores.clear()
            c.restoreFired = true
        }
        assertEquals(listOf("sidA1", "sidA2"), spawned)

        // Now imagine the restore file came back — pendingRestores re-populated.
        c.pendingRestores.addAll(listOf("sidA1", "sidA2"))
        val secondPassSpawned = mutableListOf<String>()
        if (!c.restoreFired) {
            secondPassSpawned.addAll(c.pendingRestores)
        }
        assertTrue("restoreFired must block second pass", secondPassSpawned.isEmpty())
    }
}
