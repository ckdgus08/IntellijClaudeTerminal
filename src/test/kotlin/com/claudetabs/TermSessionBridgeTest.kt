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
 * six live Claude sessions hung off shells the enumeration never mentioned (96253, 44469).
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
        writeTermHook("e826544a-9543-49a0-a347-3f33df07c617", "c0a4f05e-76b6-48c7-8b7e-ba7b01302f47")
        writeTermHook("25dc357c-17b3-4ffd-b330-0adaa3fc3ab1", "eb442e0b-642f-40a9-8c1a-b074830e6d1d")

        val map = store.termSessionMap()
        assertEquals(2, map.size)
        assertEquals("c0a4f05e-76b6-48c7-8b7e-ba7b01302f47", map["e826544a-9543-49a0-a347-3f33df07c617"])
        assertEquals("eb442e0b-642f-40a9-8c1a-b074830e6d1d", map["25dc357c-17b3-4ffd-b330-0adaa3fc3ab1"])
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
