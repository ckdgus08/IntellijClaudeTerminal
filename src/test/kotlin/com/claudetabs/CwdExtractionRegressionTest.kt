package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for a real-world failure where pruneStaleRestoreEntries dropped valid
 * restore entries because extractJsonString returned the cwd WITHOUT backslashes
 * (e.g. "D:DevProject" instead of "D:\Dev\Project"). The file content was a hand-written
 * recovery restore file with the same JSON encoding the plugin itself produces via
 * [ClaudeTabsStorage.serialiseSessions].
 *
 * Each backslash in the on-disk JSON is encoded as `\\` (two bytes). After readText() the
 * in-memory String contains the literal char sequence `\\` (two backslash chars). The
 * regex+replace must decode that back to a single backslash so the cwd matches the
 * project base path.
 */
class CwdExtractionRegressionTest {

    @Test fun extractJsonString_windowsPathWithDoubleBackslashEscape_decodesCorrectly() {
        val json = "{\"sessionId\":\"a\",\"cwd\":\"D:\\\\path\\\\to\\\\project\",\"tabName\":\"X\",\"bypassPermissions\":false}"
        val cwd = ClaudeTabsHelpers.extractJsonString(json, "cwd")
        assertEquals("D:\\path\\to\\project", cwd)
    }

    @Test fun extractJsonString_multipleEntriesInArray_eachCwdParsesIndependently() {
        val json = "[\n" +
            "  {\"sessionId\":\"a\",\"cwd\":\"D:\\\\path\\\\to\\\\project\",\"tabName\":\"A\",\"bypassPermissions\":false},\n" +
            "  {\"sessionId\":\"b\",\"cwd\":\"D:\\\\path\\\\to\\\\project\",\"tabName\":\"B\",\"bypassPermissions\":false}\n" +
            "]"
        val entries = Regex("\\{[^}]+\\}").findAll(json).map { it.value }.toList()
        assertEquals(2, entries.size)
        for (e in entries) {
            assertEquals("D:\\path\\to\\project", ClaudeTabsHelpers.extractJsonString(e, "cwd"))
        }
    }
}
