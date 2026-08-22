package com.claudetabs

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Layer 2 integration tests — [ClaudeTabsStorage] against a real filesystem (under a temp dir).
 *
 * These lock in the file-format contracts and the bugs we've already fixed:
 *   - saveState must not wipe the restore file while pendingRestores is non-empty
 *     (this was the bug where tabs disappeared on the first poll after startup)
 *   - loadRestoreWithFallback must fall back to snapshots when the live file is empty
 *   - appendToHistory must be thread-safe and prune old entries
 *   - snapshot rotation must respect keepCount
 */
class ClaudeTabsStorageTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var storage: ClaudeTabsStorage
    private val projectHash = "D--Dev-Test"

    @Before fun setup() {
        storage = ClaudeTabsStorage(tmp.root)
        storage.stateDir.mkdirs()
    }

    @After fun teardown() {
        // TemporaryFolder handles cleanup, nothing to do.
    }

    private fun session(id: String, name: String, bypass: Boolean = false) =
        ClaudeTabsStorage.SavedSession(id, "D:/Dev/Test", name, bypass)

    // ══════════════════════════════════════════════════════════════
    // serialise / parse round-trip
    // ══════════════════════════════════════════════════════════════

    @Test fun serialise_roundTrips() {
        val sessions = listOf(
            session("abc-123", "Fix Auth Bug"),
            session("def-456", "Rider Plugin", bypass = true),
        )
        val json = storage.serialiseSessions(sessions)
        val parsed = storage.parseSessions(json)
        assertEquals(sessions, parsed)
    }

    @Test fun serialise_roundTripsCodexWithoutLeakingInternalPrefix() {
        val session = ClaudeTabsStorage.SavedSession(
            AgentKind.CODEX.toInternalSessionId("0198cafe-1234"),
            "/work/repo",
            "Fix flaky tests",
            false,
            AgentKind.CODEX,
        )

        val json = storage.serialiseSessions(listOf(session))
        assertTrue(json.contains("\"provider\":\"codex\""))
        assertTrue(json.contains("\"sessionId\":\"0198cafe-1234\""))
        assertFalse(json.contains("codex--0198cafe-1234"))
        assertEquals(listOf(session), storage.parseSessions(json))
    }

    @Test fun parse_legacyEntryWithoutProviderDefaultsToClaude() {
        val parsed = storage.parseSessions(
            """[{"sessionId":"legacy","cwd":"/p","tabName":"Old","bypassPermissions":false}]"""
        )

        assertEquals(AgentKind.CLAUDE, parsed.single().provider)
        assertEquals("legacy", parsed.single().sessionId)
    }

    @Test fun parse_emptyArrayOrBlankReturnsEmpty() {
        assertTrue(storage.parseSessions("").isEmpty())
        assertTrue(storage.parseSessions("   ").isEmpty())
        assertTrue(storage.parseSessions("[]").isEmpty())
    }

    @Test fun parse_skipsEntriesMissingRequiredFields() {
        val json = """[{"sessionId":"ok","cwd":"/p","tabName":"Foo"},{"onlySessionId":"bad"}]"""
        val parsed = storage.parseSessions(json)
        assertEquals(1, parsed.size)
        assertEquals("ok", parsed[0].sessionId)
    }

    @Test fun serialise_escapesBackslashesAndQuotes() {
        val sessions = listOf(session("s", """Name with \ and "quote""""))
        val json = storage.serialiseSessions(sessions)
        val parsed = storage.parseSessions(json)
        assertEquals("""Name with \ and "quote"""", parsed[0].tabName)
    }

    // ══════════════════════════════════════════════════════════════
    // saveState — THE KEY REGRESSION TEST
    // ══════════════════════════════════════════════════════════════

    @Test fun saveState_emptyAfterNonEmpty_preservesFile() {
        // Reproduces the bug: after startup the live restore file had 4 entries; a poll saw
        // 0 active sessions and called saveState with empty — which USED TO wipe the file,
        // destroying the saved state. The fix is high-water-mark union: an empty `new`
        // doesn't evict anything, so the existing entries survive. Real session closures
        // are tracked through userClosedSessions, not by erasing the restore file on a
        // missed poll.
        val f = storage.restoreFile(projectHash)
        storage.saveState(projectHash, listOf(session("pre-existing", "Saved Tab")), keepCount = 0)
        assertTrue("file should exist after non-empty save", f.exists())

        storage.saveState(projectHash, emptyList(), keepCount = 0)
        assertTrue("file must survive any empty save — never delete on empty", f.exists())
        assertTrue("file content must contain pre-existing after empty save",
            f.readText().contains("pre-existing"))
    }

    @Test fun saveState_writesSnapshotWhenKeepCountPositive() {
        storage.saveState(projectHash, listOf(session("x", "Foo")), keepCount = 3, now = 1000L)
        val snaps = storage.listSnapshots(projectHash)
        assertEquals(1, snaps.size)
        assertTrue(snaps[0].name.endsWith("__1000.json"))
    }

    @Test fun saveState_doesNotWriteSnapshotWhenKeepCountZero() {
        storage.saveState(projectHash, listOf(session("x", "Foo")), keepCount = 0, now = 1000L)
        assertTrue(storage.listSnapshots(projectHash).isEmpty())
    }

    // ══════════════════════════════════════════════════════════════
    // snapshot rotation
    // ══════════════════════════════════════════════════════════════

    @Test fun snapshots_pruneBeyondKeepCount() {
        repeat(5) { i ->
            storage.saveState(projectHash, listOf(session("s$i", "Tab $i")),
                keepCount = 3, now = 1000L + i)
        }
        val snaps = storage.listSnapshots(projectHash)
        assertEquals("should keep only newest 3", 3, snaps.size)
        // Newest first — verify by timestamp suffix
        assertTrue(snaps[0].name.endsWith("__1004.json"))
        assertTrue(snaps[1].name.endsWith("__1003.json"))
        assertTrue(snaps[2].name.endsWith("__1002.json"))
    }

    @Test fun snapshots_listedNewestFirst() {
        storage.writeSnapshot(projectHash, "[]", 10, now = 3000)
        storage.writeSnapshot(projectHash, "[]", 10, now = 1000)
        storage.writeSnapshot(projectHash, "[]", 10, now = 2000)
        val snaps = storage.listSnapshots(projectHash)
        assertTrue(snaps[0].name.endsWith("__3000.json"))
        assertTrue(snaps[1].name.endsWith("__2000.json"))
        assertTrue(snaps[2].name.endsWith("__1000.json"))
    }

    @Test fun snapshots_legacySingleDashFilenamesStillAccepted() {
        // Backward-compat: snapshots written by 1.0.x (before the double-underscore
        // delimiter) used `${projectHash}-${ts}.json`. listSnapshots must still find them so
        // restore can fall back to legacy snapshots after an in-place plugin upgrade.
        storage.snapshotsDir.mkdirs()
        File(storage.snapshotsDir, "${projectHash}-100.json").writeText("[]")
        File(storage.snapshotsDir, "${projectHash}__200.json").writeText("[]")
        val snaps = storage.listSnapshots(projectHash)
        assertEquals(2, snaps.size)
    }

    @Test fun snapshots_scopedByProjectHash() {
        storage.saveState("proj-A", listOf(session("a", "A")), 5, now = 1)
        storage.saveState("proj-B", listOf(session("b", "B")), 5, now = 2)
        assertEquals(1, storage.listSnapshots("proj-A").size)
        assertEquals(1, storage.listSnapshots("proj-B").size)
    }

    // ══════════════════════════════════════════════════════════════
    // loadRestoreWithFallback
    // ══════════════════════════════════════════════════════════════

    @Test fun load_prefersLiveFileWhenNonEmpty() {
        storage.saveState(projectHash, listOf(session("live", "From Live")), 5, now = 100)
        // Add a snapshot with DIFFERENT content too — live should still win
        storage.writeSnapshot(projectHash,
            storage.serialiseSessions(listOf(session("snap", "From Snap"))),
            5, now = 50)

        val result = storage.loadRestoreWithFallback(projectHash)
        assertEquals(1, result.sessions.size)
        assertEquals("live", result.sessions[0].sessionId)
        assertEquals(storage.restoreFile(projectHash), result.source)
    }

    @Test fun load_fallsBackToNewestSnapshotWhenLiveMissing() {
        storage.writeSnapshot(projectHash,
            storage.serialiseSessions(listOf(session("old", "Old"))),
            5, now = 100)
        storage.writeSnapshot(projectHash,
            storage.serialiseSessions(listOf(session("newer", "Newer"))),
            5, now = 200)

        val result = storage.loadRestoreWithFallback(projectHash)
        assertEquals(1, result.sessions.size)
        assertEquals("newer", result.sessions[0].sessionId)
    }

    @Test fun load_fallsBackWhenLiveFileEmpty() {
        // Write an explicitly-empty restore file, then a useful snapshot
        storage.restoreFile(projectHash).also { it.parentFile.mkdirs(); it.writeText("[]") }
        storage.writeSnapshot(projectHash,
            storage.serialiseSessions(listOf(session("recovered", "Recovered"))),
            5, now = 100)

        val result = storage.loadRestoreWithFallback(projectHash)
        assertEquals(1, result.sessions.size)
        assertEquals("recovered", result.sessions[0].sessionId)
    }

    @Test fun load_returnsEmptyWhenNothingAvailable() {
        val result = storage.loadRestoreWithFallback("never-saved")
        assertTrue(result.sessions.isEmpty())
        assertNull(result.source)
    }

    // ══════════════════════════════════════════════════════════════
    // history
    // ══════════════════════════════════════════════════════════════

    @Test fun history_appendAddsEntry() {
        storage.appendToHistory(session("abc", "Thing"), now = 1000L, maxAgeMs = 10_000L)
        val raw = storage.loadHistoryRaw()
        assertEquals(1, raw.size)
        assertEquals("abc", ClaudeTabsHelpers.extractJsonString(raw[0], "sessionId"))
    }

    @Test fun history_upsertReplacesExistingEntryBySessionId() {
        storage.appendToHistory(session("abc", "Old Name"), now = 1000L, maxAgeMs = 10_000L)
        storage.appendToHistory(session("abc", "New Name"), now = 2000L, maxAgeMs = 10_000L)
        val raw = storage.loadHistoryRaw()
        assertEquals(1, raw.size)
        assertEquals("New Name", ClaudeTabsHelpers.extractJsonString(raw[0], "tabName"))
    }

    @Test fun history_prunesEntriesOlderThanMaxAge() {
        // First entry at t=100, max age 50 means cutoff at t=2050 will prune it.
        storage.appendToHistory(session("old", "Old"), now = 100L, maxAgeMs = 1000L)
        storage.appendToHistory(session("new", "New"), now = 2000L, maxAgeMs = 1000L)
        val raw = storage.loadHistoryRaw()
        assertEquals(1, raw.size)
        assertEquals("new", ClaudeTabsHelpers.extractJsonString(raw[0], "sessionId"))
    }

    @Test fun history_concurrentAppendsAreSerialised() {
        // 50 concurrent appends — without synchronized, some writes would overwrite each other.
        val threads = (0 until 50).map { i ->
            Thread {
                storage.appendToHistory(session("id-$i", "Tab $i"), now = 1000L + i.toLong(), maxAgeMs = 1_000_000L)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val raw = storage.loadHistoryRaw()
        assertEquals("all 50 entries should survive", 50, raw.size)
    }

    // ══════════════════════════════════════════════════════════════
    // file paths
    // ══════════════════════════════════════════════════════════════

    @Test fun storagePaths_resolveUnderClaudeHome() {
        val home = tmp.root
        val s = ClaudeTabsStorage(home)
        assertEquals(File(home, "intellij-claude-terminal"), s.stateDir)
        assertEquals(File(home, "intellij-claude-terminal/tabs"), s.tabsDir)
        assertEquals(File(home, "intellij-claude-terminal/session-map"), s.sessionMapDir)
        assertEquals(File(home, "intellij-claude-terminal/snapshots"), s.snapshotsDir)
        assertEquals(File(home, "intellij-claude-terminal/history.json"), s.historyFile)
        assertEquals(File(home, "intellij-claude-terminal/config.json"), s.configFile)
        assertEquals(File(home, "CLAUDE.md"), s.claudeMdFile)
        assertEquals(File(home, "settings.json"), s.settingsFile)
        assertEquals(File(home, "sessions"), s.sessionsDir)
        assertEquals(File(home, "commands"), s.commandsDir)
    }

    @Test fun restoreFile_usesProjectHashSuffix() {
        assertEquals(File(storage.stateDir, "restore-repos-MyApp.json"),
            storage.restoreFile("repos-MyApp"))
    }

    // ══════════════════════════════════════════════════════════════
    // history.json safe-read — protect against silent wipes
    // ══════════════════════════════════════════════════════════════

    @Test fun loadHistorySafe_missingFile_returnsOkEmpty() {
        // No file = legitimately empty, not a failure.
        val r = storage.loadHistorySafe()
        assertTrue("expected Ok for missing file, got $r", r is ClaudeTabsStorage.HistoryRead.Ok)
        assertEquals(0, (r as ClaudeTabsStorage.HistoryRead.Ok).entries.size)
    }

    @Test fun loadHistorySafe_emptyArrayLiteral_returnsOkEmpty() {
        storage.historyFile.parentFile.mkdirs()
        storage.historyFile.writeText("[]")
        val r = storage.loadHistorySafe()
        assertTrue(r is ClaudeTabsStorage.HistoryRead.Ok)
        assertEquals(0, (r as ClaudeTabsStorage.HistoryRead.Ok).entries.size)
    }

    @Test fun loadHistorySafe_corruptContent_returnsReadFailed() {
        // File exists with content but no JSON entries — almost certainly partial/corrupt
        // write. Production caller MUST refuse to overwrite this.
        storage.historyFile.parentFile.mkdirs()
        storage.historyFile.writeText("garbage that doesn't contain any braces at all")
        val r = storage.loadHistorySafe()
        assertTrue("expected ReadFailed for unparseable content, got $r",
            r is ClaudeTabsStorage.HistoryRead.ReadFailed)
    }

    @Test fun appendToHistory_returnsFalseAndPreservesFile_whenReadFailed() {
        // Seed with corrupt content so loadHistorySafe returns ReadFailed.
        storage.historyFile.parentFile.mkdirs()
        val corruptContent = "this is not valid history json content"
        storage.historyFile.writeText(corruptContent)

        val ok = storage.appendToHistory(
            ClaudeTabsStorage.SavedSession("new-sess", "D:\\Foo", "New", false),
            now = 1_000_000_000_000L,
            maxAgeMs = 90L * 24 * 60 * 60 * 1000,
        )
        assertFalse("appendToHistory must report failure on read-failed", ok)
        assertEquals("file content must be untouched on read-failed",
            corruptContent, storage.historyFile.readText())
    }

    @Test fun appendToHistory_writesAtomically_noStaleTmpFile() {
        // After a successful write there should be no .tmp.* leftover sitting next to
        // history.json — atomic rename should have moved the tempfile into place.
        val ok = storage.appendToHistory(
            ClaudeTabsStorage.SavedSession("sess-1", "D:\\Foo", "Tab 1", false),
            now = 1_000_000_000_000L,
            maxAgeMs = 90L * 24 * 60 * 60 * 1000,
        )
        assertTrue(ok)
        val leftovers = storage.stateDir.listFiles()
            ?.filter { it.name.startsWith("history.json.tmp.") }
            ?: emptyList()
        assertTrue("no .tmp.* leftovers expected, found: ${leftovers.map { it.name }}",
            leftovers.isEmpty())
    }

    // ══════════════════════════════════════════════════════════════
    // saveState — authoritative replace / userClosed / transcriptCheck / ReadFailed
    // ══════════════════════════════════════════════════════════════
    //
    // 1.0.17 follow-up contract change: saveState is AUTHORITATIVE on `newSessions`.
    // Existing entries whose sid is NOT in newSessions are EVICTED. Bug history: the
    // prior "high-water-mark union" preserved stale entries forever — once a dead or
    // migrated sid landed in the file, nothing dropped it. Combined with the now-fixed
    // tab-walk pollution that fed wrong sids into newSessions, corrupted files were
    // one-way trapdoors. Transient-empty preservation (newSessions=[] → don't write)
    // protects against poll races.
    //
    // Name preservation: when newSessions[sid] has a generic tabName ("Claude" / "Local")
    // but the prior file had a descriptive tabName for the same sid, the descriptive name
    // is copied over. This preserves restore-file-only names without keeping zombies.

    @Test fun saveState_authoritativeReplace_newNameWinsForExistingSid() {
        // Existing file has session "x" with the old name; new save passes "x" with a new name.
        // Both are non-generic, so new (more current) wins.
        storage.saveState(projectHash, listOf(session("x", "Old Name")), 0)
        storage.saveState(projectHash, listOf(session("x", "New Name")), 0)
        val parsed = storage.parseSessions(storage.restoreFile(projectHash).readText())
        assertEquals(1, parsed.size)
        assertEquals("New Name", parsed[0].tabName)
    }

    @Test fun saveState_genericLiveName_doesNotClobberDescriptiveSavedName() {
        // Tab with a descriptive name is saved. On the next poll the scanner returns a
        // generic name (no names.json entry yet, no widget title visible). The descriptive
        // name in the existing file is preserved via the name-preservation rule.
        storage.saveState(projectHash, listOf(session("sid", "Topic")), 0)
        storage.saveState(projectHash, listOf(session("sid", "Local")), 0)
        val parsed = storage.parseSessions(storage.restoreFile(projectHash).readText())
        assertEquals("Topic", parsed.single().tabName)
    }

    @Test fun saveState_descriptiveLiveName_replacesGenericSavedName() {
        // Inverse: file has generic, new has descriptive ⇒ descriptive wins.
        // Otherwise legitimate /tab renames would never propagate.
        storage.saveState(projectHash, listOf(session("sid", "Local")), 0)
        storage.saveState(projectHash, listOf(session("sid", "Topic")), 0)
        val parsed = storage.parseSessions(storage.restoreFile(projectHash).readText())
        assertEquals("Topic", parsed.single().tabName)
    }

    @Test fun saveState_genericNewStillPropagatesOtherFields() {
        // The name-preservation rule must not freeze cwd / bypass at their saved
        // values. If the live session moved cwd or flipped bypass, those still flow.
        storage.saveState(projectHash,
            listOf(ClaudeTabsStorage.SavedSession("sid", "/cwd-a", "Topic", false)), 0)
        storage.saveState(projectHash,
            listOf(ClaudeTabsStorage.SavedSession("sid", "/cwd-b", "Local", true)), 0)
        val s = storage.parseSessions(storage.restoreFile(projectHash).readText()).single()
        assertEquals("Topic", s.tabName)
        assertEquals("/cwd-b", s.cwd)
        assertTrue(s.bypassPermissions)
    }

    @Test fun saveState_authoritativeReplace_evictsEntriesNotInNew() {
        // The eviction contract: any sid in the existing file that is NOT in newSessions
        // is dropped. This is what fixes the cross-project / dead-zombie leak — once the
        // scanner correctly excludes a sid (because the process died, or the cwd moved
        // to a different project), the file rebuilds without it.
        //
        // Poll 1: scanner sees x and y → file = [x, y].
        // Poll 2: scanner sees only x (y migrated to a different project, or died) →
        // file MUST = [x] only. y is evicted.
        storage.saveState(projectHash,
            listOf(session("x", "X"), session("y", "Y")), 0)
        storage.saveState(projectHash, listOf(session("x", "X")), 0)
        val parsed = storage.parseSessions(storage.restoreFile(projectHash).readText())
        assertEquals(1, parsed.size)
        assertEquals("x", parsed[0].sessionId)
    }

    @Test fun saveState_transientEmptyNew_preservesExistingFile() {
        // Critical safety property: if newSessions is empty (poll race, scanner returned
        // nothing transiently, startup grace, Claude binary error), do NOT touch the file.
        // The existing content must be preserved verbatim. Otherwise a single bad poll
        // would wipe every saved session in the project.
        storage.saveState(projectHash,
            listOf(session("a", "A"), session("b", "B")), 0)
        val before = storage.restoreFile(projectHash).readText()
        val result = storage.saveState(projectHash, emptyList(), 0)
        val after = storage.restoreFile(projectHash).readText()
        assertEquals("transient empty must not write", null, result)
        assertEquals("file content must be byte-identical", before, after)
    }

    @Test fun saveState_twoPollGrace_keepsExistingOnFirstMiss() {
        // Pin the two-poll-grace contract: a sid in existing but NOT in newSessions is
        // KEPT this save if the caller passes it via keepExistingSids (first miss = grace),
        // and is EVICTED if the caller doesn't pass it (second miss = conviction).
        //
        // Poll 1: scanner sees [a, b]. Both saved → file = [a, b]. nothing missed.
        storage.saveState(projectHash, listOf(session("a", "A"), session("b", "B")), 0)
        // Poll 2: scanner sees only [a]. Caller computes (existing - new) = {b}. b is not
        // in caller's previousMissed (it was present last poll), so caller passes
        // keepExistingSids = {b} → first-miss grace, file stays [a, b].
        storage.saveState(projectHash, listOf(session("a", "A")), 0,
            keepExistingSids = setOf("b"))
        val afterGrace = storage.parseSessions(storage.restoreFile(projectHash).readText())
        assertEquals(setOf("a", "b"), afterGrace.map { it.sessionId }.toSet())

        // Poll 3: scanner still sees only [a]. b is now in caller's previousMissed (it was
        // graced last poll), so caller passes keepExistingSids = {} → b evicted, file = [a].
        storage.saveState(projectHash, listOf(session("a", "A")), 0,
            keepExistingSids = emptySet())
        val afterEvict = storage.parseSessions(storage.restoreFile(projectHash).readText())
        assertEquals(setOf("a"), afterEvict.map { it.sessionId }.toSet())
    }

    @Test fun saveState_twoPollGrace_recoversIfSidReappears() {
        // Scanner momentarily missed sid b (transient PID race / Claude restart) at poll 2,
        // but it's back at poll 3 → grace forgives the miss, no eviction.
        //
        // Poll 1: [a, b] → file = [a, b].
        storage.saveState(projectHash, listOf(session("a", "A"), session("b", "B")), 0)
        // Poll 2: [a] only. Grace keeps b.
        storage.saveState(projectHash, listOf(session("a", "A")), 0,
            keepExistingSids = setOf("b"))
        // Poll 3: scanner sees b again. Caller's previousMissed had {b} but b is now in
        // newSessions so it doesn't need grace → keepExistingSids = {}. File = [a, b].
        storage.saveState(projectHash, listOf(session("a", "A"), session("b", "B")), 0,
            keepExistingSids = emptySet())
        val parsed = storage.parseSessions(storage.restoreFile(projectHash).readText())
        assertEquals(setOf("a", "b"), parsed.map { it.sessionId }.toSet())
    }

    @Test fun scenario_tabWalkPollutionDropped() {
        // Regression pin matching the exact bug shape that drove this fix. Before: a
        // project's restore file had a real entry plus a cross-project leak (sid alive
        // in a different project) plus a dead zombie (process gone, transcript still
        // on disk). The bugged union kept all three forever; new alive sessions never
        // appeared because the tab-walk pollution tricked the scanner into skipping
        // them as already-active.
        //
        // After the fix: the scanner produces the authoritative list of alive-and-ours
        // sessions, and saveState rebuilds the file from that. Stale entries evicted,
        // real new entries appear.
        //
        // Setup: pre-bug state of the file (after the tab-walk pollution had run).
        storage.saveState(projectHash, listOf(
            session("real-alive", "Important Topic"),     // legitimate alive session
            session("cross-project", "Local"),            // alive but actually belongs to another project
            session("dead-zombie", "Local"),              // dead process, transcript still on disk
        ), 0)
        // The next poll: scanner correctly identifies what's alive-and-in-this-project.
        // (cross-project and dead-zombie correctly absent; two new alive sessions present.)
        storage.saveState(projectHash, listOf(
            session("real-alive", "Important Topic"),
            session("new-alive-1", "Claude"),
            session("new-alive-2", "Claude"),
        ), 0)
        val parsed = storage.parseSessions(storage.restoreFile(projectHash).readText())
        val ids = parsed.map { it.sessionId }.toSet()
        assertEquals(setOf("real-alive", "new-alive-1", "new-alive-2"), ids)
        assertTrue("cross-project leak must be evicted", "cross-project" !in ids)
        assertTrue("dead-zombie must be evicted", "dead-zombie" !in ids)
    }

    @Test fun saveState_subtractsUserClosedSessions() {
        // y is in existing AND in new; userClosed contains y → file must NOT have y.
        storage.saveState(projectHash,
            listOf(session("x", "X"), session("y", "Y")), 0)
        storage.saveState(projectHash,
            listOf(session("x", "X"), session("y", "Y")), 0,
            userClosedSessionIds = setOf("y"))
        val parsed = storage.parseSessions(storage.restoreFile(projectHash).readText())
        assertEquals(1, parsed.size)
        assertEquals("x", parsed[0].sessionId)
    }

    @Test fun saveState_transcriptCheckDropsEntriesWithMissingTranscript() {
        // "ghost" is in newSessions but its transcript check returns false → dropped.
        storage.saveState(projectHash,
            listOf(session("real", "Real"), session("ghost", "Ghost")), 0,
            transcriptCheck = { _, sid -> sid != "ghost" })
        val parsed = storage.parseSessions(storage.restoreFile(projectHash).readText())
        assertEquals(1, parsed.size)
        assertEquals("real", parsed[0].sessionId)
    }

    @Test fun saveState_refusesToOverwriteCorruptedFile() {
        // Restore file exists with non-trivial content that loadRestoreSafe flags as ReadFailed.
        // saveState must NOT overwrite it (would clobber recoverable state).
        val f = storage.restoreFile(projectHash)
        f.parentFile.mkdirs()
        val corrupt = "this content has no parseable session objects at all"
        f.writeText(corrupt)

        val result = storage.saveState(projectHash, listOf(session("new", "New")), 0)
        assertNull("saveState must return null when existing file is corrupted", result)
        assertEquals("file content must be untouched", corrupt, f.readText())
    }

    @Test fun saveState_userClosedAndTranscriptCheckLeaveFileUnchanged_whenResultEmpty() {
        // Start with one entry, then call save where userClosed removes it AND no new entries.
        // The contract: never write an empty list — preserve previous file.
        storage.saveState(projectHash, listOf(session("only", "Only")), 0)
        val before = storage.restoreFile(projectHash).readText()
        storage.saveState(projectHash, emptyList(), 0,
            userClosedSessionIds = setOf("only"))
        assertEquals("file must be unchanged when result would be empty",
            before, storage.restoreFile(projectHash).readText())
    }

    // ══════════════════════════════════════════════════════════════
    // loadRestoreSafe
    // ══════════════════════════════════════════════════════════════

    @Test fun loadRestoreSafe_missingFile_returnsOkEmpty() {
        val r = storage.loadRestoreSafe("never-existed")
        assertTrue(r is ClaudeTabsStorage.RestoreRead.Ok)
        assertEquals(0, (r as ClaudeTabsStorage.RestoreRead.Ok).sessions.size)
    }

    @Test fun loadRestoreSafe_emptyArray_returnsOkEmpty() {
        storage.restoreFile(projectHash).also { it.parentFile.mkdirs(); it.writeText("[]") }
        val r = storage.loadRestoreSafe(projectHash)
        assertTrue(r is ClaudeTabsStorage.RestoreRead.Ok)
        assertEquals(0, (r as ClaudeTabsStorage.RestoreRead.Ok).sessions.size)
    }

    @Test fun loadRestoreSafe_corruptedNonTrivial_returnsReadFailed() {
        storage.restoreFile(projectHash).also {
            it.parentFile.mkdirs()
            it.writeText("not actually JSON, no braces here")
        }
        val r = storage.loadRestoreSafe(projectHash)
        assertTrue("expected ReadFailed, got $r",
            r is ClaudeTabsStorage.RestoreRead.ReadFailed)
    }

    @Test fun loadRestoreSafe_validJson_returnsOkWithParsedSessions() {
        storage.saveState(projectHash,
            listOf(session("a", "Alpha"), session("b", "Beta")), 0)
        val r = storage.loadRestoreSafe(projectHash)
        assertTrue(r is ClaudeTabsStorage.RestoreRead.Ok)
        val list = (r as ClaudeTabsStorage.RestoreRead.Ok).sessions
        assertEquals(2, list.size)
        assertTrue(list.any { it.sessionId == "a" && it.tabName == "Alpha" })
    }

    @Test fun appendToHistory_preservesExistingEntries_whenReadSucceeds() {
        // The whole point of the safe-read fix: existing entries must survive an append.
        storage.historyFile.parentFile.mkdirs()
        storage.historyFile.writeText("""
            [
              {"sessionId":"old-1","cwd":"D:\\X","tabName":"Old A","bypassPermissions":false,"closedAt":1700000000000},
              {"sessionId":"old-2","cwd":"D:\\X","tabName":"Old B","bypassPermissions":false,"closedAt":1700000001000}
            ]
        """.trimIndent())

        val ok = storage.appendToHistory(
            ClaudeTabsStorage.SavedSession("new-1", "D:\\Y", "New C", false),
            now = 1_800_000_000_000L,
            maxAgeMs = 365L * 100 * 24 * 60 * 60 * 1000,  // effectively no age cutoff
        )
        assertTrue(ok)

        val text = storage.historyFile.readText()
        assertTrue("old-1 must survive: $text", text.contains("old-1"))
        assertTrue("old-2 must survive: $text", text.contains("old-2"))
        assertTrue("new-1 must be added: $text", text.contains("new-1"))
    }

    // ══════════════════════════════════════════════════════════════
    // names.json — single source of truth for tab names
    // ══════════════════════════════════════════════════════════════

    @Test fun names_missingFile_loadReturnsEmptyMap() {
        assertTrue(storage.loadNames().isEmpty())
    }

    @Test fun names_emptyObjectFile_loadReturnsEmptyMap() {
        storage.namesFile.parentFile.mkdirs()
        storage.namesFile.writeText("{}")
        assertTrue(storage.loadNames().isEmpty())
    }

    @Test fun names_upsertCreatesFileAndPersists() {
        storage.upsertName("sid-1", "Login Bug", "user", now = 1000L)
        assertTrue(storage.namesFile.exists())
        val loaded = storage.loadNames()
        assertEquals(1, loaded.size)
        assertEquals("Login Bug", loaded["sid-1"]?.name)
        assertEquals("user", loaded["sid-1"]?.setBy)
        assertEquals(1000L, loaded["sid-1"]?.setAt)
    }

    @Test fun names_upsertReplacesExistingEntry() {
        storage.upsertName("sid-1", "Old Name", "user", now = 1000L)
        storage.upsertName("sid-1", "New Name", "user", now = 2000L)
        val loaded = storage.loadNames()
        assertEquals(1, loaded.size)
        assertEquals("New Name", loaded["sid-1"]?.name)
        assertEquals(2000L, loaded["sid-1"]?.setAt)
    }

    @Test fun names_upsertSkipsWriteWhenContentIdentical() {
        storage.upsertName("sid-1", "Same Name", "user", now = 1000L)
        val mtime1 = storage.namesFile.lastModified()
        Thread.sleep(10) // ensure mtime would change if a write happened
        storage.upsertName("sid-1", "Same Name", "user", now = 9999L)
        val mtime2 = storage.namesFile.lastModified()
        assertEquals("no-op write should not change mtime", mtime1, mtime2)
    }

    @Test fun names_nameForCanonicalLookup() {
        storage.upsertName("sid-1", "Tab A", "user")
        storage.upsertName("sid-2", "Tab B", "hook")
        assertEquals("Tab A", storage.nameFor("sid-1"))
        assertEquals("Tab B", storage.nameFor("sid-2"))
        assertNull(storage.nameFor("nonexistent"))
    }

    @Test fun names_aliasCopiesNameToNewSid() {
        storage.upsertName("canonical-1", "Original Name", "user", now = 1000L)
        storage.aliasName("canonical-1", "rotated-1", now = 2000L)
        val loaded = storage.loadNames()
        assertEquals(2, loaded.size)
        assertEquals("Original Name", loaded["canonical-1"]?.name)
        assertEquals("Original Name", loaded["rotated-1"]?.name)
        assertEquals("user", loaded["canonical-1"]?.setBy)
        assertEquals("alias", loaded["rotated-1"]?.setBy)
    }

    @Test fun names_aliasIsNoOpWhenTargetAlreadyExists() {
        storage.upsertName("src", "Source", "user")
        storage.upsertName("dst", "Pre-existing", "user")
        storage.aliasName("src", "dst")
        assertEquals("Pre-existing", storage.nameFor("dst"))
    }

    @Test fun names_pruneDropsEntriesWithoutPredicate() {
        storage.upsertName("keep", "K", "user")
        storage.upsertName("drop1", "D1", "user")
        storage.upsertName("drop2", "D2", "user")
        val removed = storage.pruneNames { it == "keep" }
        assertEquals(2, removed)
        assertEquals(1, storage.loadNames().size)
        assertNotNull(storage.nameFor("keep"))
    }

    @Test fun names_handlesUnicodeAndEscapesRoundTrip() {
        val name = "✳ Tab \"with\" \\ backslash"
        storage.upsertName("sid-x", name, "user")
        assertEquals(name, storage.nameFor("sid-x"))
    }

    @Test fun names_loadCachesUntilMtimeChanges() {
        storage.upsertName("sid-1", "Original", "user")
        assertEquals("Original", storage.nameFor("sid-1"))
        // Sneak a manual edit that bypasses the cache invalidation. Without mtime change
        // this would normally be hidden by the cache — but the test ensures we DO re-read
        // on mtime change by touching the file's mtime forward.
        val newContent = """{"sid-1":{"name":"Manual","setBy":"user","setAt":1}}"""
        Thread.sleep(20) // ensure new mtime
        storage.namesFile.writeText(newContent)
        assertTrue("manual mtime bump", storage.namesFile.setLastModified(System.currentTimeMillis() + 1000))
        assertEquals("Manual", storage.nameFor("sid-1"))
    }

    // ══════════════════════════════════════════════════════════════
    // user-closed store
    // ══════════════════════════════════════════════════════════════

    @Test fun userClosed_missingFileReturnsEmptySet() {
        assertTrue(storage.loadUserClosed(projectHash).isEmpty())
    }

    @Test fun userClosed_addAndLoadRoundTrip() {
        assertTrue(storage.addUserClosed(projectHash, "sid-1"))
        assertTrue(storage.addUserClosed(projectHash, "sid-2"))
        val loaded = storage.loadUserClosed(projectHash)
        assertEquals(setOf("sid-1", "sid-2"), loaded)
    }

    @Test fun userClosed_addIdempotent() {
        assertTrue(storage.addUserClosed(projectHash, "sid-1"))
        assertFalse("second add should report no change", storage.addUserClosed(projectHash, "sid-1"))
    }

    @Test fun userClosed_perProjectIsolation() {
        storage.addUserClosed("proj-A", "sid-A1")
        storage.addUserClosed("proj-B", "sid-B1")
        assertEquals(setOf("sid-A1"), storage.loadUserClosed("proj-A"))
        assertEquals(setOf("sid-B1"), storage.loadUserClosed("proj-B"))
    }

    @Test fun userClosed_pruneDropsEntriesWithoutPredicate() {
        storage.addUserClosed(projectHash, "keep")
        storage.addUserClosed(projectHash, "drop")
        val removed = storage.pruneUserClosed(projectHash) { it == "keep" }
        assertEquals(1, removed)
        assertEquals(setOf("keep"), storage.loadUserClosed(projectHash))
    }

    // ══════════════════════════════════════════════════════════════
    // backups — 3-tier recovery
    // ══════════════════════════════════════════════════════════════

    @Test fun backups_rotateCreatesBackup1OnFirstWrite() {
        val content = storage.serialiseSessions(listOf(session("a", "A")))
        storage.rotateBackups(projectHash, content)
        assertTrue(storage.backupFile(projectHash, 1).exists())
        assertFalse(storage.backupFile(projectHash, 2).exists())
        assertFalse(storage.backupFile(projectHash, 3).exists())
    }

    @Test fun backups_rotateShiftsExistingBackups() {
        val v1 = storage.serialiseSessions(listOf(session("a", "v1")))
        val v2 = storage.serialiseSessions(listOf(session("a", "v2")))
        val v3 = storage.serialiseSessions(listOf(session("a", "v3")))
        val v4 = storage.serialiseSessions(listOf(session("a", "v4")))
        storage.rotateBackups(projectHash, v1)
        storage.rotateBackups(projectHash, v2)
        storage.rotateBackups(projectHash, v3)
        storage.rotateBackups(projectHash, v4)  // pushes v1 out

        assertEquals(v4, storage.backupFile(projectHash, 1).readText())
        assertEquals(v3, storage.backupFile(projectHash, 2).readText())
        assertEquals(v2, storage.backupFile(projectHash, 3).readText())
    }

    @Test fun backups_rotateSkipsWhenIdenticalToBackup1() {
        val content = storage.serialiseSessions(listOf(session("a", "Stable")))
        storage.rotateBackups(projectHash, content)
        val originalMtime = storage.backupFile(projectHash, 1).lastModified()
        Thread.sleep(20)
        // Second call with same content: backup-1 should not be rewritten
        storage.rotateBackups(projectHash, content)
        assertEquals(originalMtime, storage.backupFile(projectHash, 1).lastModified())
        assertFalse("no rotation should happen", storage.backupFile(projectHash, 2).exists())
    }

    @Test fun backups_rotateSkipsEmptyContent() {
        storage.rotateBackups(projectHash, "[]")
        storage.rotateBackups(projectHash, "")
        storage.rotateBackups(projectHash, "   ")
        assertFalse(storage.backupFile(projectHash, 1).exists())
    }

    @Test fun load_fallsBackToBackupWhenLiveCorrupt() {
        // Write a non-empty backup-1, then corrupt the live file.
        val good = listOf(session("recovered", "From Backup"))
        storage.rotateBackups(projectHash, storage.serialiseSessions(good))
        storage.restoreFile(projectHash).also {
            it.parentFile.mkdirs()
            it.writeText("garbage")
        }
        val result = storage.loadRestoreWithFallback(projectHash)
        assertEquals(1, result.sessions.size)
        assertEquals("recovered", result.sessions[0].sessionId)
        assertEquals("backup-1", result.tier)
    }

    @Test fun load_fallsThroughBackup1ToBackup2WhenBoth1AndLiveCorrupt() {
        val v1 = listOf(session("v1", "V1"))
        val v2 = listOf(session("v2", "V2"))
        storage.rotateBackups(projectHash, storage.serialiseSessions(v1))
        storage.rotateBackups(projectHash, storage.serialiseSessions(v2))
        // Corrupt live and backup-1
        storage.restoreFile(projectHash).also { it.parentFile.mkdirs(); it.writeText("garbage") }
        storage.backupFile(projectHash, 1).writeText("also garbage")
        // backup-2 still has v1 content
        val result = storage.loadRestoreWithFallback(projectHash)
        assertEquals("v1", result.sessions[0].sessionId)
        assertEquals("backup-2", result.tier)
    }

    @Test fun saveState_writesBackupToBackup1() {
        // saveState rotates backups before each non-empty write.
        storage.saveState(projectHash, listOf(session("a", "First")), 5, now = 100L)
        assertTrue("backup-1 should exist after first save",
            storage.backupFile(projectHash, 1).exists())
        storage.saveState(projectHash, listOf(session("a", "Second")), 5, now = 200L)
        val b1Content = storage.backupFile(projectHash, 1).readText()
        assertTrue("backup-1 should reflect most recent save", b1Content.contains("Second"))
    }
}
