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
        // `shell` is Claude running a Bash call — still mid-turn.
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
