package com.claudetabs

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(File(home, "rider-plugin"), s.stateDir)
        assertEquals(File(home, "rider-plugin/tabs"), s.tabsDir)
        assertEquals(File(home, "rider-plugin/session-map"), s.sessionMapDir)
        assertEquals(File(home, "rider-plugin/snapshots"), s.snapshotsDir)
        assertEquals(File(home, "rider-plugin/history.json"), s.historyFile)
        assertEquals(File(home, "rider-plugin/config.json"), s.configFile)
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
    // saveState — union / userClosed / transcriptCheck / ReadFailed
    // ══════════════════════════════════════════════════════════════

    @Test fun saveState_unionsExistingAndNew_newTakesPrecedenceOnCollision() {
        // Existing file has session "x" with the old name; new save passes "x" with a new name.
        // Result: file contains "x" with the NEW name (new overlays existing) — but only when
        // both names are non-generic. See generic-name precedence tests below.
        storage.saveState(projectHash, listOf(session("x", "Old Name")), 0)
        storage.saveState(projectHash, listOf(session("x", "New Name")), 0)
        val parsed = storage.parseSessions(storage.restoreFile(projectHash).readText())
        assertEquals(1, parsed.size)
        assertEquals("New Name", parsed[0].tabName)
    }

    @Test fun saveState_genericLiveName_doesNotClobberDescriptiveSavedName() {
        // Bug shape: tab with a descriptive name is saved to disk. On the next poll the
        // live title still reads the JetBrains default ("Local") because Claude hasn't
        // repainted yet after --resume. The naive union let "Local" overlay the saved
        // name; after one cycle the descriptive name was gone.
        storage.saveState(projectHash, listOf(session("sid", "Topic")), 0)
        storage.saveState(projectHash, listOf(session("sid", "Local")), 0)
        val parsed = storage.parseSessions(storage.restoreFile(projectHash).readText())
        assertEquals("Topic", parsed.single().tabName)
    }

    @Test fun saveState_descriptiveLiveName_replacesGenericSavedName() {
        // Inverse of the bug: file has generic, live has descriptive ⇒ descriptive wins.
        // Otherwise legitimate /tab renames would never propagate to disk.
        storage.saveState(projectHash, listOf(session("sid", "Local")), 0)
        storage.saveState(projectHash, listOf(session("sid", "Topic")), 0)
        val parsed = storage.parseSessions(storage.restoreFile(projectHash).readText())
        assertEquals("Topic", parsed.single().tabName)
    }

    @Test fun saveState_genericNewStillPropagatesOtherFields() {
        // Pin: the name-precedence rule must not freeze cwd / bypass at their saved
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

    @Test fun saveState_unionPreservesEntriesPollMissed() {
        // Poll 1: sees both x and y → file has both.
        // Poll 2: sees only x (y was transiently invisible) → file MUST still have both.
        // This is the drift-safety contract: a missed poll cannot evict a session.
        storage.saveState(projectHash,
            listOf(session("x", "X"), session("y", "Y")), 0)
        storage.saveState(projectHash, listOf(session("x", "X")), 0)
        val parsed = storage.parseSessions(storage.restoreFile(projectHash).readText())
        assertEquals(2, parsed.size)
        assertTrue(parsed.any { it.sessionId == "y" })
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
        // "ghost" passes the union but its transcript check returns false → dropped.
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
}
