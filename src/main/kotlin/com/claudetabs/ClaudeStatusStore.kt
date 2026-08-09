package com.claudetabs

import java.io.File

/**
 * Reads the two on-disk status signals and folds them into `sessionId → `[ClaudeStatus].
 *
 *  - **Hook edges** live in `~/.claude/intellij-claude-terminal/status/<sessionId>.json`, written by
 *    `status-hook.sh` on every `SessionStart` / `UserPromptSubmit` / `Notification` / `Stop`
 *    / `SessionEnd`.
 *  - **Claude's own reading** lives in `~/.claude/sessions/<pid>.json` as a `status` field.
 *
 * Both directories hold one small flat-JSON file per live session (single digits in
 * practice), so a full re-read is a few hundred microseconds — cheap enough to run on the
 * sub-second cadence the tab indicator needs.
 *
 * Deliberately free of IntelliJ types so it can be unit-tested against a temp directory.
 */
internal class ClaudeStatusStore(claudeHome: File) {

    val statusDir = File(claudeHome, "intellij-claude-terminal/status")
    private val sessionsDir = File(claudeHome, "sessions")

    // ══════════════════════════════════════════════════════════════
    // PARSE CACHE
    // ══════════════════════════════════════════════════════════════

    /**
     * Parsed file contents, keyed by path and invalidated by mtime.
     *
     * These directories are read on a 400ms cadence but almost never change: measured on a
     * real install, 107 status files backed **two** live sessions — the rest were finished
     * conversations whose last edge was written hours ago and will never be written again.
     * Re-reading and re-parsing all of them on every tick cost 1.1 ms per snapshot and 1.2 ms
     * per `termSessionMap`, and grew with every session ever run rather than with the ones
     * being watched.
     *
     * A `lastModified` check is a single stat and settles it. The value is kept even when
     * parsing produced nothing, so a malformed or irrelevant file isn't re-parsed each tick
     * either.
     */
    private class ParseCache<T : Any> {
        private class Entry<T>(val mtime: Long, val size: Long, val value: T?)

        private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry<T>>()

        /** [parse] runs only when [f] is new or has changed since it was last read. */
        fun read(f: File, parse: (String) -> T?): T? {
            // One `readAttributes` rather than `lastModified()` + `length()`: measured over
            // 107 files, 147us against 395us, because the two File calls are two syscalls
            // each time and this is one.
            //
            // Size as well as mtime because hook files are rewritten in place, sometimes
            // within the same filesystem timestamp tick as the edge before them — `Stop`
            // then `UserPromptSubmit` when you reply the instant a turn ends. Their payloads
            // differ in length, so size catches what mtime alone would miss.
            val attrs = try {
                java.nio.file.Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
            } catch (_: Exception) {
                entries.remove(f.path)
                return null
            }
            val mtime = attrs.lastModifiedTime().toMillis()
            val size = attrs.size()
            entries[f.path]?.let { if (it.mtime == mtime && it.size == size) return it.value }
            val text = try { f.readText() } catch (_: Exception) { null }
            val value = text?.let { runCatching { parse(it) }.getOrNull() }
            entries[f.path] = Entry(mtime, size, value)
            return value
        }

        /** Drop entries for files that are no longer present, so the map can't grow forever. */
        fun retainOnly(paths: Set<String>) {
            if (entries.size > paths.size) entries.keys.retainAll(paths)
        }
    }

    private val hookCache = ParseCache<HookRecord>()
    private val termCache = ParseCache<Pair<String, Long>>()
    private val sessionCache = ParseCache<SessionFile>()

    /** The fields of `sessions/<pid>.json` this class reads. */
    private data class SessionFile(val sessionId: String, val status: String?, val updatedAt: Long)

    // Both caches are shared by more than one caller, so the parse has to be shared too:
    // keyed by path, whichever parser ran first wins, and two that disagreed about which
    // fields to fill would hand the other caller a half-populated record.

    private fun parseHook(f: File, text: String): HookRecord? {
        val event = ClaudeTabsHelpers.extractJsonString(text, "event") ?: return null
        return HookRecord(
            sessionId = ClaudeTabsHelpers.extractJsonString(text, "sessionId") ?: f.nameWithoutExtension,
            signal = StatusResolver.HookSignal(
                event = event,
                ts = Regex(""""ts"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
                    ?: f.lastModified(),
                // Only SessionStart carries a source, only Notification a type, and only
                // SessionEnd a reason.
                source = ClaudeTabsHelpers.extractJsonString(text, "source")?.takeIf { it.isNotBlank() },
                notificationType = ClaudeTabsHelpers.extractJsonString(text, "notificationType")?.takeIf { it.isNotBlank() },
                reason = ClaudeTabsHelpers.extractJsonString(text, "reason")?.takeIf { it.isNotBlank() },
            ),
            pid = Regex(""""pid"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull(),
        )
    }

    private fun parseSession(f: File, text: String): SessionFile? {
        val sid = ClaudeTabsHelpers.extractJsonString(text, "sessionId") ?: return null
        return SessionFile(
            sessionId = sid,
            status = ClaudeTabsHelpers.extractJsonString(text, "status"),
            updatedAt = Regex(""""statusUpdatedAt"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
                ?: Regex(""""updatedAt"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
                ?: f.lastModified(),
        )
    }

    /** One session's resolved state plus the inputs that produced it (for logging). */
    data class Reading(
        val status: ClaudeStatus,
        val hookEvent: String?,
        val sessionStatus: String?,
    )

    /**
     * Snapshot every session we can say something about, keyed by Claude session id.
     *
     * A hook file's `sessionId` is the id Claude reported at hook time; after
     * `claude --resume` that is the *rotated* id, while the plugin tracks the canonical
     * (transcript-backed) one. Callers resolve both — see the `statusFor` lookup in
     * `ClaudeTabWatcherStartup`, which tries canonical then raw.
     *
     * [isAlive] is injected so tests don't depend on real pids.
     */
    fun snapshot(isAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }): Map<String, Reading> {
        val hooks = readHookSignals()
        val sessions = readSessionSignals(isAlive)

        val result = mutableMapOf<String, Reading>()
        for (sid in hooks.keys + sessions.keys) {
            val hook = hooks[sid]
            val session = sessions[sid]
            val resolved = StatusResolver.resolve(hook, session?.signal) ?: continue
            result[sid] = Reading(resolved, hook?.event, session?.signal?.status)
        }
        return result
    }

    /**
     * `TERM_SESSION_ID` → Claude `sessionId`, from the `termsess-*.json` files the hook
     * writes alongside the session-keyed ones.
     *
     * This is the PID-free bridge between a terminal tab and the session running in it, and
     * on IntelliJ 2026.1's reworked terminal it is the only one that works: the platform
     * reports shell PIDs for the wrong tabs (empty shells) and none at all for the tabs that
     * actually host Claude, so walking `shell pid → child claude` finds nothing. The tab's
     * own `TERM_SESSION_ID` is stable and is inherited by every process the tab spawns, so
     * the hook can record the mapping from the inside.
     *
     * Only sessions that have fired at least one hook appear here — a session started before
     * the hooks were installed has no entry until it restarts.
     */
    fun termSessionMap(): Map<String, String> {
        val files = statusDir.listFiles { f -> f.name.startsWith("termsess-") && f.name.endsWith(".json") }
            ?: return emptyMap()
        termCache.retainOnly(files.mapTo(HashSet()) { it.path })

        val out = mutableMapOf<String, String>()
        val seenAt = mutableMapOf<String, Long>()
        for (f in files) {
            val termSessionId = f.nameWithoutExtension.removePrefix("termsess-")
            val parsed = termCache.read(f) { text ->
                val sid = ClaudeTabsHelpers.extractJsonString(text, "sessionId")?.takeIf { it.isNotBlank() }
                    ?: return@read null
                val ts = Regex(""""ts"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
                    ?: f.lastModified()
                sid to ts
            } ?: continue
            val (sid, ts) = parsed
            // A terminal is reused across sessions (the user exits Claude and starts it
            // again in the same tab), so the newest write is the session in it now.
            if (ts >= (seenAt[termSessionId] ?: Long.MIN_VALUE)) {
                out[termSessionId] = sid
                seenAt[termSessionId] = ts
            }
        }
        return out
    }

    /**
     * `oldSessionId → newSessionId` for sessions that were replaced in place.
     *
     * `/clear` does not end anything the user can see: the terminal, the process and the tab
     * all survive, and only Claude's session id rotates. The old id gets a `SessionEnd` hook
     * and vanishes from `sessions/<pid>.json`, so anything still bound to it resolves to
     * [ClaudeStatus.EXITED] and stays there — a live conversation sitting under a `✕`.
     * Observed exactly that way:
     *
     *   70000004  SessionEnd  pid=12001          ← the hook record
     *   sessions/12001.json → 10000002, busy     ← same process, new session
     *
     * The pid is the join. A process outlives the session ids it runs, so a `SessionEnd`
     * whose pid now hosts a *different, live* session means "replaced", not "gone". Nothing
     * else on disk links the two: the new session gets its own transcript and its own hook
     * file, and `termsess-*.json` only ever keeps the newest id per terminal.
     */
    fun supersededSessions(
        interesting: Set<String>,
        isAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
    ): Map<String, String> {
        if (interesting.isEmpty()) return emptyMap()
        // Scoped to the sessions the caller is actually holding a tab for. Every other
        // `SessionEnd` on disk belongs to a conversation that ended long ago and has no tab
        // to hand over — reading them was the bulk of this call's cost, and it grew with
        // every session ever run rather than with the ones open now.
        val files = statusDir.listFiles { f ->
            f.name.endsWith(".json") && !f.name.startsWith("termsess-") &&
                interesting.any { sid -> f.name.startsWith(sid) }
        } ?: return emptyMap()

        val out = mutableMapOf<String, String>()
        for (f in files) {
            val hook = hookCache.read(f) { parseHook(f, it) } ?: continue
            if (hook.signal.event != "SessionEnd") continue
            val oldSid = hook.sessionId
            if (oldSid !in interesting) continue
            val pid = hook.pid ?: continue
            if (!isAlive(pid)) continue
            val successorFile = File(sessionsDir, "$pid.json")
            val successor = sessionCache.read(successorFile) { parseSession(successorFile, it) }?.sessionId ?: continue
            if (successor.isBlank() || successor == oldSid) continue
            out[oldSid] = successor
        }
        return out
    }

    /** `status/<sessionId>.json` → `{"event":"Stop","sessionId":"...","ts":1786179029939}`. */
    private fun readHookSignals(): Map<String, StatusResolver.HookSignal> {
        // `termsess-*.json` is the fallback key the hook writes when it can resolve the
        // terminal but not yet the Claude session id. The tab indicator is keyed by session
        // id, so those are filtered out by name — before any read — and reaped by `prune`.
        val files = statusDir.listFiles { f ->
            f.name.endsWith(".json") && !f.name.startsWith("termsess-")
        } ?: return emptyMap()
        hookCache.retainOnly(files.mapTo(HashSet()) { it.path })

        val out = mutableMapOf<String, StatusResolver.HookSignal>()
        for (f in files) {
            val hook = hookCache.read(f) { parseHook(f, it) } ?: continue
            val existing = out[hook.sessionId]
            if (existing == null || hook.signal.ts >= existing.ts) out[hook.sessionId] = hook.signal
        }
        return out
    }

    /** One `status/<sessionId>.json`, parsed. [pid] is only used by [supersededSessions]. */
    private data class HookRecord(
        val sessionId: String,
        val signal: StatusResolver.HookSignal,
        val pid: Long?,
    )

    private data class SessionReading(val pid: Long, val signal: StatusResolver.SessionSignal)

    /** `sessions/<pid>.json` → Claude's own `status` / `statusUpdatedAt` fields. */
    private fun readSessionSignals(isAlive: (Long) -> Boolean): Map<String, SessionReading> {
        val files = sessionsDir.listFiles { f -> f.name.endsWith(".json") } ?: return emptyMap()
        sessionCache.retainOnly(files.mapTo(HashSet()) { it.path })

        val out = mutableMapOf<String, SessionReading>()
        for (f in files) {
            val pid = f.nameWithoutExtension.toLongOrNull() ?: continue
            val parsed = sessionCache.read(f) { parseSession(f, it) } ?: continue
            val sid = parsed.sessionId
            val updatedAt = parsed.updatedAt
            // Liveness is deliberately outside the cache: the file stops changing the moment
            // the process dies, which is exactly when this answer has to change.
            val signal = StatusResolver.SessionSignal(parsed.status, updatedAt, isAlive(pid))
            val existing = out[sid]
            // Same sid under two pids happens briefly across a `--resume` handover; the
            // live process is the one that describes the session now.
            if (existing == null || (signal.alive && !existing.signal.alive) ||
                (signal.alive == existing.signal.alive && updatedAt >= existing.signal.statusUpdatedAt)
            ) {
                out[sid] = SessionReading(pid, signal)
            }
        }
        return out
    }

    /**
     * Delete hook files for sessions that are neither live nor recently touched. Without
     * this the directory accumulates one file per session ever run.
     *
     * [liveSessionIds] are the sessions the plugin currently tracks; anything else older
     * than [maxAgeMs] goes.
     */
    fun prune(liveSessionIds: Set<String>, maxAgeMs: Long = 24L * 60 * 60 * 1000, now: Long = System.currentTimeMillis()) {
        val files = statusDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        for (f in files) {
            val sid = f.nameWithoutExtension.removePrefix("termsess-")
            if (sid in liveSessionIds) continue
            if (now - f.lastModified() < maxAgeMs) continue
            try { f.delete() } catch (_: Exception) { /* best effort */ }
        }
    }
}
