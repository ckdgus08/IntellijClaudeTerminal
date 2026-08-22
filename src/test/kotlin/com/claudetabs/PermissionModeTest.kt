package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading the permission mode a session runs under.
 *
 * A restored tab re-runs `claude --resume`, and whether that carries
 * `--dangerously-skip-permissions` is decided from this. So "recorded as the default" and
 * "not recorded at all" must stay distinguishable — collapsing both to false is what made a
 * bypass session come back without bypass after `/clear`.
 *
 * The ordinary transcript carries the record, with one important exception — the transcript
 * `/clear` creates:
 *
 *   10000001  {"type":"mode",…} / {"type":"permission-mode","permissionMode":"bypass…"}
 *   10000002  {"type":"mode",…} / {"type":"file-history-snapshot",…}     ← after /clear
 */
class PermissionModeTest {

    private val modeLine = """{"type":"mode","mode":"normal","sessionId":"s"}"""
    private fun permissionLine(mode: String) =
        """{"type":"permission-mode","permissionMode":"$mode","sessionId":"s"}"""
    private val snapshotLine = """{"type":"file-history-snapshot","messageId":"m","snapshot":{}}"""

    @Test fun readsTheRecordedMode() {
        val lines = sequenceOf(modeLine, permissionLine("bypassPermissions"), snapshotLine)
        assertEquals("bypassPermissions", ClaudeTabsHelpers.permissionModeFrom(lines))
    }

    @Test fun readsModesOtherThanBypass() {
        val lines = sequenceOf(modeLine, permissionLine("acceptEdits"))
        assertEquals("acceptEdits", ClaudeTabsHelpers.permissionModeFrom(lines))
    }

    /**
     * The `/clear` shape. Null rather than a default is the whole point: it lets the caller
     * fall back to the mode of the session this one replaced, instead of silently dropping
     * a choice the user made.
     */
    @Test fun reportsAbsenceRatherThanGuessingADefault() {
        val lines = sequenceOf(modeLine, snapshotLine)
        assertNull(ClaudeTabsHelpers.permissionModeFrom(lines))
    }

    /** `"mode":"normal"` on line 1 is a different record and must not be mistaken for it. */
    @Test fun doesNotConfuseTheConversationModeRecord() {
        assertNull(ClaudeTabsHelpers.permissionModeFrom(listOf(modeLine).asSequence()))
    }

    @Test fun survivesMalformedLines() {
        val lines = sequenceOf("not json", """{"type":"permission-mode"}""", permissionLine("bypassPermissions"))
        assertEquals("bypassPermissions", ClaudeTabsHelpers.permissionModeFrom(lines))
    }

    @Test fun emptyTranscriptRecordsNothing() {
        assertNull(ClaudeTabsHelpers.permissionModeFrom(emptySequence()))
    }
}
