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
 * intellij-claude-terminal/
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

    val stateDir = File(claudeHome, "intellij-claude-terminal")
    val tabsDir = File(stateDir, "tabs")
    val sessionMapDir = File(stateDir, "session-map")
    val snapshotsDir = File(stateDir, "snapshots")
    val backupsDir = File(stateDir, "backups")
    val sessionsDir = File(claudeHome, "sessions")
    val historyFile = File(stateDir, "history.json")
    val configFile = File(stateDir, "config.json")
    val namesFile = File(stateDir, "names.json")
    val claudeMdFile = File(claudeHome, "CLAUDE.md")
    val settingsFile = File(claudeHome, "settings.json")
    val commandsDir = File(claudeHome, "commands")

    fun restoreFile(projectHash: String): File = File(stateDir, "restore-$projectHash.json")
    fun userClosedFile(projectHash: String): File = File(stateDir, "user-closed-$projectHash.json")

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
     * Write [newSessions] to the project's restore file as the **authoritative current state**,
     * preserving descriptive names from any prior entries, then write a rotating snapshot.
     *
     * Semantics (1.0.17 follow-up — authoritative replace, not union):
     *  - Read the existing restore file. If [RestoreRead.ReadFailed], abort the write
     *    (don't clobber a corrupted file — keep whatever is on disk).
     *  - **[newSessions] is authoritative**: the file is rebuilt from it. Existing entries
     *    whose sid is NOT in [newSessions] are DROPPED (this is the eviction path for dead
     *    zombies, cross-project leaks, and sessions the scanner correctly determined are
     *    no longer alive-and-ours).
     *  - **Name preservation**: for each entry in [newSessions], if its tabName is generic
     *    ("Claude", "Local", etc.) AND the prior file had a descriptive tabName for the same
     *    sid, the descriptive name is copied over. This preserves restore-file-only names
     *    (sessions that never got a names.json entry) without keeping zombies.
     *  - Subtract [userClosedSessionIds].
     *  - Filter via [transcriptCheck] if provided.
     *  - Transient-empty preservation: if [newSessions] is empty (poll race / startup
     *    grace / Claude binary error), do NOT write — keep whatever's already on disk.
     *  - Otherwise write atomically (tmp + rename).
     *
     * Bug history: prior versions used `existing ∪ newSessions` (high-water-mark union).
     * That preserved stale entries forever — once a dead/migrated sid landed in the file,
     * nothing evicted it. Combined with the now-fixed tab-walk pollution (which fed wrong
     * sids into newSessions), corrupted restore files were one-way trapdoors.
     *
     * Returns the file content written, or null if nothing was written.
     */
    fun saveState(
        projectHash: String,
        newSessions: List<SavedSession>,
        keepCount: Int,
        userClosedSessionIds: Set<String> = emptySet(),
        transcriptCheck: ((cwd: String, sessionId: String) -> Boolean)? = null,
        keepExistingSids: Set<String> = emptySet(),
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

        // Transient-empty preservation: if newSessions is empty AND no grace entries, do NOT
        // touch the file. An empty poll is either a startup race (Claude hasn't written
        // sessions/<pid>.json yet) or the project genuinely has no sessions. Either way, the
        // existing file is a more reliable source than "nothing right now."
        if (newSessions.isEmpty() && keepExistingSids.isEmpty()) return null

        // Authoritative replace + two-poll grace:
        //   - newSessions is authoritative. Existing entries whose sid is NOT in newSessions
        //     AND NOT in keepExistingSids are DROPPED (the eviction path for dead zombies
        //     and cross-project leaks).
        //   - keepExistingSids is the caller's grace list: sids missing from new for the
        //     FIRST time this poll. They're copied verbatim from existing (no scanner data
        //     to overlay). On the next poll, if they're still missing, the caller passes
        //     them in newSessions (which they won't be in) and NOT in keepExistingSids →
        //     finally evicted.
        //
        // Name preservation: if newSessions[sid] has a generic tabName ("Claude"/"Local"/...)
        // but the prior file had a descriptive name for the same sid, copy the descriptive
        // name across. This preserves restore-file-only names without keeping zombies.
        // Sessions that have a name in names.json (the durable name store) get their name
        // via the scanner's resolveName before we ever see them here, so this branch is the
        // safety net for pre-1.0.17 sessions whose only name record is the old restore file.
        val priorById = existing.associateBy { it.sessionId }
        val byId = linkedMapOf<String, SavedSession>()
        for (s in newSessions) {
            val prior = priorById[s.sessionId]
            byId[s.sessionId] = if (prior != null
                && ClaudeTabsHelpers.isGenericTabName(s.tabName)
                && !ClaudeTabsHelpers.isGenericTabName(prior.tabName)) {
                s.copy(tabName = prior.tabName)
            } else {
                s
            }
        }
        // Grace entries: preserve existing entries that the caller flagged as "first miss."
        // Iterate `existing` to preserve original ordering of file entries.
        for (s in existing) {
            if (s.sessionId in keepExistingSids && s.sessionId !in byId) {
                byId[s.sessionId] = s
            }
        }

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
        // Rotate backups BEFORE overwriting live. If the live file has good content and the
        // new write somehow corrupts (it shouldn't — writeAtomic is tmp+rename — but defense
        // in depth), backup-1 still has the pre-write state.
        rotateBackups(projectHash, content)
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

    // ══════════════════════════════════════════════════════════════
    // NAMES STORE — names.json (sessionId → NameEntry)
    // ══════════════════════════════════════════════════════════════
    //
    // names.json is the authoritative source of truth for tab names. The save loop reads
    // from here (via [nameFor]) — never from the live widget title — so the AI Assistant
    // overlay's status-glyph titles ("✳ Claude Code") can never corrupt what gets saved.
    //
    // Writes go through [upsertName] (called from the rename-application paths in
    // ClaudeTabWatcherStartup whenever /tab fires or a tabs/<sid>.json drop is processed).
    // Reads via [nameFor] use an in-memory cache invalidated on file mtime change — so
    // a poll cycle that doesn't rename anything pays at most one File.lastModified() check.

    private val namesLock = Any()
    @Volatile private var namesCache: Map<String, ClaudeTabsHelpers.NameEntry>? = null
    @Volatile private var namesCacheMtime: Long = -1L

    /** Load the names map, reading from disk only when the file mtime has changed since the
     *  cached copy. Cheap to call from inside the 5s poll loop — typically a single stat
     *  syscall in steady state. */
    fun loadNames(): Map<String, ClaudeTabsHelpers.NameEntry> = synchronized(namesLock) {
        val currentMtime = if (namesFile.exists()) namesFile.lastModified() else 0L
        val cached = namesCache
        if (cached != null && currentMtime == namesCacheMtime) return@synchronized cached
        val text = if (namesFile.exists()) {
            try { namesFile.readText() } catch (_: Exception) { "" }
        } else {
            ""
        }
        val parsed = parseNames(text)
        namesCache = parsed
        namesCacheMtime = currentMtime
        parsed
    }

    /** Look up the name for [sessionId]. Returns null if no entry exists. */
    fun nameFor(sessionId: String): String? = loadNames()[sessionId]?.name

    /** Insert or replace the entry for [sessionId] and atomically rewrite the file.
     *  Cache is invalidated; the next [loadNames] re-reads. */
    fun upsertName(
        sessionId: String,
        name: String,
        setBy: String,
        now: Long = System.currentTimeMillis(),
    ) = synchronized(namesLock) {
        val current = loadNames().toMutableMap()
        val existing = current[sessionId]
        if (existing != null && existing.name == name && existing.setBy == setBy) {
            // No-op write — saves a disk hit for the common "every poll re-applies same name".
            return@synchronized
        }
        current[sessionId] = ClaudeTabsHelpers.NameEntry(name, setBy, now)
        namesFile.parentFile?.mkdirs()
        writeAtomic(namesFile, serialiseNames(current))
        namesCache = null
        namesCacheMtime = -1L
    }

    /** Copy [fromSid]'s name to [toSid] (no-op if [fromSid] has no entry or [toSid] already
     *  has one). Used after `claude --resume` to mirror a canonical sid's name onto its
     *  rotated counterpart so either lookup resolves correctly. */
    fun aliasName(
        fromSid: String,
        toSid: String,
        now: Long = System.currentTimeMillis(),
    ) = synchronized(namesLock) {
        if (fromSid == toSid) return@synchronized
        val current = loadNames()
        val src = current[fromSid] ?: return@synchronized
        if (current[toSid] != null) return@synchronized
        val updated = current.toMutableMap().apply {
            put(toSid, ClaudeTabsHelpers.NameEntry(src.name, "alias", now))
        }
        namesFile.parentFile?.mkdirs()
        writeAtomic(namesFile, serialiseNames(updated))
        namesCache = null
        namesCacheMtime = -1L
    }

    /** Drop entries whose sid no longer satisfies [sidStillExists]. Used by the periodic
     *  pruner to keep names.json from growing unbounded across long Rider sessions.
     *  Returns the number of entries removed. */
    fun pruneNames(sidStillExists: (sid: String) -> Boolean): Int = synchronized(namesLock) {
        val current = loadNames()
        val kept = current.filterKeys(sidStillExists)
        if (kept.size == current.size) return@synchronized 0
        namesFile.parentFile?.mkdirs()
        writeAtomic(namesFile, serialiseNames(kept))
        namesCache = null
        namesCacheMtime = -1L
        current.size - kept.size
    }

    /** Serialise [map] to a stable, human-readable JSON object. Order is insertion order
     *  (so the file diff stays minimal across small upserts when the map is a LinkedHashMap). */
    fun serialiseNames(map: Map<String, ClaudeTabsHelpers.NameEntry>): String {
        if (map.isEmpty()) return "{}"
        return map.entries.joinToString(prefix = "{\n", postfix = "\n}", separator = ",\n") { (sid, e) ->
            "  \"${ClaudeTabsHelpers.esc(sid)}\":{" +
                "\"name\":\"${ClaudeTabsHelpers.esc(e.name)}\"," +
                "\"setBy\":\"${ClaudeTabsHelpers.esc(e.setBy)}\"," +
                "\"setAt\":${e.setAt}}"
        }
    }

    /** Parse a names.json text blob back into a map. Tolerant of trailing whitespace,
     *  missing fields (setBy defaults to "unknown", setAt to 0), and any unparseable
     *  entry is silently skipped (won't crash the poll loop on a hand-edited file). */
    fun parseNames(text: String): Map<String, ClaudeTabsHelpers.NameEntry> {
        val t = text.trim()
        if (t.isEmpty() || t == "{}") return emptyMap()
        val result = linkedMapOf<String, ClaudeTabsHelpers.NameEntry>()
        // Top-level entries: "sid": { ... } — sid value may have escaped chars, body is flat object.
        val entryRe = Regex("\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"\\s*:\\s*\\{([^}]*)\\}")
        for (m in entryRe.findAll(t)) {
            val sid = m.groupValues[1].replace("\\\\", "\\").replace("\\\"", "\"")
            val body = "{${m.groupValues[2]}}"
            val name = ClaudeTabsHelpers.extractJsonString(body, "name") ?: continue
            val setBy = ClaudeTabsHelpers.extractJsonString(body, "setBy") ?: "unknown"
            val setAt = Regex("\"setAt\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            result[sid] = ClaudeTabsHelpers.NameEntry(name, setBy, setAt)
        }
        return result
    }

    // ══════════════════════════════════════════════════════════════
    // USER-CLOSED STORE — per-project persistent set of sids the user closed
    // ══════════════════════════════════════════════════════════════
    //
    // Was in-memory only (ProjectCtx.userClosedSessions). A crash mid-close lost the close
    // event and the next start auto-restored the just-closed tab. Persisted here so the
    // close survives a hard kill / power loss.

    private val userClosedLock = Any()

    /** Load the set of sids the user has explicitly closed in [projectHash]. Returns
     *  emptySet on missing/empty/unreadable file (safe default — never resurrect tabs
     *  spuriously, but also never block load on a corrupted file). */
    fun loadUserClosed(projectHash: String): Set<String> = synchronized(userClosedLock) {
        val f = userClosedFile(projectHash)
        if (!f.exists()) return@synchronized emptySet()
        val text = try { f.readText() } catch (_: Exception) { return@synchronized emptySet() }
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return@synchronized emptySet()
        // Match every quoted string literal — sids are UUIDs so quote-only contents are safe.
        Regex("\"([^\"]+)\"").findAll(trimmed).map { it.groupValues[1] }.toSet()
    }

    /** Persist [closed] for [projectHash] via atomic write. */
    fun saveUserClosed(projectHash: String, closed: Set<String>) = synchronized(userClosedLock) {
        val f = userClosedFile(projectHash)
        f.parentFile?.mkdirs()
        val content = if (closed.isEmpty()) "[]" else closed.joinToString(
            prefix = "[\n",
            postfix = "\n]",
            separator = ",\n",
        ) { "  \"${ClaudeTabsHelpers.esc(it)}\"" }
        writeAtomic(f, content)
    }

    /** Add [sid] to the persistent user-closed set for [projectHash]. Idempotent. */
    fun addUserClosed(projectHash: String, sid: String): Boolean = synchronized(userClosedLock) {
        val current = loadUserClosed(projectHash)
        if (sid in current) return@synchronized false
        saveUserClosed(projectHash, current + sid)
        true
    }

    /** Prune entries whose sid no longer satisfies [sidStillExists] (called from the
     *  periodic pruner to keep this set from accumulating sids whose transcripts are gone). */
    fun pruneUserClosed(projectHash: String, sidStillExists: (sid: String) -> Boolean): Int =
        synchronized(userClosedLock) {
            val current = loadUserClosed(projectHash)
            val kept = current.filter(sidStillExists).toSet()
            if (kept.size == current.size) return@synchronized 0
            saveUserClosed(projectHash, kept)
            current.size - kept.size
        }

    // ══════════════════════════════════════════════════════════════
    // BACKUPS — 3 most recent non-empty active-sessions files per project
    // ══════════════════════════════════════════════════════════════
    //
    // Separate from snapshots/ (forensic rolling history of every save). Backups are the
    // user-facing recovery tier: if the live file gets corrupted / wiped / replaced with
    // [] by some race, fall back to backup-1, then backup-2, then backup-3.

    private val backupsLock = Any()
    private val BACKUP_COUNT = 3

    fun backupFile(projectHash: String, index: Int): File =
        File(backupsDir, "active-sessions-$projectHash-$index.json")

    /** Rotate backups for [projectHash], shifting `1→2`, `2→3` (oldest dropped), then
     *  copying [currentContent] into `-1`. No-op when [currentContent] is empty/`[]` or
     *  identical to the current `-1.json` (avoids 3 identical backups when state hasn't
     *  changed). */
    fun rotateBackups(projectHash: String, currentContent: String) = synchronized(backupsLock) {
        val trimmed = currentContent.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return@synchronized
        backupsDir.mkdirs()
        val backup1 = backupFile(projectHash, 1)
        if (backup1.exists()) {
            val existing1 = try { backup1.readText().trim() } catch (_: Exception) { "" }
            if (existing1 == trimmed) return@synchronized
        }
        // Shift: 2→3, 1→2 (oldest gone)
        for (i in (BACKUP_COUNT - 1) downTo 1) {
            val src = backupFile(projectHash, i)
            val dst = backupFile(projectHash, i + 1)
            if (src.exists()) {
                try {
                    if (dst.exists()) dst.delete()
                    src.copyTo(dst, overwrite = true)
                } catch (_: Exception) { /* best effort */ }
            }
        }
        // Write new -1
        writeAtomic(backup1, currentContent)
    }

    /** List backup files for [projectHash] newest-first. Used by the recovery fallback. */
    fun listBackups(projectHash: String): List<File> = (1..BACKUP_COUNT)
        .map { backupFile(projectHash, it) }
        .filter { it.exists() }

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
     * Load the restore file with a tiered fallback chain:
     *   1. live `restore-<hash>.json`
     *   2. `backups/active-sessions-<hash>-{1,2,3}.json` (most recent first)
     *   3. existing `snapshots/<hash>__<ts>.json` (forensic rolling history, newest first)
     *
     * Returns the first non-empty source with its parsed sessions plus a tag for logging
     * (so idea.log can tell the user which tier rescued the restore).
     */
    data class LoadResult(val sessions: List<SavedSession>, val source: File?, val tier: String?)
    fun loadRestoreWithFallback(projectHash: String): LoadResult {
        data class Tier(val tag: String, val file: File)
        val sources = mutableListOf<Tier>().apply {
            val live = restoreFile(projectHash)
            if (live.exists()) add(Tier("live", live))
            listBackups(projectHash).forEachIndexed { i, f -> add(Tier("backup-${i + 1}", f)) }
            listSnapshots(projectHash).forEachIndexed { i, f -> add(Tier("snapshot-${i + 1}", f)) }
        }
        for (src in sources) {
            try {
                val parsed = parseSessions(src.file.readText())
                if (parsed.isNotEmpty()) return LoadResult(parsed, src.file, src.tag)
            } catch (_: Exception) { /* try next */ }
        }
        return LoadResult(emptyList(), null, null)
    }
}
