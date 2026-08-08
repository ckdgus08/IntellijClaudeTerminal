package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Only sessions someone is typing into belong in a terminal tab.
 *
 * The shapes here are taken from a real Claude Code 2.1.226 install where an interactive
 * session had spawned a background job: the job's process tree was
 * `bg claude -> supervisor -> claude -> interactive claude -> zsh -> idea`, so it descended
 * from the IDE's JVM, was alive, looked like Claude, and shared the project cwd. Every
 * predicate the scanner and the ancestry walk had at the time said "this is a tab".
 */
class SessionKindFilterTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun interactiveAndLegacySessionsAreTabs() {
        assertTrue(ClaudeTabsHelpers.isTerminalTabSessionKind("interactive"))
        // Pre-2.1 CLIs wrote no `kind`, and everything they wrote was interactive.
        assertTrue(ClaudeTabsHelpers.isTerminalTabSessionKind(null))
        assertTrue(ClaudeTabsHelpers.isTerminalTabSessionKind(""))
    }

    @Test fun everyOtherKindIsRejected() {
        // The full set Claude Code 2.1.226 can write.
        for (kind in listOf("bg", "daemon", "daemon-worker", "desktop", "rc", "bridge", "remote")) {
            assertFalse("'$kind' must not be treated as a terminal tab", ClaudeTabsHelpers.isTerminalTabSessionKind(kind))
        }
    }

    @Test fun unknownFutureKindsAreRejected() {
        // Allowlist, not denylist: a kind added by a future CLI is not a tab until we say so.
        assertFalse(ClaudeTabsHelpers.isTerminalTabSessionKind("something-new"))
    }

    // ── Scanner integration ───────────────────────────────────────

    private fun sessionFile(dir: File, pid: Long, sid: String, kind: String?, cwd: String) {
        val kindField = if (kind == null) "" else ""","kind":"$kind""""
        File(dir, "$pid.json").writeText(
            """{"pid":$pid,"sessionId":"$sid","cwd":"$cwd","startedAt":1$kindField,"status":"busy"}"""
        )
    }

    private fun scan(dir: File, basePath: String) = SessionsDirScanner.scan(
        sessionsDir = dir,
        projectBasePath = basePath,
        alreadyActiveIds = emptySet(),
        processLookup = { SessionsDirScanner.ProcessLookup.Alive(SessionsDirScanner.ProcessInfo("/usr/bin/claude", "claude")) },
        canonicalSessionId = { _, _, raw, _ -> raw },
        hasTranscript = { _, _ -> true },
        resolveName = { "tab" },
        readBypass = { _, _ -> false },
    )

    @Test fun scannerKeepsInteractiveAndDropsBackgroundJobs() {
        val dir = tmp.newFolder("sessions")
        // Exactly the situation observed on the dev machine: the IDE project is the
        // workspace root, so every session under it matches on cwd.
        sessionFile(dir, 3001, "sid-interactive-1", "interactive", "/Users/example/projects")
        sessionFile(dir, 3002, "sid-interactive-2", "interactive", "/Users/example/projects/RiderClaudeTabs")
        sessionFile(dir, 3003, "sid-background", "bg", "/Users/example/projects")

        val result = scan(dir, "/Users/example/projects")

        assertEquals(listOf("sid-interactive-1", "sid-interactive-2"), result.added.map { it.sessionId }.sorted())
        assertEquals(1, result.skipNotInteractive)
    }

    @Test fun scannerDropsDesktopAndRemoteControlSessions() {
        val dir = tmp.newFolder("sessions")
        sessionFile(dir, 100, "sid-desktop", "desktop", "/Users/example/projects")
        sessionFile(dir, 101, "sid-rc", "rc", "/Users/example/projects")
        sessionFile(dir, 102, "sid-bridge", "bridge", "/Users/example/projects")

        val result = scan(dir, "/Users/example/projects")

        assertTrue("nothing should be restorable as a tab", result.added.isEmpty())
        assertEquals(3, result.skipNotInteractive)
    }

    @Test fun kindIsCheckedBeforeCwd_soTheCounterIsUnambiguous() {
        // A bg job in a different project shouldn't be reported as "other project" — the
        // reason it's excluded is that it's not a tab at all, and the log line is how this
        // gets diagnosed in the field.
        val dir = tmp.newFolder("sessions")
        sessionFile(dir, 200, "sid-bg-elsewhere", "bg", "/somewhere/else")

        val result = scan(dir, "/Users/example/projects")

        assertEquals(1, result.skipNotInteractive)
        assertEquals(0, result.skipOtherProject)
    }

    @Test fun statusLineReportsTheNewCounter() {
        val dir = tmp.newFolder("sessions")
        sessionFile(dir, 3003, "sid-background", "bg", "/Users/example/projects")
        assertTrue(scan(dir, "/Users/example/projects").statusLine().contains("skipNotInteractive=1"))
    }
}

/**
 * Recognising the Claude CLI by its executable path.
 *
 * The installed CLI is a versioned binary whose filename is the version, not "claude".
 * Sessions the daemon launches run it directly instead of through the `~/.local/bin/claude`
 * shim, so every name-based check misses them — and a live background agent reading as
 * "dead" is what made the restore path try `claude --resume` on a session that was still
 * running, which Claude refuses outright.
 */
class ClaudeProcessRecognitionTest {

    private fun info(command: String, commandLine: String = command) =
        SessionsDirScanner.ProcessInfo(command, commandLine)

    @Test fun recognisesTheVersionedBinaryTheDaemonLaunches() {
        // Observed verbatim for a live background agent (pid 3003).
        assertTrue(
            SessionsDirScanner.looksLikeClaude(
                info(
                    "/Users/example/.local/share/claude/versions/2.1.226",
                    "/Users/example/.local/share/claude/versions/2.1.226 --session-id 90000001-0000-4000-8000-000000000001 --fork-session",
                )
            )
        )
    }

    @Test fun recognisesTheBundledAppWrapper() {
        assertTrue(
            SessionsDirScanner.looksLikeClaude(
                info("/Users/example/.local/share/claude/ClaudeCode.app/Contents/MacOS/claude")
            )
        )
    }

    @Test fun stillRecognisesTheOrdinaryShim() {
        assertTrue(SessionsDirScanner.looksLikeClaude(info("/Users/example/.local/bin/claude", "claude --dangerously-skip-permissions")))
        assertTrue(SessionsDirScanner.looksLikeClaude(info("claude")))
        assertTrue(SessionsDirScanner.looksLikeClaude(info("C:\\bin\\claude.exe")))
    }

    @Test fun doesNotClaimUnrelatedProcesses() {
        assertFalse(SessionsDirScanner.looksLikeClaude(info("/bin/zsh", "/bin/zsh --login -i")))
        assertFalse(SessionsDirScanner.looksLikeClaude(info("/usr/bin/git", "git status")))
        // A path merely mentioning claude isn't the CLI — the install-root match is anchored
        // on the real layout, not on the word appearing anywhere.
        assertFalse(SessionsDirScanner.looksLikeClaude(info("/Users/example/projects/claude-notes/bin/tool", "tool")))
    }
}
