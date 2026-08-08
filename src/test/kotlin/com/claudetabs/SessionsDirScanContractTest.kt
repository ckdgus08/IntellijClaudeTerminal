package com.claudetabs

import com.claudetabs.SessionsDirScanner.ProcessInfo
import com.claudetabs.SessionsDirScanner.ProcessLookup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Tests for the REAL `SessionsDirScanner.scan` — the function the production poll
 * loop calls at STEP 6b. Every fact pinned here also pins production behaviour;
 * there is no mirror function in this file.
 *
 * Symptom on the build before STEP 6b existed:
 *   - 10 tabs spawned via createShellWidget across 3 projects.
 *   - Save loop iterated tabs from getAllTabs(), but freshly-spawned widgets don't
 *     expose their ttyConnector in Rider 2026.1 — only 1 of 10 sessions made it into
 *     activeSessions. Next restart: 1 tab restored.
 *
 * Fix: scan ~/.claude/sessions/<pid>.json directly. Five predicates per file:
 *   1. PID parses out of the filename.
 *   2. PID is alive in the OS.
 *   3. The process is actually `claude` (PID-recycling guard).
 *   4. The session's cwd is under THIS project's basePath.
 *   5. The transcript exists on disk (filtering pre-flush sessions).
 *
 * After all five pass, the session is unioned into activeSessions if not already
 * present (deduped by canonical sessionId).
 */
class SessionsDirScanContractTest {

    private lateinit var sessionsDir: File

    @Before
    fun setUp() {
        sessionsDir = File.createTempFile("claude-sessions-dir-scan-", "").apply {
            delete(); mkdirs()
        }
    }

    @After
    fun tearDown() {
        sessionsDir.deleteRecursively()
    }

    // ══════════════════════════════════════════════════════════════
    // Helpers — write a fake sessions/<pid>.json and run the real scanner
    // ══════════════════════════════════════════════════════════════

    private fun writeSession(pid: Long, sid: String, cwd: String, startedAt: Long? = null) {
        val started = startedAt ?: System.currentTimeMillis()
        File(sessionsDir, "$pid.json").writeText(
            """{"sessionId":"$sid","cwd":"${cwd.replace("\\", "\\\\")}","startedAt":$started}"""
        )
    }

    /** Run [SessionsDirScanner.scan] with sensible defaults — every PID alive + Claude,
     *  identity canonical resolver, every transcript exists, fallback name, no bypass.
     *  Individual tests override the relevant lambda(s). */
    private fun scan(
        projectBasePath: String? = "D:\\Dev\\Proj",
        alreadyActive: Set<String> = emptySet(),
        processLookup: (Long) -> ProcessLookup = { ProcessLookup.Alive(ProcessInfo("/usr/bin/claude", "/usr/bin/claude")) },
        canonical: (Long, String, String, Long) -> String = { _, _, raw, _ -> raw },
        hasTranscript: (String, String) -> Boolean = { _, _ -> true },
        resolveName: (String) -> String = { sid -> "Tab-$sid" },
        readBypass: (String, String) -> Boolean = { _, _ -> false },
    ): SessionsDirScanner.ScanResult =
        SessionsDirScanner.scan(
            sessionsDir, projectBasePath, alreadyActive,
            processLookup, canonical, hasTranscript, resolveName, readBypass,
        )

    // ══════════════════════════════════════════════════════════════
    // 1. Filename parsing — junk files in sessions/ ignored silently
    // ══════════════════════════════════════════════════════════════

    @Test
    fun nonNumericFilenameSilentlyIgnored() {
        File(sessionsDir, "lockfile.json").writeText("{}")
        File(sessionsDir, "foo.bar.json").writeText("{}")
        writeSession(pid = 12345, sid = "sid-1", cwd = "D:\\Dev\\Proj")
        val r = scan()
        // Non-numeric filenames are not counted in any bucket — they're not "sessions"
        // at all. Only the one numeric file contributes to scanned/added.
        assertEquals(3, r.scanned)
        assertEquals(1, r.added.size)
        assertEquals("sid-1", r.added[0].sessionId)
    }

    @Test
    fun nonJsonFilesNotEnumerated() {
        File(sessionsDir, "12345.txt").writeText("not a session")
        writeSession(pid = 99, sid = "sid-1", cwd = "D:\\Dev\\Proj")
        val r = scan()
        assertEquals("only the .json file is enumerated", 1, r.scanned)
        assertEquals(1, r.added.size)
    }

    // ══════════════════════════════════════════════════════════════
    // 2. PID alive in OS
    // ══════════════════════════════════════════════════════════════

    @Test
    fun deadPidBucketedAsSkipDead() {
        writeSession(pid = 12345, sid = "sid-1", cwd = "D:\\Dev\\Proj")
        val r = scan(processLookup = { ProcessLookup.DeadOrMissing })
        assertEquals(1, r.scanned)
        assertTrue("session not added", r.added.isEmpty())
        assertEquals("dead-pid bucketed as skipDead", 1, r.skipDead)
    }

    // ══════════════════════════════════════════════════════════════
    // 3. PID-recycling guard — alive but not claude
    // ══════════════════════════════════════════════════════════════

    @Test
    fun pidRecycledToChromeBucketedAsSkipDead() {
        writeSession(pid = 12345, sid = "sid-1", cwd = "D:\\Dev\\Proj")
        // PID 12345 belonged to Claude when sessions/12345.json was written, but Claude
        // exited and the OS recycled the PID for chrome.exe. The recycling guard
        // must drop it — otherwise we'd misclassify chrome as Claude and write a
        // phantom restore entry.
        val r = scan(processLookup = {
            ProcessLookup.Alive(ProcessInfo("C:\\Program Files\\Chrome\\chrome.exe", "chrome.exe --type=renderer"))
        })
        assertEquals(0, r.added.size)
        assertEquals("recycled PID bucketed as skipDead alongside dead PIDs", 1, r.skipDead)
    }

    @Test
    fun looksLikeClaudeRecogniseesAllVariants() {
        // Each row triggers a different branch of the looksLikeClaude heuristic.
        // Pinning the recognised variants here means a future tightening of the
        // heuristic can't accidentally exclude a real Claude install.
        listOf(
            ProcessInfo("/usr/local/bin/claude", "/usr/local/bin/claude"),
            ProcessInfo("C:\\Users\\me\\AppData\\Roaming\\npm\\claude.exe", "claude.exe"),
            ProcessInfo("C:\\Users\\me\\AppData\\Roaming\\npm\\claude.cmd", "claude.cmd"),
            ProcessInfo("/usr/bin/node", "/usr/bin/node /opt/@anthropic-ai/claude-code/cli.mjs"),
            ProcessInfo("/usr/bin/node", "/usr/bin/node /usr/lib/claude-code/cli.mjs"),
            ProcessInfo("/usr/local/bin/node", "/usr/local/bin/node /opt/claude/bin/claude"),
        ).forEach { info ->
            assertTrue("$info should look like Claude", SessionsDirScanner.looksLikeClaude(info))
        }
    }

    @Test
    fun looksLikeClaudeRejectsObviousNonClaudeProcesses() {
        listOf(
            ProcessInfo("/usr/bin/bash", "/usr/bin/bash"),
            ProcessInfo("C:\\Windows\\explorer.exe", "explorer.exe"),
            ProcessInfo("/usr/bin/python3", "/usr/bin/python3 myscript.py"),
            ProcessInfo("/usr/bin/node", "/usr/bin/node server.js"),  // node, but no claude in cmd line
        ).forEach { info ->
            assertTrue("$info must NOT look like Claude", !SessionsDirScanner.looksLikeClaude(info))
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 4. Cwd filtering uses the real isCwdUnderProject helper
    // ══════════════════════════════════════════════════════════════

    @Test
    fun crossProjectSessionBucketedAsSkipOtherProject() {
        // Project A's poll must not save Project B's Claude sessions.
        writeSession(pid = 12345, sid = "sid-1", cwd = "/repos/OtherApp")
        val r = scan(projectBasePath = "/repos/MyApp")
        assertEquals(0, r.added.size)
        assertEquals(1, r.skipOtherProject)
    }

    @Test
    fun sessionInProjectSubdirectoryAccepted() {
        // User cd'd into a subdir before launching Claude.
        writeSession(pid = 12345, sid = "sid-1", cwd = "/repos/MyApp/packages/api")
        val r = scan(projectBasePath = "/repos/MyApp")
        assertEquals(1, r.added.size)
        assertEquals("/repos/MyApp/packages/api", r.added[0].cwd)
    }

    @Test
    fun siblingProjectWithCommonPrefixBucketedAsSkipOtherProject() {
        // /repos/MyApp must NOT match /repos/MyApp-mobile — the sibling-project
        // path-prefix bug requires the `/` boundary after the project base.
        writeSession(pid = 12345, sid = "sid-1", cwd = "/repos/MyApp-mobile/packages/app")
        val r = scan(projectBasePath = "/repos/MyApp")
        assertEquals(0, r.added.size)
        assertEquals(1, r.skipOtherProject)
    }

    @Test
    fun nullProjectBasePathSkipsFiltering() {
        // Defensive default: detached / default Rider projects have no basePath.
        // isCwdUnderProject returns true in that case (letting all entries through is
        // safer than dropping every session). Pin that here.
        writeSession(pid = 12345, sid = "sid-1", cwd = "/repos/MyApp")
        val r = scan(projectBasePath = null)
        assertEquals(1, r.added.size)
    }

    // ══════════════════════════════════════════════════════════════
    // 5. Dedup against tab-driven activeSessions
    // ══════════════════════════════════════════════════════════════

    @Test
    fun sessionAlreadyInActiveBucketedAsSkipAlreadyHave() {
        // STEP 6b is additive only — if STEP 1-3 already added this sid via the
        // tab-driven loop, we must not re-add it.
        writeSession(pid = 12345, sid = "sid-1", cwd = "D:\\Dev\\Proj")
        val r = scan(alreadyActive = setOf("sid-1"))
        assertEquals(0, r.added.size)
        assertEquals(1, r.skipAlreadyHave)
    }

    @Test
    fun multipleSessionFilesWithSameCanonicalDedupAcrossPass() {
        // Two PIDs writing transcripts that canonicalise to the same id (one is
        // raw post-resume, the other its canonical pre-resume). Within the SCAN
        // pass, the second occurrence must dedup.
        writeSession(pid = 100, sid = "raw-1", cwd = "D:\\Dev\\Proj")
        writeSession(pid = 101, sid = "raw-2", cwd = "D:\\Dev\\Proj")
        val r = scan(canonical = { _, _, _, _ -> "shared-canonical" })
        // First file gets added; second is bucketed as ALREADY_HAVE because the
        // scanner tracks added sids internally.
        assertEquals(1, r.added.size)
        assertEquals(1, r.skipAlreadyHave)
    }

    // ══════════════════════════════════════════════════════════════
    // 6. Transcript existence
    // ══════════════════════════════════════════════════════════════

    @Test
    fun noTranscriptYetBucketedAsSkipNoTranscript() {
        // Claude wrote sessions/<pid>.json but hasn't flushed the transcript yet.
        // Saving this would write a restore entry whose --resume fails.
        writeSession(pid = 12345, sid = "sid-1", cwd = "D:\\Dev\\Proj")
        val r = scan(hasTranscript = { _, _ -> false })
        assertEquals(0, r.added.size)
        assertEquals(1, r.skipNoTranscript)
    }

    // ══════════════════════════════════════════════════════════════
    // 7. Malformed session files — silent skip (no bucket)
    // ══════════════════════════════════════════════════════════════

    @Test
    fun fileMissingSessionIdSilentlySkipped() {
        // Malformed file (no sessionId field). Scanner skips without bucketing —
        // matches the original inline code's behaviour for unreadable / partial files.
        File(sessionsDir, "12345.json").writeText("""{"cwd":"D:\\Dev\\Proj"}""")
        val r = scan()
        assertEquals("file enumerated", 1, r.scanned)
        assertEquals("but not added", 0, r.added.size)
        assertEquals("no bucket increment", 0, r.skipDead + r.skipOtherProject + r.skipAlreadyHave + r.skipNoTranscript)
    }

    @Test
    fun fileMissingCwdSilentlySkipped() {
        File(sessionsDir, "12345.json").writeText("""{"sessionId":"sid-1"}""")
        val r = scan()
        assertEquals(1, r.scanned)
        assertEquals(0, r.added.size)
    }

    // ══════════════════════════════════════════════════════════════
    // 8. Name resolution lambda — production wires widget → lastApplied → previousActive → "Claude"
    // ══════════════════════════════════════════════════════════════

    @Test
    fun nameComesFromResolveLambda() {
        // The scanner does not implement the widget→lastApplied→previousActive chain
        // itself — production passes a lambda that does. Pin that scanner uses
        // whatever name the lambda returns, verbatim. Use the helper's default
        // projectBasePath ("D:\\Dev\\Proj") so the session passes the cwd filter.
        writeSession(pid = 12345, sid = "sid-1", cwd = "D:\\Dev\\Proj")
        val r = scan(resolveName = { sid -> "Resolved $sid" })
        assertEquals("Resolved sid-1", r.added[0].tabName)
    }

    // ══════════════════════════════════════════════════════════════
    // 9. Defensive try/catch — a bad file doesn't abort the scan
    // ══════════════════════════════════════════════════════════════

    @Test
    fun emptyDirReturnsZeroCounters() {
        val r = scan()
        assertEquals(0, r.scanned)
        assertEquals(0, r.added.size)
        assertEquals(0, r.skipDead)
        assertEquals(0, r.skipOtherProject)
        assertEquals(0, r.skipAlreadyHave)
        assertEquals(0, r.skipNoTranscript)
    }

    @Test
    fun missingSessionsDirReturnsZeroCounters() {
        // sessions/ doesn't exist yet — pre-init, or wiped state. Scan must not throw.
        val nonExistent = File(sessionsDir, "does-not-exist")
        val r = SessionsDirScanner.scan(
            sessionsDir = nonExistent,
            projectBasePath = "/repos/MyApp",
            alreadyActiveIds = emptySet(),
            processLookup = { ProcessLookup.DeadOrMissing },
            canonicalSessionId = { _, _, raw, _ -> raw },
            hasTranscript = { _, _ -> true },
            resolveName = { "" },
            readBypass = { _, _ -> false },
        )
        assertEquals(0, r.scanned)
    }

    // ══════════════════════════════════════════════════════════════
    // 10. End-to-end status line — what the user reads from idea.log
    // ══════════════════════════════════════════════════════════════

    @Test
    fun statusLineMatchesIdeaLogFormat() {
        // Replay of a status line shape the scan produces during a real poll:
        //   STEP 6b: SESSIONS_DIR scan — scanned=8 added=5 skipDead=0 skipOtherProject=2 skipAlreadyHave=1 skipNoTranscript=0
        val projABase = "/repos/MyApp"
        val projBBase = "/repos/OtherApp"
        // 5 project-A sessions that should be added
        listOf(100L to "sid-1", 101L to "sid-2", 102L to "sid-3", 103L to "sid-4", 104L to "sid-5").forEach { (pid, sid) ->
            writeSession(pid, sid, projABase)
        }
        // 1 project-A session already present in activeSessions
        writeSession(105L, "sid-6", projABase)
        // 2 sessions from another project
        writeSession(200L, "sid-7", projBBase)
        writeSession(201L, "sid-8", projBBase)

        val r = scan(
            projectBasePath = projABase,
            alreadyActive = setOf("sid-6"),
        )
        assertEquals(8, r.scanned)
        assertEquals(5, r.added.size)
        assertEquals(0, r.skipDead)
        assertEquals(2, r.skipOtherProject)
        assertEquals(1, r.skipAlreadyHave)
        assertEquals(0, r.skipNoTranscript)
        assertEquals(0, r.skipNotInteractive)
        // Verify the status line format matches what production logs:
        assertEquals(
            "scanned=8 added=5 skipDead=0 skipOtherProject=2 skipAlreadyHave=1 skipNoTranscript=0 skipNotInteractive=0",
            r.statusLine(),
        )
    }
}
