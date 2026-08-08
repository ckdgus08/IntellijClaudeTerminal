package com.claudetabs

import java.io.File

/**
 * Reads the two on-disk status signals and folds them into `sessionId → `[ClaudeStatus].
 *
 *  - **Hook edges** live in `~/.claude/rider-plugin/status/<sessionId>.json`, written by
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

    val statusDir = File(claudeHome, "rider-plugin/status")
    private val sessionsDir = File(claudeHome, "sessions")

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
        val files = statusDir.listFiles { f -> f.isFile && f.name.startsWith("termsess-") && f.name.endsWith(".json") }
            ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        val seenAt = mutableMapOf<String, Long>()
        for (f in files) {
            val termSessionId = f.nameWithoutExtension.removePrefix("termsess-")
            val text = try { f.readText() } catch (_: Exception) { continue }
            val sid = ClaudeTabsHelpers.extractJsonString(text, "sessionId")?.takeIf { it.isNotBlank() } ?: continue
            val ts = Regex(""""ts"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
                ?: f.lastModified()
            // A terminal is reused across sessions (the user exits Claude and starts it
            // again in the same tab), so the newest write is the session in it now.
            if (ts >= (seenAt[termSessionId] ?: Long.MIN_VALUE)) {
                out[termSessionId] = sid
                seenAt[termSessionId] = ts
            }
        }
        return out
    }

    /** `status/<sessionId>.json` → `{"event":"Stop","sessionId":"...","ts":1786179029939}`. */
    private fun readHookSignals(): Map<String, StatusResolver.HookSignal> {
        val files = statusDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyMap()
        val out = mutableMapOf<String, StatusResolver.HookSignal>()
        for (f in files) {
            // `termsess-*.json` is the fallback key the hook writes when it can resolve the
            // terminal but not yet the Claude session id. The tab indicator is keyed by
            // session id, so those are ignored here and reaped by `prune`.
            if (f.name.startsWith("termsess-")) continue
            val text = try { f.readText() } catch (_: Exception) { continue }
            val event = ClaudeTabsHelpers.extractJsonString(text, "event") ?: continue
            val sid = ClaudeTabsHelpers.extractJsonString(text, "sessionId")
                ?: f.nameWithoutExtension
            val ts = Regex(""""ts"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
                ?: f.lastModified()
            // Only SessionStart carries one; blank for everything else.
            val source = ClaudeTabsHelpers.extractJsonString(text, "source")?.takeIf { it.isNotBlank() }
            val existing = out[sid]
            if (existing == null || ts >= existing.ts) out[sid] = StatusResolver.HookSignal(event, ts, source)
        }
        return out
    }

    private data class SessionReading(val pid: Long, val signal: StatusResolver.SessionSignal)

    /** `sessions/<pid>.json` → Claude's own `status` / `statusUpdatedAt` fields. */
    private fun readSessionSignals(isAlive: (Long) -> Boolean): Map<String, SessionReading> {
        val files = sessionsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyMap()
        val out = mutableMapOf<String, SessionReading>()
        for (f in files) {
            val pid = f.nameWithoutExtension.toLongOrNull() ?: continue
            val text = try { f.readText() } catch (_: Exception) { continue }
            val sid = ClaudeTabsHelpers.extractJsonString(text, "sessionId") ?: continue
            val status = ClaudeTabsHelpers.extractJsonString(text, "status")
            val updatedAt = Regex(""""statusUpdatedAt"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
                ?: Regex(""""updatedAt"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
                ?: f.lastModified()
            val signal = StatusResolver.SessionSignal(status, updatedAt, isAlive(pid))
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
