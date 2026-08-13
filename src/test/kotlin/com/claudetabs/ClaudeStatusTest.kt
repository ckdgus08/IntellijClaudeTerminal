package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two pure pieces of the status indicator: the glyph decoration contract
 * (nothing decorated may ever reach a persistence path) and the hook/session-file
 * precedence rules.
 */
class ClaudeStatusTest {

    // ── StatusDecoration ──────────────────────────────────────────

    @Test fun decorate_prefixesGlyphAndSpace() {
        assertEquals("● backend", StatusDecoration.decorate("backend", ClaudeStatus.WORKING))
        assertEquals("⚠ frontend", StatusDecoration.decorate("frontend", ClaudeStatus.WAITING))
        assertEquals("✓ infra", StatusDecoration.decorate("infra", ClaudeStatus.FINISHED))
        assertEquals("○ test", StatusDecoration.decorate("test", ClaudeStatus.IDLE))
        assertEquals("✕ gone", StatusDecoration.decorate("gone", ClaudeStatus.EXITED))
    }

    @Test fun decorate_withNullStatus_leavesNameBare() {
        assertEquals("backend", StatusDecoration.decorate("backend", null))
    }

    @Test fun decorate_isIdempotent_neverStacksGlyphs() {
        // The status loop re-decorates from whatever the current title is; stacking would
        // produce "● ● ● backend" within a few ticks.
        var title = "backend"
        repeat(5) { title = StatusDecoration.decorate(title, ClaudeStatus.WORKING) }
        assertEquals("● backend", title)
    }

    @Test fun decorate_replacesAnExistingGlyph() {
        assertEquals("✓ backend", StatusDecoration.decorate("● backend", ClaudeStatus.FINISHED))
    }

    @Test fun strip_removesGlyphAndIsIdempotent() {
        assertEquals("backend", StatusDecoration.strip("● backend"))
        assertEquals("backend", StatusDecoration.strip("backend"))
        assertEquals("backend", StatusDecoration.strip(StatusDecoration.strip("⚠ backend")))
        assertEquals("", StatusDecoration.strip(null))
        assertEquals("", StatusDecoration.strip(""))
    }

    @Test fun strip_leavesAiAssistantOverlayGlyphsAlone() {
        // The AI Assistant's own overlay glyphs are a different character set, handled by
        // ClaudeTabsHelpers.isAiOverlayName. Stripping them here would make an overlay
        // title look like a legitimate user rename.
        assertEquals("✳ Claude Code", StatusDecoration.strip("✳ Claude Code"))
        assertEquals("⠂ rider-claude-tabs", StatusDecoration.strip("⠂ rider-claude-tabs"))
        assertEquals("* fix-auth-flow", StatusDecoration.strip("* fix-auth-flow"))
    }

    @Test fun strip_doesNotEatAGlyphThatIsThewholeName() {
        // No trailing space → not our prefix, so it's someone's actual tab name.
        assertEquals("●", StatusDecoration.strip("●"))
    }

    @Test fun decoratedNamesNeverSurviveIntoPersistence() {
        // The load-bearing invariant: whatever we put on screen, stripping gets the exact
        // name back, for every status.
        val base = "billing service migration"
        for (status in ClaudeStatus.entries) {
            assertEquals(base, StatusDecoration.strip(StatusDecoration.decorate(base, status)))
        }
    }

    @Test fun isDecorated_detectsOnlyOurPrefix() {
        assertTrue(StatusDecoration.isDecorated("● backend"))
        assertFalse(StatusDecoration.isDecorated("backend"))
        assertFalse(StatusDecoration.isDecorated("✳ Claude Code"))
    }

    @Test fun tooltip_readsAsNameThenState() {
        assertEquals("frontend — Waiting for input", StatusDecoration.tooltip("frontend", ClaudeStatus.WAITING))
        assertEquals("frontend — Working", StatusDecoration.tooltip("● frontend", ClaudeStatus.WORKING))
        assertEquals("frontend", StatusDecoration.tooltip("frontend", null))
    }

    // ── StatusResolver: event / status mapping ────────────────────

    @Test fun hookEvents_mapToStates() {
        assertEquals(ClaudeStatus.WORKING, StatusResolver.fromHookEvent("UserPromptSubmit"))
        assertEquals(ClaudeStatus.WAITING, StatusResolver.fromHookEvent("Notification"))
        assertEquals(ClaudeStatus.FINISHED, StatusResolver.fromHookEvent("Stop"))
        assertEquals(ClaudeStatus.IDLE, StatusResolver.fromHookEvent("SessionStart"))
        assertEquals(ClaudeStatus.EXITED, StatusResolver.fromHookEvent("SessionEnd"))
    }

    @Test fun subagentStop_isNotAFinishedTurn() {
        // A subagent finishing mid-run must not flip the tab to ✓.
        assertNull(StatusResolver.fromHookEvent("SubagentStop"))
        assertFalse("SubagentStop" in ClaudeSettingsPatcher.STATUS_EVENTS)
    }

    @Test fun claudeSessionStatuses_mapToStates() {
        assertEquals(ClaudeStatus.WORKING, StatusResolver.fromSessionStatus("busy"))
        // `shell` is a detached background Bash still running while Claude sits at the
        // prompt — see TurnEndSignalsTest. It shows as running; it is not mid-turn.
        assertEquals(ClaudeStatus.WORKING, StatusResolver.fromSessionStatus("shell"))
        assertEquals(ClaudeStatus.WAITING, StatusResolver.fromSessionStatus("waiting"))
        assertEquals(ClaudeStatus.IDLE, StatusResolver.fromSessionStatus("idle"))
        assertNull(StatusResolver.fromSessionStatus(null))
        assertNull(StatusResolver.fromSessionStatus("something-new-in-a-future-cli"))
    }

    // ── StatusResolver: precedence ────────────────────────────────

    private fun hook(event: String, ts: Long) = StatusResolver.HookSignal(event, ts)
    private fun session(status: String?, ts: Long, alive: Boolean = true) =
        StatusResolver.SessionSignal(status, ts, alive)

    @Test fun deadProcess_isExited_whateverEitherSignalSaid() {
        assertEquals(
            ClaudeStatus.EXITED,
            StatusResolver.resolve(hook("UserPromptSubmit", 500), session("busy", 400, alive = false)),
        )
    }

    @Test fun sessionEndHook_isTerminal_evenIfSessionFileLooksBusy() {
        // The session file lags a clean exit; SessionEnd is definitive.
        assertEquals(
            ClaudeStatus.EXITED,
            StatusResolver.resolve(hook("SessionEnd", 100), session("busy", 999)),
        )
    }

    @Test fun newerHookWins() {
        assertEquals(
            ClaudeStatus.WORKING,
            StatusResolver.resolve(hook("UserPromptSubmit", 200), session("idle", 100)),
        )
    }

    @Test fun newerSessionFileWins() {
        assertEquals(
            ClaudeStatus.WAITING,
            StatusResolver.resolve(hook("UserPromptSubmit", 100), session("waiting", 200)),
        )
    }

    @Test fun staleSessionIdle_doesNotDowngradeFinished() {
        // Claude's session file has no "a turn just completed" state — only `idle`. Letting
        // it win here would flip every ✓ back to ○ on the next tick.
        assertEquals(
            ClaudeStatus.FINISHED,
            StatusResolver.resolve(hook("Stop", 100), session("idle", 200)),
        )
    }

    @Test fun sessionWaiting_stillOverridesFinished() {
        // A permission prompt after a completed turn is a real transition, not the
        // idle/finished ambiguity — it must not be suppressed by the rule above.
        assertEquals(
            ClaudeStatus.WAITING,
            StatusResolver.resolve(hook("Stop", 100), session("waiting", 200)),
        )
    }

    @Test fun oneSidedSignals() {
        assertEquals(ClaudeStatus.WORKING, StatusResolver.resolve(hook("UserPromptSubmit", 1), null))
        assertEquals(ClaudeStatus.IDLE, StatusResolver.resolve(null, session("idle", 1)))
        assertNull(StatusResolver.resolve(null, null))
        // Unknown hook event + unknown session status = nothing to say.
        assertNull(StatusResolver.resolve(hook("PreCompact", 1), session("brand-new", 2)))
    }

    @Test fun sessionFileWithoutStatusField_fallsBackToHook() {
        // Older Claude CLIs wrote no `status`; the hooks still carry the state.
        assertEquals(
            ClaudeStatus.WORKING,
            StatusResolver.resolve(hook("UserPromptSubmit", 10), session(null, 999)),
        )
    }

    @Test fun equalTimestamps_preferTheHook() {
        // Hook edges carry strictly more information (finished vs idle), so ties go to them.
        assertEquals(
            ClaudeStatus.FINISHED,
            StatusResolver.resolve(hook("Stop", 500), session("idle", 500)),
        )
    }
}

/**
 * `SessionStart` has to be read together with its source.
 *
 * Restarting the IDE resumes every saved conversation, which fires `SessionStart` again.
 * Mapping that to Idle wholesale meant a chat left at ✓ came back marked ○ — as if it had
 * never run a turn. The restart is the IDE's business, not the conversation's.
 */
class SessionStartSourceTest {

    private fun hook(source: String?, ts: Long = 100) = StatusResolver.HookSignal("SessionStart", ts, source)

    @Test fun resumeKeepsTheConversationLookingFinished() {
        assertEquals(ClaudeStatus.FINISHED, StatusResolver.fromHookEvent("SessionStart", "resume"))
        assertEquals(ClaudeStatus.FINISHED, StatusResolver.resolve(hook("resume"), null))
    }

    @Test fun aGenuinelyNewSessionIsIdle() {
        assertEquals(ClaudeStatus.IDLE, StatusResolver.fromHookEvent("SessionStart", "startup"))
        assertEquals(ClaudeStatus.IDLE, StatusResolver.fromHookEvent("SessionStart", "clear"))
    }

    @Test fun compactEstablishesNothing() {
        // Fires mid-conversation, often mid-turn: it says nothing about whether Claude is
        // working, so it must not overwrite the state that is already showing.
        assertNull(StatusResolver.fromHookEvent("SessionStart", "compact"))
        assertEquals(
            ClaudeStatus.WORKING,
            StatusResolver.resolve(hook("compact", ts = 500), StatusResolver.SessionSignal("busy", 100, true)),
        )
    }

    @Test fun anAbsentSourceKeepsTheOldBehaviour() {
        // Hook files written before the source was recorded, and older deployed scripts.
        assertEquals(ClaudeStatus.IDLE, StatusResolver.fromHookEvent("SessionStart"))
        assertEquals(ClaudeStatus.IDLE, StatusResolver.fromHookEvent("SessionStart", null))
        assertEquals(ClaudeStatus.IDLE, StatusResolver.fromHookEvent("SessionStart", ""))
    }

    @Test fun sourceOnlyMattersForSessionStart() {
        // Nothing else carries one, and a stray value must not change their meaning.
        assertEquals(ClaudeStatus.WORKING, StatusResolver.fromHookEvent("UserPromptSubmit", "resume"))
        assertEquals(ClaudeStatus.EXITED, StatusResolver.fromHookEvent("SessionEnd", "resume"))
        assertEquals(ClaudeStatus.FINISHED, StatusResolver.fromHookEvent("Stop", "startup"))
    }

    @Test fun theRestartScenarioEndToEnd() {
        // Left finished; IDE restarts; the tab is respawned with `claude --resume`, which
        // fires SessionStart(resume) and Claude reports the session as idle.
        val afterRestart = StatusResolver.resolve(
            hook("resume", ts = 2_000),
            StatusResolver.SessionSignal("idle", 2_100, alive = true),
        )
        assertEquals("a finished chat must not come back as never-run", ClaudeStatus.FINISHED, afterRestart)
    }
}

/**
 * Which notifications actually mean someone is waiting.
 *
 * The filter used to be a deny-list that knew about the 60-second idle nudge and nothing
 * else, so the other eleven `notification_type` values all landed as ⚠ — and because the
 * status file holds one edge per session, each one overwrote the `Stop` or
 * `UserPromptSubmit` underneath it.
 *
 * `agent_completed` was the worst of them. It fires when a **background agent finishes**
 * ("<label> finished"), so a fan-out of five put five spurious prompts on the tab, each
 * erasing the edge that said what was really going on.
 */
class NotificationTypeTest {

    @Test fun theFourThatBlockYou() {
        for (type in listOf("permission_prompt", "worker_permission_prompt", "elicitation_dialog", "agent_needs_input")) {
            assertEquals(type, ClaudeStatus.WAITING, StatusResolver.fromHookEvent("Notification", notificationType = type))
        }
        assertEquals(
            setOf("permission_prompt", "worker_permission_prompt", "elicitation_dialog", "agent_needs_input"),
            StatusResolver.BLOCKING_NOTIFICATIONS,
        )
    }

    @Test fun everythingClaudeIsMerelyTellingYouEstablishesNothing() {
        for (type in listOf(
            "agent_completed",          // a background agent finished
            "elicitation_complete",     // the end of a wait…
            "elicitation_response",     // …and the answer to one
            "auth_success",             // "Claude Code login successful"
            "computer_use_enter",
            "computer_use_exit",
            "push_notification",
            StatusResolver.IDLE_NOTIFICATION,
        )) {
            assertNull(type, StatusResolver.fromHookEvent("Notification", notificationType = type))
        }
    }

    /**
     * An unrecognised type establishes nothing rather than defaulting to ⚠. A blocking type
     * we haven't listed is still caught by Claude's own session file, which reports `waiting`
     * for permission dialogs, elicitation and sandbox prompts alike — so a miss self-corrects,
     * while a false ⚠ destroys the edge it was written over.
     */
    @Test fun anUnknownTypeEstablishesNothing() {
        assertNull(StatusResolver.fromHookEvent("Notification", notificationType = "some_future_type"))
    }

    /**
     * A blank type is an older hook script that recorded none — not a type anyone decided
     * about. Those files keep their previous meaning rather than changing on disk.
     */
    @Test fun anUnrecordedTypeKeepsTheOldMeaning() {
        assertEquals(ClaudeStatus.WAITING, StatusResolver.fromHookEvent("Notification"))
        assertEquals(ClaudeStatus.WAITING, StatusResolver.fromHookEvent("Notification", notificationType = ""))
        assertEquals(ClaudeStatus.WAITING, StatusResolver.fromHookEvent("Notification", notificationType = "  "))
    }

    /** The fan-out, as it plays out: only the one that needs an answer changes the glyph. */
    @Test fun aFanOutOfAgentsDoesNotTurnTheTabIntoAPrompt() {
        val busy = StatusResolver.SessionSignal("busy", 1_000, alive = true)
        for (t in 3_000L..7_000L step 1_000) {
            val done = StatusResolver.HookSignal("Notification", ts = t, notificationType = "agent_completed")
            assertEquals(ClaudeStatus.WORKING, StatusResolver.resolve(done, busy))
        }
        val needs = StatusResolver.HookSignal("Notification", ts = 8_000, notificationType = "agent_needs_input")
        assertEquals(ClaudeStatus.WAITING, StatusResolver.resolve(needs, busy))
    }
}

/**
 * `Stop` is the end of a *response*, not the end of the session's work.
 *
 * Background agents and a subagent fan-out keep running past it, and Claude's own file is the
 * only signal that knows — its `busy` covers `delegatedActive`, not just the model turn.
 * Caught live on a session printing "waiting for 5 background agents to finish" under a ✓ tab:
 *
 *   hook     Stop   ts=1786258683693              ← 15:58:03, the response ended
 *   session  busy   statusUpdatedAt=1786256718513 ← 15:25:18, and still busy at 16:00
 *
 * The session reading is half an hour older and still the correct one, so this cannot be a
 * freshness check: Claude rewrites the file on state *changes*, which makes "old" say nothing
 * about "stale".
 */
class TurnEndSignalsTest {

    private fun stop(ts: Long) = StatusResolver.HookSignal("Stop", ts)
    private fun session(status: String, ts: Long, alive: Boolean = true) =
        StatusResolver.SessionSignal(status, ts, alive)

    @Test fun aBusySessionIsNotFinishedByAStopEdge() {
        assertEquals(
            ClaudeStatus.WORKING,
            StatusResolver.resolve(stop(1786258683693), session("busy", 1786256718513)),
        )
    }

    @Test fun theAgeOfTheBusyReadingIsIrrelevant() {
        for (age in listOf(1L, 60_000L, 30L * 60 * 1000, 6L * 60 * 60 * 1000)) {
            assertEquals(
                "busy from ${age}ms ago",
                ClaudeStatus.WORKING,
                StatusResolver.resolve(stop(10_000_000), session("busy", 10_000_000 - age)),
            )
        }
    }

    /**
     * `shell` is deliberately not covered by rule 4, and its name is why it looks like it
     * should be. Claude computes it only when the status would otherwise be `idle` — "at the
     * prompt, with a detached background Bash still running". Covering it would pin the tab
     * to ● for as long as someone's `npm run dev` stays up.
     *
     * Which makes the ordering irrelevant, and it used not to be: whichever of the two files
     * was written last decided the icon, so "response done, background shell alive" showed ✓
     * or ● depending on a race. Rule 3 covers `shell` now, so it settles either way.
     */
    @Test fun aDetachedBackgroundShellDoesNotOutrankTheStop() {
        assertEquals(ClaudeStatus.FINISHED, StatusResolver.resolve(stop(2_000), session("shell", 1_000)))
        assertEquals(ClaudeStatus.FINISHED, StatusResolver.resolve(stop(1_000), session("shell", 2_000)))
    }

    /** The user-visible statement of the same thing: one state, one icon, whatever the order. */
    @Test fun theIconDoesNotDependOnWhichFileWasWrittenLast() {
        for (sessionTs in listOf(1L, 999L, 1_000L, 1_001L, 5_000L, 400_000L)) {
            assertEquals(
                "Stop at 1000, shell at $sessionTs",
                ClaudeStatus.FINISHED,
                StatusResolver.resolve(stop(1_000), session("shell", sessionTs)),
            )
        }
    }

    /**
     * Only a `Stop` settles it. A background `Bash` running inside a turn reads as `busy`,
     * not `shell`, so this is the case where the user really did start something and walk
     * away — and until the turn ends the tab is still working.
     */
    @Test fun aShellWithNoFinishedEdgeStillPaintsAsRunning() {
        assertEquals(
            ClaudeStatus.WORKING,
            StatusResolver.resolve(StatusResolver.HookSignal("UserPromptSubmit", 1_000), session("shell", 2_000)),
        )
        assertEquals(ClaudeStatus.WORKING, StatusResolver.resolve(null, session("shell", 2_000)))
    }

    /** `StopFailure` ends a response the same way, so it settles a `shell` too. */
    @Test fun aFailedTurnAlsoSettlesADetachedShell() {
        assertEquals(
            ClaudeStatus.FINISHED,
            StatusResolver.resolve(StatusResolver.HookSignal("StopFailure", 1_000), session("shell", 2_000)),
        )
    }

    /** A dead process outranks all of it — rule 1 runs first. */
    @Test fun aShellOnADeadProcessIsStillDead() {
        assertEquals(
            ClaudeStatus.EXITED,
            StatusResolver.resolve(stop(1_000), session("shell", 2_000, alive = false)),
        )
    }

    @Test fun aDeadProcessIsStillDead() {
        assertEquals(
            ClaudeStatus.EXITED,
            StatusResolver.resolve(stop(2_000), session("busy", 1_000, alive = false)),
        )
    }

    @Test fun onceTheWorkIsDoneTheStopStands() {
        assertEquals(ClaudeStatus.FINISHED, StatusResolver.resolve(stop(1_000), session("idle", 2_000)))
    }

    @Test fun theWholeArc() {
        val busy = session("busy", 1_100)
        assertEquals(
            ClaudeStatus.WORKING,
            StatusResolver.resolve(StatusResolver.HookSignal("UserPromptSubmit", 1_000), busy),
        )
        // The response ends; five background agents carry on and Claude stays `busy`.
        assertEquals(ClaudeStatus.WORKING, StatusResolver.resolve(stop(5_000), busy))
        // The last one finishes and Claude finally writes `idle`.
        assertEquals(ClaudeStatus.FINISHED, StatusResolver.resolve(stop(5_000), session("idle", 400_000)))
    }

    // ── StopFailure ───────────────────────────────────────────────

    /**
     * A turn can end without a `Stop`. On an API error — rate limit, overloaded, billing —
     * Claude fires `StopFailure` **instead**, and nothing else marks the end.
     */
    @Test fun aTurnThatDiedOnAnApiErrorIsStillOver() {
        assertEquals(ClaudeStatus.FINISHED, StatusResolver.fromHookEvent("StopFailure"))
        assertTrue("StopFailure" in ClaudeSettingsPatcher.STATUS_EVENTS)

        val idle = session("idle", 6_000)
        assertEquals(
            ClaudeStatus.FINISHED,
            StatusResolver.resolve(StatusResolver.HookSignal("StopFailure", 5_000), idle),
        )
        // Without it subscribed to, the newest edge is still the prompt that opened the turn,
        // rule 3 has no `finished` to protect, and a turn that ran for minutes reads as one
        // that never started.
        assertEquals(
            ClaudeStatus.IDLE,
            StatusResolver.resolve(StatusResolver.HookSignal("UserPromptSubmit", 1_000), idle),
        )
    }

    /** An error ends the response, not the delegated work — so rule 4 covers it too. */
    @Test fun delegatedWorkOutlivesAFailedTurn() {
        assertEquals(
            ClaudeStatus.WORKING,
            StatusResolver.resolve(StatusResolver.HookSignal("StopFailure", 5_000), session("busy", 1_100)),
        )
    }
}

/**
 * Not every `SessionEnd` is an ending.
 *
 * `clear` and `resume` are in-place replacements: the process, the terminal and the tab all
 * survive and only the session id rotates. Painting those ✕ put a live conversation under a
 * dead marker until the pid-join in `ClaudeStatusStore.supersededSessions` caught up — and
 * that recovery exists precisely because this signal used to be thrown away.
 */
class SessionEndReasonTest {

    @Test fun aHandOverEstablishesNothing() {
        assertNull(StatusResolver.fromHookEvent("SessionEnd", reason = "clear"))
        assertNull(StatusResolver.fromHookEvent("SessionEnd", reason = "resume"))
    }

    @Test fun everyOtherReasonIsAnEnding() {
        for (reason in listOf("logout", "prompt_input_exit", "bypass_permissions_disabled", "other", null, "")) {
            assertEquals("reason=$reason", ClaudeStatus.EXITED, StatusResolver.fromHookEvent("SessionEnd", reason = reason))
        }
    }

    /** The old id's session file is gone, so a hand-over simply has no reading at all. */
    @Test fun aClearedSessionIsNotPaintedAsDead() {
        assertNull(StatusResolver.resolve(StatusResolver.HookSignal("SessionEnd", 1_000, reason = "clear"), null))
        assertEquals(
            ClaudeStatus.EXITED,
            StatusResolver.resolve(StatusResolver.HookSignal("SessionEnd", 1_000, reason = "logout"), null),
        )
    }

    /** A reason only means anything on SessionEnd. */
    @Test fun reasonOnlyMattersForSessionEnd() {
        assertEquals(ClaudeStatus.FINISHED, StatusResolver.fromHookEvent("Stop", reason = "clear"))
        assertEquals(ClaudeStatus.WORKING, StatusResolver.fromHookEvent("UserPromptSubmit", reason = "clear"))
    }
}
