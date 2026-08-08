package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [ClaudeStatusStore] against a real (temp) `~/.claude` layout — the file shapes here are
 * copied from an actual Claude Code 2.1.226 install.
 */
class ClaudeStatusStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var home: File
    private lateinit var store: ClaudeStatusStore

    private fun setUpHome(): ClaudeStatusStore {
        home = tmp.newFolder("claude-home")
        File(home, "sessions").mkdirs()
        File(home, "rider-plugin/status").mkdirs()
        store = ClaudeStatusStore(home)
        return store
    }

    private fun writeSession(pid: Long, sid: String, status: String?, statusUpdatedAt: Long, cwd: String = "/repo") {
        val statusField = if (status == null) "" else ""","status":"$status""""
        File(home, "sessions/$pid.json").writeText(
            """{"pid":$pid,"sessionId":"$sid","cwd":"$cwd","startedAt":1,"kind":"interactive"$statusField,"updatedAt":$statusUpdatedAt,"statusUpdatedAt":$statusUpdatedAt}"""
        )
    }

    private fun writeHook(sid: String, event: String, ts: Long, fileName: String = "$sid.json") {
        File(home, "rider-plugin/status/$fileName").writeText(
            """{"event":"$event","sessionId":"$sid","ts":$ts,"pid":999}"""
        )
    }

    private val allAlive: (Long) -> Boolean = { true }
    private val noneAlive: (Long) -> Boolean = { false }

    @Test fun readsClaudesOwnStatusFieldWithNoHooksAtAll() {
        // The plugin must be useful on a session that started before the hooks existed.
        setUpHome()
        writeSession(100, "sid-a", "busy", 1000)
        writeSession(101, "sid-b", "idle", 1000)
        writeSession(102, "sid-c", "waiting", 1000)

        val snap = store.snapshot(allAlive)
        assertEquals(ClaudeStatus.WORKING, snap["sid-a"]?.status)
        assertEquals(ClaudeStatus.IDLE, snap["sid-b"]?.status)
        assertEquals(ClaudeStatus.WAITING, snap["sid-c"]?.status)
    }

    @Test fun hookEdgeOverridesAnOlderSessionFile() {
        setUpHome()
        writeSession(100, "sid-a", "idle", 1000)
        writeHook("sid-a", "UserPromptSubmit", 2000)

        assertEquals(ClaudeStatus.WORKING, store.snapshot(allAlive)["sid-a"]?.status)
    }

    @Test fun deadProcessReportsExited() {
        setUpHome()
        writeSession(100, "sid-a", "busy", 1000)
        writeHook("sid-a", "UserPromptSubmit", 2000)

        assertEquals(ClaudeStatus.EXITED, store.snapshot(noneAlive)["sid-a"]?.status)
    }

    @Test fun hookOnlySession_isStillReported() {
        // SessionStart fires before Claude has written its first sessions/<pid>.json.
        setUpHome()
        writeHook("sid-new", "SessionStart", 500)

        val reading = store.snapshot(allAlive)["sid-new"]
        assertEquals(ClaudeStatus.IDLE, reading?.status)
        assertEquals("SessionStart", reading?.hookEvent)
        assertNull(reading?.sessionStatus)
    }

    @Test fun latestHookWins_whenSeveralFilesNameTheSameSession() {
        setUpHome()
        writeHook("sid-a", "UserPromptSubmit", 1000, fileName = "sid-a.json")
        // The termsess-keyed fallback copy is ignored: the indicator is keyed by session id
        // and honouring both would make the result depend on directory listing order.
        writeHook("sid-a", "Stop", 1, fileName = "termsess-abc.json")

        assertEquals(ClaudeStatus.WORKING, store.snapshot(allAlive)["sid-a"]?.status)
    }

    @Test fun aLiveProcessWinsOverADeadOneForTheSameSessionId() {
        // Briefly true across a `claude --resume` handover: the old pid's file lingers.
        setUpHome()
        writeSession(100, "sid-a", "idle", 5000)   // stale, will be reported dead
        writeSession(200, "sid-a", "busy", 1000)   // fresh, alive

        val snap = store.snapshot { pid -> pid == 200L }
        assertEquals(ClaudeStatus.WORKING, snap["sid-a"]?.status)
    }

    @Test fun malformedFilesAreSkippedNotFatal() {
        setUpHome()
        File(home, "sessions/bad.json").writeText("{not json")
        File(home, "sessions/300.json").writeText("{\"no\":\"sessionId\"}")
        File(home, "rider-plugin/status/junk.json").writeText("nonsense")
        writeSession(100, "sid-a", "busy", 1000)

        val snap = store.snapshot(allAlive)
        assertEquals(1, snap.size)
        assertEquals(ClaudeStatus.WORKING, snap["sid-a"]?.status)
    }

    @Test fun missingDirectoriesYieldAnEmptySnapshot() {
        val emptyHome = tmp.newFolder("no-claude")
        assertTrue(ClaudeStatusStore(emptyHome).snapshot(allAlive).isEmpty())
    }

    @Test fun readingCarriesBothInputsForDiagnostics() {
        setUpHome()
        writeSession(100, "sid-a", "waiting", 2000)
        writeHook("sid-a", "Stop", 1000)

        val reading = store.snapshot(allAlive)["sid-a"]!!
        assertEquals(ClaudeStatus.WAITING, reading.status)
        assertEquals("Stop", reading.hookEvent)
        assertEquals("waiting", reading.sessionStatus)
    }

    @Test fun pruneKeepsLiveSessionsAndRecentFilesOnly() {
        setUpHome()
        writeHook("sid-live", "Stop", 1)
        writeHook("sid-old", "Stop", 1)
        writeHook("sid-recent", "Stop", 1)

        val dayMs = 24L * 60 * 60 * 1000
        val now = 10 * dayMs
        File(home, "rider-plugin/status/sid-live.json").setLastModified(now - 5 * dayMs)
        File(home, "rider-plugin/status/sid-old.json").setLastModified(now - 5 * dayMs)
        File(home, "rider-plugin/status/sid-recent.json").setLastModified(now - 1000)

        store.prune(liveSessionIds = setOf("sid-live"), maxAgeMs = dayMs, now = now)

        assertTrue(File(home, "rider-plugin/status/sid-live.json").exists())
        assertTrue(File(home, "rider-plugin/status/sid-recent.json").exists())
        assertFalse(File(home, "rider-plugin/status/sid-old.json").exists())
    }
}
