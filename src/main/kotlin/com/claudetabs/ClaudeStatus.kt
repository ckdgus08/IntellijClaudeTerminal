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

    /** A leading status glyph, optional background badge, and separating space. */
    private val PREFIX = Regex(
        "^[${Regex.escape(ClaudeStatus.GLYPHS)}](?:\\s+·\\s+bg[0-9+]+)?\\s+"
    )

    /** Compact count shown beside the main state; bounded so a bad payload cannot grow a tab. */
    fun backgroundLabel(backgroundTaskCount: Int): String? = when {
        backgroundTaskCount <= 0 -> null
        backgroundTaskCount > 99 -> "bg99+"
        else -> "bg$backgroundTaskCount"
    }

    /** `("backend", FINISHED, 1)` → `"✓ · bg1 backend"`. */
    fun decorate(base: String?, status: ClaudeStatus?, backgroundTaskCount: Int = 0): String {
        val bare = strip(base)
        if (status == null) return bare
        val prefix = buildString {
            append(status.glyph)
            backgroundLabel(backgroundTaskCount)?.let { append(" · ").append(it) }
        }
        return if (bare.isBlank()) prefix else "$prefix $bare"
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

    /** Tooltip text for a tab, including background work without exposing task details. */
    fun tooltip(base: String?, status: ClaudeStatus?, backgroundTaskCount: Int = 0): String {
        val bare = strip(base)
        if (status == null) return bare
        val state = buildString {
            append(status.label)
            if (backgroundTaskCount > 0) {
                append(" · ").append(backgroundTaskCount).append(" background task")
                if (backgroundTaskCount != 1) append('s')
                append(" running")
            }
        }
        return if (bare.isBlank()) state else "$bare — $state"
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
     *
     * [source] is only carried by `SessionStart`, where it says how the session began:
     * `startup` | `resume` | `clear` | `compact`. Null for every other event, and for hook
     * files written before the plugin started recording it.
     */
    data class HookSignal(
        val event: String,
        val ts: Long,
        val source: String? = null,
        /** `Notification` only: Claude's `notification_type`. See [BLOCKING_NOTIFICATIONS]. */
        val notificationType: String? = null,
        /** `SessionEnd` only: Claude's `reason`. See [fromHookEvent]. */
        val reason: String? = null,
    )

    /**
     * The `notification_type` Claude uses for its "Claude is waiting for your input" nudge,
     * fired once the session has been idle for `messageIdleNotifThresholdMs` (60s).
     *
     * Not a permission prompt and not a state change — which is why a finished session used
     * to show ✓ and then flip to ⚠ a minute later with nothing having happened.
     */
    const val IDLE_NOTIFICATION = "idle_prompt"

    /**
     * The `notification_type` values that actually mean someone is blocked.
     *
     * This is an allowlist because the deny-list it replaced was wrong about most of the set.
     * Claude Code 2.1.x sends at least twelve types and only these four are a person waiting:
     *
     *     permission_prompt · worker_permission_prompt · elicitation_dialog · agent_needs_input
     *
     * Everything else is Claude *telling* you something, and reading those as ⚠ was actively
     * destructive — the status file holds one edge per session, so each one overwrote the
     * `Stop` or `UserPromptSubmit` underneath it. The ones that bit hardest:
     *
     *  - `agent_completed` — fires when a **background agent finishes**, with the message
     *    "<label> finished". A fan-out of five agents produced five spurious ⚠ per turn, on
     *    the tab that was least likely to be looked at.
     *  - `elicitation_complete` / `elicitation_response` — the end of a wait, marked as the
     *    start of one, so the tab stayed ⚠ after the question had been answered.
     *  - `auth_success` — "Claude Code login successful", which flipped a working tab to ⚠.
     *  - `idle_prompt` — see [IDLE_NOTIFICATION].
     *
     * An unrecognised type establishes nothing rather than defaulting to ⚠: a genuinely
     * blocking one that isn't listed here is still caught by Claude's own session file, which
     * reports `waiting` for permission dialogs, elicitation and sandbox prompts alike — so a
     * miss self-corrects, while a false ⚠ destroys the edge it was written over.
     */
    val BLOCKING_NOTIFICATIONS = setOf(
        "permission_prompt",
        "worker_permission_prompt",
        "elicitation_dialog",
        "agent_needs_input",
    )

    /**
     * A reading of `~/.claude/sessions/<pid>.json`. [status] is Claude's own field
     * (`busy` | `shell` | `idle` | `waiting`, or null/unknown on an older CLI);
     * [alive] is whether the pid still exists.
     */
    data class SessionSignal(val status: String?, val statusUpdatedAt: Long, val alive: Boolean)

    /**
     * Hook event → the state it establishes. Unknown events contribute nothing.
     *
     * `SessionStart` needs its [source] to be read correctly. Restarting the IDE resumes
     * each saved conversation, which fires `SessionStart` again — and mapping that to
     * [ClaudeStatus.IDLE] wholesale meant a chat you had left finished came back marked as
     * never having run a turn. The restart is the IDE's business, not the conversation's:
     *
     *  - `startup` / `clear` — genuinely nothing has run yet → [ClaudeStatus.IDLE]
     *  - `resume` — an existing conversation picked back up, sitting at the prompt with its
     *    last turn behind it → [ClaudeStatus.FINISHED]
     *  - `compact` — fires mid-conversation, often mid-turn. It says nothing about whether
     *    Claude is working, so it establishes nothing and leaves the current state alone.
     *
     * A null [source] (an older hook script, or a file written before this was recorded)
     * falls back to [ClaudeStatus.IDLE], which is the previous behaviour.
     */
    fun fromHookEvent(
        event: String,
        source: String? = null,
        notificationType: String? = null,
        reason: String? = null,
    ): ClaudeStatus? = when (event) {
        "UserPromptSubmit", "PreToolUse", "PostToolUse" -> ClaudeStatus.WORKING

        // Codex emits a dedicated edge before showing an approval dialog.
        "PermissionRequest" -> ClaudeStatus.WAITING

        // Only the types that mean a person is blocked — see [BLOCKING_NOTIFICATIONS] for
        // why this is an allowlist and what the deny-list version got wrong. status-hook.sh
        // already declines to record the rest (writing one would destroy the edge
        // underneath); this covers files written before it did, and any other route the
        // event might arrive by. A blank type is an older hook script that recorded none, so
        // it keeps the previous meaning rather than silently changing what those files say.
        "Notification" -> when {
            notificationType.isNullOrBlank() -> ClaudeStatus.WAITING
            notificationType in BLOCKING_NOTIFICATIONS -> ClaudeStatus.WAITING
            else -> null
        }

        "Stop" -> ClaudeStatus.FINISHED

        // A turn that ended on an API error — rate limit, overloaded, billing. `Stop` does
        // not fire for these, so without this the session stays on its `UserPromptSubmit`
        // edge and the tab claims to be working. The turn is over and Claude is back at the
        // prompt, which is exactly what [ClaudeStatus.FINISHED] says; the enum has no state
        // for "ended badly", and inventing one would say more than the tab strip can carry.
        "StopFailure" -> ClaudeStatus.FINISHED

        "SessionStart" -> when (source) {
            // `fork` branches an existing conversation, so like `resume` it arrives with its
            // history behind it and a turn already run.
            "resume", "fork" -> ClaudeStatus.FINISHED
            "compact" -> null
            else -> ClaudeStatus.IDLE
        }

        // Not every `SessionEnd` is an ending. `clear` and `resume` are **in-place
        // replacements**: the process, the terminal and the tab all survive and only the
        // session id rotates. Painting those ✕ put a live conversation under a dead marker
        // until the pid-join in `ClaudeStatusStore.supersededSessions` caught up — and that
        // recovery exists precisely because this signal used to be thrown away. Establishing
        // nothing lets the hand-over happen without the tab ever lying.
        //
        // Everything else — `logout`, `prompt_input_exit`, `bypass_permissions_disabled`,
        // `other`, and a missing reason from an older hook script — really is the end.
        "SessionEnd" -> if (reason == "clear" || reason == "resume") null else ClaudeStatus.EXITED

        else -> null
    }

    /**
     * Claude's own `status` field → the state it establishes.
     *
     * The four values are the whole set — `["busy","shell","idle","waiting"]` in the CLI —
     * and they are computed as:
     *
     *     status = waitingForSomething ? "waiting"
     *            : (isLoading || delegatedActive) ? "busy"
     *            : "idle"
     *     if (status == "idle" && aBackgroundLocalBashIsStillRunning) status = "shell"
     *
     * Two things follow that are not obvious from the name of either value:
     *
     *  - **`busy` covers delegated work**, not just the model turn. That is what keeps it set
     *    while background agents run, and what [resolve]'s rule 4 relies on.
     *  - **`shell` is a flavour of `idle`**, not of `busy`. It is only ever produced when the
     *    status would otherwise be `idle`, and it means "at the prompt, with a detached
     *    background `Bash` still running" — a dev server, a watcher. A Bash *tool call* inside
     *    a turn reads as `busy`, because the turn is loading.
     *
     * `shell` is still surfaced as [ClaudeStatus.WORKING]: something the user started is
     * running, and the tab saying so is the useful reading. But it is not a turn in progress,
     * which is why rule 4 excludes it.
     */
    fun fromSessionStatus(status: String?): ClaudeStatus? = when (status) {
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
     *  3. **[FINISHED] survives a session file that says "back at the prompt".** Claude's
     *     session file has no way to say "a turn just completed" — it only knows `idle`, and
     *     `shell` for `idle` with a detached background `Bash` still up. So when either would
     *     downgrade a hook-established [ClaudeStatus.FINISHED], keep [ClaudeStatus.FINISHED];
     *     they describe the same underlying process state and the hook's is strictly more
     *     informative.
     *
     *     `shell` belongs here rather than under rule 4, and leaving it to rule 2 made the
     *     same real state paint two different ways depending on which file was written last:
     *
     *       Stop at t=2000, session `shell` at t=1000  → ✓, the response is over
     *       Stop at t=1000, session `shell` at t=2000  → ●, the response is equally over
     *
     *     Nothing about the tab differs between those — a turn has ended and a background
     *     command the user started is still running — so the indicator must not differ
     *     either. `shell` now settles to ✓ both ways, which is also what rule 4 already
     *     wanted: it excludes `shell` from its own protection precisely so a long-lived
     *     `npm run dev` can't pin a tab to ● for as long as the server is up.
     *  4. **A working session is not finished, however new the `Stop`.** `Stop` fires when
     *     the *response* ends, which is not when the session's work ends: background agents
     *     and a subagent fan-out keep running after it. Claude's own file is the only signal
     *     that knows about them, and it stays `busy` until they are all done. For example:
     *
     *       hook     Stop   ts=2_000              ← the response ended
     *       session  busy   statusUpdatedAt=1_000 ← delegated work is still active
     *
     *     The session reading is half an hour older and still the correct one, so this can't
     *     be a freshness check: Claude rewrites the file on state *changes*, so "old" says
     *     nothing about "stale". `busy` is only ever written by a live process describing
     *     itself, which is what makes it safe to believe over an edge that has been
     *     superseded by work the edge cannot see. [ClaudeStatusStore] later splits this
     *     conservative result back into main FINISHED + `bgN` when a current Claude CLI
     *     supplies an authoritative positive `background_tasks` count.
     */
    fun resolve(hook: HookSignal?, session: SessionSignal?, now: Long = 0L): ClaudeStatus? {
        val hookState = hook?.let { fromHookEvent(it.event, it.source, it.notificationType, it.reason) }
        val sessionState = session?.let { fromSessionStatus(it.status) }

        // Rule 1 — terminal states.
        if (hookState == ClaudeStatus.EXITED) return ClaudeStatus.EXITED
        if (session != null && !session.alive) return ClaudeStatus.EXITED

        if (hookState == null) return sessionState
        if (sessionState == null) return hookState

        // Rule 2 — newer wins.
        val winner = if (hook.ts >= session.statusUpdatedAt) hookState else sessionState

        // Rule 3 — a session file saying "back at the prompt" doesn't erase a hook-established
        // `finished`. Both of its ways of saying that are covered: `idle`, and `shell` — which
        // Claude only ever writes when the status would otherwise be `idle` (see
        // [fromSessionStatus]). `shell` has to be named explicitly because it *paints* as
        // WORKING, so it never arrives here as `winner == IDLE`.
        if (hookState == ClaudeStatus.FINISHED &&
            (winner == ClaudeStatus.IDLE || session.status == "shell")
        ) {
            return ClaudeStatus.FINISHED
        }

        // Rule 4 — a `Stop` edge doesn't finish a session Claude still reports as busy.
        // `busy` only, not every reading that paints as WORKING: `shell` is Claude *idle*
        // with a detached background Bash still running (see [fromSessionStatus]), and a
        // `Stop` is entitled to settle that one — otherwise a `npm run dev` left running in
        // the background would pin the tab to ● for as long as the server is up.
        if (winner == ClaudeStatus.FINISHED && session.status == "busy") return ClaudeStatus.WORKING

        return winner
    }
}
