package com.claudetabs

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** End-to-end contract for the count-only Claude background-task record. */
class ClaudeBackgroundTaskHookContractTest {
    @get:Rule val tmp = TemporaryFolder()

    private val statusDir get() = File(tmp.root, ".claude/intellij-claude-terminal/status")

    private fun runHook(event: String, payload: String) {
        assumeFalse(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val script = File("src/main/resources/claude-integration/status-hook.sh").absoluteFile
        val process = ProcessBuilder("bash", script.path, event)
            .apply {
                environment()["HOME"] = tmp.root.absolutePath
                environment()["TERM_SESSION_ID"] = "term-fixture"
            }
            .start()
        process.outputStream.bufferedWriter().use { it.write(payload) }
        assertTrue("hook timed out", process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(process.errorStream.bufferedReader().readText(), 0, process.exitValue())
    }

    private fun backgroundCount(sid: String): Int {
        val text = File(statusDir, "background-$sid.json").readText()
        @Suppress("UNCHECKED_CAST")
        val root = MiniJson.parse(text) as Map<String, Any?>
        return (root["count"] as MiniJson.Num).raw.toInt()
    }

    @Test fun stopStoresOnlyTheCountAndSubagentStopDoesNotReplaceTheMainEdge() {
        val sid = "session-fixture"
        runHook(
            "Stop",
            """{
              "session_id":"$sid",
              "last_assistant_message":"text containing background_tasks and { braces }",
              "background_tasks":[
                {"id":"private-task-a","type":"shell","status":"running","command":"review { src }"},
                {"id":"private-task-b","type":"subagent","status":"running","description":"private description"}
              ]
            }""".trimIndent(),
        )

        assertEquals(2, backgroundCount(sid))
        val countRecord = File(statusDir, "background-$sid.json").readText()
        assertFalse(countRecord.contains("private-task"))
        assertFalse(countRecord.contains("private description"))
        assertFalse(countRecord.contains("review { src }"))
        assertTrue(File(statusDir, "$sid.json").readText().contains("\"event\":\"Stop\""))

        runHook(
            "SubagentStop",
            """{"session_id":"$sid","background_tasks":[{"id":"remaining","type":"shell","status":"running"}]}""",
        )
        assertEquals(1, backgroundCount(sid))
        assertTrue("SubagentStop must not replace Stop", File(statusDir, "$sid.json").readText().contains("\"event\":\"Stop\""))

        runHook("SubagentStop", """{"session_id":"$sid","background_tasks":[]}""")
        assertEquals(0, backgroundCount(sid))

        // Older CLIs omit the array on SubagentStop; fall back to one completed task.
        runHook(
            "Stop",
            """{"session_id":"$sid","background_tasks":[{"id":"a"},{"id":"b"}]}""",
        )
        runHook("SubagentStop", """{"session_id":"$sid","agent_id":"completed"}""")
        assertEquals(1, backgroundCount(sid))
    }

    @Test fun aNewPromptDoesNotEraseIndependentBackgroundWork() {
        val sid = "session-new-turn"
        runHook(
            "Stop",
            """{"session_id":"$sid","background_tasks":[{"id":"old","type":"shell","status":"running"}]}""",
        )
        assertEquals(1, backgroundCount(sid))

        runHook("UserPromptSubmit", """{"session_id":"$sid","prompt":"new turn"}""")
        assertEquals(1, backgroundCount(sid))
    }
}
