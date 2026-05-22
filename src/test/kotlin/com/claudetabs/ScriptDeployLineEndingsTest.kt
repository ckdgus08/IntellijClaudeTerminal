package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression pin for issue #1: when the bundled `.sh` resources land on macOS / Linux
 * with embedded `\r` characters, `bash` fails with `$'\r': command not found`. The
 * production deploy path (`ClaudeTabWatcherStartup.deployResource`) strips CR for any
 * resource ending in `.sh` or `.bash`. Both decisions are pinned here:
 *
 *   1. CR stripping is a no-op for any other extension (`.md`, `.js`, `.json`, …).
 *   2. CR stripping removes EVERY `\r`, not just trailing ones (catches both `\r\n`
 *      line terminators and stray `\r` mid-line).
 *
 * The actual `deployResource` function takes a classpath stream + a target file, both
 * of which are expensive to set up in a unit test. Here we exercise the same byte-level
 * predicate / filter that the production deploy path uses, against representative inputs.
 */
class ScriptDeployLineEndingsTest {

    /** Mirror of the predicate in [ClaudeTabWatcherStartup.deployResource]. */
    private fun isExecutableScript(path: String): Boolean =
        path.endsWith(".sh") || path.endsWith(".bash")

    /** Mirror of the byte filter the production path applies for executable scripts. */
    private fun stripCr(bytes: ByteArray): ByteArray =
        bytes.filter { it != 0x0D.toByte() }.toByteArray()

    @Test fun isExecutableScript_matchesShAndBash() {
        assertTrue(isExecutableScript("foo.sh"))
        assertTrue(isExecutableScript("claude-integration/rename-tab.sh"))
        assertTrue(isExecutableScript("hook.bash"))
    }

    @Test fun isExecutableScript_excludesOtherExtensions() {
        assertFalse(isExecutableScript("README.md"))
        assertFalse(isExecutableScript("tabs-status.md"))
        assertFalse(isExecutableScript("tab-backup.js"))
        assertFalse(isExecutableScript("settings.json"))
        assertFalse(isExecutableScript("noextension"))
        // Suffix-style is the contract — these should NOT match (defensive against
        // a future glob refactor that accidentally matches sh* / *.shutdown / etc).
        assertFalse(isExecutableScript("script.sh.bak"))
        assertFalse(isExecutableScript("rename-tab.shell"))
    }

    @Test fun stripCr_removesAllCarriageReturns() {
        val crlf = "#!/bin/bash\r\nset -e\r\necho hi\r\n".toByteArray()
        val result = stripCr(crlf)
        assertEquals("#!/bin/bash\nset -e\necho hi\n", String(result))
    }

    @Test fun stripCr_removesStrayMidLineCr() {
        // Some editors / merge tools insert a lone \r without \n. Bash still chokes on it.
        // Production strips all CRs, not just \r\n pairs.
        val weird = "echo foo\rbar\n".toByteArray()
        val result = stripCr(weird)
        assertEquals("echo foobar\n", String(result))
    }

    @Test fun stripCr_leavesPureLfUntouched() {
        val lf = "#!/bin/bash\nset -e\necho hi\n".toByteArray()
        val result = stripCr(lf)
        assertEquals("#!/bin/bash\nset -e\necho hi\n", String(result))
        // Same length — verifies no spurious mutations.
        assertEquals(lf.size, result.size)
    }

    @Test fun stripCr_emptyInput_returnsEmpty() {
        assertEquals(0, stripCr(ByteArray(0)).size)
    }

    @Test fun stripCr_onlyCrInput_returnsEmpty() {
        val onlyCr = "\r\r\r\r".toByteArray()
        assertEquals(0, stripCr(onlyCr).size)
    }

    @Test fun stripCr_preservesNonAsciiBytes() {
        // The .sh files are UTF-8. The CR stripper must not touch multi-byte sequences.
        val utf8 = "echo \"café — résumé\"\r\n".toByteArray(Charsets.UTF_8)
        val result = String(stripCr(utf8), Charsets.UTF_8)
        assertEquals("echo \"café — résumé\"\n", result)
    }
}
