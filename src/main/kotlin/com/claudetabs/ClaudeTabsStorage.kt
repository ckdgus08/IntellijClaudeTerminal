package com.claudetabs

import java.io.File

/**
 * All filesystem reads/writes the plugin performs, centralised into one object that can
 * operate on any root directory (production uses `~/.claude`, tests use a temp dir).
 *
 * Nothing here touches the IntelliJ platform — this is intentionally split from
 * [ClaudeTabWatcherStartup] so it can be exercised by headless integration tests.
 *
 * File layout under [claudeHome]:
 * ```
 * CLAUDE.md                               # plugin injects a section between markers
 * settings.json                           # plugin adds a permission entry
 * commands/tab.md, tabs-*.md              # (deployed by plugin at runtime, not here)
 * rider-plugin/
 *   rename-tab.sh, session-start-hook.sh  # shell integration scripts
 *   tabs/<sessionId>.json                 # rename directives (scripts → plugin)
 *   session-map/<TERM_SESSION_ID>         # per-tab session ID mapping
 *   restore-<projectHash>.json            # auto-restore target
 *   snapshots/<projectHash>-<ts>.json     # rolling backups
 *   history.json                          # closed/backed-up sessions
 *   config.json                           # user-overridable settings
 * ```
 */
internal class ClaudeTabsStorage(private val claudeHome: File) {

    val stateDir = File(claudeHome, "rider-plugin")
    val tabsDir = File(stateDir, "tabs")
    val sessionMapDir = File(stateDir, "session-map")
    val snapshotsDir = File(stateDir, "snapshots")
    val sessionsDir = File(claudeHome, "sessions")
    val historyFile = File(stateDir, "history.json")
    val configFile = File(stateDir, "config.json")
    val claudeMdFile = File(claudeHome, "CLAUDE.md")
    val settingsFile = File(claudeHome, "settings.json")
    val commandsDir = File(claudeHome, "commands")

    fun restoreFile(projectHash: String): File = File(stateDir, "restore-$projectHash.json")

    // ══════════════════════════════════════════════════════════════
    // HISTORY
    // ══════════════════════════════════════════════════════════════

    /**
     * Append (or update) a history entry for [session]. Entries older than [maxAgeMs] are pruned.
     *
     * Thread-safe via [historyLock]. If the write fails the exception is rethrown so callers
     * can log (the production caller catches and logs at DEBUG).
     */
    private val historyLock = Any()

    /** Result of attempting to read history.json — distinguishes a legitimately-empty file
     *  from a transient read/parse failure on a non-trivial file. Callers MUST treat
     *  [ReadFailed] as a hard abort: writing on top of a failed read is what historically
     *  silently wiped users' history when AV scanners / OneDrive / file indexers briefly
     *  locked the file. */
    sealed class HistoryRead {
        data class Ok(val entries: List<String>) : HistoryRead()
        data class ReadFailed(val reason: String) : HistoryRead()
    }

    fun appendToHistory(session: SavedSession, now: Long = System.currentTimeMillis(), maxAgeMs: Long): Boolean =
        synchronized(historyLock) {
            val read = loadHistorySafe()
            if (read is HistoryRead.ReadFailed) {
                // Abort — writing here would clobber whatever's actually on disk.
                return@synchronized false
            }
            val existing = (read as HistoryRead.Ok).entries.toMutableList()
            existing.removeAll { ClaudeTabsHelpers.extractJsonString(it, "sessionId") == session.sessionId }

            val entry = buildString {
                append("{\"sessionId\":\"${ClaudeTabsHelpers.esc(session.sessionId)}\"")
                append(",\"cwd\":\"${ClaudeTabsHelpers.esc(session.cwd)}\"")
                append(",\"tabName\":\"${ClaudeTabsHelpers.esc(session.tabName)}\"")
                append(",\"bypassPermissions\":${session.bypassPermissions}")
                append(",\"closedAt\":$now}")
            }
            existing.add(entry)

            val cutoff = now - maxAgeMs
            val pruned = existing.filter { raw ->
                val ts = Regex(""""closedAt":(\d+)""").find(raw)?.groupValues?.get(1)?.toLongOrNull()
                ts != null && ts > cutoff
            }

            historyFile.parentFile?.mkdirs()
            writeAtomic(historyFile, pruned.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") { "  $it" })
            true
        }

    /** Best-effort read used by tests and external callers — returns empty list on failure
     *  for backwards compatibility. Production code path uses [loadHistorySafe] instead so
     *  it can distinguish empty-file from read-failure. */
    fun loadHistoryRaw(): List<String> = when (val r = loadHistorySafe()) {
        is HistoryRead.Ok -> r.entries
        is HistoryRead.ReadFailed -> emptyList()
    }

    /**
     * Read [historyFile] safely. Returns:
     *  - [HistoryRead.Ok] with an empty list when the file is missing OR genuinely empty
     *    (`""` / `[]` / whitespace).
     *  - [HistoryRead.Ok] with the parsed entries when the file is readable.
     *  - [HistoryRead.ReadFailed] when the file exists with non-trivial content but reading
     *    or parsing failed. **Never proceed to write in this state** — that's how silent
     *    history wipes happen.
     */
    fun loadHistorySafe(): HistoryRead = synchronized(historyLock) {
        if (!historyFile.exists()) return@synchronized HistoryRead.Ok(emptyList())
        val text = try {
            historyFile.readText()
        } catch (e: Exception) {
            return@synchronized HistoryRead.ReadFailed("readText failed: ${e.message}")
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return@synchronized HistoryRead.Ok(emptyList())
        val matches = Regex("""\{[^}]+\}""").findAll(text).map { it.value }.toList()
        if (matches.isEmpty()) {
            // File has content but we couldn't extract a single JSON object — almost certainly
            // a partial/corrupt write, not a legitimately empty file. Refuse to overwrite.
            return@synchronized HistoryRead.ReadFailed("file has ${trimmed.length} chars but no parseable entries")
        }
        HistoryRead.Ok(matches)
    }

    /** Write [content] to [target] via a tempfile + rename so a crash mid-write can't leave
     *  the target file partially overwritten. The tempfile sits next to the target so the
     *  rename stays on the same filesystem (atomic on Windows + Unix). */
    internal fun writeAtomic(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp.${System.nanoTime()}")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // Windows refuses rename when the destination exists. Try delete-then-rename;
            // if even that fails, leave the tmpfile around (it's named so it won't collide)
            // and surface the IO error so callers know not to claim success.
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw java.io.IOException("atomic rename failed for ${target.absolutePath}")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // RESTORE FILE + SNAPSHOTS
    // ══════════════════════════════════════════════════════════════

    data class SavedSession(val sessionId: String, val cwd: String, val tabName: String, val bypassPermissions: Boolean)

    /** Serialise [sessions] to a JSON array string (matches what saveState writes). */
    fun serialiseSessions(sessions: List<SavedSession>): String {
        if (sessions.isEmpty()) return "[]"
        return sessions.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") { s ->
            "  {\"sessionId\":\"${ClaudeTabsHelpers.esc(s.sessionId)}\"," +
                "\"cwd\":\"${ClaudeTabsHelpers.esc(s.cwd)}\"," +
                "\"tabName\":\"${ClaudeTabsHelpers.esc(s.tabName)}\"," +
                "\"bypassPermissions\":${s.bypassPermissions}}"
        }
    }

    /** Parse a saved-sessions JSON string back into the record list. */
    fun parseSessions(json: String): List<SavedSession> {
        val text = json.trim()
        if (text.isEmpty() || text == "[]") return emptyList()
        return Regex("""\{[^}]+\}""").findAll(text).mapNotNull { m ->
            val o = m.value
            val sid = ClaudeTabsHelpers.extractJsonString(o, "sessionId") ?: return@mapNotNull null
            val cwd = ClaudeTabsHelpers.extractJsonString(o, "cwd") ?: return@mapNotNull null
            val name = ClaudeTabsHelpers.extractJsonString(o, "tabName") ?: return@mapNotNull null
            SavedSession(sid, cwd, name, o.contains("\"bypassPermissions\":true"))
        }.toList()
    }

    /** Result of [loadRestoreSafe] — same shape as [HistoryRead]. ReadFailed is sticky:
     *  callers MUST refuse to overwrite the file when it appears, or they'll silently wipe
     *  a corrupted-but-recoverable file. */
    sealed class RestoreRead {
        data class Ok(val sessions: List<SavedSession>) : RestoreRead()
        data class ReadFailed(val reason: String) : RestoreRead()
    }

    /** Safely read the restore file. Returns:
     *  - `Ok(emptyList())` when the file doesn't exist or is genuinely empty (`""`, `[]`).
     *  - `Ok(parsed)` when the file is readable and parsed.
     *  - `ReadFailed` when the file has non-trivial content but parsing yielded nothing
     *    (likely a partial/corrupted write — refusing to overwrite is safer than nuking it).
     */
    fun loadRestoreSafe(projectHash: String): RestoreRead {
        val f = restoreFile(projectHash)
        if (!f.exists()) return RestoreRead.Ok(emptyList())
        val text = try { f.readText() } catch (e: Exception) {
            return RestoreRead.ReadFailed("readText failed: ${e.message}")
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return RestoreRead.Ok(emptyList())
        val parsed = parseSessions(text)
        if (parsed.isEmpty()) {
            return RestoreRead.ReadFailed("file has ${trimmed.length} chars but no parseable entries")
        }
        return RestoreRead.Ok(parsed)
    }

    /**
     * Write [newSessions] to the project's restore file as a **high-water-mark union** with
     * any existing entries, then write a rotating snapshot.
     *
     * Semantics:
     *  - Read the existing restore file. If [RestoreRead.ReadFailed], abort the write
     *    (don't clobber a corrupted file — keep whatever is on disk).
     *  - Union: `existing ∪ newSessions`, **new takes precedence** on sessionId collisions
     *    (new's name is more current than the file's stale name).
     *  - Subtract [userClosedSessionIds] — these are sessions the user explicitly closed via
     *    the terminal tab's X (NOT project close). Sessions in this set are dropped from
     *    the saved state and won't be auto-restored.
     *  - Filter via [transcriptCheck] if provided — drops entries whose Claude transcript
     *    is no longer on disk (resume would fail anyway, so don't save them).
     *  - Empty result + empty new input + no existing content → return null (transient
     *    empty poll, preserve previous file content).
     *  - Otherwise write atomically (tmp + rename) so a mid-write crash can't corrupt.
     *
     * This is **crash-safe** (atomic write) and **drift-safe** (union with existing means
     * a session can't get evicted from restore just because the poll missed it once).
     *
     * Returns the file content written, or null if nothing was written.
     */
    fun saveState(
        projectHash: String,
        newSessions: List<SavedSession>,
        keepCount: Int,
        userClosedSessionIds: Set<String> = emptySet(),
        transcriptCheck: ((cwd: String, sessionId: String) -> Boolean)? = null,
        now: Long = System.currentTimeMillis(),
    ): String? {
        val f = restoreFile(projectHash)

        // Read existing — defensive against corrupted files.
        val existingRead = loadRestoreSafe(projectHash)
        if (existingRead is RestoreRead.ReadFailed) {
            // File exists with non-trivial content we couldn't parse. Writing now would
            // wipe it. Preserve and skip — next poll will retry.
            return null
        }
        val existing = (existingRead as RestoreRead.Ok).sessions

        // Union: existing first (so iteration order is stable), then new overlays.
        val byId = linkedMapOf<String, SavedSession>()
        for (s in existing) byId[s.sessionId] = s
        for (s in newSessions) byId[s.sessionId] = s

        // Subtract user-closed.
        for (sid in userClosedSessionIds) byId.remove(sid)

        // Filter by transcript existence.
        val filtered = if (transcriptCheck != null) {
            byId.values.filter { transcriptCheck(it.cwd, it.sessionId) }
        } else {
            byId.values.toList()
        }

        // Transient-empty preservation: if we'd write an empty list AND there were no new
        // inputs AND no existing content, leave the file alone (matches original contract).
        if (filtered.isEmpty()) {
            if (newSessions.isEmpty() && existing.isEmpty()) return null
            // Writing an empty list IS meaningful when we had content and lost it all to
            // userClosed/transcript-gone — but that's so destructive we still preserve.
            // The next poll with non-empty sessions overwrites.
            return null
        }

        val content = serialiseSessions(filtered)
        f.parentFile?.mkdirs()
        writeAtomic(f, content)
        writeSnapshot(projectHash, content, keepCount, now)
        return content
    }

    /** Write a timestamped snapshot and prune older ones beyond [keepCount]. */
    fun writeSnapshot(projectHash: String, content: String, keepCount: Int, now: Long = System.currentTimeMillis()) {
        if (keepCount <= 0) return
        snapshotsDir.mkdirs()
        // Double-underscore delimiter so sibling projects whose hashes share a prefix
        // (e.g. `a-b` and `a-b-mobile`) don't accidentally match each other's snapshots
        // when listing/pruning. Legacy `-` filenames are still accepted on read.
        File(snapshotsDir, "${projectHash}__${now}.json").writeText(content)

        val existing = listSnapshots(projectHash)
        if (existing.size > keepCount) {
            existing.drop(keepCount).forEach { old ->
                try { old.delete() } catch (_: Exception) { /* best effort */ }
            }
        }
    }

    /** List snapshots for [projectHash], newest first. Accepts both the current `__`
     *  delimiter and the legacy single-dash format. */
    fun listSnapshots(projectHash: String): List<File> {
        val newPrefix = "${projectHash}__"
        val legacyPrefix = "$projectHash-"
        return snapshotsDir.listFiles()
            ?.filter {
                it.name.endsWith(".json") &&
                    (it.name.startsWith(newPrefix) || it.name.startsWith(legacyPrefix))
            }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /**
     * Load the restore file (or fall back to the newest non-empty snapshot if the live file
     * is missing, empty, or unparseable). Returns the list of sessions to restore plus the
     * source file for logging.
     */
    data class LoadResult(val sessions: List<SavedSession>, val source: File?)
    fun loadRestoreWithFallback(projectHash: String): LoadResult {
        val sources = mutableListOf<File>().apply {
            val live = restoreFile(projectHash)
            if (live.exists()) add(live)
            addAll(listSnapshots(projectHash))
        }
        for (src in sources) {
            try {
                val parsed = parseSessions(src.readText())
                if (parsed.isNotEmpty()) return LoadResult(parsed, src)
            } catch (_: Exception) { /* try next */ }
        }
        return LoadResult(emptyList(), null)
    }
}
