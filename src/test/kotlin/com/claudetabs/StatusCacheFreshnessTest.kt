package com.claudetabs

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The parse cache must never cost freshness.
 *
 * These files are read on a 400ms cadence and almost never change — 107 of them backed two
 * live sessions on a real install — so unchanged files are parsed once and remembered. That
 * is only safe if a *changed* file is still picked up on the very next read: the whole point
 * of the indicator is that it moves the moment a hook fires.
 *
 * The risk is specific. Hook files are rewritten in place, often within the same millisecond
 * of a previous edge, so an mtime comparison alone can miss an update on a filesystem with
 * coarse timestamps. Hence size as well, and hence these tests.
 */
class StatusCacheFreshnessTest {

    @get:Rule val tmp = TemporaryFolder()

    private val sid = "aaaaaaaa-1111-2222-3333-444444444444"

    private fun statusDir() = File(tmp.root, "rider-plugin/status").apply { mkdirs() }
    private fun sessionsDir() = File(tmp.root, "sessions").apply { mkdirs() }

    private fun writeHook(event: String, ts: Long, mtime: Long) {
        val f = File(statusDir(), "$sid.json")
        f.writeText("""{"event":"$event","source":"","sessionId":"$sid","ts":$ts,"pid":4242}""")
        f.setLastModified(mtime)
    }

    private fun writeSession(pid: Long, status: String, updatedAt: Long, mtime: Long) {
        val f = File(sessionsDir(), "$pid.json")
        f.writeText("""{"sessionId":"$sid","status":"$status","statusUpdatedAt":$updatedAt}""")
        f.setLastModified(mtime)
    }

    @Test fun aNewHookEdgeIsSeenImmediately() {
        val store = ClaudeStatusStore(tmp.root)
        writeHook("UserPromptSubmit", ts = 1_000, mtime = 10_000)
        assertEquals(ClaudeStatus.WORKING, store.snapshot { true }[sid]?.status)

        writeHook("Stop", ts = 2_000, mtime = 20_000)
        assertEquals(
            "the tab must follow the turn, not a cached edge",
            ClaudeStatus.FINISHED, store.snapshot { true }[sid]?.status,
        )
    }

    /**
     * Two edges written inside one filesystem timestamp tick. `Stop` → `UserPromptSubmit`
     * is the common pair (you reply the instant a turn ends), and the payloads differ in
     * length, which is what the size check is there to catch.
     */
    @Test fun anEdgeWrittenWithinTheSameMtimeIsStillSeen() {
        val store = ClaudeStatusStore(tmp.root)
        writeHook("Stop", ts = 1_000, mtime = 10_000)
        assertEquals(ClaudeStatus.FINISHED, store.snapshot { true }[sid]?.status)

        writeHook("UserPromptSubmit", ts = 1_000, mtime = 10_000)
        assertEquals(ClaudeStatus.WORKING, store.snapshot { true }[sid]?.status)
    }

    @Test fun claudesOwnStatusChangeIsSeenImmediately() {
        val store = ClaudeStatusStore(tmp.root)
        writeSession(4242, "idle", updatedAt = 1_000, mtime = 10_000)
        assertEquals(ClaudeStatus.IDLE, store.snapshot { true }[sid]?.status)

        writeSession(4242, "busy", updatedAt = 2_000, mtime = 20_000)
        assertEquals(ClaudeStatus.WORKING, store.snapshot { true }[sid]?.status)
    }

    /**
     * Liveness is deliberately not cached: the file stops changing the instant the process
     * dies, which is precisely when the answer has to change.
     */
    @Test fun aDeadProcessIsNoticedThoughNothingOnDiskChanged() {
        val store = ClaudeStatusStore(tmp.root)
        writeSession(4242, "busy", updatedAt = 1_000, mtime = 10_000)
        assertEquals(ClaudeStatus.WORKING, store.snapshot { true }[sid]?.status)
        assertEquals(ClaudeStatus.EXITED, store.snapshot { false }[sid]?.status)
    }

    @Test fun theTerminalBridgeFollowsANewSession() {
        val store = ClaudeStatusStore(tmp.root)
        val term = "TERM-1"
        val f = File(statusDir(), "termsess-$term.json")
        f.writeText("""{"event":"SessionStart","sessionId":"$sid","ts":1000,"pid":1}""")
        f.setLastModified(10_000)
        assertEquals(sid, store.termSessionMap()[term])

        val other = "bbbbbbbb-1111-2222-3333-444444444444"
        f.writeText("""{"event":"SessionStart","sessionId":"$other","ts":2000,"pid":1}""")
        f.setLastModified(20_000)
        assertEquals("a terminal reused by a new session must remap", other, store.termSessionMap()[term])
    }

    /** A deleted session must stop being reported, not linger in the cache. */
    @Test fun aRemovedFileDropsOutOfTheSnapshot() {
        val store = ClaudeStatusStore(tmp.root)
        writeHook("Stop", ts = 1_000, mtime = 10_000)
        assertEquals(ClaudeStatus.FINISHED, store.snapshot { true }[sid]?.status)

        File(statusDir(), "$sid.json").delete()
        assertEquals(null, store.snapshot { true }[sid])
    }
}
