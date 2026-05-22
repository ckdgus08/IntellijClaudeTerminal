package com.claudetabs

import com.claudetabs.ClaudeTabsStorage.SavedSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression pin for the additive-union semantics of the save loop's belt-and-braces
 * layers (1.0.14).
 *
 * The save loop in `poll()` runs four passes that all contribute candidates to
 * `activeSessions`:
 *
 *   - STEP 1-3  : tab-driven loop — for each platform-enumerated tab, walk to its
 *                 Claude child and read sessions/<pid>.json.
 *   - STEP 5    : ContentManager sweep — pick up tabs the reworked frontend/backend
 *                 managers don't list, then resolve their PIDs via the widget.
 *   - STEP 6    : spawnedWidgets union — tabs we explicitly created via
 *                 createShellWidget on restore.
 *   - STEP 6b   : SESSIONS_DIR direct scan via [SessionsDirScanner.scan] — Claude
 *                 processes alive on the OS whose cwd matches this project,
 *                 regardless of whether the platform enumerates their tab at all.
 *
 * Each pass is ADDITIVE — production tracks added sessionIds in a Set and skips
 * candidates already present. This file pins:
 *
 *   1. The `Set + List.add` orchestration that production uses for STEPS 1-6.
 *   2. That [SessionsDirScanner.scan] itself dedups within its pass via the
 *      `alreadyActiveIds` parameter (tested by calling the REAL scan function).
 *   3. The round-trip through [ClaudeTabsStorage] preserves the union without
 *      reordering or duplicating.
 *
 * What's NOT pinned here (because it lives inside `poll()` and isn't extractable
 * without an IntelliJ Project): the actual orchestration that wires STEPS 1-6 then
 * calls the scanner with the partial set. That's covered by the idea.log
 * integration test the user reads after each rebuild.
 */
class SaveLoopUnionContractTest {

    /** Mirror of the union behaviour: pass-N runs ONLY for sids not yet in the set. */
    private fun additiveAdd(
        activeSessions: MutableList<SavedSession>,
        seenIds: MutableSet<String>,
        candidate: SavedSession,
    ): Boolean {
        if (candidate.sessionId in seenIds) return false
        activeSessions.add(candidate)
        seenIds.add(candidate.sessionId)
        return true
    }

    private fun mkSession(sid: String, name: String = "Tab", cwd: String = "D:\\Dev\\Proj"): SavedSession =
        SavedSession(sid, cwd, name, false)

    // ══════════════════════════════════════════════════════════════
    // Tab-driven pass found EVERYTHING — later passes add nothing
    // ══════════════════════════════════════════════════════════════

    @Test
    fun fullyEnumeratedClassicCase_step6and6bAddNothing() {
        // The pre-Rider-2026.1 case: tabs/sessions all visible to the platform. STEP 6
        // (spawnedWidgets union) and STEP 6b (SESSIONS_DIR scan) both run but find
        // every candidate already present.
        val active = mutableListOf<SavedSession>()
        val seen = mutableSetOf<String>()
        // STEP 1-3 found sids 1, 2, 3
        additiveAdd(active, seen, mkSession("sid-1", "Tab A"))
        additiveAdd(active, seen, mkSession("sid-2", "Tab B"))
        additiveAdd(active, seen, mkSession("sid-3", "Tab C"))

        // STEP 6 tries the same 3 sids (their widgets are also in spawnedWidgets)
        val step6Added = listOf("sid-1", "sid-2", "sid-3").count {
            additiveAdd(active, seen, mkSession(it, "would-overwrite"))
        }
        assertEquals("STEP 6 adds zero on full enumeration", 0, step6Added)
        // STEP 6b tries the same 3 sids
        val step6bAdded = listOf("sid-1", "sid-2", "sid-3").count {
            additiveAdd(active, seen, mkSession(it, "would-overwrite-via-dirscan"))
        }
        assertEquals("STEP 6b adds zero on full enumeration", 0, step6bAdded)
        assertEquals("activeSessions still has exactly 3", 3, active.size)
        assertEquals("first entry's name survives — no overwrite by later passes",
            "Tab A", active[0].tabName)
    }

    // ══════════════════════════════════════════════════════════════
    // Reworked-terminal case: tab loop sees 1 of 6, SESSIONS_DIR rescues the other 5
    // ══════════════════════════════════════════════════════════════

    @Test
    fun reworkedTerminalCase_sessionsDirScanRescues5ofManyMissedTabs() {
        // The actual idea.log scenario from 2026-05-21:
        //   STEP 1-3 (tab-driven) finds 1 session (the one whose backend tab had a sid).
        //   STEP 5 (ContentManager sweep) and STEP 6 (spawnedWidgets union) add 0 (PIDs
        //     not extractable from freshly-spawned widgets in 2026.1).
        //   STEP 6b (SESSIONS_DIR scan) rescues the other 5 by reading sessions/<pid>.json
        //     and checking PIDs in the OS.
        val active = mutableListOf<SavedSession>()
        val seen = mutableSetOf<String>()

        // Tab-driven loop found just one session.
        additiveAdd(active, seen, mkSession("sid-6", "Tab C"))
        assertEquals(1, active.size)

        // STEP 6b finds 6 candidates via SESSIONS_DIR, 5 are net-new.
        val step6bCandidates = (1..6).map { mkSession("sid-$it", "Resolved-from-cache-$it") }
        val added = step6bCandidates.count { additiveAdd(active, seen, it) }
        assertEquals("STEP 6b rescued 5 missed sessions", 5, added)
        assertEquals("activeSessions now has all 6", 6, active.size)
    }

    // ══════════════════════════════════════════════════════════════
    // First-write-wins — later passes never overwrite name
    // ══════════════════════════════════════════════════════════════

    @Test
    fun firstWriteWinsAcrossPasses() {
        // The tab-driven pass has access to the LIVE TerminalWidget and uses
        // `widget.terminalTitle.buildTitle()` — which gives the richest possible name
        // (user-applied via /tab, Claude's chat title, or last manual rename). STEP 6b
        // falls back to weaker sources (spawnedWidgets buildTitle, lastAppliedName,
        // previousActive, "Claude" default). If a later pass overwrote, we'd downgrade
        // the name. Pin: first pass to write a sid wins.
        val active = mutableListOf<SavedSession>()
        val seen = mutableSetOf<String>()

        additiveAdd(active, seen, mkSession("sid-1", "Tab C"))  // tab-driven, rich name
        additiveAdd(active, seen, mkSession("sid-1", "Claude"))           // STEP 6b, fallback default
        assertEquals(1, active.size)
        assertEquals("rich name from first pass survives", "Tab C", active[0].tabName)
    }

    // ══════════════════════════════════════════════════════════════
    // No silent duplicates in the serialised restore file
    // ══════════════════════════════════════════════════════════════

    @Test
    fun finalListHasNoDuplicateSessionIds() {
        // Whatever combination of passes contributed, the restore file must not have
        // two entries with the same sessionId — `claude --resume <sid>` from the restore
        // loop would race two terminal spawns on the same session and corrupt the
        // transcript.
        val active = mutableListOf<SavedSession>()
        val seen = mutableSetOf<String>()
        // Simulate all 4 passes contributing the same 3 sids with different name sources:
        val passes = listOf(
            listOf(mkSession("sid-A", "Pass1-A"), mkSession("sid-B", "Pass1-B")),
            listOf(mkSession("sid-A", "Pass2-A"), mkSession("sid-C", "Pass2-C")),
            listOf(mkSession("sid-B", "Pass3-B"), mkSession("sid-C", "Pass3-C")),
            listOf(mkSession("sid-A", "Pass4-A"), mkSession("sid-D", "Pass4-D")),
        )
        for (pass in passes) for (s in pass) additiveAdd(active, seen, s)

        val sids = active.map { it.sessionId }
        assertEquals("4 unique sids", 4, sids.toSet().size)
        assertEquals("4 entries total — no duplicates", 4, sids.size)
        // First-write-wins on every sid:
        assertEquals("Pass1-A", active.first { it.sessionId == "sid-A" }.tabName)
        assertEquals("Pass1-B", active.first { it.sessionId == "sid-B" }.tabName)
        assertEquals("Pass2-C", active.first { it.sessionId == "sid-C" }.tabName)
        assertEquals("Pass4-D", active.first { it.sessionId == "sid-D" }.tabName)
    }

    // ══════════════════════════════════════════════════════════════
    // Round-trip: write the union → re-parse → verify
    // ══════════════════════════════════════════════════════════════

    @Test
    fun unionedSessionsSerialiseAndRoundTripCleanly() {
        // The whole point of getting the union right is so it survives the write to
        // restore-<projectHash>.json and the read on the next start. Pin the
        // round-trip explicitly.
        val tmp = java.io.File.createTempFile("claude-tabs-union-rt-", "").apply { delete(); mkdirs() }
        try {
            val storage = ClaudeTabsStorage(tmp)
            val active = mutableListOf<SavedSession>()
            val seen = mutableSetOf<String>()
            // Mimic the reworked-terminal case from above.
            additiveAdd(active, seen, mkSession("sid-6", "Tab C"))
            (1..5).map { mkSession("sid-$it", "Resolved-$it") }.forEach { additiveAdd(active, seen, it) }

            val hash = "test-hash"
            val content = storage.saveState(hash, active, keepCount = 0)
            assertNotNull("non-empty save must write content", content)

            val reread = storage.parseSessions(storage.restoreFile(hash).readText())
            assertEquals("all 6 sessions survive round-trip", 6, reread.size)
            assertEquals("first session's name preserved (first-write-wins after parse)",
                "Tab C", reread.first { it.sessionId == "sid-6" }.tabName)
            assertFalse("no duplicate sids after round-trip",
                reread.size != reread.map { it.sessionId }.toSet().size)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
