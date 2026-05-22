package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Layer 3a — extract the Node blocks from each slash-command markdown file and run them
 * against a seeded temp HOME. Verifies each command's file-side-effects and stdout.
 *
 * These tests skip gracefully if Node isn't on PATH — CI without Node will still be green.
 */
class SlashCommandScriptTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var claudeHome: File
    private lateinit var storage: ClaudeTabsStorage

    @Before fun setup() {
        claudeHome = File(tmp.root, ".claude")
        claudeHome.mkdirs()
        storage = ClaudeTabsStorage(claudeHome)
        storage.stateDir.mkdirs()
    }

    // ── test helpers ──────────────────────────────────────────────

    private fun hasNode(): Boolean = try {
        ProcessBuilder("node", "--version").redirectErrorStream(true).start().waitFor() == 0
    } catch (_: Exception) { false }

    private data class NodeResult(val exitCode: Int, val stdout: String)

    /**
     * Run [nodeScript] via `node -` (reads script from stdin). Env overrides make Node's
     * `os.homedir()` resolve to our temp root so the scripts use the seeded files.
     */
    private fun runNode(nodeScript: String, extraEnv: Map<String, String> = emptyMap()): NodeResult {
        val pb = ProcessBuilder("node", "-")
            .redirectErrorStream(true)
        pb.environment().apply {
            put("HOME", tmp.root.absolutePath)
            put("USERPROFILE", tmp.root.absolutePath)
            extraEnv.forEach { (k, v) -> put(k, v) }
        }
        val process = pb.start()
        process.outputStream.writer().use { it.write(nodeScript) }
        val out = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        return NodeResult(exit, out)
    }

    /**
     * Run a deployed JS file directly so the test exercises the exact bytes that ship in
     * the plugin (rather than an inline copy). [workingDir] controls process.cwd() so we
     * can verify cwd-walk logic in current-project.js.
     */
    private fun runNodeFile(
        scriptPath: File,
        args: List<String> = emptyList(),
        workingDir: File = tmp.root,
        extraEnv: Map<String, String> = emptyMap(),
    ): NodeResult {
        val cmd = mutableListOf("node", scriptPath.absolutePath)
        cmd.addAll(args)
        val pb = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .directory(workingDir)
        pb.environment().apply {
            put("HOME", tmp.root.absolutePath)
            put("USERPROFILE", tmp.root.absolutePath)
            extraEnv.forEach { (k, v) -> put(k, v) }
        }
        val process = pb.start()
        val out = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        return NodeResult(exit, out)
    }

    /** Resolve a deployed JS resource by its filename. Tests run with Gradle's default
     *  working dir = project root, so this relative path works. */
    private fun deployedJs(name: String): File =
        File("src/main/resources/claude-integration/$name").also {
            assertTrue("missing deployed resource: ${it.absolutePath}", it.exists())
        }

    private fun seedRestoreFile(projectHash: String, sessions: List<ClaudeTabsStorage.SavedSession>) {
        val f = storage.restoreFile(projectHash)
        f.parentFile.mkdirs()
        f.writeText(storage.serialiseSessions(sessions))
    }

    private fun sess(id: String, name: String, bypass: Boolean = false, cwd: String = "D:\\Dev\\Project"): ClaudeTabsStorage.SavedSession =
        ClaudeTabsStorage.SavedSession(id, cwd, name, bypass)

    // ══════════════════════════════════════════════════════════════
    // /tabs-backup
    // ══════════════════════════════════════════════════════════════

    private val tabsBackupScript = """
        const fs = require('fs'), path = require('path'), os = require('os');
        const home = path.join(os.homedir(), '.claude', 'rider-plugin');
        const historyPath = path.join(home, 'history.json');

        let history = [];
        if (fs.existsSync(historyPath)) {
          try { history = JSON.parse(fs.readFileSync(historyPath, 'utf8')); }
          catch { history = []; }
        }

        let active = [];
        if (fs.existsSync(home)) {
          for (const f of fs.readdirSync(home).filter(n => n.startsWith('restore-') && n.endsWith('.json'))) {
            try { active.push(...JSON.parse(fs.readFileSync(path.join(home, f), 'utf8'))); }
            catch {}
          }
        }

        if (!active.length) {
          console.log('No active sessions to back up.');
          process.exit(0);
        }

        const now = Date.now();
        let added = 0, updated = 0;
        for (const s of active) {
          const sid = s.sessionId;
          if (!sid) continue;
          const wasPresent = history.some(e => e.sessionId === sid);
          history = history.filter(e => e.sessionId !== sid);
          history.push({
            sessionId: sid, cwd: s.cwd || '', tabName: s.tabName || '',
            bypassPermissions: !!s.bypassPermissions, closedAt: now, backedUp: true,
          });
          if (wasPresent) updated++; else added++;
        }
        const cutoff = now - 90 * 24 * 60 * 60 * 1000;
        history = history.filter(e => (e.closedAt || 0) > cutoff);
        fs.mkdirSync(home, { recursive: true });
        fs.writeFileSync(historyPath, JSON.stringify(history, null, 2));
        console.log(`Backup complete: ${'$'}{added} new, ${'$'}{updated} updated, ${'$'}{history.length} total in history.`);
    """.trimIndent()

    @Test fun tabsBackup_writesActiveSessionsToHistory() {
        if (!hasNode()) { println("Node missing — skipping"); return }

        seedRestoreFile("proj-a", listOf(
            sess("sess-1", "Tab One"),
            sess("sess-2", "Tab Two", bypass = true),
        ))

        val r = runNode(tabsBackupScript)
        assertEquals(0, r.exitCode)
        assertTrue("output should confirm backup", r.stdout.contains("Backup complete"))
        assertTrue(r.stdout.contains("2 new"))

        assertTrue(storage.historyFile.exists())
        val history = storage.historyFile.readText()
        assertTrue(history.contains("sess-1"))
        assertTrue(history.contains("sess-2"))
        assertTrue(history.contains("\"backedUp\": true"))
    }

    @Test fun tabsBackup_noActiveSessions_printsFriendlyMessage() {
        if (!hasNode()) { println("Node missing — skipping"); return }
        val r = runNode(tabsBackupScript)
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.contains("No active sessions"))
        assertFalse(storage.historyFile.exists())
    }

    @Test fun tabsBackup_existingSessionIsUpdatedNotDuplicated() {
        if (!hasNode()) { println("Node missing — skipping"); return }

        // Pre-seed history with sess-1
        storage.appendToHistory(
            ClaudeTabsStorage.SavedSession("sess-1", "D:/old", "Old Name", false),
            now = 100L, maxAgeMs = 90L * 24 * 60 * 60 * 1000,
        )
        seedRestoreFile("proj-a", listOf(sess("sess-1", "New Name")))

        val r = runNode(tabsBackupScript)
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.contains("1 updated"))

        // Only one entry for sess-1 should remain, with the new name
        val history = JsonArrayHelper.readEntries(storage.historyFile)
        val sess1 = history.filter { it.contains("\"sessionId\": \"sess-1\"") }
        assertEquals(1, sess1.size)
        assertTrue(sess1[0].contains("New Name"))
    }

    // ══════════════════════════════════════════════════════════════
    // /tabs-status
    // ══════════════════════════════════════════════════════════════

    private val tabsStatusScript = """
        const fs = require('fs'), path = require('path'), os = require('os');
        const home = path.join(os.homedir(), '.claude', 'rider-plugin');
        const restoreFiles = fs.existsSync(home)
          ? fs.readdirSync(home).filter(f => f.startsWith('restore-') && f.endsWith('.json')).sort()
          : [];
        if (!restoreFiles.length) { console.log('No active Claude sessions tracked.'); process.exit(0); }
        let total = 0;
        for (const rf of restoreFiles) {
          let sessions; try { sessions = JSON.parse(fs.readFileSync(path.join(home, rf), 'utf8')); } catch { continue; }
          if (!sessions.length) continue;
          const BS = String.fromCharCode(92);
          const sampleCwd = (sessions[0].cwd || '').split(BS).join('/').replace(/\/+${'$'}/, '');
          const projectName = sampleCwd.split('/').pop() || rf;
          const plural = sessions.length === 1 ? '' : 's';
          console.log(`=== ${'$'}{projectName} (${'$'}{sessions.length} tab${'$'}{plural}) ===`);
          console.log(`    ${'$'}{sampleCwd}`);
          console.log('');
          sessions.forEach((s, i) => {
            const bypass = s.bypassPermissions ? ' [bypass]' : '';
            const sid = (s.sessionId || '?').slice(0, 12);
            console.log(`  ${'$'}{i + 1}. ${'$'}{s.tabName || '?'}${'$'}{bypass}`);
            console.log(`     session: ${'$'}{sid}...`);
          });
          console.log('');
          total += sessions.length;
        }
        console.log(`Total: ${'$'}{total} active session(s) across ${'$'}{restoreFiles.length} project(s).`);
    """.trimIndent()

    @Test fun tabsStatus_noSessions_friendlyMessage() {
        if (!hasNode()) { println("Node missing — skipping"); return }
        val r = runNode(tabsStatusScript)
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.contains("No active Claude sessions"))
    }

    @Test fun tabsStatus_reportsActiveSessionsGroupedByProject() {
        if (!hasNode()) { println("Node missing — skipping"); return }
        seedRestoreFile("proj-a", listOf(
            sess("sess-1", "Alpha", cwd = "D:\\Dev\\ProjectA"),
            sess("sess-2", "Beta", bypass = true, cwd = "D:\\Dev\\ProjectA"),
        ))
        seedRestoreFile("proj-b", listOf(
            sess("sess-3", "Gamma", cwd = "D:\\Dev\\ProjectB"),
        ))
        val r = runNode(tabsStatusScript)
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.contains("ProjectA (2 tabs)"))
        assertTrue(r.stdout.contains("ProjectB (1 tab)"))
        assertTrue(r.stdout.contains("Alpha"))
        assertTrue(r.stdout.contains("Beta [bypass]"))
        assertTrue(r.stdout.contains("Gamma"))
        assertTrue(r.stdout.contains("Total: 3 active session(s) across 2 project(s)."))
    }

    // ══════════════════════════════════════════════════════════════
    // /tabs-history
    // ══════════════════════════════════════════════════════════════

    private val tabsHistoryScript = """
        const fs = require('fs'), path = require('path'), os = require('os');
        const historyPath = path.join(os.homedir(), '.claude', 'rider-plugin', 'history.json');
        if (!fs.existsSync(historyPath)) { console.log('No session history found.'); process.exit(0); }
        let entries; try { entries = JSON.parse(fs.readFileSync(historyPath, 'utf8')); }
        catch { console.log('history.json is corrupt.'); process.exit(1); }
        entries.sort((a, b) => (b.closedAt || 0) - (a.closedAt || 0));
        console.log('=== Session History (newest first) ===\n');
        const now = Date.now();
        const BS = String.fromCharCode(92);
        entries.forEach((e, i) => {
          const ts = e.closedAt || 0;
          const ageMs = now - ts;
          let ago;
          if (ageMs < 3600_000) ago = `${'$'}{Math.floor(ageMs / 60_000)}m ago`;
          else if (ageMs < 86400_000) ago = `${'$'}{Math.floor(ageMs / 3600_000)}h ago`;
          else ago = `${'$'}{Math.floor(ageMs / 86400_000)}d ago`;
          const date = new Date(ts).toISOString().slice(0, 16).replace('T', ' ');
          const cwdNorm = (e.cwd || '').split(BS).join('/').replace(/\/+${'$'}/, '');
          const proj = cwdNorm.split('/').pop() || '?';
          const marker = e.backedUp ? ' [backup]' : '';
          console.log(`  ${'$'}{i + 1}. [${'$'}{ago}] ${'$'}{e.tabName || '?'}  (${'$'}{proj})${'$'}{marker}  - ${'$'}{date}`);
          console.log(`     session: ${'$'}{(e.sessionId || '?').slice(0, 12)}...`);
        });
    """.trimIndent()

    @Test fun tabsHistory_noHistory_friendlyMessage() {
        if (!hasNode()) { println("Node missing — skipping"); return }
        val r = runNode(tabsHistoryScript)
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.contains("No session history found"))
    }

    @Test fun tabsHistory_listsEntriesNewestFirst() {
        if (!hasNode()) { println("Node missing — skipping"); return }
        val maxAge = 90L * 24 * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        storage.appendToHistory(sess("old", "Old One"), now - 5 * 60 * 1000, maxAge)
        storage.appendToHistory(sess("new", "New One"), now - 60 * 1000, maxAge)

        val r = runNode(tabsHistoryScript)
        assertEquals(0, r.exitCode)
        // "New One" should appear before "Old One" in the output
        val newIdx = r.stdout.indexOf("New One")
        val oldIdx = r.stdout.indexOf("Old One")
        assertTrue("entries should sort newest first", newIdx in 0 until oldIdx)
    }

    // ══════════════════════════════════════════════════════════════
    // /tab backup portion (the Node block inside tab.md)
    // ══════════════════════════════════════════════════════════════

    private val tabScript = """
        const fs = require('fs'), path = require('path'), os = require('os');
        const name = (process.env.TAB_NAME || '').trim();
        const termSid = (process.env.TERM_SESSION_ID || '').trim();
        const home = path.join(os.homedir(), '.claude', 'rider-plugin');
        let sid = null, cwd = '', bypass = false;
        if (termSid) {
          const mapPath = path.join(home, 'session-map', termSid);
          if (fs.existsSync(mapPath)) sid = fs.readFileSync(mapPath, 'utf8').trim();
        }
        const restoreFiles = fs.existsSync(home)
          ? fs.readdirSync(home).filter(f => f.startsWith('restore-') && f.endsWith('.json')) : [];
        if (!sid) {
          for (const f of restoreFiles) {
            let sessions; try { sessions = JSON.parse(fs.readFileSync(path.join(home, f), 'utf8')); } catch { continue; }
            const match = sessions.find(s => s.tabName === name);
            if (match) { sid = match.sessionId; cwd = match.cwd || ''; bypass = !!match.bypassPermissions; break; }
          }
        }
        if (!sid) process.exit(0);
        if (!cwd) {
          for (const f of restoreFiles) {
            let sessions; try { sessions = JSON.parse(fs.readFileSync(path.join(home, f), 'utf8')); } catch { continue; }
            const match = sessions.find(s => s.sessionId === sid);
            if (match) { cwd = match.cwd || ''; bypass = !!match.bypassPermissions; break; }
          }
        }
        const historyPath = path.join(home, 'history.json');
        let history = [];
        if (fs.existsSync(historyPath)) { try { history = JSON.parse(fs.readFileSync(historyPath, 'utf8')); } catch { history = []; } }
        const now = Date.now();
        history = history.filter(e => e.sessionId !== sid);
        history.push({ sessionId: sid, cwd, tabName: name, bypassPermissions: bypass, closedAt: now, backedUp: true });
        const cutoff = now - 90 * 24 * 60 * 60 * 1000;
        history = history.filter(e => (e.closedAt || 0) > cutoff);
        fs.mkdirSync(home, { recursive: true });
        fs.writeFileSync(historyPath, JSON.stringify(history, null, 2));
        console.log(`Backed up ${'$'}{sid.slice(0, 12)}... as "${'$'}{name}"`);
    """.trimIndent()

    @Test fun tabScript_resolvesSessionFromTermSessionIdMap() {
        if (!hasNode()) { println("Node missing — skipping"); return }
        // Seed the session-map with a TERM_SESSION_ID → session ID mapping
        val termSid = "uuid-term-abc"
        storage.sessionMapDir.mkdirs()
        File(storage.sessionMapDir, termSid).writeText("sess-123")

        val r = runNode(tabScript, extraEnv = mapOf(
            "TERM_SESSION_ID" to termSid,
            "TAB_NAME" to "Preferred Tab Name",
        ))
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.contains("sess-123"))

        val history = storage.historyFile.readText()
        assertTrue(history.contains("sess-123"))
        assertTrue(history.contains("Preferred Tab Name"))
    }

    @Test fun tabScript_fallsBackToRestoreFileByName() {
        if (!hasNode()) { println("Node missing — skipping"); return }
        seedRestoreFile("p", listOf(sess("sess-lookup", "Unique Tab")))

        val r = runNode(tabScript, extraEnv = mapOf(
            "TERM_SESSION_ID" to "no-such-mapping",
            "TAB_NAME" to "Unique Tab",
        ))
        assertEquals(0, r.exitCode)
        assertTrue(storage.historyFile.exists())
        assertTrue(storage.historyFile.readText().contains("sess-lookup"))
    }

    @Test fun tabScript_noSessionFound_silentlyExits() {
        if (!hasNode()) { println("Node missing — skipping"); return }
        val r = runNode(tabScript, extraEnv = mapOf(
            "TERM_SESSION_ID" to "nope",
            "TAB_NAME" to "Doesn't Match Anything",
        ))
        assertEquals(0, r.exitCode)
        assertFalse(storage.historyFile.exists())
    }

    // ══════════════════════════════════════════════════════════════
    // current-project.js — project-root resolver helper
    // ══════════════════════════════════════════════════════════════

    private fun parseCurrentProjectJson(stdout: String): Map<String, String> {
        // The script emits a single line of compact JSON. Strip whitespace, then extract
        // the three string fields with a flat regex (no nested objects in the output).
        val out = stdout.trim()
        val fields = mutableMapOf<String, String>()
        Regex(""""(root|hash|name)"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(out).forEach {
            fields[it.groupValues[1]] = it.groupValues[2].replace("\\\\", "\\").replace("\\\"", "\"")
        }
        return fields
    }

    @Test fun currentProject_walksUpToIdeaDirectory() {
        if (!hasNode()) { println("Node missing — skipping"); return }
        // Create: tmp/proj/.idea, tmp/proj/sub/deeper/
        // Run from sub/deeper — should find .idea two levels up.
        val proj = File(tmp.root, "proj").apply { mkdirs() }
        File(proj, ".idea").mkdirs()
        val deep = File(proj, "sub/deeper").apply { mkdirs() }

        val r = runNodeFile(deployedJs("current-project.js"), workingDir = deep)
        assertEquals("script failed: ${r.stdout}", 0, r.exitCode)

        val parsed = parseCurrentProjectJson(r.stdout)
        assertEquals(proj.canonicalPath, File(parsed["root"]!!).canonicalPath)
        assertEquals("proj", parsed["name"])
    }

    @Test fun currentProject_fallsBackToCwdWhenNoIdea() {
        if (!hasNode()) { println("Node missing — skipping"); return }
        val orphan = File(tmp.root, "no-idea-anywhere").apply { mkdirs() }

        val r = runNodeFile(deployedJs("current-project.js"), workingDir = orphan)
        assertEquals(0, r.exitCode)

        val parsed = parseCurrentProjectJson(r.stdout)
        assertEquals(orphan.canonicalPath, File(parsed["root"]!!).canonicalPath)
        assertEquals("no-idea-anywhere", parsed["name"])
    }

    @Test fun currentProject_hashMatchesKotlinHelper() {
        if (!hasNode()) { println("Node missing — skipping"); return }
        // The JS hash MUST mirror ClaudeTabsHelpers.projectHashForPath() so the slash
        // commands can read restore-<hash>.json directly.
        val proj = File(tmp.root, "match-hash-proj").apply { mkdirs() }
        File(proj, ".idea").mkdirs()

        val r = runNodeFile(deployedJs("current-project.js"), workingDir = proj)
        assertEquals(0, r.exitCode)

        val parsed = parseCurrentProjectJson(r.stdout)
        val jsHash = parsed["hash"]!!
        val kotlinHash = ClaudeTabsHelpers.projectHashForPath(proj.absolutePath)
        assertEquals(
            "JS hash should match Kotlin projectHashForPath for the same path " +
                "(JS=$jsHash, Kotlin=$kotlinHash, path=${proj.absolutePath})",
            kotlinHash, jsHash,
        )
    }

    @Test fun currentProject_hashFormatRoundtripsToRestoreFilename() {
        if (!hasNode()) { println("Node missing — skipping"); return }
        // The hash should be filesystem-safe and produce a working `restore-<hash>.json`
        // that can actually be read back. Catches any escape regression.
        val proj = File(tmp.root, "roundtrip-proj").apply { mkdirs() }
        File(proj, ".idea").mkdirs()

        val r = runNodeFile(deployedJs("current-project.js"), workingDir = proj)
        val parsed = parseCurrentProjectJson(r.stdout)
        val hash = parsed["hash"]!!

        // Seed a restore file under the JS-reported hash; verify backup-active.js can read it.
        seedRestoreFile(hash, listOf(sess("rt-1", "Roundtrip Tab")))
        val backup = runNodeFile(deployedJs("backup-active.js"), args = listOf("--hash=$hash"))
        assertEquals(0, backup.exitCode)
        assertTrue("expected backup to find the seeded session: ${backup.stdout}",
            backup.stdout.contains("1 new"))
    }

    // ══════════════════════════════════════════════════════════════
    // backup-active.js --hash=<projectHash> filter
    // ══════════════════════════════════════════════════════════════

    @Test fun backupActive_withHashFilter_onlyBacksUpThatProject() {
        if (!hasNode()) { println("Node missing — skipping"); return }
        seedRestoreFile("proj-a", listOf(sess("sess-a1", "A1"), sess("sess-a2", "A2")))
        seedRestoreFile("proj-b", listOf(sess("sess-b1", "B1")))

        val r = runNodeFile(deployedJs("backup-active.js"), args = listOf("--hash=proj-a"))
        assertEquals(0, r.exitCode)
        assertTrue("expected 2 new sessions backed up: ${r.stdout}", r.stdout.contains("2 new"))

        val history = storage.historyFile.readText()
        assertTrue("history must include proj-a sessions", history.contains("sess-a1"))
        assertTrue("history must include proj-a sessions", history.contains("sess-a2"))
        assertFalse("history must NOT include proj-b sessions", history.contains("sess-b1"))
    }

    @Test fun backupActive_withoutHashFilter_backsUpEveryProject() {
        if (!hasNode()) { println("Node missing — skipping"); return }
        seedRestoreFile("proj-a", listOf(sess("sess-a1", "A1")))
        seedRestoreFile("proj-b", listOf(sess("sess-b1", "B1")))

        val r = runNodeFile(deployedJs("backup-active.js"))
        assertEquals(0, r.exitCode)

        val history = storage.historyFile.readText()
        assertTrue(history.contains("sess-a1"))
        assertTrue(history.contains("sess-b1"))
    }

    @Test fun backupActive_withHashFilter_emptyForUnknownProject() {
        if (!hasNode()) { println("Node missing — skipping"); return }
        // Seed proj-a so home/ exists, but ask for proj-c which has no restore file.
        seedRestoreFile("proj-a", listOf(sess("sess-a1", "A1")))

        val r = runNodeFile(deployedJs("backup-active.js"), args = listOf("--hash=proj-c"))
        assertEquals(0, r.exitCode)
        assertTrue("should report per-project empty message: ${r.stdout}",
            r.stdout.contains("No active sessions for this project"))
        assertFalse("history must not be created when nothing was backed up",
            storage.historyFile.exists())
    }
}

/** Test-only helper for splitting the pretty-printed JSON array history.json produces. */
private object JsonArrayHelper {
    fun readEntries(file: File): List<String> {
        if (!file.exists()) return emptyList()
        return Regex("""\{[^}]*?\n\s*}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .map { it.value }
            .toList()
    }
}
