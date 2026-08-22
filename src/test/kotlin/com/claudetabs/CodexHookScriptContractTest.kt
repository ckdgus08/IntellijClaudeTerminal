package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class CodexHookScriptContractTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun runHook(event: String, payload: String): Process {
        assumeFalse(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val script = File("src/main/resources/codex-integration/status-hook.sh").absoluteFile
        val process = ProcessBuilder("bash", script.path, event)
            .apply {
                environment()["HOME"] = tmp.root.absolutePath
                environment()["TERM_SESSION_ID"] = "term-qa"
            }
            .start()
        process.outputStream.bufferedWriter().use { it.write(payload) }
        assertTrue("hook timed out", process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(process.errorStream.bufferedReader().readText(), 0, process.exitValue())
        return process
    }

    @Test fun posixHookWritesAtomicValidNestedJsonAndPromptBuffer() {
        val process = runHook(
            "UserPromptSubmit",
            """{"session_id":"0198-qa","cwd":"/repo with space","prompt":"fix \"quoted\" test","hook_event_name":"UserPromptSubmit"}""",
        )
        assertEquals("", process.inputStream.bufferedReader().readText())

        val dir = File(tmp.root, ".codex/rider-agent-tabs/status")
        val session = File(dir, "0198-qa.json")
        val bridge = File(dir, "termsess-term-qa.json")
        val prompt = File(dir, "prompt-0198-qa.json")
        assertTrue(session.exists())
        assertTrue(bridge.exists())
        assertTrue(prompt.exists())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })

        @Suppress("UNCHECKED_CAST")
        val root = MiniJson.parse(session.readText()) as Map<String, Any?>
        val payload = root["payload"] as Map<String, Any?>
        assertEquals("fix \"quoted\" test", payload["prompt"])
        assertEquals("term-qa", root["termSessionId"])
    }

    @Test fun unsafeSessionIdCannotBecomeAFileName() {
        runHook("Stop", """{"session_id":"../../escape","cwd":"/repo"}""")
        val dir = File(tmp.root, ".codex/rider-agent-tabs/status")
        assertFalse(dir.exists())
        assertFalse(File(tmp.root, "escape.json").exists())
    }
}
