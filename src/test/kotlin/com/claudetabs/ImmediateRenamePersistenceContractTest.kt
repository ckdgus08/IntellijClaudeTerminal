package com.claudetabs

import com.claudetabs.ClaudeTabsStorage.SavedSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Contract tests for [ImmediateRenamePersistence.compute] — the "writing-action /tab"
 * rule that says: when a user runs `/tab <name>` (or right-clicks Rename Session in a
 * Claude tab), the new name must land in the restore file IMMEDIATELY for that one
 * session, without waiting for the next poll. Two scopes:
 *
 *  - Pure-logic tests: the [compute] function itself.
 *  - Round-trip tests: pair [compute] with [ClaudeTabsStorage] to prove the end-to-end
 *    behaviour on disk matches the contract (and that other sessions in the restore
 *    file are not disturbed).
 */
class ImmediateRenamePersistenceContractTest {

    @get:Rule val tmp = TemporaryFolder()

    private val projectBase = "D:\\Dev\\Proj"
    private val isUnder: (String, String?) -> Boolean = ClaudeTabsHelpers::isCwdUnderProject

    private fun session(id: String, name: String, cwd: String = "$projectBase\\sub", bypass: Boolean = false) =
        SavedSession(id, cwd, name, bypass)

    // ══════════════════════════════════════════════════════════════
    // PURE — existing entry: update name, preserve cwd + bypassPermissions
    // ══════════════════════════════════════════════════════════════

    @Test fun existingEntry_updatesNameAndPreservesCwd() {
        val before = listOf(session("sid-1", "Old Name", cwd = "$projectBase\\one", bypass = true))
        val outcome = ImmediateRenamePersistence.compute(
            canonicalSessionId = "sid-1",
            newName = "Brand New Name",
            existing = before,
            cwdHint = null,
            bypassPermissionsHint = false,
            projectBasePath = projectBase,
            isCwdUnderProject = isUnder,
        )
        assertTrue("expected Write, got $outcome", outcome is ImmediateRenamePersistence.Outcome.Write)
        val after = (outcome as ImmediateRenamePersistence.Outcome.Write).updated
        assertEquals(1, after.size)
        assertEquals("Brand New Name", after[0].tabName)
        assertEquals("cwd must be preserved from existing entry",
            "$projectBase\\one", after[0].cwd)
        assertTrue("bypassPermissions must be preserved (true) from existing entry",
            after[0].bypassPermissions)
    }

    @Test fun existingEntry_doesNotDisturbOtherEntries() {
        val before = listOf(
            session("sid-A", "Alpha"),
            session("sid-B", "Beta"),
            session("sid-C", "Gamma"),
        )
        val outcome = ImmediateRenamePersistence.compute(
            canonicalSessionId = "sid-B",
            newName = "Beta Renamed",
            existing = before,
            cwdHint = null,
            bypassPermissionsHint = false,
            projectBasePath = projectBase,
            isCwdUnderProject = isUnder,
        )
        val after = (outcome as ImmediateRenamePersistence.Outcome.Write).updated
        assertEquals(3, after.size)
        assertEquals("Alpha", after.first { it.sessionId == "sid-A" }.tabName)
        assertEquals("Beta Renamed", after.first { it.sessionId == "sid-B" }.tabName)
        assertEquals("Gamma", after.first { it.sessionId == "sid-C" }.tabName)
    }

    @Test fun existingEntry_preservesIterationOrder() {
        val before = listOf(
            session("sid-A", "A"),
            session("sid-B", "B"),
            session("sid-C", "C"),
        )
        val outcome = ImmediateRenamePersistence.compute(
            canonicalSessionId = "sid-B",
            newName = "B'",
            existing = before,
            cwdHint = null,
            bypassPermissionsHint = false,
            projectBasePath = projectBase,
            isCwdUnderProject = isUnder,
        )
        val after = (outcome as ImmediateRenamePersistence.Outcome.Write).updated
        // linkedMapOf preserves insertion order; an existing-key put doesn't reorder.
        assertEquals(listOf("sid-A", "sid-B", "sid-C"), after.map { it.sessionId })
    }

    // ══════════════════════════════════════════════════════════════
    // PURE — missing entry + cwd hint: add new entry under hint
    // ══════════════════════════════════════════════════════════════

    @Test fun missingEntry_withCwdHint_addsNewEntry() {
        val outcome = ImmediateRenamePersistence.compute(
            canonicalSessionId = "sid-new",
            newName = "Fresh Tab",
            existing = listOf(session("sid-other", "Other")),
            cwdHint = "$projectBase\\new",
            bypassPermissionsHint = true,
            projectBasePath = projectBase,
            isCwdUnderProject = isUnder,
        )
        val after = (outcome as ImmediateRenamePersistence.Outcome.Write).updated
        val added = after.firstOrNull { it.sessionId == "sid-new" }
        assertNotNull("new entry must be added", added)
        assertEquals("Fresh Tab", added!!.tabName)
        assertEquals("$projectBase\\new", added.cwd)
        assertTrue("bypassPermissionsHint must be used for fresh entry",
            added.bypassPermissions)
        // Other entries preserved.
        assertEquals("Other", after.first { it.sessionId == "sid-other" }.tabName)
    }

    // ══════════════════════════════════════════════════════════════
    // PURE — Skip cases
    // ══════════════════════════════════════════════════════════════

    @Test fun missingEntry_noCwdHint_returnsSkip() {
        val outcome = ImmediateRenamePersistence.compute(
            canonicalSessionId = "sid-x",
            newName = "Whatever",
            existing = emptyList(),
            cwdHint = null,
            bypassPermissionsHint = false,
            projectBasePath = projectBase,
            isCwdUnderProject = isUnder,
        )
        assertTrue("expected Skip, got $outcome",
            outcome is ImmediateRenamePersistence.Outcome.Skip)
    }

    @Test fun crossProjectCwd_returnsSkip() {
        val outcome = ImmediateRenamePersistence.compute(
            canonicalSessionId = "sid-x",
            newName = "Whatever",
            existing = emptyList(),
            cwdHint = "D:\\some\\other\\project",
            bypassPermissionsHint = false,
            projectBasePath = projectBase,
            isCwdUnderProject = isUnder,
        )
        assertTrue("expected Skip for cross-project, got $outcome",
            outcome is ImmediateRenamePersistence.Outcome.Skip)
    }

    @Test fun existingEntry_crossProjectCwd_returnsSkip() {
        // Defensive — the existing entry has a cross-project cwd somehow.
        // We must not touch it. (`compute` checks the RESOLVED cwd against project base.)
        val outcome = ImmediateRenamePersistence.compute(
            canonicalSessionId = "sid-leaked",
            newName = "Rename",
            existing = listOf(SavedSession("sid-leaked", "D:\\Different\\Project", "X", false)),
            cwdHint = null,
            bypassPermissionsHint = false,
            projectBasePath = projectBase,
            isCwdUnderProject = isUnder,
        )
        assertTrue(outcome is ImmediateRenamePersistence.Outcome.Skip)
    }

    // ══════════════════════════════════════════════════════════════
    // ROUND-TRIP via ClaudeTabsStorage
    // ══════════════════════════════════════════════════════════════

    @Test fun roundTrip_renameOneSessionUpdatesRestoreFile() {
        val storage = ClaudeTabsStorage(tmp.root)
        val hash = "test-hash"
        storage.saveState(hash, listOf(
            SavedSession("sid-A", "$projectBase\\a", "Old A", false),
            SavedSession("sid-B", "$projectBase\\b", "Old B", false),
        ), keepCount = 5)

        // Simulate the orchestration's persistRenameImmediately:
        val existing = (storage.loadRestoreSafe(hash) as ClaudeTabsStorage.RestoreRead.Ok).sessions
        val outcome = ImmediateRenamePersistence.compute(
            canonicalSessionId = "sid-A",
            newName = "Renamed A",
            existing = existing,
            cwdHint = null,
            bypassPermissionsHint = false,
            projectBasePath = projectBase,
            isCwdUnderProject = isUnder,
        )
        val updated = (outcome as ImmediateRenamePersistence.Outcome.Write).updated
        storage.writeAtomic(storage.restoreFile(hash), storage.serialiseSessions(updated))

        val reloaded = storage.parseSessions(storage.restoreFile(hash).readText())
        assertEquals(2, reloaded.size)
        assertEquals("Renamed A", reloaded.first { it.sessionId == "sid-A" }.tabName)
        assertEquals("Old B", reloaded.first { it.sessionId == "sid-B" }.tabName)
    }

    @Test fun roundTrip_renameThenPollSaveKeepsRenamedName() {
        // Simulates: user runs /tab → restore file updated with new name → next poll
        // happens (with the OLD name in its in-memory snapshot of the tab title because the
        // poll hasn't observed the rename yet). The union semantics of [storage.saveState]
        // mean the new (poll) name overwrites… which would be a regression: the user's /tab
        // rename gets clobbered by the next poll!
        //
        // This test pins the EXPECTED behaviour going forward: the /tab fast-path is
        // SUPPOSED to also update lastAppliedName so the poll uses the new name. The poll
        // therefore re-saves the same name → no clobber. Here we model the corrected flow
        // by running both saves with the user's chosen name.
        val storage = ClaudeTabsStorage(tmp.root)
        val hash = "h"
        storage.saveState(hash, listOf(SavedSession("sid", "$projectBase\\p", "Initial", false)), keepCount = 0)

        // /tab updates the file
        val existing = (storage.loadRestoreSafe(hash) as ClaudeTabsStorage.RestoreRead.Ok).sessions
        val outcome = ImmediateRenamePersistence.compute(
            canonicalSessionId = "sid",
            newName = "User Chosen",
            existing = existing,
            cwdHint = null,
            bypassPermissionsHint = false,
            projectBasePath = projectBase,
            isCwdUnderProject = isUnder,
        )
        storage.writeAtomic(storage.restoreFile(hash),
            storage.serialiseSessions((outcome as ImmediateRenamePersistence.Outcome.Write).updated))

        // Poll runs — production updates lastAppliedName at /tab time so the title pulled
        // for the save is the user's choice, not Claude's auto-title.
        storage.saveState(hash,
            listOf(SavedSession("sid", "$projectBase\\p", "User Chosen", false)), keepCount = 0)

        val reloaded = storage.parseSessions(storage.restoreFile(hash).readText())
        assertEquals("User Chosen", reloaded[0].tabName)
    }
}
