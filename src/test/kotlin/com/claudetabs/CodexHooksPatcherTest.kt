package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CodexHooksPatcherTest {
    @Suppress("UNCHECKED_CAST")
    private fun commands(text: String, event: String): List<Map<String, Any?>> {
        val root = MiniJson.parse(text) as Map<String, Any?>
        val hooks = root["hooks"] as Map<String, Any?>
        val groups = hooks[event] as List<Map<String, Any?>>
        return groups.flatMap { it["hooks"] as List<Map<String, Any?>> }
    }

    @Test fun installsEverySupportedEventForPosixAndWindows() {
        val out = CodexHooksPatcher.patch(null)!!
        for (event in CodexHooksPatcher.STATUS_EVENTS) {
            val entry = commands(out, event).single()
            assertTrue((entry["command"] as String).endsWith("status-hook.sh $event"))
            assertTrue((entry["commandWindows"] as String).endsWith("status-hook.ps1\" $event"))
        }
    }

    @Test fun preservesUserHooksAndIsIdempotent() {
        val before = """{"theme":"dark","hooks":{"Stop":[{"hooks":[{"type":"command","command":"say done"}]}]}}"""
        val once = CodexHooksPatcher.patch(before)!!
        assertEquals("dark", (MiniJson.parse(once) as Map<*, *>)["theme"])
        assertEquals(2, commands(once, "Stop").size)
        assertNull(CodexHooksPatcher.patch(once))
    }

    @Test fun malformedOrNonObjectJsonIsUntouched() {
        assertNull(CodexHooksPatcher.patch("{broken"))
        assertNull(CodexHooksPatcher.patch("[]"))
        assertNull(CodexHooksPatcher.patch("""{"hooks":"do not clobber"}"""))
        assertNull(CodexHooksPatcher.patch("""{"hooks":{"Stop":"do not clobber"}}"""))
    }

    @Test fun unpatchRemovesOnlyPluginHooks() {
        val installed = CodexHooksPatcher.patch(
            """{"hooks":{"Stop":[{"hooks":[{"type":"command","command":"say done"}]}]}}"""
        )!!
        val cleaned = CodexHooksPatcher.unpatch(installed)!!
        assertFalse(cleaned.contains("rider-agent-tabs/status-hook"))
        assertEquals("say done", commands(cleaned, "Stop").single()["command"])
    }
}

class CodexStatusStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(name: String, body: String): CodexStatusStore {
        val store = CodexStatusStore(tmp.root)
        store.statusDir.mkdirs()
        File(store.statusDir, name).writeText(body)
        return store
    }

    @Test fun readsNestedPayloadWithoutLosingPromptEscapes() {
        val store = write(
            "0198.json",
            """{"event":"UserPromptSubmit","termSessionId":"term-1","ts":42,"pid":77,"payload":{"session_id":"0198","cwd":"/repo","prompt":"fix \"quoted\" test","permission_mode":"on-request"}}""",
        )
        val metadata = store.sessions().values.single()
        assertEquals("codex--0198", metadata.sessionId)
        assertEquals("fix \"quoted\" test", metadata.prompt)
        assertEquals(ClaudeStatus.WORKING, store.snapshot { true }.values.single().status)
    }

    @Test fun termBridgeAndPermissionStateAreResolved() {
        val store = write(
            "termsess-term-1.json",
            """{"event":"PermissionRequest","termSessionId":"term-1","ts":43,"pid":77,"payload":{"session_id":"0198","cwd":"/repo"}}""",
        )
        // A session-keyed record drives status; the termsess record drives tab attachment.
        File(store.statusDir, "0198.json").writeText(File(store.statusDir, "termsess-term-1.json").readText())
        assertEquals("codex--0198", store.termSessionMap { true }["term-1"])
        assertEquals(ClaudeStatus.WAITING, store.snapshot { true }["codex--0198"]?.status)
    }

    @Test fun firstPromptSurvivesLaterStopRecord() {
        val store = write(
            "prompt-0198.json",
            """{"event":"UserPromptSubmit","ts":10,"pid":77,"payload":{"session_id":"0198","cwd":"/repo","prompt":"implement codex tabs"}}""",
        )
        File(store.statusDir, "0198.json").writeText(
            """{"event":"Stop","ts":20,"pid":77,"payload":{"session_id":"0198","cwd":"/repo"}}"""
        )
        val record = store.sessions()["codex--0198"]!!
        assertEquals("implement codex tabs", record.prompt)
        assertEquals(ClaudeStatus.FINISHED, store.snapshot { true }["codex--0198"]?.status)
        store.discardPrompt("codex--0198")
        assertFalse(File(store.statusDir, "prompt-0198.json").exists())
    }

    @Test fun malformedAndUnsafeRecordsAreIgnored() {
        val store = write("bad.json", "{broken")
        File(store.statusDir, "unsafe.json").writeText(
            """{"event":"Stop","ts":1,"payload":{"session_id":"x; rm","cwd":"/repo"}}"""
        )
        assertTrue(store.sessions().isEmpty())
    }

    @Test fun deadCodexProcessIsExitedAndItsTermBridgeIsDropped() {
        val store = write(
            "0198.json",
            """{"event":"PreToolUse","termSessionId":"term-1","ts":43,"pid":77,"payload":{"session_id":"0198","cwd":"/repo"}}""",
        )
        File(store.statusDir, "termsess-term-1.json").writeText(File(store.statusDir, "0198.json").readText())
        assertEquals(ClaudeStatus.EXITED, store.snapshot { false }["codex--0198"]?.status)
        assertTrue(store.termSessionMap { false }.isEmpty())
    }
}
