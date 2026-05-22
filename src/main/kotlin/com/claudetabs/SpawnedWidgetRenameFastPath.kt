package com.claudetabs

/**
 * Direct-rename fast path for tabs the plugin spawned via
 * `TerminalToolWindowManager.createShellWidget`.
 *
 * **Why this exists:** in Rider 2026.1's reworked terminal, spawned tabs are
 * invisible to most platform tab-enumeration APIs (the frontend manager, the backend
 * manager, sometimes ContentManager). So `handleRename`'s usual approach — call
 * `getAllTabs()`, walk to the Claude child, match its sessionId — reports "no tab
 * found" for restored sessions. The fix is to keep a direct reference to the
 * `TerminalWidget` returned by `createShellWidget`, keyed by canonical sessionId,
 * and apply the rename to that widget directly.
 *
 * **What this function does:** look the sessionId up in the cache; if present, apply
 * the rename via the injected lambda; classify the outcome so the caller knows
 * whether to consider the rename handled or to fall through to the scan path.
 *
 * The function is generic in the target type so tests can substitute `Any` for the
 * production `TerminalWidget` — there's no IntelliJ dependency in this file at all.
 */
internal object SpawnedWidgetRenameFastPath {

    /** What happened when the fast path tried to apply. */
    enum class Result {
        /** Cache hit AND apply succeeded — caller can `return` from handleRename. */
        APPLIED,

        /** Cache hit but apply threw (widget disposed underfoot, etc.) — caller
         *  should fall through to the scan path. */
        APPLY_FAILED,

        /** Cache miss — tab wasn't spawned by us — caller should fall through to
         *  the scan path (which handles classic terminal tabs and manually-opened
         *  ones). */
        CACHE_MISS,
    }

    /**
     * Try to rename via the cached target.
     *
     * Look up [sessionId] in [cache]; on hit, call [applyToTarget] with the cached
     * target + [newName]. Any exception from the apply is caught and bucketed as
     * [Result.APPLY_FAILED] — the production code logs a warn and falls through to
     * the scan path. This matches the existing handleRename behaviour exactly.
     */
    fun <T> tryRename(
        sessionId: String,
        newName: String,
        cache: Map<String, T>,
        applyToTarget: (T, String) -> Unit,
    ): Result {
        val target = cache[sessionId] ?: return Result.CACHE_MISS
        return try {
            applyToTarget(target, newName)
            Result.APPLIED
        } catch (_: Exception) {
            Result.APPLY_FAILED
        }
    }
}
