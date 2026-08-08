package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The `TERM_SESSION_ID → sessionId` bridge, which is what actually attaches the status
 * indicator to a tab on IntelliJ 2026.1's reworked terminal.
 *
 * Why it is needed, from a real install: `getAllTabs` reported three tabs whose shell PIDs
 * (94329, 95752) were childless `/bin/zsh --login -i` and one (95258) already dead, while
 * six live Claude sessions hung off shells the enumeration never mentioned (5001, 5002).
 * Walking `shell pid → child claude` finds nothing when the shell pid belongs to a different
 * tab. TERM_SESSION_ID is inherited by everything the tab spawns, so the hook can record the
 * mapping from inside the session and no PID is involved anywhere.
 */
class TermSessionBridgeTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var home: File
    private lateinit var store: ClaudeStatusStore

    private fun setUp(): ClaudeStatusStore {
        home = tmp.newFolder("claude-home")
        File(home, "sessions").mkdirs()
        File(home, "rider-plugin/status").mkdirs()
        store = ClaudeStatusStore(home)
        return store
    }

    private fun writeTermHook(termSessionId: String, sid: String, event: String = "SessionStart", ts: Long = 1000) {
        File(home, "rider-plugin/status/termsess-$termSessionId.json").writeText(
            """{"event":"$event","sessionId":"$sid","ts":$ts,"pid":1}"""
        )
    }

    @Test fun mapsTerminalToSession() {
        setUp()
        // The real values observed on the dev machine.
        writeTermHook("70000001-0000-4000-8000-000000000001", "70000002-0000-4000-8000-000000000002")
        writeTermHook("70000003-0000-4000-8000-000000000003", "70000004-0000-4000-8000-000000000004")

        val map = store.termSessionMap()
        assertEquals(2, map.size)
        assertEquals("70000002-0000-4000-8000-000000000002", map["70000001-0000-4000-8000-000000000001"])
        assertEquals("70000004-0000-4000-8000-000000000004", map["70000003-0000-4000-8000-000000000003"])
    }

    @Test fun aReusedTerminalReportsItsCurrentSession() {
        // Exit Claude, start it again in the same tab: same TERM_SESSION_ID, new session.
        // The tab must show the session in it now, not the one that ended.
        setUp()
        writeTermHook("term-1", "old-session", event = "SessionEnd", ts = 1000)
        writeTermHook("term-1", "new-session", event = "SessionStart", ts = 2000)

        assertEquals("new-session", store.termSessionMap()["term-1"])
    }

    @Test fun outOfOrderWritesStillResolveToTheNewest() {
        setUp()
        // Directory listing order is not timestamp order; the ts field decides.
        File(home, "rider-plugin/status/termsess-term-1.json").writeText(
            """{"event":"SessionStart","sessionId":"newer","ts":5000,"pid":1}"""
        )
        assertEquals("newer", store.termSessionMap()["term-1"])
    }

    @Test fun ignoresTheSessionKeyedFiles() {
        // status/<sessionId>.json drives the state; only termsess-* carries the terminal id.
        setUp()
        File(home, "rider-plugin/status/some-session-id.json").writeText(
            """{"event":"Stop","sessionId":"some-session-id","ts":1,"pid":1}"""
        )
        assertTrue(store.termSessionMap().isEmpty())
    }

    @Test fun malformedOrEmptyEntriesAreSkipped() {
        setUp()
        File(home, "rider-plugin/status/termsess-bad.json").writeText("not json")
        File(home, "rider-plugin/status/termsess-blank.json").writeText("""{"event":"Stop","sessionId":"","ts":1}""")
        writeTermHook("term-ok", "sid-ok")

        assertEquals(mapOf("term-ok" to "sid-ok"), store.termSessionMap())
    }

    @Test fun noStatusDirectoryIsNotAnError() {
        assertTrue(ClaudeStatusStore(tmp.newFolder("empty")).termSessionMap().isEmpty())
    }

    @Test fun theBridgeAndTheStateSignalAgreeOnTheSameSession() {
        // End to end on the file layer: the terminal resolves to a session, and that session
        // has a state to show.
        setUp()
        writeTermHook("term-1", "sid-1", event = "UserPromptSubmit", ts = 2000)
        File(home, "rider-plugin/status/sid-1.json").writeText(
            """{"event":"UserPromptSubmit","sessionId":"sid-1","ts":2000,"pid":1}"""
        )

        val sid = store.termSessionMap()["term-1"]!!
        assertEquals("sid-1", sid)
        assertEquals(ClaudeStatus.WORKING, store.snapshot { true }[sid]?.status)
    }
}
