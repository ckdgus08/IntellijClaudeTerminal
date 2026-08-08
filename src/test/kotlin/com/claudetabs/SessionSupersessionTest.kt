package com.claudetabs

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `/clear` replaces a session without ending anything the user can see.
 *
 * The terminal, the process and the tab all survive; only Claude's session id rotates. The
 * old id gets a `SessionEnd` hook and drops out of `sessions/<pid>.json`, so a tab still
 * bound to it resolves to EXITED and stays there — a live conversation under a `✕`.
 * Reproduced from a real run:
 *
 *   status/eb442e0b….json  → {"event":"SessionEnd","pid":21312}
 *   sessions/21312.json    → {"sessionId":"78872536…","status":"busy"}
 *
 * The pid is the only link on disk. The new session gets its own transcript and its own
 * hook file, and `termsess-*.json` keeps just the newest id per terminal, so nothing else
 * can tell "replaced" from "gone".
 */
class SessionSupersessionTest {

    @get:Rule val tmp = TemporaryFolder()

    private val old = "eb442e0b-642f-40a9-8c1a-b074830e6d1d"
    private val new = "78872536-c8b9-4f4f-a08a-82768e91c100"

    private fun home(): File = tmp.root
    private fun statusDir() = File(home(), "rider-plugin/status").apply { mkdirs() }
    private fun sessionsDir() = File(home(), "sessions").apply { mkdirs() }

    private fun hook(sid: String, event: String, pid: Long) {
        File(statusDir(), "$sid.json")
            .writeText("""{"event":"$event","source":"","sessionId":"$sid","ts":1786193571236,"pid":$pid}""")
    }

    private fun session(pid: Long, sid: String, status: String = "busy") {
        File(sessionsDir(), "$pid.json")
            .writeText("""{"sessionId":"$sid","status":"$status","kind":"interactive","statusUpdatedAt":1786193571300}""")
    }

    private fun store() = ClaudeStatusStore(home())

    @Test fun linksTheReplacedSessionToItsReplacementByPid() {
        hook(old, "SessionEnd", 21312)
        session(21312, new)
        assertEquals(mapOf(old to new), store().supersededSessions { true })
    }

    /** A process that really did exit is a real death, and must stay one. */
    @Test fun aDeadProcessIsNotASupersession() {
        hook(old, "SessionEnd", 21312)
        session(21312, new)
        assertTrue(store().supersededSessions { false }.isEmpty())
    }

    /** No session file for the pid means nothing took over — the session genuinely ended. */
    @Test fun anEndedSessionWithNoSuccessorIsNotASupersession() {
        hook(old, "SessionEnd", 21312)
        assertTrue(store().supersededSessions { true }.isEmpty())
    }

    /**
     * The ordinary case: the session file still names the same session. Treating that as a
     * supersession would re-key every tab to itself on every poll.
     */
    @Test fun theSameIdIsNotASupersession() {
        hook(old, "SessionEnd", 21312)
        session(21312, old)
        assertTrue(store().supersededSessions { true }.isEmpty())
    }

    /** Only an ended session can have been replaced; a running one is just running. */
    @Test fun ignoresHookEventsThatAreNotSessionEnd() {
        for (event in listOf("SessionStart", "UserPromptSubmit", "Notification", "Stop")) {
            statusDir().listFiles()?.forEach { it.delete() }
            hook(old, event, 21312)
            session(21312, new)
            assertTrue("$event should not count", store().supersededSessions { true }.isEmpty())
        }
    }

    /** The TERM_SESSION_ID files share the directory and must not be mistaken for records. */
    @Test fun ignoresTermSessionBridgeFiles() {
        File(statusDir(), "termsess-abc.json")
            .writeText("""{"event":"SessionEnd","sessionId":"$old","ts":1,"pid":21312}""")
        session(21312, new)
        assertTrue(store().supersededSessions { true }.isEmpty())
    }

    @Test fun survivesMissingAndMalformedFiles() {
        File(statusDir(), "$old.json").writeText("not json")
        File(sessionsDir(), "21312.json").writeText("{")
        assertTrue(store().supersededSessions { true }.isEmpty())
    }

    /**
     * The status the tab should end up showing once the hand-over happens: the replacement
     * is a normal live session, not a terminal state.
     */
    @Test fun theReplacementResolvesToARunningState() {
        hook(old, "SessionEnd", 21312)
        session(21312, new, status = "busy")
        val readings = store().snapshot { true }
        assertEquals(ClaudeStatus.WORKING, readings[new]?.status)
        assertEquals(ClaudeStatus.EXITED, readings[old]?.status)
    }
}
