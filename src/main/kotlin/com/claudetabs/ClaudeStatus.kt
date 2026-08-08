package com.claudetabs

/**
 * The state of a Claude Code session as surfaced on its terminal tab.
 *
 * Two independent signals feed this (see [StatusResolver]):
 *
 *  1. **Claude Code hooks** — the primary signal. `UserPromptSubmit` / `Notification` /
 *     `Stop` / `SessionStart` / `SessionEnd` fire the moment the state changes, so an edge
 *     is recorded with no polling latency and with semantics the session file can't express
 *     (notably [FINISHED] vs [IDLE]).
 *  2. **`~/.claude/sessions/<pid>.json`** — the reconciliation signal. Claude Code maintains
 *     a `status` field there itself (`busy` | `shell` | `idle` | `waiting`). It covers what
 *     hooks structurally can't: sessions that started before the hooks were installed, a
 *     Claude that was killed rather than exiting cleanly, and permission prompts that don't
 *     emit a `Notification`.
 *
 * [glyph] is what gets prefixed onto the tab title; [label] is the tab tooltip wording.
 */
internal enum class ClaudeStatus(val glyph: Char, val label: String) {
    /** Claude is running a turn — thinking, calling tools, or running a shell command. */
    WORKING('●', "Working"),

    /** Claude is blocked on the user: a permission prompt or an explicit input request. */
    WAITING('⚠', "Waiting for input"),

    /** A turn completed and Claude is back at the prompt. */
    FINISHED('✓', "Finished"),

    /** Session is up but has not run a turn yet (fresh start / resume). */
    IDLE('○', "Idle"),

    /** The Claude process is gone — clean exit, crash, or kill. */
    EXITED('✕', "Exited");

    companion object {
        /** All glyphs, as a string — used by [StatusDecoration] to recognise its own prefix. */
        val GLYPHS: String = entries.map { it.glyph }.joinToString("")
    }
}

/**
 * Adds and removes the status glyph on a terminal tab title.
 *
 * The plugin's naming/persistence pipeline (names.json, restore files, history) stores
 * **bare** names — a glyph must never leak into them, or a restored tab comes back called
 * `"● backend"`. So decoration is applied only when writing the live UI title, and
 * [strip] is applied at every point where a title is read back.
 */
internal object StatusDecoration {

    /** A leading status glyph plus its separating space. */
    private val PREFIX = Regex("^[${Regex.escape(ClaudeStatus.GLYPHS)}]\\s+")

    /** `("backend", WORKING)` → `"● backend"`. A blank [base] yields just the glyph. */
    fun decorate(base: String?, status: ClaudeStatus?): String {
        val bare = strip(base)
        if (status == null) return bare
        return if (bare.isBlank()) status.glyph.toString() else "${status.glyph} $bare"
    }

    /**
     * Remove the status glyph prefix if present. Idempotent, and a no-op for titles the
     * plugin never decorated — including the JetBrains AI Assistant's own overlay glyphs,
     * which are a disjoint character set (see `ClaudeTabsHelpers.isAiOverlayName`).
     */
    fun strip(title: String?): String {
        if (title.isNullOrEmpty()) return ""
        return PREFIX.replace(title, "").trim()
    }

    /** True if [title] currently carries one of our status glyphs. */
    fun isDecorated(title: String?): Boolean =
        !title.isNullOrEmpty() && PREFIX.containsMatchIn(title)

    /** Tooltip text for a tab: `"backend — Working"`. */
    fun tooltip(base: String?, status: ClaudeStatus?): String {
        val bare = strip(base)
        if (status == null) return bare
        return if (bare.isBlank()) status.label else "$bare — ${status.label}"
    }
}

/**
 * Combines the hook edge and the session-file reading into a single [ClaudeStatus].
 *
 * Pure so the precedence rules can be unit-tested without a filesystem or an IDE.
 */
internal object StatusResolver {

    /**
     * The last hook that fired for a session. [event] is the Claude Code hook event name
     * exactly as it appears in `settings.json`; [ts] is when the hook script ran (epoch ms).
     */
    data class HookSignal(val event: String, val ts: Long)

    /**
     * A reading of `~/.claude/sessions/<pid>.json`. [status] is Claude's own field
     * (`busy` | `shell` | `idle` | `waiting`, or null/unknown on an older CLI);
     * [alive] is whether the pid still exists.
     */
    data class SessionSignal(val status: String?, val statusUpdatedAt: Long, val alive: Boolean)

    /** Hook event → the state it establishes. Unknown events contribute nothing. */
    fun fromHookEvent(event: String): ClaudeStatus? = when (event) {
        "UserPromptSubmit" -> ClaudeStatus.WORKING
        "Notification" -> ClaudeStatus.WAITING
        "Stop" -> ClaudeStatus.FINISHED
        "SessionStart" -> ClaudeStatus.IDLE
        "SessionEnd" -> ClaudeStatus.EXITED
        else -> null
    }

    /** Claude's own `status` field → the state it establishes. */
    fun fromSessionStatus(status: String?): ClaudeStatus? = when (status) {
        // `shell` is Claude running a Bash tool call — still a turn in progress.
        "busy", "shell" -> ClaudeStatus.WORKING
        "waiting" -> ClaudeStatus.WAITING
        "idle" -> ClaudeStatus.IDLE
        else -> null
    }

    /**
     * Resolve the state to show. Returns null when neither signal says anything, which the
     * caller treats as "not a Claude tab" and leaves the title undecorated.
     *
     * Rules, in order:
     *
     *  1. **A dead process is [ClaudeStatus.EXITED]**, whatever either signal last claimed.
     *     `SessionEnd` is likewise terminal — a session that ended does not come back under
     *     the same id.
     *  2. **The newer signal wins.** Both carry timestamps, so a hook edge that fired after
     *     Claude last rewrote its session file supersedes it, and vice versa.
     *  3. **[FINISHED] survives a stale `idle`.** Claude's session file has no way to say
     *     "a turn just completed" — it only knows `idle`. So when the session file would
     *     downgrade a hook-established [ClaudeStatus.FINISHED] to [ClaudeStatus.IDLE], keep
     *     [ClaudeStatus.FINISHED]; the two describe the same underlying process state and
     *     the hook's is strictly more informative.
     */
    fun resolve(hook: HookSignal?, session: SessionSignal?, now: Long = 0L): ClaudeStatus? {
        val hookState = hook?.let { fromHookEvent(it.event) }
        val sessionState = session?.let { fromSessionStatus(it.status) }

        // Rule 1 — terminal states.
        if (hookState == ClaudeStatus.EXITED) return ClaudeStatus.EXITED
        if (session != null && !session.alive) return ClaudeStatus.EXITED

        if (hookState == null) return sessionState
        if (sessionState == null) return hookState

        // Rule 2 — newer wins.
        val winner = if (hook.ts >= session.statusUpdatedAt) hookState else sessionState

        // Rule 3 — don't let a session-file `idle` erase a hook-established `finished`.
        if (winner == ClaudeStatus.IDLE && hookState == ClaudeStatus.FINISHED) return ClaudeStatus.FINISHED

        return winner
    }
}
